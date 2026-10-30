package com.runkeep.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest = Color(0xFF195C46)
val Lime = Color(0xFFD9F88C)
private val light = lightColorScheme(primary = Forest, onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EDD9), onPrimaryContainer = Color(0xFF173E30),
    secondary = Color(0xFF637150), secondaryContainer = Lime,
    background = Color(0xFFF8F8F2), surface = Color(0xFFF8F8F2), surfaceContainer = Color(0xFFF0F1E9),
    onSurface = Color(0xFF1E2D25), outlineVariant = Color(0xFFDDE2D5))
private val dark = darkColorScheme(primary = Lime, onPrimary = Color(0xFF193C2D),
    primaryContainer = Forest, secondaryContainer = Color(0xFF344F34), background = Color(0xFF101D17),
    surface = Color(0xFF101D17), surfaceContainer = Color(0xFF1B2B22), onSurface = Color(0xFFE7EFE4))
@Composable fun RunkeepTheme(darkMode: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkMode) dark else light,
        typography = Typography(
            displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 74.sp, letterSpacing = (-4).sp),
            headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
        ), content = content)
}
