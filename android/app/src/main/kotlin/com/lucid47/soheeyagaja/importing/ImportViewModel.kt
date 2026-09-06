package com.lucid47.soheeyagaja.importing

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucid47.soheeyagaja.contacts.AndroidContactService
import com.lucid47.soheeyagaja.contacts.DeviceContactGroup
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.domain.importing.ImportProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportUiState(
    val selectedUri: Uri? = null,
    val selectedFileName: String = "",
    val listName: String = "",
    val isImporting: Boolean = false,
    val progress: ImportProgress = ImportProgress(),
    val resultMessage: String? = null,
    val errorMessage: String? = null,
    val previewOpen: Boolean = false,
    val previewPage: Int = 0,
    val previewRows: List<com.lucid47.soheeyagaja.domain.importing.ImportedCustomer> = emptyList(),
    val previewHasMore: Boolean = false,
    val selectAllRows: Boolean = true,
    val selectionExceptions: Set<Long> = emptySet(),
)

enum class ContactImportStep {
    CLOSED,
    CONTACTS,
    GROUPS,
    DESTINATION,
}

enum class ContactDestinationMode {
    EXISTING_LIST,
    NEW_LIST,
}

data class ContactImportUiState(
    val step: ContactImportStep = ContactImportStep.CLOSED,
    val isLoading: Boolean = false,
    val contacts: List<ContactImportRecord> = emptyList(),
    val groups: List<DeviceContactGroup> = emptyList(),
    val selectedContactIds: Set<String> = emptySet(),
    val selectedGroupIds: Set<Long> = emptySet(),
    val searchQuery: String = "",
    val destinationMode: ContactDestinationMode = ContactDestinationMode.NEW_LIST,
    val selectedListId: Long? = null,
    val newListName: String = "연락처 가져오기",
    val skipDuplicatePhones: Boolean = true,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CustomerImportRepository(AppDatabase.get(application))
    private val contactService = AndroidContactService(application)
    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState = _uiState.asStateFlow()
    private val _contactUiState = MutableStateFlow(ContactImportUiState())
    val contactUiState = _contactUiState.asStateFlow()

    val customerLists = repository.observeCustomerLists().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun selectFile(uri: Uri) {
        val displayName = resolveDisplayName(uri)
        _uiState.update { state ->
            state.copy(
                selectedUri = uri,
                previewOpen = false,
                selectionExceptions = emptySet(),
                selectAllRows = true,
                selectedFileName = displayName,
                listName = displayName.substringBeforeLast('.').ifBlank { "새 고객리스트" },
                progress = ImportProgress(),
                resultMessage = null,
                errorMessage = null,
            )
        }
    }

    fun updateListName(value: String) {
        _uiState.update { it.copy(listName = value, resultMessage = null, errorMessage = null) }
    }

    fun previewCsv(page: Int = 0) {
        val uri = _uiState.value.selectedUri ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { repository.previewCsv(it, page) }
            } }.onSuccess { (rows, more) -> _uiState.update { it.copy(previewOpen = true, previewPage = page, previewRows = rows, previewHasMore = more) } }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = error.message) } }
        }
    }

    fun closeCsvPreview() { _uiState.update { it.copy(previewOpen = false) } }
    fun selectAllCsvRows(value: Boolean) { _uiState.update { it.copy(selectAllRows = value, selectionExceptions = emptySet()) } }
    fun toggleCsvRow(row: Long) { _uiState.update { it.copy(selectionExceptions = if (row in it.selectionExceptions) it.selectionExceptions - row else it.selectionExceptions + row) } }

    fun importSelectedFile() {
        val request = _uiState.value
        if (request.selectedUri == null) {
            _uiState.update { it.copy(errorMessage = "가져올 CSV 파일을 선택해주세요.") }
            return
        }
        if (request.listName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "고객리스트 이름을 입력해주세요.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    previewOpen = false,
                    progress = ImportProgress(),
                    resultMessage = null,
                    errorMessage = null,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(request.selectedUri).use { source ->
                        requireNotNull(source) { "선택한 파일을 열 수 없습니다." }
                        repository.importCsv(
                            source = source,
                            listName = request.listName,
                            sourceName = request.selectedFileName,
                            selectAll = request.selectAllRows,
                            selectionExceptions = request.selectionExceptions,
                            onProgress = { progress ->
                                _uiState.update { it.copy(progress = progress) }
                            },
                        )
                    }
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = result.progress,
                        resultMessage = "${result.progress.acceptedRows}명의 고객을 추가했습니다.",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = error.message ?: "가져오기에 실패했습니다.",
                    )
                }
            }
        }
    }

    fun openContactSelection() {
        loadContactData(ContactImportStep.CONTACTS) { contactService.allContacts() }
    }

    fun openGroupSelection() {
        _contactUiState.update {
            it.copy(
                step = ContactImportStep.GROUPS,
                isLoading = true,
                groups = emptyList(),
                selectedGroupIds = emptySet(),
                searchQuery = "",
                statusMessage = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { contactService.groups() }
                .onSuccess { groups ->
                    _contactUiState.update {
                        it.copy(
                            isLoading = false,
                            groups = groups,
                            errorMessage = if (groups.isEmpty()) "연락처 그룹이 없습니다." else null,
                        )
                    }
                }
                .onFailure(::showContactError)
        }
    }

    fun contactPermissionDenied() {
        _contactUiState.update {
            it.copy(
                step = ContactImportStep.CLOSED,
                isLoading = false,
                errorMessage = "연락처 접근 권한이 필요합니다. 설정에서 연락처 권한을 허용해주세요.",
            )
        }
    }

    fun closeContactImport() {
        _contactUiState.update {
            ContactImportUiState(
                statusMessage = it.statusMessage,
                errorMessage = it.errorMessage,
            )
        }
    }

    fun updateContactSearch(value: String) {
        _contactUiState.update { it.copy(searchQuery = value) }
    }

    fun toggleContact(contactId: String) {
        _contactUiState.update { state ->
            state.copy(selectedContactIds = state.selectedContactIds.toggle(contactId))
        }
    }

    fun selectContacts(contactIds: Set<String>) {
        _contactUiState.update { it.copy(selectedContactIds = contactIds) }
    }

    fun toggleGroup(groupId: Long) {
        _contactUiState.update { state ->
            state.copy(selectedGroupIds = state.selectedGroupIds.toggle(groupId))
        }
    }

    fun continueContactSelection() {
        val state = _contactUiState.value
        val selected = state.contacts.filter { it.contactIdentifier in state.selectedContactIds }
        prepareContactDestination(selected, "연락처 가져오기")
    }

    fun continueGroupSelection() {
        val state = _contactUiState.value
        if (state.selectedGroupIds.isEmpty()) return
        _contactUiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { contactService.contactsInGroups(state.selectedGroupIds) }
                .onSuccess { contacts ->
                    val selectedGroups = state.groups.filter { it.id in state.selectedGroupIds }
                    val defaultName = if (selectedGroups.size == 1) {
                        selectedGroups.single().name
                    } else {
                        "연락처 그룹 ${selectedGroups.size}개"
                    }
                    prepareContactDestination(contacts, defaultName)
                }
                .onFailure(::showContactError)
        }
    }

    fun updateContactDestinationMode(mode: ContactDestinationMode) {
        _contactUiState.update { it.copy(destinationMode = mode) }
    }

    fun selectContactDestinationList(listId: Long) {
        _contactUiState.update { it.copy(selectedListId = listId) }
    }

    fun updateContactListName(value: String) {
        _contactUiState.update { it.copy(newListName = value) }
    }

    fun updateSkipDuplicatePhones(value: Boolean) {
        _contactUiState.update { it.copy(skipDuplicatePhones = value) }
    }

    fun saveContactImport() {
        val state = _contactUiState.value
        if (state.contacts.isEmpty()) return
        val destination = when (state.destinationMode) {
            ContactDestinationMode.EXISTING_LIST -> {
                val listId = state.selectedListId ?: return
                ContactImportDestination.ExistingList(listId)
            }
            ContactDestinationMode.NEW_LIST -> ContactImportDestination.NewList(state.newListName)
        }

        _contactUiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.importContacts(
                        contacts = state.contacts,
                        destination = destination,
                        skipDuplicatePhones = state.skipDuplicatePhones,
                    )
                }
            }.onSuccess { result ->
                val duplicateText = if (result.skippedCount > 0) {
                    " 중복 ${result.skippedCount}명은 건너뛰었습니다."
                } else {
                    ""
                }
                _contactUiState.value = ContactImportUiState(
                    statusMessage = "${result.addedCount}명의 연락처를 가져왔습니다.$duplicateText",
                )
            }.onFailure(::showContactError)
        }
    }

    private fun loadContactData(
        step: ContactImportStep,
        loader: suspend () -> List<ContactImportRecord>,
    ) {
        _contactUiState.update {
            it.copy(
                step = step,
                isLoading = true,
                contacts = emptyList(),
                selectedContactIds = emptySet(),
                searchQuery = "",
                statusMessage = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { loader() }
                .onSuccess { contacts ->
                    _contactUiState.update {
                        it.copy(
                            isLoading = false,
                            contacts = contacts,
                            errorMessage = if (contacts.isEmpty()) "가져올 연락처가 없습니다." else null,
                        )
                    }
                }
                .onFailure(::showContactError)
        }
    }

    private fun prepareContactDestination(contacts: List<ContactImportRecord>, defaultName: String) {
        if (contacts.isEmpty()) {
            _contactUiState.update { it.copy(isLoading = false, errorMessage = "연락처를 선택해주세요.") }
            return
        }
        val lists = customerLists.value
        _contactUiState.update {
            it.copy(
                step = ContactImportStep.DESTINATION,
                isLoading = false,
                contacts = contacts,
                destinationMode = if (lists.isEmpty()) {
                    ContactDestinationMode.NEW_LIST
                } else {
                    ContactDestinationMode.EXISTING_LIST
                },
                selectedListId = lists.firstOrNull()?.id,
                newListName = defaultName,
                errorMessage = null,
            )
        }
    }

    private fun showContactError(error: Throwable) {
        _contactUiState.update {
            it.copy(
                isLoading = false,
                errorMessage = error.message ?: "연락처를 불러오지 못했습니다.",
            )
        }
    }

    private fun <T> Set<T>.toggle(value: T): Set<T> =
        if (value in this) this - value else this + value

    private fun resolveDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty().ifBlank { "고객리스트.csv" }
    }
}
