package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = GoldAccent,
    onPrimary = NavyDark,
    primaryContainer = GoldContainer,
    onPrimaryContainer = GoldLight,
    secondary = GoldAccent,
    onSecondary = NavyDark,
    secondaryContainer = NavyContainer,
    onSecondaryContainer = Color.White,
    tertiary = GoldMuted,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFD1D8E0),
    outline = Color(0xFF334B68),
    error = StatusError,
    onError = Color.White
  )

private val LightColorScheme =
  lightColorScheme(
    primary = NavyDark,
    onPrimary = Color.White,
    primaryContainer = NavyContainer,
    onPrimaryContainer = Color.White,
    secondary = GoldMuted,
    onSecondary = Color.White,
    secondaryContainer = GoldLight,
    onSecondaryContainer = NavyDark,
    tertiary = GoldAccent,
    onTertiary = NavyDark,
    background = LightBackground,
    onBackground = NavyDark,
    surface = LightSurface,
    onSurface = NavyDark,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = NavyDark,
    outline = Color(0xFFB0B9C6),
    error = StatusError,
    onError = Color.White
  )

enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

@Composable
fun SmartSecurityTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    SmartSecurityTheme(
        themeMode = if (darkTheme) ThemeMode.DARK else ThemeMode.LIGHT,
        content = content
    )
}
