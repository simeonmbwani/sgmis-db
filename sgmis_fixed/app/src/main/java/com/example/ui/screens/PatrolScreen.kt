package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
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
import com.example.data.model.Checkpoint
import com.example.data.model.PatrolLog
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatrolScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val activePatrol = uiState.activePatrol
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showFinishConfirmDialog by remember { mutableStateOf(false) }
    var showAssignPatrolDialog by remember { mutableStateOf(false) }
    var patrolToReject by remember { mutableStateOf<PatrolLog?>(null) }
    var rejectReasonText by remember { mutableStateOf("") }

    val currentUser = uiState.currentUser
    val isSupervisor = currentUser?.role == "SUPERVISOR"
    val isAdmin = currentUser?.appRole == com.example.data.model.AppRole.ADMINISTRATOR
    val isGuard = currentUser?.role == "GUARD" || currentUser?.role == null

    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var scannedCheckpointIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(activePatrol?.id) {
        elapsedSeconds = 0
        if (activePatrol != null) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                elapsedSeconds++
            }
        } else {
            scannedCheckpointIds = emptySet()
        }
    }

    val elapsedMinutes = elapsedSeconds / 60
    val elapsedSecs = elapsedSeconds % 60
    val formattedElapsedTime = String.format(java.util.Locale.US, "%02d:%02d", elapsedMinutes, elapsedSecs)

    val orderedCheckpoints = remember(uiState.checkpoints) {
        uiState.checkpoints.sortedBy { it.order }
    }
    val totalCheckpoints = orderedCheckpoints.size
    val completedScans = remember(scannedCheckpointIds, activePatrol?.scansCount) {
        maxOf(scannedCheckpointIds.size, activePatrol?.scansCount ?: 0)
    }
    val remainingCheckpoints = maxOf(0, totalCheckpoints - completedScans)

    // Route sequence: find next unscanned checkpoint in strict order
    val nextRequiredCheckpoint = remember(orderedCheckpoints, scannedCheckpointIds) {
        orderedCheckpoints.firstOrNull { !scannedCheckpointIds.contains(it.id) }
    }

    LaunchedEffect(Unit) {
        viewModel.fetchCheckpoints()
        viewModel.fetchPatrolLogs()
    }

    // Auto-dismiss transient messages
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.patrols_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("patrol_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (isSupervisor) {
                        IconButton(
                            onClick = { showAssignPatrolDialog = true },
                            modifier = Modifier.testTag("assign_patrol_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Assign Patrol")
                        }
                    }
                    IconButton(
                        onClick = {
                            viewModel.fetchCheckpoints()
                            viewModel.fetchPatrolLogs()
                        },
                        modifier = Modifier.testTag("refresh_patrols_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Patrols")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Notification Banners
            if (uiState.successMessage != null) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
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

            // Offline Sync Banner
            if (uiState.unsyncedPatrolEventsCount > 0) {
                Surface(
                    color = StatusWarning.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudQueue, null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${uiState.unsyncedPatrolEventsCount} offline verification scan(s) queued locally.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (activePatrol != null) {
                            Button(
                                onClick = { viewModel.syncOfflinePatrolEvents(activePatrol.id) },
                                enabled = !uiState.isSyncingPatrolEvents,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                if (uiState.isSyncingPatrolEvents) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Text("Sync Now", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // ACTIVE PATROL CARD
            if (activePatrol != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("active_patrol_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = activePatrol.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Station: ${activePatrol.stationName} • Guard: ${activePatrol.guardName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Surface(color = StatusSuccess, shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    text = "IN PROGRESS",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Elapsed Timer & Inspection Progress
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Active Inspection Time:", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(
                                        text = formattedElapsedTime,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Progress:", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        text = "$completedScans of $totalCheckpoints Verified",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (completedScans == totalCheckpoints && totalCheckpoints > 0) StatusSuccess else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Checkpoint Route Progression Visualizer
                        if (orderedCheckpoints.isNotEmpty()) {
                            Text(
                                text = "Inspection Route Sequence (1 → 2 → 3):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                orderedCheckpoints.forEachIndexed { index, cp ->
                                    val isScanned = scannedCheckpointIds.contains(cp.id)
                                    val isNext = nextRequiredCheckpoint?.id == cp.id
                                    Surface(
                                        color = if (isScanned) StatusSuccess
                                        else if (isNext) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${cp.order}. ${cp.name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isScanned || isNext) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (isScanned) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                    if (index < orderedCheckpoints.size - 1) {
                                        Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }

                        if (isGuard) {
                            Button(
                                onClick = {
                                    if (elapsedSeconds < 60) {
                                        viewModel.postSecurityAlert("Minimum Patrol Duration: Physical inspection requires at least 60 seconds elapsed before submission (${60 - elapsedSeconds}s remaining).")
                                    } else if (totalCheckpoints > 0 && remainingCheckpoints > 0) {
                                        viewModel.postSecurityAlert("Checkpoint Scan Required: All $totalCheckpoints station checkpoints must be verified ($remainingCheckpoints remaining).")
                                    } else {
                                        showFinishConfirmDialog = true
                                    }
                                },
                                enabled = !uiState.isLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().testTag("finish_patrol_button")
                            ) {
                                if (uiState.isLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Submitting...")
                                } else {
                                    Text("Complete & Submit Patrol Log")
                                }
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Supervisors have view-only monitoring. Only assigned on-duty guards can execute patrols.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            } else if (isGuard) {
                // GUARD VIEW: ASSIGNED PATROLS LIST
                val myAssignedPatrols = uiState.assignedPatrols.filter {
                    it.guard == currentUser?.id || it.guardName == currentUser?.fullName || it.station == currentUser?.station
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ASSIGNED PATROLS (${myAssignedPatrols.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (!uiState.isOnDuty) {
                                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(4.dp)) {
                                    Text(
                                        text = "OFF DUTY",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        if (!uiState.isOnDuty) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("You must be CLOCKED IN (On Duty) to execute assigned patrols.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        if (myAssignedPatrols.isEmpty()) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "No Assigned Patrols Pending",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Patrol rounds must be scheduled and assigned by your station supervisor. Guards cannot self-initiate unassigned patrols.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            myAssignedPatrols.forEach { assigned ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(text = assigned.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "Assigned by: ${assigned.assignedByName ?: "Supervisor"} • Post: ${assigned.stationName}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (!assigned.deadline.isNullOrBlank()) {
                                            Text(
                                                text = "Deadline: ${assigned.deadline.take(19).replace('T', ' ')}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        if (!assigned.notes.isNullOrBlank()) {
                                            Text(text = "Instructions: ${assigned.notes}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(
                                            onClick = { viewModel.startAssignedPatrol(assigned.id) },
                                            enabled = !uiState.isLoading && uiState.isOnDuty,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.fillMaxWidth().testTag("start_assigned_patrol_${assigned.id}")
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Start Assigned Patrol")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // SUPERVISOR / ADMIN VIEW
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SUPERVISOR PATROL CONTROLS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Button(
                                onClick = { showAssignPatrolDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Assign Patrol")
                            }
                        }

                        val completedAwaitingApproval = uiState.patrolLogs.filter { it.status == "COMPLETED" && !it.isApproved }
                        if (completedAwaitingApproval.isNotEmpty()) {
                            Text(
                                text = "Completed Patrols Pending Review (${completedAwaitingApproval.size}):",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            completedAwaitingApproval.forEach { patrol ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(patrol.name, fontWeight = FontWeight.Bold)
                                            if (patrol.anomaliesCount > 0) {
                                                Surface(color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(4.dp)) {
                                                    Text(
                                                        text = "${patrol.anomaliesCount} ANOMALIES",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            } else {
                                                Surface(color = StatusSuccess, shape = RoundedCornerShape(4.dp)) {
                                                    Text(
                                                        text = "CLEAN AUDIT",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "Guard: ${patrol.guardName} • Station: ${patrol.stationName} • Scans: ${patrol.scansCount}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { viewModel.approvePatrol(patrol.id) },
                                                colors = ButtonDefaults.buttonColors(containerColor = StatusSuccess),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Approve")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    patrolToReject = patrol
                                                    rejectReasonText = ""
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Reject", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Station Checkpoints Header
            Text(
                text = "Station Inspection Checkpoints (${orderedCheckpoints.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (orderedCheckpoints.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No inspection checkpoints registered for this station.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(orderedCheckpoints) { cp ->
                        val isScanned = scannedCheckpointIds.contains(cp.id)
                        val isNext = nextRequiredCheckpoint?.id == cp.id
                        CheckpointItemCard(
                            checkpoint = cp,
                            patrolActive = activePatrol != null && !uiState.isLoading,
                            isScanned = isScanned,
                            isNextRequired = isNext,
                            onScan = { method ->
                                if (activePatrol != null) {
                                    // Sequence validation
                                    if (nextRequiredCheckpoint != null && nextRequiredCheckpoint.id != cp.id) {
                                        viewModel.postSecurityAlert("Sequence Violation: Checkpoint ${nextRequiredCheckpoint.order} (${nextRequiredCheckpoint.name}) must be inspected before ${cp.name}.")
                                        return@CheckpointItemCard
                                    }

                                    scannedCheckpointIds = scannedCheckpointIds + cp.id
                                    coroutineScope.launch {
                                        val loc = com.example.util.LocationHelper.getDeviceLocation(context)
                                        val gpsStr = if (loc != null) "${loc.first},${loc.second}" else ""
                                        val noteStr = "Physical checkpoint verified via $method (GPS: $gpsStr)"
                                        viewModel.scanCheckpoint(
                                            patrolId = activePatrol.id,
                                            checkpointId = cp.id,
                                            gps = gpsStr,
                                            notes = noteStr,
                                            checkpointCode = cp.code,
                                            checkpointOrder = cp.order,
                                            verificationMethod = method,
                                            accuracy = if (loc != null) 10.0 else null
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Supervisor Assign Patrol Dialog
    if (showAssignPatrolDialog) {
        var selectedGuardId by remember { mutableStateOf("") }
        var patrolName by remember { mutableStateOf("Routine Station Patrol") }
        var deadlineHours by remember { mutableStateOf("2") }
        var notes by remember { mutableStateOf("") }

        val stationGuards = remember(uiState.users, currentUser?.station) {
            uiState.users.filter { it.role == "GUARD" && (currentUser?.station == null || it.station == currentUser.station) }
        }

        AlertDialog(
            onDismissRequest = { showAssignPatrolDialog = false },
            title = { Text("Assign New Patrol Round") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Select on-duty guard to conduct patrol at this station:", style = MaterialTheme.typography.bodySmall)

                    if (stationGuards.isEmpty()) {
                        Text("No guards found for this station.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column {
                            stationGuards.forEach { guard ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedGuardId = guard.id }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedGuardId == guard.id,
                                        onClick = { selectedGuardId = guard.id }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("${guard.fullName} (${guard.employeeNumber ?: guard.username})", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = patrolName,
                        onValueChange = { patrolName = it },
                        label = { Text("Patrol Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = deadlineHours,
                        onValueChange = { deadlineHours = it },
                        label = { Text("Deadline (Hours from now)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Instructions / Focus Areas") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedGuardId.isNotBlank()) {
                            val hours = deadlineHours.toLongOrNull() ?: 2L
                            val deadlineIso = java.time.Instant.now().plusSeconds(hours * 3600).toString()
                            showAssignPatrolDialog = false
                            viewModel.assignPatrol(
                                guardId = selectedGuardId,
                                stationId = currentUser?.station,
                                name = patrolName,
                                startWindow = java.time.Instant.now().toString(),
                                deadline = deadlineIso,
                                notes = notes.ifBlank { null }
                            )
                        }
                    },
                    enabled = selectedGuardId.isNotBlank()
                ) {
                    Text("Assign Patrol")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAssignPatrolDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Supervisor Reject Dialog
    if (patrolToReject != null) {
        val target = patrolToReject!!
        AlertDialog(
            onDismissRequest = { patrolToReject = null },
            title = { Text("Reject Patrol Log") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Provide the operational reason for rejecting patrol log '${target.name}':")
                    OutlinedTextField(
                        value = rejectReasonText,
                        onValueChange = { rejectReasonText = it },
                        label = { Text("Rejection Reason") },
                        placeholder = { Text("e.g. Checkpoint 3 bypassed or rapid transit anomaly detected") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rejectReasonText.isNotBlank()) {
                            val pid = target.id
                            patrolToReject = null
                            viewModel.rejectPatrol(pid, rejectReasonText)
                        }
                    },
                    enabled = rejectReasonText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Rejection")
                }
            },
            dismissButton = {
                TextButton(onClick = { patrolToReject = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirm Completion Dialog
    if (showFinishConfirmDialog && activePatrol != null) {
        AlertDialog(
            onDismissRequest = { showFinishConfirmDialog = false },
            title = { Text("Complete Patrol Round") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Are you sure you want to finalize and submit this patrol log for ${activePatrol.stationName}?")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Patrol Summary:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "• Checkpoints Verified: $completedScans of $totalCheckpoints",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "• Elapsed Duration: $formattedElapsedTime",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFinishConfirmDialog = false
                        viewModel.finishPatrol(activePatrol.id, "Routine patrol completed successfully ($completedScans/$totalCheckpoints checkpoints scanned).")
                    }
                ) {
                    Text("Confirm & Submit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun CheckpointItemCard(
    checkpoint: Checkpoint,
    patrolActive: Boolean,
    isScanned: Boolean = false,
    isNextRequired: Boolean = false,
    onScan: (method: String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("checkpoint_item_${checkpoint.code}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isScanned) StatusSuccess.copy(alpha = 0.08f)
            else if (isNextRequired) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${checkpoint.order}. ${checkpoint.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isScanned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.CheckCircle, contentDescription = "Scanned", tint = StatusSuccess, modifier = Modifier.size(16.dp))
                    } else if (isNextRequired) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(4.dp)) {
                            Text(
                                text = "NEXT",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "Code: ${checkpoint.code} • QR: ${checkpoint.qrCode}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!checkpoint.nfcUid.isNullOrBlank()) {
                    Text(
                        text = "NFC Tag UID: ${checkpoint.nfcUid}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (isScanned) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "VERIFIED",
                        color = StatusSuccess,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onScan("NFC") },
                        enabled = patrolActive,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("scan_checkpoint_nfc_${checkpoint.code}")
                    ) {
                        Icon(Icons.Default.Nfc, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("NFC", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = { onScan("QR") },
                        enabled = patrolActive,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("scan_checkpoint_qr_${checkpoint.code}")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("QR", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
