package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: SgmisViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val user = uiState.currentUser
    val shift = uiState.todayShift
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "SMART SECURITY",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshAllData() },
                        modifier = Modifier.testTag("refresh_dashboard_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Data")
                    }
                    IconButton(
                        onClick = { onNavigate("settings") },
                        modifier = Modifier.testTag("dashboard_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings & About")
                    }
                    IconButton(
                        onClick = { viewModel.logout() },
                        modifier = Modifier.testTag("logout_button")
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Personnel Profile Banner Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_banner_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = user?.fullName ?: user?.username ?: "Security Officer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = user?.role ?: "GUARD",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Employee ID: ${user?.employeeNumber ?: "SEC-ACTIVE"} • ${user?.rank ?: "Security Officer"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = "Station: ${user?.stationName ?: shift?.stationName ?: "Central Operations"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Quick Shift Duty Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate("today_shift") }
                    .testTag("quick_shift_status_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TODAY'S DUTY ASSIGNMENT",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (shift != null) {
                            AttendanceStatusPill(shift.attendanceStatus)
                        }
                    }

                    if (shift != null) {
                        Text(
                            text = "${shift.stationName} (${shift.shiftType} Shift)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Hours: ${shift.startTime} - ${shift.endTime} | Partner: ${shift.partnerName ?: "Solo"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "No active shift assigned on today's roster.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "View Duty Console →",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            val role = user?.role?.uppercase() ?: "GUARD"
            val isSupervisorOrAdmin = role in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")

            // Supervisory / Administrative Command Overview Card
            if (isSupervisorOrAdmin) {
                var isTelemetryExpanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isTelemetryExpanded = !isTelemetryExpanded }
                        .testTag("telemetry_overview_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (role == "SUPERVISOR") "SUPERVISOR COMMAND STATUS" else "SYSTEM TELEMETRY & OPERATIONS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (isTelemetryExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isTelemetryExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${uiState.telemetry.totalGuards.coerceAtLeast(uiState.users.size)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Personnel", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${uiState.incidents.count { it.status != "RESOLVED" }}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                Text("Open Incidents", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${uiState.leaveApplications.count { it.status == "PENDING" }}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                                Text("Pending Leave", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${uiState.stations.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Stations", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        AnimatedVisibility(visible = isTelemetryExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // Incident Breakdown
                                val incBreakdown = uiState.telemetry.incidentBreakdown
                                if (!incBreakdown.isNullOrEmpty()) {
                                    Text(
                                        text = "Incidents Breakdown",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        incBreakdown.forEach { (k, v) ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surface,
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = "$v",
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        text = k.replace("_", " "),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Patrol Breakdown
                                val patrolBreakdown = uiState.telemetry.patrolBreakdown
                                if (!patrolBreakdown.isNullOrEmpty()) {
                                    Text(
                                        text = "Patrols Breakdown",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        patrolBreakdown.forEach { (k, v) ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surface,
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = "$v",
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                    Text(
                                                        text = k.replace("_", " "),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Attendance Breakdown
                                val attBreakdown = uiState.telemetry.attendanceBreakdown
                                if (!attBreakdown.isNullOrEmpty()) {
                                    Text(
                                        text = "Attendance Breakdown",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        attBreakdown.forEach { (k, v) ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surface,
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = "$v",
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = StatusSuccess
                                                    )
                                                    Text(
                                                        text = k.replace("_", " "),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1
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

            // Command Operations Navigation Grid
            Text(
                text = if (isSupervisorOrAdmin) "Management & Operations Modules" else "Operational Modules",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val menuItems = mutableListOf(
                ModuleNav("today_shift", "Today's Duty", "Clock in, view partner & post details", Icons.Default.Schedule, "nav_today_shift"),
                ModuleNav("guard_duty_plan", "Guard Duty Plan", "View upcoming rotation & schedule", Icons.Default.EventAvailable, "nav_guard_duty_plan"),
                ModuleNav("handover", "Shift Handover", "Transfer duties to incoming guard", Icons.Default.SwapHoriz, "nav_handover"),
                ModuleNav("occurrence_book", "Occurrence Book", "Official OB records & logs", Icons.Default.MenuBook, "nav_ob"),
                ModuleNav("visitors", "Visitor Register", "Official visitor logs & gate passes", Icons.Default.Badge, "nav_visitors"),
                ModuleNav("incidents", "Incident Reports", "File & review station incidents", Icons.Default.Warning, "nav_incidents"),
                ModuleNav("patrol", "Station Patrols", "Verify inspection checkpoints", Icons.Default.DirectionsWalk, "nav_patrol"),
                ModuleNav("leave", "Leave Manager", "Entitlement balances & requests", Icons.Default.EventNote, "nav_leave"),
                ModuleNav("additional_duties", "Escort Duties", "Vehicle & exam security escorts", Icons.Default.DirectionsCar, "nav_additional_duties"),
                ModuleNav("reports", "Operations Reports", "Filter & analyze station records", Icons.Default.Assessment, "nav_reports"),
                ModuleNav("emergency_sos", "Emergency SOS", "Immediate distress alert beacon", Icons.Default.Sos, "nav_emergency_sos"),
                ModuleNav("notifications", "Notifications", "Operational alerts and messages", Icons.Default.Notifications, "nav_notifications"),
                ModuleNav("profile", "My Profile", "View organizational credentials & info", Icons.Default.AccountCircle, "nav_profile"),
                ModuleNav("settings", "Settings & About", "Theme, app version & preferences", Icons.Default.Settings, "nav_settings")
            )

            if (isSupervisorOrAdmin) {
                menuItems.add(2, ModuleNav("attendance_management", "Attendance Console", "Real-time clock-in & post monitoring", Icons.Default.HowToReg, "nav_attendance_management"))
                menuItems.add(5, ModuleNav("users", "Personnel & Guards", "Staff & station assignments", Icons.Default.People, "nav_users"))
                menuItems.add(6, ModuleNav("stations", "Stations & Pairs", "Posts, checkpoints & pairs", Icons.Default.Business, "nav_stations"))
                menuItems.add(7, ModuleNav("roster", "Duty Roster Engine", "Automated rotation & shifts", Icons.Default.CalendarMonth, "nav_roster"))
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (rowItems in menuItems.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (item in rowItems) {
                            val badge = if (item.route == "notifications") uiState.unreadNotificationCount else 0
                            ModuleCard(
                                item = item,
                                badgeCount = badge,
                                onClick = { onNavigate(item.route) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ModuleNav(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val testTag: String
)

@Composable
fun ModuleCard(
    item: ModuleNav,
    badgeCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(130.dp)
            .clickable(onClick = onClick)
            .testTag(item.testTag),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (badgeCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (badgeCount > 99) "99+" else "$badgeCount",
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
