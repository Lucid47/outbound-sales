package com.lucid47.soheeyagaja.customers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucid47.soheeyagaja.activities.ActivityRepository
import com.lucid47.soheeyagaja.dashboard.DashboardPaletteFamily
import com.lucid47.soheeyagaja.dashboard.DashboardRepository
import com.lucid47.soheeyagaja.contacts.AndroidContactService
import com.lucid47.soheeyagaja.contacts.ManagedContactGroup
import com.lucid47.soheeyagaja.location.CustomerLocationRepository
import com.lucid47.soheeyagaja.media.CustomerMediaRepository
import java.io.File
import android.net.Uri
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.CustomerListSummary
import com.lucid47.soheeyagaja.data.CustomerWithFields
import com.lucid47.soheeyagaja.data.HistoryEntryRecord
import com.lucid47.soheeyagaja.data.AudioMemoEntity
import com.lucid47.soheeyagaja.data.PhotoMemoEntity
import java.time.LocalDate
import java.time.ZoneId
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

enum class HistoryKindFilter {
    ALL,
    TOUCH,
    VISIT,
    DONE,
}

enum class MemoMode {
    TEXT_MEMO,
    VISIT_DETAIL,
}

data class MemoEditorState(
    val customerId: Long,
    val mode: MemoMode,
    val text: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

fun historyTitle(entry: HistoryEntryRecord): String = when (entry.type) {
    ActivityRepository.TYPE_CALL -> "전화 시도"
    ActivityRepository.TYPE_MANUAL_SMS -> "문자 시도"
    ActivityRepository.TYPE_NOTE -> "텍스트 메모"
    ActivityRepository.TYPE_STATUS_COMPLETE -> "완료 처리"
    ActivityRepository.TYPE_STATUS_REOPEN -> "완료 취소"
    "PROCESS_STATUS" -> "프로세스 변경"
    ActivityRepository.VISIT_QUICK -> "방문"
    ActivityRepository.VISIT_TEXT_MEMO -> "텍스트 메모"
    "PHOTO_MEMO" -> "사진 메모"
    "AUDIO_MEMO" -> "음성 메모"
    else -> "고객 터치"
}

data class CustomerManagementUiState(
    val selectedListId: Long? = null,
    val searchQuery: String = "",
    val selectedCustomerId: Long? = null,
    val editor: CustomerEditorState = CustomerEditorState(),
    val renameListId: Long? = null,
    val renameValue: String = "",
    val deleteListId: Long? = null,
    val deleteCustomerId: Long? = null,
    val visitPromptCustomerId: Long? = null,
    val memoEditor: MemoEditorState? = null,
    val historyCustomerId: Long? = null,
    val historySearchQuery: String = "",
    val historyKindFilter: HistoryKindFilter = HistoryKindFilter.ALL,
    val historyDateFilterEnabled: Boolean = false,
    val historyStartEpochDay: Long = LocalDate.now().minusDays(30).toEpochDay(),
    val historyEndEpochDay: Long = LocalDate.now().toEpochDay(),
    val dashboardVisible: Boolean = false,
    val dashboardSettingsVisible: Boolean = false,
    val contactToolsVisible: Boolean = false,
    val contactPrefixEnabled: Boolean = true,
    val contactPrefix: String = "#",
    val managedContactGroups: List<ManagedContactGroup> = emptyList(),
    val contactToolsBusy: Boolean = false,
    val deleteManagedGroupId: Long? = null,
    val mapVisible: Boolean = false,
    val mapScheduleOnly: Boolean = false,
    val mapBusy: Boolean = false,
    val photoMemoCustomerId: Long? = null,
    val audioMemoCustomerId: Long? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.get(application)
    private val repository = CustomerManagementRepository(database)
    private val activityRepository = ActivityRepository(database)
    private val dashboardRepository = DashboardRepository(database)
    private val contactService = AndroidContactService(application)
    private val locationRepository = CustomerLocationRepository(application, database)
    private val mediaRepository = CustomerMediaRepository(application, database)
    private val _uiState = MutableStateFlow(CustomerManagementUiState())
    val uiState = _uiState

    val customerLists = repository.observeCustomerLists().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val dashboardStatuses = dashboardRepository.observeStatuses().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val dashboardSettings = dashboardRepository.observeSettings().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    private val selectedListCustomers = _uiState
        .map { state -> state.selectedListId }
        .distinctUntilChanged()
        .flatMapLatest { state ->
            state?.let(repository::observeCustomers) ?: flowOf(emptyList())
        }

    val allCustomers = selectedListCustomers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

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

    private val rawHistory = _uiState
        .map { state -> state.selectedListId }
        .distinctUntilChanged()
        .flatMapLatest { listId ->
            listId?.let(activityRepository::observeHistoryForList) ?: flowOf(emptyList())
        }

    val allHistoryEntries = rawHistory.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val historyEntries = combine(rawHistory, allCustomers, _uiState) { entries, customers, state ->
        val query = state.historySearchQuery.trim()
        val zone = ZoneId.systemDefault()
        val completedCustomerIds = customers
            .filter { it.customer.status == CustomerManagementRepository.STATUS_DONE }
            .mapTo(mutableSetOf()) { it.customer.id }
        entries.filter { entry ->
            val matchesType = when (state.historyKindFilter) {
                HistoryKindFilter.ALL -> true
                HistoryKindFilter.TOUCH -> entry.category == CATEGORY_CONTACT
                HistoryKindFilter.VISIT -> entry.category == CATEGORY_VISIT
                HistoryKindFilter.DONE -> entry.customerId in completedCustomerIds
            }
            val matchesQuery = query.isEmpty() || listOf(
                entry.customerName,
                historyTitle(entry),
                entry.detail,
            ).any { it.contains(query, ignoreCase = true) }
            val epochDay = java.time.Instant.ofEpochMilli(entry.occurredAtEpochMillis)
                .atZone(zone)
                .toLocalDate()
                .toEpochDay()
            val matchesDate = !state.historyDateFilterEnabled ||
                epochDay in state.historyStartEpochDay..state.historyEndEpochDay
            matchesType && matchesQuery && matchesDate
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedCustomerHistory = _uiState
        .map { state -> state.selectedCustomerId }
        .distinctUntilChanged()
        .flatMapLatest { customerId ->
            customerId?.let(activityRepository::observeHistoryForCustomer) ?: flowOf(emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyDialogEntries = _uiState
        .map { state -> state.historyCustomerId }
        .distinctUntilChanged()
        .flatMapLatest { customerId ->
            customerId?.let(activityRepository::observeHistoryForCustomer) ?: flowOf(emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todaySchedule = _uiState
        .map { state -> state.selectedListId }
        .distinctUntilChanged()
        .flatMapLatest { listId ->
            listId?.let { activityRepository.observeTodaySchedule(it) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedCustomerPhotos = _uiState
        .map { it.photoMemoCustomerId }
        .distinctUntilChanged()
        .flatMapLatest { customerId ->
            customerId?.let(mediaRepository::observePhotos) ?: flowOf(emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedCustomerAudio = _uiState
        .map { it.audioMemoCustomerId }
        .distinctUntilChanged()
        .flatMapLatest { customerId ->
            customerId?.let(mediaRepository::observeAudio) ?: flowOf(emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { dashboardRepository.ensureDefaults() }
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

    fun recordCallAttempt(customerId: Long) {
        launchActivityAction("전화 시도를 기록했습니다.") {
            activityRepository.recordCallAttempt(customerId)
        }
    }

    fun recordSmsAttempt(customerId: Long) {
        launchActivityAction("문자 시도를 기록했습니다.") {
            activityRepository.recordSmsAttempt(customerId)
        }
    }

    fun requestTextMemo(customerId: Long) {
        _uiState.update {
            it.copy(memoEditor = MemoEditorState(customerId, MemoMode.TEXT_MEMO))
        }
    }

    fun requestVisit(customerId: Long) {
        _uiState.update { it.copy(visitPromptCustomerId = customerId) }
    }

    fun cancelVisit() {
        _uiState.update { it.copy(visitPromptCustomerId = null) }
    }

    fun recordQuickVisit() {
        val customerId = _uiState.value.visitPromptCustomerId ?: return
        _uiState.update { it.copy(visitPromptCustomerId = null) }
        launchActivityAction("방문 히스토리를 기록했습니다.") {
            activityRepository.recordQuickVisit(customerId)
        }
    }

    fun openDetailedVisitMemo() {
        val customerId = _uiState.value.visitPromptCustomerId ?: return
        _uiState.update {
            it.copy(
                visitPromptCustomerId = null,
                memoEditor = MemoEditorState(customerId, MemoMode.VISIT_DETAIL),
            )
        }
    }

    fun updateMemoText(text: String) {
        _uiState.update { state ->
            state.copy(memoEditor = state.memoEditor?.copy(text = text, errorMessage = null))
        }
    }

    fun closeMemoEditor() {
        _uiState.update { it.copy(memoEditor = null) }
    }

    fun saveMemo() {
        val editor = _uiState.value.memoEditor ?: return
        if (editor.text.isBlank()) {
            _uiState.update {
                it.copy(memoEditor = editor.copy(errorMessage = "메모 내용을 입력해주세요."))
            }
            return
        }
        _uiState.update { it.copy(memoEditor = editor.copy(isSaving = true, errorMessage = null)) }
        viewModelScope.launch {
            runCatching { activityRepository.recordTextMemo(editor.customerId, editor.text) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            memoEditor = null,
                            statusMessage = if (editor.mode == MemoMode.VISIT_DETAIL) {
                                "상세 방문 메모를 기록했습니다."
                            } else {
                                "텍스트 메모를 기록했습니다."
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(memoEditor = editor.copy(isSaving = false, errorMessage = error.message))
                    }
                }
        }
    }

    fun setCustomerCompleted(customerId: Long, completed: Boolean) {
        launchActivityAction(if (completed) "완료 처리했습니다." else "완료를 취소했습니다.") {
            activityRepository.setCustomerCompleted(customerId, completed)
        }
    }

    fun addToTodaySchedule(customerId: Long) {
        launchActivityAction("오늘 스케줄에 추가했습니다.") {
            activityRepository.addToTodaySchedule(customerId)
        }
    }

    fun removeFromTodaySchedule(customerId: Long) {
        launchActivityAction("오늘 스케줄에서 제외했습니다.") {
            activityRepository.removeFromTodaySchedule(customerId)
        }
    }

    fun updateHistorySearch(query: String) {
        _uiState.update { it.copy(historySearchQuery = query) }
    }

    fun setHistoryKindFilter(filter: HistoryKindFilter) {
        _uiState.update { it.copy(historyKindFilter = filter) }
    }

    fun setHistoryDateFilterEnabled(enabled: Boolean) {
        _uiState.update { it.copy(historyDateFilterEnabled = enabled) }
    }

    fun setHistoryStartEpochDay(epochDay: Long) {
        _uiState.update { state ->
            state.copy(
                historyStartEpochDay = epochDay.coerceAtMost(state.historyEndEpochDay),
            )
        }
    }

    fun setHistoryEndEpochDay(epochDay: Long) {
        _uiState.update { state ->
            state.copy(
                historyEndEpochDay = epochDay.coerceAtLeast(state.historyStartEpochDay),
            )
        }
    }

    fun openHistoryCustomer(customerId: Long) {
        _uiState.update { it.copy(historyCustomerId = customerId) }
    }

    fun closeHistoryCustomer() {
        _uiState.update { it.copy(historyCustomerId = null) }
    }

    fun openDashboard() {
        _uiState.update { it.copy(dashboardVisible = true) }
    }

    fun closeDashboard() {
        _uiState.update { it.copy(dashboardVisible = false, dashboardSettingsVisible = false) }
    }

    fun openDashboardSettings() {
        _uiState.update { it.copy(dashboardSettingsVisible = true) }
    }

    fun closeDashboardSettings() {
        _uiState.update { it.copy(dashboardSettingsVisible = false) }
    }

    fun setDashboardStatus(customerId: Long, statusId: String) {
        launchActivityAction("고객 프로세스를 변경했습니다.") {
            dashboardRepository.setCustomerStatus(customerId, statusId)
        }
    }

    fun setDashboardStatusCount(count: Int) {
        launchActivityAction("프로세스 단계를 ${count.coerceIn(1, 10)}개로 변경했습니다.") {
            dashboardRepository.setStatusCount(count)
        }
    }

    fun renameDashboardStatus(statusId: String, name: String) {
        viewModelScope.launch {
            runCatching { dashboardRepository.renameStatus(statusId, name) }.onFailure(::showError)
        }
    }

    fun setDashboardPalette(family: DashboardPaletteFamily) {
        launchActivityAction("히트맵 색상 계열을 변경했습니다.") {
            dashboardRepository.setPalette(family)
        }
    }

    fun setDashboardLegendVisible(visible: Boolean) {
        viewModelScope.launch {
            runCatching { dashboardRepository.setLegendVisible(visible) }.onFailure(::showError)
        }
    }

    fun openContactTools() {
        _uiState.update { it.copy(contactToolsVisible = true) }
        refreshManagedContactGroups()
    }

    fun closeContactTools() {
        _uiState.update { it.copy(contactToolsVisible = false, deleteManagedGroupId = null) }
    }

    fun setContactPrefixEnabled(enabled: Boolean) {
        _uiState.update { it.copy(contactPrefixEnabled = enabled) }
    }

    fun updateContactPrefix(prefix: String) {
        _uiState.update { it.copy(contactPrefix = prefix.take(4)) }
    }

    fun refreshManagedContactGroups() {
        _uiState.update { it.copy(contactToolsBusy = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { contactService.managedGroups() }
                .onSuccess { groups ->
                    _uiState.update { it.copy(managedContactGroups = groups, contactToolsBusy = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(contactToolsBusy = false, errorMessage = error.message) }
                }
        }
    }

    fun exportSelectedListToContacts() {
        val state = _uiState.value
        val list = customerLists.value.firstOrNull { it.id == state.selectedListId }
        if (list == null) {
            showError(IllegalStateException("내보낼 고객리스트를 선택해주세요."))
            return
        }
        val records = allCustomers.value
        _uiState.update { it.copy(contactToolsBusy = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                contactService.exportCustomerGroup(
                    listId = list.id,
                    listName = list.name,
                    customers = records,
                    prefixEnabled = state.contactPrefixEnabled,
                    prefix = state.contactPrefix,
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        contactToolsBusy = false,
                        statusMessage = "${result.exportedCount}명을 연락처 그룹으로 등록했습니다.",
                    )
                }
                refreshManagedContactGroups()
            }.onFailure { error ->
                _uiState.update { it.copy(contactToolsBusy = false, errorMessage = error.message) }
            }
        }
    }

    fun requestManagedGroupDelete(groupId: Long) {
        _uiState.update { it.copy(deleteManagedGroupId = groupId) }
    }

    fun cancelManagedGroupDelete() {
        _uiState.update { it.copy(deleteManagedGroupId = null) }
    }

    fun confirmManagedGroupDelete() {
        val groupId = _uiState.value.deleteManagedGroupId ?: return
        _uiState.update { it.copy(contactToolsBusy = true, deleteManagedGroupId = null) }
        viewModelScope.launch {
            runCatching { contactService.deleteManagedGroup(groupId, deleteContacts = true) }
                .onSuccess { deletedCount ->
                    _uiState.update {
                        it.copy(
                            contactToolsBusy = false,
                            statusMessage = "연락처 그룹과 앱이 등록한 ${deletedCount}명을 삭제했습니다.",
                        )
                    }
                    refreshManagedContactGroups()
                }
                .onFailure { error ->
                    _uiState.update { it.copy(contactToolsBusy = false, errorMessage = error.message) }
                }
        }
    }

    fun openCustomerMap() {
        _uiState.update { it.copy(mapVisible = true) }
        geocodeVisibleCustomers()
    }

    fun closeCustomerMap() {
        _uiState.update { it.copy(mapVisible = false) }
    }

    fun setMapScheduleOnly(scheduleOnly: Boolean) {
        _uiState.update { it.copy(mapScheduleOnly = scheduleOnly) }
    }

    fun geocodeVisibleCustomers() {
        _uiState.update { it.copy(mapBusy = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { locationRepository.geocodeCustomers(allCustomers.value) }
                .onSuccess { count ->
                    _uiState.update {
                        it.copy(mapBusy = false, statusMessage = if (count > 0) "${count}명의 주소를 지도 좌표로 변환했습니다." else it.statusMessage)
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(mapBusy = false, errorMessage = error.message) } }
        }
    }

    fun recordQuickVisitWithLocation() {
        val customerId = _uiState.value.visitPromptCustomerId ?: return
        _uiState.update { it.copy(visitPromptCustomerId = null) }
        viewModelScope.launch {
            runCatching {
                val address = locationRepository.currentAddress()
                activityRepository.recordQuickVisit(customerId, address)
            }.onSuccess {
                _uiState.update { it.copy(statusMessage = "날짜·시간과 현재 장소를 방문 히스토리에 기록했습니다.") }
            }.onFailure(::showError)
        }
    }

    fun openPhotoMemo(customerId: Long) {
        _uiState.update { it.copy(photoMemoCustomerId = customerId) }
    }

    fun closePhotoMemo() {
        _uiState.update { it.copy(photoMemoCustomerId = null) }
    }

    fun createCameraFile(customerId: Long): File = mediaRepository.newCameraFile(customerId)

    fun saveCapturedPhoto(customerId: Long, file: File) {
        launchActivityAction("사진 메모를 저장했습니다.") {
            mediaRepository.saveCapturedPhoto(customerId, file)
        }
    }

    fun importPhotoMemos(customerId: Long, uris: List<Uri>) {
        viewModelScope.launch {
            runCatching { mediaRepository.importPhotos(customerId, uris) }
                .onSuccess { count ->
                    _uiState.update { it.copy(statusMessage = "사진 메모 ${count}장을 저장했습니다.") }
                }
                .onFailure(::showError)
        }
    }

    fun openAudioMemo(customerId: Long) {
        _uiState.update { it.copy(audioMemoCustomerId = customerId) }
    }

    fun closeAudioMemo() {
        _uiState.update { it.copy(audioMemoCustomerId = null) }
    }

    fun createAudioFile(customerId: Long): File = mediaRepository.newAudioFile(customerId)

    fun saveAudioMemo(customerId: Long, file: File, durationMillis: Long, transcript: String) {
        launchActivityAction("음성 메모를 저장했습니다.") {
            mediaRepository.saveAudioMemo(customerId, file, durationMillis, transcript)
        }
    }

    fun deletePhotoMemo(photo: PhotoMemoEntity) {
        launchActivityAction("사진 메모를 삭제했습니다.") { mediaRepository.deletePhoto(photo) }
    }

    fun deleteAudioMemo(audio: AudioMemoEntity) {
        launchActivityAction("음성 메모를 삭제했습니다.") { mediaRepository.deleteAudio(audio) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    private fun showError(error: Throwable) {
        _uiState.update {
            it.copy(errorMessage = error.message ?: "작업을 완료하지 못했습니다.")
        }
    }

    private fun launchActivityAction(successMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { _uiState.update { it.copy(statusMessage = successMessage, errorMessage = null) } }
                .onFailure(::showError)
        }
    }

    private companion object {
        const val CATEGORY_CONTACT = "CONTACT"
        const val CATEGORY_VISIT = "VISIT"
    }
}
