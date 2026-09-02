package com.lucid47.soheeyagaja.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.lucid47.soheeyagaja.importing.ContactImportRecord
import com.lucid47.soheeyagaja.data.CustomerWithFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceContactGroup(
    val id: Long,
    val name: String,
    val accountName: String,
    val contactCount: Int,
)

data class ManagedContactGroup(
    val id: Long,
    val name: String,
    val sourceId: String,
    val contactCount: Int,
)

data class ContactExportResult(
    val groupId: Long,
    val exportedCount: Int,
    val skippedCount: Int,
)

class AndroidContactService(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun allContacts(): List<ContactImportRecord> = withContext(Dispatchers.IO) {
        requirePermission()
        loadContacts()
    }

    suspend fun groups(): List<DeviceContactGroup> = withContext(Dispatchers.IO) {
        requirePermission()
        val counts = groupMembershipCounts()
        val groups = mutableListOf<DeviceContactGroup>()
        resolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(
                ContactsContract.Groups._ID,
                ContactsContract.Groups.TITLE,
                ContactsContract.Groups.ACCOUNT_NAME,
            ),
            "${ContactsContract.Groups.DELETED}=0",
            null,
            "${ContactsContract.Groups.TITLE} COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
            val accountColumn = cursor.getColumnIndexOrThrow(ContactsContract.Groups.ACCOUNT_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn).orEmpty().trim()
                if (title.isNotEmpty()) {
                    groups += DeviceContactGroup(
                        id = id,
                        name = title,
                        accountName = cursor.getString(accountColumn).orEmpty(),
                        contactCount = counts[id]?.size ?: 0,
                    )
                }
            }
        }
        groups.distinctBy(DeviceContactGroup::id)
    }

    suspend fun contactsInGroups(groupIds: Set<Long>): List<ContactImportRecord> = withContext(Dispatchers.IO) {
        requirePermission()
        if (groupIds.isEmpty()) return@withContext emptyList()
        val contactIds = mutableSetOf<Long>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
            ),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            val contactColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val groupColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
            )
            while (cursor.moveToNext()) {
                if (cursor.getLong(groupColumn) in groupIds) {
                    contactIds += cursor.getLong(contactColumn)
                }
            }
        }
        if (contactIds.isEmpty()) emptyList() else loadContacts(contactIds)
    }

    suspend fun exportCustomerGroup(
        listId: Long,
        listName: String,
        customers: List<CustomerWithFields>,
        prefixEnabled: Boolean,
        prefix: String,
    ): ContactExportResult = withContext(Dispatchers.IO) {
        requireReadWritePermission()
        val account = preferredAccount()
        val sourceId = "$MANAGED_GROUP_PREFIX$listId"
        val groupId = findManagedGroupId(sourceId) ?: createManagedGroup(
            name = listName,
            sourceId = sourceId,
            accountName = account.first,
            accountType = account.second,
        )
        val existingSourceIds = existingManagedContactSourceIds()
        var exported = 0
        var skipped = 0
        customers.chunked(EXPORT_BATCH_SIZE).forEach { batch ->
            val operations = arrayListOf<ContentProviderOperation>()
            batch.forEach { record ->
                val customer = record.customer
                val contactSourceId = "$MANAGED_CONTACT_PREFIX${customer.id}"
                if ((customer.phone.isBlank() && customer.name.isBlank()) || contactSourceId in existingSourceIds) {
                    skipped += 1
                    return@forEach
                }
                val rawContactInsertIndex = operations.size
                operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.first)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.second)
                    .withValue(ContactsContract.RawContacts.SOURCE_ID, contactSourceId)
                    .build()
                operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        (if (prefixEnabled) prefix else "") + customer.name,
                    )
                    .build()
                if (customer.phone.isNotBlank()) {
                    operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, customer.phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                }
                customer.address.takeIf(String::isNotBlank)?.let { address ->
                    operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, address)
                        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK)
                        .build()
                }
                operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, "$MANAGED_CONTACT_NOTE${customer.id}")
                    .build()
                operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                    .build()
                exported += 1
                existingSourceIds += contactSourceId
            }
            if (operations.isNotEmpty()) resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }
        ContactExportResult(groupId, exported, skipped)
    }

    suspend fun managedGroups(): List<ManagedContactGroup> = withContext(Dispatchers.IO) {
        requirePermission()
        val counts = groupMembershipCounts()
        buildList {
            resolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE, ContactsContract.Groups.SOURCE_ID),
                "${ContactsContract.Groups.DELETED}=0 AND ${ContactsContract.Groups.SOURCE_ID} LIKE ?",
                arrayOf("$MANAGED_GROUP_PREFIX%"),
                "${ContactsContract.Groups.TITLE} COLLATE LOCALIZED ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
                val sourceIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.SOURCE_ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    add(
                        ManagedContactGroup(
                            id = id,
                            name = cursor.getString(titleIndex).orEmpty(),
                            sourceId = cursor.getString(sourceIndex).orEmpty(),
                            contactCount = counts[id]?.size ?: 0,
                        ),
                    )
                }
            }
        }
    }

    suspend fun deleteManagedGroup(groupId: Long, deleteContacts: Boolean): Int = withContext(Dispatchers.IO) {
        requireReadWritePermission()
        val managed = managedGroups().firstOrNull { it.id == groupId }
            ?: error("소희야 가자가 만든 연락처 그룹만 삭제할 수 있습니다.")
        val rawContactIds = mutableSetOf<Long>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID}=?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            while (cursor.moveToNext()) rawContactIds += cursor.getLong(idIndex)
        }
        if (deleteContacts && rawContactIds.isNotEmpty()) {
            rawContactIds.chunked(100).forEach { ids ->
                val placeholders = ids.joinToString(",") { "?" }
                resolver.delete(
                    ContactsContract.RawContacts.CONTENT_URI,
                    "${ContactsContract.RawContacts._ID} IN ($placeholders) AND " +
                        "${ContactsContract.RawContacts.SOURCE_ID} LIKE ?",
                    (ids.map(Long::toString) + "$MANAGED_CONTACT_PREFIX%").toTypedArray(),
                )
            }
        }
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, managed.id), null, null)
        if (deleteContacts) rawContactIds.size else 0
    }

    private fun preferredAccount(): Pair<String?, String?> {
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} IS NOT NULL AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NOT NULL",
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) to cursor.getString(1)
        }
        return null to null
    }

    private fun existingManagedContactSourceIds(): MutableSet<String> {
        val ids = mutableSetOf<String>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.SOURCE_ID),
            "${ContactsContract.RawContacts.SOURCE_ID} LIKE ? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf("$MANAGED_CONTACT_PREFIX%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(ids::add)
        }
        return ids
    }

    private fun findManagedGroupId(sourceId: String): Long? {
        resolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID),
            "${ContactsContract.Groups.SOURCE_ID}=? AND ${ContactsContract.Groups.DELETED}=0",
            arrayOf(sourceId),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        return null
    }

    private fun createManagedGroup(
        name: String,
        sourceId: String,
        accountName: String?,
        accountType: String?,
    ): Long {
        val values = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, name)
            put(ContactsContract.Groups.SOURCE_ID, sourceId)
            put(ContactsContract.Groups.GROUP_VISIBLE, 1)
            put(ContactsContract.Groups.SHOULD_SYNC, 1)
            put(ContactsContract.Groups.ACCOUNT_NAME, accountName)
            put(ContactsContract.Groups.ACCOUNT_TYPE, accountType)
        }
        val uri = checkNotNull(resolver.insert(ContactsContract.Groups.CONTENT_URI, values)) {
            "연락처 그룹을 만들지 못했습니다."
        }
        return ContentUris.parseId(uri)
    }

    private fun loadContacts(onlyIds: Set<Long>? = null): List<ContactImportRecord> {
        val builders = linkedMapOf<Long, ContactBuilder>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ),
            null,
            null,
            ContactsContract.Contacts.SORT_KEY_PRIMARY,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                if (onlyIds == null || id in onlyIds) {
                    builders[id] = ContactBuilder(id, cursor.getString(nameColumn).orEmpty())
                }
            }
        }

        if (builders.isEmpty()) return emptyList()
        loadPhones(builders)
        loadAddresses(builders)
        loadOrganizations(builders)

        return builders.values.mapNotNull(ContactBuilder::build)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun loadPhones(builders: Map<Long, ContactBuilder>) {
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val numberColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            while (cursor.moveToNext()) {
                builders[cursor.getLong(idColumn)]?.offerPhone(
                    number = cursor.getString(numberColumn).orEmpty(),
                    type = cursor.getInt(typeColumn),
                )
            }
        }
    }

    private fun loadAddresses(builders: Map<Long, ContactBuilder>) {
        resolver.query(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID,
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD,
                ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID)
            val formattedColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
            )
            val componentColumns = listOf(
                ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD,
                ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
            ).map(cursor::getColumnIndexOrThrow)
            while (cursor.moveToNext()) {
                val builder = builders[cursor.getLong(idColumn)] ?: continue
                if (builder.address.isNotEmpty()) continue
                val formatted = cursor.getString(formattedColumn).orEmpty().trim()
                builder.address = formatted.ifEmpty {
                    componentColumns.map { cursor.getString(it).orEmpty().trim() }
                        .filter(String::isNotEmpty)
                        .distinct()
                        .joinToString(" ")
                }
            }
        }
    }

    private fun loadOrganizations(builders: Map<Long, ContactBuilder>) {
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.CommonDataKinds.Organization.COMPANY,
                ContactsContract.CommonDataKinds.Organization.DEPARTMENT,
                ContactsContract.CommonDataKinds.Organization.TITLE,
            ),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val valueColumns = listOf(
                ContactsContract.CommonDataKinds.Organization.COMPANY,
                ContactsContract.CommonDataKinds.Organization.DEPARTMENT,
                ContactsContract.CommonDataKinds.Organization.TITLE,
            ).map(cursor::getColumnIndexOrThrow)
            while (cursor.moveToNext()) {
                val builder = builders[cursor.getLong(idColumn)] ?: continue
                val values = valueColumns.map { cursor.getString(it).orEmpty().trim() }
                    .filter(String::isNotEmpty)
                    .distinct()
                if (builder.name.isBlank()) builder.name = values.firstOrNull().orEmpty()
                builder.notes += values
            }
        }
    }

    private fun groupMembershipCounts(): Map<Long, Set<Long>> {
        val members = mutableMapOf<Long, MutableSet<Long>>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
            ),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            val contactColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val groupColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
            )
            while (cursor.moveToNext()) {
                members.getOrPut(cursor.getLong(groupColumn), ::mutableSetOf) += cursor.getLong(contactColumn)
            }
        }
        return members
    }

    private fun requirePermission() {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "연락처 접근 권한이 필요합니다." }
    }

    private fun requireReadWritePermission() {
        requirePermission()
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "연락처 저장 권한이 필요합니다." }
    }

    private data class ContactBuilder(
        val id: Long,
        var name: String,
        var phone: String = "",
        var phonePriority: Int = Int.MAX_VALUE,
        var address: String = "",
        val notes: MutableList<String> = mutableListOf(),
    ) {
        fun offerPhone(number: String, type: Int) {
            val candidate = number.trim()
            if (candidate.isEmpty()) return
            val priority = when (type) {
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE,
                -> 0
                else -> 1
            }
            if (phone.isEmpty() || priority < phonePriority) {
                phone = candidate
                phonePriority = priority
            }
        }

        fun build(): ContactImportRecord? {
            val resolvedName = name.trim()
            if (resolvedName.isEmpty() && phone.isEmpty()) return null
            return ContactImportRecord(
                contactIdentifier = id.toString(),
                name = resolvedName,
                phoneNumber = phone,
                address = address,
                notes = notes.distinct().joinToString(" · "),
            )
        }
    }

    companion object {
        private const val EXPORT_BATCH_SIZE = 100
        private const val MANAGED_GROUP_PREFIX = "soheeya-gaja-list-"
        private const val MANAGED_CONTACT_PREFIX = "soheeya-gaja-customer-"
        private const val MANAGED_CONTACT_NOTE = "소희야 가자 고객 ID: "
    }
}
