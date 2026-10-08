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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GuardDutyState
import com.example.data.model.Shift
import com.example.ui.components.SgmisCard
import com.example.ui.components.SgmisPrimaryButton
import com.example.ui.components.SgmisSecondaryButton
import com.example.ui.components.SgmisStatusCard
import com.example.ui.navigation.NavRoutes
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisUiState
import com.example.ui.viewmodel.SgmisViewModel

/**
 * Guard Home Experience (Blueprint Page 14 & Phase 6 Core Redesign).
 * Authoritatively answers within seconds:
 * 1. Am I on duty?
 * 2. What shift am I working?
 * 3. Where am I supposed to be?
 * 4. What do I need to do right now?
 * 5. Is anything urgent?
 * 6. What happened recently?
 */
@Composable
fun GuardHomeScreen(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState,
    todayFormatted: String,
    gpsStatusText: String,
    deviceLocation: Pair<Double, Double>?,
    nextDutyShift: Shift?,
    onOpenMessages: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val user = uiState.currentUser
    val shift = uiState.todayShift
    val dutyState = uiState.guardDutyState
    val isOnDuty = dutyState == GuardDutyState.ON_DUTY

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ==============================================================
        // LEVEL 1: AUTHORITATIVE CURRENT DUTY STATUS HERO
        // ==============================================================
        when (dutyState) {
            // ----------------------------------------------------------
            // 1. ACTIVE ON DUTY
            // ----------------------------------------------------------
            GuardDutyState.ON_DUTY -> {
                SgmisStatusCard(
                    statusColor = StatusSuccess,
                    modifier = Modifier.testTag("quick_shift_status_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = StatusSuccess,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "● ON DUTY",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = NavyDark.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "GPS Verified",
                                        color = NavyDark,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Text(
                                text = "${shift?.shiftType ?: "DAY"} SHIFT",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondaryLight
                            )
                        }

                        Column {
                            Text(
                                text = "${user?.fullName ?: user?.username ?: "Officer"} (${shift?.startTime ?: "07:00"}–${shift?.endTime ?: "18:00"})",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimaryLight
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Post: ${uiState.currentStationName}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondaryLight
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = gpsStatusText,
                                fontSize = 11.sp,
                                color = if (deviceLocation != null) StatusSuccess else TextSecondaryLight
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SgmisSecondaryButton(
                                text = "Post Relief / Handover",
                                onClick = { onNavigate(NavRoutes.HANDOVER) },
                                modifier = Modifier.weight(1f),
                                minHeight = 44.dp
                            )
                            SgmisPrimaryButton(
                                text = "Clock Out Console",
                                onClick = { onNavigate(NavRoutes.TODAY_SHIFT) },
                                modifier = Modifier.weight(1f),
                                minHeight = 44.dp
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------
            // 2. READY FOR DUTY / REASSIGNED
            // ----------------------------------------------------------
            GuardDutyState.ELIGIBLE_FOR_DUTY, GuardDutyState.REASSIGNED -> {
                val isReassigned = dutyState == GuardDutyState.REASSIGNED
                SgmisStatusCard(
                    statusColor = StatusWarning,
                    modifier = Modifier.testTag("quick_shift_status_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = StatusWarning,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isReassigned) "⚡ REASSIGNED DUTY" else "▲ READY TO REPORT",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Text(
                                text = "${shift?.shiftType ?: "DAY"} SHIFT",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondaryLight
                            )
                        }

                        Column {
                            Text(
                                text = if (isReassigned) "Assigned Post: ${uiState.currentStationName}" else "Scheduled at ${uiState.currentStationName}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimaryLight
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Hours: ${shift?.startTime ?: "07:00"}–${shift?.endTime ?: "18:00"}",
                                fontSize = 13.sp,
                                color = TextSecondaryLight
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Reporting window active. Clock in to unlock operational logs, patrol recording, and occurrence book.",
                                fontSize = 12.sp,
                                color = TextSecondaryLight
                            )
                        }

                        SgmisPrimaryButton(
                            text = "PROCEED TO CLOCK IN CONSOLE",
                            onClick = { onNavigate(NavRoutes.TODAY_SHIFT) },
                            leadingIcon = Icons.Default.AccessTime,
                            minHeight = 52.dp
                        )
                    }
                }
            }

            // ----------------------------------------------------------
            // 3. OFF DUTY / RESTING
            // ----------------------------------------------------------
            GuardDutyState.OFF_DUTY, GuardDutyState.TIME_OFF, GuardDutyState.EARLY_EXIT_PENDING -> {
                SgmisStatusCard(
                    statusColor = TextSecondaryLight,
                    modifier = Modifier.testTag("profile_banner_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = BorderSubtleLight,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "○ OFF DUTY • RESTING",
                                    color = TextSecondaryLight,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Text(
                                text = uiState.currentStationName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondaryLight
                            )
                        }

                        Column {
                            Text(
                                text = user?.fullName ?: user?.username ?: "Security Officer",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryLight
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Operational post logging is locked while off duty. Rest and recharge.",
                                fontSize = 13.sp,
                                color = TextSecondaryLight
                            )
                        }
                    }
                }

                // Next Scheduled Duty Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    border = BorderStroke(1.dp, BorderSubtleLight)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NEXT DUTY SCHEDULE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NavyDark,
                                letterSpacing = 0.5.sp
                            )
                            Icon(
                                imageVector = Icons.Default.EventAvailable,
                                contentDescription = null,
                                tint = NavyDark,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        if (nextDutyShift != null) {
                            Text(
                                text = "${nextDutyShift.date} • ${nextDutyShift.shiftType} Shift (${nextDutyShift.startTime}–${nextDutyShift.endTime})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryLight
                            )
                            Text(
                                text = "Station: ${nextDutyShift.stationName}",
                                fontSize = 12.sp,
                                color = TextSecondaryLight
                            )
                        } else {
                            Text(
                                text = "No upcoming shifts on active server roster.",
                                fontSize = 13.sp,
                                color = TextSecondaryLight
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------
            // 4. ON LEAVE
            // ----------------------------------------------------------
            GuardDutyState.ON_LEAVE -> {
                SgmisStatusCard(
                    statusColor = StatusWarning,
                    modifier = Modifier.testTag("profile_banner_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = StatusWarning,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "★ ON AUTHORIZED LEAVE",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            text = user?.fullName ?: user?.username ?: "Security Officer",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                        Text(
                            text = "Enjoy your scheduled leave period. Operational post logging remains locked until your return date.",
                            fontSize = 13.sp,
                            color = TextSecondaryLight
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        SgmisSecondaryButton(
                            text = "View Leave Entitlements",
                            onClick = { onNavigate(NavRoutes.LEAVE) },
                            minHeight = 40.dp
                        )
                    }
                }
            }

            // ----------------------------------------------------------
            // 5. SPECIAL DUTY (EXAM OR ESCORT)
            // ----------------------------------------------------------
            GuardDutyState.EXAM, GuardDutyState.ESCORT -> {
                val isExam = dutyState == GuardDutyState.EXAM
                SgmisStatusCard(
                    statusColor = NavyDark,
                    modifier = Modifier.testTag("quick_shift_status_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = NavyDark,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (isExam) "EXAM DUTY DETAIL" else "ARMED ESCORT DETAIL",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            text = if (isExam) "Supervising Academic Examination" else "Armed Convoy & Escort Security",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                        Text(
                            text = "Special mission active. Dedicated mission log and protocols apply.",
                            fontSize = 13.sp,
                            color = TextSecondaryLight
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SgmisPrimaryButton(
                            text = if (isExam) "Open Exam Duty Log" else "Open Escort Mission Log",
                            onClick = { onNavigate(if (isExam) NavRoutes.EXAM_DUTIES else NavRoutes.ESCORT_DUTIES) },
                            minHeight = 44.dp
                        )
                    }
                }
            }
        }

        // ==============================================================
        // LEVEL 2: COMMUNICATIONS & DISPATCH ATTENTION
        // ==============================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenMessages() }
                    .testTag("nav_direct_messages"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = LightSurface),
                border = BorderStroke(1.dp, BorderSubtleLight),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Chat, null, tint = NavyDark, modifier = Modifier.size(22.dp))
                        if (uiState.unreadMessageCount > 0) {
                            Surface(color = NavyDark, shape = RoundedCornerShape(10.dp)) {
                                Text(
                                    text = "${uiState.unreadMessageCount} new",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text("Messages", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                    Text("Supervisor comms", fontSize = 11.sp, color = TextSecondaryLight)
                }
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onNavigate(NavRoutes.NOTIFICATIONS) }
                    .testTag("nav_notifications"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = LightSurface),
                border = BorderStroke(1.dp, BorderSubtleLight),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, null, tint = NavyDark, modifier = Modifier.size(22.dp))
                        if (uiState.unreadNotificationCount > 0) {
                            Surface(color = StatusError, shape = RoundedCornerShape(10.dp)) {
                                Text(
                                    text = "${uiState.unreadNotificationCount} new",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text("Notifications", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                    Text("Official dispatches", fontSize = 11.sp, color = TextSecondaryLight)
                }
            }
        }

        // ==============================================================
        // LEVEL 3: PRIMARY OPERATIONAL ACTIONS
        // ==============================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (isOnDuty) "Operational Actions" else "Post Operations (Locked When Off Duty)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )

            val actions = listOf(
                BlueprintAction(
                    title = "Occurrence Book",
                    subtitle = "Log incidents & events",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    route = NavRoutes.OCCURRENCE_BOOK,
                    testTag = "nav_ob",
                    isLocked = !isOnDuty
                ),
                BlueprintAction(
                    title = "Visitor Book",
                    subtitle = "Register passes & visitors",
                    icon = Icons.Default.Badge,
                    route = NavRoutes.VISITOR_BOOK,
                    testTag = "nav_visitors",
                    isLocked = !isOnDuty
                ),
                BlueprintAction(
                    title = "Patrol Route",
                    subtitle = "Verify RFID checkpoints",
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    route = NavRoutes.PATROL,
                    testTag = "nav_patrol",
                    isLocked = !isOnDuty
                ),
                BlueprintAction(
                    title = "Shift Handover",
                    subtitle = "Duty relief & equipment",
                    icon = Icons.Default.SwapHoriz,
                    route = NavRoutes.HANDOVER,
                    testTag = "nav_handover",
                    isLocked = !isOnDuty
                ),
                BlueprintAction(
                    title = "Incident Report",
                    subtitle = "Security breach or hazard",
                    icon = Icons.Default.ReportProblem,
                    route = NavRoutes.INCIDENTS,
                    testTag = "nav_incidents",
                    isLocked = !isOnDuty
                ),
                BlueprintAction(
                    title = "SOS Emergency",
                    subtitle = "Immediate assistance",
                    icon = Icons.Default.Warning,
                    route = NavRoutes.SOS,
                    testTag = "nav_emergency_sos",
                    isEmergency = true,
                    isLocked = !isOnDuty
                ),
                BlueprintAction(
                    title = "My Roster",
                    subtitle = "Personal shift schedule",
                    icon = Icons.Default.CalendarMonth,
                    route = NavRoutes.MY_ROSTER,
                    testTag = "nav_guard_duty_plan",
                    isLocked = false
                ),
                BlueprintAction(
                    title = "Leave Requests",
                    subtitle = "Balances & applications",
                    icon = Icons.AutoMirrored.Filled.EventNote,
                    route = NavRoutes.LEAVE,
                    testTag = "nav_leave",
                    isLocked = false
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (pair in actions.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (action in pair) {
                            BlueprintActionCard(
                                action = action,
                                onClick = {
                                    if (action.isLocked) {
                                        val alertMsg = if (dutyState == GuardDutyState.ELIGIBLE_FOR_DUTY) {
                                            "Duty Lock: Please Clock In to unlock operational modules."
                                        } else {
                                            "Duty Lock: You must be CLOCKED IN (On Duty) to access operational records."
                                        }
                                        viewModel.postSecurityAlert(alertMsg)
                                    } else {
                                        onNavigate(action.route)
                                    }
                                },
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
        // LEVEL 4: RECENT ACTIVITY & TELEMETRY
        // ==============================================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LightSurface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderSubtleLight)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Date", fontSize = 11.sp, color = TextSecondaryLight)
                    Text(text = todayFormatted, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                }
                Box(modifier = Modifier.width(1.dp).height(20.dp).background(BorderSubtleLight))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "OB Logs", fontSize = 11.sp, color = TextSecondaryLight)
                    Text(text = "${uiState.obEntries.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NavyDark)
                }
                Box(modifier = Modifier.width(1.dp).height(20.dp).background(BorderSubtleLight))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Visitors", fontSize = 11.sp, color = TextSecondaryLight)
                    Text(text = "${uiState.visitors.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NavyDark)
                }
                Box(modifier = Modifier.width(1.dp).height(20.dp).background(BorderSubtleLight))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Patrols", fontSize = 11.sp, color = TextSecondaryLight)
                    Text(text = "${uiState.patrolLogs.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NavyDark)
                }
            }
        }
    }
}
