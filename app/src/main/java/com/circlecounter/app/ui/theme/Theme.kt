package com.circlecounter.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Forest = Color(0xFF1B4332)
private val Moss = Color(0xFF2D6A4F)
private val Mist = Color(0xFFD8F3DC)
private val Leaf = Color(0xFF95D5B2)
private val Ink = Color(0xFF081C15)
private val Warn = Color(0xFFE9C46A)

private val scheme = darkColorScheme(
    primary = Leaf,
    onPrimary = Ink,
    secondary = Mist,
    onSecondary = Ink,
    background = Forest,
    onBackground = Mist,
    surface = Moss,
    onSurface = Mist,
    error = Warn,
    onError = Ink,
)

@Composable
fun CircleCounterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
