package com.lucid47.soheeyagaja.customers

import androidx.room.withTransaction
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerCustomFieldEntity
import com.lucid47.soheeyagaja.data.CustomerEntity
import com.lucid47.soheeyagaja.domain.importing.ImportedCustomer
import java.util.UUID

data class CustomFieldDraft(
    val label: String = "",
    val value: String = "",
)

data class CustomerDraft(
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val ownedAddress: String = "",
    val parcelAddress: String = "",
    val birthDate: String = "",
    val notes: String = "",
    val customFields: List<CustomFieldDraft> = emptyList(),
)

class CustomerManagementRepository(private val database: AppDatabase) {
    fun observeCustomerLists() = database.customerListDao().observeSummaries()

    fun observeCustomers(listId: Long) = database.customerDao().observeByList(listId)

    fun observeCustomer(customerId: Long) = database.customerDao().observeById(customerId)

    suspend fun createCustomer(listId: Long, draft: CustomerDraft): Long = database.withTransaction {
        require(database.customerListDao().exists(listId)) { "고객리스트를 찾지 못했습니다." }
        validateDraft(draft)
        val now = System.currentTimeMillis()
        val normalizedPhone = ImportedCustomer.normalizePhone(draft.phone)
        require(
            normalizedPhone.isEmpty() ||
                !database.customerDao().phoneExists(listId, normalizedPhone, excludingId = 0),
        ) { "같은 전화번호의 고객이 이 리스트에 있습니다." }

        val customerId = database.customerDao().insertOne(
            CustomerEntity(
                listId = listId,
                sourceRow = database.customerDao().maxSourceRow(listId) + 1,
                name = draft.name.trim(),
                phone = draft.phone.trim(),
                normalizedPhone = normalizedPhone,
                address = draft.address.trim(),
                ownedAddress = draft.ownedAddress.trim(),
                parcelAddress = draft.parcelAddress.trim(),
                birthDate = draft.birthDate.trim(),
                notes = draft.notes.trim(),
                status = STATUS_OPEN,
                duplicateKey = if (normalizedPhone.isNotEmpty()) {
                    "phone:$normalizedPhone"
                } else {
                    "manual:${UUID.randomUUID()}"
                },
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        replaceCustomFields(customerId, draft.customFields)
        database.customerListDao().touch(listId, now)
        customerId
    }

    suspend fun updateCustomer(customerId: Long, draft: CustomerDraft) = database.withTransaction {
        val existing = requireNotNull(database.customerDao().getById(customerId)) {
            "고객을 찾지 못했습니다."
        }
        validateDraft(draft)
        val now = System.currentTimeMillis()
        val normalizedPhone = ImportedCustomer.normalizePhone(draft.phone)
        require(
            normalizedPhone.isEmpty() ||
                !database.customerDao().phoneExists(existing.listId, normalizedPhone, customerId),
        ) { "같은 전화번호의 고객이 이 리스트에 있습니다." }

        database.customerDao().update(
            existing.copy(
                name = draft.name.trim(),
                phone = draft.phone.trim(),
                normalizedPhone = normalizedPhone,
                address = draft.address.trim(),
                ownedAddress = draft.ownedAddress.trim(),
                parcelAddress = draft.parcelAddress.trim(),
                birthDate = draft.birthDate.trim(),
                notes = draft.notes.trim(),
                duplicateKey = if (normalizedPhone.isNotEmpty()) {
                    "phone:$normalizedPhone"
                } else if (existing.duplicateKey.startsWith("phone:")) {
                    "manual:${UUID.randomUUID()}"
                } else {
                    existing.duplicateKey
                },
                updatedAtEpochMillis = now,
            ),
        )
        replaceCustomFields(customerId, draft.customFields)
        database.customerListDao().touch(existing.listId, now)
    }

    suspend fun deleteCustomer(customerId: Long) = database.withTransaction {
        val existing = requireNotNull(database.customerDao().getById(customerId)) {
            "고객을 찾지 못했습니다."
        }
        database.customerDao().deleteById(customerId)
        database.customerListDao().touch(existing.listId, System.currentTimeMillis())
    }

    suspend fun renameCustomerList(listId: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "고객리스트 이름을 입력해주세요." }
        require(database.customerListDao().exists(listId)) { "고객리스트를 찾지 못했습니다." }
        database.customerListDao().rename(listId, trimmed, System.currentTimeMillis())
    }

    suspend fun deleteCustomerList(listId: Long) = database.withTransaction {
        requireNotNull(database.customerListDao().getById(listId)) { "고객리스트를 찾지 못했습니다." }
        database.customerListDao().deleteById(listId)
    }

    private suspend fun replaceCustomFields(customerId: Long, fields: List<CustomFieldDraft>) {
        database.customerDao().deleteCustomFields(customerId)
        val normalized = fields.mapIndexedNotNull { index, field ->
            val label = field.label.trim()
            val value = field.value.trim()
            if (label.isEmpty() && value.isEmpty()) {
                null
            } else {
                CustomerCustomFieldEntity(
                    customerId = customerId,
                    label = label.ifEmpty { "추가 항목" },
                    value = value,
                    sortOrder = index,
                )
            }
        }
        if (normalized.isNotEmpty()) database.customerDao().insertCustomFields(normalized)
    }

    private fun validateDraft(draft: CustomerDraft) {
        require(draft.name.isNotBlank()) { "고객 이름을 입력해주세요." }
    }

    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_DONE = "DONE"
    }
}
