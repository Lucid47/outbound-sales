package com.lucid47.soheeyagaja.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

class RecordedAudioTranscriber(private val context: Context) {
    fun isModelReady(): Boolean = KoreanSpeechModel.isReady(context)

    suspend fun transcribe(file: File, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(file.exists() && file.length() > 0L) { "전사할 음성 파일을 찾지 못했습니다." }
        onProgress("한국어 전사 모델을 확인하는 중...")
        val modelDirectory = KoreanSpeechModel.ensureReady(context, onProgress)
        onProgress("음성을 글로 변환하는 중...")
        LibVosk.setLogLevel(LogLevel.WARNINGS)

        Model(modelDirectory.absolutePath).use { model ->
            var recognizer: Recognizer? = null
            val completedSegments = mutableListOf<String>()
            try {
                decodePcm16(file) { bytes, sampleRate, channelCount ->
                    val activeRecognizer = recognizer ?: Recognizer(model, sampleRate.toFloat()).also {
                        recognizer = it
                    }
                    val mono = downmixPcm16(bytes, channelCount)
                    if (mono.isNotEmpty() && activeRecognizer.acceptWaveForm(mono, mono.size)) {
                        extractVoskText(activeRecognizer.result)?.let(completedSegments::add)
                    }
                }
                val activeRecognizer = checkNotNull(recognizer) { "음성 데이터가 비어 있습니다." }
                extractVoskText(activeRecognizer.finalResult)?.let(completedSegments::add)
                val text = completedSegments.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                check(text.isNotEmpty()) { "음성에서 인식 가능한 한국어 문장을 찾지 못했습니다." }
                text
            } finally {
                recognizer?.close()
            }
        }
    }

    private suspend fun decodePcm16(
        file: File,
        consume: (bytes: ByteArray, sampleRate: Int, channelCount: Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("음성 트랙을 찾지 못했습니다.")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
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
                        val outputFormat = codec.outputFormat
                        val encoding = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        check(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                            "이 기기의 음성 디코더가 지원하지 않는 PCM 형식을 반환했습니다."
                        }
                        sampleRate = outputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = outputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    }
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            buffer.get(bytes)
                            consume(bytes, sampleRate, channelCount)
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

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
    }
}

internal fun extractVoskText(json: String): String? =
    runCatching { JSONObject(json).optString("text").replace(Regex("\\s+"), " ").trim() }
        .getOrNull()
        ?.takeIf(String::isNotBlank)

internal fun downmixPcm16(bytes: ByteArray, channelCount: Int): ByteArray {
    require(channelCount > 0) { "음성 채널 수가 올바르지 않습니다." }
    if (channelCount == 1) return bytes
    val bytesPerFrame = channelCount * 2
    val frameCount = bytes.size / bytesPerFrame
    if (frameCount == 0) return byteArrayOf()
    val output = ByteArray(frameCount * 2)
    for (frame in 0 until frameCount) {
        var sum = 0
        for (channel in 0 until channelCount) {
            val offset = frame * bytesPerFrame + channel * 2
            val sample = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort().toInt()
            sum += sample
        }
        val mixed = (sum / channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        output[frame * 2] = (mixed and 0xff).toByte()
        output[frame * 2 + 1] = ((mixed ushr 8) and 0xff).toByte()
    }
    return output
}
