package com.lucid47.soheeyagaja.domain.importing

enum class ImportField {
    NAME,
    PHONE,
    ADDRESS,
    OWNED_ADDRESS,
    PARCEL_ADDRESS,
    NOTES,
}

data class HeaderMapping(
    val headers: List<String>,
    val columns: Map<ImportField, List<Int>>,
) {
    fun values(field: ImportField, row: List<String>): List<String> =
        columns[field].orEmpty()
            .mapNotNull { index -> row.getOrNull(index)?.trim()?.takeIf(String::isNotEmpty) }

    fun first(field: ImportField, row: List<String>): String = values(field, row).firstOrNull().orEmpty()

    companion object {
        private val aliases = mapOf(
            ImportField.NAME to setOf(
                "이름", "성명", "고객", "고객명", "고객이름", "name", "customer", "customername",
            ),
            ImportField.PHONE to setOf(
                "전화", "전화번호", "휴대폰", "휴대폰번호", "핸드폰", "핸드폰번호", "연락처", "연락처번호",
                "휴대전화", "모바일", "phone", "phonenumber", "mobile", "tel", "telephone",
            ),
            ImportField.ADDRESS to setOf(
                "주소", "도로명", "도로명주소", "거주지", "자택주소", "address", "streetaddress",
            ),
            ImportField.OWNED_ADDRESS to setOf(
                "소유", "소유주소", "보유", "보유주소", "보유부동산", "부동산", "owned", "ownedaddress",
            ),
            ImportField.PARCEL_ADDRESS to setOf(
                "소유지번", "지번", "지번주소", "소재지", "토지소재지", "parcel", "parceladdress", "lotaddress",
            ),
            ImportField.NOTES to setOf(
                "메모", "비고", "참고", "특이사항", "내용", "notes", "note", "memo", "remark", "remarks",
            ),
        )

        fun detect(headers: List<String>): HeaderMapping {
            val detected = mutableMapOf<ImportField, MutableList<Int>>()
            headers.forEachIndexed { index, header ->
                val normalized = normalizeHeader(header)
                val field = aliases.entries.firstOrNull { normalized in it.value }?.key
                if (field != null) detected.getOrPut(field, ::mutableListOf) += index
            }
            return HeaderMapping(headers, detected)
        }

        private fun normalizeHeader(value: String): String = value
            .removePrefix("\uFEFF")
            .lowercase()
            .filter(Char::isLetterOrDigit)
    }
}

data class ImportedCustomer(
    val sourceRow: Long,
    val name: String,
    val phone: String,
    val normalizedPhone: String,
    val address: String,
    val ownedAddress: String,
    val parcelAddress: String,
    val notes: String,
) {
    val duplicateKey: String
        get() = duplicateKey(name, normalizedPhone, address, ownedAddress, parcelAddress)

    companion object {
        fun duplicateKey(
            name: String,
            normalizedPhone: String,
            address: String,
            ownedAddress: String = "",
            parcelAddress: String = "",
        ): String {
            if (normalizedPhone.isNotBlank()) return "phone:$normalizedPhone"
            val normalizedName = name.lowercase().filterNot(Char::isWhitespace)
            val location = sequenceOf(address, ownedAddress, parcelAddress)
                .firstOrNull(String::isNotBlank)
                .orEmpty()
                .lowercase()
                .filterNot(Char::isWhitespace)
            return "name:$normalizedName|$location"
        }

        fun normalizePhone(value: String): String {
            val digits = value.filter(Char::isDigit)
            return when {
                digits.startsWith("82") && digits.length >= 11 -> "0${digits.drop(2)}"
                else -> digits
            }
        }
    }
}

data class ImportProgress(
    val processedRows: Long = 0,
    val acceptedRows: Long = 0,
    val duplicateRows: Long = 0,
    val invalidRows: Long = 0,
)

data class CustomerImportBatch(
    val customers: List<ImportedCustomer>,
    val progress: ImportProgress,
)
