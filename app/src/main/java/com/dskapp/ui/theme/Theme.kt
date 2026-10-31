package com.dskapp.ui.theme

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

private val BrandBlue = Color(0xFF2563EB)
private val BrandBlueDark = Color(0xFF1E40AF)
private val BrandAmber = Color(0xFFD97706)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF475569),
    tertiary = BrandAmber,
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF44506B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93C5FD),
    onPrimary = Color(0xFF0B2A6B),
    primaryContainer = BrandBlueDark,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF94A3B8),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111A2B),
    surfaceVariant = Color(0xFF1C2740),
    onSurfaceVariant = Color(0xFFC7D2E5)
)

/**
 * App theme. Uses Material You dynamic colour on Android 12+ so the app matches the
 * phone/tablet wallpaper; falls back to the brand palette elsewhere.
 */
@Composable
fun DskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DskTypography,
        content = content
    )
}
