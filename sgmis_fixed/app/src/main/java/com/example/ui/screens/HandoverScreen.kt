package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.*
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoverScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var handoverToReject by remember { mutableStateOf<ShiftHandover?>(null) }

    val currentUserRole = uiState.currentUser?.role
    val isSupervisor = currentUserRole == "SUPERVISOR"

    LaunchedEffect(Unit) {
        viewModel.fetchHandovers()
    }

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.handover_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("handover_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchHandovers() },
                        modifier = Modifier.testTag("refresh_handovers_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh Handovers")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (uiState.todayShift != null && !isSupervisor) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Default.Send, null) },
                    text = { Text("Submit Handover") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("submit_handover_fab")
                )
            }
        }
    ) { paddingValues ->
        val currentUserId = uiState.currentUser?.id
        val isSupervisorOrAdmin = currentUserRole in listOf("SUPERVISOR", "ADMIN")
        val pendingHandovers = uiState.handovers.filter {
            !it.incomingAccepted && !it.isHandoverRejected && (it.incomingGuard == currentUserId || isSupervisorOrAdmin || currentUserId == null)
        }
        val displayList = if (selectedTab == 0) pendingHandovers else uiState.handovers

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Pending Acceptance (${pendingHandovers.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("All Handovers (${uiState.handovers.size})") }
                )
            }

            // Notification banners
            if (uiState.successMessage != null) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.successMessage!!,
                            color = StatusSuccess,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (uiState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (uiState.handoversLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (displayList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.AssignmentTurnedIn, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (selectedTab == 0) "No pending handovers to accept" else "No handover records found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayList) { handover ->
                        HandoverCard(
                            handover = handover,
                            currentUserId = currentUserId,
                            currentUserRole = currentUserRole,
                            onAccept = { viewModel.acceptHandover(handover.id) },
                            onReject = { handoverToReject = handover }
                        )
                    }
                }
            }
        }
    }

    if (handoverToReject != null) {
        RejectHandoverDialog(
            handover = handoverToReject!!,
            isLoading = uiState.isLoading,
            onDismiss = { handoverToReject = null },
            onConfirm = { reason ->
                viewModel.rejectHandover(
                    handoverId = handoverToReject!!.id,
                    reason = reason,
                    onSuccess = {
                        handoverToReject = null
                    }
                )
            }
        )
    }

    if (showCreateDialog && uiState.todayShift != null) {
        CreateHandoverDialog(
            shift = uiState.todayShift!!,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showCreateDialog = false
            },
            onSubmit = { occurrence, equipment, keys, pending, override ->
                viewModel.submitHandover(
                    outgoingShiftId = uiState.todayShift!!.id,
                    occurrence = occurrence,
                    equipment = equipment,
                    keys = keys,
                    pending = pending,
                    emergencyOverride = override,
                    onSuccess = {
                        showCreateDialog = false
                    }
                )
            }
        )
    }
}

@Composable
fun HandoverCard(
    handover: ShiftHandover,
    currentUserId: String?,
    currentUserRole: String?,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val isIncomingForMe = handover.incomingGuard == currentUserId
    val isSupervisor = currentUserRole == "SUPERVISOR"
    val isPending = !handover.incomingAccepted && !handover.isHandoverRejected
    val canAction = isPending && isIncomingForMe && !isSupervisor

    Card(
        modifier = Modifier.fillMaxWidth().testTag("handover_item_${handover.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = handover.stationName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = when {
                        handover.incomingAccepted -> StatusSuccess.copy(alpha = 0.15f)
                        handover.isHandoverRejected -> MaterialTheme.colorScheme.errorContainer
                        else -> StatusWarning.copy(alpha = 0.15f)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = when {
                            handover.incomingAccepted -> "ACCEPTED"
                            handover.isHandoverRejected -> "REJECTED"
                            else -> "PENDING ACCEPTANCE"
                        },
                        color = when {
                            handover.incomingAccepted -> StatusSuccess
                            handover.isHandoverRejected -> MaterialTheme.colorScheme.error
                            else -> StatusWarning
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Outgoing: ${handover.outgoingGuardName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Incoming: ${handover.incomingGuardName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Text(
                text = "Occurrence Summary:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = handover.occurrenceSummary,
                style = MaterialTheme.typography.bodyMedium
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Equipment: ${handover.equipmentIssued}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Keys: ${handover.keysHandedOver}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (handover.pendingIssues.isNotBlank() && handover.pendingIssues != "None.") {
                Surface(
                    color = if (handover.isHandoverRejected) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (handover.isHandoverRejected) Icons.Default.Cancel else Icons.Default.Info,
                            null,
                            tint = if (handover.isHandoverRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = handover.pendingIssues,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (handover.isHandoverRejected) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (canAction) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAccept,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("accept_handover_button")
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Accept")
                    }

                    OutlinedButton(
                        onClick = onReject,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("reject_handover_button")
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reject", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else if (handover.incomingAccepted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Accepted at ${handover.incomingAcceptedAt ?: "on shift"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = StatusSuccess
                    )
                }
            }
        }
    }
}

@Composable
fun RejectHandoverDialog(
    handover: ShiftHandover,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Reject Shift Handover", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Rejecting handover for ${handover.stationName} (Outgoing: ${handover.outgoingGuardName}). This flags discrepancies for supervisor review.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason for Rejection / Issues") },
                    placeholder = { Text("e.g. Missing keys, equipment damage, post discrepancies") },
                    modifier = Modifier.fillMaxWidth().testTag("reject_reason_input"),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason.trim()) },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_reject_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                } else {
                    Text("Confirm Rejection")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CreateHandoverDialog(
    shift: com.example.data.model.Shift,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String, Boolean) -> Unit
) {
    var occurrence by remember { mutableStateOf("") }
    var equipment by remember { mutableStateOf("All issued equipment accounted for.") }
    var keys by remember { mutableStateOf("Station perimeter and barrier keys transferred.") }
    var pending by remember { mutableStateOf("None.") }
    var emergencyOverride by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Submit Shift Handover") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text(
                    text = "Handover for ${shift.stationName} (${shift.shiftType} shift). Incoming guard will be authoritatively resolved from the roster.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = occurrence,
                    onValueChange = { occurrence = it },
                    label = { Text("Occurrence Summary *") },
                    placeholder = { Text("Log all noteworthy post occurrences...") },
                    minLines = 3,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("handover_occurrence_input")
                )

                OutlinedTextField(
                    value = equipment,
                    onValueChange = { equipment = it },
                    label = { Text("Equipment Handed Over") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    label = { Text("Keys Handed Over") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pending,
                    onValueChange = { pending = it },
                    label = { Text("Pending Issues") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    color = if (emergencyOverride) StatusWarning.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Emergency Early Handover",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (emergencyOverride) StatusWarning else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Authorizes handover prior to standard 12-hour shift duration",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = emergencyOverride,
                            onCheckedChange = { emergencyOverride = it },
                            enabled = !isLoading,
                            modifier = Modifier.testTag("emergency_override_switch")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (occurrence.isNotBlank() && !isLoading) {
                        onSubmit(occurrence, equipment, keys, pending, emergencyOverride)
                    }
                },
                enabled = occurrence.isNotBlank() && !isLoading,
                modifier = Modifier.testTag("submit_handover_confirm_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Submitting...")
                } else {
                    Text("Submit")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}
