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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Shift
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Reconstructed Guard Personal Roster Screen ("My Roster Overview", Blueprint Page 16).
 * Follows the blueprint's personal calendar, duty schedule stream, and stats overview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardDutyPlanScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val user = uiState.currentUser
    val shifts = uiState.rosterShifts
    val todayShift = uiState.todayShift
    val leaveBalance = uiState.leaveBalance
    var viewMode by remember { mutableStateOf("TABLE") }

    LaunchedEffect(Unit) {
        viewModel.fetchTodayShift()
        viewModel.fetchRosterShifts()
        viewModel.fetchLeave()
        viewModel.fetchPublicHolidays()
        viewModel.fetchHolidayDutyRecords()
    }

    val harareTz = remember { java.util.TimeZone.getTimeZone("Africa/Harare") }
    val currentMonthYear = remember {
        SimpleDateFormat("MMMM yyyy", Locale.US).apply { timeZone = harareTz }.format(Date())
    }
    val todayStr = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz }.format(Date())
    }

    val myShifts = remember(shifts, user) {
        val uid = user?.id
        val uName = user?.username
        val emp = user?.employeeNumber
        val filtered = shifts.filter { s ->
            (uid != null && s.guard == uid) ||
            (uName != null && s.guardName.equals(uName, ignoreCase = true)) ||
            (emp != null && s.employeeNumber == emp)
        }
        filtered
    }

    // Server-derived statistics (no hardcoded 4 ON / 8 OFF rules)
    val totalDaysOn = remember(myShifts) {
        myShifts.count {
            val st = it.shiftType.uppercase()
            val at = it.assignmentType.uppercase()
            st != "OFF" && at != "TIME_OFF"
        }
    }
    val totalDaysOff = remember(myShifts) {
        myShifts.count {
            val st = it.shiftType.uppercase()
            val at = it.assignmentType.uppercase()
            st == "OFF" || at == "TIME_OFF"
        }
    }
    val nextDuty = remember(myShifts, todayStr) {
        myShifts
            .filter { it.date >= todayStr && it.shiftType.uppercase() != "OFF" && it.assignmentType.uppercase() != "TIME_OFF" }
            .minByOrNull { it.date }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "My Roster Overview",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "$currentMonthYear • Personal Calendar",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("duty_plan_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.fetchTodayShift()
                        viewModel.fetchRosterShifts()
                        viewModel.fetchLeave()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Roster")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Officer Header Card (Blueprint Page 16 Header)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("duty_plan_profile_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Badge, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.fullName ?: user?.username ?: "Security Officer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Service ID: ${user?.employeeNumber ?: "—"} • Post: ${uiState.currentStationName}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Status: Guard • Active • ${if (uiState.isOnDuty) "On Duty" else "On Roster Schedule"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isOnDuty) StatusSuccess else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. Blueprint Stats Overview Section (3 Metric Blocks + Holiday Comp)
            item {
                Text(
                    text = "MONTHLY ROSTER STATISTICS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$totalDaysOn",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("Total Shifts", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$totalDaysOff",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Days Off", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1.2f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val vac = leaveBalance?.let { String.format(Locale.getDefault(), "%.1f", it.remainingVacation.coerceAtLeast(it.vacationDays)) } ?: "—"
                            val cas = leaveBalance?.let { String.format(Locale.getDefault(), "%.1f", it.remainingCasual.coerceAtLeast(it.casualDays)) } ?: "—"
                            Text(
                                text = "$vac / $cas",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text("Vacation / Casual", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Public Holiday Compensation Card (Blueprint Comp Days Card)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Celebration, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Public Holiday Compensation",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = "Compensatory credits accrue automatically when scheduled shifts fall on official Zimbabwe public holidays.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val compRemaining = leaveBalance?.remainingCompensation ?: 0.0
                            val compEarned = leaveBalance?.compensationEarned ?: 0.0
                            if (compEarned > 0 || compRemaining > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Earned: %.1f Days • Remaining: %.1f Days".format(compEarned, compRemaining),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }

            // Next Duty Card
            if (nextDuty != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.EventAvailable, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Next Scheduled Duty",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${nextDuty.date} • ${nextDuty.shiftType} Shift (${nextDuty.startTime}–${nextDuty.endTime})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Station: ${nextDuty.stationName} • Partner: ${nextDuty.partnerName ?: "Solo"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 3. Duty Schedule Stream Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DUTY SCHEDULE STREAM",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${myShifts.size} Rostered Entries",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // View Mode Selector
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = viewMode == "TABLE",
                        onClick = { viewMode = "TABLE" },
                        label = { Text("My Roster Table (Official)") },
                        leadingIcon = { Icon(Icons.Default.TableChart, null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = viewMode == "CARDS",
                        onClick = { viewMode = "CARDS" },
                        label = { Text("Duty Stream Cards") },
                        leadingIcon = { Icon(Icons.Default.ViewAgenda, null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Duty Schedule Items
            if (uiState.adminLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (myShifts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No roster shifts recorded for this period. Contact your station supervisor to verify roster publication.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (viewMode == "TABLE") {
                item {
                    AuthoritativeRosterTable(
                        shifts = myShifts,
                        publicHolidays = uiState.publicHolidays,
                        holidayDutyRecords = uiState.holidayDutyRecords,
                        leaveApplications = uiState.leaveApplications
                    )
                }
            } else {
                items(myShifts) { shiftItem ->
                    BlueprintRosterShiftItem(shift = shiftItem, currentUserId = user?.id)
                }
            }
        }
    }
}

/**
 * Renders a single shift row with Blueprint-styled [DAY], [NIGHT], and [OFF] badges.
 */
@Composable
fun BlueprintRosterShiftItem(shift: Shift, currentUserId: String?) {
    val isMyShift = currentUserId == null || shift.guard == currentUserId
    val isTimeOff = shift.assignmentType == "TIME_OFF" || shift.shiftType.uppercase() == "OFF"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("duty_plan_item_${shift.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isTimeOff) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isTimeOff) 0.dp else 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            isTimeOff -> Icons.Default.Bedtime
                            shift.shiftType.uppercase() == "DAY" -> Icons.Default.WbSunny
                            else -> Icons.Default.Nightlight
                        },
                        contentDescription = null,
                        tint = when {
                            isTimeOff -> MaterialTheme.colorScheme.onSurfaceVariant
                            shift.shiftType.uppercase() == "DAY" -> GoldAccent
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = shift.date,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Blueprint Badges: [DAY], [NIGHT], [OFF]
                BlueprintShiftPill(shiftType = shift.shiftType, assignmentType = shift.assignmentType)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isTimeOff) "OFF DUTY • Scheduled Rest Day" else "Hours: ${shift.startTime}–${shift.endTime} • ${shift.stationName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isTimeOff) FontWeight.Normal else FontWeight.Medium,
                        color = if (isTimeOff) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    if (!isTimeOff) {
                        Text(
                            text = "Partner: ${shift.partnerName ?: "Solo Assignment"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!isTimeOff) {
                    Surface(
                        color = when (shift.attendanceStatus) {
                            "CLOCKED_IN" -> StatusSuccess.copy(alpha = 0.15f)
                            "CLOCKED_OUT" -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = when (shift.attendanceStatus) {
                                "CLOCKED_IN" -> "ON DUTY"
                                "CLOCKED_OUT" -> "COMPLETED"
                                else -> "SCHEDULED"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when (shift.attendanceStatus) {
                                "CLOCKED_IN" -> StatusSuccess
                                "CLOCKED_OUT" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Blueprint shift type pill:
 * [DAY] -> Green pill badge
 * [NIGHT] -> Dark navy/blue pill badge
 * [OFF] -> Grey pill badge
 */
@Composable
fun BlueprintShiftPill(shiftType: String, assignmentType: String) {
    val isOff = assignmentType == "TIME_OFF" || shiftType.uppercase() == "OFF"
    val isDay = shiftType.uppercase() == "DAY"
    val isNight = shiftType.uppercase() == "NIGHT"

    val (badgeText, bg, fg) = when {
        isOff -> Triple("[OFF]", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        isDay -> Triple("[DAY]", StatusSuccess.copy(alpha = 0.2f), StatusSuccess)
        isNight -> Triple("[NIGHT]", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        else -> Triple("[$shiftType]", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = badgeText,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * Authoritative 8-Column "My Roster" Table for Smart Security (Phase 14).
 * Strictly renders:
 * DATE | DAY | STATUS | SHIFT | PARTNER | LEAVE TYPE | PUBLIC HOLIDAY | COMPENSATORY DAY
 */
@Composable
fun AuthoritativeRosterTable(
    shifts: List<Shift>,
    publicHolidays: List<com.example.data.model.PublicHoliday>,
    holidayDutyRecords: List<com.example.data.model.PublicHolidayDutyRecord>,
    leaveApplications: List<com.example.data.model.LeaveApplication>
) {
    val harareTz = remember { java.util.TimeZone.getTimeZone("Africa/Harare") }
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.US).apply { timeZone = harareTz } }
    val parseFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz } }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("authoritative_roster_table"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Official Station Roster Matrix",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Zimbabwe • Africa/Harare",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Column {
                    // Header Row: Exactly the 8 authoritative columns mandated by Phase 14
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DATE", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
                        Text("DAY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
                        Text("STATUS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(90.dp))
                        Text("SHIFT", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
                        Text("PARTNER", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
                        Text("LEAVE TYPE", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(130.dp))
                        Text("PUBLIC HOLIDAY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(150.dp))
                        Text("COMPENSATORY DAY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(160.dp))
                    }

                    HorizontalDivider()

                    if (shifts.isEmpty()) {
                        Box(modifier = Modifier.padding(24.dp)) {
                            Text("No rostered shifts found for this schedule cycle.", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        shifts.forEachIndexed { index, shift ->
                            val isTimeOff = shift.assignmentType == "TIME_OFF" || shift.shiftType.uppercase() == "OFF"
                            val dayName = try {
                                val d = parseFormat.parse(shift.date)
                                if (d != null) dayFormat.format(d) else "-"
                            } catch (e: Exception) {
                                "-"
                            }

                            val holiday = publicHolidays.find { it.date == shift.date }
                            val dutyRecord = holidayDutyRecords.find { it.shiftDate == shift.date }
                            val leaveApp = leaveApplications.find { it.startDate <= shift.date && it.endDate >= shift.date }

                            val statusPill = when {
                                leaveApp != null && leaveApp.status == "APPROVED" -> "LEAVE"
                                isTimeOff -> "OFF"
                                shift.shiftType.uppercase() == "DAY" -> "DAY"
                                shift.shiftType.uppercase() == "NIGHT" -> "NIGHT"
                                else -> shift.shiftType
                            }

                            val leaveTypeDisplay = when {
                                leaveApp != null -> leaveApp.leaveTypeDisplay ?: leaveApp.leaveType
                                shift.overrideReason?.contains("Leave", ignoreCase = true) == true -> shift.overrideReason
                                isTimeOff -> "Off Duty"
                                else -> "-"
                            }

                            val compDayDisplay = when {
                                dutyRecord != null && dutyRecord.status == "APPROVED" -> "+2.0 Days (Earned)"
                                dutyRecord != null && dutyRecord.status == "PENDING" -> "Pending (+2d)"
                                leaveApp?.leaveType == "COMPENSATION" -> "Used (-1d)"
                                holiday != null && !isTimeOff -> "+2.0 Days (Eligible)"
                                else -> "-"
                            }

                            val rowBg = if (holiday != null) {
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                            } else if (index % 2 == 1) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            } else {
                                Color.Transparent
                            }

                            Row(
                                modifier = Modifier
                                    .background(rowBg)
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(shift.date, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(96.dp))
                                Text(dayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))

                                Box(modifier = Modifier.width(90.dp)) {
                                    BlueprintShiftPill(shiftType = statusPill, assignmentType = if (statusPill == "OFF" || statusPill == "LEAVE") "TIME_OFF" else "NORMAL")
                                }

                                Text(
                                    text = if (isTimeOff) "-" else "${shift.startTime}–${shift.endTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(110.dp)
                                )

                                Text(
                                    text = if (isTimeOff) "-" else (shift.partnerName ?: "Solo"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(110.dp)
                                )

                                Text(
                                    text = leaveTypeDisplay ?: "-",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (leaveApp != null) FontWeight.Bold else FontWeight.Normal,
                                    color = if (leaveApp != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.width(130.dp)
                                )

                                Text(
                                    text = holiday?.name ?: "-",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (holiday != null) FontWeight.Bold else FontWeight.Normal,
                                    color = if (holiday != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(150.dp)
                                )

                                Text(
                                    text = compDayDisplay,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (compDayDisplay.startsWith("+")) FontWeight.Bold else FontWeight.Normal,
                                    color = if (compDayDisplay.startsWith("+")) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(160.dp)
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}
