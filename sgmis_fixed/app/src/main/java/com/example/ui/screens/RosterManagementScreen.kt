package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterManagementScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showGenerateDialog by remember { mutableStateOf(false) }
    var showResumeNormalDialog by remember { mutableStateOf(false) }
    var showConfirmRosterApproval by remember { mutableStateOf(false) }
    var showDutyOverrideDialog by remember { mutableStateOf(false) }
    var showPairReassignDialog by remember { mutableStateOf(false) }
    var shiftForReassignment by remember { mutableStateOf<Shift?>(null) }

    val role = uiState.currentUser?.role?.uppercase()
    val isAdmin = role == "ADMINISTRATOR" || role == "ADMIN"
    val isSupervisor = role == "SUPERVISOR"
    var selectedApprovalStation by remember { mutableStateOf<String?>(uiState.currentUser?.station) }

    LaunchedEffect(uiState.stations) {
        if (selectedApprovalStation == null && uiState.stations.isNotEmpty()) {
            selectedApprovalStation = uiState.currentUser?.station ?: uiState.stations.first().id
        }
    }

    LaunchedEffect(selectedApprovalStation) {
        selectedApprovalStation?.let { stId ->
            viewModel.detectConflicts(stId)
            viewModel.fetchExaminationPeriods(stId)
            viewModel.fetchTemporaryAssignments(stId)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fetchRosterShifts()
        viewModel.fetchAttendanceRecords()
        viewModel.fetchStations()
        viewModel.fetchGuardPairs()
        viewModel.fetchLeave()
        if (isAdmin || isSupervisor) {
            viewModel.fetchUsers(role = "GUARD")
            viewModel.fetchStationCoverage()
            viewModel.fetchDutyOverrides()
            viewModel.fetchPairReassignments()
        }
    }

    // Harare Zimbabwe CAT TimeZone for all authoritative roster rendering
    val harareTz = remember { TimeZone.getTimeZone("Africa/Harare") }

    val targetOperationalShifts = remember(uiState.rosterShifts, selectedApprovalStation) {
        if (selectedApprovalStation != null) {
            uiState.rosterShifts.filter { it.station == selectedApprovalStation }
        } else {
            uiState.rosterShifts
        }
    }

    // Build calendar matrix rows
    val calendarRows = remember(targetOperationalShifts, uiState.leaveApplications, selectedApprovalStation) {
        val dates = targetOperationalShifts.map { it.date }.distinct().sorted()
        val inFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz }
        val dayFormat = SimpleDateFormat("EEE", Locale.US).apply { timeZone = harareTz }
        val stationGuardIds = targetOperationalShifts.map { it.guard }.toSet()
        dates.map { dateStr ->
            val dateObj = try { inFormat.parse(dateStr) } catch (e: Exception) { null }
            val dayOfWeek = if (dateObj != null) dayFormat.format(dateObj) else "-"

            val activeLeaves = uiState.leaveApplications.filter {
                it.status == "APPROVED" && it.startDate <= dateStr && it.endDate >= dateStr &&
                (selectedApprovalStation == null || it.guard in stationGuardIds)
            }
            val leaveGuardIds = activeLeaves.map { it.guard }.toSet()

            val isShiftOnLeave = { s: Shift ->
                s.isOnLeave || s.rawDutyState == "ON_LEAVE" || s.leaveType != null || s.guard in leaveGuardIds
            }

            val dayShifts = targetOperationalShifts.filter {
                it.date == dateStr && it.shiftType == "DAY" && it.assignmentType != "TIME_OFF" && !isShiftOnLeave(it)
            }
            val nightShifts = targetOperationalShifts.filter {
                it.date == dateStr && it.shiftType == "NIGHT" && it.assignmentType != "TIME_OFF" && !isShiftOnLeave(it)
            }
            val toShifts = targetOperationalShifts.filter {
                it.date == dateStr && (it.shiftType in listOf("REST", "OFF") || it.assignmentType == "TIME_OFF") && !isShiftOnLeave(it)
            }

            val vac = activeLeaves.filter { it.leaveType == "VACATION" }.map { it.guardName }
            val occ = activeLeaves.filter { it.leaveType == "CASUAL" }.map { it.guardName }
            val sick = activeLeaves.filter { it.leaveType in listOf("SICK", "EMERGENCY") }.map { it.guardName }

            CalendarMatrixRow(
                date = dateStr,
                dayOfWeek = dayOfWeek,
                dayCount = dayShifts.size,
                nightCount = nightShifts.size,
                toCount = toShifts.size,
                vacGuards = vac,
                occGuards = occ,
                sickGuards = sick
            )
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = stringResource(R.string.roster_title),
                subtitle = "Rotational Scheduling & Deployment",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.fetchRosterShifts()
                        viewModel.fetchStations()
                        viewModel.fetchGuardPairs()
                        viewModel.fetchLeave()
                        selectedApprovalStation?.let { viewModel.detectConflicts(it) }
                    }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            if (isAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { showGenerateDialog = true },
                    icon = { Icon(Icons.Default.AutoFixHigh, null) },
                    text = { Text("Generate Roster") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Notification feedback banners
            if (uiState.successMessage != null) {
                SgmisStatusCard(
                    status = CardStatus.SUCCESS,
                    title = "Operation Successful",
                    description = uiState.successMessage!!,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (uiState.errorMessage != null) {
                SgmisStatusCard(
                    status = CardStatus.ERROR,
                    title = "Roster Notice",
                    description = uiState.errorMessage!!,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (isSupervisor) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Supervisor Guardrail: Rosters are generated exclusively by Administrators. Station Supervisors validate and formally sign off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Station Selector Header
            if (uiState.stations.isNotEmpty()) {
                var expandedStation by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Station Filter:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box {
                        OutlinedButton(
                            onClick = { expandedStation = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            val stName = uiState.stations.firstOrNull { it.id == selectedApprovalStation }?.name ?: "All Stations"
                            Text(stName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = expandedStation,
                            onDismissRequest = { expandedStation = false }
                        ) {
                            uiState.stations.forEach { st ->
                                DropdownMenuItem(
                                    text = { Text(st.name) },
                                    onClick = {
                                        selectedApprovalStation = st.id
                                        expandedStation = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            var selectedTabIndex by remember { mutableStateOf(0) }
            val tabs = listOf("Operational Matrix", "Calendar & Audit", "Shift Records", "Overrides")

            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                title,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            val groupedDates = remember(targetOperationalShifts) {
                targetOperationalShifts.groupBy { it.date }.toSortedMap()
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 88.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (selectedTabIndex == 0) {
                    item {
                        SgmisSectionHeader(
                            title = "Authoritative Operational Matrix (${groupedDates.size} Days)",
                            actionLabel = null
                        )
                    }

                    if (uiState.adminLoading) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SgmisLoadingSkeleton(modifier = Modifier.fillMaxWidth().height(120.dp))
                                SgmisLoadingSkeleton(modifier = Modifier.fillMaxWidth().height(120.dp))
                            }
                        }
                    } else if (groupedDates.isEmpty()) {
                        item {
                            SgmisEmptyState(
                                icon = Icons.Outlined.CalendarMonth,
                                title = "No Roster Shifts Generated",
                                message = "No roster shifts generated for this station. Tap 'Generate Roster' to calculate the 3-pair rotational duty schedule."
                            )
                        }
                    } else {
                        items(groupedDates.entries.toList()) { (dateStr, shiftsOnDate) ->
                            DateOperationalMatrixCard(
                                dateStr = dateStr,
                                shiftsOnDate = shiftsOnDate,
                                guardPairs = uiState.guardPairs,
                                leaveApplications = uiState.leaveApplications
                            )
                        }
                    }
                } else if (selectedTabIndex == 1) {
                    // Tab 1: Calendar Matrix & Conflict Audit
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Roster Calendar Matrix",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Scroll horizontally →",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (calendarRows.isEmpty()) {
                                    Text(
                                        text = "No roster schedule data to populate matrix.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                    ) {
                                        Column {
                                            // Header Row
                                            Row(
                                                modifier = Modifier
                                                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Date", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(90.dp))
                                                Text("Day", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(50.dp))
                                                Text("D / N / TO", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(110.dp))
                                                Text("Vac Leave", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(100.dp))
                                                Text("Occ Leave", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(100.dp))
                                                Text("Sick Leave", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(100.dp))
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            calendarRows.forEach { row ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(row.date, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp))
                                                    Text(row.dayOfWeek, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(50.dp))
                                                    Text(
                                                        text = "D:${row.dayCount} N:${row.nightCount} TO:${row.toCount}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.width(110.dp)
                                                    )
                                                    Text(
                                                        text = if (row.vacGuards.isNotEmpty()) row.vacGuards.joinToString(", ") else "—",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (row.vacGuards.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.width(100.dp)
                                                    )
                                                    Text(
                                                        text = if (row.occGuards.isNotEmpty()) row.occGuards.joinToString(", ") else "—",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (row.occGuards.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.width(100.dp)
                                                    )
                                                    Text(
                                                        text = if (row.sickGuards.isNotEmpty()) row.sickGuards.joinToString(", ") else "—",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (row.sickGuards.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.width(100.dp)
                                                    )
                                                }
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Conflict Audit Banner
                    item {
                        RosterConflictBanner(
                            report = uiState.conflictReport,
                            isLoading = uiState.rosterConflictsLoading,
                            onAuditClick = {
                                selectedApprovalStation?.let { viewModel.detectConflicts(it) }
                            }
                        )
                    }

                    // Supervisor / Admin Formal Roster Approval Card
                    if (isSupervisor || isAdmin) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(14.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = StatusSuccess)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Supervisor Roster Sign-Off & Approval",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Text(
                                        text = "Confirm and formally approve station rotational schedule for deployment.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (uiState.conflictReport?.hasConflicts == true) {
                                        Text(
                                            text = "⚠ Sign-off disabled: Please resolve active roster conflicts before approving.",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                selectedApprovalStation?.let { stId ->
                                                    viewModel.validateRoster(stId)
                                                }
                                            },
                                            enabled = !uiState.isLoading && selectedApprovalStation != null,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Validate")
                                        }

                                        Button(
                                            onClick = { showConfirmRosterApproval = true },
                                            enabled = !uiState.isLoading && selectedApprovalStation != null && uiState.conflictReport?.hasConflicts != true,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Approve Roster")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Examination Periods Card
                    if (uiState.examinationPeriods.isNotEmpty() || uiState.temporaryAssignments.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(14.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Examination Duty Periods",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        if (isAdmin || isSupervisor) {
                                            Button(
                                                onClick = { showResumeNormalDialog = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Resume Normal", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }

                                    uiState.examinationPeriods.forEach { ep ->
                                        Surface(
                                            color = if (ep.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(ep.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                                    Text("Venue: ${ep.venueName} • ${ep.startDate} → ${ep.endDate}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Surface(
                                                    color = if (ep.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = if (ep.isActive) "ACTIVE (5-DAY)" else "CONCLUDED",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedTabIndex == 2) {
                    // Tab 2: Individual Shifts Header and List
                    item {
                        SgmisSectionHeader(
                            title = "Scheduled Shift Deployments (${targetOperationalShifts.size})",
                            actionLabel = null
                        )
                    }

                    if (uiState.adminLoading) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SgmisLoadingSkeleton(modifier = Modifier.fillMaxWidth().height(80.dp))
                                SgmisLoadingSkeleton(modifier = Modifier.fillMaxWidth().height(80.dp))
                            }
                        }
                    } else if (targetOperationalShifts.isEmpty()) {
                        item {
                            SgmisEmptyState(
                                icon = Icons.Outlined.EventBusy,
                                title = "No Shifts Scheduled",
                                message = "No roster shifts generated for this station. Tap 'Generate Roster' to calculate rotational duty schedule."
                            )
                        }
                    } else {
                        items(targetOperationalShifts) { shift ->
                            Column {
                                RosterShiftCard(shift)
                                if (isAdmin || isSupervisor) {
                                    TextButton(
                                        onClick = { shiftForReassignment = shift },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Reassign This Shift", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedTabIndex == 3) {
                    // Tab 3: Pair & Duty Overrides
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Operational Overrides & Pairing Management",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Authorized supervisors and administrators can perform emergency duty overrides (leave interruption, relief recalls) and update permanent guard pairing rotations.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isAdmin || isSupervisor) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { showDutyOverrideDialog = true },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Duty Override", style = MaterialTheme.typography.labelSmall)
                                        }
                                        OutlinedButton(
                                            onClick = { showPairReassignDialog = true },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.GroupAdd, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Reassign Pair", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Section 1: Active Duty Overrides & Leave Interruptions
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active Duty Overrides (${uiState.dutyOverrides.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (uiState.dutyOverrides.isEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "No duty overrides or emergency recalls active.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(uiState.dutyOverrides) { overrideItem ->
                            DutyOverrideCard(
                                override = overrideItem,
                                canSettle = isAdmin || isSupervisor,
                                onSettleCompensation = {
                                    viewModel.settleDutyOverrideCompensation(overrideItem.id)
                                }
                            )
                        }
                    }

                    // Section 2: Guard Pair Reassignment Audit Trail
                    item {
                        Text(
                            text = "Pair Reassignment History (${uiState.pairReassignments.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    if (uiState.pairReassignments.isEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "No permanent pair reassignments logged.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(uiState.pairReassignments) { audit ->
                            PairReassignmentAuditCard(audit)
                        }
                    }
                }
            }
        }
    }

    // Modal Confirmation & Action Dialogs
    if (showConfirmRosterApproval) {
        val stationName = uiState.stations.firstOrNull { it.id == selectedApprovalStation }?.name ?: "selected station"
        AlertDialog(
            onDismissRequest = { showConfirmRosterApproval = false },
            title = { Text("Approve Roster for Deployment?", fontWeight = FontWeight.Bold) },
            text = { Text("This formally approves the current rotational schedule for $stationName. Verify the roster and validation results before approval.") },
            confirmButton = {
                TextButton(onClick = {
                    selectedApprovalStation?.let(viewModel::approveRoster)
                    showConfirmRosterApproval = false
                }) { Text("Approve Roster") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmRosterApproval = false }) { Text("Cancel") }
            }
        )
    }

    if (showGenerateDialog) {
        GenerateRosterDialog(
            stations = uiState.stations,
            guardPairs = uiState.guardPairs,
            onDismiss = { showGenerateDialog = false },
            onSubmit = { stId, start, days, mode, examPeriodId, examVenueName, examGuardIds ->
                viewModel.generateRoster(
                    stationId = stId,
                    startDate = start,
                    cycleDays = days,
                    mode = mode,
                    examinationPeriodId = examPeriodId,
                    examVenueName = examVenueName,
                    examGuardIds = examGuardIds
                ) {
                    showGenerateDialog = false
                }
            }
        )
    }

    if (showResumeNormalDialog) {
        ResumeNormalRosterDialog(
            stations = uiState.stations,
            onDismiss = { showResumeNormalDialog = false },
            onSubmit = { stId, afterDate, days ->
                viewModel.resumeNormalRoster(
                    stationId = stId,
                    afterDate = afterDate,
                    cycleDays = days
                ) {
                    showResumeNormalDialog = false
                }
            }
        )
    }

    if (showDutyOverrideDialog) {
        DutyOverrideDialog(
            guards = uiState.users,
            stations = uiState.stations,
            defaultStationId = selectedApprovalStation,
            saving = uiState.isCreatingDutyOverride,
            onDismiss = { showDutyOverrideDialog = false },
            onSubmit = { req ->
                viewModel.createDutyOverride(req) {
                    showDutyOverrideDialog = false
                }
            }
        )
    }

    if (showPairReassignDialog) {
        ReassignPairDialog(
            guards = uiState.users,
            guardPairs = uiState.guardPairs,
            saving = uiState.isReassigningPair,
            onDismiss = { showPairReassignDialog = false },
            onSubmit = { req ->
                viewModel.reassignGuardPair(req) {
                    showPairReassignDialog = false
                }
            }
        )
    }

    shiftForReassignment?.let { shift ->
        ReassignSingleShiftDialog(
            shift = shift,
            guards = uiState.users,
            stations = uiState.stations,
            saving = uiState.dutyReassignmentSaving,
            onDismiss = { shiftForReassignment = null },
            onSubmit = { req ->
                viewModel.reassignSingleShift(shift.id, req) {
                    shiftForReassignment = null
                }
            }
        )
    }
}
