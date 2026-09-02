package com.lucid47.soheeyagaja.customers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerListSummary
import com.lucid47.soheeyagaja.data.CustomerWithFields
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class CustomerEditorState(
    val isVisible: Boolean = false,
    val customerId: Long? = null,
    val draft: CustomerDraft = CustomerDraft(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

data class CustomerManagementUiState(
    val selectedListId: Long? = null,
    val searchQuery: String = "",
    val selectedCustomerId: Long? = null,
    val editor: CustomerEditorState = CustomerEditorState(),
    val renameListId: Long? = null,
    val renameValue: String = "",
    val deleteListId: Long? = null,
    val deleteCustomerId: Long? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CustomerManagementRepository(AppDatabase.get(application))
    private val _uiState = MutableStateFlow(CustomerManagementUiState())
    val uiState = _uiState

    val customerLists = repository.observeCustomerLists().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val selectedListCustomers = _uiState
        .map { state -> state.selectedListId }
        .distinctUntilChanged()
        .flatMapLatest { state ->
            state?.let(repository::observeCustomers) ?: flowOf(emptyList())
        }

    val customers = combine(selectedListCustomers, _uiState) { customers, state ->
        val query = state.searchQuery.trim()
        if (query.isEmpty()) {
            customers
        } else {
            customers.filter { record ->
                val customer = record.customer
                listOf(
                    customer.name,
                    customer.phone,
                    customer.address,
                    customer.ownedAddress,
                    customer.parcelAddress,
                    customer.notes,
                ).any { it.contains(query, ignoreCase = true) } ||
                    record.customFields.any {
                        it.label.contains(query, ignoreCase = true) ||
                            it.value.contains(query, ignoreCase = true)
                    }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedCustomer = _uiState
        .map { state -> state.selectedCustomerId }
        .distinctUntilChanged()
        .flatMapLatest { customerId ->
            customerId?.let(repository::observeCustomer) ?: flowOf(null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            customerLists.collect { lists ->
                _uiState.update { state ->
                    val selectedExists = lists.any { it.id == state.selectedListId }
                    state.copy(selectedListId = if (selectedExists) state.selectedListId else lists.firstOrNull()?.id)
                }
            }
        }
    }

    fun selectList(listId: Long) {
        _uiState.update { it.copy(selectedListId = listId, selectedCustomerId = null, searchQuery = "") }
    }

    fun updateSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun openCustomer(customerId: Long) {
        _uiState.update { it.copy(selectedCustomerId = customerId) }
    }

    fun closeCustomer() {
        _uiState.update { it.copy(selectedCustomerId = null, deleteCustomerId = null) }
    }

    fun openCreateCustomer() {
        if (_uiState.value.selectedListId == null) {
            _uiState.update { it.copy(errorMessage = "먼저 고객리스트를 만들어주세요.") }
            return
        }
        _uiState.update { it.copy(editor = CustomerEditorState(isVisible = true)) }
    }

    fun openEditCustomer(record: CustomerWithFields) {
        val customer = record.customer
        _uiState.update {
            it.copy(
                editor = CustomerEditorState(
                    isVisible = true,
                    customerId = customer.id,
                    draft = CustomerDraft(
                        name = customer.name,
                        phone = customer.phone,
                        address = customer.address,
                        ownedAddress = customer.ownedAddress,
                        parcelAddress = customer.parcelAddress,
                        birthDate = customer.birthDate,
                        notes = customer.notes,
                        isDone = customer.status == CustomerManagementRepository.STATUS_DONE,
                        customFields = record.customFields.sortedBy { field -> field.sortOrder }.map { field ->
                            CustomFieldDraft(field.label, field.value)
                        },
                    ),
                ),
            )
        }
    }

    fun updateDraft(draft: CustomerDraft) {
        _uiState.update { it.copy(editor = it.editor.copy(draft = draft, errorMessage = null)) }
    }

    fun closeEditor() {
        _uiState.update { it.copy(editor = CustomerEditorState()) }
    }

    fun saveCustomer() {
        val state = _uiState.value
        val editor = state.editor
        val listId = state.selectedListId ?: return
        if (editor.draft.name.isBlank()) {
            _uiState.update {
                it.copy(editor = it.editor.copy(errorMessage = "고객 이름을 입력해주세요."))
            }
            return
        }
        _uiState.update { it.copy(editor = it.editor.copy(isSaving = true, errorMessage = null)) }
        viewModelScope.launch {
            runCatching {
                editor.customerId?.let { repository.updateCustomer(it, editor.draft) }
                    ?: repository.createCustomer(listId, editor.draft)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        editor = CustomerEditorState(),
                        statusMessage = if (editor.customerId == null) "고객을 추가했습니다." else "고객 정보를 수정했습니다.",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(editor = it.editor.copy(isSaving = false, errorMessage = error.message))
                }
            }
        }
    }

    fun requestCustomerDelete(customerId: Long) {
        _uiState.update { it.copy(deleteCustomerId = customerId) }
    }

    fun cancelCustomerDelete() {
        _uiState.update { it.copy(deleteCustomerId = null) }
    }

    fun confirmCustomerDelete() {
        val customerId = _uiState.value.deleteCustomerId ?: return
        viewModelScope.launch {
            runCatching { repository.deleteCustomer(customerId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            deleteCustomerId = null,
                            selectedCustomerId = null,
                            statusMessage = "고객을 삭제했습니다.",
                        )
                    }
                }
                .onFailure(::showError)
        }
    }

    fun requestListRename(list: CustomerListSummary) {
        _uiState.update { it.copy(renameListId = list.id, renameValue = list.name) }
    }

    fun updateRenameValue(value: String) {
        _uiState.update { it.copy(renameValue = value) }
    }

    fun cancelListRename() {
        _uiState.update { it.copy(renameListId = null, renameValue = "") }
    }

    fun confirmListRename() {
        val state = _uiState.value
        val listId = state.renameListId ?: return
        viewModelScope.launch {
            runCatching { repository.renameCustomerList(listId, state.renameValue) }
                .onSuccess {
                    _uiState.update {
                        it.copy(renameListId = null, renameValue = "", statusMessage = "고객리스트 이름을 변경했습니다.")
                    }
                }
                .onFailure(::showError)
        }
    }

    fun requestListDelete(listId: Long) {
        _uiState.update { it.copy(deleteListId = listId) }
    }

    fun cancelListDelete() {
        _uiState.update { it.copy(deleteListId = null) }
    }

    fun confirmListDelete() {
        val listId = _uiState.value.deleteListId ?: return
        viewModelScope.launch {
            runCatching { repository.deleteCustomerList(listId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            deleteListId = null,
                            selectedCustomerId = null,
                            statusMessage = "고객리스트와 포함된 고객을 삭제했습니다.",
                        )
                    }
                }
                .onFailure(::showError)
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    private fun showError(error: Throwable) {
        _uiState.update {
            it.copy(errorMessage = error.message ?: "작업을 완료하지 못했습니다.")
        }
    }
}
