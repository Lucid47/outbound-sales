package com.lucid47.soheeyagaja

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.data.CustomerListSummary
import com.lucid47.soheeyagaja.importing.ImportUiState
import com.lucid47.soheeyagaja.importing.ImportViewModel
import com.lucid47.soheeyagaja.ui.theme.SoheeyaGajaTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val importViewModel: ImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SoheeyaGajaTheme {
                val uiState by importViewModel.uiState.collectAsStateWithLifecycle()
                val customerLists by importViewModel.customerLists.collectAsStateWithLifecycle()
                CustomerImportScreen(
                    uiState = uiState,
                    customerLists = customerLists,
                    onFileSelected = importViewModel::selectFile,
                    onListNameChanged = importViewModel::updateListName,
                    onImport = importViewModel::importSelectedFile,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerImportScreen(
    uiState: ImportUiState,
    customerLists: List<CustomerListSummary>,
    onFileSelected: (android.net.Uri) -> Unit,
    onListNameChanged: (String) -> Unit,
    onImport: () -> Unit,
) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFileSelected(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "고객 가져오기",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ImportPanel(
                    uiState = uiState,
                    onChooseFile = {
                        filePicker.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "text/plain",
                                "application/csv",
                                "application/vnd.ms-excel",
                            ),
                        )
                    },
                    onListNameChanged = onListNameChanged,
                    onImport = onImport,
                )
            }

            item {
                Text(
                    text = "고객리스트",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (customerLists.isEmpty()) {
                item {
                    Text(
                        text = "저장된 고객리스트가 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            } else {
                items(customerLists, key = CustomerListSummary::id) { customerList ->
                    CustomerListRow(customerList)
                }
            }
        }
    }
}

@Composable
private fun ImportPanel(
    uiState: ImportUiState,
    onChooseFile: () -> Unit,
    onListNameChanged: (String) -> Unit,
    onImport: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedButton(
                onClick = onChooseFile,
                enabled = !uiState.isImporting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = uiState.selectedFileName.ifBlank { "CSV 파일 선택" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedTextField(
                value = uiState.listName,
                onValueChange = onListNameChanged,
                enabled = !uiState.isImporting,
                label = { Text("고객리스트 이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onImport,
                enabled = !uiState.isImporting,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                if (uiState.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                }
                Spacer(Modifier.size(8.dp))
                Text(if (uiState.isImporting) "가져오는 중" else "가져오기")
            }

            if (uiState.isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "${uiState.progress.processedRows}행 확인 · ${uiState.progress.acceptedRows}명 추가",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            uiState.resultMessage?.let { message ->
                StatusRow(message, isError = false)
            }
            uiState.errorMessage?.let { message ->
                StatusRow(message, isError = true)
            }
        }
    }
}

@Composable
private fun StatusRow(message: String, isError: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CustomerListRow(customerList: CustomerListSummary) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = customerList.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${customerList.customerCount}명",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${customerList.sourceName} · ${formatDate(customerList.createdAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDate(epochMillis: Long): String = DATE_FORMATTER.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
