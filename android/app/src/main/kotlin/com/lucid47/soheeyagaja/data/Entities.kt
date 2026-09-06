package com.lucid47.soheeyagaja.data

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "customer_lists")
data class CustomerListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceName: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
)

@Entity(
    tableName = "customers",
    foreignKeys = [
        ForeignKey(
            entity = CustomerListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("listId"),
        Index(value = ["listId", "duplicateKey"], unique = true),
        Index("normalizedPhone"),
    ],
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val sourceRow: Long,
    val name: String,
    val phone: String,
    val normalizedPhone: String,
    val address: String,
    val ownedAddress: String,
    val parcelAddress: String,
    val birthDate: String = "",
    val notes: String,
    val status: String = "OPEN",
    val dashboardStatusId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val geocodedAtEpochMillis: Long? = null,
    val contactIdentifier: String? = null,
    val contactRegisteredName: String? = null,
    val duplicateKey: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
)

@Entity(tableName = "dashboard_statuses", indices = [Index(value = ["orderIndex"], unique = true)])
data class DashboardStatusEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: String,
    val orderIndex: Int,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "dashboard_settings")
data class DashboardSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val statusCount: Int = 5,
    val paletteFamily: String = "BLUE",
    val showsLegend: Boolean = true,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "process_status_logs",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("customerId"), Index("listId"), Index("createdAtEpochMillis")],
)
data class ProcessStatusLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val customerId: Long,
    val previousStatusId: String?,
    val previousStatusName: String?,
    val nextStatusId: String,
    val nextStatusName: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "photo_memos",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("customerId"), Index("listId"), Index("createdAtEpochMillis")],
)
data class PhotoMemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val customerId: Long,
    val filePath: String,
    val originalName: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "audio_memos",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("customerId"), Index("listId"), Index("createdAtEpochMillis")],
)
data class AudioMemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val customerId: Long,
    val filePath: String,
    val durationMillis: Long,
    val transcript: String,
    val sourceType: String = "AUDIO_MEMO",
    @ColumnInfo(defaultValue = "'[]'") val transcriptWordsJson: String = "[]",
    @ColumnInfo(defaultValue = "'NONE'") val transcriptionState: String = "NONE",
    @ColumnInfo(defaultValue = "''") val transcriptionError: String = "",
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "customer_custom_fields",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("customerId")],
)
data class CustomerCustomFieldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val label: String,
    val value: String,
    val sortOrder: Int,
)

data class CustomerWithFields(
    @Embedded val customer: CustomerEntity,
    @Relation(parentColumn = "id", entityColumn = "customerId")
    val customFields: List<CustomerCustomFieldEntity>,
)

@Entity(
    tableName = "contact_logs",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("customerId"), Index("listId"), Index("createdAtEpochMillis")],
)
data class ContactLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val customerId: Long,
    val type: String,
    val result: String,
    val messageBody: String? = null,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "visit_logs",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("customerId"), Index("listId"), Index("visitedAtEpochMillis")],
)
data class VisitLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val customerId: Long,
    val visitedAtEpochMillis: Long,
    val result: String,
    val memo: String? = null,
    val kind: String,
    val locationAddress: String? = null,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "visit_schedules",
    foreignKeys = [
        ForeignKey(
            entity = CustomerListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["listId", "dateKey"], unique = true)],
)
data class VisitScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val dateKey: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "visit_schedule_items",
    foreignKeys = [
        ForeignKey(
            entity = VisitScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("scheduleId"),
        Index("customerId"),
        Index("listId"),
        Index(value = ["scheduleId", "customerId"], unique = true),
    ],
)
data class VisitScheduleItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val listId: Long,
    val customerId: Long,
    val orderIndex: Int,
    val status: String,
    val completedAtEpochMillis: Long? = null,
)

data class HistoryEntryRecord(
    val stableId: String,
    val listId: Long,
    val customerId: Long,
    val customerName: String,
    val category: String,
    val type: String,
    val result: String,
    val detail: String,
    val occurredAtEpochMillis: Long,
    val mediaType: String? = null,
    val mediaPath: String? = null,
    val durationMillis: Long? = null,
)

data class ScheduledCustomerRecord(
    val scheduleItemId: Long,
    val scheduleId: Long,
    val listId: Long,
    val customerId: Long,
    val customerName: String,
    val phone: String,
    val address: String,
    @ColumnInfo(name = "customerStatus") val customerStatus: String,
    val orderIndex: Int,
    @ColumnInfo(name = "scheduleStatus") val scheduleStatus: String,
    val completedAtEpochMillis: Long?,
)

data class CustomerListSummary(
    val id: Long,
    val name: String,
    val sourceName: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val customerCount: Long,
)
