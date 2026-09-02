package com.lucid47.soheeyagaja.contacts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.data.CustomerListSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactToolsDialog(
    viewModel: CustomerManagementViewModel,
    customerLists: List<CustomerListSummary>,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionVersion by remember { mutableStateOf(0) }
    val hasPermissions = remember(permissionVersion) {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionVersion += 1
        if (result.values.all { it }) viewModel.refreshManagedContactGroups()
    }
    var listMenuExpanded by remember { mutableStateOf(false) }
    val selectedList = customerLists.firstOrNull { it.id == state.selectedListId }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("연락처 내보내기", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "닫기") }
                        },
                        actions = {
                            IconButton(onClick = viewModel::refreshManagedContactGroups, enabled = hasPermissions && !state.contactToolsBusy) {
                                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                            }
                        },
                    )
                },
            ) { padding ->
                if (!hasPermissions) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.size(16.dp))
                        Text("연락처 읽기와 저장 권한이 필요합니다.", fontWeight = FontWeight.Bold)
                        Text("앱이 만든 그룹만 별도로 표시하고 삭제합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(20.dp))
                        Button(onClick = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                        }) { Text("연락처 권한 허용") }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item {
                            Text("고객리스트를 그룹으로 등록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        item {
                            Column {
                                OutlinedButton(onClick = { listMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(selectedList?.name ?: "고객리스트 선택", modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                                }
                                DropdownMenu(expanded = listMenuExpanded, onDismissRequest = { listMenuExpanded = false }) {
                                    customerLists.forEach { list ->
                                        DropdownMenuItem(
                                            text = { Text("${list.name} · ${list.customerCount}명") },
                                            onClick = {
                                                viewModel.selectList(list.id)
                                                listMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("이름 앞에 접두어 추가", fontWeight = FontWeight.Bold)
                                    Text("카카오톡 자동 친구 등록 방지 등에 사용합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = state.contactPrefixEnabled, onCheckedChange = viewModel::setContactPrefixEnabled)
                            }
                        }
                        if (state.contactPrefixEnabled) {
                            item {
                                OutlinedTextField(
                                    value = state.contactPrefix,
                                    onValueChange = viewModel::updateContactPrefix,
                                    label = { Text("접두어") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        item {
                            Button(
                                onClick = viewModel::exportSelectedListToContacts,
                                enabled = selectedList != null && !state.contactToolsBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("연락처 그룹으로 등록")
                            }
                        }
                        item { HorizontalDivider() }
                        item {
                            Text("앱이 만든 연락처 그룹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        if (state.contactToolsBusy) {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (state.managedContactGroups.isEmpty()) {
                            item { Text("앱이 만든 연락처 그룹이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            items(state.managedContactGroups, key = ManagedContactGroup::id) { group ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { viewModel.requestManagedGroupDelete(group.id) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Groups, contentDescription = null)
                                    Spacer(Modifier.size(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(group.name, fontWeight = FontWeight.Bold)
                                        Text("${group.contactCount}명", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.requestManagedGroupDelete(group.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "그룹과 연락처 삭제", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        state.statusMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                        state.errorMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    }
                }
            }
        }
    }

    state.deleteManagedGroupId?.let { groupId ->
        val group = state.managedContactGroups.firstOrNull { it.id == groupId }
        AlertDialog(
            onDismissRequest = viewModel::cancelManagedGroupDelete,
            title = { Text("그룹과 연락처를 삭제할까요?") },
            text = { Text("${group?.name.orEmpty()} 그룹과 소희야 가자가 이 그룹에 등록한 ${group?.contactCount ?: 0}명의 연락처를 함께 삭제합니다. 앱의 고객 데이터는 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmManagedGroupDelete) {
                    Text("함께 삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelManagedGroupDelete) { Text("취소") } },
        )
    }
}
