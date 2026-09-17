package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Checkpoint
import com.example.ui.theme.StatusSuccess
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

    val currentUser = uiState.currentUser
    val isSupervisor = currentUser?.role == "SUPERVISOR"
    val isGuard = currentUser?.role == "GUARD" || currentUser?.role == null

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
                    IconButton(
                        onClick = {
                            viewModel.fetchCheckpoints()
                            viewModel.fetchPatrolLogs()
                        },
                        modifier = Modifier.testTag("refresh_patrols_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh Patrols")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

            // Active Patrol Banner Card
            Card(
                modifier = Modifier.fillMaxWidth().testTag("active_patrol_card"),
                colors = CardDefaults.cardColors(
                    containerColor = if (activePatrol != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (activePatrol != null) "PATROL IN PROGRESS" else "NO ACTIVE PATROL",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (activePatrol != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        if (activePatrol != null) {
                            Surface(color = StatusSuccess, shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    text = "ACTIVE",
                                    color = MaterialTheme.colorScheme.surface,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (activePatrol != null) {
                        Text(
                            text = "Station: ${activePatrol.stationName} • Officer: ${activePatrol.guardName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (isGuard) {
                            Button(
                                onClick = { showFinishConfirmDialog = true },
                                enabled = !uiState.isLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().testTag("finish_patrol_button")
                            ) {
                                if (uiState.isLoading) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
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
                                    text = "Supervisors have view-only monitoring. Only on-duty guards can terminate active patrols.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    } else if (isSupervisor) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Supervisors have view-only access to patrol telemetry and inspection checkpoints. Patrol rounds are conducted by assigned on-duty guards.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        val hasAssignedStation = !currentUser?.station.isNullOrBlank() || uiState.todayShift?.station != null
                        val assignedStationName = currentUser?.stationName ?: uiState.todayShift?.stationName ?: "Assigned Post"
                        if (!hasAssignedStation) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Station Assignment Required",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Text(
                                        text = "Your account has no station assigned directly or on today's roster. Station patrols require an assigned duty station. Contact your supervisor or administrator.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Start a verified patrol round to record checkpoint inspections at your assigned station ($assignedStationName).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { viewModel.startPatrol() },
                            enabled = !uiState.isLoading && hasAssignedStation,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("start_patrol_button")
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Initiating Patrol...")
                            } else {
                                Icon(Icons.Default.DirectionsWalk, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (hasAssignedStation) "Initiate Station Patrol" else "Station Assignment Required")
                            }
                        }
                    }
                }
            }

            // Checkpoints Header
            Text(
                text = "Station Inspection Checkpoints (${uiState.checkpoints.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (uiState.checkpoints.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No inspection checkpoints registered for this station.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.checkpoints) { cp ->
                        CheckpointItemCard(
                            checkpoint = cp,
                            patrolActive = activePatrol != null && !uiState.isLoading,
                            onScan = {
                                if (activePatrol != null) {
                                    coroutineScope.launch {
                                        val loc = com.example.util.LocationHelper.getDeviceLocation(context)
                                        val gpsStr = if (loc != null) "${loc.first},${loc.second}" else ""
                                        val noteStr = if (loc != null) "Physical checkpoint inspected (GPS: $gpsStr)" else "Physical checkpoint inspected (No GPS fix)"
                                        viewModel.scanCheckpoint(
                                            patrolId = activePatrol.id,
                                            checkpointId = cp.id,
                                            gps = gpsStr,
                                            notes = noteStr
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

    if (showFinishConfirmDialog && activePatrol != null) {
        AlertDialog(
            onDismissRequest = { showFinishConfirmDialog = false },
            title = { Text("Complete Patrol Round") },
            text = {
                Text("Are you sure you want to complete and submit this patrol log for ${activePatrol.stationName}? This will finalize the record.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFinishConfirmDialog = false
                        viewModel.finishPatrol(activePatrol.id, "Routine patrol completed successfully.")
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
    onScan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("checkpoint_item_${checkpoint.code}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${checkpoint.order}. ${checkpoint.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Code: ${checkpoint.code} • QR: ${checkpoint.qrCode}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Button(
                onClick = onScan,
                enabled = patrolActive,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.testTag("scan_checkpoint_${checkpoint.code}")
            ) {
                Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Verify", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
