package com.lucid47.soheeyagaja.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DashboardDao {
    @Query("SELECT * FROM dashboard_statuses ORDER BY orderIndex")
    fun observeStatuses(): Flow<List<DashboardStatusEntity>>

    @Query("SELECT * FROM dashboard_settings WHERE id = 1")
    fun observeSettings(): Flow<DashboardSettingsEntity?>

    @Query("SELECT * FROM dashboard_statuses ORDER BY orderIndex")
    suspend fun getStatuses(): List<DashboardStatusEntity>

    @Query("SELECT * FROM dashboard_settings WHERE id = 1")
    suspend fun getSettings(): DashboardSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatuses(statuses: List<DashboardStatusEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: DashboardSettingsEntity)

    @Query("DELETE FROM dashboard_statuses WHERE id IN (:statusIds)")
    suspend fun deleteStatuses(statusIds: List<String>)

    @Query(
        "UPDATE customers SET dashboardStatusId = :fallbackId, updatedAtEpochMillis = :updatedAt " +
            "WHERE dashboardStatusId IN (:removedIds)",
    )
    suspend fun moveCustomersFromRemovedStatuses(
        removedIds: List<String>,
        fallbackId: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE customers SET dashboardStatusId = :statusId, updatedAtEpochMillis = :updatedAt " +
            "WHERE id = :customerId",
    )
    suspend fun setCustomerStatus(customerId: Long, statusId: String, updatedAt: Long)

    @Insert
    suspend fun insertProcessLog(log: ProcessStatusLogEntity): Long

    @Query("SELECT COUNT(*) FROM process_status_logs WHERE customerId = :customerId")
    suspend fun countProcessLogs(customerId: Long): Long
}
