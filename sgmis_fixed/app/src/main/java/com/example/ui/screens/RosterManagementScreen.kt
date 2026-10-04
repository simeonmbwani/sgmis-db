package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.*
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
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
        if (isAdmin) viewModel.fetchUsers(role = "GUARD")
    }

    // Harare Zimbabwe CAT TimeZone for all authoritative roster rendering
    val harareTz = remember { java.util.TimeZone.getTimeZone("Africa/Harare") }

    val targetOperationalShifts = remember(uiState.rosterShifts, selectedApprovalStation) {
        if (selectedApprovalStation != null) {
            uiState.rosterShifts.filter { it.station == selectedApprovalStation }
        } else {
            uiState.rosterShifts
        }
    }

    // Build calendar matrix rows
    val calendarRows = remember(targetOperationalShifts, uiState.leaveApplications) {
        val dates = targetOperationalShifts.map { it.date }.distinct().sorted()
        val inFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz }
        val dayFormat = SimpleDateFormat("EEE", Locale.US).apply { timeZone = harareTz }
        dates.map { dateStr ->
            val dateObj = try { inFormat.parse(dateStr) } catch (e: Exception) { null }
            val dayOfWeek = if (dateObj != null) dayFormat.format(dateObj) else "-"
            val dayShifts = targetOperationalShifts.filter { it.date == dateStr && it.shiftType == "DAY" && it.assignmentType != "TIME_OFF" }
            val nightShifts = targetOperationalShifts.filter { it.date == dateStr && it.shiftType == "NIGHT" && it.assignmentType != "TIME_OFF" }
            val toShifts = targetOperationalShifts.filter { it.date == dateStr && (it.shiftType in listOf("REST", "OFF") || it.assignmentType == "TIME_OFF") }

            val activeLeaves = uiState.leaveApplications.filter {
                it.status == "APPROVED" && it.startDate <= dateStr && it.endDate >= dateStr
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
            TopAppBar(
                title = { Text(stringResource(R.string.roster_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.fetchRosterShifts()
                        viewModel.fetchStations()
                        viewModel.fetchGuardPairs()
                        viewModel.fetchLeave()
                    }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
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
            // Notification banners
            if (uiState.successMessage != null) {
                Surface(
                    color = com.example.ui.theme.StatusSuccess.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = com.example.ui.theme.StatusSuccess, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.successMessage!!,
                            color = com.example.ui.theme.StatusSuccess,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (uiState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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

            if (isSupervisor) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Supervisor Guardrail: Rosters are generated exclusively by Administrators. Supervisors review and sign off approvals.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            var selectedTabIndex by remember { mutableStateOf(0) }
            val tabs = listOf("Operational Matrix", "Calendar & Audit", "Shift Records")

            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            val groupedDates = remember(targetOperationalShifts) {
                targetOperationalShifts.groupBy { it.date }.toSortedMap()
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 88.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (selectedTabIndex == 0) {
                    item {
                        Text(
                            text = "Authoritative Operational Matrix (${groupedDates.size} Days)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (uiState.adminLoading) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else if (groupedDates.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("No roster shifts generated. Tap 'Generate Roster' to calculate rotational duty schedule.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(groupedDates.entries.toList()) { (dateStr, shiftsOnDate) ->
                            DateOperationalMatrixCard(
                                dateStr = dateStr,
                                shiftsOnDate = shiftsOnDate,
                                guardPairs = uiState.guardPairs
                            )
                        }
                    }
                } else if (selectedTabIndex == 1) {
                    // 1. Horizontal Calendar Matrix Section
                    item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
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
                                        .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                                ) {
                                    Column {
                                        // Header Row
                                        Row(
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
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

                                        // Data Rows
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
                                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Worked Public Holidays Compensation Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Celebration, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Worked Public Holidays Compensation",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Text(
                                text = "Field personnel rendering active guard duties on official public holidays accrue compensatory vacation leave automatically credited to their ledger.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Accumulated Vacation Balance:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "%.1f Days".format(uiState.leaveBalance?.remainingVacation ?: 0.0),
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Button(
                                    onClick = { viewModel.creditHoliday(2.0) },
                                    enabled = !uiState.isLoading,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.AddCircle, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Credit Holiday (+2.0d)")
                                }
                            }
                        }
                    }
                }

                // 2.5 Roster Integrity & Conflict Audit Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (uiState.conflictReport?.hasConflicts == true) Icons.Default.Error else if (uiState.conflictReport?.hasWarnings == true) Icons.Default.Warning else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (uiState.conflictReport?.hasConflicts == true) MaterialTheme.colorScheme.error else if (uiState.conflictReport?.hasWarnings == true) MaterialTheme.colorScheme.tertiary else StatusSuccess
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Roster Integrity & Conflict Audit",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        selectedApprovalStation?.let { stId ->
                                            viewModel.detectConflicts(stId)
                                        }
                                    },
                                    enabled = !uiState.rosterConflictsLoading && selectedApprovalStation != null,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    if (uiState.rosterConflictsLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text("Audit Roster")
                                }
                            }

                            val report = uiState.conflictReport
                            if (report == null) {
                                Text(
                                    text = "Select a station and click 'Audit Roster' to scan for double-booking, overstaffing, uncovered posts, or duty cycle violations.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (report.hasConflicts) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "CRITICAL: ${report.totalConflicts} Conflict(s) Detected (Sign-Off Blocked)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        report.conflicts.filter { it.severity == "ERROR" }.forEach { c ->
                                            Text(
                                                text = "• [${c.date}] ${c.message}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            } else if (report.hasWarnings) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Advisory Warnings (${report.conflicts.size}):",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        report.conflicts.forEach { c ->
                                            Text(
                                                text = "• [${c.date}] ${c.message}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }
                                }
                            } else {
                                Surface(
                                    color = StatusSuccess.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Verified, null, tint = StatusSuccess, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Roster Integrity Verified: Zero Conflicts. Exactly 1 Day & 1 Night coverage compliant. Max duty cycles respected.",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = StatusSuccess
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2.8 Examination Periods & Resumption Card
                if (uiState.examinationPeriods.isNotEmpty() || uiState.temporaryAssignments.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
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

                                if (uiState.temporaryAssignments.isNotEmpty()) {
                                    Text(
                                        text = "Temporary Assignment Reallocation Audits (${uiState.temporaryAssignments.size}):",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    uiState.temporaryAssignments.take(3).forEach { ta ->
                                        Text(
                                            text = "• ${ta.guardName} assigned to ${ta.temporaryAssignment} at ${ta.location} (${ta.startDate} to ${ta.endDate})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Supervisor Roster Approval Card
                if (isSupervisor || isAdmin) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = com.example.ui.theme.StatusSuccess)
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
                                    if (uiState.stations.isNotEmpty()) {
                                        var expandedStation by remember { mutableStateOf(false) }
                                        Box(modifier = Modifier.weight(1f)) {
                                            OutlinedButton(
                                                onClick = { expandedStation = true },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                val stName = uiState.stations.firstOrNull { it.id == selectedApprovalStation }?.name ?: "Select Station"
                                                Text(stName, maxLines = 1)
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

                                    OutlinedButton(
                                        onClick = {
                                            selectedApprovalStation?.let { stId ->
                                                viewModel.validateRoster(stId)
                                            }
                                        },
                                        enabled = !uiState.isLoading && selectedApprovalStation != null,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Validate")
                                    }

                                    Button(
                                        onClick = {
                                            showConfirmRosterApproval = true
                                        },
                                        enabled = !uiState.isLoading && selectedApprovalStation != null && uiState.conflictReport?.hasConflicts != true,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Approve")
                                    }
                                }
                            }
                        }
                    }
                }
                } else {
                    // Tab 2: Individual Shifts Header and List
                    item {
                        Text(
                            text = "Scheduled Shift Deployments (${targetOperationalShifts.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                if (uiState.adminLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (targetOperationalShifts.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No roster shifts generated for this station. Tap 'Generate Roster' to calculate rotational duty schedule.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(targetOperationalShifts) { shift ->
                        Column {
                            RosterShiftCard(shift)
                            if (isAdmin) TextButton(onClick = { shiftForReassignment = shift }) { Text("Reassign This Shift") }
                        }
                    }
                }
            }
        }
    }
}

    if (showConfirmRosterApproval) {
        val stationName = uiState.stations.firstOrNull { it.id == selectedApprovalStation }?.name ?: "selected station"
        AlertDialog(
            onDismissRequest = { showConfirmRosterApproval = false },
            title = { Text("Approve roster for deployment?") },
            text = { Text("This formally approves the current rotational schedule for $stationName. Verify the roster and validation results before approval.") },
            confirmButton = {
                TextButton(onClick = {
                    selectedApprovalStation?.let(viewModel::approveRoster)
                    showConfirmRosterApproval = false
                }) { Text("Approve roster") }
            },
            dismissButton = { TextButton(onClick = { showConfirmRosterApproval = false }) { Text("Cancel") } }
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
            onSubmit = { stId, afterDate, cycleDays ->
                viewModel.resumeNormalRoster(stId, afterDate, cycleDays) {
                    showResumeNormalDialog = false
                }
            }
        )
    }
    shiftForReassignment?.let { shift ->
        ReassignSingleShiftDialog(
            shift = shift,
            guards = uiState.users.filter { it.role.equals("GUARD", true) },
            stations = uiState.stations,
            saving = uiState.dutyReassignmentSaving,
            onDismiss = { shiftForReassignment = null },
            onSubmit = { request -> viewModel.reassignSingleShift(shift.id, request) { shiftForReassignment = null } }
        )
    }
}

@Composable
private fun ReassignSingleShiftDialog(
    shift: Shift,
    guards: List<User>,
    stations: List<Station>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ReassignSingleShiftRequest) -> Unit
) {
    var confirmReassignment by remember(shift.id) { mutableStateOf(false) }
    var guardId by remember(shift.id) { mutableStateOf(shift.guard) }
    var stationId by remember(shift.id) { mutableStateOf(shift.station) }
    var reason by remember(shift.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reassign One Shift") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${shift.date} · ${shift.shiftType} · ${shift.stationName}", fontWeight = FontWeight.Bold)
                Text("This changes only this unstarted shift. Past shifts and attendance remain unchanged.", style = MaterialTheme.typography.bodySmall)
                Text("Replacement guard")
                guards.forEach { guard ->
                    TextButton(onClick = { guardId = guard.id }) { Text("${if (guardId == guard.id) "✓ " else ""}${guard.fullName ?: guard.username} · ${guard.employeeNumber.orEmpty()}") }
                }
                Text("Station")
                stations.forEach { station ->
                    TextButton(onClick = { stationId = station.id }) { Text("${if (stationId == station.id) "✓ " else ""}${station.name}") }
                }
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason (required)") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(enabled = !saving && guardId.isNotBlank() && reason.isNotBlank(), onClick = { confirmReassignment = true }) { Text("Review reassignment") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (confirmReassignment) {
        val oldGuard = shift.guardName ?: shift.guard
        val newGuard = guards.firstOrNull { it.id == guardId }?.let { it.fullName ?: it.username } ?: guardId
        val oldStation = shift.stationName
        val newStation = stations.firstOrNull { it.id == stationId }?.name ?: stationId
        AlertDialog(
            onDismissRequest = { confirmReassignment = false },
            title = { Text("Save these changes?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Shift: ${shift.date} · ${shift.shiftType}")
                Text("Guard: $oldGuard → $newGuard")
                Text("Station: $oldStation → $newStation")
                Text("Reason: $reason")
            } },
            confirmButton = { TextButton(enabled = !saving, onClick = { confirmReassignment = false; onSubmit(ReassignSingleShiftRequest(guardId = guardId, stationId = stationId, reason = reason)) }) { Text(if (saving) "Saving…" else "Confirm reassignment") } },
            dismissButton = { TextButton(onClick = { confirmReassignment = false }) { Text("Back") } }
        )
    }
}

data class CalendarMatrixRow(
    val date: String,
    val dayOfWeek: String,
    val dayCount: Int,
    val nightCount: Int,
    val toCount: Int,
    val vacGuards: List<String>,
    val occGuards: List<String>,
    val sickGuards: List<String>
)

@Composable
fun RosterShiftCard(shift: Shift) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${shift.guardName} (${shift.employeeNumber ?: "SEC"})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (shift.assignmentType != "NORMAL" && shift.assignmentType != "TIME_OFF") {
                        Surface(
                            color = when (shift.assignmentType) {
                                "EXAM" -> MaterialTheme.colorScheme.secondaryContainer
                                "ESCORT" -> MaterialTheme.colorScheme.tertiaryContainer
                                "RELIEF" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = when (shift.assignmentType) {
                                    "EXAM" -> "EXAM VENUE"
                                    "ESCORT" -> "ESCORT"
                                    "RELIEF" -> "RELIEF"
                                    else -> shift.assignmentType
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (shift.assignmentType) {
                                    "EXAM" -> MaterialTheme.colorScheme.onSecondaryContainer
                                    "ESCORT" -> MaterialTheme.colorScheme.onTertiaryContainer
                                    "RELIEF" -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        color = when (shift.shiftType) {
                            "DAY" -> MaterialTheme.colorScheme.primaryContainer
                            "NIGHT" -> MaterialTheme.colorScheme.inversePrimary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${shift.shiftType} SHIFT",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (shift.shiftType) {
                                "DAY" -> MaterialTheme.colorScheme.onPrimaryContainer
                                "NIGHT" -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Text("Date: ${shift.date} • Hours: ${shift.startTime} – ${shift.endTime}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(
                text = "Station: ${shift.stationName} • Partner: ${shift.partnerName ?: "Solo"} • Location: ${shift.dutyLocation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun GenerateRosterDialog(
    stations: List<com.example.data.model.Station>,
    guardPairs: List<com.example.data.model.GuardPair> = emptyList(),
    onDismiss: () -> Unit,
    onSubmit: (stationId: String, startDate: String, cycleDays: Int, mode: String, examPeriodId: String?, examVenueName: String?, examGuardIds: List<String>) -> Unit
) {
    val harareTz = remember { java.util.TimeZone.getTimeZone("Africa/Harare") }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz } }
    val today = remember { dateFormat.format(Date()) }

    var selectedStationId by remember { mutableStateOf(stations.firstOrNull()?.id ?: "") }
    var startDate by remember { mutableStateOf(today) }
    var cycleDays by remember { mutableStateOf("12") }
    var mode by remember { mutableStateOf("NORMAL") } // "NORMAL" or "EXAM"
    var examPeriodName by remember { mutableStateOf("Examination Period") }
    var examVenueName by remember { mutableStateOf("Examination Venue") }
    var selectedPairId by remember { mutableStateOf<String?>(null) }

    val stationPairs = remember(guardPairs, selectedStationId) {
        guardPairs.filter { it.station == selectedStationId }
    }

    LaunchedEffect(stations) {
        if (selectedStationId.isBlank() && stations.isNotEmpty()) {
            selectedStationId = stations.first().id
        }
    }

    LaunchedEffect(stationPairs) {
        if (selectedPairId == null && stationPairs.isNotEmpty()) {
            selectedPairId = stationPairs.first().id
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == "EXAM") "Generate Examination Duty Roster" else "Generate Automated Duty Roster") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Operational Mode:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == "NORMAL",
                        onClick = {
                            mode = "NORMAL"
                            cycleDays = "12"
                        },
                        label = { Text("Normal (4-Day Rotating)") }
                    )
                    FilterChip(
                        selected = mode == "EXAM",
                        onClick = {
                            mode = "EXAM"
                            cycleDays = "5"
                        },
                        label = { Text("Examination (5-Day Venue)") }
                    )
                }

                if (mode == "NORMAL") {
                    Text(
                        text = "Calculates 4-day Day, 4-day Night, 4-day Rest rotation blocks for 3 guard pairs with alternating Day/Night shift swap on each return.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Allocates 2 Day guards (07:00-18:00) to Exam Venue for 5 consecutive days. Main campus remains 24/7 covered via automated relief substitution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("Cycle Start Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = cycleDays,
                    onValueChange = { cycleDays = it },
                    label = { Text("Cycle Duration (Days)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mode == "EXAM") {
                    OutlinedTextField(
                        value = examPeriodName,
                        onValueChange = { examPeriodName = it },
                        label = { Text("Examination Period Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = examVenueName,
                        onValueChange = { examVenueName = it },
                        label = { Text("Exam Venue Name / Location") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (stationPairs.isNotEmpty()) {
                        Text("Select Pair for Exam Venue Duty (5 Days Day):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        stationPairs.forEach { p ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selectedPairId == p.id) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedPairId == p.id,
                                        onClick = { selectedPairId = p.id }
                                    )
                                    val pairDesc = if (!p.guardAName.isNullOrBlank() && !p.guardBName.isNullOrBlank()) {
                                        "${p.guardAName} & ${p.guardBName}"
                                    } else {
                                        "Guard #${p.guardA.take(6)} & Guard #${p.guardB.take(6)}"
                                    }
                                    Text("Pair #${p.rotationOrder ?: p.order}: $pairDesc", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                if (stations.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "No operational stations found. A station with active guard pairs must be configured first.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                } else {
                    Text("Select Station:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    stations.forEach { st ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedStationId == st.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedStationId == st.id,
                                    onClick = { selectedStationId = st.id }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(st.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    if (!st.code.isNullOrBlank()) {
                                        Text("Code: ${st.code}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedStationId.isNotBlank() && startDate.isNotBlank()) {
                        val examGuardIds = if (mode == "EXAM" && selectedPairId != null) {
                            val chosenPair = stationPairs.firstOrNull { it.id == selectedPairId }
                            listOfNotNull(chosenPair?.guardA, chosenPair?.guardB)
                        } else {
                            emptyList()
                        }
                        onSubmit(
                            selectedStationId,
                            startDate.trim(),
                            cycleDays.toIntOrNull() ?: (if (mode == "EXAM") 5 else 12),
                            mode,
                            null,
                            if (mode == "EXAM") examVenueName.trim() else null,
                            examGuardIds
                        )
                    }
                },
                enabled = selectedStationId.isNotBlank() && startDate.isNotBlank()
            ) {
                Text(if (mode == "EXAM") "Generate Exam Schedule" else "Generate Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ResumeNormalRosterDialog(
    stations: List<com.example.data.model.Station>,
    onDismiss: () -> Unit,
    onSubmit: (stationId: String, afterDate: String, cycleDays: Int) -> Unit
) {
    val harareTz = remember { java.util.TimeZone.getTimeZone("Africa/Harare") }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz } }
    val today = remember { dateFormat.format(Date()) }

    var selectedStationId by remember { mutableStateOf(stations.firstOrNull()?.id ?: "") }
    var afterDate by remember { mutableStateOf(today) }
    var cycleDays by remember { mutableStateOf("12") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume Normal Rotational Roster") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Conclude active examination period and restore the regular 4-day rotating cycle across all 3 pairs with alternating Day/Night shift swap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = afterDate,
                    onValueChange = { afterDate = it },
                    label = { Text("Resumption Effective Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = cycleDays,
                    onValueChange = { cycleDays = it },
                    label = { Text("Resumption Duration (Days)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (stations.isNotEmpty()) {
                    Text("Station:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    stations.forEach { st ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedStationId == st.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedStationId == st.id,
                                    onClick = { selectedStationId = st.id }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(st.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedStationId.isNotBlank() && afterDate.isNotBlank()) {
                        onSubmit(selectedStationId, afterDate.trim(), cycleDays.toIntOrNull() ?: 12)
                    }
                },
                enabled = selectedStationId.isNotBlank() && afterDate.isNotBlank()
            ) {
                Text("Confirm Resumption")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun DateOperationalMatrixCard(
    dateStr: String,
    shiftsOnDate: List<Shift>,
    guardPairs: List<GuardPair> = emptyList()
) {
    val dayShifts = shiftsOnDate.filter { it.shiftType == "DAY" && it.assignmentType != "TIME_OFF" }
    val nightShifts = shiftsOnDate.filter { it.shiftType == "NIGHT" && it.assignmentType != "TIME_OFF" }
    val timeOffShifts = shiftsOnDate.filter { it.shiftType == "OFF" || it.shiftType == "REST" || it.assignmentType == "TIME_OFF" }
    val specialShifts = shiftsOnDate.filter { it.assignmentType in listOf("EXAM_ESCORT", "ESCORT") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Date Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dateStr,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    val stName = shiftsOnDate.firstOrNull()?.stationName ?: "Station"
                    Text(
                        text = stName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // Day Shift Section
            OperationalShiftBlock(
                title = "DAY SHIFT (07:00 – 18:00)",
                badgeText = "DAY DUTY",
                badgeColor = GoldAccent,
                icon = Icons.Default.WbSunny,
                shifts = dayShifts
            )

            // Night Shift Section
            OperationalShiftBlock(
                title = "NIGHT SHIFT (18:00 – 07:00)",
                badgeText = "NIGHT DUTY",
                badgeColor = MaterialTheme.colorScheme.secondary,
                icon = Icons.Default.Nightlight,
                shifts = nightShifts
            )

            // Time Off Section
            OperationalShiftBlock(
                title = "SCHEDULED TIME-OFF (REST)",
                badgeText = "TIME OFF",
                badgeColor = MaterialTheme.colorScheme.outline,
                icon = Icons.Default.Bedtime,
                shifts = timeOffShifts
            )

            if (specialShifts.isNotEmpty()) {
                OperationalShiftBlock(
                    title = "SPECIAL ESCORT / EXAM DUTIES",
                    badgeText = "SPECIAL DUTY",
                    badgeColor = MaterialTheme.colorScheme.error,
                    icon = Icons.Default.DirectionsCar,
                    shifts = specialShifts
                )
            }
        }
    }
}

@Composable
fun OperationalShiftBlock(
    title: String,
    badgeText: String,
    badgeColor: androidx.compose.ui.graphics.Color,
    icon: ImageVector,
    shifts: List<Shift>
) {
    Surface(
        color = badgeColor.copy(alpha = 0.08f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (shifts.isEmpty()) {
                Text(
                    text = "No guards scheduled in this cycle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                shifts.forEach { s ->
                    val guardName = s.guardName
                    val partnerStr = if (!s.partnerName.isNullOrBlank()) " • Partner: ${s.partnerName}" else ""
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "• $guardName$partnerStr",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (s.attendanceStatus.isNotBlank()) {
                            AttendanceStatusPill(status = s.attendanceStatus)
                        }
                    }
                }
            }
        }
    }
}
