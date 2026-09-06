package com.lucid47.soheeyagaja.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lucid47.soheeyagaja.R
import com.lucid47.soheeyagaja.data.AppDatabase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioTranscriptionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("audioId", -1)
        val dao = AppDatabase.get(applicationContext).attachmentDao()
        val audio = dao.getAudioById(id) ?: return Result.success()
        if (audio.filePath != inputData.getString("filePath")) return Result.success()
        return try {
            setForeground(getForegroundInfo())
            dao.setTranscriptionState(id, "QUEUED")
            recognitionLock.withLock {
                dao.setTranscriptionState(id, "RUNNING")
                val result = RecordedAudioTranscriber(applicationContext).transcribeDetailed(File(audio.filePath))
                if (dao.getAudioById(id)?.filePath == audio.filePath) dao.completeTranscription(id, result.text, result.wordsJson)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            dao.setTranscriptionState(id, "FAILED", error.message ?: "전사에 실패했습니다.")
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = "audio-transcription"
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "음성 메모 전사", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.app_icon).setContentTitle("음성 메모를 글로 옮기는 중")
            .setContentText("다른 화면을 사용해도 작업을 계속합니다.").setOngoing(true).build()
        return ForegroundInfo(4800 + (inputData.getLong("audioId", 0) % 1000).toInt(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private val recognitionLock = Mutex()
        fun enqueue(context: Context, audioId: Long, filePath: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("audio-transcription-$audioId", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AudioTranscriptionWorker>().setInputData(workDataOf("audioId" to audioId, "filePath" to filePath)).build())
        }
    }
}
