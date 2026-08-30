package com.gamr.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GamrColorScheme = darkColorScheme(
    primary = GamrPurple,
    onPrimary = GamrInk,
    secondary = GamrCyan,
    onSecondary = GamrInk,
    tertiary = GamrGreen,
    background = GamrInk,
    onBackground = GamrText,
    surface = GamrSurface,
    onSurface = GamrText,
    surfaceVariant = GamrSurfaceBright,
    onSurfaceVariant = GamrTextMuted,
)

@Composable
fun GamrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GamrColorScheme,
        typography = Typography,
        content = content,
    )
}
