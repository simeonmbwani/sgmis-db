package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Attendance
import com.example.data.model.Shift
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceManagementScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val harareTz = remember { TimeZone.getTimeZone("Africa/Harare") }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz } }
    val today = remember { dateFormat.format(Date()) }

    var selectedDate by remember { mutableStateOf(today) }
    var selectedStationId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.fetchStations()
        viewModel.fetchAttendanceRecords(date = selectedDate)
        viewModel.fetchRosterShifts(date = selectedDate)
    }

    LaunchedEffect(selectedDate, selectedStationId) {
        viewModel.fetchAttendanceRecords(date = selectedDate)
        viewModel.fetchRosterShifts(date = selectedDate, station = selectedStationId)
    }

    // Filter shifts for selected date and station, excluding TIME_OFF
    val activeDutyShifts = remember(uiState.rosterShifts, selectedStationId, searchQuery) {
        uiState.rosterShifts.filter { shift ->
            val matchesStation = selectedStationId == null || shift.station == selectedStationId
            val matchesSearch = searchQuery.isBlank() ||
                    shift.guardName.contains(searchQuery, ignoreCase = true) ||
                    (shift.employeeNumber ?: "").contains(searchQuery, ignoreCase = true)
            val isDuty = shift.assignmentType != "TIME_OFF" && shift.shiftType != "OFF"
            matchesStation && matchesSearch && isDuty
        }
    }

    // Map attendance records by shift id or guard id
    val attendanceMap = remember(uiState.attendanceRecords) {
        uiState.attendanceRecords.associateBy { it.shift }
    }

    // Metrics calculation
    val totalScheduled = activeDutyShifts.size
    val clockedInCount = activeDutyShifts.count { s ->
        val att = attendanceMap[s.id]
        att?.clockIn != null && att.clockOut == null
    }
    val clockedOutCount = activeDutyShifts.count { s ->
        val att = attendanceMap[s.id]
        att?.clockOut != null
    }
    val notClockedInCount = totalScheduled - clockedInCount - clockedOutCount
    val lateCount = activeDutyShifts.count { s ->
        val att = attendanceMap[s.id]
        att?.isLate == true
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "Attendance Console",
                subtitle = "Duty Monitoring • ${uiState.currentStationName}",
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("attendance_mgmt_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.fetchAttendanceRecords(date = selectedDate)
                        viewModel.fetchRosterShifts(date = selectedDate, station = selectedStationId)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Attendance")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Filters and Search Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = Elevation.card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = selectedDate,
                            onValueChange = { selectedDate = it },
                            label = { Text("Date (YYYY-MM-DD)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        // Station Selector Dropdown
                        var expandedStation by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { expandedStation = true },
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                val currentName = uiState.stations.firstOrNull { it.id == selectedStationId }?.name ?: "All Stations"
                                Text(currentName, maxLines = 1)
                            }
                            DropdownMenu(
                                expanded = expandedStation,
                                onDismissRequest = { expandedStation = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Stations") },
                                    onClick = {
                                        selectedStationId = null
                                        expandedStation = false
                                    }
                                )
                                uiState.stations.forEach { st ->
                                    DropdownMenuItem(
                                        text = { Text(st.name) },
                                        onClick = {
                                            selectedStationId = st.id
                                            expandedStation = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    SgmisSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = "Search guard name or employee ID...",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // High contrast Metrics Row
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(label = "SCHEDULED", value = "$totalScheduled", color = MaterialTheme.colorScheme.onSurface)
                    MetricItem(label = "ON DUTY", value = "$clockedInCount", color = StatusSuccess)
                    MetricItem(label = "COMPLETED", value = "$clockedOutCount", color = MaterialTheme.colorScheme.primary)
                    MetricItem(label = "NOT ARRIVED", value = "$notClockedInCount", color = StatusWarning)
                    if (lateCount > 0) {
                        MetricItem(label = "LATE", value = "$lateCount", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Attendance Records List
            if (uiState.adminLoading) {
                SgmisLoadingSkeleton(modifier = Modifier.padding(Spacing.md))
            } else if (activeDutyShifts.isEmpty()) {
                SgmisEmptyState(
                    icon = Icons.Outlined.EventBusy,
                    title = "No Duty Shifts Found",
                    description = "No scheduled active guard duties found for $selectedDate.",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
                    contentPadding = PaddingValues(top = Spacing.sm, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(activeDutyShifts) { shift ->
                        val attendance = attendanceMap[shift.id]
                        AttendanceRecordCard(shift = shift, attendance = attendance)
                    }
                }
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AttendanceRecordCard(shift: Shift, attendance: Attendance?) {
    val isClockedOut = attendance?.clockOut != null
    val isClockedIn = attendance?.clockIn != null && !isClockedOut

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("attendance_record_${shift.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(CornerRadius.md),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card)
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = shift.guardName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: ${shift.employeeNumber ?: "SEC"} • ${shift.stationName} (${shift.dutyLocation})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SgmisBadge(
                    text = when {
                        isClockedOut -> "COMPLETED"
                        isClockedIn -> "ON DUTY"
                        else -> "NOT ARRIVED"
                    },
                    variant = when {
                        isClockedOut -> SgmisBadgeVariant.INFO
                        isClockedIn -> SgmisBadgeVariant.SUCCESS
                        else -> SgmisBadgeVariant.WARNING
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (shift.shiftType == "DAY") Icons.Default.WbSunny else Icons.Default.Nightlight,
                        contentDescription = null,
                        tint = if (shift.shiftType == "DAY") GoldAccent else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = "${shift.shiftType} (${shift.startTime} - ${shift.endTime})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (shift.partnerName != null) {
                    Text(
                        text = "Partner: ${shift.partnerName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Timestamps and GPS row
            if (attendance != null && (attendance.clockIn != null || attendance.clockOut != null)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(CornerRadius.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(Spacing.xs), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (attendance.clockIn != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Login, null, tint = StatusSuccess, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text("In: ${attendance.clockIn}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                if (attendance.isLate) {
                                    Spacer(modifier = Modifier.width(Spacing.xs))
                                    SgmisBadge(text = "LATE", variant = SgmisBadgeVariant.ERROR)
                                }
                            }
                        }

                        if (!attendance.lateReason.isNullOrBlank()) {
                            Text("Late Reason: ${attendance.lateReason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }

                        if (attendance.clockOut != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text("Out: ${attendance.clockOut}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }

                        if (!attendance.clockInGps.isNullOrBlank()) {
                            Text("GPS: ${attendance.clockInGps}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
