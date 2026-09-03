package com.lucid47.soheeyagaja.transcript

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.lucid47.soheeyagaja.activities.ActivityRepository
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.ContactLogEntity
import com.lucid47.soheeyagaja.domain.importing.ImportedCustomer
import com.lucid47.soheeyagaja.media.CustomerMediaRepository
import com.lucid47.soheeyagaja.media.RecordedAudioTranscriber
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SharedTranscriptCustomer(
    val id: Long,
    val listName: String,
    val name: String,
    val phone: String,
    val matchScore: Int,
)

data class SharedTranscriptDraft(
    val transcript: String,
    val temporaryAudioPath: String?,
    val sourceNames: List<String>,
    val occurredAtEpochMillis: Long,
    val customers: List<SharedTranscriptCustomer>,
    val selectedCustomerId: Long?,
)

data class SharedTranscriptUiState(
    val draft: SharedTranscriptDraft? = null,
    val isBusy: Boolean = false,
    val progress: String? = null,
    val errorMessage: String? = null,
    val completionMessage: String? = null,
)

private data class SharedTranscriptSaveResult(
    val audioId: Long? = null,
    val audioFile: File? = null,
    val transcriptWasMissing: Boolean = false,
)

class SharedTranscriptImportViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.get(application)
    private val repository = SharedTranscriptImportRepository(application, database)
    private val transcriber = RecordedAudioTranscriber(application)
    private val _uiState = MutableStateFlow(SharedTranscriptUiState())
    val uiState: StateFlow<SharedTranscriptUiState> = _uiState.asStateFlow()

    fun acceptIntent(intent: Intent?) {
        val sharedIntent = intent ?: return
        if (sharedIntent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        viewModelScope.launch {
            repository.cleanup(_uiState.value.draft)
            _uiState.value = SharedTranscriptUiState(isBusy = true, progress = "공유 내용을 읽는 중...")
            runCatching { repository.prepare(sharedIntent) }
                .onSuccess { draft -> _uiState.value = SharedTranscriptUiState(draft = draft) }
                .onFailure { error ->
                    _uiState.value = SharedTranscriptUiState(
                        errorMessage = error.message ?: "공유된 통화 전사를 읽지 못했습니다.",
                    )
                }
            sharedIntent.action = null
        }
    }

    fun selectCustomer(customerId: Long) {
        _uiState.update { state ->
            state.copy(draft = state.draft?.copy(selectedCustomerId = customerId), errorMessage = null)
        }
    }

    fun save() {
        val draft = _uiState.value.draft ?: return
        val customerId = draft.selectedCustomerId ?: run {
            _uiState.update { it.copy(errorMessage = "전사를 연결할 고객을 선택해주세요.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, progress = "고객 히스토리에 저장하는 중...", errorMessage = null) }
            runCatching { repository.save(draft, customerId) }
                .onSuccess { result ->
                    repository.cleanup(draft)
                    _uiState.value = SharedTranscriptUiState(
                        completionMessage = if (result.transcriptWasMissing) {
                            "통화 녹음을 저장했습니다. 기기 내 자동 전사를 계속합니다."
                        } else if (result.audioId != null) {
                            "통화 녹음과 전사를 고객 히스토리에 저장했습니다."
                        } else {
                            "통화 전사를 고객 히스토리에 저장했습니다."
                        },
                    )
                    if (result.transcriptWasMissing && result.audioId != null && result.audioFile != null) {
                        transcribeImportedAudio(result.audioId, result.audioFile)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            progress = null,
                            errorMessage = error.message ?: "통화 전사를 저장하지 못했습니다.",
                        )
                    }
                }
        }
    }

    private fun transcribeImportedAudio(audioId: Long, file: File) {
        viewModelScope.launch {
            runCatching { transcriber.transcribe(file) }
                .onSuccess { transcript -> repository.updateTranscript(audioId, transcript) }
                .onFailure { error ->
                    if (_uiState.value.completionMessage != null) {
                        _uiState.update {
                            it.copy(completionMessage = "통화 녹음은 저장했지만 자동 전사에 실패했습니다. ${error.message.orEmpty()}".trim())
                        }
                    }
                }
        }
    }

    fun dismiss() {
        if (_uiState.value.isBusy) return
        repository.cleanup(_uiState.value.draft)
        _uiState.value = SharedTranscriptUiState()
    }

    override fun onCleared() {
        repository.cleanup(_uiState.value.draft)
        super.onCleared()
    }
}

private class SharedTranscriptImportRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    private val mediaRepository = CustomerMediaRepository(context, database)

    suspend fun prepare(intent: Intent): SharedTranscriptDraft = withContext(Dispatchers.IO) {
        val uris = intent.sharedUris()
        val sourceNames = mutableListOf<String>()
        val textParts = mutableListOf<String>()
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(textParts::add)
        var temporaryAudio: File? = null

        uris.forEach { uri ->
            val name = displayName(uri)
            sourceNames += name
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
            when {
                mime.startsWith("audio/") || name.hasAudioExtension() -> {
                    if (temporaryAudio == null) temporaryAudio = copyIncomingAudio(uri, name)
                }
                mime.startsWith("text/") || name.endsWith(".txt", ignoreCase = true) -> {
                    readSharedText(uri)?.let(textParts::add)
                }
            }
        }

        val transcript = textParts.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n\n")
        require(transcript.isNotBlank() || temporaryAudio != null) {
            "텍스트 전사 또는 음성 파일을 찾지 못했습니다. 삼성 전화 앱에서 텍스트나 '음성과 텍스트 파일'로 공유해주세요."
        }
        val occurredAt = SharedTranscriptMatcher.timestampFrom(sourceNames) ?: System.currentTimeMillis()
        val customers = database.customerDao().getAll()
        val listNames = database.customerListDao().getAll().associate { it.id to it.name }
        val nearestCallCustomerId = database.activityDao().nearestImportedCallCustomer(
            targetEpochMillis = occurredAt,
            fromEpochMillis = occurredAt - CALL_MATCH_WINDOW_MILLIS,
            toEpochMillis = occurredAt + CALL_MATCH_WINDOW_MILLIS,
        )
        val ranked = SharedTranscriptMatcher.rankCustomers(
            customers = customers.map {
                SharedTranscriptMatcher.CustomerInput(it.id, it.name, it.phone, listNames[it.listId].orEmpty())
            },
            sourceNames = sourceNames,
            transcript = transcript,
            nearestCallCustomerId = nearestCallCustomerId,
        )
        SharedTranscriptDraft(
            transcript = transcript,
            temporaryAudioPath = temporaryAudio?.absolutePath,
            sourceNames = sourceNames.ifEmpty { listOf("공유된 텍스트") },
            occurredAtEpochMillis = occurredAt,
            customers = ranked,
            selectedCustomerId = SharedTranscriptMatcher.recommendedCustomerId(ranked),
        )
    }

    suspend fun save(draft: SharedTranscriptDraft, customerId: Long): SharedTranscriptSaveResult =
        withContext(Dispatchers.IO) {
            val customer = requireNotNull(database.customerDao().getById(customerId)) { "선택한 고객을 찾지 못했습니다." }
            val audio = draft.temporaryAudioPath?.let(::File)?.takeIf(File::isFile)
            if (audio != null) {
                check(
                    !database.attachmentDao().hasImportedAudio(
                        customerId,
                        ActivityRepository.TYPE_CALL_TRANSCRIPT,
                        draft.occurredAtEpochMillis,
                    ),
                ) { "이미 가져온 통화 녹음입니다." }
                val duration = audioDurationMillis(audio)
                val id = mediaRepository.importCallRecording(
                    customerId = customer.id,
                    source = audio,
                    durationMillis = duration,
                    transcript = draft.transcript,
                    occurredAtEpochMillis = draft.occurredAtEpochMillis,
                )
                val imported = database.attachmentDao().getAudioById(id)
                return@withContext SharedTranscriptSaveResult(
                    audioId = id,
                    audioFile = imported?.filePath?.let(::File),
                    transcriptWasMissing = draft.transcript.isBlank(),
                )
            }

            val transcript = draft.transcript.trim()
            require(transcript.isNotBlank()) { "저장할 전사 내용이 없습니다." }
            check(
                !database.activityDao().hasImportedText(
                    customer.id,
                    ActivityRepository.TYPE_CALL_TRANSCRIPT,
                    transcript,
                ),
            ) { "이미 가져온 통화 전사입니다." }
            database.withTransaction {
                database.activityDao().insertContactLog(
                    ContactLogEntity(
                        listId = customer.listId,
                        customerId = customer.id,
                        type = ActivityRepository.TYPE_CALL_TRANSCRIPT,
                        result = ActivityRepository.RESULT_IMPORTED,
                        messageBody = transcript,
                        createdAtEpochMillis = draft.occurredAtEpochMillis,
                    ),
                )
                database.customerListDao().touch(customer.listId, System.currentTimeMillis())
            }
            SharedTranscriptSaveResult()
        }

    suspend fun updateTranscript(audioId: Long, transcript: String) =
        mediaRepository.updateAudioTranscript(audioId, transcript)

    fun cleanup(draft: SharedTranscriptDraft?) {
        draft?.temporaryAudioPath?.let(::File)?.takeIf { it.isFile && it.isInIncomingCache(context) }?.delete()
    }

    private fun readSharedText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(8_192)
            val result = StringBuilder()
            while (result.length < MAX_TRANSCRIPT_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_TRANSCRIPT_CHARS - result.length))
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            result.toString().trim()
        }
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun copyIncomingAudio(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "m4a").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "m4a"
        val directory = File(context.cacheDir, INCOMING_DIRECTORY).apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "공유된 음성 파일을 열지 못했습니다." }
            destination.outputStream().buffered().use(input::copyTo)
        }
        require(destination.length() > 0L) { "공유된 음성 파일이 비어 있습니다." }
        return destination
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
            }
        }
        return uri.lastPathSegment.orEmpty().ifBlank { "공유 파일" }
    }

    private fun audioDurationMillis(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun Intent.sharedUris(): List<Uri> = buildList {
        @Suppress("DEPRECATION")
        when (action) {
            Intent.ACTION_SEND -> (getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(::add)
            Intent.ACTION_SEND_MULTIPLE -> getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
        }
        clipData?.let { clips ->
            for (index in 0 until clips.itemCount) clips.getItemAt(index).uri?.let(::add)
        }
    }.distinct()

    private fun String.hasAudioExtension(): Boolean =
        substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "wav", "aac", "ogg", "amr", "3gp", "flac")

    private fun File.isInIncomingCache(context: Context): Boolean =
        runCatching { canonicalPath.startsWith(File(context.cacheDir, INCOMING_DIRECTORY).canonicalPath + File.separator) }
            .getOrDefault(false)

    private companion object {
        const val MAX_TRANSCRIPT_CHARS = 1_000_000
        const val CALL_MATCH_WINDOW_MILLIS = 5L * 60L * 1_000L
        const val INCOMING_DIRECTORY = "incoming-transcripts"
    }
}

