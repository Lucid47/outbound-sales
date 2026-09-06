package com.lucid47.soheeyagaja.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1769E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A3470),
    secondary = Color(0xFF465D7A),
    tertiary = Color(0xFF087F6B),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EDF3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF15477F),
    onPrimaryContainer = Color(0xFFD7E5FF),
    secondary = Color(0xFFB7C9E5),
    tertiary = Color(0xFF68DBC4),
    background = Color(0xFF101114),
    surface = Color(0xFF181A1F),
    surfaceVariant = Color(0xFF30333A),
)

@Composable
fun SoheeyaGajaTheme(content: @Composable () -> Unit) {
    val settings = com.lucid47.soheeyagaja.ui.rememberDisplaySettings()
    val dark = when (settings.theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
