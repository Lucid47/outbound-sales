package com.lucid47.soheeyagaja.messaging

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.contacts.AndroidContactService
import com.lucid47.soheeyagaja.contacts.DeviceContactGroup
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.domain.importing.ImportedCustomer
import com.lucid47.soheeyagaja.importing.ContactImportRecord
import kotlinx.coroutines.launch

private data class MessageRecipient(
    val id: String,
    val name: String,
    val phone: String,
    val normalizedPhone: String,
    val customerId: Long? = null,
    val source: String,
)

private enum class ContactPickerMode { NONE, CONTACTS, GROUPS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMessageDialog(
    viewModel: CustomerManagementViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customerRecords by viewModel.allCustomers.collectAsStateWithLifecycle()
    val schedule by viewModel.todaySchedule.collectAsStateWithLifecycle()
    val contactService = remember { AndroidContactService(context) }
    var recipients by remember { mutableStateOf<List<MessageRecipient>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pickerMode by remember { mutableStateOf(ContactPickerMode.NONE) }
    var contacts by remember { mutableStateOf<List<ContactImportRecord>>(emptyList()) }
    var groups by remember { mutableStateOf<List<DeviceContactGroup>>(emptyList()) }
    var pickedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pickedGroupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var duplicateExcluded by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun merge(next: List<MessageRecipient>) {
        val existingNumbers = recipients.filter { validPhone(it.normalizedPhone) }.mapTo(mutableSetOf()) { it.normalizedPhone }
        val uniqueNext = next.filter { validPhone(it.normalizedPhone) }.distinctBy { it.normalizedPhone }
        val added = uniqueNext.filter { it.normalizedPhone !in existingNumbers }
        duplicateExcluded += next.count { validPhone(it.normalizedPhone) } - added.size
        val invalid = next.filterNot { validPhone(it.normalizedPhone) }
        recipients = (recipients + added + invalid).distinctBy { it.id }
        selectedIds = selectedIds + added.map { it.id }
    }

    fun loadContacts(mode: ContactPickerMode) {
        busy = true
        error = null
        scope.launch {
            runCatching {
                if (mode == ContactPickerMode.CONTACTS) contactService.allContacts() else contactService.groups()
            }.onSuccess { result ->
                if (mode == ContactPickerMode.CONTACTS) {
                    @Suppress("UNCHECKED_CAST")
                    contacts = result as List<ContactImportRecord>
                } else {
                    @Suppress("UNCHECKED_CAST")
                    groups = result as List<DeviceContactGroup>
                }
                pickerMode = mode
            }.onFailure { error = it.message ?: "연락처를 불러오지 못했습니다." }
            busy = false
        }
    }

    var pendingContactMode by remember { mutableStateOf(ContactPickerMode.NONE) }
    val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadContacts(pendingContactMode) else error = "연락처 선택에는 연락처 권한이 필요합니다."
    }
    fun requestContacts(mode: ContactPickerMode) {
        pendingContactMode = mode
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadContacts(mode)
        } else contactPermission.launch(Manifest.permission.READ_CONTACTS)
    }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        attachments = uris
    }

    val selected = recipients.filter { it.id in selectedIds }
    val validSelected = selected.filter { validPhone(it.normalizedPhone) }.distinctBy { it.normalizedPhone }
    val invalidCount = recipients.count { !validPhone(it.normalizedPhone) } + duplicateExcluded
    val messageKind = when {
        attachments.isNotEmpty() -> "MMS"
        message.isEmpty() -> "내용 없음"
        message.any { it.code > 127 } && message.length > 70 -> "LMS 예상"
        message.all { it.code <= 127 } && message.length > 160 -> "LMS 예상"
        else -> "SMS 예상"
    }
    val canOpen = validSelected.isNotEmpty() && validSelected.size <= MAX_RECIPIENTS && message.isNotBlank() && !busy

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("단체문자") },
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
                contentPadding = PaddingValues(14.dp, padding.calculateTopPadding() + 8.dp, 14.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Text("대상 선택", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceButton("고객리스트", Icons.Default.People, Modifier.weight(1f)) {
                            merge(customerRecords.map { record ->
                                val customer = record.customer
                                MessageRecipient(
                                    id = "customer-${customer.id}",
                                    name = customer.name,
                                    phone = customer.phone,
                                    normalizedPhone = ImportedCustomer.normalizePhone(customer.phone),
                                    customerId = customer.id,
                                    source = "고객리스트",
                                )
                            })
                        }
                        SourceButton("오늘 스케줄", Icons.Default.CalendarMonth, Modifier.weight(1f)) {
                            merge(schedule.map { item ->
                                MessageRecipient(
                                    id = "customer-${item.customerId}",
                                    name = item.customerName,
                                    phone = item.phone,
                                    normalizedPhone = ImportedCustomer.normalizePhone(item.phone),
                                    customerId = item.customerId,
                                    source = "오늘 스케줄",
                                )
                            })
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceButton("연락처 선택", Icons.Default.PersonSearch, Modifier.weight(1f)) {
                            requestContacts(ContactPickerMode.CONTACTS)
                        }
                        SourceButton("연락처 그룹", Icons.Default.Groups, Modifier.weight(1f)) {
                            requestContacts(ContactPickerMode.GROUPS)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${selectedIds.size}명 선택", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedIds = recipients.filter { validPhone(it.normalizedPhone) }.mapTo(mutableSetOf()) { it.id } }) {
                            Text("전체 선택")
                        }
                        TextButton(onClick = { selectedIds = emptySet() }) { Text("전체 해제") }
                    }
                }
                if (recipients.isEmpty()) {
                    item { Text("위 버튼에서 문자 대상을 추가해주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(recipients, key = MessageRecipient::id) { recipient ->
                        RecipientRow(
                            recipient = recipient,
                            selected = recipient.id in selectedIds,
                            onToggle = {
                                selectedIds = if (recipient.id in selectedIds) selectedIds - recipient.id else selectedIds + recipient.id
                            },
                        )
                    }
                }
                item { Text("메시지 작성", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                item {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("문자 내용") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { attachmentPicker.launch(arrayOf("image/*", "application/pdf", "text/*")) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Default.AttachFile, null)
                        Spacer(Modifier.size(8.dp))
                        Text("사진·파일 여러 개 첨부 (${attachments.size})")
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("발송 전 점검", fontWeight = FontWeight.Bold)
                            Text("유효 대상 ${validSelected.size}명 · 중복/잘못된 번호 제외 ${invalidCount}명")
                            Text("$messageKind · 첨부 ${attachments.size}개")
                            Text("한 번에 최대 ${MAX_RECIPIENTS}명까지 메시지 앱으로 전달합니다.")
                            if (validSelected.size > MAX_RECIPIENTS) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.size(6.dp))
                                    Text("대상을 ${MAX_RECIPIENTS}명 이하로 줄여주세요.", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            validSelected.mapNotNull { it.customerId }.distinct().forEach(viewModel::recordSmsAttempt)
                            openMessageComposer(context, validSelected.map { it.phone }, message, attachments)
                        },
                        enabled = canOpen,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null)
                        Spacer(Modifier.size(8.dp))
                        Text("메시지 앱에서 확인")
                    }
                }
                item {
                    Text(
                        "예약 발송은 열린 삼성 메시지 작성 화면의 + 메뉴에서 예약을 선택합니다. 실제 전송과 성공 여부는 기본 메시지 앱과 통신사가 관리합니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (busy) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        }
    }

    if (pickerMode == ContactPickerMode.CONTACTS) {
        ContactSelectionDialog(
            contacts = contacts,
            selected = pickedContactIds,
            onToggle = { id -> pickedContactIds = if (id in pickedContactIds) pickedContactIds - id else pickedContactIds + id },
            onDismiss = { pickerMode = ContactPickerMode.NONE },
            onAdd = {
                merge(contacts.filter { it.contactIdentifier in pickedContactIds }.map { it.toRecipient() })
                pickerMode = ContactPickerMode.NONE
                pickedContactIds = emptySet()
            },
        )
    }
    if (pickerMode == ContactPickerMode.GROUPS) {
        GroupSelectionDialog(
            groups = groups,
            selected = pickedGroupIds,
            onToggle = { id -> pickedGroupIds = if (id in pickedGroupIds) pickedGroupIds - id else pickedGroupIds + id },
            onDismiss = { pickerMode = ContactPickerMode.NONE },
            onAdd = {
                busy = true
                scope.launch {
                    runCatching { contactService.contactsInGroups(pickedGroupIds) }
                        .onSuccess { merge(it.map(ContactImportRecord::toRecipient)) }
                        .onFailure { error = it.message }
                    busy = false
                    pickerMode = ContactPickerMode.NONE
                    pickedGroupIds = emptySet()
                }
            },
        )
    }
}

@Composable
private fun SourceButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(54.dp)) {
        Icon(icon, null)
        Spacer(Modifier.size(6.dp))
        Text(label)
    }
}

