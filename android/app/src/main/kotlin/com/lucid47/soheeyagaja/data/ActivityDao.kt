package com.lucid47.soheeyagaja.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Insert
    suspend fun insertContactLog(log: ContactLogEntity): Long

    @Query(
        "SELECT EXISTS(SELECT 1 FROM contact_logs WHERE customerId = :customerId " +
            "AND type = :type AND createdAtEpochMillis = :occurredAt)",
    )
    suspend fun hasImportedCall(customerId: Long, type: String, occurredAt: Long): Boolean

    @Insert
    suspend fun insertVisitLog(log: VisitLogEntity): Long

    @Query("UPDATE visit_logs SET locationAddress = :address WHERE id = :visitLogId")
    suspend fun updateVisitLocation(visitLogId: Long, address: String)

    @Insert
    suspend fun insertSchedule(schedule: VisitScheduleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScheduleItem(item: VisitScheduleItemEntity): Long

    @Query("SELECT * FROM visit_schedules WHERE listId = :listId AND dateKey = :dateKey LIMIT 1")
    suspend fun getSchedule(listId: Long, dateKey: String): VisitScheduleEntity?

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM visit_schedule_items WHERE scheduleId = :scheduleId")
    suspend fun maxScheduleOrder(scheduleId: Long): Int

    @Query("DELETE FROM visit_schedule_items WHERE scheduleId = :scheduleId AND customerId = :customerId")
    suspend fun removeScheduleItem(scheduleId: Long, customerId: Long)

    @Query(
        "UPDATE visit_schedule_items SET status = :status, completedAtEpochMillis = :completedAt " +
            "WHERE scheduleId = :scheduleId AND customerId = :customerId",
    )
    suspend fun updateScheduleItemStatus(
        scheduleId: Long,
        customerId: Long,
        status: String,
        completedAt: Long?,
    )

    @Query(
        """
        SELECT visit_schedule_items.id AS scheduleItemId,
               visit_schedule_items.scheduleId AS scheduleId,
               visit_schedule_items.listId AS listId,
               customers.id AS customerId,
               customers.name AS customerName,
               customers.phone AS phone,
               customers.address AS address,
               customers.status AS customerStatus,
               visit_schedule_items.orderIndex AS orderIndex,
               visit_schedule_items.status AS scheduleStatus,
               visit_schedule_items.completedAtEpochMillis AS completedAtEpochMillis
        FROM visit_schedule_items
        INNER JOIN visit_schedules ON visit_schedules.id = visit_schedule_items.scheduleId
        INNER JOIN customers ON customers.id = visit_schedule_items.customerId
        WHERE visit_schedules.listId = :listId AND visit_schedules.dateKey = :dateKey
        ORDER BY visit_schedule_items.orderIndex, customers.name COLLATE NOCASE
        """,
    )
    fun observeScheduledCustomers(listId: Long, dateKey: String): Flow<List<ScheduledCustomerRecord>>

    @Query(
        """
        SELECT 'contact-' || contact_logs.id AS stableId,
               contact_logs.listId AS listId,
               contact_logs.customerId AS customerId,
               customers.name AS customerName,
               'CONTACT' AS category,
               contact_logs.type AS type,
               contact_logs.result AS result,
               COALESCE(contact_logs.messageBody, '') AS detail,
               contact_logs.createdAtEpochMillis AS occurredAtEpochMillis,
               NULL AS mediaType,
               NULL AS mediaPath,
               NULL AS durationMillis
        FROM contact_logs
        INNER JOIN customers ON customers.id = contact_logs.customerId
        WHERE contact_logs.listId = :listId
        UNION ALL
        SELECT 'visit-' || visit_logs.id AS stableId,
               visit_logs.listId AS listId,
               visit_logs.customerId AS customerId,
               customers.name AS customerName,
               'VISIT' AS category,
               visit_logs.kind AS type,
               visit_logs.result AS result,
               CASE
                   WHEN COALESCE(visit_logs.memo, '') != '' AND COALESCE(visit_logs.locationAddress, '') != ''
                       THEN visit_logs.memo || ' · ' || visit_logs.locationAddress
                   WHEN COALESCE(visit_logs.memo, '') != '' THEN visit_logs.memo
                   ELSE COALESCE(visit_logs.locationAddress, '')
               END AS detail,
               visit_logs.visitedAtEpochMillis AS occurredAtEpochMillis,
               NULL AS mediaType,
               NULL AS mediaPath,
               NULL AS durationMillis
        FROM visit_logs
        INNER JOIN customers ON customers.id = visit_logs.customerId
        WHERE visit_logs.listId = :listId
        UNION ALL
        SELECT 'process-' || process_status_logs.id AS stableId,
               process_status_logs.listId AS listId,
               process_status_logs.customerId AS customerId,
               customers.name AS customerName,
               'PROCESS' AS category,
               'PROCESS_STATUS' AS type,
               'CHANGED' AS result,
               COALESCE(process_status_logs.previousStatusName, '상태 없음') ||
                   ' → ' || process_status_logs.nextStatusName AS detail,
               process_status_logs.createdAtEpochMillis AS occurredAtEpochMillis,
               NULL AS mediaType,
               NULL AS mediaPath,
               NULL AS durationMillis
        FROM process_status_logs
        INNER JOIN customers ON customers.id = process_status_logs.customerId
        WHERE process_status_logs.listId = :listId
        UNION ALL
        SELECT 'photo-' || photo_memos.id AS stableId,
               photo_memos.listId AS listId,
               photo_memos.customerId AS customerId,
               customers.name AS customerName,
               'VISIT' AS category,
               'PHOTO_MEMO' AS type,
               'SAVED' AS result,
               photo_memos.originalName AS detail,
               photo_memos.createdAtEpochMillis AS occurredAtEpochMillis,
               'PHOTO' AS mediaType,
               photo_memos.filePath AS mediaPath,
               NULL AS durationMillis
        FROM photo_memos
        INNER JOIN customers ON customers.id = photo_memos.customerId
        WHERE photo_memos.listId = :listId
        UNION ALL
        SELECT 'audio-' || audio_memos.id AS stableId,
               audio_memos.listId AS listId,
               audio_memos.customerId AS customerId,
               customers.name AS customerName,
               'VISIT' AS category,
               'AUDIO_MEMO' AS type,
               'SAVED' AS result,
               audio_memos.transcript AS detail,
               audio_memos.createdAtEpochMillis AS occurredAtEpochMillis,
               'AUDIO' AS mediaType,
               audio_memos.filePath AS mediaPath,
               audio_memos.durationMillis AS durationMillis
        FROM audio_memos
        INNER JOIN customers ON customers.id = audio_memos.customerId
        WHERE audio_memos.listId = :listId
        ORDER BY occurredAtEpochMillis DESC
        """,
    )
    fun observeHistoryForList(listId: Long): Flow<List<HistoryEntryRecord>>

    @Query(
        """
        SELECT 'contact-' || contact_logs.id AS stableId,
               contact_logs.listId AS listId,
               contact_logs.customerId AS customerId,
               customers.name AS customerName,
               'CONTACT' AS category,
               contact_logs.type AS type,
               contact_logs.result AS result,
               COALESCE(contact_logs.messageBody, '') AS detail,
               contact_logs.createdAtEpochMillis AS occurredAtEpochMillis,
               NULL AS mediaType,
               NULL AS mediaPath,
               NULL AS durationMillis
        FROM contact_logs
        INNER JOIN customers ON customers.id = contact_logs.customerId
        WHERE contact_logs.customerId = :customerId
        UNION ALL
        SELECT 'visit-' || visit_logs.id AS stableId,
               visit_logs.listId AS listId,
               visit_logs.customerId AS customerId,
               customers.name AS customerName,
               'VISIT' AS category,
               visit_logs.kind AS type,
               visit_logs.result AS result,
               CASE
                   WHEN COALESCE(visit_logs.memo, '') != '' AND COALESCE(visit_logs.locationAddress, '') != ''
                       THEN visit_logs.memo || ' · ' || visit_logs.locationAddress
                   WHEN COALESCE(visit_logs.memo, '') != '' THEN visit_logs.memo
                   ELSE COALESCE(visit_logs.locationAddress, '')
               END AS detail,
               visit_logs.visitedAtEpochMillis AS occurredAtEpochMillis,
               NULL AS mediaType,
               NULL AS mediaPath,
               NULL AS durationMillis
        FROM visit_logs
        INNER JOIN customers ON customers.id = visit_logs.customerId
        WHERE visit_logs.customerId = :customerId
        UNION ALL
        SELECT 'process-' || process_status_logs.id AS stableId,
               process_status_logs.listId AS listId,
               process_status_logs.customerId AS customerId,
               customers.name AS customerName,
               'PROCESS' AS category,
               'PROCESS_STATUS' AS type,
               'CHANGED' AS result,
               COALESCE(process_status_logs.previousStatusName, '상태 없음') ||
                   ' → ' || process_status_logs.nextStatusName AS detail,
               process_status_logs.createdAtEpochMillis AS occurredAtEpochMillis,
               NULL AS mediaType,
               NULL AS mediaPath,
               NULL AS durationMillis
        FROM process_status_logs
        INNER JOIN customers ON customers.id = process_status_logs.customerId
        WHERE process_status_logs.customerId = :customerId
        UNION ALL
        SELECT 'photo-' || photo_memos.id AS stableId,
               photo_memos.listId AS listId,
               photo_memos.customerId AS customerId,
               customers.name AS customerName,
               'VISIT' AS category,
               'PHOTO_MEMO' AS type,
               'SAVED' AS result,
               photo_memos.originalName AS detail,
               photo_memos.createdAtEpochMillis AS occurredAtEpochMillis,
               'PHOTO' AS mediaType,
               photo_memos.filePath AS mediaPath,
               NULL AS durationMillis
        FROM photo_memos
        INNER JOIN customers ON customers.id = photo_memos.customerId
        WHERE photo_memos.customerId = :customerId
        UNION ALL
        SELECT 'audio-' || audio_memos.id AS stableId,
               audio_memos.listId AS listId,
               audio_memos.customerId AS customerId,
               customers.name AS customerName,
               'VISIT' AS category,
               'AUDIO_MEMO' AS type,
               'SAVED' AS result,
               audio_memos.transcript AS detail,
               audio_memos.createdAtEpochMillis AS occurredAtEpochMillis,
               'AUDIO' AS mediaType,
               audio_memos.filePath AS mediaPath,
               audio_memos.durationMillis AS durationMillis
        FROM audio_memos
        INNER JOIN customers ON customers.id = audio_memos.customerId
        WHERE audio_memos.customerId = :customerId
        ORDER BY occurredAtEpochMillis DESC
        """,
    )
    fun observeHistoryForCustomer(customerId: Long): Flow<List<HistoryEntryRecord>>

    @Query("SELECT COUNT(*) FROM contact_logs WHERE customerId = :customerId")
    suspend fun countContactLogs(customerId: Long): Long

    @Query("SELECT COUNT(*) FROM visit_logs WHERE customerId = :customerId")
    suspend fun countVisitLogs(customerId: Long): Long

    @Query("SELECT COUNT(*) FROM visit_schedule_items WHERE customerId = :customerId")
    suspend fun countScheduleItems(customerId: Long): Long

    @Query("SELECT COUNT(*) FROM process_status_logs WHERE customerId = :customerId")
    suspend fun countProcessLogs(customerId: Long): Long
}
