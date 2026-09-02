package com.lucid47.soheeyagaja.callhistory

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import androidx.room.withTransaction
import com.lucid47.soheeyagaja.activities.ActivityRepository
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.ContactLogEntity
import com.lucid47.soheeyagaja.domain.importing.CsvRecordReader
import com.lucid47.soheeyagaja.domain.importing.ImportedCustomer
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class CallHistoryImportResult(
    val importedCount: Int,
    val duplicateCount: Int,
    val unmatchedCount: Int,
    val invalidCount: Int,
) {
    fun summary(): String = buildString {
        append("통화기록 ${importedCount}건을 고객 히스토리에 추가했습니다.")
        if (duplicateCount > 0) append(" 중복 ${duplicateCount}건 제외.")
        if (unmatchedCount > 0) append(" 고객 미매칭 ${unmatchedCount}건.")
        if (invalidCount > 0) append(" 형식 오류 ${invalidCount}건.")
    }
}

private data class CallHistoryRecord(
    val phoneNumber: String,
    val occurredAtEpochMillis: Long,
    val type: String,
    val durationSeconds: Long,
)

class CallHistoryImportRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun importDeviceCalls(listId: Long, days: Int?): CallHistoryImportResult {
        val since = days?.let { System.currentTimeMillis() - it * MILLIS_PER_DAY }
        val records = mutableListOf<CallHistoryRecord>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val selection = since?.let { "${CallLog.Calls.DATE} >= ?" }
        val arguments = since?.let { arrayOf(it.toString()) }

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            arguments,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext()) {
                records += CallHistoryRecord(
                    phoneNumber = cursor.getString(numberIndex).orEmpty(),
                    occurredAtEpochMillis = cursor.getLong(dateIndex),
                    type = callType(cursor.getInt(typeIndex)),
                    durationSeconds = cursor.getLong(durationIndex).coerceAtLeast(0),
                )
            }
        }
        return importRecords(listId, records)
    }

    suspend fun importCsv(listId: Long, uri: Uri): CallHistoryImportResult {
        val records = mutableListOf<CallHistoryRecord>()
        var invalidCount = 0
        context.contentResolver.openInputStream(uri).use { source ->
            requireNotNull(source) { "선택한 통화기록 파일을 열 수 없습니다." }
            val buffered = source.bufferedForDetection()
            val reader = CsvRecordReader(InputStreamReader(buffered, buffered.detectCharsetAndSkipBom()))
            val headers = reader.readRecord()?.map(::normalizeHeader)
                ?: throw IllegalArgumentException("CSV 파일에 헤더가 없습니다.")
            val phoneIndex = headers.firstMatching(PHONE_HEADERS)
            val dateIndex = headers.firstMatching(DATE_HEADERS)
            require(phoneIndex >= 0) { "전화번호 헤더를 찾지 못했습니다." }
            require(dateIndex >= 0) { "통화일시 헤더를 찾지 못했습니다." }
            val typeIndex = headers.firstMatching(TYPE_HEADERS)
            val durationIndex = headers.firstMatching(DURATION_HEADERS)

            while (true) {
                val row = reader.readRecord() ?: break
                if (row.all(String::isBlank)) continue
                val timestamp = row.getOrNull(dateIndex).orEmpty().toEpochMillisOrNull()
                val phone = row.getOrNull(phoneIndex).orEmpty()
                if (timestamp == null || ImportedCustomer.normalizePhone(phone).isBlank()) {
                    invalidCount += 1
                    continue
                }
                records += CallHistoryRecord(
                    phoneNumber = phone,
                    occurredAtEpochMillis = timestamp,
                    type = csvCallType(row.getOrNull(typeIndex).orEmpty()),
                    durationSeconds = row.getOrNull(durationIndex).orEmpty().toDurationSeconds(),
                )
            }
        }
        val imported = importRecords(listId, records)
        return imported.copy(invalidCount = imported.invalidCount + invalidCount)
    }

    private suspend fun importRecords(
        listId: Long,
        records: List<CallHistoryRecord>,
    ): CallHistoryImportResult = database.withTransaction {
        val customers = database.customerDao().getByList(listId)
        val uniqueCustomersByPhone = customers
            .filter { it.normalizedPhone.isNotBlank() }
            .groupBy { it.normalizedPhone }
            .mapValues { (_, matches) -> matches.singleOrNull() }
        var imported = 0
        var duplicate = 0
        var unmatched = 0
        var invalid = 0

        records.forEach { record ->
            val phone = ImportedCustomer.normalizePhone(record.phoneNumber)
            if (phone.isBlank() || record.occurredAtEpochMillis <= 0) {
                invalid += 1
                return@forEach
            }
            val customer = uniqueCustomersByPhone[phone]
            if (customer == null) {
                unmatched += 1
                return@forEach
            }
            if (
                database.activityDao().hasImportedCall(
                    customerId = customer.id,
                    type = record.type,
                    occurredAt = record.occurredAtEpochMillis,
                )
            ) {
                duplicate += 1
                return@forEach
            }
            database.activityDao().insertContactLog(
                ContactLogEntity(
                    listId = listId,
                    customerId = customer.id,
                    type = record.type,
                    result = ActivityRepository.RESULT_IMPORTED,
                    messageBody = formatDuration(record.durationSeconds),
                    createdAtEpochMillis = record.occurredAtEpochMillis,
                ),
            )
            imported += 1
        }
        if (imported > 0) database.customerListDao().touch(listId, System.currentTimeMillis())
        CallHistoryImportResult(imported, duplicate, unmatched, invalid)
    }

    private fun callType(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> ActivityRepository.TYPE_CALL_INCOMING
        CallLog.Calls.OUTGOING_TYPE -> ActivityRepository.TYPE_CALL_OUTGOING
        CallLog.Calls.MISSED_TYPE -> ActivityRepository.TYPE_CALL_MISSED
        CallLog.Calls.REJECTED_TYPE -> ActivityRepository.TYPE_CALL_REJECTED
        CallLog.Calls.BLOCKED_TYPE -> ActivityRepository.TYPE_CALL_BLOCKED
        else -> ActivityRepository.TYPE_CALL_OTHER
    }

    private fun csvCallType(value: String): String {
        val normalized = normalizeHeader(value)
        return when {
            normalized.contains("수신") || normalized.contains("incoming") -> ActivityRepository.TYPE_CALL_INCOMING
            normalized.contains("발신") || normalized.contains("outgoing") -> ActivityRepository.TYPE_CALL_OUTGOING
            normalized.contains("부재") || normalized.contains("missed") -> ActivityRepository.TYPE_CALL_MISSED
            normalized.contains("거절") || normalized.contains("rejected") -> ActivityRepository.TYPE_CALL_REJECTED
            normalized.contains("차단") || normalized.contains("blocked") -> ActivityRepository.TYPE_CALL_BLOCKED
            else -> ActivityRepository.TYPE_CALL_OTHER
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remaining = seconds % 60
        return if (minutes > 0) "통화시간 ${minutes}분 ${remaining}초" else "통화시간 ${remaining}초"
    }

    private fun String.toDurationSeconds(): Long {
        val trimmed = trim()
        trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        val colon = trimmed.split(':').mapNotNull(String::toLongOrNull)
        return when (colon.size) {
            3 -> (colon[0] * 3600 + colon[1] * 60 + colon[2]).coerceAtLeast(0)
            2 -> (colon[0] * 60 + colon[1]).coerceAtLeast(0)
            else -> 0
        }
    }

    private fun String.toEpochMillisOrNull(): Long? {
        val text = trim()
        text.toLongOrNull()?.let { raw ->
            return if (raw < 10_000_000_000L) raw * 1_000 else raw
        }
        runCatching { return Instant.parse(text).toEpochMilli() }
        DATE_TIME_FORMATTERS.forEach { formatter ->
            try {
                return LocalDateTime.parse(text, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Try the next common export format.
            }
        }
        DATE_FORMATTERS.forEach { formatter ->
            try {
                return LocalDate.parse(text, formatter)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Try the next common export format.
            }
        }
        return null
    }

    private fun List<String>.firstMatching(candidates: Set<String>): Int =
        indexOfFirst { it in candidates }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase().replace(" ", "").replace("_", "").replace("-", "")

    private fun InputStream.bufferedForDetection(): BufferedInputStream =
        if (this is BufferedInputStream) this else BufferedInputStream(this, SAMPLE_SIZE + 4)

    private fun BufferedInputStream.detectCharsetAndSkipBom(): Charset {
        mark(SAMPLE_SIZE + 4)
        val sample = ByteArray(SAMPLE_SIZE)
        val count = read(sample)
        reset()
        if (count <= 0) return Charsets.UTF_8
        val bytes = sample.copyOf(count)
        return when {
            bytes.startsWith(UTF8_BOM) -> Charsets.UTF_8.also { skipFully(UTF8_BOM.size) }
            bytes.startsWith(UTF16_LE_BOM) -> Charsets.UTF_16LE.also { skipFully(UTF16_LE_BOM.size) }
            bytes.startsWith(UTF16_BE_BOM) -> Charsets.UTF_16BE.also { skipFully(UTF16_BE_BOM.size) }
            bytes.isValidUtf8() -> Charsets.UTF_8
            else -> Charset.forName("MS949")
        }
    }

    private fun BufferedInputStream.skipFully(count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.isValidUtf8(): Boolean {
        val result = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this), CharBuffer.allocate(size), false)
        return !result.isError
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
        const val SAMPLE_SIZE = 64 * 1_024
        val PHONE_HEADERS = setOf("전화번호", "번호", "연락처", "phone", "phonenumber", "number")
        val DATE_HEADERS = setOf("통화일시", "일시", "날짜", "date", "datetime", "starttime")
        val TYPE_HEADERS = setOf("통화유형", "유형", "종류", "type", "calltype")
        val DURATION_HEADERS = setOf("통화시간", "통화길이", "통화초", "지속시간", "duration", "durationseconds")
        val DATE_TIME_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy. M. d. a h:mm"),
            DateTimeFormatter.ofPattern("yyyy. M. d. HH:mm"),
        )
        val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy. M. d."),
        )
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
}
