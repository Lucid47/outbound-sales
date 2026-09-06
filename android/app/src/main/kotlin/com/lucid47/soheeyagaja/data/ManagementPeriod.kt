package com.lucid47.soheeyagaja.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "management_periods")
data class ManagementPeriod(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val snapshotText: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ManagementPeriodDao {
    @Query("SELECT * FROM management_periods ORDER BY createdAt DESC")
    fun observe(): Flow<List<ManagementPeriod>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(period: ManagementPeriod)
}

fun historySnapshot(entries: List<HistoryEntryRecord>): String = buildString {
    append("날짜,고객,유형,결과,내용\n")
    entries.forEach { entry ->
        val date = java.time.Instant.ofEpochMilli(entry.occurredAtEpochMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString()
        append(listOf(date, entry.customerName, entry.type, entry.result, entry.detail)
            .joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
        append('\n')
    }
}
