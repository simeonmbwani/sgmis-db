package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RosterShiftCard(shift: Shift) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${shift.guardName} (${shift.employeeNumber ?: "SEC"})",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
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
            Text(
                text = "Date: ${shift.date} • Hours: ${shift.startTime} – ${shift.endTime}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Station: ${shift.stationName} • Partner: ${shift.partnerName ?: "Solo"} • Location: ${shift.dutyLocation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * Data model for horizontal calendar audit matrix.
 */
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

/**
 * Human-readable Roster Conflict & Integrity Banner.
 */
@Composable
fun RosterConflictBanner(
    report: ConflictReport?,
    isLoading: Boolean,
    onAuditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Roster Integrity & Conflict Audit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onAuditClick,
                    enabled = !isLoading,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Audit Roster", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (report == null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scan station schedule for double-booking, overstaffing, uncovered posts, or duty cycle violations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (report.hasConflicts) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Conflicts Detected (${report.totalConflicts}) — Sign-Off Blocked",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            text = "An active conflict exists for this station's roster period. Resolve overlapping assignments before approving.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                        report.conflicts.filter { it.severity == "ERROR" }.forEach { c ->
                            Text(
                                text = "• [${c.date}] ${c.message}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            } else if (report.hasWarnings) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Advisory Notices (${report.conflicts.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
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
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Verified, null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Roster Integrity Verified",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusSuccess
                            )
                            Text(
                                text = "Zero conflicts detected. Exactly 1 Day & 1 Night coverage compliant across all rotation cycles.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusSuccess
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders an operational date block in the matrix.
 */
@Composable
fun DateOperationalMatrixCard(
    dateStr: String,
    shiftsOnDate: List<Shift>,
    guardPairs: List<GuardPair> = emptyList(),
    leaveApplications: List<LeaveApplication> = emptyList()
) {
    val activeLeaveGuardIds = remember(leaveApplications, dateStr, shiftsOnDate) {
        val shiftGuardIds = shiftsOnDate.map { it.guard }.toSet()
        leaveApplications.filter {
            it.status == "APPROVED" && it.startDate <= dateStr && it.endDate >= dateStr &&
            (shiftGuardIds.isEmpty() || it.guard in shiftGuardIds)
        }.map { it.guard }.toSet()
    }
    val isLeaveShift = { s: Shift -> s.isOnLeave || s.rawDutyState == "ON_LEAVE" || s.leaveType != null || s.guard in activeLeaveGuardIds }
    val dayShifts = shiftsOnDate.filter { it.shiftType == "DAY" && it.assignmentType != "TIME_OFF" && !isLeaveShift(it) }
    val nightShifts = shiftsOnDate.filter { it.shiftType == "NIGHT" && it.assignmentType != "TIME_OFF" && !isLeaveShift(it) }
    val timeOffShifts = shiftsOnDate.filter { (it.shiftType == "OFF" || it.shiftType == "REST" || it.assignmentType == "TIME_OFF") && !isLeaveShift(it) }
    val leaveShifts = shiftsOnDate.filter { isLeaveShift(it) }
    val specialShifts = shiftsOnDate.filter { it.assignmentType in listOf("EXAM_ESCORT", "ESCORT") && !isLeaveShift(it) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

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

            // Approved Leave Section
            if (leaveShifts.isNotEmpty()) {
                OperationalShiftBlock(
                    title = "ON APPROVED LEAVE (UNAVAILABLE)",
                    badgeText = "ON LEAVE",
                    badgeColor = MaterialTheme.colorScheme.tertiary,
                    icon = Icons.Default.EventBusy,
                    shifts = leaveShifts
                )
            }

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
    badgeColor: Color,
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

@Composable
fun DutyOverrideCard(
    override: DutyOverride,
    canSettle: Boolean,
    onSettleCompensation: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = override.guardName ?: "Guard",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${override.stationName ?: "Station"} • ${override.date} (${override.shiftType})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SmartStatusChip(
                    status = override.overrideTypeDisplay ?: override.overrideType
                )
            }

            if (override.reason.isNotBlank()) {
                Text(
                    text = "Reason: ${override.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Compensation: %.1fd owed".format(override.compensationDaysOwed),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (override.compensationSettled) StatusSuccess else MaterialTheme.colorScheme.primary
                    )
                    if (override.authorizedByName != null) {
                        Text(
                            text = "Auth by: ${override.authorizedByName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (override.compensationSettled) {
                    Surface(
                        color = StatusSuccess.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "SETTLED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusSuccess,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (canSettle && override.compensationDaysOwed > 0) {
                    OutlinedButton(
                        onClick = onSettleCompensation,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Settle Compensation", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun PairReassignmentAuditCard(audit: GuardPairReassignmentAudit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = audit.guardName ?: "Guard",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = audit.effectiveDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${audit.oldPairDisplay ?: "Unassigned"}  ➜  ${audit.newPairDisplay ?: "New Pair"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (audit.stationName != null) {
                        Text(
                            text = audit.stationName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (audit.reason.isNotBlank()) {
                Text(
                    text = "Reason: ${audit.reason}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (audit.authorizedByName != null) {
                Text(
                    text = "Authorized by: ${audit.authorizedByName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun GenerateRosterDialog(
    stations: List<Station>,
    guardPairs: List<GuardPair> = emptyList(),
    onDismiss: () -> Unit,
    onSubmit: (stationId: String, startDate: String, cycleDays: Int, mode: String, examPeriodId: String?, examVenueName: String?, examGuardIds: List<String>) -> Unit
) {
    val harareTz = remember { TimeZone.getTimeZone("Africa/Harare") }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz } }
    val today = remember { dateFormat.format(Date()) }

    var selectedStationId by remember { mutableStateOf(stations.firstOrNull()?.id ?: "") }
    var startDate by remember { mutableStateOf(today) }
    var cycleDays by remember { mutableStateOf("12") }
    var mode by remember { mutableStateOf("NORMAL") }
    var examPeriodName by remember { mutableStateOf("Examination Period") }
    var examVenueName by remember { mutableStateOf("Examination Venue") }
    var selectedPairId by remember { mutableStateOf<String?>(null) }

    val stationPairs = remember(guardPairs, selectedStationId) {
        guardPairs.filter { it.station == selectedStationId }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generate Authoritative Roster", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Generates rotational operational shifts following the standard 3-pair rotational protocol (Pair 1, Pair 2, Pair 3) with alternating Day and Night shifts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Select Station:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                if (stations.isEmpty()) {
                    Text("No stations configured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    stations.forEach { st ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedStationId == st.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedStationId == st.id, onClick = { selectedStationId = st.id })
                                Text(st.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("Start Date (YYYY-MM-DD)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = cycleDays,
                    onValueChange = { cycleDays = it.filter { c -> c.isDigit() } },
                    label = { Text("Cycle Days (Multiple of 12)") },
                    placeholder = { Text("12") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Operational Mode:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("NORMAL" to "Standard Rotation", "EXAM" to "Exam Duty (5-Day)").forEach { (m, label) ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (mode == "EXAM") {
                    OutlinedTextField(
                        value = examPeriodName,
                        onValueChange = { examPeriodName = it },
                        label = { Text("Exam Period Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = examVenueName,
                        onValueChange = { examVenueName = it },
                        label = { Text("Exam Venue Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedStationId.isNotBlank() && startDate.isNotBlank() && cycleDays.toIntOrNull() != null,
                onClick = {
                    val days = cycleDays.toIntOrNull() ?: 12
                    onSubmit(selectedStationId, startDate.trim(), days, mode, null, if (mode == "EXAM") examVenueName else null, emptyList())
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Generate Shifts")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ResumeNormalRosterDialog(
    stations: List<Station>,
    onDismiss: () -> Unit,
    onSubmit: (stationId: String, afterDate: String, cycleDays: Int) -> Unit
) {
    val harareTz = remember { TimeZone.getTimeZone("Africa/Harare") }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz } }
    val today = remember { dateFormat.format(Date()) }

    var selectedStationId by remember { mutableStateOf(stations.firstOrNull()?.id ?: "") }
    var afterDate by remember { mutableStateOf(today) }
    var cycleDays by remember { mutableStateOf("12") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume Normal Rotational Roster", fontWeight = FontWeight.Bold) },
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

                Text("Station:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                stations.forEach { st ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedStationId == st.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedStationId == st.id, onClick = { selectedStationId = st.id })
                            Text(st.name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedTextField(
                    value = afterDate,
                    onValueChange = { afterDate = it },
                    label = { Text("Resume From Date (YYYY-MM-DD)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = cycleDays,
                    onValueChange = { cycleDays = it.filter { c -> c.isDigit() } },
                    label = { Text("Cycle Days") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = selectedStationId.isNotBlank() && afterDate.isNotBlank() && cycleDays.toIntOrNull() != null,
                onClick = {
                    val days = cycleDays.toIntOrNull() ?: 12
                    onSubmit(selectedStationId, afterDate.trim(), days)
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Resume Normal Rotation")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun DutyOverrideDialog(
    guards: List<User>,
    stations: List<Station>,
    defaultStationId: String?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (CreateDutyOverrideRequest) -> Unit
) {
    val today = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Africa/Harare")
        }.format(Date())
    }
    var selectedGuardId by remember { mutableStateOf(guards.firstOrNull()?.id ?: "") }
    var selectedStationId by remember { mutableStateOf(defaultStationId ?: stations.firstOrNull()?.id ?: "") }
    var date by remember { mutableStateOf(today) }
    var shiftType by remember { mutableStateOf("DAY") }
    var overrideType by remember { mutableStateOf("LEAVE_INTERRUPTION") }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record Duty Override", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Override guard duty for emergency recall, leave interruption, or immediate station coverage. An authoritative relief shift will be generated and compensation tracked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Select Guard:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                guards.forEach { g ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedGuardId == g.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedGuardId == g.id, onClick = { selectedGuardId = g.id })
                            Text("${g.fullName ?: g.username} (${g.employeeNumber ?: "G"})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (stations.isNotEmpty()) {
                    Text("Select Station:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    stations.forEach { st ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selectedStationId == st.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedStationId == st.id, onClick = { selectedStationId = st.id })
                                Text(st.name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Shift Type:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("DAY", "NIGHT").forEach { st ->
                        FilterChip(
                            selected = shiftType == st,
                            onClick = { shiftType = st },
                            label = { Text(st) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text("Override Type:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                listOf(
                    "LEAVE_INTERRUPTION" to "Leave Interruption",
                    "EMERGENCY_RECALL" to "Emergency Recall",
                    "COVERAGE_DEFICIT" to "Coverage Deficit"
                ).forEach { (code, label) ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (overrideType == code) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = overrideType == code, onClick = { overrideType = code })
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (Required)") },
                    minLines = 2,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && selectedGuardId.isNotBlank() && date.isNotBlank() && reason.isNotBlank(),
                onClick = {
                    onSubmit(
                        CreateDutyOverrideRequest(
                            guard = selectedGuardId,
                            date = date.trim(),
                            shiftType = shiftType,
                            overrideType = overrideType,
                            reason = reason.trim(),
                            station = selectedStationId.ifBlank { null }
                        )
                    )
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (saving) "Recording..." else "Record Override")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ReassignPairDialog(
    guards: List<User>,
    guardPairs: List<GuardPair>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ReassignGuardPairRequest) -> Unit
) {
    val today = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Africa/Harare")
        }.format(Date())
    }
    var selectedGuardId by remember { mutableStateOf(guards.firstOrNull()?.id ?: "") }
    var selectedPairId by remember { mutableStateOf(guardPairs.firstOrNull()?.id ?: "") }
    var effectiveDate by remember { mutableStateOf(today) }
    var reason by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf("A") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reassign Guard Pair", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Transfer a security guard to a different rotational operational pair. This updates authoritative pair assignments for future roster schedules.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Select Guard:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                guards.forEach { g ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedGuardId == g.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedGuardId == g.id, onClick = { selectedGuardId = g.id })
                            Text("${g.fullName ?: g.username} (${g.employeeNumber ?: "G"})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text("Target Guard Pair:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                guardPairs.forEach { pair ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedPairId == pair.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedPairId == pair.id, onClick = { selectedPairId = pair.id })
                            val desc = if (!pair.guardAName.isNullOrBlank() && !pair.guardBName.isNullOrBlank()) {
                                "${pair.guardAName} & ${pair.guardBName}"
                            } else {
                                "Order ${pair.order ?: pair.rotationOrder ?: 1}"
                            }
                            Text("${pair.stationName ?: "Station"}: $desc", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text("Pair Slot:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("A" to "Slot A", "B" to "Slot B").forEach { (s, label) ->
                        FilterChip(
                            selected = slot == s,
                            onClick = { slot = s },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                OutlinedTextField(
                    value = effectiveDate,
                    onValueChange = { effectiveDate = it },
                    label = { Text("Effective Date (YYYY-MM-DD)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (Required)") },
                    minLines = 2,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && selectedGuardId.isNotBlank() && selectedPairId.isNotBlank() && reason.isNotBlank(),
                onClick = {
                    onSubmit(
                        ReassignGuardPairRequest(
                            guard = selectedGuardId,
                            newPair = selectedPairId,
                            effectiveDate = effectiveDate.trim(),
                            reason = reason.trim(),
                            slot = slot
                        )
                    )
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (saving) "Reassigning..." else "Confirm Reassignment")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ReassignSingleShiftDialog(
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
        title = { Text("Reassign Single Shift", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("${shift.date} • ${shift.shiftType} • ${shift.stationName}", fontWeight = FontWeight.Bold)
                Text("This changes only this unstarted shift. Past shifts and attendance remain unchanged.", style = MaterialTheme.typography.bodySmall)

                Text("Replacement Guard:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                guards.forEach { guard ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (guardId == guard.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = guardId == guard.id, onClick = { guardId = guard.id })
                            Text("${guard.fullName ?: guard.username} (${guard.employeeNumber.orEmpty()})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text("Station:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                stations.forEach { station ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (stationId == station.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = stationId == station.id, onClick = { stationId = station.id })
                            Text(station.name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reassignment Reason") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmReassignment, onCheckedChange = { confirmReassignment = it })
                    Text("Confirm assignment update for this shift", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && confirmReassignment && reason.isNotBlank(),
                onClick = {
                    onSubmit(ReassignSingleShiftRequest(guardId = guardId, stationId = stationId, reason = reason.trim()))
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (saving) "Saving..." else "Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