internal object SharedTranscriptMatcher {
    data class CustomerInput(
        val id: Long,
        val name: String,
        val phone: String,
        val listName: String,
    )

    private val timestampPattern = Regex("(?<!\\d)(\\d{6})[_ -]?(\\d{6})(?!\\d)")
    private val phonePattern = Regex("(?:\\+82|0)[0-9 -]{8,14}[0-9]")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyMMddHHmmss")

    fun timestampFrom(sourceNames: List<String>): Long? {
        sourceNames.forEach { name ->
            val match = timestampPattern.find(name) ?: return@forEach
            runCatching {
                return LocalDateTime.parse(match.groupValues[1] + match.groupValues[2], timestampFormatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        }
        return null
    }

    fun rankCustomers(
        customers: List<CustomerInput>,
        sourceNames: List<String>,
        transcript: String,
        nearestCallCustomerId: Long?,
    ): List<SharedTranscriptCustomer> {
        val source = sourceNames.joinToString(" ").replace(" ", "").lowercase()
        val phones = phonePattern.findAll((sourceNames.joinToString(" ") + " " + transcript.take(2_000)))
            .map { ImportedCustomer.normalizePhone(it.value) }
            .filter(String::isNotBlank)
            .toSet()
        return customers.map { customer ->
            val normalizedPhone = ImportedCustomer.normalizePhone(customer.phone)
            var score = 0
            if (normalizedPhone.isNotBlank() && normalizedPhone in phones) score += 120
            val normalizedName = customer.name.replace(" ", "").lowercase()
            if (normalizedName.length >= 2 && source.contains(normalizedName)) score += 80
            if (customer.id == nearestCallCustomerId) score += 100
            SharedTranscriptCustomer(
                id = customer.id,
                listName = customer.listName,
                name = customer.name,
                phone = customer.phone,
                matchScore = score,
            )
        }.sortedWith(compareByDescending<SharedTranscriptCustomer> { it.matchScore }.thenBy { it.name })
    }

    fun recommendedCustomerId(customers: List<SharedTranscriptCustomer>): Long? {
        val top = customers.firstOrNull() ?: return null
        if (top.matchScore < 80) return null
        val second = customers.getOrNull(1)
        return top.id.takeIf { second == null || top.matchScore > second.matchScore }
    }
}
