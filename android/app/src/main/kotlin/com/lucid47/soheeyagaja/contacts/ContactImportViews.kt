package com.lucid47.soheeyagaja.contacts

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lucid47.soheeyagaja.data.CustomerListSummary
import com.lucid47.soheeyagaja.importing.ContactDestinationMode
import com.lucid47.soheeyagaja.importing.ContactImportRecord
import com.lucid47.soheeyagaja.importing.ContactImportStep
import com.lucid47.soheeyagaja.importing.ContactImportUiState

@Composable
fun ContactImportSourcePanel(
    state: ContactImportUiState,
    enabled: Boolean,
    onSelectContacts: () -> Unit,
    onSelectGroups: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "휴대폰 연락처",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Button(
                onClick = onSelectContacts,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("개별 연락처 선택")
            }
            OutlinedButton(
                onClick = onSelectGroups,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(Icons.Default.Groups, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("연락처 그룹 선택")
            }
            state.statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.tertiary)
            }
            if (state.step == ContactImportStep.CLOSED) {
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactImportDialog(
    state: ContactImportUiState,
    customerLists: List<CustomerListSummary>,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleContact: (String) -> Unit,
    onSelectContacts: (Set<String>) -> Unit,
    onToggleGroup: (Long) -> Unit,
    onContinueContacts: () -> Unit,
    onContinueGroups: () -> Unit,
    onDestinationModeChange: (ContactDestinationMode) -> Unit,
    onDestinationListChange: (Long) -> Unit,
    onListNameChange: (String) -> Unit,
    onSkipDuplicatesChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = when (state.step) {
                                    ContactImportStep.CONTACTS -> "개별 연락처 선택"
                                    ContactImportStep.GROUPS -> "연락처 그룹 선택"
                                    ContactImportStep.DESTINATION -> "연락처 가져오기"
                                    ContactImportStep.CLOSED -> "연락처 가져오기"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "닫기")
                            }
                        },
                    )
                },
                bottomBar = {
                    ContactImportBottomBar(
                        state = state,
                        customerLists = customerLists,
                        onContinueContacts = onContinueContacts,
                        onContinueGroups = onContinueGroups,
                        onSave = onSave,
                    )
                },
            ) { padding ->
                when {
                    state.isLoading -> LoadingContent(Modifier.padding(padding))
                    state.step == ContactImportStep.CONTACTS -> ContactsContent(
                        state = state,
                        modifier = Modifier.padding(padding),
                        onSearchChange = onSearchChange,
                        onToggleContact = onToggleContact,
                        onSelectContacts = onSelectContacts,
                    )
                    state.step == ContactImportStep.GROUPS -> GroupsContent(
                        state = state,
                        modifier = Modifier.padding(padding),
                        onToggleGroup = onToggleGroup,
                    )
                    state.step == ContactImportStep.DESTINATION -> DestinationContent(
                        state = state,
                        customerLists = customerLists,
                        modifier = Modifier.padding(padding),
                        onDestinationModeChange = onDestinationModeChange,
                        onDestinationListChange = onDestinationListChange,
                        onListNameChange = onListNameChange,
                        onSkipDuplicatesChange = onSkipDuplicatesChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactsContent(
    state: ContactImportUiState,
    modifier: Modifier,
    onSearchChange: (String) -> Unit,
    onToggleContact: (String) -> Unit,
    onSelectContacts: (Set<String>) -> Unit,
) {
    val filtered = remember(state.contacts, state.searchQuery) {
        val query = state.searchQuery.trim()
        if (query.isEmpty()) state.contacts else state.contacts.filter { contact ->
            contact.name.contains(query, ignoreCase = true) ||
                contact.phoneNumber.contains(query) ||
                contact.address.contains(query, ignoreCase = true)
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("이름, 전화번호 또는 주소") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.selectedContactIds.size}명 선택",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
            Row {
                TextButton(onClick = {
                    onSelectContacts(state.selectedContactIds + filtered.map(ContactImportRecord::contactIdentifier))
                }) { Text("전체 선택") }
                TextButton(onClick = { onSelectContacts(emptySet()) }) { Text("전체 해제") }
            }
        }
        if (filtered.isEmpty()) {
            EmptyContent(state.errorMessage ?: "일치하는 연락처가 없습니다.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = ContactImportRecord::contactIdentifier) { contact ->
                    ContactRow(
                        contact = contact,
                        selected = contact.contactIdentifier in state.selectedContactIds,
                        onClick = { onToggleContact(contact.contactIdentifier) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: ContactImportRecord, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onClick() })
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name.ifBlank { "이름 없음" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = contact.phoneNumber.ifBlank { "전화번호 없음" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (contact.address.isNotBlank()) {
                Text(
                    text = contact.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GroupsContent(
    state: ContactImportUiState,
    modifier: Modifier,
    onToggleGroup: (Long) -> Unit,
) {
    if (state.groups.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyContent(state.errorMessage ?: "연락처 그룹이 없습니다.")
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = "${state.selectedGroupIds.size}개 그룹 선택",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(state.groups, key = DeviceContactGroup::id) { group ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onToggleGroup(group.id) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = group.id in state.selectedGroupIds,
                    onCheckedChange = { onToggleGroup(group.id) },
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = listOf("${group.contactCount}명", group.accountName)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
        }
    }
}

@Composable
private fun DestinationContent(
    state: ContactImportUiState,
    customerLists: List<CustomerListSummary>,
    modifier: Modifier,
    onDestinationModeChange: (ContactDestinationMode) -> Unit,
    onDestinationListChange: (Long) -> Unit,
    onListNameChange: (String) -> Unit,
    onSkipDuplicatesChange: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("${state.contacts.size}명 선택", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.destinationMode == ContactDestinationMode.EXISTING_LIST,
                    onClick = { onDestinationModeChange(ContactDestinationMode.EXISTING_LIST) },
                    enabled = customerLists.isNotEmpty(),
                    label = { Text("기존 리스트에 추가") },
                )
                FilterChip(
                    selected = state.destinationMode == ContactDestinationMode.NEW_LIST,
                    onClick = { onDestinationModeChange(ContactDestinationMode.NEW_LIST) },
                    label = { Text("새 리스트") },
                )
            }
        }
        if (state.destinationMode == ContactDestinationMode.NEW_LIST) {
            item {
                OutlinedTextField(
                    value = state.newListName,
                    onValueChange = onListNameChange,
                    label = { Text("고객리스트 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            items(customerLists, key = CustomerListSummary::id) { customerList ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onDestinationListChange(customerList.id) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.selectedListId == customerList.id,
                        onClick = { onDestinationListChange(customerList.id) },
                    )
                    Column {
                        Text(customerList.name, fontWeight = FontWeight.Bold)
                        Text("${customerList.customerCount}명", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("중복 전화번호 건너뛰기", fontWeight = FontWeight.Bold)
                    Text(
                        "앱의 전체 고객 전화번호와 비교합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.skipDuplicatePhones,
                    onCheckedChange = onSkipDuplicatesChange,
                )
            }
        }
        item {
            Text("미리보기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(state.contacts.take(8), key = ContactImportRecord::contactIdentifier) { contact ->
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(contact.name.ifBlank { "이름 없음" }, fontWeight = FontWeight.Bold)
                Text(contact.phoneNumber.ifBlank { "전화번호 없음" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.contacts.size > 8) {
            item { Text("외 ${state.contacts.size - 8}명", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ContactImportBottomBar(
    state: ContactImportUiState,
    customerLists: List<CustomerListSummary>,
    onContinueContacts: () -> Unit,
    onContinueGroups: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = when (state.step) {
                ContactImportStep.CONTACTS -> onContinueContacts
                ContactImportStep.GROUPS -> onContinueGroups
                ContactImportStep.DESTINATION -> onSave
                ContactImportStep.CLOSED -> ({})
            },
            enabled = !state.isLoading && when (state.step) {
                ContactImportStep.CONTACTS -> state.selectedContactIds.isNotEmpty()
                ContactImportStep.GROUPS -> state.selectedGroupIds.isNotEmpty()
                ContactImportStep.DESTINATION -> when (state.destinationMode) {
                    ContactDestinationMode.NEW_LIST -> state.newListName.isNotBlank()
                    ContactDestinationMode.EXISTING_LIST ->
                        state.selectedListId != null && customerLists.any { it.id == state.selectedListId }
                }
                ContactImportStep.CLOSED -> false
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
        ) {
            Text(
                when (state.step) {
                    ContactImportStep.CONTACTS -> "다음 (${state.selectedContactIds.size}명)"
                    ContactImportStep.GROUPS -> "다음 (${state.selectedGroupIds.size}개 그룹)"
                    ContactImportStep.DESTINATION -> "저장"
                    ContactImportStep.CLOSED -> "다음"
                },
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("연락처를 불러오는 중")
        }
    }
}

@Composable
private fun EmptyContent(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}
