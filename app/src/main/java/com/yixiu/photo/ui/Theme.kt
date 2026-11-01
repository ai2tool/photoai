package com.yixiu.photo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0A0B0F)
val Panel = Color(0xFF14151C)
val Panel2 = Color(0xFF1D1F28)
val Panel3 = Color(0xFF272A36)
val Accent = Color(0xFF7C5CFF)
val Accent2 = Color(0xFF29D3E8)
val TextPrimary = Color(0xFFF2F3F7)
val TextSecondary = Color(0xFF8B90A3)
val HairLine = Color(0x14FFFFFF)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent2,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = Panel2,
    onSurfaceVariant = TextSecondary,
    outline = HairLine
)

@Composable
fun YixiuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.copy(
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
            bodyMedium = TextStyle(fontSize = 14.sp, color = TextPrimary),
            labelSmall = TextStyle(fontSize = 11.sp, color = TextSecondary)
        ),
        content = content
    )
}
