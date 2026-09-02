package com.lucid47.soheeyagaja.dashboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.data.CustomerWithFields
import com.lucid47.soheeyagaja.data.DashboardStatusEntity
import kotlin.math.ceil

private const val PAGE_SIZE = 100
private const val GRID_DIMENSION = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessDashboardDialog(
    viewModel: CustomerManagementViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val customers by viewModel.allCustomers.collectAsStateWithLifecycle()
    val history by viewModel.allHistoryEntries.collectAsStateWithLifecycle()
    val statuses by viewModel.dashboardStatuses.collectAsStateWithLifecycle()
    val settings by viewModel.dashboardSettings.collectAsStateWithLifecycle()
    var page by remember { mutableIntStateOf(0) }
    var selectedCustomer by remember { mutableStateOf<CustomerWithFields?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val landscape = maxWidth > maxHeight
            DashboardSystemBars(hidden = landscape)
            val pageCount = maxOf(1, ceil(customers.size / PAGE_SIZE.toDouble()).toInt())
            val safePage = page.coerceIn(0, pageCount - 1)
            val pageCustomers = customers.drop(safePage * PAGE_SIZE).take(PAGE_SIZE)
            val latestTouch = remember(history) {
                history.groupBy { it.customerId }.mapValues { (_, entries) ->
                    entries.maxOfOrNull { it.occurredAtEpochMillis }
                }
            }

            if (landscape) {
                DashboardBoard(
                    customers = pageCustomers,
                    statuses = statuses,
                    latestTouch = latestTouch,
                    landscape = true,
                    onCustomerClick = { selectedCustomer = it },
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("고객 프로세스", fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                                }
                            },
                            actions = {
                                IconButton(onClick = viewModel::openDashboardSettings) {
                                    Icon(Icons.Default.Settings, contentDescription = "상태 설정")
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("현재 고객", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${customers.size}명", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            Text("${safePage * PAGE_SIZE + if (customers.isEmpty()) 0 else 1}–${minOf((safePage + 1) * PAGE_SIZE, customers.size)} / ${customers.size}")
                        }
                        if (settings?.showsLegend != false) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                statuses.forEachIndexed { index, status ->
                                    val count = pageCustomers.count { customer ->
                                        resolvedStatusId(customer, statuses) == status.id
                                    }
                                    Surface(
                                        color = parseColor(status.colorHex),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            "${index + 1} ${status.name} $count",
                                            color = foregroundColor(status.colorHex),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                        DashboardBoard(
                            customers = pageCustomers,
                            statuses = statuses,
                            latestTouch = latestTouch,
                            landscape = false,
                            onCustomerClick = { selectedCustomer = it },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { page = (safePage - 1).coerceAtLeast(0) }, enabled = safePage > 0) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "이전 페이지")
                            }
                            Text("${safePage + 1} / $pageCount", fontWeight = FontWeight.Bold)
                            IconButton(onClick = { page = (safePage + 1).coerceAtMost(pageCount - 1) }, enabled = safePage < pageCount - 1) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "다음 페이지")
                            }
                        }
                    }
                }
            }

            if (state.dashboardSettingsVisible) {
                DashboardSettingsDialog(viewModel = viewModel, onDismiss = viewModel::closeDashboardSettings)
            }
            selectedCustomer?.let { customer ->
                DashboardCustomerStatusDialog(
                    customer = customer,
                    statuses = statuses,
                    onSelect = { statusId ->
                        viewModel.setDashboardStatus(customer.customer.id, statusId)
                        selectedCustomer = null
                    },
                    onDismiss = { selectedCustomer = null },
                )
            }
        }
    }
}

