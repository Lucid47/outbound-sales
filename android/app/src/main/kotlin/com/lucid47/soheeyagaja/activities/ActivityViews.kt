package com.lucid47.soheeyagaja.activities

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.customers.HistoryActivityFilter
import com.lucid47.soheeyagaja.customers.HistoryCustomerSummary
import com.lucid47.soheeyagaja.customers.HistoryDatePreset
import com.lucid47.soheeyagaja.customers.HistoryDisplayMode
import com.lucid47.soheeyagaja.customers.HistorySortOrder
import com.lucid47.soheeyagaja.customers.historyTitle
import com.lucid47.soheeyagaja.data.CustomerListSummary
import com.lucid47.soheeyagaja.data.CustomerWithFields
import com.lucid47.soheeyagaja.data.HistoryEntryRecord
import com.lucid47.soheeyagaja.data.ScheduledCustomerRecord
import com.lucid47.soheeyagaja.media.HistoryMediaPreview
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayActivityScreen(
    viewModel: CustomerManagementViewModel,
    modifier: Modifier = Modifier,
    onOpenCustomer: (Long) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lists by viewModel.customerLists.collectAsStateWithLifecycle()
    val schedule by viewModel.todaySchedule.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selectedList = lists.firstOrNull { it.id == state.selectedListId }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("오늘", fontWeight = FontWeight.Bold) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 14.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(TODAY_DATE_FORMATTER.format(now), style = MaterialTheme.typography.titleLarge)
                Text(
                    TODAY_TIME_FORMATTER.format(now),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                ActivityListSelector(lists, selectedList, viewModel::selectList)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TodayMetric("오늘 스케줄", schedule.size, Modifier.weight(1f))
                    TodayMetric(
                        "대기",
                        schedule.count { it.scheduleStatus == ActivityRepository.SCHEDULE_PENDING },
                        Modifier.weight(1f),
                    )
                    TodayMetric(
                        "완료",
                        schedule.count { it.scheduleStatus == ActivityRepository.SCHEDULE_COMPLETED },
                        Modifier.weight(1f),
                    )
                }
            }
            item {
                Text("방문 일정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (selectedList == null) {
                item { EmptyActivityMessage("고객리스트를 먼저 만들어주세요.") }
            } else if (schedule.isEmpty()) {
                item { EmptyActivityMessage("고객 상세에서 오늘 스케줄에 고객을 추가할 수 있습니다.") }
            } else {
                items(schedule, key = ScheduledCustomerRecord::scheduleItemId) { customer ->
                    ScheduleCustomerCard(
                        customer = customer,
                        onOpen = { onOpenCustomer(customer.customerId) },
                        onCall = {
                            viewModel.recordCallAttempt(customer.customerId)
                            openPhone(context, customer.phone)
                        },
                        onMessage = {
                            viewModel.recordSmsAttempt(customer.customerId)
                            openMessage(context, customer.phone)
                        },
                        onRemove = { viewModel.removeFromTodaySchedule(customer.customerId) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryActivityScreen(
    viewModel: CustomerManagementViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lists by viewModel.customerLists.collectAsStateWithLifecycle()
    val customers by viewModel.allCustomers.collectAsStateWithLifecycle()
    val entries by viewModel.historyEntries.collectAsStateWithLifecycle()
    val customerSummaries by viewModel.historyCustomerSummaries.collectAsStateWithLifecycle()
    val dialogEntries by viewModel.historyDialogEntries.collectAsStateWithLifecycle()
    val selectedList = lists.firstOrNull { it.id == state.selectedListId }
    val selectedHistoryCustomer = customers.firstOrNull { it.customer.id == state.historyCustomerFilterId }
    var customerPickerVisible by remember { mutableStateOf(false) }
    var periodsVisible by remember { mutableStateOf(false) }
    if (periodsVisible) ManagementPeriodDialog(entries,
        start = if (state.historyDateFilterEnabled) state.historyStartEpochDay else entries.minOfOrNull { Instant.ofEpochMilli(it.occurredAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() } ?: LocalDate.now().toEpochDay(),
        end = if (state.historyDateFilterEnabled) state.historyEndEpochDay else LocalDate.now().toEpochDay(),
        onApply = { start, end ->
            viewModel.setHistoryDateFilterEnabled(true)
            viewModel.setHistoryStartEpochDay(start)
            viewModel.setHistoryEndEpochDay(end)
        }, onDismiss = { periodsVisible = false })

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("기록", fontWeight = FontWeight.Bold) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 14.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ActivityListSelector(lists, selectedList, viewModel::selectList) }
            item { OutlinedButton(onClick = { periodsVisible = true }) { Text("관리 기간 · 활동 보관") } }
            item {
                HistoryDisplayModeSelector(state.historyDisplayMode, viewModel::setHistoryDisplayMode)
            }
            item {
                HistoryActivitySelector(state.historyActivityFilter, viewModel::setHistoryActivityFilter)
            }
            item {
                OutlinedTextField(
                    value = state.historySearchQuery,
                    onValueChange = viewModel::updateHistorySearch,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("고객 이름과 기록 내용 검색") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                DateFilterPanel(
                    enabled = state.historyDateFilterEnabled,
                    preset = state.historyDatePreset,
                    startEpochDay = state.historyStartEpochDay,
                    endEpochDay = state.historyEndEpochDay,
                    onEnabledChange = viewModel::setHistoryDateFilterEnabled,
                    onPresetChange = viewModel::setHistoryDatePreset,
                    onStartChange = viewModel::setHistoryStartEpochDay,
                    onEndChange = viewModel::setHistoryEndEpochDay,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { customerPickerVisible = true },
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.Default.People, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            selectedHistoryCustomer?.customer?.name ?: "전체 고객",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HistorySortSelector(state.historySortOrder, viewModel::setHistorySortOrder)
                }
            }
            item {
                val resultText = if (state.historyDisplayMode == HistoryDisplayMode.CUSTOMERS) {
                    "고객 ${customerSummaries.size}명 · 기록 ${entries.size}건"
                } else {
                    "기록 ${entries.size}건"
                }
                Text(resultText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (entries.isEmpty()) {
                item { EmptyActivityMessage("조건에 맞는 고객 기록이 없습니다.") }
            } else if (state.historyDisplayMode == HistoryDisplayMode.CUSTOMERS) {
                items(customerSummaries, key = HistoryCustomerSummary::customerId) { summary ->
                    HistoryCustomerCard(summary, onClick = { viewModel.openHistoryCustomer(summary.customerId) })
                }
            } else {
                items(entries, key = HistoryEntryRecord::stableId) { entry ->
                    HistoryEntryCard(entry, onClick = { viewModel.openHistoryCustomer(entry.customerId) })
                }
            }
        }
    }

    state.historyCustomerId?.let {
        CustomerHistoryDialog(dialogEntries, viewModel::closeHistoryCustomer)
    }
    if (customerPickerVisible) {
        HistoryCustomerPickerDialog(
            customers = customers,
            selectedCustomerId = state.historyCustomerFilterId,
            onSelect = {
                viewModel.setHistoryCustomerFilter(it)
                customerPickerVisible = false
            },
            onDismiss = { customerPickerVisible = false },
        )
    }
}

@Composable
private fun ActivityListSelector(
    lists: List<CustomerListSummary>,
    selectedList: CustomerListSummary?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = lists.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(
                selectedList?.let { "${it.name} · ${it.customerCount}명" } ?: "고객리스트 없음",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ExpandMore, contentDescription = "리스트 변경")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            lists.forEach { list ->
                DropdownMenuItem(
                    text = { Text("${list.name} · ${list.customerCount}명") },
                    onClick = {
                        expanded = false
                        onSelect(list.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun TodayMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$value", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryDisplayModeSelector(
    selected: HistoryDisplayMode,
    onSelect: (HistoryDisplayMode) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == HistoryDisplayMode.EVENTS,
            onClick = { onSelect(HistoryDisplayMode.EVENTS) },
            label = { Text("기록별") },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = selected == HistoryDisplayMode.CUSTOMERS,
            onClick = { onSelect(HistoryDisplayMode.CUSTOMERS) },
            label = { Text("고객별") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HistoryActivitySelector(
    selected: HistoryActivityFilter,
    onSelect: (HistoryActivityFilter) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(HistoryActivityFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label()) },
            )
        }
    }
}

@Composable
private fun HistorySortSelector(
    selected: HistorySortOrder,
    onSelect: (HistorySortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.height(52.dp)) {
            Text(selected.label())
            Icon(Icons.Default.ExpandMore, contentDescription = "정렬 변경")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            HistorySortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label()) },
                    onClick = {
                        onSelect(order)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ScheduleCustomerCard(
    customer: ScheduledCustomerRecord,
    onOpen: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    customer.customerName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (customer.scheduleStatus == ActivityRepository.SCHEDULE_COMPLETED) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "완료", tint = Color(0xFF16A34A))
                }
            }
            Text(customer.phone.ifBlank { "연락처 없음" }, style = MaterialTheme.typography.titleMedium)
            if (customer.address.isNotBlank()) {
                Text(customer.address, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCall, enabled = customer.phone.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Phone, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    Text("전화")
                }
                FilledTonalButton(onClick = onMessage, enabled = customer.phone.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    Text("문자")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "스케줄 해제")
                }
            }
        }
    }
}

@Composable
private fun DateFilterPanel(
    enabled: Boolean,
    preset: HistoryDatePreset,
    startEpochDay: Long,
    endEpochDay: Long,
    onEnabledChange: (Boolean) -> Unit,
    onPresetChange: (HistoryDatePreset) -> Unit,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("조회 기간", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HISTORY_DATE_PRESETS, key = { it.name }) { item ->
                        FilterChip(
                            selected = preset == item,
                            onClick = { onPresetChange(item) },
                            label = { Text(item.label()) },
                        )
                    }
                }
                if (preset == HistoryDatePreset.CUSTOM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateButton("시작", startEpochDay, Modifier.weight(1f)) { showDatePicker(context, startEpochDay, onStartChange) }
                        DateButton("종료", endEpochDay, Modifier.weight(1f)) { showDatePicker(context, endEpochDay, onEndChange) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateButton(label: String, epochDay: Long, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp)) {
        Text("$label ${LocalDate.ofEpochDay(epochDay).format(SHORT_DATE_FORMATTER)}")
    }
}

private fun showDatePicker(context: Context, epochDay: Long, onChange: (Long) -> Unit) {
    val date = LocalDate.ofEpochDay(epochDay)
    DatePickerDialog(
        context,
        { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).toEpochDay()) },
        date.year,
        date.monthValue - 1,
        date.dayOfMonth,
    ).show()
}

@Composable
private fun HistoryCustomerCard(summary: HistoryCustomerSummary, onClick: () -> Unit) {
    val entry = summary.latestEntry
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.customerName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("${summary.entryCount}건", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(historyTitle(entry), fontWeight = FontWeight.Bold)
                Text(
                    HISTORY_DATE_FORMATTER.format(
                        Instant.ofEpochMilli(entry.occurredAtEpochMillis).atZone(ZoneId.systemDefault()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.detail.isNotBlank()) {
                Text(
                    entry.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryCustomerPickerDialog(
    customers: List<CustomerWithFields>,
    selectedCustomerId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visibleCustomers = remember(customers, query) {
        val normalized = query.trim()
        customers
            .filter {
                normalized.isEmpty() ||
                    it.customer.name.contains(normalized, ignoreCase = true) ||
                    it.customer.phone.contains(normalized)
            }
            .sortedBy { it.customer.name.lowercase() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("고객 선택", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                            }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        end = 14.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            label = { Text("이름 또는 전화번호 검색") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        CustomerFilterRow(
                            name = "전체 고객",
                            detail = "${customers.size}명",
                            selected = selectedCustomerId == null,
                            onClick = { onSelect(null) },
                        )
                    }
                    items(visibleCustomers, key = { it.customer.id }) { record ->
                        CustomerFilterRow(
                            name = record.customer.name,
                            detail = record.customer.phone,
                            selected = selectedCustomerId == record.customer.id,
                            onClick = { onSelect(record.customer.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerFilterRow(
    name: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) {
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(entry: HistoryEntryRecord, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (entry.category == CATEGORY_VISIT) Icons.Default.People else Icons.Default.History,
                contentDescription = null,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row {
                    Text(entry.customerName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(historyTitle(entry), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                if (entry.detail.isNotBlank()) {
                    Text(entry.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    HISTORY_DATE_FORMATTER.format(Instant.ofEpochMilli(entry.occurredAtEpochMillis).atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HistoryMediaPreview(entry, Modifier.fillMaxWidth().height(112.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerHistoryDialog(entries: List<HistoryEntryRecord>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(entries.firstOrNull()?.customerName ?: "고객 히스토리", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                            }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        end = 14.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (entries.isEmpty()) item { EmptyActivityMessage("고객 기록이 없습니다.") }
                    else items(entries, key = HistoryEntryRecord::stableId) { HistoryEntryCard(it, onClick = {}) }
                }
            }
        }
    }
}

@Composable
private fun EmptyActivityMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(42.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openPhone(context: Context, phone: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))) }
}

private fun openMessage(context: Context, phone: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}"))) }
}

private const val CATEGORY_VISIT = "VISIT"
private val HISTORY_DATE_PRESETS = listOf(
    HistoryDatePreset.LAST_30_DAYS,
    HistoryDatePreset.LAST_MONTH,
    HistoryDatePreset.LAST_QUARTER,
    HistoryDatePreset.CUSTOM,
)

private fun HistoryActivityFilter.label(): String = when (this) {
    HistoryActivityFilter.ALL -> "전체"
    HistoryActivityFilter.CALL -> "전화"
    HistoryActivityFilter.MESSAGE -> "문자"
    HistoryActivityFilter.VISIT -> "방문"
    HistoryActivityFilter.MEMO -> "메모"
    HistoryActivityFilter.STATUS -> "상태"
}

private fun HistoryDatePreset.label(): String = when (this) {
    HistoryDatePreset.ALL -> "전체 기간"
    HistoryDatePreset.LAST_30_DAYS -> "최근 30일"
    HistoryDatePreset.LAST_MONTH -> "지난달"
    HistoryDatePreset.LAST_QUARTER -> "지난 분기"
    HistoryDatePreset.CUSTOM -> "기간 선택"
}

private fun HistorySortOrder.label(): String = when (this) {
    HistorySortOrder.NEWEST -> "최신순"
    HistorySortOrder.OLDEST -> "오래된순"
    HistorySortOrder.CUSTOMER_NAME -> "고객 이름순"
}

private val TODAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)
private val TODAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val HISTORY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
