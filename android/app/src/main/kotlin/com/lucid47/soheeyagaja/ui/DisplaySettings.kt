package com.lucid47.soheeyagaja.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class DisplaySettings(val theme: String = "SYSTEM", val phone: Boolean = true,
    val address: Boolean = true, val notes: Boolean = true, val custom: Boolean = true)

private fun SharedPreferences.displaySettings() = DisplaySettings(getString("theme", "SYSTEM") ?: "SYSTEM",
    getBoolean("phone", true), getBoolean("address", true), getBoolean("notes", true), getBoolean("custom", true))

@Composable
fun rememberDisplaySettings(): DisplaySettings {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("display-settings", Context.MODE_PRIVATE) }
    var settings by remember { mutableStateOf(prefs.displaySettings()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settings = prefs.displaySettings() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

@Composable
fun DisplaySettingsPanel() {
    val settings = rememberDisplaySettings()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("display-settings", Context.MODE_PRIVATE) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("화면 모드", style = MaterialTheme.typography.titleMedium)
        Row {
            listOf("SYSTEM" to "자동", "LIGHT" to "라이트", "DARK" to "다크").forEach { (key, label) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { RadioButton(settings.theme == key, onClick = { prefs.edit().putString("theme", key).apply() }); Text(label) }
            }
        }
        Text("고객카드 표시 항목", style = MaterialTheme.typography.titleMedium)
        listOf(Triple("phone", "전화번호", settings.phone), Triple("address", "주소", settings.address),
            Triple("notes", "메모", settings.notes), Triple("custom", "추가 항목", settings.custom)).forEach { (key, label, enabled) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(label)
                Switch(enabled, onCheckedChange = { prefs.edit().putBoolean(key, it).apply() })
            }
        }
    }
}
