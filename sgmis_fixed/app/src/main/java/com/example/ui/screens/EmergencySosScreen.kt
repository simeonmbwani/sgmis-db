package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel

/**
 * Reconstructed Guard SOS Emergency Screen (Blueprint Pages 6 & 14).
 * Provides a high-contrast, deliberate emergency distress trigger with confirmation
 * using the authoritative backend emergency SOS endpoint (/incidents/sos/).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencySosScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val user = uiState.currentUser
    val scrollState = rememberScrollState()

    var showConfirmDialog by remember { mutableStateOf(false) }
    var sosSentNotice by remember { mutableStateOf(false) }

    val emergencyCategories = listOf(
        "Security Intrusion / Breach",
        "Medical Emergency",
        "Violence / Armed Threat",
        "Fire / Facility Hazard",
        "General Officer Distress"
    )
    var selectedCategory by remember { mutableStateOf(emergencyCategories[0]) }

    val isOffDutyGuard = uiState.isGuard && !uiState.isOnDuty

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isDispatchingSos) showConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    "CONFIRM EMERGENCY SOS",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        "Are you sure you want to broadcast an immediate high-priority distress alert to station supervisors and central dispatch?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(CornerRadius.sm),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(Spacing.sm)) {
                            Text(
                                text = "Emergency: $selectedCategory",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Location: ${uiState.currentStationName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val guardName = user?.fullName ?: user?.username ?: "Officer"
                        val empNo = user?.employeeNumber ?: "No ID"
                        val stnName = uiState.currentStationName
                        val emergencyDetails = "Distress beacon triggered by $guardName ($empNo) at $stnName. Category: $selectedCategory."
                        viewModel.triggerEmergencySos(
                            context = context,
                            category = selectedCategory,
                            emergencyDetails = emergencyDetails
                        ) {
                            showConfirmDialog = false
                            sosSentNotice = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !uiState.isDispatchingSos && !uiState.isLoading,
                    modifier = Modifier.testTag("confirm_sos_button").defaultMinSize(minHeight = 48.dp)
                ) {
                    if (uiState.isDispatchingSos || uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("ALERTING...", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
                    } else {
                        Text("DISPATCH SOS ALARM", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    enabled = !uiState.isDispatchingSos && !uiState.isLoading
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "Emergency SOS Beacon",
                subtitle = "Active Post: ${uiState.currentStationName}",
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("emergency_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // Off-Duty Notice
            if (isOffDutyGuard) {
                SgmisStatusCard(
                    status = SgmisCardStatus.WARNING,
                    title = "Duty Restricted",
                    message = "Viewing Mode: Active emergency distress beacon requires an active, clocked-in operational shift.",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Error banner if submit fails
            if (uiState.errorMessage != null) {
                SgmisStatusCard(
                    status = SgmisCardStatus.ERROR,
                    title = "Dispatch Error",
                    message = uiState.errorMessage!!,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // SOS Status Banner
            if (sosSentNotice || uiState.sosAlertMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("sos_active_banner"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(CornerRadius.md)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Column {
                            Text(
                                text = "DISTRESS BEACON DISPATCHED",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = uiState.sosAlertMessage
                                    ?: "High-priority distress alert transmitted for ${user?.fullName ?: user?.username} at ${uiState.currentStationName}. Station supervisors alerted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Emergency Type Selector
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(CornerRadius.md),
                elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card)
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = "SELECT EMERGENCY TYPE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    emergencyCategories.forEach { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(CornerRadius.sm))
                                .clickable { selectedCategory = category }
                                .padding(vertical = Spacing.xxs, horizontal = Spacing.xxs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error)
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text(
                                text = category,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selectedCategory == category) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Tactical SOS Panic Emblem Button
            Spacer(modifier = Modifier.height(Spacing.xs))
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOffDutyGuard) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOffDutyGuard) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.error
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (isOffDutyGuard) {
                                viewModel.postSecurityAlert("Duty Lock: You must be CLOCKED IN (On Duty) to dispatch emergency alerts.")
                            } else {
                                showConfirmDialog = true
                            }
                        },
                        modifier = Modifier.size(120.dp).testTag("panic_sos_button"),
                        enabled = !uiState.isDispatchingSos
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (uiState.isDispatchingSos) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "ALERTING",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                            } else {
                                Icon(
                                    imageVector = if (isOffDutyGuard) Icons.Default.Lock else Icons.Default.Sos,
                                    contentDescription = "SOS Panic Trigger",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = if (isOffDutyGuard) "LOCKED" else "PRESS SOS",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = if (isOffDutyGuard) "Emergency trigger locked while off duty." else "Tap the emergency button above and confirm to dispatch an instant distress beacon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )

            // Station & Officer Telemetry Card
            Card(
                modifier = Modifier.fillMaxWidth().testTag("sos_telemetry_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(CornerRadius.md),
                elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card)
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = "Officer Context for Dispatch",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SosInfoRow("Officer Name", user?.fullName ?: user?.username ?: "—")
                    SosInfoRow("Employee ID", user?.employeeNumber ?: "—")
                    SosInfoRow("Station Post", uiState.currentStationName)
                    SosInfoRow("Duty Status", if (uiState.isOnDuty) "ON DUTY (Clocked In)" else "OFF DUTY")
                    SosInfoRow("Channel Protocol", "Priority 1 - Direct Command Alert")
                }
            }
        }
    }
}

@Composable
private fun SosInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
