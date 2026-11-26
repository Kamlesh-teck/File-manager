package com.example.filemanager

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** Theme ki settings (accent rang + dark/light). SharedPreferences me save hoti hain. */
object ThemeSettings {
    val accents: List<Pair<String, Color>> = listOf(
        "Blue" to Color(0xFF1976D2),
        "Green" to Color(0xFF2E7D32),
        "Purple" to Color(0xFF6A1B9A),
        "Orange" to Color(0xFFEF6C00),
        "Red" to Color(0xFFC62828),
        "Teal" to Color(0xFF00796B),
        "Pink" to Color(0xFFAD1457),
        "Indigo" to Color(0xFF283593)
    )

    var accentIndex by mutableIntStateOf(0)
    var darkMode by mutableIntStateOf(0) // 0 = system, 1 = light, 2 = dark

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        accentIndex = sp.getInt("accent", 0).coerceIn(0, accents.lastIndex)
        darkMode = sp.getInt("dark", 0).coerceIn(0, 2)
        loaded = true
    }

    fun save(context: Context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt("accent", accentIndex)
            .putInt("dark", darkMode)
            .apply()
    }
}

val LocalDark = staticCompositionLocalOf { false }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    ThemeSettings.load(LocalContext.current)
    val dark = when (ThemeSettings.darkMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val accent = ThemeSettings.accents[ThemeSettings.accentIndex].second

    val scheme = if (dark) darkColorScheme(
        primary = lerp(accent, Color.White, 0.45f),
        onPrimary = Color(0xFF101010),
        primaryContainer = lerp(accent, Color.Black, 0.45f),
        onPrimaryContainer = Color.White,
        secondary = lerp(accent, Color.White, 0.3f),
        background = Color(0xFF181818),
        onBackground = Color(0xFFEAEAEA),
        surface = Color(0xFF1E1E1E),
        onSurface = Color(0xFFEAEAEA),
        surfaceVariant = Color(0xFF2B2B2B),
        onSurfaceVariant = Color(0xFFBDBDBD),
        surfaceContainer = Color(0xFF2B2B2B),
        surfaceContainerHigh = Color(0xFF2B2B2B)
    ) else lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = lerp(accent, Color.White, 0.82f),
        onPrimaryContainer = lerp(accent, Color.Black, 0.5f),
        secondary = lerp(accent, Color.Black, 0.2f),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF1B1B1B),
        surface = Color(0xFFFAFAFA),
        onSurface = Color(0xFF1B1B1B),
        surfaceVariant = lerp(accent, Color.White, 0.9f),
        onSurfaceVariant = Color(0xFF444444),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White
    )

    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
