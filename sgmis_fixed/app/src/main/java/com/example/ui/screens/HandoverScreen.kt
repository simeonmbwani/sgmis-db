package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import com.example.util.NotificationHelper
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoverScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var handoverToAccept by remember { mutableStateOf<ShiftHandover?>(null) }
    var handoverToReject by remember { mutableStateOf<ShiftHandover?>(null) }

    val currentUserRole = uiState.currentUser?.role?.uppercase()
    val isSupervisor = currentUserRole == "SUPERVISOR"
    val currentUserId = uiState.currentUser?.id
    val isSupervisorOrAdmin = currentUserRole in listOf("SUPERVISOR", "ADMIN", "ADMINISTRATOR")

    val pendingForMe = remember(uiState.handovers, currentUserId) {
        uiState.handovers.filter {
            !it.incomingAccepted && !it.isHandoverRejected && it.incomingGuard == currentUserId
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fetchHandovers()
    }

    // Trigger operational notification when pending handover awaits incoming guard's physical verification
    LaunchedEffect(pendingForMe.size) {
        if (uiState.isGuard && pendingForMe.isNotEmpty()) {
            NotificationHelper.triggerOperationalNotification(
                context,
                "Pending Shift Takeover",
                "You have ${pendingForMe.size} pending shift handover(s) requiring your physical verification and acceptance."
            )
        }
    }

    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = stringResource(R.string.handover_title),
                subtitle = "Active Station: ${uiState.currentStationName}",
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
                }
            )
        },
        floatingActionButton = {
            if (uiState.todayShift != null && !isSupervisor && (!uiState.isGuard || uiState.isOnDuty)) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Default.Send, null) },
                    text = { Text("Submit Handover", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("submit_handover_fab")
                )
            }
        }
    ) { paddingValues ->
        val pendingHandovers = uiState.handovers.filter {
            !it.incomingAccepted && !it.isHandoverRejected && (it.incomingGuard == currentUserId || isSupervisorOrAdmin || currentUserId == null)
        }
        val displayList = if (selectedTab == 0) pendingHandovers else uiState.handovers

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Off-duty guard notice
            if (uiState.isGuard && !uiState.isOnDuty) {
                SgmisStatusCard(
                    status = SgmisCardStatus.WARNING,
                    title = "Duty Restricted",
                    message = "Viewing mode: Submitting shift handovers requires an active clocked-in duty shift.",
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )
            }

            // Notification banners
            if (uiState.successMessage != null) {
                SgmisStatusCard(
                    status = SgmisCardStatus.SUCCESS,
                    title = "Success",
                    message = uiState.successMessage!!,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )
            }

            if (uiState.errorMessage != null) {
                SgmisStatusCard(
                    status = SgmisCardStatus.ERROR,
                    title = "Error",
                    message = uiState.errorMessage!!,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )
            }

            // Tabs for Pending vs All
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text("Pending Acceptance", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                            if (pendingHandovers.isNotEmpty()) {
                                SgmisBadge(text = "${pendingHandovers.size}", variant = SgmisBadgeVariant.WARNING)
                            }
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text("All Handovers", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                            Text("(${uiState.handovers.size})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                )
            }

            if (uiState.handoversLoading) {
                SgmisLoadingSkeleton(modifier = Modifier.padding(Spacing.md))
            } else if (displayList.isEmpty()) {
                SgmisEmptyState(
                    icon = Icons.Outlined.AssignmentTurnedIn,
                    title = if (selectedTab == 0) "No Pending Handovers" else "No Handover Records",
                    description = if (selectedTab == 0) "All incoming handovers have been accepted." else "Shift logs and transfer documents will appear here.",
                    actionLabel = if (uiState.todayShift != null && !isSupervisor && (!uiState.isGuard || uiState.isOnDuty)) "Create Handover" else null,
                    onAction = { showCreateDialog = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
                    contentPadding = PaddingValues(top = Spacing.sm, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(displayList) { handover ->
                        HandoverCard(
                            handover = handover,
                            currentUserId = currentUserId,
                            currentUserRole = currentUserRole,
                            onAccept = { handoverToAccept = handover },
                            onReject = { handoverToReject = handover }
                        )
                    }
                }
            }
        }
    }

    if (handoverToAccept != null) {
        AcceptHandoverDialog(
            handover = handoverToAccept!!,
            isLoading = uiState.isLoading,
            onDismiss = { handoverToAccept = null },
            onConfirm = {
                viewModel.acceptHandover(
                    handoverId = handoverToAccept!!.id,
                    onSuccess = {
                        handoverToAccept = null
                    }
                )
            }
        )
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
        modifier = Modifier
            .fillMaxWidth()
            .testTag("handover_item_${handover.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(CornerRadius.md),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
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
                SgmisBadge(
                    text = when {
                        handover.incomingAccepted -> "ACCEPTED"
                        handover.isHandoverRejected -> "REJECTED"
                        else -> "PENDING ACCEPTANCE"
                    },
                    variant = when {
                        handover.incomingAccepted -> SgmisBadgeVariant.SUCCESS
                        handover.isHandoverRejected -> SgmisBadgeVariant.ERROR
                        else -> SgmisBadgeVariant.WARNING
                    }
                )
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
                    shape = RoundedCornerShape(CornerRadius.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (handover.isHandoverRejected) Icons.Default.Cancel else Icons.Default.Info,
                            null,
                            tint = if (handover.isHandoverRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Button(
                        onClick = onAccept,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(CornerRadius.sm),
                        modifier = Modifier.weight(1f).testTag("accept_handover_button").defaultMinSize(minHeight = 44.dp)
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Accept")
                    }

                    OutlinedButton(
                        onClick = onReject,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(CornerRadius.sm),
                        modifier = Modifier.weight(1f).testTag("reject_handover_button").defaultMinSize(minHeight = 44.dp)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Reject", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else if (handover.incomingAccepted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(Spacing.xs))
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
fun AcceptHandoverDialog(
    handover: ShiftHandover,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var confirmedVerification by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Verify & Accept Shift Takeover",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(CornerRadius.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(Spacing.xs), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Station:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(handover.stationName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Outgoing Officer:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(handover.outgoingGuardName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Incoming Officer:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(handover.incomingGuardName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Text(
                    text = "Shift Occurrence Summary:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(CornerRadius.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = handover.occurrenceSummary.ifBlank { "No occurrences logged." },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(Spacing.xs)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Equipment Issued:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(handover.equipmentIssued, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keys Handed Over:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(handover.keysHandedOver, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (handover.pendingIssues.isNotBlank() && handover.pendingIssues != "None.") {
                    Surface(
                        color = StatusWarning.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(CornerRadius.sm),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = StatusWarning, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Column {
                                Text("Pending Handover Discrepancies:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = StatusWarning)
                                Text(handover.pendingIssues, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(CornerRadius.sm))
                        .clickable { confirmedVerification = !confirmedVerification }
                        .padding(vertical = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = confirmedVerification,
                        onCheckedChange = { confirmedVerification = it },
                        modifier = Modifier.testTag("handover_verification_checkbox")
                    )
                    Spacer(modifier = Modifier.width(Spacing.xxs))
                    Text(
                        text = "I physically verify and confirm receipt of all post equipment, keys, and security status from ${handover.outgoingGuardName}.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmedVerification && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("confirm_accept_handover_button").defaultMinSize(minHeight = 44.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("Verifying...")
                } else {
                    Text("Confirm & Accept")
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
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
                modifier = Modifier.testTag("confirm_reject_button").defaultMinSize(minHeight = 44.dp)
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
        title = { Text("Submit Shift Handover", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (errorMessage != null) {
                    SgmisStatusCard(
                        status = SgmisCardStatus.ERROR,
                        title = "Handover Error",
                        message = errorMessage,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    shape = RoundedCornerShape(CornerRadius.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = Spacing.xs)) {
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
                modifier = Modifier.testTag("submit_handover_confirm_button").defaultMinSize(minHeight = 44.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
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
