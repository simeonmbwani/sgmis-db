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

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Default to command center dark navy theme
  dynamicColor: Boolean = false, // Keep distinct security brand identity
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
