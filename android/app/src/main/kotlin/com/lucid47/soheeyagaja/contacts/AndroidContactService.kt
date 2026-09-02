package com.lucid47.soheeyagaja.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.lucid47.soheeyagaja.importing.ContactImportRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceContactGroup(
    val id: Long,
    val name: String,
    val accountName: String,
    val contactCount: Int,
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
}
