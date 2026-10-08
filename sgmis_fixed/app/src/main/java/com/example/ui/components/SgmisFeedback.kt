package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Standardized empty state card guiding users on what to do next.
 */
@Composable
fun SgmisEmptyState(
    title: String = "",
    message: String = "",
    icon: ImageVector = Icons.Default.Inbox,
    actionLabel: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    val effectiveMsg = description ?: message
    val effectiveActionLabel = actionText ?: actionLabel
    val effectiveOnAction = onActionClick ?: onAction

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(BorderSubtleLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondaryLight,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (title.isNotBlank()) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight,
                textAlign = TextAlign.Center
            )
        }

        if (effectiveMsg.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = effectiveMsg,
                fontSize = 14.sp,
                color = TextSecondaryLight,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }

        if (!effectiveActionLabel.isNullOrBlank() && effectiveOnAction != null) {
            Spacer(modifier = Modifier.height(18.dp))
            SgmisPrimaryButton(
                text = effectiveActionLabel,
                onClick = effectiveOnAction,
                modifier = Modifier.widthIn(min = 160.dp, max = 240.dp)
            )
        }
    }
}

/**
 * Actionable Error Card answering:
 * 1. What happened?
 * 2. Why did it happen?
 * 3. What can I do now?
 */
@Composable
fun SgmisErrorState(
    whatHappened: String = "",
    whyItHappened: String? = null,
    recoveryActionLabel: String? = null,
    onRecoveryAction: (() -> Unit)? = null,
    title: String? = null,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val effectiveTitle = title ?: whatHappened
    val effectiveMessage = message ?: whyItHappened
    val effectiveActionLabel = actionLabel ?: recoveryActionLabel ?: "Try Again"
    val effectiveOnAction = onAction ?: onRecoveryAction

    SgmisStatusCard(
        statusColor = StatusError,
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(StatusError.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = StatusError,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (effectiveTitle.isNotBlank()) {
                    Text(
                        text = effectiveTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )
                }

                if (!effectiveMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = effectiveMessage,
                        fontSize = 13.sp,
                        color = TextSecondaryLight,
                        lineHeight = 18.sp
                    )
                }

                if (effectiveActionLabel.isNotBlank() && effectiveOnAction != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SgmisPrimaryButton(
                        text = effectiveActionLabel,
                        onClick = effectiveOnAction,
                        modifier = Modifier.widthIn(min = 120.dp)
                    )
                }
            }
        }
    }
}

/**
 * Positive feedback card confirming task completion.
 */
@Composable
fun SgmisSuccessState(
    title: String,
    message: String? = null,
    actionLabel: String? = "Continue",
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    SgmisStatusCard(
        statusColor = StatusSuccess,
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(StatusSuccess.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = StatusSuccess,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryLight
                )

                if (!message.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message,
                        fontSize = 13.sp,
                        color = TextSecondaryLight
                    )
                }

                if (!actionLabel.isNullOrBlank() && onAction != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    SgmisGhostButton(
                        text = actionLabel,
                        onClick = onAction
                    )
                }
            }
        }
    }
}

/**
 * Shimmer skeleton loading placeholder simulating cards or list items.
 */
@Composable
fun SgmisLoadingSkeleton(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 72.dp
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )

    val shimmerColors = listOf(
        Color(0xFFE2E8F0),
        Color(0xFFF1F5F9),
        Color(0xFFE2E8F0)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(brush)
    )
}

/**
 * Standard confirmation dialog for critical or destructive actions.
 */
@Composable
fun SgmisConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )
        },
        text = {
            Text(
                text = message,
                fontSize = 14.sp,
                color = TextSecondaryLight,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            if (isDestructive) {
                Button(
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text(text = confirmLabel, color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NavyDark)
                ) {
                    Text(text = confirmLabel, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = cancelLabel, color = TextSecondaryLight, fontWeight = FontWeight.SemiBold)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = LightSurface
    )
}

/**
 * Standard Modal Bottom Sheet wrapper for contextual tasks (e.g. shift reassignments, relief selection).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SgmisModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = LightSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = BorderStrongLight) },
        content = content
    )
}
