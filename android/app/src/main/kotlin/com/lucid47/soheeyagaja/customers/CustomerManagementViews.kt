package com.lucid47.soheeyagaja.customers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.data.CustomerListSummary
import com.lucid47.soheeyagaja.data.CustomerWithFields
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerManagementScreen(
    viewModel: CustomerManagementViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lists by viewModel.customerLists.collectAsStateWithLifecycle()
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val selectedCustomer by viewModel.selectedCustomer.collectAsStateWithLifecycle()
    val selectedList = lists.firstOrNull { it.id == uiState.selectedListId }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("고객", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = viewModel::openCreateCustomer,
                        enabled = selectedList != null,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "고객 추가")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 310.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                CustomerListControl(
                    lists = lists,
                    selectedList = selectedList,
                    onSelectList = viewModel::selectList,
                    onRename = viewModel::requestListRename,
                    onDelete = { viewModel.requestListDelete(it.id) },
                )
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::updateSearch,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("이름, 전화번호, 주소 검색") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            uiState.statusMessage?.let { message ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    StatusBanner(message, isError = false)
                }
            }
            uiState.errorMessage?.let { message ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    StatusBanner(message, isError = true)
                }
            }

            if (selectedList == null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyCustomers("가져오기 탭에서 고객리스트를 만들어주세요.")
                }
            } else if (customers.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyCustomers(if (uiState.searchQuery.isBlank()) "이 고객리스트에 고객이 없습니다." else "검색 결과가 없습니다.")
                }
            } else {
                items(customers, key = { it.customer.id }) { record ->
                    CustomerCard(record = record, onClick = { viewModel.openCustomer(record.customer.id) })
                }
            }
        }
    }

    selectedCustomer?.let { record ->
        CustomerDetailDialog(
            record = record,
            onDismiss = viewModel::closeCustomer,
            onEdit = { viewModel.openEditCustomer(record) },
            onDelete = { viewModel.requestCustomerDelete(record.customer.id) },
        )
    }

    if (uiState.editor.isVisible) {
        CustomerEditorDialog(
            state = uiState.editor,
            onDismiss = viewModel::closeEditor,
            onDraftChange = viewModel::updateDraft,
            onSave = viewModel::saveCustomer,
        )
    }

    uiState.renameListId?.let {
        AlertDialog(
            onDismissRequest = viewModel::cancelListRename,
            title = { Text("고객리스트 이름 변경") },
            text = {
                OutlinedTextField(
                    value = uiState.renameValue,
                    onValueChange = viewModel::updateRenameValue,
                    label = { Text("고객리스트 이름") },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmListRename) { Text("변경") } },
            dismissButton = { TextButton(onClick = viewModel::cancelListRename) { Text("취소") } },
        )
    }

    uiState.deleteListId?.let { listId ->
        val list = lists.firstOrNull { it.id == listId }
        AlertDialog(
            onDismissRequest = viewModel::cancelListDelete,
            title = { Text("고객리스트 영구삭제") },
            text = { Text("${list?.name.orEmpty()} 리스트와 포함된 ${list?.customerCount ?: 0}명의 고객을 삭제합니다.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmListDelete) {
                    Text("영구삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelListDelete) { Text("취소") } },
        )
    }

    uiState.deleteCustomerId?.let {
        AlertDialog(
            onDismissRequest = viewModel::cancelCustomerDelete,
            title = { Text("고객 영구삭제") },
            text = { Text("이 고객 정보를 삭제합니다. 휴대폰 연락처는 삭제하지 않습니다.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCustomerDelete) {
                    Text("영구삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelCustomerDelete) { Text("취소") } },
        )
    }
}

@Composable
private fun CustomerListControl(
    lists: List<CustomerListSummary>,
    selectedList: CustomerListSummary?,
    onSelectList: (Long) -> Unit,
    onRename: (CustomerListSummary) -> Unit,
    onDelete: (CustomerListSummary) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = lists.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedList?.name ?: "고객리스트 없음",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        selectedList?.let { Text("${it.customerCount}명", style = MaterialTheme.typography.bodySmall) }
                    }
                    Icon(Icons.Default.ExpandMore, contentDescription = "리스트 변경")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    lists.forEach { list ->
                        DropdownMenuItem(
                            text = { Text("${list.name}  ${list.customerCount}명") },
                            onClick = {
                                expanded = false
                                onSelectList(list.id)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { selectedList?.let(onRename) }, enabled = selectedList != null) {
                Icon(Icons.Default.Edit, contentDescription = "리스트 이름 변경")
            }
            IconButton(onClick = { selectedList?.let(onDelete) }, enabled = selectedList != null) {
                Icon(Icons.Default.Delete, contentDescription = "리스트 삭제", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CustomerCard(record: CustomerWithFields, onClick: () -> Unit) {
    val customer = record.customer
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = customer.name.ifBlank { "이름 없음" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (customer.status == CustomerManagementRepository.STATUS_DONE) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "완료", tint = Color(0xFF16A34A))
                }
            }
            Text(customer.phone.ifBlank { "연락처 없음" }, style = MaterialTheme.typography.titleMedium)
            Text(
                customer.address.ifBlank { "주소 없음" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (customer.notes.isNotBlank()) {
                Text(
                    customer.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyCustomers(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(42.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusBanner(message: String, isError: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else Color(0xFF16A34A),
        )
        Text(message, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerDetailDialog(
    record: CustomerWithFields,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val customer = record.customer
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(customer.name.ifBlank { "고객 상세" }, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                            }
                        },
                        actions = {
                            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "수정") }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        end = 16.dp,
                        bottom = padding.calculateBottomPadding() + 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("전화", Icons.Default.Phone, customer.phone.isNotBlank()) {
                                context.openIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(customer.phone)}")))
                            }
                            ActionButton("문자", Icons.AutoMirrored.Filled.Message, customer.phone.isNotBlank()) {
                                context.openIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(customer.phone)}")))
                            }
                            ActionButton("길찾기", Icons.Default.Directions, customer.address.isNotBlank()) {
                                context.openIntent(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("geo:0,0?q=${Uri.encode(customer.address)}(${Uri.encode(customer.name)})"),
                                    ),
                                )
                            }
                        }
                    }
                    item {
                        DetailSection("고객 정보") {
                            DetailValue("이름", customer.name)
                            DetailValue("연락처", customer.phone.ifBlank { "연락처 없음" })
                            DetailValue("생년월일", customer.birthDate, hideWhenEmpty = true)
                            DetailValue(
                                "상태",
                                if (customer.status == CustomerManagementRepository.STATUS_DONE) "완료" else "미완료",
                            )
                        }
                    }
                    item {
                        DetailSection("주소") {
                            DetailValue("기본 주소", customer.address.ifBlank { "주소 없음" })
                            DetailValue("소유 주소", customer.ownedAddress, hideWhenEmpty = true)
                            DetailValue("지번 주소", customer.parcelAddress, hideWhenEmpty = true)
                        }
                    }
                    if (customer.notes.isNotBlank()) {
                        item { DetailSection("메모") { Text(customer.notes) } }
                    }
                    if (record.customFields.isNotEmpty()) {
                        item {
                            DetailSection("추가 항목") {
                                record.customFields.sortedBy { it.sortOrder }.forEach { field ->
                                    DetailValue(field.label, field.value)
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "수정 ${DETAIL_DATE_FORMATTER.format(Instant.ofEpochMilli(customer.updatedAtEpochMillis).atZone(ZoneId.systemDefault()))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(8.dp))
                            Text("고객 영구삭제", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    action: () -> Unit,
) {
    FilledTonalButton(onClick = action, enabled = enabled, modifier = Modifier.weight(1f).height(54.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(6.dp))
        Text(label)
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, hideWhenEmpty: Boolean = false) {
    if (hideWhenEmpty && value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, modifier = Modifier.weight(0.65f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerEditorDialog(
    state: CustomerEditorState,
    onDismiss: () -> Unit,
    onDraftChange: (CustomerDraft) -> Unit,
    onSave: () -> Unit,
) {
    val draft = state.draft
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (state.customerId == null) "고객 추가" else "고객 수정", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !state.isSaving) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                            }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        end = 16.dp,
                        bottom = padding.calculateBottomPadding() + 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { EditorField("고객 이름", draft.name) { onDraftChange(draft.copy(name = it)) } }
                    item {
                        EditorField("전화번호", draft.phone, KeyboardType.Phone) {
                            onDraftChange(draft.copy(phone = it))
                        }
                    }
                    item { EditorField("기본 주소", draft.address) { onDraftChange(draft.copy(address = it)) } }
                    item { EditorField("소유 주소", draft.ownedAddress) { onDraftChange(draft.copy(ownedAddress = it)) } }
                    item { EditorField("지번 주소", draft.parcelAddress) { onDraftChange(draft.copy(parcelAddress = it)) } }
                    item { EditorField("생년월일", draft.birthDate) { onDraftChange(draft.copy(birthDate = it)) } }
                    item {
                        OutlinedTextField(
                            value = draft.notes,
                            onValueChange = { onDraftChange(draft.copy(notes = it)) },
                            label = { Text("메모") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("완료 상태", fontWeight = FontWeight.Bold)
                                Text(
                                    if (draft.isDone) "완료" else "미완료",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = draft.isDone,
                                onCheckedChange = { onDraftChange(draft.copy(isDone = it)) },
                            )
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("추가 항목", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    onDraftChange(draft.copy(customFields = draft.customFields + CustomFieldDraft()))
                                },
                            ) { Icon(Icons.Default.Add, contentDescription = "항목 추가") }
                        }
                    }
                    items(draft.customFields.indices.toList()) { index ->
                        val field = draft.customFields[index]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = field.label,
                                onValueChange = { value ->
                                    onDraftChange(draft.copy(customFields = draft.customFields.updateAt(index, field.copy(label = value))))
                                },
                                label = { Text("항목명") },
                                singleLine = true,
                                modifier = Modifier.weight(0.4f),
                            )
                            OutlinedTextField(
                                value = field.value,
                                onValueChange = { value ->
                                    onDraftChange(draft.copy(customFields = draft.customFields.updateAt(index, field.copy(value = value))))
                                },
                                label = { Text("내용") },
                                singleLine = true,
                                modifier = Modifier.weight(0.6f),
                            )
                            IconButton(
                                onClick = {
                                    onDraftChange(draft.copy(customFields = draft.customFields.filterIndexed { itemIndex, _ -> itemIndex != index }))
                                },
                            ) { Icon(Icons.Default.Delete, contentDescription = "항목 삭제") }
                        }
                    }
                    state.errorMessage?.let { message ->
                        item { StatusBanner(message, isError = true) }
                    }
                    item {
                        Button(
                            onClick = onSave,
                            enabled = !state.isSaving,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                            }
                            Text("저장")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun <T> List<T>.updateAt(index: Int, value: T): List<T> =
    mapIndexed { itemIndex, item -> if (itemIndex == index) value else item }

private fun Context.openIntent(intent: Intent) {
    runCatching { startActivity(intent) }
}

private val DETAIL_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
