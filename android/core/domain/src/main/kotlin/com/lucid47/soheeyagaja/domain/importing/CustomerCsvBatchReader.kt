package com.lucid47.soheeyagaja.domain.importing

import java.io.Reader

class CustomerCsvBatchReader(
    reader: Reader,
    existingDuplicateKeys: Set<String> = emptySet(),
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val csv = CsvRecordReader(reader)
    private val seenKeys = existingDuplicateKeys.toMutableSet()
    val mapping: HeaderMapping

    private var finished = false
    private var progress = ImportProgress()

    init {
        require(batchSize > 0) { "batchSize must be positive" }
        val headers = nextNonEmptyRecord() ?: throw IllegalArgumentException("CSV 파일에 헤더가 없습니다.")
        mapping = HeaderMapping.detect(headers)
        require(mapping.columns.containsKey(ImportField.NAME)) {
            "이름 또는 고객명 헤더를 찾을 수 없습니다."
        }
        require(
            mapping.columns.keys.any {
                it == ImportField.PHONE || it == ImportField.ADDRESS ||
                    it == ImportField.OWNED_ADDRESS || it == ImportField.PARCEL_ADDRESS
            },
        ) { "전화번호 또는 주소 헤더를 하나 이상 매핑해야 합니다." }
    }

    fun readBatch(): CustomerImportBatch? {
        if (finished) return null
        val accepted = ArrayList<ImportedCustomer>(batchSize)

        while (accepted.size < batchSize) {
            val row = csv.readRecord()
            if (row == null) {
                finished = true
                break
            }
            if (row.all(String::isBlank)) continue

            progress = progress.copy(processedRows = progress.processedRows + 1)
            val customer = row.toCustomer(progress.processedRows + 1)
            if (customer == null) {
                progress = progress.copy(invalidRows = progress.invalidRows + 1)
                continue
            }
            if (!seenKeys.add(customer.duplicateKey)) {
                progress = progress.copy(duplicateRows = progress.duplicateRows + 1)
                continue
            }
            accepted += customer
            progress = progress.copy(acceptedRows = progress.acceptedRows + 1)
        }

        if (accepted.isEmpty() && finished) return null
        return CustomerImportBatch(accepted, progress)
    }

    fun currentProgress(): ImportProgress = progress

    private fun nextNonEmptyRecord(): List<String>? {
        while (true) {
            val row = csv.readRecord() ?: return null
            if (row.any(String::isNotBlank)) return row
        }
    }

    private fun List<String>.toCustomer(sourceRow: Long): ImportedCustomer? {
        val name = mapping.first(ImportField.NAME, this).trim()
        val phone = mapping.first(ImportField.PHONE, this).trim()
        val normalizedPhone = ImportedCustomer.normalizePhone(phone)
        val address = mapping.values(ImportField.ADDRESS, this).joinDistinct()
        val ownedAddress = mapping.values(ImportField.OWNED_ADDRESS, this).joinDistinct()
        val parcelAddress = mapping.values(ImportField.PARCEL_ADDRESS, this).joinDistinct()
        val notes = mapping.values(ImportField.NOTES, this).joinDistinct("\n")

        if (name.isBlank()) return null
        if (normalizedPhone.isBlank() && address.isBlank() && ownedAddress.isBlank() && parcelAddress.isBlank()) {
            return null
        }
        return ImportedCustomer(
            sourceRow = sourceRow,
            name = name,
            phone = phone,
            normalizedPhone = normalizedPhone,
            address = address,
            ownedAddress = ownedAddress,
            parcelAddress = parcelAddress,
            notes = notes,
        )
    }

    private fun List<String>.joinDistinct(separator: String = " | "): String =
        distinct().joinToString(separator)

    companion object {
        const val DEFAULT_BATCH_SIZE = 200
    }
}
