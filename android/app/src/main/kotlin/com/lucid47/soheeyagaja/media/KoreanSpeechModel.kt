package com.lucid47.soheeyagaja.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lucid47.soheeyagaja.MainActivity
import com.lucid47.soheeyagaja.R
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object KoreanSpeechModel {
    const val DOWNLOAD_SIZE_MB = 82
    const val MODEL_NAME = "vosk-model-small-ko-0.22"
    const val UNIQUE_WORK_NAME = "korean-speech-model-$MODEL_NAME"
    const val KEY_PROGRESS = "progress"
    const val KEY_MESSAGE = "message"
    const val KEY_ERROR = "error"

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private val cached = AtomicReference<File?>()
    private val waitMutex = Mutex()
    private val installMutex = Mutex()

    fun isReady(context: Context): Boolean {
        cached.get()?.takeIf(::isValidModel)?.let { return true }
        val directory = finalDirectory(context.applicationContext)
        return isValidModel(directory).also { ready ->
            if (ready) cached.set(directory)
        }
    }

    suspend fun ensureReady(context: Context, onProgress: (String) -> Unit): File =
        withContext(Dispatchers.IO) {
            waitMutex.withLock {
                val appContext = context.applicationContext
                finalDirectory(appContext).takeIf(::isValidModel)?.let {
                    cached.set(it)
                    return@withLock it
                }

                val workManager = WorkManager.getInstance(appContext)
                val active = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
                    .firstOrNull { !it.state.isFinished }
                val workId = active?.id ?: enqueueDownload(workManager)
                waitForDownload(workManager, workId, onProgress)

                finalDirectory(appContext).takeIf(::isValidModel)?.also(cached::set)
                    ?: error("한국어 전사 모델 설치를 확인하지 못했습니다.")
            }
        }

    private fun enqueueDownload(workManager: WorkManager): UUID {
        val request = OneTimeWorkRequestBuilder<KoreanSpeechModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request).result.get()
        return request.id
    }

    private suspend fun waitForDownload(
        workManager: WorkManager,
        workId: UUID,
        onProgress: (String) -> Unit,
    ) {
        var lastMessage: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val info = workManager.getWorkInfoById(workId).get()
                ?: error("한국어 전사 모델 다운로드 작업을 찾지 못했습니다.")
            val progress = info.progress.getInt(KEY_PROGRESS, -1)
            val message = info.progress.getString(KEY_MESSAGE) ?: when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "인터넷 연결을 기다리는 중..."
                WorkInfo.State.RUNNING -> if (progress >= 0) {
                    "한국어 전사 모델 내려받는 중 $progress%"
                } else {
                    "한국어 전사 모델을 준비하는 중..."
                }
                else -> null
            }
            if (message != null && message != lastMessage) {
                lastMessage = message
                onProgress(message)
            }
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> return
                WorkInfo.State.FAILED -> error(
                    info.outputData.getString(KEY_ERROR)
                        ?: "한국어 전사 모델을 내려받지 못했습니다.",
                )
                WorkInfo.State.CANCELLED -> error("한국어 전사 모델 다운로드가 취소되었습니다.")
                else -> delay(WORK_POLL_INTERVAL_MILLIS)
            }
        }
    }

    suspend fun install(context: Context, onProgress: (Int?, String) -> Unit) =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                if (isReady(context)) return@withLock
                val root = modelRoot(context)
                val finalDirectory = finalDirectory(context)
                val archive = File(root, "$MODEL_NAME.download")
                val stage = File(root, "$MODEL_NAME-staging")
                root.mkdirs()
                stage.deleteRecursively()
                stage.mkdirs()
                var installed = false
                try {
                    download(archive, onProgress)
                    onProgress(100, "한국어 전사 모델을 설치하는 중...")
                    val extracted = try {
                        unzip(archive, stage)
                        stage.walkTopDown().firstOrNull(::isValidModel)
                            ?: error("다운로드한 한국어 전사 모델의 형식이 올바르지 않습니다.")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        archive.delete()
                        throw error
                    }
                    finalDirectory.deleteRecursively()
                    if (!extracted.renameTo(finalDirectory)) {
                        extracted.copyRecursively(finalDirectory, overwrite = true)
                    }
                    check(isValidModel(finalDirectory)) { "한국어 전사 모델 설치를 완료하지 못했습니다." }
                    cached.set(finalDirectory)
                    installed = true
                } finally {
                    stage.deleteRecursively()
                    if (installed) archive.delete()
                }
            }
        }

    private suspend fun download(destination: File, onProgress: (Int?, String) -> Unit) {
        val existingBytes = destination.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "SoheeyaGaja-Android")
        if (existingBytes > 0L) connection.setRequestProperty("Range", "bytes=$existingBytes-")
        try {
            val responseCode = connection.responseCode
            if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && existingBytes > 0L) {
                return
            }
            check(responseCode in 200..299) {
                "한국어 전사 모델을 내려받지 못했습니다. (HTTP $responseCode)"
            }
            val resumed = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            if (!resumed && existingBytes > 0L) destination.delete()
            val initialBytes = if (resumed) existingBytes else 0L
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }?.plus(initialBytes) ?: -1L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination, resumed).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = initialBytes
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (totalBytes > 0L) {
                            val percent = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent >= lastPercent + 2) {
                                lastPercent = percent
                                onProgress(percent, "한국어 전사 모델 내려받는 중 $percent%")
                            }
                        } else {
                            onProgress(null, "한국어 전사 모델 내려받는 중...")
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
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

    private fun modelRoot(context: Context) = File(context.filesDir, "speech-models")

    private fun finalDirectory(context: Context) = File(modelRoot(context), MODEL_NAME)

    private fun isValidModel(directory: File): Boolean =
        directory.isDirectory &&
            File(directory, "am/final.mdl").isFile &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph/HCLr.fst").isFile

    private const val WORK_POLL_INTERVAL_MILLIS = 500L
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
}

class KoreanSpeechModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        createNotificationChannel()
        setForeground(foregroundInfo(null, "한국어 전사 모델 다운로드 준비 중"))
        return try {
            KoreanSpeechModel.install(applicationContext) { percent, message ->
                val progress = workDataOf(
                    KoreanSpeechModel.KEY_PROGRESS to (percent ?: -1),
                    KoreanSpeechModel.KEY_MESSAGE to message,
                )
                setProgressAsync(progress)
                notificationManager.notify(NOTIFICATION_ID, notification(percent, message))
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KoreanSpeechModel.KEY_ERROR to error.message.orEmpty()))
            }
        } finally {
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    private fun foregroundInfo(percent: Int?, message: String): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            notification(percent, message),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )

    private fun notification(percent: Int?, message: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("한국어 전사 모델 준비")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    Intent(applicationContext, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "음성 전사 모델", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private val notificationManager: NotificationManager
        get() = applicationContext.getSystemService(NotificationManager::class.java)

    private companion object {
        const val CHANNEL_ID = "speech_model_download"
        const val NOTIFICATION_ID = 4202
        const val MAX_RETRY_COUNT = 3
    }
}
