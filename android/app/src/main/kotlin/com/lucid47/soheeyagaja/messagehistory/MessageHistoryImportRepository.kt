package com.lucid47.soheeyagaja.messagehistory

import android.content.Context
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
    val phoneNumber: String,
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
                        phoneNumber = cursor.getString(addressIndex).orEmpty(),
                        body = cursor.getString(bodyIndex).orEmpty().trim(),
                        occurredAtEpochMillis = cursor.getLong(dateIndex),
                        type = messageType,
                    )
                }
            }
            importRecords(listId, records)
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
            val phone = ImportedCustomer.normalizePhone(record.phoneNumber)
            if (phone.isBlank() || record.body.isBlank() || record.occurredAtEpochMillis <= 0L) {
                invalid += 1
                return@forEach
            }
            val customer = uniqueCustomersByPhone[phone]
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
    }
}
