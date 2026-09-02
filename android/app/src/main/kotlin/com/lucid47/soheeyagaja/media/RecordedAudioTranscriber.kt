package com.lucid47.soheeyagaja.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

class RecordedAudioTranscriber(private val context: Context) {
    suspend fun transcribe(file: File, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(file.exists() && file.length() > 0L) { "전사할 음성 파일을 찾지 못했습니다." }
        onProgress("한국어 전사 모델을 확인하는 중...")
        val modelDirectory = KoreanSpeechModel.ensureReady(context, onProgress)
        onProgress("음성을 글로 변환하는 중...")
        LibVosk.setLogLevel(LogLevel.WARNINGS)

        val format = readAudioFormat(file)
        check(format.channelCount == 1) { "현재는 한 채널 음성만 전사할 수 있습니다." }
        Model(modelDirectory.absolutePath).use { model ->
            Recognizer(model, format.sampleRate.toFloat()).use { recognizer ->
                decodePcm16(file) { bytes -> recognizer.acceptWaveForm(bytes, bytes.size) }
                val text = JSONObject(recognizer.finalResult).optString("text").trim()
                check(text.isNotEmpty()) { "음성에서 인식 가능한 한국어 문장을 찾지 못했습니다." }
                text
            }
        }
    }

    private fun readAudioFormat(file: File): PcmFormat {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("음성 트랙을 찾지 못했습니다.")
            PcmFormat(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodePcm16(file: File, consume: (ByteArray) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("음성 트랙을 찾지 못했습니다.")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val encoding = codec.outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        check(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                            "이 기기의 음성 디코더가 지원하지 않는 PCM 형식을 반환했습니다."
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            buffer.get(bytes)
                            consume(bytes)
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private data class PcmFormat(val sampleRate: Int, val channelCount: Int)

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
    }
}

private object KoreanSpeechModel {
    private const val MODEL_NAME = "vosk-model-small-ko-0.22"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private val cached = AtomicReference<File?>()
    private val mutex = Mutex()

    suspend fun ensureReady(context: Context, onProgress: (String) -> Unit): File =
        withContext(Dispatchers.IO) {
            mutex.withLock { ensureReadyLocked(context, onProgress) }
        }

    private fun ensureReadyLocked(context: Context, onProgress: (String) -> Unit): File {
        cached.get()?.takeIf(::isValidModel)?.let { return it }
        val root = File(context.filesDir, "speech-models")
        val finalDirectory = File(root, MODEL_NAME)
        if (isValidModel(finalDirectory)) {
            cached.set(finalDirectory)
            return finalDirectory
        }

        root.mkdirs()
        val archive = File(root, "$MODEL_NAME.download")
        val stage = File(root, "$MODEL_NAME-staging")
        archive.delete()
        stage.deleteRecursively()
        stage.mkdirs()
        try {
            download(archive, onProgress)
            onProgress("한국어 전사 모델을 설치하는 중...")
            unzip(archive, stage)
            val extracted = stage.walkTopDown().firstOrNull(::isValidModel)
                ?: error("다운로드한 한국어 전사 모델의 형식이 올바르지 않습니다.")
            finalDirectory.deleteRecursively()
            if (!extracted.renameTo(finalDirectory)) {
                extracted.copyRecursively(finalDirectory, overwrite = true)
            }
            check(isValidModel(finalDirectory)) { "한국어 전사 모델 설치를 완료하지 못했습니다." }
            cached.set(finalDirectory)
            return finalDirectory
        } finally {
            archive.delete()
            stage.deleteRecursively()
        }
    }

    private fun download(destination: File, onProgress: (String) -> Unit) {
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "SoheeyaGaja-Android")
        try {
            check(connection.responseCode in 200..299) {
                "한국어 전사 모델을 내려받지 못했습니다. (HTTP ${connection.responseCode})"
            }
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent >= lastPercent + 5) {
                                lastPercent = percent
                                onProgress("한국어 전사 모델 내려받는 중 $percent%")
                            }
                        } else {
                            onProgress("한국어 전사 모델 내려받는 중...")
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(archive: File, target: File) {
        val targetPath = target.canonicalPath + File.separator
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(target, entry.name)
                require(output.canonicalPath.startsWith(targetPath)) { "안전하지 않은 모델 압축 파일입니다." }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use(zip::copyTo)
                }
                zip.closeEntry()
            }
        }
    }

    private fun isValidModel(directory: File): Boolean =
        directory.isDirectory &&
            File(directory, "am/final.mdl").isFile &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph/HCLr.fst").isFile
}
