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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.User
import com.example.ui.components.*
import com.example.ui.navigation.NavRoutes
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisUiState
import com.example.ui.viewmodel.SgmisViewModel

/**
 * Supervisor Command Console Experience (Blueprint Phase 12.5 & Phase 6 Core Redesign).
 * Authoritatively answers within seconds:
 * 1. Is my station adequately covered?
 * 2. Who is on duty right now?
 * 3. Are there attendance or missing guard problems?
 * 4. Are there roster or shift gaps?
 * 5. What needs my immediate supervisor attention?
 * 6. What operational logs require review?
 */
@Composable
fun SupervisorHomeScreen(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState,
    todayStr: String,
    todayFormatted: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val stationId = uiState.currentStationId
    val stationName = uiState.currentStationName
    val hasStation = uiState.hasAssignedStation

    // Station-scoped datasets
    val stationAttendance = remember(uiState.attendanceRecords, stationName, todayStr, hasStation) {
        if (!hasStation) emptyList()
        else uiState.attendanceRecords.filter {
            (it.shiftDate == todayStr || it.shiftDate.isBlank()) &&
            (it.stationName.equals(stationName, ignoreCase = true) || it.stationName.contains(stationName, ignoreCase = true))
        }
    }

    val todayStationShifts = remember(uiState.rosterShifts, stationId, stationName, todayStr, hasStation) {
        if (!hasStation) emptyList()
        else uiState.rosterShifts.filter {
            it.date == todayStr &&
            it.shiftType.uppercase() != "OFF" &&
            it.assignmentType.uppercase() != "TIME_OFF" &&
            (it.station == stationId || it.stationName.equals(stationName, ignoreCase = true))
        }
    }

    val serverShift = remember(uiState.todayShift, todayStationShifts) {
        uiState.todayShift?.takeIf { it.shiftType.uppercase() != "OFF" && it.assignmentType.uppercase() != "TIME_OFF" }
            ?: todayStationShifts.firstOrNull()
    }

    val activeShiftBadge = remember(serverShift) {
        computeSupervisorShiftBadge(serverShift)
    }

    val configuredRadius = uiState.stationGeofenceRadius
    val geofenceText = remember(hasStation, configuredRadius) {
        computeSupervisorGeofenceText(hasStation, configuredRadius)
    }

    val stationOB = remember(uiState.obEntries, stationId, stationName, hasStation) {
        if (!hasStation) emptyList()
        else uiState.obEntries.filter {
            it.station.isBlank() || it.station == stationId || it.stationName.equals(stationName, ignoreCase = true)
        }
    }

    val stationVisitors = remember(uiState.visitors, stationId, stationName, hasStation) {
        if (!hasStation) emptyList()
        else uiState.visitors.filter {
            it.station.isBlank() || it.station == stationId || it.stationName.equals(stationName, ignoreCase = true)
        }
    }

    val stationIncidents = remember(uiState.incidents, stationId, stationName, hasStation) {
        if (!hasStation) emptyList()
        else uiState.incidents.filter {
            it.station.isBlank() || it.station == stationId || it.stationName.equals(stationName, ignoreCase = true)
        }
    }

    val stationPatrols = remember(uiState.patrolLogs, stationId, stationName, hasStation) {
        if (!hasStation) emptyList()
        else uiState.patrolLogs.filter {
            it.station.isBlank() || it.station == stationId || it.stationName.equals(stationName, ignoreCase = true)
        }
    }

    val supervisorDashboard = uiState.supervisorDashboard

    // Live Metrics
    val guardsClockedIn = supervisorDashboard?.guardsOnPost ?: stationAttendance.count { it.clockIn != null && it.clockOut == null }
    val guardsExpected = todayStationShifts.size
    val guardsAvailable = supervisorDashboard?.guardsAvailable ?: (guardsExpected - guardsClockedIn).coerceAtLeast(0)
    val guardsOnLeave = supervisorDashboard?.guardsOnLeave ?: uiState.leaveApplications.count { it.status == "PENDING" }
    val activeVisitorsCount = stationVisitors.count {
        !it.occurrenceText.contains("Time Out:", ignoreCase = true) && !it.occurrenceText.contains("CHECKED OUT", ignoreCase = true)
    }
    val openIncidentsCount = supervisorDashboard?.openIncidents ?: stationIncidents.count { it.status != "RESOLVED" }
    val criticalIncidentsCount = stationIncidents.count {
        it.status != "RESOLVED" && it.priority.uppercase() in listOf("HIGH", "CRITICAL", "URGENT")
    }
    val activePatrolsCount = supervisorDashboard?.activePatrols ?: stationPatrols.count { it.status == "IN_PROGRESS" }
    val pendingLeaveCount = guardsOnLeave
    val attendanceRate = supervisorDashboard?.attendanceRate ?: if (guardsExpected > 0) (guardsClockedIn.toDouble() / guardsExpected * 100) else 100.0

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ==============================================================
        // LEVEL 1: STATION STATUS & OPERATIONAL POSTURE
        // ==============================================================
        SgmisStatusCard(
            statusColor = if (!hasStation) StatusWarning else NavyDark
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (hasStation) StatusSuccess else StatusWarning,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (hasStation) "● STATION OPERATIONAL" else "▲ STATION UNASSIGNED",
                                color = if (hasStation) Color.White else Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
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
                        text = if (hasStation) stationName else "No Station Assigned",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimaryLight
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = activeShiftBadge,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NavyDark
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = geofenceText,
                        fontSize = 11.sp,
                        color = TextSecondaryLight
                    )
                }
            }
        }

        // Live Telemetry Strip
        if (supervisorDashboard?.liveOps?.isNotEmpty() == true) {
            SmartLiveOpsStrip(items = supervisorDashboard.liveOps)
        }

        // ==============================================================
        // LEVEL 1 & 2: STATION TELEMETRY METRICS
        // ==============================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Station Staffing & Telemetry",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardMetricTile(
                    label = "On Duty",
                    value = "$guardsClockedIn / $guardsExpected",
                    caption = "${attendanceRate.toInt()}% attendance",
                    icon = Icons.Default.Shield,
                    statusColor = if (guardsClockedIn >= guardsExpected && guardsExpected > 0) StatusSuccess else StatusWarning,
                    onClick = { onNavigate(NavRoutes.ATTENDANCE_MANAGEMENT) },
                    modifier = Modifier.weight(1f)
                )

                DashboardMetricTile(
                    label = "Available Relief",
                    value = "$guardsAvailable",
                    caption = "Off-duty guards",
                    icon = Icons.Default.People,
                    statusColor = NavyDark,
                    onClick = { onNavigate(NavRoutes.RECORD_ADJUSTMENTS) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardMetricTile(
                    label = "Active Patrols",
                    value = "$activePatrolsCount",
                    caption = "Routes in progress",
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    statusColor = NavyDark,
                    onClick = { onNavigate(NavRoutes.PATROL) },
                    modifier = Modifier.weight(1f)
                )

                DashboardMetricTile(
                    label = "Open Incidents",
                    value = "$openIncidentsCount",
                    caption = if (criticalIncidentsCount > 0) "$criticalIncidentsCount critical" else "No critical",
                    icon = Icons.Default.ReportProblem,
                    statusColor = if (criticalIncidentsCount > 0) StatusError else TextPrimaryLight,
                    onClick = { onNavigate(NavRoutes.INCIDENTS) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ==============================================================
        // LEVEL 2: WHAT NEEDS ATTENTION (SUPERVISOR ACTION QUEUE)
        // ==============================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "What Needs Attention",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )

            var hasAlerts = false

            // 1. Understaffed shift warning
            if (guardsExpected > 0 && guardsClockedIn < guardsExpected) {
                hasAlerts = true
                SgmisStatusCard(statusColor = StatusWarning) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Understaffed Shift Alert",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusWarning
                        )
                        Text(
                            text = "${guardsExpected - guardsClockedIn} guard(s) scheduled for this shift have not clocked in. Station coverage is below roster requirements.",
                            fontSize = 12.sp,
                            color = TextPrimaryLight
                        )
                        SgmisSecondaryButton(
                            text = "Verify Roll Call & Attendance",
                            onClick = { onNavigate(NavRoutes.ATTENDANCE_MANAGEMENT) },
                            minHeight = 40.dp
                        )
                    }
                }
            }

            // 2. Pending leave requests
            if (pendingLeaveCount > 0) {
                hasAlerts = true
                SgmisStatusCard(statusColor = StatusWarning) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Pending Leave Applications",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusWarning
                        )
                        Text(
                            text = "$pendingLeaveCount guard leave application(s) awaiting your supervisor review.",
                            fontSize = 12.sp,
                            color = TextPrimaryLight
                        )
                        SgmisSecondaryButton(
                            text = "Review Leave Queue",
                            onClick = { onNavigate(NavRoutes.LEAVE) },
                            minHeight = 40.dp
                        )
                    }
                }
            }

            // 3. Critical incidents
            if (criticalIncidentsCount > 0) {
                hasAlerts = true
                SgmisStatusCard(statusColor = StatusError) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Critical Incident Alert",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusError
                        )
                        Text(
                            text = "$criticalIncidentsCount high-priority or urgent security incident(s) require immediate supervisor intervention.",
                            fontSize = 12.sp,
                            color = TextPrimaryLight
                        )
                        SgmisPrimaryButton(
                            text = "Open Incident Command",
                            onClick = { onNavigate(NavRoutes.INCIDENTS) },
                            minHeight = 40.dp
                        )
                    }
                }
            }

            // 4. Station coverage warning from server
            val coverage = uiState.stationCoverage
            if (coverage != null && coverage.coverageWarning) {
                hasAlerts = true
                SgmisStatusCard(statusColor = StatusWarning) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Roster Coverage Deficit",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusWarning
                        )
                        Text(
                            text = coverage.warningMessage ?: "Scheduled duty coverage is below required station levels.",
                            fontSize = 12.sp,
                            color = TextPrimaryLight
                        )
                        SgmisSecondaryButton(
                            text = "Manage Duty Roster",
                            onClick = { onNavigate(NavRoutes.ROSTER_MANAGEMENT) },
                            minHeight = 40.dp
                        )
                    }
                }
            }

            // Fallback: All clear
            if (!hasAlerts) {
                SgmisStatusCard(statusColor = StatusSuccess) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "All Station Posts Operational • No pending exceptions",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryLight
                        )
                    }
                }
            }
        }

        // ==============================================================
        // LEVEL 3: QUICK SUPERVISOR ACTIONS
        // ==============================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Supervisor Operations",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )

            val supervisorActions = listOf(
                BlueprintAction("Roster Management", "Validate shifts & cycles", Icons.Default.CalendarMonth, NavRoutes.ROSTER_MANAGEMENT, "nav_roster"),
                BlueprintAction("Roll Call / Attendance", "Verify post clock-ins", Icons.Default.AssignmentTurnedIn, NavRoutes.ATTENDANCE_MANAGEMENT, "nav_attendance"),
                BlueprintAction("Leave Reviews", "Approve guard requests", Icons.AutoMirrored.Filled.EventNote, NavRoutes.LEAVE, "nav_leave"),
                BlueprintAction("Occurrence Book", "Review daily post ledger", Icons.AutoMirrored.Filled.MenuBook, NavRoutes.OCCURRENCE_BOOK, "nav_ob"),
                BlueprintAction("Patrol Oversight", "Monitor RFID scans", Icons.AutoMirrored.Filled.DirectionsWalk, NavRoutes.PATROL, "nav_patrol"),
                BlueprintAction("Visitor Gate Log", "Inspect visitor passes", Icons.Default.Badge, NavRoutes.VISITOR_BOOK, "nav_visitors"),
                BlueprintAction("Shift Handovers", "Approve shift transfers", Icons.Default.SwapHoriz, NavRoutes.HANDOVER, "nav_handover"),
                BlueprintAction("Incident Command", "Escalate & close cases", Icons.Default.ReportProblem, NavRoutes.INCIDENTS, "nav_incidents"),
                BlueprintAction("Relief & Adjustments", "Shift swaps & overrides", Icons.Default.Tune, NavRoutes.RECORD_ADJUSTMENTS, "nav_adjustments")
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (pair in supervisorActions.chunked(2)) {
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
        // LEVEL 4: RECENT POST ACTIVITY STREAM
        // ==============================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Station Activity",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryLight
                )
                TextButton(onClick = { onNavigate(NavRoutes.OCCURRENCE_BOOK) }) {
                    Text("View Full Ledger", fontSize = 12.sp, color = NavyDark, fontWeight = FontWeight.Bold)
                }
            }

            if (stationOB.isEmpty() && stationVisitors.isEmpty()) {
                SgmisCard {
                    Text(
                        text = "No operational entries logged for today yet.",
                        fontSize = 13.sp,
                        color = TextSecondaryLight
                    )
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    border = BorderStroke(1.dp, BorderSubtleLight)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        stationOB.take(3).forEach { ob ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(NavyDark)
                                        .padding(top = 4.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ob.occurrenceText,
                                        fontSize = 13.sp,
                                        color = TextPrimaryLight,
                                        maxLines = 2
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "By ${ob.guardName ?: "Officer"} • ${ob.createdAt.take(16).ifBlank { "Today" }}",
                                        fontSize = 11.sp,
                                        color = TextSecondaryLight
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
