package com.lucid47.soheeyagaja.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid47.soheeyagaja.customers.CustomerManagementViewModel
import com.lucid47.soheeyagaja.data.AudioMemoEntity
import com.lucid47.soheeyagaja.data.HistoryEntryRecord
import com.lucid47.soheeyagaja.data.PhotoMemoEntity
import java.io.File
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoMemoDialog(
    customerId: Long,
    viewModel: CustomerManagementViewModel,
    onDismiss: () -> Unit,
) {
    val photos by viewModel.selectedCustomerPhotos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingImports by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var viewerPhoto by remember { mutableStateOf<PhotoMemoEntity?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingCameraFile
        if (saved && file != null) viewModel.saveCapturedPhoto(customerId, file) else file?.delete()
        pendingCameraFile = null
    }
    fun launchCamera() {
        runCatching {
            val file = viewModel.createCameraFile(customerId)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            pendingCameraFile = file
            camera.launch(uri)
        }.onFailure { error ->
            pendingCameraFile?.delete()
            pendingCameraFile = null
            cameraError = error.message ?: "카메라를 실행하지 못했습니다."
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else cameraError = "사진 촬영을 위해 카메라 권한을 허용해주세요."
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingImports = uris
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("사진 메모") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchCamera()
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("카메라")
                    }
                    FilledTonalButton(
                        onClick = { gallery.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("사진 가져오기")
                    }
                }
                Text("최근 사진 ${photos.size}장", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (photos.isEmpty()) {
                    Text("저장된 사진 메모가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(photos, key = PhotoMemoEntity::id) { photo ->
                            PhotoThumbnail(photo.filePath, Modifier.fillMaxWidth().aspectRatio(1f)) {
                                viewerPhoto = photo
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingImports.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingImports = emptyList() },
            title = { Text("사진 메모로 가져오기") },
            text = { Text("선택한 사진 ${pendingImports.size}장을 앱 전용 저장소에 복사해 이 고객의 기록으로 추가할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importPhotoMemos(customerId, pendingImports)
                    pendingImports = emptyList()
                }) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { pendingImports = emptyList() }) { Text("취소") } },
        )
    }
    cameraError?.let { message ->
        AlertDialog(
            onDismissRequest = { cameraError = null },
            title = { Text("카메라 실행 오류") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { cameraError = null }) { Text("확인") } },
        )
    }
    viewerPhoto?.let { photo ->
        PhotoViewer(photo, onDismiss = { viewerPhoto = null }, onDelete = {
            viewModel.deletePhotoMemo(photo)
            viewerPhoto = null
        })
    }
}

@Composable
private fun PhotoThumbnail(path: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bitmap = remember(path) { decodeBitmap(path, 640) }
    Surface(modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("사진 없음") }
        }
    }
}

@Composable
fun HistoryMediaPreview(entry: HistoryEntryRecord, modifier: Modifier = Modifier) {
    var viewerPath by remember(entry.mediaPath) { mutableStateOf<String?>(null) }
    if (entry.mediaType == "PHOTO" && !entry.mediaPath.isNullOrBlank()) {
        PhotoThumbnail(entry.mediaPath, modifier = modifier, onClick = { viewerPath = entry.mediaPath })
    } else if (entry.mediaType == "AUDIO") {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(formatDuration(entry.durationMillis ?: 0L))
        }
    }
    viewerPath?.let { path -> HistoryPhotoViewer(path = path, onDismiss = { viewerPath = null }) }
}

