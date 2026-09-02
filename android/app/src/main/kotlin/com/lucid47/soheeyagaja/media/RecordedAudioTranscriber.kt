package com.lucid47.soheeyagaja.media

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class RecordedAudioTranscriber(private val context: Context) {
    suspend fun transcribe(file: File): String {
        require(file.exists() && file.length() > 0L) { "전사할 음성 파일을 찾지 못했습니다." }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            error("저장된 음성 자동 전사는 Android 13 이상에서 사용할 수 있습니다.")
        }
        check(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            "이 기기에서 개인정보를 외부로 보내지 않는 기기 내 음성 인식을 사용할 수 없습니다."
        }

        val audioFormat = readAudioFormat(file)
        return supervisorScope {
            withContext(Dispatchers.Main.immediate) {
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val pipe = ParcelFileDescriptor.createPipe()
                val completed = CompletableDeferred<String>()
                val segments = mutableListOf<String>()
                var writer: Job? = null

                fun resultFrom(bundle: Bundle?): String? =
                    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onError(error: Int) {
                        if (!completed.isCompleted) completed.completeExceptionally(
                            IllegalStateException(recognitionErrorMessage(error)),
                        )
                    }

                    override fun onResults(results: Bundle?) {
                        if (!completed.isCompleted) completeWithText(completed, resultFrom(results), segments)
                    }

                    override fun onSegmentResults(segmentResults: Bundle) {
                        resultFrom(segmentResults)?.let(segments::add)
                    }

                    override fun onEndOfSegmentedSession() {
                        if (!completed.isCompleted) completeWithText(completed, null, segments)
                    }
                })

                try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, audioFormat.channelCount)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, audioFormat.sampleRate)
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    }
                    recognizer.startListening(intent)
                    writer = launch(Dispatchers.IO) {
                        pipe[0].close()
                        FileOutputStream(pipe[1].fileDescriptor).use { output -> decodeToPcm(file, output) }
                    }
                    withTimeout(MAX_TRANSCRIPTION_MILLIS) { completed.await() }
                } finally {
                    writer?.cancelAndJoin()
                    runCatching { pipe[0].close() }
                    runCatching { pipe[1].close() }
                    runCatching { recognizer.stopListening() }
                    recognizer.destroy()
                }
            }
        }
    }

    private fun completeWithText(
        completed: CompletableDeferred<String>,
        finalText: String?,
        segments: List<String>,
    ) {
        val text = finalText ?: segments.joinToString(" ").trim()
        if (text.isNotEmpty()) completed.complete(text)
        else completed.completeExceptionally(IllegalStateException("음성에서 전사할 내용을 찾지 못했습니다."))
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

    private suspend fun decodeToPcm(file: File, output: FileOutputStream) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("음성 트랙을 찾지 못했습니다.")
            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            val playbackStartedNanos = System.nanoTime()
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

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val targetNanos = playbackStartedNanos + bufferInfo.presentationTimeUs.coerceAtLeast(0L) * 1_000L
                        val waitNanos = targetNanos - System.nanoTime()
                        if (waitNanos > 0L) {
                            Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                        }
                        val buffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.get(bytes)
                        output.write(bytes)
                        output.flush()
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun recognitionErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "음성 데이터를 읽지 못했습니다."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "음성 인식 권한이 없습니다."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "기기의 음성 인식 엔진이 한국어를 지원하지 않습니다."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "한국어 음성 인식 모델을 사용할 수 없습니다."
        SpeechRecognizer.ERROR_NO_MATCH -> "음성에서 인식 가능한 문장을 찾지 못했습니다."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식 엔진이 사용 중입니다. 잠시 후 다시 시도해주세요."
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "기기 내 음성 인식 서비스가 응답하지 않습니다."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "인식할 음성이 충분하지 않습니다."
        else -> "음성 전사 중 오류가 발생했습니다. (코드 $error)"
    }

    private data class PcmFormat(val sampleRate: Int, val channelCount: Int)

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_TRANSCRIPTION_MILLIS = 30L * 60L * 1_000L
    }
}
