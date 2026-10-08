package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppRole
import com.example.data.model.ReassignDutyRequest
import com.example.data.model.SetOpeningBalanceRequest
import com.example.ui.components.*
import com.example.ui.theme.*
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
    var compensation by remember { mutableStateOf("") }
    var stationId by remember { mutableStateOf("") }
    var shiftType by remember { mutableStateOf("") }
    var pairGuardId by remember { mutableStateOf("") }
    var rosterPosition by remember { mutableStateOf("") }
    var assignmentType by remember { mutableStateOf("") }

    var guardDropdownExpanded by remember { mutableStateOf(false) }
    var stationDropdownExpanded by remember { mutableStateOf(false) }
    var shiftDropdownExpanded by remember { mutableStateOf(false) }
    var pairDropdownExpanded by remember { mutableStateOf(false) }
    var rosterPosDropdownExpanded by remember { mutableStateOf(false) }
    var assignTypeDropdownExpanded by remember { mutableStateOf(false) }

    var confirmOpeningBalance by remember { mutableStateOf(false) }
    var confirmDutyReassignment by remember { mutableStateOf(false) }

    val admin = state.currentUser?.appRole == AppRole.ADMINISTRATOR

    fun reloadAll() {
        viewModel.clearMessages()
        viewModel.fetchUsers(role = "GUARD")
        viewModel.fetchStations()
        viewModel.fetchGuardPairs()
        viewModel.fetchStationLeaveBalances()
        viewModel.fetchRosterShifts()
        viewModel.fetchAdministrativeHistory()
    }

    LaunchedEffect(Unit) {
        if (!admin) onBack() else {
            reloadAll()
        }
    }

    // Auto-dismiss transient messages
    LaunchedEffect(state.successMessage, state.errorMessage) {
        if (state.successMessage != null || state.errorMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearMessages()
        }
    }

    val guards = state.users.filter { it.role.equals("GUARD", true) }
    val selectedGuard = guards.firstOrNull { it.id == guardId }
    val currentBalance = state.stationLeaveBalances.firstOrNull { it.guard == guardId }
    val activePair = state.guardPairs.firstOrNull { (it.guardA == guardId || it.guardB == guardId) && it.isActive }
    val todayStr = LocalDate.now().toString()
    val upcomingShift = state.rosterShifts.filter { it.guard == guardId && it.date >= todayStr }.minByOrNull { it.date }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "Leave & Duty Master Control",
                subtitle = "Authoritative Overrides & Adjustments",
                onNavigationClick = onBack,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("admin_master_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NavyDark
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { reloadAll() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("refresh_admin_master_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = NavyDark)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(LightBackground)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.errorMessage != null) {
                Surface(
                    color = StatusError.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        state.errorMessage!!,
                        color = StatusError,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (state.successMessage != null) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        state.successMessage!!,
                        color = StatusSuccess,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 1. Guard Selector
            ExposedDropdownMenuBox(
                expanded = guardDropdownExpanded,
                onExpandedChange = { guardDropdownExpanded = !guardDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = selectedGuard?.let { "${it.fullName ?: it.username} (${it.employeeNumber ?: "No ID"})" } ?: "Select guard to inspect records…",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Authoritative Target Guard") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = guardDropdownExpanded) }
                )
                ExposedDropdownMenu(
                    expanded = guardDropdownExpanded,
                    onDismissRequest = { guardDropdownExpanded = false }
                ) {
                    guards.forEach { guard ->
                        DropdownMenuItem(
                            text = { Text("${guard.fullName ?: guard.username} · ${guard.employeeNumber.orEmpty()} · ${state.stations.firstOrNull { it.id == guard.station }?.name ?: guard.stationName ?: "No station"}") },
                            onClick = {
                                guardId = guard.id
                                stationId = guard.station.orEmpty()
                                guardDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // 2. Authoritative Current Record Card
            if (selectedGuard != null) {
                SgmisCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Authoritative Current Record",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryLight
                            )
                            SgmisBadge(
                                text = "DATABASE REAL-TIME",
                                variant = BadgeVariant.Info
                            )
                        }
                        HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Employee:", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                            Text("${selectedGuard.fullName ?: selectedGuard.username} (${selectedGuard.employeeNumber ?: "None"})", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = TextPrimaryLight)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Station:", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                            Text(state.stations.firstOrNull { it.id == selectedGuard.station }?.name ?: selectedGuard.stationName ?: "Unassigned", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = TextPrimaryLight)
                        }

                        val partnerName = if (activePair != null) {
                            if (activePair.guardA == guardId) (activePair.guardBName ?: "Guard B") else (activePair.guardAName ?: "Guard A")
                        } else "None assigned"
                        val pairOrder = activePair?.rotationOrder ?: activePair?.order ?: 1
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Roster Pair & Order:", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                            Text("Pair $pairOrder · Partner: $partnerName", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = TextPrimaryLight)
                        }

                        HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                        Text("Current Leave Balances:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Vacation Days:", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                            Text("${currentBalance?.remainingVacation ?: 0.0} rem (${currentBalance?.vacationDays ?: 0.0} accrued, ${currentBalance?.usedVacation ?: 0.0} used)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = TextPrimaryLight)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Casual Days:", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                            Text("${currentBalance?.remainingCasual ?: 0.0} rem (${currentBalance?.casualDays ?: 0.0} accrued, ${currentBalance?.usedCasual ?: 0.0} used)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = TextPrimaryLight)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Holiday Compensation:", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                            Text("${currentBalance?.remainingCompensation ?: 0.0} days rem", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = TextPrimaryLight)
                        }

                        if (upcomingShift != null) {
                            HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                            Text("Next Scheduled Duty:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${upcomingShift.date} · ${upcomingShift.shiftType} · ${upcomingShift.stationName}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = TextPrimaryLight)
                                Text("${upcomingShift.assignmentType} (${upcomingShift.attendanceStatus})", style = MaterialTheme.typography.bodySmall, color = NavyDark, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. Opening Leave Balance Card
            SgmisCard {
                Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Opening Leave Balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                    Text("Superuser master ledger adjustment. Establishes verified opening balance without mutating historical accruals.", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)

                    if (currentBalance != null) {
                        Surface(
                            color = NavyDark.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Current Balances: Vacation ${currentBalance.vacationDays}d · Casual ${currentBalance.casualDays}d · Holiday Compensation ${currentBalance.remainingCompensation}d",
                                style = MaterialTheme.typography.labelSmall,
                                color = NavyDark,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = vacation,
                        onValueChange = { vacation = it },
                        label = { Text("Vacation balance (days, optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = casual,
                        onValueChange = { casual = it },
                        label = { Text("Casual balance (days, optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = compensation,
                        onValueChange = { compensation = it },
                        label = { Text("Holiday Compensation balance (days, optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = effectiveDate,
                        onValueChange = { effectiveDate = it },
                        label = { Text("Effective date (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it },
                        label = { Text("Source citation (e.g. Physical muster roll / payroll ledger)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason / verification citation (mandatory)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Button(
                        enabled = guardId.isNotBlank() && reason.isNotBlank() &&
                                (vacation.isNotBlank() || casual.isNotBlank() || compensation.isNotBlank()) &&
                                (vacation.isBlank() || vacation.toDoubleOrNull() != null) &&
                                (casual.isBlank() || casual.toDoubleOrNull() != null) &&
                                (compensation.isBlank() || compensation.toDoubleOrNull() != null) &&
                                !state.openingBalanceSaving,
                        onClick = { confirmOpeningBalance = true },
                        colors = ButtonDefaults.buttonColors(containerColor = NavyDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(if (state.openingBalanceSaving) "Saving…" else "Review opening balance")
                    }
                }
            }

            // 4. Future Duty Reassignment Card
            SgmisCard {
                Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Future Duty Reassignment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                    Text("Superuser duty reassignment forward from effective date. Earlier shifts and attendance records are strictly preserved.", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)

                    OutlinedTextField(
                        value = effectiveDate,
                        onValueChange = { effectiveDate = it },
                        label = { Text("Effective date (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Station Dropdown
                    ExposedDropdownMenuBox(
                        expanded = stationDropdownExpanded,
                        onExpandedChange = { stationDropdownExpanded = !stationDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = state.stations.firstOrNull { it.id == stationId }?.name ?: if (stationId.isBlank()) "Keep current station" else stationId,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Station") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stationDropdownExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = stationDropdownExpanded,
                            onDismissRequest = { stationDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(text = { Text("Keep current station") }, onClick = { stationId = ""; stationDropdownExpanded = false })
                            state.stations.forEach { st ->
                                DropdownMenuItem(text = { Text(st.name) }, onClick = { stationId = st.id; stationDropdownExpanded = false })
                            }
                        }
                    }

                    // Shift Type Dropdown
                    ExposedDropdownMenuBox(
                        expanded = shiftDropdownExpanded,
                        onExpandedChange = { shiftDropdownExpanded = !shiftDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = shiftType.ifBlank { "Keep current shift type" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Shift Type") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = shiftDropdownExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = shiftDropdownExpanded,
                            onDismissRequest = { shiftDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(text = { Text("Keep current shift type") }, onClick = { shiftType = ""; shiftDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("DAY (07:00 – 18:00)") }, onClick = { shiftType = "DAY"; shiftDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("NIGHT (18:00 – 07:00)") }, onClick = { shiftType = "NIGHT"; shiftDropdownExpanded = false })
                        }
                    }

                    // Pair Guard Dropdown
                    ExposedDropdownMenuBox(
                        expanded = pairDropdownExpanded,
                        onExpandedChange = { pairDropdownExpanded = !pairDropdownExpanded }
                    ) {
                        val selectedPairGuard = guards.firstOrNull { it.id == pairGuardId }
                        OutlinedTextField(
                            value = selectedPairGuard?.let { "${it.fullName ?: it.username} (${it.employeeNumber.orEmpty()})" } ?: if (pairGuardId.isBlank()) "Keep current pair" else pairGuardId,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Replacement Pair Guard") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pairDropdownExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = pairDropdownExpanded,
                            onDismissRequest = { pairDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(text = { Text("Keep current pair") }, onClick = { pairGuardId = ""; pairDropdownExpanded = false })
                            guards.filter { it.id != guardId }.forEach { g ->
                                DropdownMenuItem(text = { Text("${g.fullName ?: g.username} · ${g.employeeNumber.orEmpty()}") }, onClick = { pairGuardId = g.id; pairDropdownExpanded = false })
                            }
                        }
                    }

                    // Roster Position (Rotation Order) Dropdown
                    ExposedDropdownMenuBox(
                        expanded = rosterPosDropdownExpanded,
                        onExpandedChange = { rosterPosDropdownExpanded = !rosterPosDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = if (rosterPosition.isBlank()) "Keep current position" else "Pair $rosterPosition (Position $rosterPosition)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Roster Position / Rotation Order") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = rosterPosDropdownExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = rosterPosDropdownExpanded,
                            onDismissRequest = { rosterPosDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(text = { Text("Keep current position") }, onClick = { rosterPosition = ""; rosterPosDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("Pair 1 (Position 1)") }, onClick = { rosterPosition = "1"; rosterPosDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("Pair 2 (Position 2)") }, onClick = { rosterPosition = "2"; rosterPosDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("Pair 3 (Position 3)") }, onClick = { rosterPosition = "3"; rosterPosDropdownExpanded = false })
                        }
                    }

                    // Assignment Type Dropdown
                    ExposedDropdownMenuBox(
                        expanded = assignTypeDropdownExpanded,
                        onExpandedChange = { assignTypeDropdownExpanded = !assignTypeDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = assignmentType.ifBlank { "Keep current assignment (NORMAL)" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Assignment Type") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = assignTypeDropdownExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = assignTypeDropdownExpanded,
                            onDismissRequest = { assignTypeDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(text = { Text("Keep current assignment") }, onClick = { assignmentType = ""; assignTypeDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("NORMAL") }, onClick = { assignmentType = "NORMAL"; assignTypeDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("EXAM") }, onClick = { assignmentType = "EXAM"; assignTypeDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("ESCORT") }, onClick = { assignmentType = "ESCORT"; assignTypeDropdownExpanded = false })
                        }
                    }

                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason / Operational Citation (mandatory)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Button(
                        enabled = guardId.isNotBlank() && reason.isNotBlank() && !state.dutyReassignmentSaving,
                        onClick = { confirmDutyReassignment = true },
                        colors = ButtonDefaults.buttonColors(containerColor = NavyDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(if (state.dutyReassignmentSaving) "Saving…" else "Review future reassignment")
                    }
                }
            }

            // 5. Recent Leave Adjustments
            Text("Recent Leave Adjustments Ledger", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            if (state.leaveAdjustmentHistory.isEmpty()) {
                Text("No recent adjustments recorded.", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
            } else {
                state.leaveAdjustmentHistory.take(20).forEach { row ->
                    SgmisCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${row.guardName ?: row.guard} · ${row.leaveType}", fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                                Text("${row.previousBalance} → ${row.newBalance} days", fontWeight = FontWeight.SemiBold, color = NavyDark)
                            }
                            Text("Effective: ${row.effectiveDate} · Authorized by: ${row.authorizedByName ?: "Admin"}", style = MaterialTheme.typography.bodySmall, color = TextPrimaryLight)
                            Text("Source: ${row.source} · Reason: ${row.reason}", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                        }
                    }
                }
            }
        }
    }

    if (confirmOpeningBalance) {
        val guardLabel = selectedGuard?.fullName ?: selectedGuard?.username ?: guardId
        AlertDialog(
            onDismissRequest = { confirmOpeningBalance = false },
            title = { Text("Save Opening Balance Changes?", fontWeight = FontWeight.Bold, color = TextPrimaryLight) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Employee: $guardLabel", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    Text("Effective Date: $effectiveDate", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    if (vacation.isNotBlank()) Text("Vacation: ${currentBalance?.vacationDays ?: 0.0} → ${vacation.toDoubleOrNull()} days", color = NavyDark, fontWeight = FontWeight.SemiBold)
                    if (casual.isNotBlank()) Text("Casual: ${currentBalance?.casualDays ?: 0.0} → ${casual.toDoubleOrNull()} days", color = NavyDark, fontWeight = FontWeight.SemiBold)
                    if (compensation.isNotBlank()) Text("Holiday Comp: ${currentBalance?.remainingCompensation ?: 0.0} → ${compensation.toDoubleOrNull()} days", color = NavyDark, fontWeight = FontWeight.SemiBold)
                    Text("Reason: $reason", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                    if (source.isNotBlank()) Text("Source: $source", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.openingBalanceSaving,
                    onClick = {
                        confirmOpeningBalance = false
                        viewModel.setOpeningBalance(
                            SetOpeningBalanceRequest(
                                guardId = guardId,
                                effectiveDate = effectiveDate,
                                reason = reason,
                                source = source.ifBlank { null },
                                vacationBalance = vacation.toDoubleOrNull(),
                                casualBalance = casual.toDoubleOrNull(),
                                compensationBalance = compensation.toDoubleOrNull()
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NavyDark)
                ) { Text(if (state.openingBalanceSaving) "Saving…" else "Confirm Save") }
            },
            dismissButton = { TextButton(onClick = { confirmOpeningBalance = false }) { Text("Back", color = TextSecondaryLight) } }
        )
    }

    if (confirmDutyReassignment) {
        val guardLabel = selectedGuard?.fullName ?: selectedGuard?.username ?: guardId
        val targetStationName = state.stations.firstOrNull { it.id == stationId }?.name ?: if (stationId.isBlank()) "Unchanged" else stationId
        val targetPairGuard = guards.firstOrNull { it.id == pairGuardId }
        val pairLabel = targetPairGuard?.let { "${it.fullName ?: it.username} (${it.employeeNumber.orEmpty()})" } ?: if (pairGuardId.isBlank()) "Unchanged" else pairGuardId

        AlertDialog(
            onDismissRequest = { confirmDutyReassignment = false },
            title = { Text("Confirm Future Duty Reassignment?", fontWeight = FontWeight.Bold, color = TextPrimaryLight) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Employee: $guardLabel", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    Text("Effective Date: From $effectiveDate forward", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    Text("New Station: $targetStationName", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    Text("New Shift Type: ${shiftType.ifBlank { "Unchanged" }}", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    Text("Replacement Pair Guard: $pairLabel", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    if (rosterPosition.isNotBlank()) Text("Roster Position: Pair $rosterPosition", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    if (assignmentType.isNotBlank()) Text("Assignment Type: $assignmentType", style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
                    Text("Earlier roster history and attendance are strictly preserved.", style = MaterialTheme.typography.labelSmall, color = NavyDark, fontWeight = FontWeight.SemiBold)
                    Text("Reason: $reason", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.dutyReassignmentSaving,
                    onClick = {
                        confirmDutyReassignment = false
                        viewModel.reassignDuty(
                            ReassignDutyRequest(
                                guardId = guardId,
                                effectiveDate = effectiveDate,
                                reason = reason,
                                shiftType = shiftType.ifBlank { null },
                                stationId = stationId.ifBlank { null },
                                pairGuardId = pairGuardId.ifBlank { null },
                                rosterPosition = rosterPosition.toIntOrNull(),
                                assignmentType = assignmentType.ifBlank { null }
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NavyDark)
                ) { Text(if (state.dutyReassignmentSaving) "Saving…" else "Confirm Reassignment") }
            },
            dismissButton = { TextButton(onClick = { confirmDutyReassignment = false }) { Text("Back", color = TextSecondaryLight) } }
        )
    }
}
