package com.lucid47.soheeyagaja.importing

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.domain.importing.ImportProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportUiState(
    val selectedUri: Uri? = null,
    val selectedFileName: String = "",
    val listName: String = "",
    val isImporting: Boolean = false,
    val progress: ImportProgress = ImportProgress(),
    val resultMessage: String? = null,
    val errorMessage: String? = null,
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CustomerImportRepository(AppDatabase.get(application))
    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState = _uiState.asStateFlow()

    val customerLists = repository.observeCustomerLists().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun selectFile(uri: Uri) {
        val displayName = resolveDisplayName(uri)
        _uiState.update { state ->
            state.copy(
                selectedUri = uri,
                selectedFileName = displayName,
                listName = displayName.substringBeforeLast('.').ifBlank { "새 고객리스트" },
                progress = ImportProgress(),
                resultMessage = null,
                errorMessage = null,
            )
        }
    }

    fun updateListName(value: String) {
        _uiState.update { it.copy(listName = value, resultMessage = null, errorMessage = null) }
    }

    fun importSelectedFile() {
        val request = _uiState.value
        if (request.selectedUri == null) {
            _uiState.update { it.copy(errorMessage = "가져올 CSV 파일을 선택해주세요.") }
            return
        }
        if (request.listName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "고객리스트 이름을 입력해주세요.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    progress = ImportProgress(),
                    resultMessage = null,
                    errorMessage = null,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(request.selectedUri).use { source ->
                        requireNotNull(source) { "선택한 파일을 열 수 없습니다." }
                        repository.importCsv(
                            source = source,
                            listName = request.listName,
                            sourceName = request.selectedFileName,
                            onProgress = { progress ->
                                _uiState.update { it.copy(progress = progress) }
                            },
                        )
                    }
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = result.progress,
                        resultMessage = "${result.progress.acceptedRows}명의 고객을 추가했습니다.",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = error.message ?: "가져오기에 실패했습니다.",
                    )
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty().ifBlank { "고객리스트.csv" }
    }
}
