package com.lucid47.soheeyagaja.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lucid47.soheeyagaja.MainActivity
import com.lucid47.soheeyagaja.R
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceRecordingStatus { IDLE, RECORDING, PAUSED, FINISHED, ERROR }

data class VoiceRecordingState(
    val status: VoiceRecordingStatus = VoiceRecordingStatus.IDLE,
    val filePath: String? = null,
    val durationMillis: Long = 0L,
    val errorMessage: String? = null,
)

class VoiceRecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var pausedAt = 0L
    private var totalPaused = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_FILE_PATH).orEmpty())
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            ACTION_CANCEL -> cancelRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recorder?.release()
        recorder = null
        super.onDestroy()
    }

    private fun startRecording(path: String) {
        if (path.isBlank() || recorder != null) return
        runCatching {
            val file = File(path).apply { parentFile?.mkdirs() }
            val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(128_000)
            next.setAudioSamplingRate(44_100)
            next.setOutputFile(file.absolutePath)
            next.prepare()
            next.start()
            recorder = next
            startedAt = System.currentTimeMillis()
            pausedAt = 0L
            totalPaused = 0L
            _state.value = VoiceRecordingState(VoiceRecordingStatus.RECORDING, file.absolutePath)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("음성 메모 녹음 중")
                .setContentText("화면이 꺼져도 녹음을 계속합니다.")
                .setOngoing(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }.onFailure { error ->
            recorder?.release()
            recorder = null
            _state.value = VoiceRecordingState(
                status = VoiceRecordingStatus.ERROR,
                filePath = path,
                errorMessage = error.message ?: "녹음을 시작하지 못했습니다.",
            )
            stopSelf()
        }
    }

    private fun pauseRecording() {
        if (_state.value.status != VoiceRecordingStatus.RECORDING) return
        runCatching { recorder?.pause() }
            .onSuccess {
                pausedAt = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    status = VoiceRecordingStatus.PAUSED,
                    durationMillis = elapsedDuration(),
                )
            }
            .onFailure(::recordError)
    }

    private fun resumeRecording() {
        if (_state.value.status != VoiceRecordingStatus.PAUSED) return
        runCatching { recorder?.resume() }
            .onSuccess {
                totalPaused += (System.currentTimeMillis() - pausedAt).coerceAtLeast(0L)
                pausedAt = 0L
                _state.value = _state.value.copy(status = VoiceRecordingStatus.RECORDING)
            }
            .onFailure(::recordError)
    }

    private fun stopRecording() {
        val path = _state.value.filePath
        if (recorder == null) return
        runCatching { recorder?.stop() }
            .onSuccess {
                recorder?.release()
                recorder = null
                _state.value = VoiceRecordingState(
                    status = VoiceRecordingStatus.FINISHED,
                    filePath = path,
                    durationMillis = elapsedDuration(),
                )
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            .onFailure(::recordError)
    }

    private fun cancelRecording() {
        val path = _state.value.filePath
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        path?.let(::File)?.delete()
        _state.value = VoiceRecordingState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun elapsedDuration(): Long {
        if (startedAt == 0L) return 0L
        val end = if (pausedAt > 0L) pausedAt else System.currentTimeMillis()
        return (end - startedAt - totalPaused).coerceAtLeast(0L)
    }

    private fun recordError(error: Throwable) {
        _state.value = _state.value.copy(
            status = VoiceRecordingStatus.ERROR,
            errorMessage = error.message ?: "녹음 중 오류가 발생했습니다.",
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "음성 메모 녹음", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "voice-memo-recording"
        private const val NOTIFICATION_ID = 47
        private const val EXTRA_FILE_PATH = "filePath"
        private const val ACTION_START = "com.lucid47.soheeyagaja.voice.START"
        private const val ACTION_PAUSE = "com.lucid47.soheeyagaja.voice.PAUSE"
        private const val ACTION_RESUME = "com.lucid47.soheeyagaja.voice.RESUME"
        private const val ACTION_STOP = "com.lucid47.soheeyagaja.voice.STOP"
        private const val ACTION_CANCEL = "com.lucid47.soheeyagaja.voice.CANCEL"

        private val _state = MutableStateFlow(VoiceRecordingState())
        val state: StateFlow<VoiceRecordingState> = _state.asStateFlow()

        fun start(context: Context, path: String) {
            ContextCompatCompat.startForegroundService(
                context,
                Intent(context, VoiceRecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_FILE_PATH, path),
            )
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun cancel(context: Context) = send(context, ACTION_CANCEL)

        fun resetFinished() {
            if (_state.value.status == VoiceRecordingStatus.FINISHED) _state.value = VoiceRecordingState()
        }

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, VoiceRecordingService::class.java).setAction(action))
        }
    }
}

private object ContextCompatCompat {
    fun startForegroundService(context: Context, intent: Intent) {
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
}
