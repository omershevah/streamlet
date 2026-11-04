package dev.omershevach.streamlet.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback for Android 11 and older; newer devices use the wallpaper colors (Material You).
private val Light = lightColorScheme(
    primary = Color(0xFF3A5BA9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001848),
    secondaryContainer = Color(0xFFDDE2F9),
    onSecondaryContainer = Color(0xFF151B2C),
    tertiary = Color(0xFF8B4F72),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF052C73),
    primaryContainer = Color(0xFF20438F),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondaryContainer = Color(0xFF3E4659),
    onSecondaryContainer = Color(0xFFDDE2F9),
    tertiary = Color(0xFFFFAFD8),
)

@Composable
fun StreamletTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
