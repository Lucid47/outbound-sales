package com.lucid47.soheeyagaja.transcript

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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTranscriptImportDialog(viewModel: SharedTranscriptImportViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.draft == null && state.errorMessage == null && state.completionMessage == null && !state.isBusy) return

    Dialog(
        onDismissRequest = viewModel::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("통화 녹음·전사 가져오기", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::dismiss, enabled = !state.isBusy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                        }
                    },
                )
            },
        ) { padding ->
            when {
                state.isBusy && state.draft == null -> LoadingContent(state.progress, padding)
                state.draft != null -> DraftContent(
                    state = state,
                    contentPadding = padding,
                    onSelectCustomer = viewModel::selectCustomer,
                    onSave = viewModel::save,
                )
                else -> ResultContent(
                    message = state.errorMessage ?: state.completionMessage.orEmpty(),
                    isError = state.errorMessage != null,
                    contentPadding = padding,
                    onClose = viewModel::dismiss,
                )
            }
        }
    }
}

@Composable
private fun DraftContent(
    state: SharedTranscriptUiState,
    contentPadding: PaddingValues,
    onSelectCustomer: (Long) -> Unit,
    onSave: () -> Unit,
) {
    val draft = requireNotNull(state.draft)
    var query by rememberSaveable { mutableStateOf("") }
    val visibleCustomers = remember(draft.customers, query) {
        val normalized = query.trim().lowercase()
        draft.customers.filter {
            normalized.isBlank() || it.name.lowercase().contains(normalized) ||
                it.phone.contains(normalized) || it.listName.lowercase().contains(normalized)
        }.take(MAX_VISIBLE_CUSTOMERS)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, contentPadding.calculateTopPadding() + 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AudioFile, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(draft.sourceNames.joinToString(", "), fontWeight = FontWeight.Bold, maxLines = 2)
                    }
                    Text(
                        TRANSCRIPT_DATE_FORMATTER.format(
                            Instant.ofEpochMilli(draft.occurredAtEpochMillis).atZone(ZoneId.systemDefault()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (draft.transcript.isBlank()) {
                            "공유된 텍스트가 없어 저장 후 오프라인 자동 전사를 시작합니다. 최초 1회 약 82MB 모델을 받으며, 다른 화면으로 이동해도 다운로드를 계속합니다."
                        }
                        else draft.transcript,
                        maxLines = 7,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            Text("연결할 고객", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (draft.selectedCustomerId != null) "파일명·전화번호·통화시각을 기준으로 고객을 추천했습니다. 저장 전에 확인해주세요."
                else "자동으로 확정하지 못했습니다. 고객을 검색해 선택해주세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("이름·전화번호·고객리스트 검색") },
                singleLine = true,
            )
        }
        items(visibleCustomers, key = SharedTranscriptCustomer::id) { customer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.isBusy) { onSelectCustomer(customer.id) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = draft.selectedCustomerId == customer.id,
                    onClick = { onSelectCustomer(customer.id) },
                    enabled = !state.isBusy,
                )
                Column(Modifier.weight(1f)) {
                    Text(customer.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${customer.phone} · ${customer.listName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (customer.matchScore > 0) {
                    Text("추천", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider()
        }
        if (draft.customers.size > visibleCustomers.size && query.isBlank()) {
            item {
                Text(
                    "고객이 많아 추천 순서의 ${MAX_VISIBLE_CUSTOMERS}명만 표시합니다. 검색하면 전체 고객에서 찾습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
        }
        state.progress?.let { progress -> item { Text(progress, color = MaterialTheme.colorScheme.primary) } }
        item {
            Button(
                onClick = onSave,
                enabled = draft.selectedCustomerId != null && !state.isBusy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                }
                Text("고객 히스토리에 저장")
            }
        }
    }
}

@Composable
private fun LoadingContent(progress: String?, padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(progress ?: "처리 중...")
    }
}

@Composable
private fun ResultContent(
    message: String,
    isError: Boolean,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("닫기")
        }
    }
}

private val TRANSCRIPT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 M월 d일 a h:mm")
private const val MAX_VISIBLE_CUSTOMERS = 200
