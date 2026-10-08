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
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import com.example.data.model.AppRole
import com.example.data.model.ExamDuty
import com.example.data.model.UpdateExamDutyRequest
import kotlinx.coroutines.delay

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
            delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "Exam Security Duties",
                subtitle = "Active Station: ${uiState.currentStationName}",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchExamDuties() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            if (isSupervisorOrAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateExamDialog = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Schedule Exam Duty", fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (uiState.successMessage != null) {
                SgmisStatusCard(
                    status = SgmisCardStatus.SUCCESS,
                    title = "Success",
                    message = uiState.successMessage!!,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.errorMessage != null) {
                SgmisStatusCard(
                    status = SgmisCardStatus.ERROR,
                    title = "Error",
                    message = uiState.errorMessage!!,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.examsLoading) {
                SgmisLoadingSkeleton()
            } else if (uiState.examDuties.isEmpty()) {
                SgmisEmptyState(
                    icon = Icons.Outlined.School,
                    title = "No Exam Duties Scheduled",
                    description = "Institutional exam posts and escort assignments will appear here.",
                    actionLabel = if (isSupervisorOrAdmin) "Schedule Exam Duty" else null,
                    onAction = { showCreateExamDialog = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(uiState.examDuties) { duty ->
                        ExamDutyCard(
                            duty = duty,
                            canUpdateStatus = !isAdmin,
                            onUpdateStatus = { st -> viewModel.updateExamStatus(duty.id, st) }
                        )
                        if (isAdmin) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { editDuty = duty }) { Text("Edit / Reassign") }
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                TextButton(
                                    enabled = duty.status != "CANCELLED",
                                    onClick = { cancelDuty = duty },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Cancel Duty")
                                }
                            }
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
            title = { Text("Cancel exam duty?", fontWeight = FontWeight.Bold) },
            text = { Text("${duty.reference} · ${duty.examTitle}\nAssigned guard: ${duty.guardName}\nInstitution: ${duty.institution}\nStatus: ${duty.status} → CANCELLED") },
            confirmButton = {
                Button(
                    onClick = { viewModel.cancelExamDuty(duty.id); cancelDuty = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Cancellation")
                }
            },
            dismissButton = { TextButton(onClick = { cancelDuty = null }) { Text("Keep Duty") } }
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
    var instructions by remember(duty.id) { mutableStateOf(duty.instructions.orEmpty()) }
    var date by remember(duty.id) { mutableStateOf(duty.date) }
    var reporting by remember(duty.id) { mutableStateOf(duty.reportingTime.orEmpty()) }
    var start by remember(duty.id) { mutableStateOf(duty.startTime) }
    var end by remember(duty.id) { mutableStateOf(duty.endTime) }
    var notes by remember(duty.id) { mutableStateOf(duty.notes.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit / Reassign Exam Duty", fontWeight = FontWeight.Bold) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Assigned guard", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            guards.forEach { user -> TextButton(onClick = { guard = user.id }) { Text("${if (guard == user.id) "✓ " else ""}${user.fullName ?: user.username}") } }
            Text("Supervisor", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            supervisors.forEach { user -> TextButton(onClick = { supervisor = user.id }) { Text("${if (supervisor == user.id) "✓ " else ""}${user.fullName ?: user.username}") } }
            Text("Station", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            stations.forEach { item -> TextButton(onClick = { station = item.id }) { Text("${if (station == item.id) "✓ " else ""}${item.name}") } }
            OutlinedTextField(title, { title = it }, label = { Text("Exam title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(institution, { institution = it }, label = { Text("Institution") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(hall, { hall = it }, label = { Text("Hall / Post") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reporting, { reporting = it }, label = { Text("Reporting time (HH:MM:SS)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(start, { start = it }, label = { Text("Start time (HH:MM:SS)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(end, { end = it }, label = { Text("End time (HH:MM:SS)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Administrative notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(onClick = { confirmSave = true }) { Text("Review changes") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (confirmSave) {
        val oldGuard = guards.firstOrNull { it.id == duty.guard }?.fullName ?: duty.guardName ?: duty.guard
        val newGuard = guards.firstOrNull { it.id == guard }?.fullName ?: guard
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save these changes?", fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Exam ${duty.reference} · ${duty.examTitle}")
                Text("Guard: $oldGuard → $newGuard")
                Text("Supervisor: ${duty.supervisorName ?: "Unassigned"} → ${supervisors.firstOrNull { it.id == supervisor }?.fullName ?: "Unassigned"}")
                Text("Station: ${duty.stationName ?: "Unassigned"} → ${stations.firstOrNull { it.id == station }?.name ?: "Unassigned"}")
                Text("Institution: ${duty.institution} → $institution")
                Text("Hall / Post: ${duty.hallPost.orEmpty()} → $hall")
                Text("Instructions: ${duty.instructions.orEmpty()} → $instructions")
                Text("Date: ${duty.date} → $date")
                Text("Hours: ${duty.startTime} – ${duty.endTime} → $start – $end")
                Text("Notes: ${duty.notes.orEmpty()} → $notes")
            } },
            confirmButton = { Button(onClick = { confirmSave = false; onSave(UpdateExamDutyRequest(guard = guard, supervisor = supervisor.ifBlank { null }, station = station.ifBlank { null }, examTitle = title, institution = institution, hallPost = hall.ifBlank { null }, instructions = instructions.ifBlank { null }, date = date, reportingTime = reporting.ifBlank { null }, startTime = start, endTime = end, notes = notes.ifBlank { null })) }) { Text("Confirm save") } },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Back") } }
        )
    }
}