@Composable
private fun HistoryPhotoViewer(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(path) { decodeBitmap(path, 2400) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "사진 메모",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                IconButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("image/*")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "사진 공유",
                        ),
                    )
                }) { Icon(Icons.Default.Share, "공유", tint = Color.White) }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "닫기", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun PhotoViewer(photo: PhotoMemoEntity, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(photo.filePath) { decodeBitmap(photo.filePath, 2400) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "사진 메모",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                )
            }
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                IconButton(onClick = {
                    val file = File(photo.filePath)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("image/*")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "사진 공유",
                        ),
                    )
                }) { Icon(Icons.Default.Share, "공유", tint = Color.White) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "삭제", tint = Color.White) }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "닫기", tint = Color.White) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMemoDialog(
    customerId: Long,
    viewModel: CustomerManagementViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val entries by viewModel.selectedCustomerAudio.collectAsStateWithLifecycle()
    val transcriptionProgress by viewModel.audioTranscriptionProgress.collectAsStateWithLifecycle()
    val recording by VoiceRecordingService.state.collectAsStateWithLifecycle()
    var transcript by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) VoiceRecordingService.start(context, viewModel.createAudioFile(customerId).absolutePath)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("음성 메모") },
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
                    RecordingControls(
                        status = recording.status,
                        onStart = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                VoiceRecordingService.start(context, viewModel.createAudioFile(customerId).absolutePath)
                            } else permission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onPause = { VoiceRecordingService.pause(context) },
                        onResume = { VoiceRecordingService.resume(context) },
                        onStop = { VoiceRecordingService.stop(context) },
                        onCancel = { VoiceRecordingService.cancel(context) },
                    )
                }
                if (recording.status == VoiceRecordingStatus.FINISHED) {
                    item {
                        OutlinedTextField(
                            value = transcript,
                            onValueChange = { transcript = it },
                            label = { Text("메모 (선택)") },
                            supportingText = {
                                Text("비워두면 저장 후 기기 내에서 자동 전사합니다. 최초 1회 한국어 모델 약 82MB를 내려받습니다.")
                            },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Button(
                            onClick = {
                                val file = recording.filePath?.let(::File) ?: return@Button
                                viewModel.saveAudioMemo(customerId, file, recording.durationMillis, transcript)
                                transcript = ""
                                VoiceRecordingService.resetFinished()
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("음성 메모 저장")
                        }
                    }
                }
                item { Text("최근 음성 ${entries.size}개", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                if (entries.isEmpty()) item { Text("저장된 음성 메모가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(entries, key = AudioMemoEntity::id) { entry ->
                    AudioMemoRow(
                        entry = entry,
                        transcriptionProgress = transcriptionProgress[entry.id],
                        onRetryTranscription = { viewModel.retryAudioTranscription(entry) },
                        onDelete = { viewModel.deleteAudioMemo(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingControls(
    status: VoiceRecordingStatus,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (status) {
                VoiceRecordingStatus.IDLE, VoiceRecordingStatus.ERROR -> {
                    Button(onClick = onStart) { Icon(Icons.Default.Mic, null); Spacer(Modifier.size(8.dp)); Text("녹음 시작") }
                }
                VoiceRecordingStatus.RECORDING -> {
                    FilledTonalButton(onClick = onPause) { Icon(Icons.Default.Pause, null); Spacer(Modifier.size(6.dp)); Text("일시정지") }
                    Button(onClick = onStop) { Icon(Icons.Default.Stop, null); Spacer(Modifier.size(6.dp)); Text("녹음 중지") }
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "취소") }
                }
                VoiceRecordingStatus.PAUSED -> {
                    FilledTonalButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("계속 녹음") }
                    Button(onClick = onStop) { Icon(Icons.Default.Stop, null); Spacer(Modifier.size(6.dp)); Text("녹음 중지") }
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "취소") }
                }
                VoiceRecordingStatus.FINISHED -> Text("녹음 완료", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AudioMemoRow(
    entry: AudioMemoEntity,
    transcriptionProgress: String?,
    onRetryTranscription: () -> Unit,
    onDelete: () -> Unit,
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    DisposableEffect(entry.filePath) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            val current = player
            if (current == null || !current.isPlaying) {
                playing = false
            } else {
                progress = current.currentPosition.toFloat() / current.duration.coerceAtLeast(1)
                delay(250)
            }
        }
    }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val current = player ?: MediaPlayer().apply {
                        setDataSource(entry.filePath)
                        prepare()
                        setOnCompletionListener { playing = false; progress = 0f }
                        player = this
                    }
                    if (current.isPlaying) { current.pause(); playing = false } else { current.start(); playing = true }
                }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "재생/일시정지") }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            transcriptionProgress != null -> transcriptionProgress
                            entry.transcript.isBlank() -> "전사 없음"
                            else -> entry.transcript
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(formatDuration(entry.durationMillis), style = MaterialTheme.typography.bodySmall)
                }
                if (entry.transcript.isBlank() && transcriptionProgress == null) {
                    TextButton(onClick = onRetryTranscription) { Text("전사 재시도") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "삭제") }
            }
            Slider(
                value = progress,
                onValueChange = { next ->
                    progress = next
                    player?.let { it.seekTo((it.duration * next).toInt()) }
                },
            )
        }
    }
}

private fun decodeBitmap(path: String, maxSize: Int) = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
