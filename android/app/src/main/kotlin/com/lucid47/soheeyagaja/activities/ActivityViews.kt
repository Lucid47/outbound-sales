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
import com.lucid47.soheeyagaja.customers.CustomerManagementRepository
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.customers.HistoryKindFilter
import com.lucid47.soheeyagaja.customers.historyTitle
import com.lucid47.soheeyagaja.data.CustomerListSummary
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
    val allEntries by viewModel.allHistoryEntries.collectAsStateWithLifecycle()
    val entries by viewModel.historyEntries.collectAsStateWithLifecycle()
    val dialogEntries by viewModel.historyDialogEntries.collectAsStateWithLifecycle()
    val selectedList = lists.firstOrNull { it.id == state.selectedListId }
    val touchedCustomerCount = allEntries.filter { it.category == CATEGORY_CONTACT }.map { it.customerId }.distinct().size
    val visitedCustomerCount = allEntries.filter { it.category == CATEGORY_VISIT }.map { it.customerId }.distinct().size
    val doneCustomerCount = customers.count { it.customer.status == CustomerManagementRepository.STATUS_DONE }

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
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryMetric(
                        label = "전체 고객",
                        value = customers.size,
                        selected = state.historyKindFilter == HistoryKindFilter.ALL,
                        onClick = { viewModel.setHistoryKindFilter(HistoryKindFilter.ALL) },
                        modifier = Modifier.weight(1f),
                    )
                    HistoryMetric(
                        label = "터치 고객",
                        value = touchedCustomerCount,
                        selected = state.historyKindFilter == HistoryKindFilter.TOUCH,
                        onClick = { viewModel.setHistoryKindFilter(HistoryKindFilter.TOUCH) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryMetric(
                        label = "방문 고객",
                        value = visitedCustomerCount,
                        selected = state.historyKindFilter == HistoryKindFilter.VISIT,
                        onClick = { viewModel.setHistoryKindFilter(HistoryKindFilter.VISIT) },
                        modifier = Modifier.weight(1f),
                    )
                    HistoryMetric(
                        label = "완료 고객",
                        value = doneCustomerCount,
                        selected = state.historyKindFilter == HistoryKindFilter.DONE,
                        onClick = { viewModel.setHistoryKindFilter(HistoryKindFilter.DONE) },
                        modifier = Modifier.weight(1f),
                    )
                }
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
                    startEpochDay = state.historyStartEpochDay,
                    endEpochDay = state.historyEndEpochDay,
                    onEnabledChange = viewModel::setHistoryDateFilterEnabled,
                    onStartChange = viewModel::setHistoryStartEpochDay,
                    onEndChange = viewModel::setHistoryEndEpochDay,
                )
            }
            item {
                Text("누적 히스토리 ${entries.size}건", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (entries.isEmpty()) {
                item { EmptyActivityMessage("조건에 맞는 고객 기록이 없습니다.") }
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
private fun HistoryMetric(
    label: String,
    value: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, fontWeight = FontWeight.Bold)
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
    startEpochDay: Long,
    endEpochDay: Long,
    onEnabledChange: (Boolean) -> Unit,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateButton("시작", startEpochDay, Modifier.weight(1f)) { showDatePicker(context, startEpochDay, onStartChange) }
                    DateButton("종료", endEpochDay, Modifier.weight(1f)) { showDatePicker(context, endEpochDay, onEndChange) }
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

private const val CATEGORY_CONTACT = "CONTACT"
private const val CATEGORY_VISIT = "VISIT"
private val TODAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)
private val TODAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val HISTORY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
