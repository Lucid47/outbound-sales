package com.lucid47.soheeyagaja.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.lucid47.soheeyagaja.data.AppDatabase
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupListPreview(val id: Long, val name: String, val customerCount: Int)

data class BackupPreview(
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val lists: List<BackupListPreview>,
)

enum class RestoreMode { MERGE_SELECTED, REPLACE_ALL }

class BackupArchiveService(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.get(context),
) {
    suspend fun writeBackup(uri: Uri, selectedListIds: Set<Long>): BackupPreview = withContext(Dispatchers.IO) {
        require(selectedListIds.isNotEmpty()) { "백업할 고객리스트를 선택해주세요." }
        val manifest = database.withTransaction { buildManifest(selectedListIds) }
        val recoveryDirectory = File(context.filesDir, "backup-recovery").apply { mkdirs() }
        val staged = File(recoveryDirectory, "${UUID.randomUUID()}.partial")
        val complete = File(recoveryDirectory, "${staged.nameWithoutExtension}.zip")
        try {
        staged.outputStream().use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                appendMedia(zip, manifest.getJSONArray("photo_memos"))
                appendMedia(zip, manifest.getJSONArray("audio_memos"))
            }
        }
        check(staged.renameTo(complete)) { "로컬 복구본 저장에 실패했습니다." }
        // Keep a complete local recovery copy even if the document provider truncates an upload.
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "백업 파일을 열지 못했습니다." }
            complete.inputStream().use { it.copyTo(output) }
        }
        recoveryDirectory.listFiles()?.filter { it != complete && it.extension == "zip" }
            ?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.delete() }
        } catch (error: Exception) {
            throw java.io.IOException("백업을 완료하지 못했습니다. 로컬 복구본을 확인해주세요.", error)
        } finally {
            staged.delete()
        }
        preview(manifest)
    }

    suspend fun inspect(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        preview(readManifest(uri))
    }

    fun localRecoveryCopies(): List<File> = File(context.filesDir, "backup-recovery").listFiles()
        ?.filter { it.extension == "zip" }?.sortedByDescending { it.lastModified() }.orEmpty()

    suspend fun restore(
        uri: Uri,
        selectedListIds: Set<Long>,
        mode: RestoreMode,
    ): Int = withContext(Dispatchers.IO) {
        val extraction = extractArchive(uri)
        try {
            val manifest = JSONObject(File(extraction, MANIFEST_ENTRY).readText())
            require(manifest.optString("format") == FORMAT) { "소희야 가자 백업 파일이 아닙니다." }
            require(manifest.optInt("schemaVersion") in 1..database.openHelper.readableDatabase.version) {
                "지원하지 않는 백업 버전입니다. 앱 업데이트를 확인해주세요."
            }
            val available = manifest.getJSONArray("customer_lists").ids()
            val chosen = if (mode == RestoreMode.REPLACE_ALL) available else selectedListIds.intersect(available)
            require(chosen.isNotEmpty()) { "복원할 고객리스트를 선택해주세요." }
            database.withTransaction { restoreManifest(manifest, extraction, chosen, mode) }
            if (mode == RestoreMode.REPLACE_ALL) manifest.optJSONObject("displaySettings")?.let { settings ->
                val editor = context.getSharedPreferences("display-settings", Context.MODE_PRIVATE).edit()
                settings.optString("theme").takeIf { it in setOf("SYSTEM", "LIGHT", "DARK") }?.let { editor.putString("theme", it) }
                listOf("phone", "address", "notes", "custom").forEach { key -> if (settings.has(key)) editor.putBoolean(key, settings.getBoolean(key)) }
                editor.apply()
            }
            chosen.size
        } finally {
            extraction.deleteRecursively()
        }
    }

    private fun buildManifest(selectedListIds: Set<Long>): JSONObject {
        val db = database.openHelper.readableDatabase
        val ids = selectedListIds.sorted().joinToString(",")
        val customerFilter = "customerId IN (SELECT id FROM customers WHERE listId IN ($ids))"
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", db.version)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("customer_lists", queryRows(db, "SELECT * FROM customer_lists WHERE id IN ($ids)"))
            .put("customers", queryRows(db, "SELECT * FROM customers WHERE listId IN ($ids)"))
            .put("customer_custom_fields", queryRows(db, "SELECT * FROM customer_custom_fields WHERE $customerFilter"))
            .put("contact_logs", queryRows(db, "SELECT * FROM contact_logs WHERE listId IN ($ids)"))
            .put("visit_logs", queryRows(db, "SELECT * FROM visit_logs WHERE listId IN ($ids)"))
            .put("visit_schedules", queryRows(db, "SELECT * FROM visit_schedules WHERE listId IN ($ids)"))
            .put("visit_schedule_items", queryRows(db, "SELECT * FROM visit_schedule_items WHERE listId IN ($ids)"))
            .put("process_status_logs", queryRows(db, "SELECT * FROM process_status_logs WHERE listId IN ($ids)"))
            .put("dashboard_statuses", queryRows(db, "SELECT * FROM dashboard_statuses"))
            .put("dashboard_settings", queryRows(db, "SELECT * FROM dashboard_settings"))
            .put("management_periods", queryRows(db, "SELECT * FROM management_periods"))
        manifest.put("photo_memos", mediaRows(db, "photo_memos", ids, "photos"))
        manifest.put("audio_memos", mediaRows(db, "audio_memos", ids, "audio"))
        manifest.put("displaySettings", JSONObject(context.getSharedPreferences("display-settings", Context.MODE_PRIVATE).all))
        return manifest
    }

    private fun mediaRows(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        ids: String,
        directory: String,
    ): JSONArray {
        val rows = queryRows(db, "SELECT * FROM $table WHERE listId IN ($ids)")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val path = row.optString("filePath")
            val extension = File(path).extension.ifBlank { if (directory == "photos") "jpg" else "m4a" }
            row.put("archivePath", "media/$directory/${row.getLong("id")}.$extension")
        }
        return rows
    }

    private fun appendMedia(zip: ZipOutputStream, rows: JSONArray) {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val source = File(row.optString("filePath"))
            require(source.isFile) { "사진 또는 음성 파일이 없어 백업을 중단했습니다." }
            zip.putNextEntry(ZipEntry(row.getString("archivePath")))
            source.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun readManifest(uri: Uri): JSONObject {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "백업 파일을 열지 못했습니다." }
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == MANIFEST_ENTRY) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        val manifest = JSONObject(text)
                        require(manifest.optString("format") == FORMAT) { "소희야 가자 백업 파일이 아닙니다." }
                        return manifest
                    }
                    zip.closeEntry()
                }
            }
        }
        error("백업 정보가 없습니다.")
    }

    private fun extractArchive(uri: Uri): File {
        val root = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "백업 파일을 열지 못했습니다." }
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = File(root, entry.name)
                    require(destination.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "잘못된 백업 경로입니다." }
                    if (!entry.isDirectory) {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().buffered().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
        return root
    }

    private fun restoreManifest(
        manifest: JSONObject,
        extraction: File,
        chosenListIds: Set<Long>,
        mode: RestoreMode,
    ): Int {
        val db = database.openHelper.writableDatabase
        if (mode == RestoreMode.REPLACE_ALL) {
            db.execSQL("DELETE FROM customer_lists")
            db.execSQL("DELETE FROM dashboard_statuses")
            db.execSQL("DELETE FROM dashboard_settings")
        }
        if (mode == RestoreMode.REPLACE_ALL) restoreGlobalSettings(db, manifest)
        // Historical snapshots are append-only; an older backup must not erase them.
        manifest.optJSONArray("management_periods")?.forEachObject { row ->
            db.insert("management_periods", SQLiteDatabase.CONFLICT_IGNORE, row.toContentValues())
        }
        val statusMap = mutableMapOf<String, String>()
        if (mode == RestoreMode.MERGE_SELECTED) {
            manifest.getJSONArray("dashboard_statuses").forEachObject { row ->
                db.query("SELECT id FROM dashboard_statuses WHERE name = ? ORDER BY orderIndex LIMIT 1", arrayOf(row.getString("name")))
                    .use { cursor -> if (cursor.moveToFirst()) statusMap[row.getString("id")] = cursor.getString(0) }
            }
        }
        val listMap = mutableMapOf<Long, Long>()
        manifest.getJSONArray("customer_lists").forEachObject { row ->
            val oldId = row.getLong("id")
            if (oldId !in chosenListIds) return@forEachObject
            val values = row.toContentValues(excluding = setOf("id"))
            values.put("name", uniqueRestoredListName(db, row.getString("name")))
            listMap[oldId] = checkedInsert(db, "customer_lists", values)
        }
        val customerMap = mutableMapOf<Long, Long>()
        manifest.getJSONArray("customers").forEachObject { row ->
            val nextListId = listMap[row.getLong("listId")] ?: return@forEachObject
            val oldId = row.getLong("id")
            val values = row.toContentValues(excluding = setOf("id"))
            values.put("listId", nextListId)
            values.putNull("contactIdentifier")
            if (mode == RestoreMode.MERGE_SELECTED) {
                val mappedStatus = statusMap[row.optString("dashboardStatusId")]
                if (mappedStatus == null) values.putNull("dashboardStatusId") else values.put("dashboardStatusId", mappedStatus)
            }
            customerMap[oldId] = checkedInsert(db, "customers", values)
        }
        restoreCustomerRows(db, manifest, "customer_custom_fields", customerMap, listMap)
        restoreCustomerRows(db, manifest, "contact_logs", customerMap, listMap)
        restoreCustomerRows(db, manifest, "visit_logs", customerMap, listMap)
        restoreCustomerRows(db, manifest, "process_status_logs", customerMap, listMap)
        restoreMediaRows(db, manifest, extraction, "photo_memos", "photos", customerMap, listMap)
        restoreMediaRows(db, manifest, extraction, "audio_memos", "audio", customerMap, listMap)

        val scheduleMap = mutableMapOf<Long, Long>()
        manifest.getJSONArray("visit_schedules").forEachObject { row ->
            val nextListId = listMap[row.getLong("listId")] ?: return@forEachObject
            val oldId = row.getLong("id")
            val values = row.toContentValues(excluding = setOf("id"))
            values.put("listId", nextListId)
            scheduleMap[oldId] = checkedInsert(db, "visit_schedules", values)
        }
        manifest.getJSONArray("visit_schedule_items").forEachObject { row ->
            val nextListId = listMap[row.getLong("listId")] ?: return@forEachObject
            val nextCustomerId = customerMap[row.getLong("customerId")] ?: return@forEachObject
            val nextScheduleId = scheduleMap[row.getLong("scheduleId")] ?: return@forEachObject
            val values = row.toContentValues(excluding = setOf("id"))
            values.put("listId", nextListId)
            values.put("customerId", nextCustomerId)
            values.put("scheduleId", nextScheduleId)
            checkedInsert(db, "visit_schedule_items", values)
        }
        return listMap.size
    }

    private fun restoreGlobalSettings(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        manifest: JSONObject,
    ) {
        db.execSQL("DELETE FROM dashboard_statuses")
        manifest.getJSONArray("dashboard_statuses").forEachObject { row ->
            db.insert("dashboard_statuses", SQLiteDatabase.CONFLICT_REPLACE, row.toContentValues())
        }
        manifest.getJSONArray("dashboard_settings").forEachObject { row ->
            db.insert("dashboard_settings", SQLiteDatabase.CONFLICT_REPLACE, row.toContentValues())
        }
    }

    private fun restoreCustomerRows(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        manifest: JSONObject,
        table: String,
        customerMap: Map<Long, Long>,
        listMap: Map<Long, Long>,
    ) {
        manifest.getJSONArray(table).forEachObject { row ->
            val nextCustomerId = customerMap[row.getLong("customerId")] ?: return@forEachObject
            val values = row.toContentValues(excluding = setOf("id"))
            values.put("customerId", nextCustomerId)
            if (row.has("listId")) values.put("listId", listMap[row.getLong("listId")] ?: return@forEachObject)
            checkedInsert(db, table, values)
        }
    }

    private fun restoreMediaRows(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        manifest: JSONObject,
        extraction: File,
        table: String,
        directory: String,
        customerMap: Map<Long, Long>,
        listMap: Map<Long, Long>,
    ) {
        manifest.getJSONArray(table).forEachObject { row ->
            val nextCustomerId = customerMap[row.getLong("customerId")] ?: return@forEachObject
            val nextListId = listMap[row.getLong("listId")] ?: return@forEachObject
            val archived = File(extraction, row.optString("archivePath"))
            require(archived.canonicalPath.startsWith(extraction.canonicalPath + File.separator) && archived.isFile) {
                "백업의 사진 또는 음성 파일이 없거나 경로가 잘못되었습니다."
            }
            val destination = File(
                context.filesDir,
                "media/$directory/$nextCustomerId/${System.currentTimeMillis()}-${UUID.randomUUID()}.${archived.extension}",
            ).apply { parentFile?.mkdirs() }
            archived.copyTo(destination, overwrite = true)
            val values = row.toContentValues(excluding = setOf("id", "archivePath"))
            values.put("listId", nextListId)
            values.put("customerId", nextCustomerId)
            values.put("filePath", destination.absolutePath)
            if (table == "audio_memos") {
                values.put("transcriptionState", if (row.optString("transcript").isBlank()) "NONE" else "DONE")
                values.put("transcriptionError", "")
            }
            checkedInsert(db, table, values)
        }
    }

    private fun uniqueRestoredListName(db: androidx.sqlite.db.SupportSQLiteDatabase, source: String): String {
        var candidate = source
        var suffix = 2
        while (db.query("SELECT 1 FROM customer_lists WHERE name=? LIMIT 1", arrayOf(candidate)).use { it.moveToFirst() }) {
            candidate = "$source (복원 $suffix)"
            suffix += 1
        }
        return candidate
    }

    private fun checkedInsert(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        values: ContentValues,
    ): Long = db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values).also {
        check(it != -1L) { "$table 복원에 실패했습니다." }
    }

    private fun preview(manifest: JSONObject): BackupPreview {
        val customers = manifest.getJSONArray("customers")
        val counts = mutableMapOf<Long, Int>()
        customers.forEachObject { row -> counts[row.getLong("listId")] = (counts[row.getLong("listId")] ?: 0) + 1 }
        val lists = buildList {
            manifest.getJSONArray("customer_lists").forEachObject { row ->
                add(BackupListPreview(row.getLong("id"), row.getString("name"), counts[row.getLong("id")] ?: 0))
            }
        }
        return BackupPreview(
            createdAtEpochMillis = manifest.getLong("createdAtEpochMillis"),
            appVersion = manifest.optString("appVersion", "알 수 없음"),
            lists = lists,
        )
    }

    private fun queryRows(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): JSONArray {
        val rows = JSONArray()
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                cursor.columnNames.forEachIndexed { index, name ->
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(index))
                        Cursor.FIELD_TYPE_STRING -> row.put(name, cursor.getString(index))
                        Cursor.FIELD_TYPE_BLOB -> row.put(name, android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP))
                    }
                }
                rows.put(row)
            }
        }
        return rows
    }

    private fun JSONObject.toContentValues(excluding: Set<String> = emptySet()): ContentValues = ContentValues().also { values ->
        keys().forEach { key ->
            if (key in excluding) return@forEach
            val value = get(key)
            when (value) {
                JSONObject.NULL -> values.putNull(key)
                is Boolean -> values.put(key, value)
                is Int -> values.put(key, value)
                is Long -> values.put(key, value)
                is Double -> values.put(key, value)
                is String -> values.put(key, value)
                else -> values.put(key, value.toString())
            }
        }
    }

    private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) block(getJSONObject(index))
    }

    private fun JSONArray.ids(): Set<Long> = buildSet {
        forEachObject { add(it.getLong("id")) }
    }

    private companion object {
        const val FORMAT = "soheeya-gaja-android-backup"
        const val MANIFEST_ENTRY = "backup.json"
    }
}
