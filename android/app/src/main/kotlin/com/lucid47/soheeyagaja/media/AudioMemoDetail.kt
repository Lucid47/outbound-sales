package com.lucid47.soheeyagaja.media

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lucid47.soheeyagaja.data.AudioMemoEntity
import kotlinx.coroutines.delay
import org.json.JSONArray

@Composable
fun AudioMemoDetail(entry: AudioMemoEntity, onDismiss: () -> Unit) {
    var player by remember(entry.id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val words = remember(entry.transcriptWordsJson) {
        runCatching { val array = JSONArray(entry.transcriptWordsJson)
            (0 until array.length()).map { array.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }
    DisposableEffect(entry.id) { onDispose { player?.release() } }
    fun playFrom(at: Long? = null) {
        runCatching {
            val active = player ?: MediaPlayer().also {
                player = it
                it.setDataSource(entry.filePath)
                it.prepare()
                it.setOnCompletionListener { playing = false }
            }
            if (at != null) { active.seekTo(at.toInt()); position = at }
            if (at == null && active.isPlaying) { active.pause(); playing = false }
            else { active.start(); playing = true }
        }.onFailure {
            player?.release(); player = null; playing = false
            error = "음성 파일을 재생할 수 없습니다. 파일이 삭제되었거나 손상되었는지 확인해주세요."
        }
    }
    LaunchedEffect(playing) {
        while (playing) { position = player?.currentPosition?.toLong() ?: 0L; delay(100) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("음성 메모", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { playFrom() }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "재생/일시정지")
                }
                Slider(value = position.toFloat().coerceIn(0f, entry.durationMillis.coerceAtLeast(1).toFloat()),
                    valueRange = 0f..entry.durationMillis.coerceAtLeast(1).toFloat(),
                    onValueChange = { position = it.toLong() }, onValueChangeFinished = { playFrom(position) })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val annotated = buildAnnotatedString {
                    if (words.isEmpty()) append(entry.transcript.ifBlank { "전사 내용이 없습니다." })
                    else words.forEachIndexed { index, word ->
                        if (index > 0) append(" ")
                        val start = (word.optDouble("start", 0.0) * 1000).toLong()
                        val end = (word.optDouble("end", 0.0) * 1000).toLong()
                        pushStringAnnotation("time", start.toString())
                        if (position in start until end) withStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)) { append(word.optString("word")) }
                        else append(word.optString("word"))
                        pop()
                    }
                }
                @Suppress("DEPRECATION")
                ClickableText(text = annotated, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    onClick = { offset -> annotated.getStringAnnotations("time", offset, offset).firstOrNull()?.item?.toLongOrNull()?.let { playFrom(it) } })
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    }
}
