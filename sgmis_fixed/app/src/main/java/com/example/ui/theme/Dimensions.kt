package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standardized spacing tokens for consistent layout rhythm across SGMIS.
 * Follows an 8pt/4pt sub-grid system.
 */
data class SgmisSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val section: Dp = 40.dp
)

/**
 * Standardized corner radii for surfaces, buttons, chips, and modals.
 */
data class SgmisRadius(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 28.dp,
    val pill: Dp = 999.dp
)

/**
 * Touch target requirements ensuring outdoor operational accessibility.
 * Controls must never drop below min touch target (48dp).
 */
data class SgmisTouchTarget(
    val min: Dp = 48.dp,
    val comfortable: Dp = 56.dp,
    val large: Dp = 64.dp
)

/**
 * Surface elevations for visual depth without excessive borders.
 */
data class SgmisElevation(
    val none: Dp = 0.dp,
    val subtle: Dp = 1.dp,
    val low: Dp = 2.dp,
    val medium: Dp = 4.dp,
    val high: Dp = 8.dp
)

val LocalSgmisSpacing = staticCompositionLocalOf { SgmisSpacing() }
val LocalSgmisRadius = staticCompositionLocalOf { SgmisRadius() }
val LocalSgmisTouchTarget = staticCompositionLocalOf { SgmisTouchTarget() }
val LocalSgmisElevation = staticCompositionLocalOf { SgmisElevation() }

object SgmisThemeTokens {
    val spacing: SgmisSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSgmisSpacing.current

    val radius: SgmisRadius
        @Composable
        @ReadOnlyComposable
        get() = LocalSgmisRadius.current

    val touchTarget: SgmisTouchTarget
        @Composable
        @ReadOnlyComposable
        get() = LocalSgmisTouchTarget.current

    val elevation: SgmisElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalSgmisElevation.current
}

object Spacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 40.dp
}

object CornerRadius {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val pill: Dp = 999.dp
}

object Elevation {
    val none: Dp = 0.dp
    val card: Dp = 1.dp
    val subtle: Dp = 1.dp
    val low: Dp = 2.dp
    val medium: Dp = 4.dp
    val high: Dp = 8.dp
}
