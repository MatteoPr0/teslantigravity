package com.tesla.autostreamer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TeslaRed = Color(0xFFE82127)
val DarkBackground = Color(0xFF0D0D11)
val SurfaceElevated = Color(0xFF181920)
val TextPrimary = Color(0xFFF0F2F5)
val TextSecondary = Color(0xFF9095A0)
val StatusGreen = Color(0xFF10B981)

private val DarkColorScheme = darkColorScheme(
    primary = TeslaRed,
    secondary = Color(0xFF3B82F6),
    background = DarkBackground,
    surface = SurfaceElevated,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun TeslaStreamerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
