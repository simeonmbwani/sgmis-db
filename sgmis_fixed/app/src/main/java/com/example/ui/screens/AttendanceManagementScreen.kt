package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
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
            TopAppBar(
                title = {
                    Column {
                        Text("Attendance Console", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Command & Duty Monitoring", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("attendance_mgmt_back_button")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.fetchAttendanceRecords(date = selectedDate)
                        viewModel.fetchRosterShifts(date = selectedDate, station = selectedStationId)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Attendance")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
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
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search guard name or employee ID...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, null)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Summary Metrics Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (uiState.adminLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (activeDutyShifts.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("No scheduled active guard duties for $selectedDate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
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
    val isNotClockedIn = attendance == null || attendance.clockIn == null

    Card(
        modifier = Modifier.fillMaxWidth().testTag("attendance_record_${shift.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                // Attendance Status Pill
                Surface(
                    color = when {
                        isClockedOut -> MaterialTheme.colorScheme.primaryContainer
                        isClockedIn -> StatusSuccess.copy(alpha = 0.15f)
                        else -> StatusWarning.copy(alpha = 0.15f)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = when {
                            isClockedOut -> "COMPLETED"
                            isClockedIn -> "ON DUTY"
                            else -> "NOT ARRIVED"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isClockedOut -> MaterialTheme.colorScheme.primary
                            isClockedIn -> StatusSuccess
                            else -> StatusWarning
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scheduled Shift Type & Hours
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (shift.shiftType == "DAY") Icons.Default.WbSunny else Icons.Default.Nightlight,
                        contentDescription = null,
                        tint = if (shift.shiftType == "DAY") GoldAccent else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
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
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (attendance.clockIn != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Login, null, tint = StatusSuccess, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("In: ${attendance.clockIn}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                if (attendance.isLate) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                        Text("LATE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                    }
                                }
                            }
                        }

                        if (!attendance.lateReason.isNullOrBlank()) {
                            Text("Late Reason: ${attendance.lateReason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }

                        if (attendance.clockOut != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Logout, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
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