@Composable
private fun RecipientRow(recipient: MessageRecipient, selected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = validPhone(recipient.normalizedPhone))
        Column(Modifier.weight(1f)) {
            Text(recipient.name.ifBlank { "이름 없음" }, fontWeight = FontWeight.Bold)
            Text(recipient.phone.ifBlank { "잘못된 번호" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(recipient.source, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ContactSelectionDialog(
    contacts: List<ContactImportRecord>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
) {
    SimplePickerDialog("연락처 선택", selected.size, onDismiss, onAdd) {
        items(contacts, key = ContactImportRecord::contactIdentifier) { contact ->
            Row(Modifier.fillMaxWidth().clickable { onToggle(contact.contactIdentifier) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(contact.contactIdentifier in selected, { onToggle(contact.contactIdentifier) })
                Column { Text(contact.name, fontWeight = FontWeight.Bold); Text(contact.phoneNumber) }
            }
        }
    }
}

@Composable
private fun GroupSelectionDialog(
    groups: List<DeviceContactGroup>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
) {
    SimplePickerDialog("연락처 그룹 선택", selected.size, onDismiss, onAdd) {
        items(groups, key = DeviceContactGroup::id) { group ->
            Row(Modifier.fillMaxWidth().clickable { onToggle(group.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(group.id in selected, { onToggle(group.id) })
                Column { Text(group.name, fontWeight = FontWeight.Bold); Text("${group.contactCount}명") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimplePickerDialog(
    title: String,
    count: Int,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기") } },
                    actions = { TextButton(onClick = onAdd, enabled = count > 0) { Text("${count}명 추가") } },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, padding.calculateTopPadding(), 12.dp, 24.dp), content = content)
        }
    }
}

private fun ContactImportRecord.toRecipient(): MessageRecipient {
    val normalized = ImportedCustomer.normalizePhone(phoneNumber)
    return MessageRecipient("contact-$contactIdentifier", name, phoneNumber, normalized, source = "연락처")
}

private fun validPhone(normalized: String): Boolean = normalized.length in 8..15 && normalized.all(Char::isDigit)

private fun openMessageComposer(context: android.content.Context, phones: List<String>, body: String, attachments: List<Uri>) {
    val normalizedPhones = phones.map(ImportedCustomer::normalizePhone).distinct()
    val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
    val intent = if (attachments.isEmpty()) {
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${normalizedPhones.joinToString(";")}"))
            .putExtra("sms_body", body)
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments))
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra("sms_body", body)
            putExtra("address", normalizedPhones.joinToString(";"))
            clipData = ClipData.newUri(context.contentResolver, "첨부", attachments.first()).also { clip ->
                attachments.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    if (!defaultPackage.isNullOrBlank()) intent.setPackage(defaultPackage)
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            intent.setPackage(null)
            context.startActivity(Intent.createChooser(intent, "메시지 앱 선택"))
        }
}

private const val MAX_RECIPIENTS = 200
