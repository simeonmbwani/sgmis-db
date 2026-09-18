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
import com.example.data.model.Shift
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel
import java.text.SimpleDateFormat
import java.util.*

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

    LaunchedEffect(Unit) {
        viewModel.fetchTodayShift()
        viewModel.fetchRosterShifts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Guard Duty Plan", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Authoritative Schedule (View-Only)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("duty_plan_back_button")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.fetchTodayShift()
                        viewModel.fetchRosterShifts()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Schedule")
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
            contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Guard Information Header Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("duty_plan_profile_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.fullName ?: user?.username ?: "Security Officer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Service ID: ${user?.employeeNumber ?: "SEC-ACTIVE"} • ${user?.stationName ?: "Assigned Post"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 2. Today's Assignment Card
            item {
                Text(
                    text = "TODAY'S OPERATIONAL ASSIGNMENT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                if (todayShift == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("duty_plan_today_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.EventBusy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("No Active Shift Today", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("You are currently off-duty or have no shift scheduled on the active roster.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    TodayDutySummaryCard(todayShift)
                }
            }

            // 3. Upcoming Schedule Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "UPCOMING ROTATION SCHEDULE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${shifts.size} Shifts Loaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.adminLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (shifts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No upcoming rotation schedule found for your duty pair. Contact your supervisor to verify roster publication.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(shifts) { shift ->
                    DutyPlanShiftItem(shift = shift, currentUserId = user?.id)
                }
            }
        }
    }
}

@Composable
fun TodayDutySummaryCard(shift: Shift) {
    val isTimeOff = shift.assignmentType == "TIME_OFF" || shift.shiftType == "OFF"
    val containerColor = if (isTimeOff) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("today_duty_card"),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = shift.stationName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                DutyTypePill(shiftType = shift.shiftType, assignmentType = shift.assignmentType)
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            if (isTimeOff) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bedtime, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Scheduled Rest Day (Time Off) • No duty attendance required.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("DUTY HOURS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${shift.startTime} - ${shift.endTime}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Column {
                        Text("DUTY POST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(shift.dutyLocation, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("DUTY PARTNER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(shift.partnerName ?: "Solo Post", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DutyPlanShiftItem(shift: Shift, currentUserId: String?) {
    val isMyShift = currentUserId == null || shift.guard == currentUserId
    val isTimeOff = shift.assignmentType == "TIME_OFF" || shift.shiftType == "OFF"

    Card(
        modifier = Modifier.fillMaxWidth().testTag("duty_plan_item_${shift.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isMyShift) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isMyShift) 2.dp else 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            isTimeOff -> Icons.Default.Bedtime
                            shift.shiftType == "DAY" -> Icons.Default.WbSunny
                            else -> Icons.Default.Nightlight
                        },
                        contentDescription = null,
                        tint = when {
                            isTimeOff -> MaterialTheme.colorScheme.onSurfaceVariant
                            shift.shiftType == "DAY" -> GoldAccent
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

                DutyTypePill(shiftType = shift.shiftType, assignmentType = shift.assignmentType)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isTimeOff) "Scheduled Rest Day" else "Post: ${shift.dutyLocation} (${shift.startTime} - ${shift.endTime})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (!isTimeOff && shift.partnerName != null) {
                        Text(
                            text = "Partner: ${shift.partnerName}",
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

@Composable
fun DutyTypePill(shiftType: String, assignmentType: String) {
    val (label, bg, fg) = when {
        assignmentType == "TIME_OFF" || shiftType == "OFF" -> Triple("TIME OFF", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        assignmentType == "EXAM" -> Triple("EXAM DUTY", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        assignmentType == "ESCORT" -> Triple("ESCORT", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        shiftType == "DAY" -> Triple("DAY SHIFT", GoldAccent.copy(alpha = 0.2f), GoldAccent)
        shiftType == "NIGHT" -> Triple("NIGHT SHIFT", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        else -> Triple(shiftType, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
