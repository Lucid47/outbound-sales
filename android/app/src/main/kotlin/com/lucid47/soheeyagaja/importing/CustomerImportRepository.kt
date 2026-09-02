package com.lucid47.soheeyagaja.importing

import androidx.room.withTransaction
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerEntity
import com.lucid47.soheeyagaja.data.CustomerListEntity
import com.lucid47.soheeyagaja.domain.importing.CustomerCsvBatchReader
import com.lucid47.soheeyagaja.domain.importing.ImportProgress
import com.lucid47.soheeyagaja.domain.importing.ImportedCustomer
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class ImportResult(
    val listId: Long,
    val progress: ImportProgress,
)

class CustomerImportRepository(private val database: AppDatabase) {
    fun observeCustomerLists() = database.customerListDao().observeSummaries()

    suspend fun importCsv(
        source: InputStream,
        listName: String,
        sourceName: String,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult = database.withTransaction {
        val createdAt = System.currentTimeMillis()
        val listId = database.customerListDao().insert(
            CustomerListEntity(
                name = listName.trim(),
                sourceName = sourceName,
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = createdAt,
            ),
        )

        val buffered = source.bufferedForDetection()
        val charset = buffered.detectCharsetAndSkipBom()
        val reader = CustomerCsvBatchReader(InputStreamReader(buffered, charset))
        var lastProgress = ImportProgress()

        while (true) {
            val batch = reader.readBatch() ?: break
            if (batch.customers.isNotEmpty()) {
                database.customerDao().insert(
                    batch.customers.map { customer ->
                        CustomerEntity(
                            listId = listId,
                            sourceRow = customer.sourceRow,
                            name = customer.name,
                            phone = customer.phone,
                            normalizedPhone = customer.normalizedPhone,
                            address = customer.address,
                            ownedAddress = customer.ownedAddress,
                            parcelAddress = customer.parcelAddress,
                            notes = customer.notes,
                            duplicateKey = customer.duplicateKey,
                            createdAtEpochMillis = createdAt,
                        )
                    },
                )
            }
            lastProgress = batch.progress
            onProgress(lastProgress)
        }

        lastProgress = reader.currentProgress()
        onProgress(lastProgress)

        require(lastProgress.acceptedRows > 0) { "추가할 수 있는 고객 데이터가 없습니다." }
        ImportResult(listId, lastProgress)
    }

    suspend fun importContacts(
        contacts: List<ContactImportRecord>,
        destination: ContactImportDestination,
        skipDuplicatePhones: Boolean,
    ): ContactImportResult = database.withTransaction {
        require(contacts.isNotEmpty()) { "선택한 연락처가 없습니다." }
        val now = System.currentTimeMillis()
        val listId = when (destination) {
            is ContactImportDestination.NewList -> {
                val name = destination.name.trim()
                require(name.isNotEmpty()) { "고객리스트 이름을 입력해주세요." }
                database.customerListDao().insert(
                    CustomerListEntity(
                        name = name,
                        sourceName = "contacts",
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
            }

            is ContactImportDestination.ExistingList -> {
                require(database.customerListDao().exists(destination.listId)) {
                    "추가할 고객리스트를 찾지 못했습니다."
                }
                destination.listId
            }
        }

        val existingPhones = if (skipDuplicatePhones) {
            database.customerDao().allNormalizedPhones().toMutableSet()
        } else {
            mutableSetOf()
        }
        val uniqueContacts = contacts.distinctBy(ContactImportRecord::contactIdentifier)
        var skipped = contacts.size - uniqueContacts.size
        var added = 0

        uniqueContacts.chunked(CustomerCsvBatchReader.DEFAULT_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            val entities = batch.mapIndexedNotNull { rowIndex, contact ->
                val normalizedPhone = ImportedCustomer.normalizePhone(contact.phoneNumber)
                if (skipDuplicatePhones && normalizedPhone.isNotEmpty() && !existingPhones.add(normalizedPhone)) {
                    skipped += 1
                    null
                } else {
                    val resolvedName = contact.name.trim().ifEmpty { "이름 없음" }
                    val duplicateKey = if (skipDuplicatePhones && normalizedPhone.isNotEmpty()) {
                        "phone:$normalizedPhone"
                    } else {
                        "contact:${contact.contactIdentifier}"
                    }
                    CustomerEntity(
                        listId = listId,
                        sourceRow = (
                            batchIndex * CustomerCsvBatchReader.DEFAULT_BATCH_SIZE + rowIndex + 1
                        ).toLong(),
                        name = resolvedName,
                        phone = contact.phoneNumber.trim(),
                        normalizedPhone = normalizedPhone,
                        address = contact.address.trim(),
                        ownedAddress = "",
                        parcelAddress = "",
                        notes = contact.notes.trim(),
                        contactIdentifier = contact.contactIdentifier,
                        contactRegisteredName = contact.name.trim().ifEmpty { null },
                        duplicateKey = duplicateKey,
                        createdAtEpochMillis = now,
                    )
                }
            }
            val inserted = database.customerDao().insert(entities).count { it != -1L }
            added += inserted
            skipped += entities.size - inserted
        }

        database.customerListDao().touch(listId, now)
        ContactImportResult(listId = listId, addedCount = added, skippedCount = skipped)
    }

    private fun InputStream.bufferedForDetection(): BufferedInputStream =
        if (this is BufferedInputStream) this else BufferedInputStream(this, SAMPLE_SIZE + 4)

    private fun BufferedInputStream.detectCharsetAndSkipBom(): Charset {
        mark(SAMPLE_SIZE + 4)
        val sample = ByteArray(SAMPLE_SIZE)
        val count = read(sample)
        reset()
        if (count <= 0) return Charsets.UTF_8

        val bytes = sample.copyOf(count)
        return when {
            bytes.startsWith(UTF8_BOM) -> Charsets.UTF_8.also { skipFully(UTF8_BOM.size) }
            bytes.startsWith(UTF16_LE_BOM) -> Charsets.UTF_16LE.also { skipFully(UTF16_LE_BOM.size) }
            bytes.startsWith(UTF16_BE_BOM) -> Charsets.UTF_16BE.also { skipFully(UTF16_BE_BOM.size) }
            bytes.isValidUtf8() -> Charsets.UTF_8
            else -> Charset.forName("MS949")
        }
    }

    private fun BufferedInputStream.skipFully(count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.isValidUtf8(): Boolean {
        val result = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this), CharBuffer.allocate(size), false)
        return !result.isError
    }

    companion object {
        private const val SAMPLE_SIZE = 64 * 1024
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
}
