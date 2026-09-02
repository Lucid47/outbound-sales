package com.lucid47.soheeyagaja.importing

data class ContactImportRecord(
    val contactIdentifier: String,
    val name: String,
    val phoneNumber: String,
    val address: String,
    val notes: String,
)

sealed interface ContactImportDestination {
    data class ExistingList(val listId: Long) : ContactImportDestination
    data class NewList(val name: String) : ContactImportDestination
}

data class ContactImportResult(
    val listId: Long,
    val addedCount: Int,
    val skippedCount: Int,
)
