package com.lucid47.soheeyagaja.importing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun CsvPreviewDialog(state: ImportUiState, viewModel: ImportViewModel) {
    Dialog(onDismissRequest = viewModel::closeCsvPreview) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("가져올 고객 선택", style = MaterialTheme.typography.titleLarge)
                Row {
                    TextButton(onClick = { viewModel.selectAllCsvRows(true) }) { Text("전체 선택") }
                    TextButton(onClick = { viewModel.selectAllCsvRows(false) }) { Text("전체 해제") }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(state.previewRows, key = { it.sourceRow }) { row ->
                        Row {
                            Checkbox(checked = if (state.selectAllRows) row.sourceRow !in state.selectionExceptions else row.sourceRow in state.selectionExceptions,
                                onCheckedChange = { viewModel.toggleCsvRow(row.sourceRow) })
                            Column(Modifier.weight(1f)) { Text(row.name); Text(row.phone); Text(row.address, maxLines = 2) }
                        }
                    }
                }
                Row {
                    TextButton(enabled = state.previewPage > 0, onClick = { viewModel.previewCsv(state.previewPage - 1) }) { Text("이전") }
                    Text("${state.previewPage + 1}")
                    TextButton(enabled = state.previewHasMore, onClick = { viewModel.previewCsv(state.previewPage + 1) }) { Text("다음") }
                }
                Row {
                    TextButton(onClick = viewModel::closeCsvPreview) { Text("취소") }
                    TextButton(onClick = viewModel::importSelectedFile) { Text("선택 고객 가져오기") }
                }
            }
        }
    }
}
