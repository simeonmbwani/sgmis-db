package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

enum class SgmisCardVariant {
    OUTLINED,
    ELEVATED,
    FLAT
}

/**
 * Standardized SGMIS card with clean visual hierarchy that avoids "box-in-box" border fatigue.
 */
@Composable
fun SgmisCard(
    modifier: Modifier = Modifier,
    variant: SgmisCardVariant = SgmisCardVariant.OUTLINED,
    backgroundColor: Color = LightSurface,
    borderColor: Color = BorderSubtleLight,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val cardModifier = modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)

    when (variant) {
        SgmisCardVariant.OUTLINED -> {
            Card(
                modifier = cardModifier,
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = backgroundColor),
                border = BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(contentPadding),
                    content = content
                )
            }
        }
        SgmisCardVariant.ELEVATED -> {
            Card(
                modifier = cardModifier,
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = backgroundColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(contentPadding),
                    content = content
                )
            }
        }
        SgmisCardVariant.FLAT -> {
            Surface(
                modifier = cardModifier,
                shape = shape,
                color = backgroundColor
            ) {
                Column(
                    modifier = Modifier.padding(contentPadding),
                    content = content
                )
            }
        }
    }
}

/**
 * Tactical status card featuring an authoritative left-edge colored indicator strip.
 * Communicates status cleanly without flooding the entire card surface with loud colors.
 */
@Composable
fun SgmisStatusCard(
    statusColor: Color,
    modifier: Modifier = Modifier,
    backgroundColor: Color = LightSurface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, BorderSubtleLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().intrinsicHeight()) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(statusColor)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                content = content
            )
        }
    }
}

enum class SgmisCardStatus {
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
    NEUTRAL
}

typealias CardStatus = SgmisCardStatus

@Composable
fun SgmisStatusCard(
    status: SgmisCardStatus,
    title: String,
    message: String = "",
    description: String = message,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    val color = when (status) {
        SgmisCardStatus.SUCCESS -> StatusSuccess
        SgmisCardStatus.WARNING -> StatusWarning
        SgmisCardStatus.ERROR -> StatusError
        SgmisCardStatus.INFO -> StatusInfo
        SgmisCardStatus.NEUTRAL -> StatusNeutral
    }
    val effectiveMsg = if (message.isNotBlank()) message else description
    SgmisStatusCard(
        statusColor = color,
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
        }
        if (effectiveMsg.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(effectiveMsg, style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(6.dp))
            action()
        }
    }
}

private fun Modifier.intrinsicHeight(): Modifier = this.height(IntrinsicSize.Min)

/**
 * Metric KPI tile for dashboards with high-contrast typography.
 */
@Composable
fun SgmisStatTile(
    title: String,
    value: String,
    icon: ImageVector,
    iconTint: Color = NavyDark,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeColor: Color = StatusSuccess,
    tint: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val effectiveTint = tint ?: iconTint
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = BorderStroke(1.dp, BorderSubtleLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(effectiveTint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = effectiveTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (!badgeText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondaryLight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
