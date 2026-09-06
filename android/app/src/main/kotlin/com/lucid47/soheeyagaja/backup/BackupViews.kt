package com.lucid47.soheeyagaja.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lucid47.soheeyagaja.data.CustomerListSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupToolsDialog(
    customerLists: List<CustomerListSummary>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { BackupArchiveService(context) }
    var selectedBackupIds by remember(customerLists) { mutableStateOf<Set<Long>>(customerLists.mapTo(mutableSetOf()) { it.id }) }
    var pendingBackupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePreview by remember { mutableStateOf<BackupPreview?>(null) }
    var selectedRestoreIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var restoreMode by remember { mutableStateOf(RestoreMode.MERGE_SELECTED) }
    var automatic by remember { mutableStateOf(BackupScheduler.isEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun launchTask(block: suspend () -> String) {
        busy = true
        message = null
        error = null
        scope.launch {
            runCatching { block() }
                .onSuccess { message = it }
                .onFailure { error = it.message ?: "작업을 완료하지 못했습니다." }
            busy = false
        }
    }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val ids = pendingBackupIds
            launchTask {
                val result = service.writeBackup(uri, ids)
                BackupScheduler.configure(context, uri, ids, automatic)
                "${result.lists.size}개 고객리스트를 백업했습니다."
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launchTask {
                val preview = service.inspect(uri)
                restoreUri = uri
                restorePreview = preview
                selectedRestoreIds = preview.lists.mapTo(mutableSetOf()) { it.id }
                "백업에 고객리스트 ${preview.lists.size}개가 있습니다."
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("백업 및 복원") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("백업할 고객리스트", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("사진·음성·히스토리·대시보드 설정을 함께 저장합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(customerLists, key = CustomerListSummary::id) { list ->
                    SelectionRow(
                        checked = list.id in selectedBackupIds,
                        title = list.name,
                        detail = "${list.customerCount}명",
                        onCheckedChange = { checked ->
                            selectedBackupIds = selectedBackupIds.toMutableSet().apply {
                                if (checked) add(list.id) else remove(list.id)
                            }
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { selectedBackupIds = customerLists.mapTo(mutableSetOf()) { it.id } },
                            modifier = Modifier.weight(1f),
                        ) { Text("전체 선택") }
                        FilledTonalButton(
                            onClick = { selectedBackupIds = emptySet() },
                            modifier = Modifier.weight(1f),
                        ) { Text("전체 해제") }
                    }
                }
                item {
                    Button(
                        onClick = {
                            pendingBackupIds = selectedBackupIds
                            createBackup.launch("소희야가자-${DATE_FORMATTER.format(java.time.LocalDateTime.now())}.sgbackup.zip")
                        },
                        enabled = selectedBackupIds.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Default.CloudUpload, null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (selectedBackupIds.size == customerLists.size) "전체 일괄 백업" else "선택 백업")
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudDone, null)
                                Spacer(Modifier.size(8.dp))
                                Text("15분 주기 자동 백업", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = automatic,
                                    enabled = BackupScheduler.targetUri(context) != null,
                                    onCheckedChange = {
                                        automatic = it
                                        BackupScheduler.setEnabled(context, it)
                                    },
                                )
                            }
                            Text(
                                if (BackupScheduler.targetUri(context) == null) "백업 파일을 한 번 만든 뒤 켤 수 있습니다."
                                else "Android가 허용하는 주기에 같은 Drive/파일 위치를 갱신합니다.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (BackupScheduler.targetUri(context) != null) {
                                FilledTonalButton(
                                    onClick = {
                                        launchTask {
                                            BackupScheduler.runNow(context)
                                            "자동 백업 파일을 지금 갱신했습니다."
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("지금 백업") }
                            }
                        }
                    }
                }
                item {
                    Text("복원", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { openBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Icon(Icons.Default.CloudDownload, null)
                        Spacer(Modifier.size(8.dp))
                        Text("백업 파일 선택")
                    }
                }
                restorePreview?.let { preview ->
                    item {
                        Text(
                            "${BACKUP_DATE_FORMATTER.format(Instant.ofEpochMilli(preview.createdAtEpochMillis).atZone(ZoneId.systemDefault()))} · Android ${preview.appVersion}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(preview.lists, key = BackupListPreview::id) { list ->
                        SelectionRow(
                            checked = list.id in selectedRestoreIds,
                            title = list.name,
                            detail = "${list.customerCount}명",
                            onCheckedChange = { checked ->
                                selectedRestoreIds = selectedRestoreIds.toMutableSet().apply {
                                    if (checked) add(list.id) else remove(list.id)
                                }
                            },
                        )
                    }
                    item {
                        RestoreModeRow(
                            selected = restoreMode,
                            onSelected = { restoreMode = it },
                        )
                    }
                    item {
                        Button(
                            onClick = {
                                val uri = restoreUri ?: return@Button
                                launchTask {
                                    val count = service.restore(uri, selectedRestoreIds, restoreMode)
                                    "고객리스트 ${count}개를 복원했습니다."
                                }
                            },
                            enabled = selectedRestoreIds.isNotEmpty() && !busy,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(if (restoreMode == RestoreMode.REPLACE_ALL) "전체 교체 복원" else "선택 추가 복원") }
                    }
                }
                if (busy) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
                item {
                    FilledTonalButton(enabled = !busy && service.localRecoveryCopies().isNotEmpty(), onClick = {
                        service.localRecoveryCopies().firstOrNull()?.let { file ->
                            launchTask {
                                val uri = Uri.fromFile(file)
                                val preview = service.inspect(uri)
                                restoreUri = uri
                                restorePreview = preview
                                selectedRestoreIds = preview.lists.mapTo(mutableSetOf()) { it.id }
                                "기기 내 최신 복구본을 선택했습니다. 복원할 목록을 확인해주세요."
                            }
                        }
                    }) { Text("기기 내 복구본 확인") }
                }
                message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}

@Composable
private fun SelectionRow(
    checked: Boolean,
    title: String,
    detail: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange)
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RestoreModeRow(selected: RestoreMode, onSelected: (RestoreMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("복원 방법", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == RestoreMode.MERGE_SELECTED, { onSelected(RestoreMode.MERGE_SELECTED) })
            Text("기존 데이터 유지하고 선택한 리스트 추가")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == RestoreMode.REPLACE_ALL, { onSelected(RestoreMode.REPLACE_ALL) })
            Text("기존 데이터를 지우고 백업 전체로 교체", color = MaterialTheme.colorScheme.error)
        }
    }
}

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
private val BACKUP_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
