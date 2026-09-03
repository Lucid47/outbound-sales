package com.lucid47.soheeyagaja.messagehistory

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.room.withTransaction
import com.lucid47.soheeyagaja.activities.ActivityRepository
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.ContactLogEntity
import com.lucid47.soheeyagaja.domain.importing.ImportedCustomer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MessageHistoryImportResult(
    val importedCount: Int,
    val duplicateCount: Int,
    val unmatchedCount: Int,
    val invalidCount: Int,
) {
    fun summary(): String = buildString {
        append("문자기록 ${importedCount}건을 고객 히스토리에 추가했습니다.")
        if (duplicateCount > 0) append(" 중복 ${duplicateCount}건 제외.")
        if (unmatchedCount > 0) append(" 고객 미매칭 ${unmatchedCount}건.")
        if (invalidCount > 0) append(" 형식 오류 ${invalidCount}건.")
    }
}

private data class MessageRecord(
    val phoneNumbers: List<String>,
    val body: String,
    val occurredAtEpochMillis: Long,
    val type: String,
)

class MessageHistoryImportRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun importDeviceMessages(listId: Long, days: Int?): MessageHistoryImportResult =
        withContext(Dispatchers.IO) {
            val since = days?.let { System.currentTimeMillis() - it * MILLIS_PER_DAY }
            val types = intArrayOf(
                Telephony.Sms.MESSAGE_TYPE_INBOX,
                Telephony.Sms.MESSAGE_TYPE_SENT,
            )
            val selectionParts = mutableListOf("${Telephony.Sms.TYPE} IN (?, ?)")
            val selectionArgs = mutableListOf(types[0].toString(), types[1].toString())
            if (since != null) {
                selectionParts += "${Telephony.Sms.DATE} >= ?"
                selectionArgs += since.toString()
            }
            val records = mutableListOf<MessageRecord>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                selectionParts.joinToString(" AND "),
                selectionArgs.toTypedArray(),
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (cursor.moveToNext()) {
                    val messageType = when (cursor.getInt(typeIndex)) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> ActivityRepository.TYPE_SMS_INCOMING
                        Telephony.Sms.MESSAGE_TYPE_SENT -> ActivityRepository.TYPE_SMS_OUTGOING
                        else -> continue
                    }
                    records += MessageRecord(
                        phoneNumbers = listOf(cursor.getString(addressIndex).orEmpty()),
                        body = cursor.getString(bodyIndex).orEmpty().trim(),
                        occurredAtEpochMillis = cursor.getLong(dateIndex),
                        type = messageType,
                    )
                }
            }
            records += readMmsRecords(since)
            importRecords(listId, records)
        }

    private fun readMmsRecords(sinceEpochMillis: Long?): List<MessageRecord> {
        val selectionParts = mutableListOf("msg_box IN (?, ?)")
        val selectionArgs = mutableListOf(MMS_BOX_INBOX.toString(), MMS_BOX_SENT.toString())
        sinceEpochMillis?.let {
            selectionParts += "date >= ?"
            selectionArgs += (it / 1_000L).toString()
        }
        val records = mutableListOf<MessageRecord>()
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("_id", "date", "msg_box", "sub"),
            selectionParts.joinToString(" AND "),
            selectionArgs.toTypedArray(),
            "date DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("_id")
            val dateIndex = cursor.getColumnIndexOrThrow("date")
            val boxIndex = cursor.getColumnIndexOrThrow("msg_box")
            val subjectIndex = cursor.getColumnIndex("sub")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val box = cursor.getInt(boxIndex)
                val addressType = if (box == MMS_BOX_INBOX) MMS_ADDRESS_FROM else MMS_ADDRESS_TO
                val addresses = readMmsAddresses(id, addressType)
                val parts = readMmsParts(id)
                val subject = if (subjectIndex >= 0) cursor.getString(subjectIndex).orEmpty().trim() else ""
                val bodyParts = buildList {
                    if (subject.isNotBlank()) add("제목: $subject")
                    if (parts.text.isNotBlank()) add(parts.text)
                    if (parts.attachmentCount > 0) add("[MMS 첨부 ${parts.attachmentCount}개]")
                }
                records += MessageRecord(
                    phoneNumbers = addresses,
                    body = bodyParts.joinToString("\n"),
                    occurredAtEpochMillis = cursor.getLong(dateIndex) * 1_000L,
                    type = if (box == MMS_BOX_INBOX) {
                        ActivityRepository.TYPE_MMS_INCOMING
                    } else {
                        ActivityRepository.TYPE_MMS_OUTGOING
                    },
                )
            }
        }
        return records
    }

    private fun readMmsAddresses(messageId: Long, requiredType: Int): List<String> {
        val result = mutableListOf<String>()
        context.contentResolver.query(
            Uri.parse("content://mms/$messageId/addr"),
            arrayOf("address", "type"),
            "type = ?",
            arrayOf(requiredType.toString()),
            null,
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow("address")
            while (cursor.moveToNext()) {
                cursor.getString(addressIndex)?.trim()
                    ?.takeUnless { it.isBlank() || it == "insert-address-token" }
                    ?.let(result::add)
            }
        }
        return result.distinct()
    }

    private fun readMmsParts(messageId: Long): MmsParts {
        val textParts = mutableListOf<String>()
        var attachmentCount = 0
        context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "text", "_data"),
            "mid = ?",
            arrayOf(messageId.toString()),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("_id")
            val typeIndex = cursor.getColumnIndexOrThrow("ct")
            val textIndex = cursor.getColumnIndexOrThrow("text")
            val dataIndex = cursor.getColumnIndexOrThrow("_data")
            while (cursor.moveToNext()) {
                val contentType = cursor.getString(typeIndex).orEmpty().lowercase()
                when (contentType) {
                    "text/plain" -> {
                        val inline = cursor.getString(textIndex).orEmpty()
                        val text = if (inline.isNotBlank()) inline else {
                            val partId = cursor.getLong(idIndex)
                            runCatching {
                                context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
                                    ?.bufferedReader(Charsets.UTF_8)
                                    ?.use { it.readText() }
                            }.getOrNull().orEmpty()
                        }
                        text.trim().takeIf(String::isNotBlank)?.let(textParts::add)
                    }
                    "application/smil" -> Unit
                    else -> if (cursor.getString(dataIndex) != null || contentType.isNotBlank()) {
                        attachmentCount += 1
                    }
                }
            }
        }
        return MmsParts(textParts.distinct().joinToString("\n"), attachmentCount)
    }

    private suspend fun importRecords(
        listId: Long,
        records: List<MessageRecord>,
    ): MessageHistoryImportResult = database.withTransaction {
        val uniqueCustomersByPhone = database.customerDao().getByList(listId)
            .filter { it.normalizedPhone.isNotBlank() }
            .groupBy { it.normalizedPhone }
            .mapValues { (_, matches) -> matches.singleOrNull() }
        var imported = 0
        var duplicate = 0
        var unmatched = 0
        var invalid = 0

        records.forEach { record ->
            val phones = record.phoneNumbers.map(ImportedCustomer::normalizePhone).filter(String::isNotBlank).distinct()
            if (phones.isEmpty() || record.body.isBlank() || record.occurredAtEpochMillis <= 0L) {
                invalid += 1
                return@forEach
            }
            val matchedCustomers = phones.mapNotNull(uniqueCustomersByPhone::get).distinctBy { it.id }
            val customer = matchedCustomers.singleOrNull()
            if (customer == null) {
                unmatched += 1
                return@forEach
            }
            if (
                database.activityDao().hasImportedMessage(
                    customerId = customer.id,
                    type = record.type,
                    occurredAt = record.occurredAtEpochMillis,
                    messageBody = record.body,
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
                    messageBody = record.body,
                    createdAtEpochMillis = record.occurredAtEpochMillis,
                ),
            )
            imported += 1
        }
        if (imported > 0) database.customerListDao().touch(listId, System.currentTimeMillis())
        MessageHistoryImportResult(imported, duplicate, unmatched, invalid)
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
        const val MMS_BOX_INBOX = 1
        const val MMS_BOX_SENT = 2
        const val MMS_ADDRESS_FROM = 137
        const val MMS_ADDRESS_TO = 151
    }
}

private data class MmsParts(val text: String, val attachmentCount: Int)
