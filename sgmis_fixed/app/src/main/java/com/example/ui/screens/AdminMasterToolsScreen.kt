package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AppRole
import com.example.data.model.ReassignDutyRequest
import com.example.data.model.SetOpeningBalanceRequest
import com.example.ui.viewmodel.SgmisViewModel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminMasterToolsScreen(viewModel: SgmisViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var guardId by remember { mutableStateOf("") }
    var effectiveDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var reason by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var vacation by remember { mutableStateOf("") }
    var casual by remember { mutableStateOf("") }
    var stationId by remember { mutableStateOf("") }
    var shiftType by remember { mutableStateOf("") }
    var pairGuardId by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var confirmOpeningBalance by remember { mutableStateOf(false) }
    var confirmDutyReassignment by remember { mutableStateOf(false) }
    val admin = state.currentUser?.appRole == AppRole.ADMINISTRATOR
    LaunchedEffect(Unit) {
        if (!admin) onBack() else {
            viewModel.fetchUsers(role = "GUARD")
            viewModel.fetchStations()
            viewModel.fetchGuardPairs()
            viewModel.fetchStationLeaveBalances()
            viewModel.fetchRosterShifts()
            viewModel.fetchAdministrativeHistory()
        }
    }
    val guards = state.users.filter { it.role.equals("GUARD", true) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Leave & Duty Master Control", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.errorMessage != null) Text(state.errorMessage!!, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(guardId, {}, readOnly = true, label = { Text("Select guard") }, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    TextButton(onClick = { expanded = true }) { Text("Choose") }
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        guards.forEach { guard -> DropdownMenuItem(text = { Text("${guard.fullName ?: guard.username} · ${guard.employeeNumber.orEmpty()}") }, onClick = { guardId = guard.id; expanded = false }) }
                    }
                }
            })
            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Opening Leave Balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Uses the administrator-only backend ledger endpoint. Leave blank to leave that balance unchanged.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(vacation, { vacation = it }, label = { Text("Vacation opening balance") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(casual, { casual = it }, label = { Text("Casual opening balance") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(effectiveDate, { effectiveDate = it }, label = { Text("Effective date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(source, { source = it }, label = { Text("Source") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(reason, { reason = it }, label = { Text("Reason / verification citation") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Button(enabled = guardId.isNotBlank() && reason.isNotBlank() && (vacation.isNotBlank() || casual.isNotBlank()) && (vacation.isBlank() || vacation.toDoubleOrNull() != null) && (casual.isBlank() || casual.toDoubleOrNull() != null) && !state.openingBalanceSaving,
                        onClick = { confirmOpeningBalance = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Review opening balance")
                    }
                }
            }
            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Future Duty Reassignment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("The backend updates this guard’s assignments from the effective date forward and preserves earlier shifts and attendance.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(effectiveDate, { effectiveDate = it }, label = { Text("Effective date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(stationId, { stationId = it }, label = { Text("Station ID (optional)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(shiftType, { shiftType = it.uppercase() }, label = { Text("Shift type DAY / NIGHT (optional)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pairGuardId, { pairGuardId = it }, label = { Text("Pair guard ID (optional)") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = guardId.isNotBlank() && reason.isNotBlank() && !state.dutyReassignmentSaving,
                        onClick = { confirmDutyReassignment = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Review future reassignment")
                    }
                }
            }
            Text("Recent leave adjustments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            state.leaveAdjustmentHistory.take(30).forEach { row ->
                Text("${row.guardName ?: row.guard} · ${row.leaveType}: ${row.previousBalance} → ${row.newBalance} effective ${row.effectiveDate} · ${row.authorizedByName.orEmpty()} · ${row.source} · ${row.reason}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmOpeningBalance) {
        val selectedGuard = guards.firstOrNull { it.id == guardId }
        val currentBalance = state.stationLeaveBalances.firstOrNull { it.guard == guardId }
        val guardLabel = selectedGuard?.fullName ?: selectedGuard?.username ?: guardId
        AlertDialog(
            onDismissRequest = { confirmOpeningBalance = false },
            title = { Text("Save these changes?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Opening balance · $guardLabel · effective $effectiveDate")
                if (vacation.isNotBlank()) Text("Vacation: ${currentBalance?.vacationDays ?: "unknown"} → ${vacation.toDoubleOrNull()}")
                if (casual.isNotBlank()) Text("Casual: ${currentBalance?.casualDays ?: "unknown"} → ${casual.toDoubleOrNull()}")
                Text("Reason: $reason")
                if (source.isNotBlank()) Text("Source: $source")
            } },
            confirmButton = { TextButton(enabled = !state.openingBalanceSaving, onClick = {
                confirmOpeningBalance = false
                viewModel.setOpeningBalance(SetOpeningBalanceRequest(guardId, effectiveDate, reason, source.ifBlank { null }, vacation.toDoubleOrNull(), casual.toDoubleOrNull()))
            }) { Text(if (state.openingBalanceSaving) "Saving…" else "Confirm save") } },
            dismissButton = { TextButton(onClick = { confirmOpeningBalance = false }) { Text("Back") } }
        )
    }
    if (confirmDutyReassignment) {
        val selectedGuard = guards.firstOrNull { it.id == guardId }
        val upcomingShift = state.rosterShifts.filter { it.guard == guardId && it.date >= effectiveDate }.minByOrNull { it.date }
        val guardLabel = selectedGuard?.fullName ?: selectedGuard?.username ?: guardId
        AlertDialog(
            onDismissRequest = { confirmDutyReassignment = false },
            title = { Text("Save these changes?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Future assignments · $guardLabel · from $effectiveDate")
                Text("Current next assignment: ${upcomingShift?.stationName ?: "none scheduled"} ${upcomingShift?.shiftType.orEmpty()}")
                Text("New station: ${state.stations.firstOrNull { it.id == stationId }?.name ?: stationId.ifBlank { "unchanged" }}")
                Text("New shift type: ${shiftType.ifBlank { "unchanged" }}")
                Text("Replacement pair guard: ${pairGuardId.ifBlank { "unchanged" }}")
                Text("Earlier roster history and attendance are preserved.")
                Text("Reason: $reason")
            } },
            confirmButton = { TextButton(enabled = !state.dutyReassignmentSaving, onClick = {
                confirmDutyReassignment = false
                viewModel.reassignDuty(ReassignDutyRequest(guardId, effectiveDate, reason, shiftType.ifBlank { null }, stationId.ifBlank { null }, pairGuardId = pairGuardId.ifBlank { null }))
            }) { Text(if (state.dutyReassignmentSaving) "Saving…" else "Confirm reassignment") } },
            dismissButton = { TextButton(onClick = { confirmDutyReassignment = false }) { Text("Back") } }
        )
    }
}
