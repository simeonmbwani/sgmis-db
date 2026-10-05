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
import com.example.data.model.ExamDuty
import com.example.data.model.UpdateExamDutyRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamDutiesScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateExamDialog by remember { mutableStateOf(false) }
    var editDuty by remember { mutableStateOf<ExamDuty?>(null) }
    var cancelDuty by remember { mutableStateOf<ExamDuty?>(null) }

    val isSupervisorOrAdmin = uiState.currentUser?.role?.uppercase() in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")
    val isAdmin = uiState.currentUser?.appRole == AppRole.ADMINISTRATOR

    LaunchedEffect(Unit) {
        viewModel.fetchExamDuties()
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
                title = { Text("Exam Period Duties", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchExamDuties() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (isSupervisorOrAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateExamDialog = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Schedule Exam Duty") },
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
            if (uiState.examsLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.examDuties.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No exam period security duties scheduled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.examDuties) { duty ->
                        ExamDutyCard(
                            duty = duty,
                            canUpdateStatus = !isAdmin,
                            onUpdateStatus = { st -> viewModel.updateExamStatus(duty.id, st) }
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

    if (showCreateExamDialog) {
        CreateExamDialog(
            guards = uiState.users.filter { it.role.equals("GUARD", true) },
            supervisors = uiState.users.filter { it.role.equals("SUPERVISOR", true) },
            stations = uiState.stations,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showCreateExamDialog = false
            },
            onSubmit = { req ->
                viewModel.createExamDuty(req) {
                    showCreateExamDialog = false
                }
            }
        )
    }

    editDuty?.let { duty ->
        ExamEditDialog(duty, uiState.users.filter { it.role.equals("GUARD", true) }, uiState.users.filter { it.role.equals("SUPERVISOR", true) }, uiState.stations, onDismiss = { editDuty = null }, onSave = { request ->
            viewModel.editExamDuty(duty.id, request) { editDuty = null }
        })
    }
    cancelDuty?.let { duty ->
        AlertDialog(
            onDismissRequest = { cancelDuty = null },
            title = { Text("Cancel exam duty?") },
            text = { Text("${duty.reference} · ${duty.examTitle}\nAssigned guard: ${duty.guardName}\nLocation: ${duty.institution}\nStatus: ${duty.status} → CANCELLED") },
            confirmButton = { TextButton(onClick = { viewModel.cancelExamDuty(duty.id); cancelDuty = null }) { Text("Confirm cancellation") } },
            dismissButton = { TextButton(onClick = { cancelDuty = null }) { Text("Keep duty") } }
        )
    }
}

@Composable
private fun ExamEditDialog(duty: ExamDuty, guards: List<com.example.data.model.User>, supervisors: List<com.example.data.model.User>, stations: List<com.example.data.model.Station>, onDismiss: () -> Unit, onSave: (UpdateExamDutyRequest) -> Unit) {
    var confirmSave by remember(duty.id) { mutableStateOf(false) }
    var guard by remember(duty.id) { mutableStateOf(duty.guard) }
    var supervisor by remember(duty.id) { mutableStateOf(duty.supervisor.orEmpty()) }
    var station by remember(duty.id) { mutableStateOf(duty.station.orEmpty()) }
    var title by remember(duty.id) { mutableStateOf(duty.examTitle) }
    var institution by remember(duty.id) { mutableStateOf(duty.institution) }
    var hall by remember(duty.id) { mutableStateOf(duty.hallPost.orEmpty()) }
    var supervisorContact by remember(duty.id) { mutableStateOf(duty.supervisorContact.orEmpty()) }
    var instructions by remember(duty.id) { mutableStateOf(duty.instructions.orEmpty()) }
    var date by remember(duty.id) { mutableStateOf(duty.date) }
    var reportingTime by remember(duty.id) { mutableStateOf(duty.reportingTime.orEmpty()) }
    var start by remember(duty.id) { mutableStateOf(duty.startTime) }
    var end by remember(duty.id) { mutableStateOf(duty.endTime) }
    var notes by remember(duty.id) { mutableStateOf(duty.notes.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit / Reassign Exam Duty") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Assigned guard")
            guards.forEach { user -> TextButton(onClick = { guard = user.id }) { Text("${if (guard == user.id) "✓ " else ""}${user.fullName ?: user.username}") } }
            Text("Supervisor")
            supervisors.forEach { user -> TextButton(onClick = { supervisor = user.id }) { Text("${if (supervisor == user.id) "✓ " else ""}${user.fullName ?: user.username}") } }
            Text("Station")
            stations.forEach { item -> TextButton(onClick = { station = item.id }) { Text("${if (station == item.id) "✓ " else ""}${item.name}") } }
            OutlinedTextField(title, { title = it }, label = { Text("Exam title") })
            OutlinedTextField(institution, { institution = it }, label = { Text("Institution") })
            OutlinedTextField(hall, { hall = it }, label = { Text("Hall / post") })
            OutlinedTextField(supervisorContact, { supervisorContact = it }, label = { Text("Supervisor contact") })
            OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, minLines = 2)
            OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") })
            OutlinedTextField(reportingTime, { reportingTime = it }, label = { Text("Reporting time (HH:MM:SS)") })
            OutlinedTextField(start, { start = it }, label = { Text("Start (HH:MM)") })
            OutlinedTextField(end, { end = it }, label = { Text("End (HH:MM)") })
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
                Text("Exam duty ${duty.reference} · ${duty.examTitle}")
                Text("Guard: $oldGuard → $newGuard")
                Text("Supervisor: ${duty.supervisorName ?: "Unassigned"} → ${supervisors.firstOrNull { it.id == supervisor }?.fullName ?: "Unassigned"}")
                Text("Station: ${duty.stationName ?: "Unassigned"} → ${stations.firstOrNull { it.id == station }?.name ?: "Unassigned"}")
                Text("Exam: ${duty.examTitle} → $title")
                Text("Location: ${duty.institution} → $institution")
                Text("Hall / post: ${duty.hallPost.orEmpty()} → $hall")
                Text("Supervisor contact: ${duty.supervisorContact.orEmpty()} → $supervisorContact")
                Text("Instructions: ${duty.instructions.orEmpty()} → $instructions")
                Text("Date and time: ${duty.date} ${duty.startTime}–${duty.endTime} → $date $start–$end")
                Text("Reporting time: ${duty.reportingTime.orEmpty()} → $reportingTime")
                Text("Notes: ${duty.notes.orEmpty()} → $notes")
            } },
            confirmButton = { TextButton(onClick = { confirmSave = false; onSave(UpdateExamDutyRequest(guard = guard, supervisor = supervisor.ifBlank { null }, station = station.ifBlank { null }, institution = institution, examTitle = title, hallPost = hall, supervisorContact = supervisorContact, instructions = instructions, date = date, reportingTime = reportingTime.ifBlank { null }, startTime = start, endTime = end, notes = notes)) }) { Text("Confirm save") } },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Back") } }
        )
    }
}
