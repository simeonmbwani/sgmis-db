package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.SgmisViewModel
import com.example.data.model.AppRole
import com.example.data.model.EscortDuty
import com.example.data.model.UpdateEscortDutyRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EscortDutiesScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateEscortDialog by remember { mutableStateOf(false) }
    var editDuty by remember { mutableStateOf<EscortDuty?>(null) }
    var cancelDuty by remember { mutableStateOf<EscortDuty?>(null) }

    val isSupervisorOrAdmin = uiState.currentUser?.role?.uppercase() in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")
    val isAdmin = uiState.currentUser?.appRole == AppRole.ADMINISTRATOR

    LaunchedEffect(Unit) {
        viewModel.fetchEscortDuties()
        if (isSupervisorOrAdmin) {
            viewModel.fetchUsers()
            viewModel.fetchStations()
        }
    }
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Escort Duties", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchEscortDuties() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (isSupervisorOrAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateEscortDialog = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Assign Escort") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            uiState.successMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (uiState.escortsLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.escortDuties.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No escort duties assigned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.escortDuties) { duty ->
                        EscortCard(
                            duty = duty,
                            canUpdateStatus = !isAdmin,
                            onUpdateStatus = { st -> viewModel.updateEscortStatus(duty.id, st) }
                        )
                        if (isAdmin) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editDuty = duty }) { Text("Edit / Reassign") }
                            TextButton(enabled = duty.status != "CANCELLED", onClick = { cancelDuty = duty }) { Text("Cancel Duty") }
                        }
                    }
                }
            }
        }
    }

    if (showCreateEscortDialog) {
        CreateEscortDialog(
            guards = uiState.users.filter { it.role.equals("GUARD", true) },
            supervisors = uiState.users.filter { it.role.equals("SUPERVISOR", true) },
            stations = uiState.stations,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showCreateEscortDialog = false
            },
            onSubmit = { req ->
                viewModel.createEscortDuty(req) {
                    showCreateEscortDialog = false
                }
            }
        )
    }

    editDuty?.let { duty ->
        EscortEditDialog(duty, uiState.users.filter { it.role.equals("GUARD", true) }, uiState.users.filter { it.role.equals("SUPERVISOR", true) }, uiState.stations, onDismiss = { editDuty = null }, onSave = { request ->
            viewModel.editEscortDuty(duty.id, request) { editDuty = null }
        })
    }
    cancelDuty?.let { duty ->
        AlertDialog(
            onDismissRequest = { cancelDuty = null },
            title = { Text("Cancel escort duty?") },
            text = { Text("${duty.reference} · ${duty.missionName}\nAssigned guard: ${duty.guardName}\nDestination: ${duty.destination}\nStatus: ${duty.status} → CANCELLED") },
            confirmButton = { TextButton(onClick = { viewModel.cancelEscortDuty(duty.id); cancelDuty = null }) { Text("Confirm cancellation") } },
            dismissButton = { TextButton(onClick = { cancelDuty = null }) { Text("Keep duty") } }
        )
    }
}

@Composable
private fun EscortEditDialog(duty: EscortDuty, guards: List<com.example.data.model.User>, supervisors: List<com.example.data.model.User>, stations: List<com.example.data.model.Station>, onDismiss: () -> Unit, onSave: (UpdateEscortDutyRequest) -> Unit) {
    var confirmSave by remember(duty.id) { mutableStateOf(false) }
    var guard by remember(duty.id) { mutableStateOf(duty.guard) }
    var supervisor by remember(duty.id) { mutableStateOf(duty.supervisor.orEmpty()) }
    var station by remember(duty.id) { mutableStateOf(duty.station.orEmpty()) }
    var mission by remember(duty.id) { mutableStateOf(duty.missionName) }
    var origin by remember(duty.id) { mutableStateOf(duty.origin) }
    var destination by remember(duty.id) { mutableStateOf(duty.destination) }
    var purpose by remember(duty.id) { mutableStateOf(duty.purpose.orEmpty()) }
    var instructions by remember(duty.id) { mutableStateOf(duty.instructions.orEmpty()) }
    var contacts by remember(duty.id) { mutableStateOf(duty.contactNumbers.orEmpty()) }
    var start by remember(duty.id) { mutableStateOf(duty.startTime) }
    var end by remember(duty.id) { mutableStateOf(duty.endTime) }
    var notes by remember(duty.id) { mutableStateOf(duty.notes.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit / Reassign Escort") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Assigned guard")
            guards.forEach { user -> TextButton(onClick = { guard = user.id }) { Text("${if (guard == user.id) "✓ " else ""}${user.fullName ?: user.username}") } }
            Text("Supervisor")
            supervisors.forEach { user -> TextButton(onClick = { supervisor = user.id }) { Text("${if (supervisor == user.id) "✓ " else ""}${user.fullName ?: user.username}") } }
            Text("Station")
            stations.forEach { item -> TextButton(onClick = { station = item.id }) { Text("${if (station == item.id) "✓ " else ""}${item.name}") } }
            OutlinedTextField(mission, { mission = it }, label = { Text("Mission") })
            OutlinedTextField(origin, { origin = it }, label = { Text("Origin") })
            OutlinedTextField(destination, { destination = it }, label = { Text("Destination") })
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose") })
            OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, minLines = 2)
            OutlinedTextField(contacts, { contacts = it }, label = { Text("Contact numbers") })
            OutlinedTextField(start, { start = it }, label = { Text("Start (ISO datetime)") })
            OutlinedTextField(end, { end = it }, label = { Text("End (ISO datetime)") })
            OutlinedTextField(notes, { notes = it }, label = { Text("Administrative notes") }, minLines = 2)
        }
    }, confirmButton = { TextButton(onClick = { confirmSave = true }) { Text("Review changes") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (confirmSave) {
        val oldGuard = guards.firstOrNull { it.id == duty.guard }?.fullName ?: duty.guardName ?: duty.guard
        val newGuard = guards.firstOrNull { it.id == guard }?.fullName ?: guard
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save these changes?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Escort ${duty.reference} · ${duty.missionName}")
                Text("Guard: $oldGuard → $newGuard")
                Text("Supervisor: ${duty.supervisorName ?: "Unassigned"} → ${supervisors.firstOrNull { it.id == supervisor }?.fullName ?: "Unassigned"}")
                Text("Station: ${duty.stationName ?: "Unassigned"} → ${stations.firstOrNull { it.id == station }?.name ?: "Unassigned"}")
                Text("Destination: ${duty.destination} → $destination")
                Text("Origin: ${duty.origin} → $origin")
                Text("Mission: ${duty.missionName} → $mission")
                Text("Purpose: ${duty.purpose.orEmpty()} → $purpose")
                Text("Instructions: ${duty.instructions.orEmpty()} → $instructions")
                Text("Contact numbers: ${duty.contactNumbers.orEmpty()} → $contacts")
                Text("Time: ${duty.startTime} – ${duty.endTime} → $start – $end")
                Text("Notes: ${duty.notes.orEmpty()} → $notes")
            } },
            confirmButton = { TextButton(onClick = { confirmSave = false; onSave(UpdateEscortDutyRequest(guard = guard, supervisor = supervisor.ifBlank { null }, station = station.ifBlank { null }, missionName = mission, origin = origin, destination = destination, purpose = purpose, instructions = instructions, contactNumbers = contacts, startTime = start, endTime = end, notes = notes)) }) { Text("Confirm save") } },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Back") } }
        )
    }
}
