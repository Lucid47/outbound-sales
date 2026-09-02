package com.lucid47.soheeyagaja.activities

import androidx.room.withTransaction
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.ContactLogEntity
import com.lucid47.soheeyagaja.data.VisitLogEntity
import com.lucid47.soheeyagaja.data.VisitScheduleEntity
import com.lucid47.soheeyagaja.data.VisitScheduleItemEntity
import java.time.LocalDate

class ActivityRepository(private val database: AppDatabase) {
    fun observeHistoryForList(listId: Long) = database.activityDao().observeHistoryForList(listId)

    fun observeHistoryForCustomer(customerId: Long) =
        database.activityDao().observeHistoryForCustomer(customerId)

    fun observeTodaySchedule(listId: Long, dateKey: String = todayKey()) =
        database.activityDao().observeScheduledCustomers(listId, dateKey)

    suspend fun recordCallAttempt(customerId: Long) = recordContact(
        customerId = customerId,
        type = TYPE_CALL,
        result = RESULT_OPENED,
    )

    suspend fun recordSmsAttempt(customerId: Long) = recordContact(
        customerId = customerId,
        type = TYPE_MANUAL_SMS,
        result = RESULT_OPENED,
    )

    suspend fun recordTextMemo(customerId: Long, memo: String) = database.withTransaction {
        val customer = requireCustomer(customerId)
        val normalized = memo.trim()
        require(normalized.isNotEmpty()) { "메모 내용을 입력해주세요." }
        val now = System.currentTimeMillis()
        database.activityDao().insertVisitLog(
            VisitLogEntity(
                listId = customer.listId,
                customerId = customer.id,
                visitedAtEpochMillis = now,
                result = RESULT_SAVED,
                memo = normalized,
                kind = VISIT_TEXT_MEMO,
                createdAtEpochMillis = now,
            ),
        )
        database.customerListDao().touch(customer.listId, now)
    }

    suspend fun recordQuickVisit(customerId: Long, locationAddress: String? = null) = database.withTransaction {
        val customer = requireCustomer(customerId)
        val now = System.currentTimeMillis()
        database.activityDao().insertVisitLog(
            VisitLogEntity(
                listId = customer.listId,
                customerId = customer.id,
                visitedAtEpochMillis = now,
                result = RESULT_SAVED,
                kind = VISIT_QUICK,
                locationAddress = locationAddress?.trim()?.ifEmpty { null },
                createdAtEpochMillis = now,
            ),
        )
        database.customerListDao().touch(customer.listId, now)
    }

    suspend fun setCustomerCompleted(customerId: Long, completed: Boolean) = database.withTransaction {
        val customer = requireCustomer(customerId)
        val now = System.currentTimeMillis()
        val nextStatus = if (completed) STATUS_DONE else STATUS_OPEN
        if (customer.status == nextStatus) return@withTransaction
        database.customerDao().updateStatus(customer.id, nextStatus, now)
        database.activityDao().insertContactLog(
            ContactLogEntity(
                listId = customer.listId,
                customerId = customer.id,
                type = if (completed) TYPE_STATUS_COMPLETE else TYPE_STATUS_REOPEN,
                result = if (completed) RESULT_COMPLETED else RESULT_REOPENED,
                createdAtEpochMillis = now,
            ),
        )
        database.activityDao().getSchedule(customer.listId, todayKey())?.let { schedule ->
            database.activityDao().updateScheduleItemStatus(
                scheduleId = schedule.id,
                customerId = customer.id,
                status = if (completed) SCHEDULE_COMPLETED else SCHEDULE_PENDING,
                completedAt = now.takeIf { completed },
            )
        }
        database.customerListDao().touch(customer.listId, now)
    }

    suspend fun addToTodaySchedule(customerId: Long, dateKey: String = todayKey()) = database.withTransaction {
        val customer = requireCustomer(customerId)
        val now = System.currentTimeMillis()
        val schedule = database.activityDao().getSchedule(customer.listId, dateKey)
        val scheduleId = schedule?.id ?: database.activityDao().insertSchedule(
            VisitScheduleEntity(
                listId = customer.listId,
                dateKey = dateKey,
                title = "오늘 방문",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        database.activityDao().insertScheduleItem(
            VisitScheduleItemEntity(
                scheduleId = scheduleId,
                listId = customer.listId,
                customerId = customer.id,
                orderIndex = database.activityDao().maxScheduleOrder(scheduleId) + 1,
                status = if (customer.status == STATUS_DONE) SCHEDULE_COMPLETED else SCHEDULE_PENDING,
                completedAtEpochMillis = now.takeIf { customer.status == STATUS_DONE },
            ),
        )
        database.customerListDao().touch(customer.listId, now)
    }

    suspend fun removeFromTodaySchedule(customerId: Long, dateKey: String = todayKey()) =
        database.withTransaction {
            val customer = requireCustomer(customerId)
            val schedule = database.activityDao().getSchedule(customer.listId, dateKey) ?: return@withTransaction
            database.activityDao().removeScheduleItem(schedule.id, customer.id)
            database.customerListDao().touch(customer.listId, System.currentTimeMillis())
        }

    private suspend fun recordContact(customerId: Long, type: String, result: String) =
        database.withTransaction {
            val customer = requireCustomer(customerId)
            val now = System.currentTimeMillis()
            database.activityDao().insertContactLog(
                ContactLogEntity(
                    listId = customer.listId,
                    customerId = customer.id,
                    type = type,
                    result = result,
                    createdAtEpochMillis = now,
                ),
            )
            database.customerListDao().touch(customer.listId, now)
        }

    private suspend fun requireCustomer(customerId: Long) =
        requireNotNull(database.customerDao().getById(customerId)) { "고객을 찾지 못했습니다." }

    companion object {
        const val TYPE_CALL = "CALL"
        const val TYPE_MANUAL_SMS = "MANUAL_SMS"
        const val TYPE_NOTE = "NOTE"
        const val TYPE_STATUS_COMPLETE = "STATUS_COMPLETE"
        const val TYPE_STATUS_REOPEN = "STATUS_REOPEN"

        const val RESULT_OPENED = "OPENED"
        const val RESULT_COMPLETED = "COMPLETED"
        const val RESULT_REOPENED = "REOPENED"
        const val RESULT_SAVED = "SAVED"

        const val VISIT_QUICK = "QUICK_LOCATION"
        const val VISIT_TEXT_MEMO = "TEXT_MEMO"

        const val SCHEDULE_PENDING = "PENDING"
        const val SCHEDULE_COMPLETED = "COMPLETED"

        const val STATUS_OPEN = "OPEN"
        const val STATUS_DONE = "DONE"

        fun todayKey(): String = LocalDate.now().toString()
    }
}
