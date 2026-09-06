package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatrolScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val activePatrol = uiState.activePatrol

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.patrols_title), fontWeight = FontWeight.Bold) },
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
                        Button(
                            onClick = {
                                viewModel.finishPatrol(activePatrol.id, "Routine patrol completed successfully.")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("finish_patrol_button")
                        ) {
                            Text("Complete & Submit Patrol Log")
                        }
                    } else {
                        Text(
                            text = "Start a verified patrol round to record checkpoint inspections.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { viewModel.startPatrol() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("start_patrol_button")
                        ) {
                            Icon(Icons.Default.DirectionsWalk, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Initiate Station Patrol")
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.checkpoints) { cp ->
                        CheckpointItemCard(
                            checkpoint = cp,
                            patrolActive = activePatrol != null,
                            onScan = {
                                if (activePatrol != null) {
                                    viewModel.scanCheckpoint(
                                        patrolId = activePatrol.id,
                                        checkpointId = cp.id,
                                        gps = "${cp.latitude},${cp.longitude}",
                                        notes = "Verified secure on round"
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
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
