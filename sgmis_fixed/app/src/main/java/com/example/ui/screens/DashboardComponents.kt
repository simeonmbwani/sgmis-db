package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DirectMessage
import com.example.data.model.Shift
import com.example.ui.components.SgmisCard
import com.example.ui.components.SgmisStatusCard
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisUiState
import com.example.ui.viewmodel.SgmisViewModel
import java.util.Calendar

data class BlueprintAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    val testTag: String,
    val isLocked: Boolean = false,
    val isEmergency: Boolean = false
)

/**
 * Tactical Action Card for dashboard operational grids.
 * High-contrast, outdoor-readable with explicit lock and emergency indicators.
 */
@Composable
fun BlueprintActionCard(
    action: BlueprintAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerBg = when {
        action.isLocked -> LightSurface.copy(alpha = 0.7f)
        action.isEmergency -> StatusError.copy(alpha = 0.08f)
        else -> LightSurface
    }

    val iconBg = when {
        action.isLocked -> BorderSubtleLight
        action.isEmergency -> StatusError.copy(alpha = 0.15f)
        else -> NavyDark.copy(alpha = 0.08f)
    }

    val iconColor = when {
        action.isLocked -> TextSecondaryLight
        action.isEmergency -> StatusError
        else -> NavyDark
    }

    val borderColor = when {
        action.isEmergency && !action.isLocked -> StatusError.copy(alpha = 0.4f)
        action.isLocked -> BorderSubtleLight.copy(alpha = 0.6f)
        else -> BorderSubtleLight
    }

    Card(
        modifier = modifier
            .defaultMinSize(minHeight = 112.dp)
            .clickable(onClick = onClick)
            .testTag(action.testTag),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (action.isLocked) 0.dp else 1.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (action.isLocked) Icons.Default.Lock else action.icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (action.isLocked) {
                    Surface(
                        color = BorderSubtleLight,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "LOCKED",
                            color = TextSecondaryLight,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (action.isEmergency) {
                    Surface(
                        color = StatusError,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "ALERT",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column {
                Text(
                    text = action.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (action.isLocked) TextSecondaryLight else TextPrimaryLight,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (action.isLocked) "Requires active clock-in" else action.subtitle,
                    fontSize = 11.sp,
                    color = TextSecondaryLight,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Reusable Metric Tile for Supervisor and Administrator command consoles.
 * Communicates why the metric matters with clear label, high-contrast value, and operational status color.
 */
@Composable
fun DashboardMetricTile(
    label: String,
    value: String,
    caption: String? = null,
    icon: ImageVector? = null,
    statusColor: Color = NavyDark,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = BorderStroke(1.dp, BorderSubtleLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = TextSecondaryLight
                )
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = statusColor
            )

            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    fontSize = 11.sp,
                    color = TextSecondaryLight,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Direct messaging dialog between officers, supervisors, and administrators.
 */
@Composable
fun DirectMessagesDialog(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState,
    onDismiss: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val currentUserId = uiState.currentUser?.id

    val supervisor = remember(uiState.users, uiState.currentStationId) {
        uiState.users.find { it.role == "SUPERVISOR" && it.station == uiState.currentStationId }
            ?: uiState.users.find { it.role == "SUPERVISOR" }
    }
    val supervisorId = supervisor?.id
    val supervisorName = supervisor?.fullName ?: supervisor?.username ?: "Station Supervisor"

    val admin = remember(uiState.users) {
        uiState.users.find { it.role == "ADMINISTRATOR" || it.role == "ADMIN" }
    }
    val adminId = admin?.id
    val adminName = admin?.fullName ?: admin?.username ?: "Administrator"

    val stationGuards = remember(uiState.users, currentUserId) {
        uiState.users.filter { it.role == "GUARD" && it.id != currentUserId }
    }

    var selectedRecipientId by remember(supervisorId, adminId) {
        mutableStateOf(supervisorId ?: adminId ?: stationGuards.firstOrNull()?.id ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Chat, contentDescription = null, tint = NavyDark)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Operational Communications", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Official station messaging channel. All dispatches are archived for operational compliance.",
                    fontSize = 12.sp,
                    color = TextSecondaryLight
                )

                // Message Thread
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    color = LightBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderSubtleLight)
                ) {
                    if (uiState.directMessages.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No messages in current thread.", fontSize = 12.sp, color = TextSecondaryLight)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(uiState.directMessages) { msg ->
                                val isMe = msg.sender == currentUserId
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                                ) {
                                    Surface(
                                        color = if (isMe) NavyDark else LightSurface,
                                        shape = RoundedCornerShape(10.dp),
                                        border = if (isMe) null else BorderStroke(1.dp, BorderSubtleLight)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = if (isMe) "You" else (msg.senderName ?: "Officer"),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isMe) GoldAccent else NavyDark
                                            )
                                            Text(
                                                text = msg.content,
                                                fontSize = 12.sp,
                                                color = if (isMe) Color.White else TextPrimaryLight
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Input Field
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text("Type official message...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (messageText.isNotBlank() && selectedRecipientId.isNotBlank()) {
                        viewModel.sendDirectMessage(selectedRecipientId, messageText)
                        messageText = ""
                    }
                },
                enabled = messageText.isNotBlank() && selectedRecipientId.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NavyDark)
            ) {
                Text("Send Dispatch")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondaryLight)
            }
        }
    )
}

fun computeSupervisorShiftBadge(serverShift: Shift?): String {
    val isServerShiftAuthoritative = serverShift != null && !serverShift.startTime.isNullOrBlank() && !serverShift.endTime.isNullOrBlank()
    return if (isServerShiftAuthoritative) {
        val sType = serverShift!!.shiftType.uppercase()
        val shiftTitle = if (sType.contains("SHIFT")) sType else "$sType SHIFT"
        val hours = "${serverShift.startTime.take(5)}–${serverShift.endTime.take(5)}"
        "$shiftTitle ($hours)"
    } else {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isDay = currentHour in 7..17
        val shiftTitle = if (isDay) "DAY SHIFT" else "NIGHT SHIFT"
        val hours = if (isDay) "07:00–18:00" else "18:00–07:00"
        "$shiftTitle ($hours • DISPLAY ONLY)"
    }
}

fun computeSupervisorGeofenceText(hasStation: Boolean, radius: Double?): String {
    return when {
        !hasStation -> "Geofence Disabled (No Station)"
        radius != null && radius > 0 -> "Operational Geofence: ${radius.toInt()}m Active"
        else -> "Operational Geofence: Unavailable"
    }
}
