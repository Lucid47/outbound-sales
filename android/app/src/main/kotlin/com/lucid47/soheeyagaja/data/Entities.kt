package com.lucid47.soheeyagaja.data

import androidx.room.Entity
import androidx.room.Embedded
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
    val contactIdentifier: String? = null,
    val contactRegisteredName: String? = null,
    val duplicateKey: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
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

data class CustomerListSummary(
    val id: Long,
    val name: String,
    val sourceName: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val customerCount: Long,
)
