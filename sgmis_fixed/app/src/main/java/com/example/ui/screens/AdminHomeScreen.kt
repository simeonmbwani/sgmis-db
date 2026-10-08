package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.navigation.NavRoutes
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisUiState
import com.example.ui.viewmodel.SgmisViewModel
import kotlinx.coroutines.delay

/**
 * Administrator National Control Center Experience (Blueprint Phase 12.6 & Phase 6 Core Redesign).
 * Authoritatively answers within seconds:
 * 1. What is the national security & system status?
 * 2. Are all stations adequately staffed & compliant?
 * 3. Are there critical security alerts or system emergencies?
 * 4. Are there roster scheduling overlaps or compliance violations?
 * 5. What governance reviews require executive decision right now?
 */
@Composable
fun AdminHomeScreen(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState,
    todayStr: String,
    todayFormatted: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val adminDash = uiState.adminDashboard
    val totalGuards = adminDash?.totalGuards ?: uiState.users.count { it.role == "GUARD" }
    val totalStations = uiState.stations.size
    val activeStations = adminDash?.totalStations ?: uiState.stations.size
    val guardsOnDuty = adminDash?.activeDuties ?: uiState.attendanceRecords.count { it.clockIn != null && it.clockOut == null }
    val guardsOnLeave = adminDash?.guardsOnLeave ?: uiState.leaveApplications.count {
        it.status.uppercase() == "APPROVED" && (todayStr.isEmpty() || (it.startDate <= todayStr && it.endDate >= todayStr))
    }
    val activePatrols = adminDash?.activePatrols ?: uiState.patrolLogs.count { it.status == "IN_PROGRESS" }
    val openIncidents = adminDash?.openIncidents ?: uiState.incidents.count { it.status != "RESOLVED" }
    val attendanceRate = adminDash?.attendanceRate ?: 0.0
    val pendingLeave = uiState.leaveApplications.count { it.status == "PENDING" }
    val stationConflicts = uiState.conflictReport?.totalConflicts ?: 0
    val pendingAdjustments = uiState.recordAdjustments.count { it.status == "PENDING" }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ==============================================================
        // LEVEL 1: NATIONAL CONTROL CENTER EXECUTIVE HEADER
        // ==============================================================
        SgmisStatusCard(
            statusColor = NavyDark
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = StatusSuccess,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "● SYSTEM OPERATIONAL",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = todayFormatted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondaryLight
                    )
                }

                Column {
                    Text(
                        text = "National Control Center",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimaryLight
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Zimbabwe Security Guard Management Information System",
                        fontSize = 12.sp,
                        color = TextSecondaryLight
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Executive Governance • All $activeStations Operational Stations Monitored",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = NavyDark
                    )
                }
            }
        }

        // Live Telemetry Strip
        if (adminDash?.liveOps?.isNotEmpty() == true) {
            SmartLiveOpsStrip(items = adminDash.liveOps)
        }

        // ==============================================================
        // LEVEL 1 & 2: NATIONAL COMMAND TELEMETRY METRICS
        // ==============================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "National Command Telemetry",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardMetricTile(
                    label = "Total Force",
                    value = "$totalGuards",
                    caption = "$activeStations Stations",
                    icon = Icons.Default.People,
                    statusColor = NavyDark,
                    onClick = { onNavigate(NavRoutes.USER_MANAGEMENT) },
                    modifier = Modifier.weight(1f)
                )

                DashboardMetricTile(
                    label = "Active Duties",
                    value = "$guardsOnDuty",
                    caption = "${attendanceRate.toInt()}% attendance",
                    icon = Icons.Default.Shield,
                    statusColor = StatusSuccess,
                    onClick = { onNavigate(NavRoutes.REPORTS) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardMetricTile(
                    label = "Live Patrols",
                    value = "$activePatrols",
                    caption = "Routes in progress",
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    statusColor = NavyDark,
                    onClick = { onNavigate(NavRoutes.ADMIN_AUDIT_HUB) },
                    modifier = Modifier.weight(1f)
                )

                DashboardMetricTile(
                    label = "Open Incidents",
                    value = "$openIncidents",
                    caption = "Across all centres",
                    icon = Icons.Default.ReportProblem,
                    statusColor = if (openIncidents > 0) StatusWarning else StatusSuccess,
                    onClick = { onNavigate(NavRoutes.INCIDENTS) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardMetricTile(
                    label = "Roster Conflicts",
                    value = "$stationConflicts",
                    caption = if (stationConflicts > 0) "Overlap detected" else "Zero overlaps",
                    icon = Icons.Default.CalendarMonth,
                    statusColor = if (stationConflicts > 0) StatusError else StatusSuccess,
                    onClick = { onNavigate(NavRoutes.ROSTER_MANAGEMENT) },
                    modifier = Modifier.weight(1f)
                )

                DashboardMetricTile(
                    label = "Pending Adjustments",
                    value = "$pendingAdjustments",
                    caption = "Awaiting approval",
                    icon = Icons.Default.HistoryEdu,
                    statusColor = if (pendingAdjustments > 0) StatusWarning else StatusSuccess,
                    onClick = { onNavigate(NavRoutes.RECORD_ADJUSTMENTS) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ==============================================================
        // LEVEL 2: CRITICAL ALERTS & GOVERNANCE EXCEPTIONS
        // ==============================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Governance Alerts & Exceptions",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )

            var hasExceptions = false

            // 1. Roster conflicts
            if (stationConflicts > 0) {
                hasExceptions = true
                SgmisStatusCard(statusColor = StatusError) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Roster Scheduling Overlap Detected",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusError
                        )
                        Text(
                            text = "$stationConflicts schedule conflict(s) detected across station rosters. Invariant requires zero-overlap before national validation.",
                            fontSize = 12.sp,
                            color = TextPrimaryLight
                        )
                        SgmisPrimaryButton(
                            text = "Review Conflicted Rosters",
                            onClick = { onNavigate(NavRoutes.ROSTER_MANAGEMENT) },
                            minHeight = 40.dp
                        )
                    }
                }
            }

            // 2. Pending Record Adjustments
            if (pendingAdjustments > 0) {
                hasExceptions = true
                SgmisStatusCard(statusColor = StatusWarning) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Pending Record Adjustment Requests",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusWarning
                        )
                        Text(
                            text = "$pendingAdjustments attendance or clock-in adjustment request(s) require executive administrator review and sign-off.",
                            fontSize = 12.sp,
                            color = TextPrimaryLight
                        )
                        SgmisSecondaryButton(
                            text = "Inspect Adjustments Queue",
                            onClick = { onNavigate(NavRoutes.RECORD_ADJUSTMENTS) },
                            minHeight = 40.dp
                        )
                    }
                }
            }

            // Fallback: National posture clear
            if (!hasExceptions) {
                SgmisStatusCard(statusColor = StatusSuccess) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "National Governance Posture Healthy • Zero active conflicts",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryLight
                        )
                    }
                }
            }
        }

        // ==============================================================
        // LEVEL 3: NATIONAL ADMINISTRATION & GOVERNANCE MODULES
        // ==============================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "National Administration & Governance",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )

            val adminActions = listOf(
                BlueprintAction("Roster Governance", "Approve & validate rosters", Icons.Default.CalendarMonth, NavRoutes.ROSTER_MANAGEMENT, "nav_roster"),
                BlueprintAction("Station Infrastructure", "Manage post geofences", Icons.Default.Business, NavRoutes.STATION_MANAGEMENT, "nav_stations"),
                BlueprintAction("Personnel & Users", "Officer credentials & roles", Icons.Default.People, NavRoutes.USER_MANAGEMENT, "nav_users"),
                BlueprintAction("Audit Ledger", "Immutable security audit", Icons.Default.HistoryEdu, NavRoutes.ADMIN_HISTORY, "nav_history"),
                BlueprintAction("Record Adjustments", "Supervised clock corrections", Icons.Default.Tune, NavRoutes.RECORD_ADJUSTMENTS, "nav_adjustments"),
                BlueprintAction("Master Tools", "System diagnostics & cleanup", Icons.Default.Build, NavRoutes.ADMIN_MASTER_TOOLS, "nav_master_tools"),
                BlueprintAction("Organization Policy", "Standard operating procedures", Icons.Default.Policy, NavRoutes.ORGANIZATION_POLICY, "nav_policy"),
                BlueprintAction("Operational Reports", "Audit exports & billing", Icons.Default.Assessment, NavRoutes.REPORTS, "nav_reports")
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (pair in adminActions.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (action in pair) {
                            BlueprintActionCard(
                                action = action,
                                onClick = { onNavigate(action.route) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ==============================================================
        // LEVEL 3 / 4: SPECIALIZED GOVERNANCE OVERSIGHT CARDS
        // ==============================================================
        MultiCentreMonitoringCard(
            stations = uiState.stations,
            attendanceRecords = uiState.attendanceRecords,
            rosterShifts = uiState.rosterShifts,
            incidents = uiState.incidents,
            patrolLogs = uiState.patrolLogs,
            todayStr = todayStr,
            onNavigate = onNavigate
        )

        NationalRosterOversightCard(
            rosterShifts = uiState.rosterShifts,
            conflictReport = uiState.conflictReport,
            stations = uiState.stations,
            handovers = uiState.handovers,
            todayStr = todayStr,
            onNavigate = onNavigate
        )

        ZimbabwePublicHolidayControlCard(
            viewModel = viewModel,
            uiState = uiState
        )

        EarlyClockOutAuthorizationCard(
            viewModel = viewModel,
            uiState = uiState
        )
    }
}

@Composable
private fun MultiCentreMonitoringCard(
    stations: List<Station>,
    attendanceRecords: List<Attendance>,
    rosterShifts: List<Shift>,
    incidents: List<IncidentReport>,
    patrolLogs: List<PatrolLog>,
    todayStr: String,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("multi_centre_monitoring_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = BorderStroke(1.dp, BorderSubtleLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Business,
                        contentDescription = null,
                        tint = NavyDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MULTI-CENTRE STATIONS (${stations.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavyDark
                    )
                }
                TextButton(onClick = { onNavigate(NavRoutes.STATION_MANAGEMENT) }) {
                    Text("MANAGE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NavyDark)
                }
            }

            if (stations.isEmpty()) {
                Text(
                    text = "No stations registered in the national system.",
                    fontSize = 12.sp,
                    color = TextSecondaryLight
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stations.take(5).forEach { station ->
                        val stationGuardsOnPost = attendanceRecords.count {
                            (it.stationName.equals(station.name, ignoreCase = true) || it.stationName.contains(station.name, ignoreCase = true)) &&
                            it.clockIn != null && it.clockOut == null
                        }
                        val stationOpenIncidents = incidents.count {
                            (it.station.isBlank() || it.station == station.id || it.stationName.equals(station.name, ignoreCase = true)) &&
                            it.status != "RESOLVED"
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = LightBackground,
                            border = BorderStroke(1.dp, BorderSubtleLight),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = station.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                                    Text(
                                        text = "Radius: ${station.effectiveRadius.toInt()}m • ${station.address ?: "Station"}",
                                        fontSize = 11.sp,
                                        color = TextSecondaryLight
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        color = StatusSuccess.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "$stationGuardsOnPost On Post",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusSuccess,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (stationOpenIncidents > 0) {
                                        Surface(
                                            color = StatusError.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "$stationOpenIncidents Alert",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = StatusError,
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
        }
    }
}

@Composable
private fun NationalRosterOversightCard(
    rosterShifts: List<Shift>,
    conflictReport: ConflictReport?,
    stations: List<Station>,
    handovers: List<ShiftHandover> = emptyList(),
    todayStr: String = "",
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("national_roster_oversight_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = BorderStroke(1.dp, BorderSubtleLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = NavyDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NATIONAL ROSTER OVERSIGHT",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavyDark
                    )
                }
                Surface(
                    color = NavyDark.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${rosterShifts.size} Shifts Active",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavyDark,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Conflict Status Banner
            if (conflictReport?.hasConflicts == true) {
                Surface(
                    color = StatusWarning.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StatusWarning),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = StatusError, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "⚠ ${conflictReport.totalConflicts} roster conflicts detected across national stations.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusError
                        )
                    }
                }
            } else {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Zero Overlap / Compliant Roster Schedules Across All Stations",
                            fontSize = 11.sp,
                            color = StatusSuccess,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZimbabwePublicHolidayControlCard(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("zimbabwe_public_holiday_control_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = BorderStroke(1.dp, BorderSubtleLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Event,
                        contentDescription = null,
                        tint = NavyDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ZIMBABWE PUBLIC HOLIDAY CONTROL",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavyDark
                    )
                }
                Surface(
                    color = GoldAccent.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Act (Cap 10:21)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavyDark,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Security guards working on statutory Zimbabwean public holidays receive 2.0 days compensatory leave credited automatically upon Administrator authorization.",
                fontSize = 12.sp,
                color = TextSecondaryLight
            )

            val pendingHolidays = uiState.holidayDutyRecords.filter { it.status == "PENDING" }
            if (pendingHolidays.isNotEmpty()) {
                Surface(
                    color = StatusWarning.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Pending, null, tint = StatusWarning, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${pendingHolidays.size} Holiday Duty Records pending compensatory review.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }
                }
            } else {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "All Zimbabwean public holiday duty records reviewed and settled.",
                        fontSize = 11.sp,
                        color = StatusSuccess,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EarlyClockOutAuthorizationCard(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState
) {
    val activeGuards = remember(uiState.attendanceRecords) {
        uiState.attendanceRecords.filter { it.clockIn != null && it.clockOut == null }
    }

    var selectedShiftId by remember { mutableStateOf("") }
    var overrideReason by remember { mutableStateOf("") }

    LaunchedEffect(activeGuards) {
        if (selectedShiftId.isBlank() && activeGuards.isNotEmpty()) {
            selectedShiftId = activeGuards.first().shift
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("early_clockout_authorization_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = BorderStroke(1.dp, BorderSubtleLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = NavyDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EARLY CLOCK-OUT AUTHORIZATION",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavyDark
                    )
                }
                Surface(
                    color = NavyDark.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "5-Min OTP Engine",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavyDark,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Issue server-authoritative 6-digit cryptographic OTP to authorize early departure. OTP expires strictly in 5 minutes and is single-use.",
                fontSize = 12.sp,
                color = TextSecondaryLight
            )

            val activeOtp = uiState.activeEarlyClockoutOtp
            if (activeOtp != null) {
                var secondsLeft by remember(activeOtp) { mutableIntStateOf(activeOtp.expiresInSeconds.coerceAtLeast(300)) }
                LaunchedEffect(activeOtp) {
                    while (secondsLeft > 0) {
                        delay(1000)
                        secondsLeft--
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = NavyDark.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, NavyDark.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CRYPTOGRAPHIC OTP CODE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NavyDark
                            )
                            Surface(
                                color = if (secondsLeft > 60) StatusSuccess else StatusError,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = activeOtp.otp,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = NavyDark,
                            letterSpacing = 4.sp
                        )

                        Text(
                            text = "Guard: ${activeOtp.guardName ?: activeOtp.guardUsername ?: "Personnel"} • ${activeOtp.stationName ?: "Station"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )

                        OutlinedButton(
                            onClick = { viewModel.clearActiveOtp() },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                        ) {
                            Text("DISMISS CODE", fontSize = 11.sp)
                        }
                    }
                }
            } else {
                if (activeGuards.isEmpty()) {
                    Surface(
                        color = LightBackground,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No guards currently clocked in on active duty across national stations.",
                            fontSize = 12.sp,
                            color = TextSecondaryLight,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Select Guard on Active Duty (${activeGuards.size} available):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )

                        activeGuards.take(4).forEach { att ->
                            val isSelected = att.shift == selectedShiftId
                            Surface(
                                onClick = { selectedShiftId = att.shift },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) NavyDark.copy(alpha = 0.08f) else LightBackground,
                                border = if (isSelected) BorderStroke(1.dp, NavyDark) else BorderStroke(1.dp, BorderSubtleLight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(text = att.guardName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                                        Text(
                                            text = "${att.stationName} • In: ${att.clockIn?.take(16) ?: "Active"}",
                                            fontSize = 11.sp,
                                            color = TextSecondaryLight
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Default.CheckCircle, null, tint = NavyDark, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = overrideReason,
                            onValueChange = { overrideReason = it },
                            label = { Text("Mandatory Justification / Reason *") },
                            placeholder = { Text("e.g. Medical emergency, urgent relief, authorized family matter") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        SgmisPrimaryButton(
                            text = if (uiState.isGeneratingOtp) "GENERATING CRYPTOGRAPHIC OTP..." else "GENERATE 5-MINUTE AUTHORIZATION OTP",
                            onClick = {
                                if (selectedShiftId.isNotBlank() && overrideReason.isNotBlank()) {
                                    viewModel.generateEarlyClockoutOtp(selectedShiftId, overrideReason)
                                }
                            },
                            enabled = selectedShiftId.isNotBlank() && overrideReason.isNotBlank() && !uiState.isGeneratingOtp,
                            isLoading = uiState.isGeneratingOtp,
                            leadingIcon = Icons.Default.VpnKey,
                            minHeight = 44.dp
                        )
                    }
                }
            }
        }
    }
}
