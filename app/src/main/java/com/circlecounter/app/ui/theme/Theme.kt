package com.circlecounter.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** High-contrast dark UI: charcoal + near-white text + bright accent. */
private val Bg = Color(0xFF0E1116)
private val Surface = Color(0xFF1A212B)
private val SurfaceHi = Color(0xFF243041)
private val Text = Color(0xFFF3F5F8)
private val TextDim = Color(0xFFB7C0CC)
private val Accent = Color(0xFFFFC14D)
private val AccentSoft = Color(0xFF5EEAD4)
private val Danger = Color(0xFFFF6B6B)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A1200),
    secondary = AccentSoft,
    onSecondary = Color(0xFF042F2E),
    tertiary = TextDim,
    background = Bg,
    onBackground = Text,
    surface = Surface,
    onSurface = Text,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = TextDim,
    error = Danger,
    onError = Color(0xFF2A0000),
    outline = Color(0xFF3A4556),
)

@Composable
fun CircleCounterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
