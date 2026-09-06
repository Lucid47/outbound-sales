package com.lucid47.soheeyagaja.activities

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lucid47.soheeyagaja.data.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ManagementPeriodDialog(entries: List<HistoryEntryRecord>, start: Long, end: Long,
    onApply: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).managementPeriodDao() }
    val periods by dao.observe().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<ManagementPeriod?>(null) }
    var exportText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write("\uFEFF" + exportText) }
            } }.onFailure { error = "내보내기에 실패했습니다." }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("관리 기간 · 활동 보관", style = MaterialTheme.typography.titleLarge)
                Text("${LocalDate.ofEpochDay(start)} ~ ${LocalDate.ofEpochDay(end)} · ${entries.size}건")
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("기간 이름") })
                TextButton(enabled = name.isNotBlank(), onClick = { scope.launch {
                    runCatching { dao.save(ManagementPeriod(name = name.trim(), startEpochDay = start, endEpochDay = end,
                        snapshotText = withContext(Dispatchers.Default) { historySnapshot(entries) })) }
                        .onSuccess { name = "" }.onFailure { error = "보관에 실패했습니다." }
                } }) { Text("현재 조회 결과 보관") }
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(periods, key = { it.id }) { period ->
                        TextButton(onClick = { opened = period }) { Text(period.name) }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    }
    opened?.let { period ->
        AlertDialog(onDismissRequest = { opened = null }, title = { Text(period.name) },
            text = { Text("${LocalDate.ofEpochDay(period.startEpochDay)} ~ ${LocalDate.ofEpochDay(period.endEpochDay)}\n저장 당시의 활동 기록은 고객을 삭제해도 보존됩니다.") },
            confirmButton = { Column {
                TextButton(onClick = { exportText = period.snapshotText; export.launch("활동기록.csv") }) { Text("활동 기록 CSV 내보내기") }
                TextButton(onClick = { onApply(period.startEpochDay, period.endEpochDay); opened = null; onDismiss() }) { Text("이 기간 조회") }
            } }, dismissButton = { TextButton(onClick = { opened = null }) { Text("닫기") } })
    }
}
