package com.lucid47.soheeyagaja.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object BackupScheduler {
    private const val PREFS = "android-backup"
    private const val URI = "targetUri"
    private const val LIST_IDS = "listIds"
    private const val ENABLED = "enabled"
    private const val LAST_BACKUP = "lastBackup"
    private const val JOB_ID = 4707

    fun configure(context: Context, uri: Uri, listIds: Set<Long>, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(URI, uri.toString())
            .putString(LIST_IDS, listIds.sorted().joinToString(","))
            .putBoolean(ENABLED, enabled)
            .apply()
        schedule(context, enabled)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        schedule(context, enabled)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun targetUri(context: Context): Uri? = prefs(context).getString(URI, null)?.let(Uri::parse)

    fun targetListIds(context: Context): Set<Long> = prefs(context).getString(LIST_IDS, "")
        .orEmpty().split(',').mapNotNull(String::toLongOrNull).toSet()

    fun lastBackup(context: Context): Long = prefs(context).getLong(LAST_BACKUP, 0L)

    suspend fun runNow(context: Context): BackupPreview {
        val uri = requireNotNull(targetUri(context)) { "먼저 자동 백업 파일을 지정해주세요." }
        val ids = targetListIds(context)
        val result = BackupArchiveService(context).writeBackup(uri, ids)
        prefs(context).edit().putLong(LAST_BACKUP, System.currentTimeMillis()).apply()
        return result
    }

    private fun schedule(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutomaticBackupJobService::class.java))
            .setPeriodic(15 * 60 * 1_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class AutomaticBackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        if (!BackupScheduler.isEnabled(this)) return false
        scope.launch {
            val needsReschedule = runCatching { BackupScheduler.runNow(this@AutomaticBackupJobService) }.isFailure
            jobFinished(params, needsReschedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
