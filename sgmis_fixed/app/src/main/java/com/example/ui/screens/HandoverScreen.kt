package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.example.R
import com.example.data.model.ShiftHandover
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoverScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.handover_title), fontWeight = FontWeight.Bold) },
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
            if (uiState.todayShift != null) {
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
                    text = { Text("Pending Acceptance") }
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
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = uiState.successMessage!!,
                        color = StatusSuccess,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            val currentUserId = uiState.currentUser?.id
            val pendingHandovers = uiState.handovers.filter {
                !it.incomingAccepted && (it.incomingGuard == currentUserId || currentUserId == null)
            }

            val displayList = if (selectedTab == 0) pendingHandovers else uiState.handovers

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
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayList) { handover ->
                        HandoverCard(
                            handover = handover,
                            currentUserId = currentUserId,
                            onAccept = { viewModel.acceptHandover(handover.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog && uiState.todayShift != null) {
        CreateHandoverDialog(
            shift = uiState.todayShift!!,
            onDismiss = { showCreateDialog = false },
            onSubmit = { occurrence, equipment, keys, pending ->
                viewModel.submitHandover(
                    outgoingShiftId = uiState.todayShift!!.id,
                    occurrence = occurrence,
                    equipment = equipment,
                    keys = keys,
                    pending = pending,
                    onSuccess = { showCreateDialog = false }
                )
            }
        )
    }
}

@Composable
fun HandoverCard(
    handover: ShiftHandover,
    currentUserId: String?,
    onAccept: () -> Unit
) {
    val isIncomingForMe = handover.incomingGuard == currentUserId
    val canAccept = !handover.incomingAccepted && isIncomingForMe

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
                    color = if (handover.incomingAccepted) StatusSuccess.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (handover.incomingAccepted) "ACCEPTED" else "PENDING ACCEPTANCE",
                        color = if (handover.incomingAccepted) StatusSuccess else StatusWarning,
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

            if (canAccept) {
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("accept_handover_button")
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Acknowledge & Accept Handover")
                }
            } else if (handover.incomingAccepted) {
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

@Composable
fun CreateHandoverDialog(
    shift: com.example.data.model.Shift,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String) -> Unit
) {
    var occurrence by remember { mutableStateOf("") }
    var equipment by remember { mutableStateOf("All issued equipment accounted for.") }
    var keys by remember { mutableStateOf("Station perimeter and barrier keys transferred.") }
    var pending by remember { mutableStateOf("None.") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Submit Shift Handover") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                    modifier = Modifier.fillMaxWidth().testTag("handover_occurrence_input")
                )

                OutlinedTextField(
                    value = equipment,
                    onValueChange = { equipment = it },
                    label = { Text("Equipment Handed Over") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    label = { Text("Keys Handed Over") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pending,
                    onValueChange = { pending = it },
                    label = { Text("Pending Issues") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (occurrence.isNotBlank()) {
                        onSubmit(occurrence, equipment, keys, pending)
                    }
                },
                enabled = occurrence.isNotBlank(),
                modifier = Modifier.testTag("submit_handover_confirm_button")
            ) {
                Text("Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