@Composable
private fun DashboardBoard(
    customers: List<CustomerWithFields>,
    statuses: List<DashboardStatusEntity>,
    latestTouch: Map<Long, Long?>,
    landscape: Boolean,
    onCustomerClick: (CustomerWithFields) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(GRID_DIMENSION) { rowIndex ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(GRID_DIMENSION) { columnIndex ->
                    val index = rowIndex * GRID_DIMENSION + columnIndex
                    val customer = customers.getOrNull(index)
                    if (customer == null) {
                        Surface(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(3.dp),
                        ) {}
                    } else {
                        val status = statuses.firstOrNull { it.id == resolvedStatusId(customer, statuses) }
                        val touchedAt = latestTouch[customer.customer.id]
                        val elapsedDays = touchedAt?.let {
                            ((System.currentTimeMillis() - it).coerceAtLeast(0) / 86_400_000L).toInt()
                        }
                        Surface(
                            modifier = Modifier.weight(1f).fillMaxSize().clickable { onCustomerClick(customer) },
                            color = parseColor(status?.colorHex ?: "CED4DC"),
                            shape = RoundedCornerShape(3.dp),
                        ) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 1.dp)) {
                                Text(
                                    text = "${customer.customer.name.ifBlank { "이름없음" }} ${elapsedDays ?: "–"}",
                                    color = foregroundColor(status?.colorHex ?: "CED4DC"),
                                    fontWeight = if (landscape) FontWeight.Black else FontWeight.Bold,
                                    fontSize = if (landscape) 18.sp else 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                                )
                                statuses.indexOfFirst { it.id == status?.id }.takeIf { it >= 0 }?.let { statusIndex ->
                                    Text(
                                        "${statusIndex + 1}",
                                        fontSize = 7.sp,
                                        color = foregroundColor(status?.colorHex ?: "CED4DC").copy(alpha = 0.7f),
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardCustomerStatusDialog(
    customer: CustomerWithFields,
    statuses: List<DashboardStatusEntity>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(customer.customer.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("프로세스 단계를 선택하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                statuses.forEachIndexed { index, status ->
                    val selected = resolvedStatusId(customer, statuses) == status.id
                    Surface(
                        color = parseColor(status.colorHex),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(status.id) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${status.name}", color = foregroundColor(status.colorHex), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (selected) Icon(Icons.Default.Check, contentDescription = "현재 상태", tint = foregroundColor(status.colorHex))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun DashboardSettingsDialog(
    viewModel: CustomerManagementViewModel,
    onDismiss: () -> Unit,
) {
    val statuses by viewModel.dashboardStatuses.collectAsStateWithLifecycle()
    val settings by viewModel.dashboardSettings.collectAsStateWithLifecycle()
    var pendingCount by remember(statuses.size) { mutableIntStateOf(statuses.size.coerceAtLeast(1)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "닫기") }
                    Text("히트맵 설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss) { Text("완료") }
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("프로세스 단계", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(onClick = { pendingCount = (pendingCount - 1).coerceAtLeast(1) }) {
                            Icon(Icons.Default.Remove, contentDescription = "단계 줄이기")
                        }
                        Text("${pendingCount}개", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { pendingCount = (pendingCount + 1).coerceAtMost(10) }) {
                            Icon(Icons.Default.Add, contentDescription = "단계 늘리기")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.setDashboardStatusCount(pendingCount) },
                            enabled = pendingCount != statuses.size,
                        ) { Text("적용") }
                    }
                    Text("색상 계열", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    DashboardPaletteFamily.entries.forEach { family ->
                        val selected = settings?.paletteFamily == family.name
                        OutlinedButton(onClick = { viewModel.setDashboardPalette(family) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    family.colors.filterIndexed { index, _ -> index % 2 == 0 }.forEach { hex ->
                                        Box(Modifier.size(width = 24.dp, height = 18.dp).background(parseColor(hex), RoundedCornerShape(2.dp)))
                                    }
                                }
                                Text(family.displayName)
                                if (selected) Icon(Icons.Default.Check, contentDescription = "선택됨", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("세로 화면에서 범례 표시", modifier = Modifier.weight(1f))
                        Switch(checked = settings?.showsLegend != false, onCheckedChange = viewModel::setDashboardLegendVisible)
                    }
                    Text("단계 이름", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    statuses.forEachIndexed { index, status ->
                        var name by remember(status.id, status.name) { mutableStateOf(status.name) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(28.dp).background(parseColor(status.colorHex), RoundedCornerShape(4.dp)))
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("${index + 1}단계") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.renameDashboardStatus(status.id, name) }) {
                                Icon(Icons.Default.Check, contentDescription = "이름 저장")
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun DashboardSystemBars(hidden: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(hidden, view) {
        val activity = context.findActivity()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        if (hidden) controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun resolvedStatusId(customer: CustomerWithFields, statuses: List<DashboardStatusEntity>): String? =
    customer.customer.dashboardStatusId?.takeIf { id -> statuses.any { it.id == id } } ?: statuses.firstOrNull()?.id

private fun parseColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor("#${hex.removePrefix("#")}"))
}.getOrDefault(Color(0xFFCED4DC))

private fun foregroundColor(hex: String): Color {
    val color = runCatching { android.graphics.Color.parseColor("#${hex.removePrefix("#")}") }
        .getOrDefault(android.graphics.Color.LTGRAY)
    return if (ColorUtils.calculateLuminance(color) < 0.48) Color.White else Color.Black
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
