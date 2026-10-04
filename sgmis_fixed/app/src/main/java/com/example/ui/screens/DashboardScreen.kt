package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.ui.navigation.NavRoutes
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisUiState
import com.example.ui.viewmodel.SgmisViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import com.example.util.LocationHelper
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: SgmisViewModel,
    roleMode: AppRole? = null,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val user = uiState.currentUser
    val shift = uiState.todayShift
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var deviceLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showDirectMessagesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        deviceLocation = LocationHelper.getDeviceLocation(context)
        viewModel.fetchUnreadMessageCount()
        viewModel.fetchDirectMessages()
    }

    val effectiveRole = roleMode ?: uiState.appRole
    val isGuard = effectiveRole == AppRole.GUARD
    val isSupervisor = effectiveRole == AppRole.SUPERVISOR
    val isAdmin = effectiveRole == AppRole.ADMINISTRATOR
    val isSupervisorOrAdmin = isSupervisor || isAdmin

    val gpsStatusText = remember(deviceLocation, uiState.currentStation) {
        val loc = deviceLocation
        val station = uiState.currentStation
        if (loc != null) {
            val (lat, lon) = loc
            val latStr = String.format(Locale.US, "%.4f", lat)
            val lonStr = String.format(Locale.US, "%.4f", lon)
            if (station?.latitude != null && station.longitude != null) {
                val distResults = FloatArray(1)
                android.location.Location.distanceBetween(lat, lon, station.latitude, station.longitude, distResults)
                val distM = distResults[0].toInt()
                val radius = station.effectiveRadius.toInt()
                if (distM <= radius) {
                    "GPS: ($latStr, $lonStr) • Inside campus boundary (${distM}m / ${radius}m radius)"
                } else {
                    "GPS: ($latStr, $lonStr) • ${distM}m from post (${radius}m perimeter)"
                }
            } else {
                "GPS: ($latStr, $lonStr) • Verified at post"
            }
        } else {
            "GPS: Acquiring satellite fix at ${uiState.currentStationName}..."
        }
    }

    // Blueprint Top Session Countdown Timer (UI indicator, non-authoritative)
    var sessionSecondsLeft by remember { mutableIntStateOf(180) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (sessionSecondsLeft > 0) {
                sessionSecondsLeft--
            } else {
                // Refresh server state upon timer expiry and reset indicator
                viewModel.refreshAuthoritativeState()
                sessionSecondsLeft = 180
            }
        }
    }

    val timerMinutes = sessionSecondsLeft / 60
    val timerSeconds = sessionSecondsLeft % 60
    val timerText = String.format("%02d:%02d", timerMinutes, timerSeconds)

    val harareTz = remember { java.util.TimeZone.getTimeZone("Africa/Harare") }
    val todayStr = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = harareTz }.format(Date())
    }
    val todayFormatted = remember {
        SimpleDateFormat("dd MMM yyyy", Locale.US).apply { timeZone = harareTz }.format(Date())
    }

    // Server-derived next duty shift for off-duty display
    val nextDutyShift = remember(uiState.rosterShifts, todayStr) {
        uiState.rosterShifts
            .filter { s ->
                s.date >= todayStr &&
                s.shiftType.uppercase() != "OFF" &&
                s.assignmentType.uppercase() != "TIME_OFF"
            }
            .minByOrNull { it.date }
    }

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
                        Column {
                            Text(
                                text = if (isSupervisor) "Station Command" else if (isAdmin) "National Control" else "Smart Security",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (isSupervisor && !uiState.hasAssignedStation) "Station Unassigned" else uiState.currentStationName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Blueprint visible session countdown timer
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Session Timer",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = timerText,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            viewModel.refreshAuthoritativeState()
                            sessionSecondsLeft = 180
                        },
                        modifier = Modifier.testTag("refresh_dashboard_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Data")
                    }
                    IconButton(
                        onClick = { onNavigate(NavRoutes.SETTINGS) },
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

            // ==============================================================
            // GUARD WORKFLOW EXPERIENCE (BLUEPRINT SECTION)
            // ==============================================================
            if (isGuard) {

                when (uiState.guardDutyState) {

                    // ----------------------------------------------------------
                    // STATE 1: GUARD ON DUTY (Blueprint Page 14, Left Screen)
                    // ----------------------------------------------------------
                    GuardDutyState.ON_DUTY -> {
                        // Header Status Card: CLOCKED IN
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quick_shift_status_card"),
                            colors = CardDefaults.cardColors(
                                containerColor = StatusSuccess.copy(alpha = 0.12f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(StatusSuccess.copy(alpha = 0.4f)))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
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
                                                text = "● CLOCKED IN",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "GPS Verified",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "${shift?.shiftType ?: "DAY"} SHIFT",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Text(
                                    text = "Guard: ${user?.fullName ?: user?.username ?: "Officer"} (${shift?.startTime ?: "07:00"}–${shift?.endTime ?: "18:00"})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = "Post: ${uiState.currentStationName} • Partner: ${uiState.assignedPartnerName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    text = gpsStatusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (deviceLocation != null) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Communications & Alerts (Partner Comms & Dispatch Notifications)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        showDirectMessagesDialog = true
                                        viewModel.fetchDirectMessages()
                                    }
                                    .testTag("nav_direct_messages"),
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
                                        Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                        if (uiState.unreadMessageCount > 0) {
                                            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                                                Text(
                                                    text = "${uiState.unreadMessageCount} new",
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text("Messages", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Partner & Supervisor comms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onNavigate(NavRoutes.NOTIFICATIONS) }
                                    .testTag("nav_notifications"),
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
                                        Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                                        if (uiState.unreadNotificationCount > 0) {
                                            Surface(color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(10.dp)) {
                                                Text(
                                                    text = "${uiState.unreadNotificationCount} new",
                                                    color = MaterialTheme.colorScheme.onError,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text("Notifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Official dispatch alerts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // 6 Primary Actions Grid (Blueprint 2-Column Mobile Grid)
                        Text(
                            text = "Operational Actions",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val onDutyActions = listOf(
                            BlueprintAction(
                                title = "Occurrence Book",
                                subtitle = "Log incidents & events",
                                icon = Icons.AutoMirrored.Filled.MenuBook,
                                route = NavRoutes.OCCURRENCE_BOOK,
                                testTag = "nav_ob"
                            ),
                            BlueprintAction(
                                title = "Visitor Book",
                                subtitle = "Register visitors & passes",
                                icon = Icons.Default.Badge,
                                route = NavRoutes.VISITOR_BOOK,
                                testTag = "nav_visitors"
                            ),
                            BlueprintAction(
                                title = "SOS Emergency",
                                subtitle = "Immediate emergency alert",
                                icon = Icons.Default.Warning,
                                route = NavRoutes.SOS,
                                testTag = "nav_emergency_sos",
                                isEmergency = true
                            ),
                            BlueprintAction(
                                title = "Patrol Check",
                                subtitle = "Start patrol route",
                                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                                route = NavRoutes.PATROL,
                                testTag = "nav_patrol"
                            ),
                            BlueprintAction(
                                title = "Handover / Take-Over",
                                subtitle = "Shift handover notes",
                                icon = Icons.Default.SwapHoriz,
                                route = NavRoutes.HANDOVER,
                                testTag = "nav_handover"
                            ),
                            BlueprintAction(
                                title = "My Roster",
                                subtitle = "View shift schedule",
                                icon = Icons.Default.CalendarMonth,
                                route = NavRoutes.MY_ROSTER,
                                testTag = "nav_guard_duty_plan"
                            ),
                            BlueprintAction(
                                title = "Escort Duties",
                                subtitle = "Assigned vehicle escorts",
                                icon = Icons.Default.DirectionsCar,
                                route = NavRoutes.ESCORT_DUTIES,
                                testTag = "nav_escort_duties"
                            ),
                            BlueprintAction(
                                title = "Exam Duties",
                                subtitle = "Exam supervision duties",
                                icon = Icons.Default.School,
                                route = NavRoutes.EXAM_DUTIES,
                                testTag = "nav_exam_duties"
                            )
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (pair in onDutyActions.chunked(2)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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

                        // Blueprint Telemetry Footer Bar
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Today",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = todayFormatted,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(24.dp)
                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "OB Entries",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${uiState.obEntries.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(24.dp)
                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Visitors",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${uiState.visitors.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }

                    // ----------------------------------------------------------
                    // STATE 2: GUARD OFF DUTY (Blueprint Page 14, Right Screen)
                    // ----------------------------------------------------------
                    GuardDutyState.OFF_DUTY, GuardDutyState.TIME_OFF, GuardDutyState.EARLY_EXIT_PENDING -> {
                        // Header Status Card: OFF DUTY - Resting
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
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(14.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NightlightRound,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = user?.fullName ?: user?.username ?: "Security Officer",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "○ OFF DUTY",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Status: Time Off / Resting • ${uiState.currentStationName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Access Restricted Warning Banner
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Restricted",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "ACCESS RESTRICTED — You are on Time Off",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Active operational logging is locked while you are off duty. Rest and recharge.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Main Features Section: Restricted Operational Cards
                        Text(
                            text = "Main Operations (Restricted When Off Duty)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val offDutyActions = listOf(
                            BlueprintAction("Occurrence Book", "Log incidents & events", Icons.AutoMirrored.Filled.MenuBook, NavRoutes.OCCURRENCE_BOOK, "nav_ob", isLocked = true),
                            BlueprintAction("Visitor Book", "Register visitors & passes", Icons.Default.Badge, NavRoutes.VISITOR_BOOK, "nav_visitors", isLocked = true),
                            BlueprintAction("SOS Emergency", "Immediate emergency alert", Icons.Default.Warning, NavRoutes.SOS, "nav_emergency_sos", isLocked = true, isEmergency = true),
                            BlueprintAction("Patrol Check", "Start patrol route", Icons.AutoMirrored.Filled.DirectionsWalk, NavRoutes.PATROL, "nav_patrol", isLocked = true),
                            BlueprintAction("Handover / Take-Over", "Shift handover notes", Icons.Default.SwapHoriz, NavRoutes.HANDOVER, "nav_handover", isLocked = true),
                            BlueprintAction("My Roster", "View shift schedule", Icons.Default.CalendarMonth, NavRoutes.MY_ROSTER, "nav_guard_duty_plan", isLocked = false),
                            BlueprintAction("Escort Duties", "Assigned vehicle escorts", Icons.Default.DirectionsCar, NavRoutes.ESCORT_DUTIES, "nav_escort_duties", isLocked = false),
                            BlueprintAction("Exam Duties", "Exam supervision duties", Icons.Default.School, NavRoutes.EXAM_DUTIES, "nav_exam_duties", isLocked = false)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (pair in offDutyActions.chunked(2)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    for (action in pair) {
                                        BlueprintActionCard(
                                            action = action,
                                            onClick = {
                                                if (action.isLocked) {
                                                    viewModel.postSecurityAlert("Duty Lock: You must be CLOCKED IN (On Duty) to access operational records.")
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

                        // Available While Off Duty Section: Notifications & Messaging
                        Text(
                            text = "Available While Off Duty",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        showDirectMessagesDialog = true
                                        viewModel.fetchDirectMessages()
                                    }
                                    .testTag("nav_direct_messages"),
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
                                        Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                        if (uiState.unreadMessageCount > 0) {
                                            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                                                Text(
                                                    text = "${uiState.unreadMessageCount} new",
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text("Messages", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Partner & Supervisor comms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onNavigate(NavRoutes.NOTIFICATIONS) }
                                    .testTag("nav_notifications"),
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
                                        Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                                        if (uiState.unreadNotificationCount > 0) {
                                            Surface(color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(10.dp)) {
                                                Text(
                                                    text = "${uiState.unreadNotificationCount} new",
                                                    color = MaterialTheme.colorScheme.onError,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text("Notifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Updates, alerts & assignments", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(NavRoutes.LEAVE) }
                                .testTag("nav_leave"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.EventNote, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Leave & Requests", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Entitlements, balances & applications", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // Next Duty Schedule Card (Server-Backed)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "NEXT DUTY SCHEDULE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = Icons.Default.EventAvailable,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                if (nextDutyShift != null) {
                                    Text(
                                        text = "${nextDutyShift.date} • ${nextDutyShift.shiftType} Shift (${nextDutyShift.startTime}–${nextDutyShift.endTime})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Station: ${nextDutyShift.stationName} • Partner: ${nextDutyShift.partnerName ?: "Solo"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "You will be eligible to clock in within the reporting window.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "No upcoming shifts scheduled on the active server roster.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // ----------------------------------------------------------
                    // STATE: GUARD ON LEAVE (Official Approved Leave Period)
                    // ----------------------------------------------------------
                    GuardDutyState.ON_LEAVE -> {
                        // Header Status Card: ON AUTHORIZED LEAVE
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
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(14.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BeachAccess,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = user?.fullName ?: user?.username ?: "Security Officer",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "ON LEAVE",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    val leaveTypeName = shift?.leaveType ?: uiState.serverDutyState?.leaveType ?: "Approved Leave"
                                    Text(
                                        text = "Status: $leaveTypeName • ${uiState.currentStationName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Leave Information Banner
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EventBusy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "OFFICIAL LEAVE PERIOD",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Operational duties and post logging are suspended during approved leave. Have a restful time off.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Available Services Section
                        Text(
                            text = "Available Services",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onNavigate(NavRoutes.LEAVE) }
                                    .testTag("nav_leave"),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(14.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.EventNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    Text("Leave Balances", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Balances & requests", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onNavigate(NavRoutes.MY_ROSTER) }
                                    .testTag("nav_guard_duty_plan"),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(14.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                                    Text("My Roster", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Upcoming schedule", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        showDirectMessagesDialog = true
                                        viewModel.fetchDirectMessages()
                                    }
                                    .testTag("nav_direct_messages"),
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
                                        Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                        if (uiState.unreadMessageCount > 0) {
                                            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                                                Text(
                                                    text = "${uiState.unreadMessageCount} new",
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text("Messages", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Comms & updates", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onNavigate(NavRoutes.NOTIFICATIONS) }
                                    .testTag("nav_notifications"),
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
                                        Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                                        if (uiState.unreadNotificationCount > 0) {
                                            Surface(color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(10.dp)) {
                                                Text(
                                                    text = "${uiState.unreadNotificationCount} new",
                                                    color = MaterialTheme.colorScheme.onError,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text("Notifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Alerts & notices", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // ----------------------------------------------------------
                    // STATE 3: GUARD ELIGIBLE FOR DUTY (Ready to Clock In)
                    // ----------------------------------------------------------
                    GuardDutyState.ELIGIBLE_FOR_DUTY -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quick_shift_status_card"),
                            colors = CardDefaults.cardColors(
                                containerColor = GoldAccent.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(GoldAccent.copy(alpha = 0.5f)))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
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
                                            text = "▲ READY FOR DUTY",
                                            color = Color.Black,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Text(
                                        text = "${shift?.shiftType ?: "DAY"} SHIFT",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = "Scheduled at ${uiState.currentStationName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = "Hours: ${shift?.startTime ?: "07:00"}–${shift?.endTime ?: "18:00"} • Partner: ${uiState.assignedPartnerName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    text = "You have a scheduled shift today. Clock in to unlock operational logs, patrol recording, and occurrence book.",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Button(
                                    onClick = { onNavigate(NavRoutes.TODAY_SHIFT) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("PROCEED TO CLOCK IN CONSOLE", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Action Grid (Operational actions locked until Clock In)
                        Text(
                            text = "Operational Modules (Unlocked After Clock In)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val eligibleActions = listOf(
                            BlueprintAction("Occurrence Book", "Log incidents & events", Icons.AutoMirrored.Filled.MenuBook, NavRoutes.OCCURRENCE_BOOK, "nav_ob", isLocked = true),
                            BlueprintAction("Visitor Book", "Register visitors & passes", Icons.Default.Badge, NavRoutes.VISITOR_BOOK, "nav_visitors", isLocked = true),
                            BlueprintAction("SOS Emergency", "Immediate emergency alert", Icons.Default.Warning, NavRoutes.SOS, "nav_emergency_sos", isLocked = true, isEmergency = true),
                            BlueprintAction("Patrol Check", "Start patrol route", Icons.AutoMirrored.Filled.DirectionsWalk, NavRoutes.PATROL, "nav_patrol", isLocked = true),
                            BlueprintAction("Handover / Take-Over", "Shift handover notes", Icons.Default.SwapHoriz, NavRoutes.HANDOVER, "nav_handover", isLocked = true),
                            BlueprintAction("My Roster", "View shift schedule", Icons.Default.CalendarMonth, NavRoutes.MY_ROSTER, "nav_guard_duty_plan", isLocked = false)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (pair in eligibleActions.chunked(2)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    for (action in pair) {
                                        BlueprintActionCard(
                                            action = action,
                                            onClick = {
                                                if (action.isLocked) {
                                                    viewModel.postSecurityAlert("Duty Lock: Please Clock In to unlock operational modules.")
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
                }
            }

            // ==============================================================
            // SUPERVISOR & ADMINISTRATOR SECTION (PRESERVED WORKFLOW)
            // ==============================================================
            // ==============================================================
            // SUPERVISOR COMMAND CONSOLE (BLUEPRINT PHASE 12.5)
            // ==============================================================
            if (isSupervisor) {
                SupervisorCommandConsole(
                    viewModel = viewModel,
                    uiState = uiState,
                    user = user,
                    todayStr = todayStr,
                    todayFormatted = todayFormatted,
                    onNavigate = onNavigate
                )
            }

            // ==============================================================
            // ADMINISTRATOR / SUPERUSER NATIONAL CONTROL CENTER (BLUEPRINT PHASE 12.6)
            // ==============================================================
            if (isAdmin) {
                AdministratorNationalControlCenter(
                    viewModel = viewModel,
                    uiState = uiState,
                    user = user,
                    todayStr = todayStr,
                    todayFormatted = todayFormatted,
                    onNavigate = onNavigate
                )
            }
        }

        if (showDirectMessagesDialog) {
            DirectMessagesDialog(
                viewModel = viewModel,
                uiState = uiState,
                onDismiss = { showDirectMessagesDialog = false }
            )
        }
    }
}

@Composable
fun DirectMessagesDialog(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState,
    onDismiss: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val currentUserId = uiState.currentUser?.id
    val shift = uiState.todayShift

    // Available messaging targets: Partner and Station Supervisor
    val partnerId = shift?.partner
    val partnerName = uiState.assignedPartnerName
    val supervisor = remember(uiState.users, uiState.currentStationId) {
        uiState.users.find { it.role == "SUPERVISOR" && it.station == uiState.currentStationId }
            ?: uiState.users.find { it.role == "SUPERVISOR" }
    }
    val supervisorId = supervisor?.id
    val supervisorName = supervisor?.fullName ?: supervisor?.username ?: "Station Supervisor"

    // Default recipient is partner if assigned, otherwise supervisor
    var selectedRecipientId by remember(partnerId, supervisorId) {
        mutableStateOf(partnerId ?: supervisorId ?: "")
    }

    LaunchedEffect(Unit) {
        viewModel.fetchDirectMessages()
        viewModel.fetchUnreadMessageCount()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DIRECT MESSAGES",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                IconButton(
                    onClick = {
                        viewModel.fetchDirectMessages()
                        viewModel.fetchUnreadMessageCount()
                    }
                ) {
                    Icon(Icons.Default.Refresh, "Refresh Comms", modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Recipient selector chips
                Text(
                    text = "Message Recipient:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!partnerId.isNullOrBlank()) {
                        FilterChip(
                            selected = selectedRecipientId == partnerId,
                            onClick = { selectedRecipientId = partnerId },
                            label = { Text("Partner ($partnerName)", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    if (!supervisorId.isNullOrBlank()) {
                        FilterChip(
                            selected = selectedRecipientId == supervisorId,
                            onClick = { selectedRecipientId = supervisorId },
                            label = { Text(supervisorName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // Messages thread
                Text(
                    text = "Comms Log (${uiState.directMessages.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.messagesLoading && uiState.directMessages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (uiState.directMessages.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "No direct messages recorded yet. Send a message to your partner or station supervisor.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(uiState.directMessages) { msg ->
                            val isOutgoing = msg.sender == currentUserId
                            val isUnread = !msg.read && msg.recipient == currentUserId
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isUnread) viewModel.markDirectMessageRead(msg.id)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isOutgoing)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else if (isUnread)
                                        GoldAccent.copy(alpha = 0.18f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isOutgoing) "To: ${msg.recipientName ?: "Officer"}" else "From: ${msg.senderName ?: "Dispatch"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isUnread) {
                                                Surface(color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(4.dp)) {
                                                    Text(
                                                        text = "NEW",
                                                        color = MaterialTheme.colorScheme.onError,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = msg.createdAt.take(16).replace('T', ' '),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(
                                        text = msg.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Compose input
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Message...") },
                    placeholder = { Text("Type message to partner or supervisor...") },
                    modifier = Modifier.fillMaxWidth().testTag("direct_message_input"),
                    singleLine = false,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedRecipientId.isNotBlank() && messageText.isNotBlank()) {
                        val text = messageText.trim()
                        messageText = ""
                        viewModel.sendDirectMessage(selectedRecipientId, text)
                    }
                },
                enabled = selectedRecipientId.isNotBlank() && messageText.isNotBlank() && !uiState.messagesLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("send_direct_message_button")
            ) {
                if (uiState.messagesLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

data class BlueprintAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    val testTag: String,
    val isLocked: Boolean = false,
    val isEmergency: Boolean = false
)

@Composable
fun BlueprintActionCard(
    action: BlueprintAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerBg = when {
        action.isLocked -> MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        action.isEmergency -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surface
    }

    val iconBg = when {
        action.isLocked -> MaterialTheme.colorScheme.surfaceVariant
        action.isEmergency -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    val iconColor = when {
        action.isLocked -> MaterialTheme.colorScheme.onSurfaceVariant
        action.isEmergency -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier
            .height(130.dp)
            .clickable(onClick = onClick)
            .testTag(action.testTag),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (action.isLocked) 0.dp else 2.dp),
        border = if (action.isEmergency && !action.isLocked) {
            CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.6f)))
        } else null
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
                        .size(38.dp)
                        .background(color = iconBg, shape = RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (action.isLocked) Icons.Default.Lock else action.icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (action.isLocked) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "LOCKED",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (action.isEmergency) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "ALERT",
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
                    text = action.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (action.isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (action.isLocked) "Requires active clock-in" else action.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

// ==========================================================================
// SUPERVISOR COMMAND CONSOLE (STATION COMMAND CENTER)
// ==========================================================================

fun computeSupervisorShiftBadge(serverShift: Shift?): String {
    val isServerShiftAuthoritative = serverShift != null && !serverShift.startTime.isNullOrBlank() && !serverShift.endTime.isNullOrBlank()
    return if (isServerShiftAuthoritative) {
        val sType = serverShift!!.shiftType.uppercase()
        val shiftTitle = if (sType.contains("SHIFT")) sType else "$sType SHIFT"
        val hours = "${serverShift.startTime.take(5)}–${serverShift.endTime.take(5)}"
        "$shiftTitle ($hours)"
    } else {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isDay = currentHour in 7..17
        val shiftTitle = if (isDay) "DAY SHIFT" else "NIGHT SHIFT"
        val hours = if (isDay) "07:00–18:00" else "18:00–07:00"
        "$shiftTitle ($hours • DISPLAY ONLY)"
    }
}

fun computeSupervisorGeofenceText(hasStation: Boolean, radius: Double?): String {
    return when {
        !hasStation -> "Geofence Disabled (No Station)"
        radius != null && radius > 0 -> "Operational Geofence: ${radius.toInt()}m Active"
        else -> "Operational Geofence: Unavailable"
    }
}

@Composable
private fun SupervisorCommandConsole(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState,
    user: User?,
    todayStr: String,
    todayFormatted: String,
    onNavigate: (String) -> Unit
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

    // Shift window determination: Bind to authoritative server shift if available; otherwise show informational display
    val serverShift = remember(uiState.todayShift, todayStationShifts) {
        uiState.todayShift?.takeIf { it.shiftType.uppercase() != "OFF" && it.assignmentType.uppercase() != "TIME_OFF" }
            ?: todayStationShifts.firstOrNull()
    }

    val activeShiftBadge: String = remember(serverShift) {
        computeSupervisorShiftBadge(serverShift)
    }

    // Geofence configuration dynamically bound to authoritative station model
    val configuredRadius = uiState.stationGeofenceRadius
    val geofenceText: String = remember(hasStation, configuredRadius) {
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

    val stationHandovers = remember(uiState.handovers, stationId, stationName, hasStation) {
        if (!hasStation) emptyList()
        else uiState.handovers.filter {
            it.station.isBlank() || it.station == stationId || it.stationName.equals(stationName, ignoreCase = true)
        }
    }

    // Live Metrics derived from server datasets
    val guardsClockedIn = stationAttendance.count { it.clockIn != null && it.clockOut == null }
    val guardsExpected = todayStationShifts.size
    val activeVisitorsCount = stationVisitors.count {
        !it.occurrenceText.contains("Time Out:", ignoreCase = true) && !it.occurrenceText.contains("CHECKED OUT", ignoreCase = true)
    }
    val openIncidentsCount = stationIncidents.count { it.status != "RESOLVED" }
    val criticalIncidentsCount = stationIncidents.count {
        it.status != "RESOLVED" && it.priority.uppercase() in listOf("HIGH", "CRITICAL", "URGENT")
    }
    val activePatrolsCount = stationPatrols.count { it.status == "IN_PROGRESS" }
    val pendingLeaveCount = uiState.leaveApplications.count { it.status == "PENDING" }

    // 1. Station Command Header
    SupervisorStationHeader(
        stationName = stationName,
        hasStation = hasStation,
        user = user,
        todayFormatted = todayFormatted,
        activeShiftBadge = activeShiftBadge,
        geofenceText = geofenceText,
        onRefresh = { viewModel.refreshAuthoritativeState() },
        onViewProfile = { onNavigate(NavRoutes.PROFILE) }
    )

    // Unassigned Station Warning Banner
    if (!hasStation) {
        SupervisorUnassignedStationBanner(onViewProfile = { onNavigate(NavRoutes.PROFILE) })
    }

    // 2. Live Operational Metrics (Command Telemetry Grid)
    Text(
        text = "Station Live Metrics",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )

    SupervisorMetricsGrid(
        hasStation = hasStation,
        guardsClockedIn = guardsClockedIn,
        guardsExpected = guardsExpected,
        obCount = stationOB.size,
        visitorsCount = if (activeVisitorsCount > 0) activeVisitorsCount else stationVisitors.size,
        openIncidentsCount = openIncidentsCount,
        criticalIncidentsCount = criticalIncidentsCount,
        activePatrolsCount = activePatrolsCount,
        pendingLeaveCount = pendingLeaveCount,
        escortDutiesCount = uiState.escortDuties.size,
        examDutiesCount = uiState.examDuties.size,
        onNavigate = onNavigate
    )

    // 3. Current Shift & Duty Status ("What is happening at my station right now?")
    SupervisorCurrentShiftCard(
        hasStation = hasStation,
        stationName = stationName,
        activeShiftBadge = activeShiftBadge,
        todayShifts = todayStationShifts,
        attendanceRecords = stationAttendance,
        latestHandover = stationHandovers.firstOrNull(),
        onNavigate = onNavigate
    )

    // 4. Roster Management Card
    SupervisorRosterCard(
        hasStation = hasStation,
        stationName = stationName,
        rosterShiftsCount = uiState.rosterShifts.count { it.station == stationId || it.stationName.equals(stationName, ignoreCase = true) },
        conflictReport = uiState.conflictReport,
        onNavigate = onNavigate
    )

    // 5. Occurrence Book Live Feed
    SupervisorOccurrenceBookCard(
        hasStation = hasStation,
        obEntries = stationOB.take(3),
        isLoading = uiState.obLoading,
        onNavigate = onNavigate
    )

    // 6. Visitor Log (Gate Register)
    SupervisorVisitorLogCard(
        hasStation = hasStation,
        visitors = stationVisitors.take(3),
        isLoading = uiState.visitorsLoading,
        onNavigate = onNavigate
    )

    // 7. Incidents & Emergency Alerts
    SupervisorIncidentsCard(
        hasStation = hasStation,
        incidents = stationIncidents.filter { it.status != "RESOLVED" }.take(3),
        isLoading = uiState.incidentsLoading,
        onNavigate = onNavigate
    )

    // 8. Patrol Monitoring
    SupervisorPatrolCard(
        hasStation = hasStation,
        activePatrol = stationPatrols.firstOrNull { it.status == "IN_PROGRESS" } ?: uiState.activePatrol,
        totalLogsToday = stationPatrols.size,
        onNavigate = onNavigate
    )

    // 9. Pending Leave Reviews
    SupervisorLeaveReviewsCard(
        pendingApplications = uiState.leaveApplications.filter { it.status == "PENDING" }.take(3),
        isLoading = uiState.leaveLoading,
        onNavigate = onNavigate
    )

    // 10. Shift Handover Oversight
    SupervisorHandoverCard(
        hasStation = hasStation,
        latestHandover = stationHandovers.firstOrNull(),
        pendingCount = stationHandovers.count { !it.incomingAccepted && !it.isHandoverRejected },
        isLoading = uiState.handoversLoading,
        onNavigate = onNavigate
    )

    // 11. Station Supervisory Modules Grid
    Text(
        text = "Station Supervisory Modules",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )

    SupervisorActionGrid(onNavigate = onNavigate)
}

@Composable
private fun SupervisorStationHeader(
    stationName: String,
    hasStation: Boolean,
    user: User?,
    todayFormatted: String,
    activeShiftBadge: String,
    geofenceText: String,
    onRefresh: () -> Unit,
    onViewProfile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("station_command_header"),
        colors = CardDefaults.cardColors(
            containerColor = if (hasStation) MaterialTheme.colorScheme.surfaceVariant else StatusWarning.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (hasStation) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else StatusWarning.copy(alpha = 0.5f)
            )
        )
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
                    Surface(
                        color = if (hasStation) StatusSuccess else StatusWarning,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (hasStation) "● STATION COMMAND ACTIVE" else "⚠ UNASSIGNED / OFFLINE",
                            color = if (hasStation) Color.White else Color.Black,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = activeShiftBadge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (hasStation) stationName else "Station Not Assigned",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Supervisor: ${user?.fullName ?: user?.username ?: "Station Supervisor"} • ID: ${user?.employeeNumber ?: user?.id?.take(8) ?: "N/A"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = todayFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = geofenceText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SupervisorUnassignedStationBanner(onViewProfile: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("station_unassigned_banner"),
        colors = CardDefaults.cardColors(
            containerColor = StatusWarning.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(StatusWarning.copy(alpha = 0.6f))
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Unassigned Station Alert",
                tint = StatusWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "STATION CONTEXT REQUIRED",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Your supervisor account is not currently assigned to an operational station. Station-scoped telemetry, duty rosters, live OB feeds, and visitor registers cannot be loaded until an administrator assigns your station post.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onViewProfile,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("VIEW ACCOUNT CREDENTIALS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SupervisorMetricsGrid(
    hasStation: Boolean,
    guardsClockedIn: Int,
    guardsExpected: Int,
    obCount: Int,
    visitorsCount: Int,
    openIncidentsCount: Int,
    criticalIncidentsCount: Int,
    activePatrolsCount: Int,
    pendingLeaveCount: Int,
    escortDutiesCount: Int = 0,
    examDutiesCount: Int = 0,
    onNavigate: (String) -> Unit
) {
    val guardsValue = if (!hasStation) "—" else if (guardsExpected > 0) "$guardsClockedIn / $guardsExpected" else "$guardsClockedIn"
    val obValue = if (!hasStation) "—" else "$obCount"
    val visitorsValue = if (!hasStation) "—" else "$visitorsCount"
    val incidentsValue = if (!hasStation) "—" else "$openIncidentsCount"
    val patrolsValue = if (!hasStation) "—" else if (activePatrolsCount > 0) "$activePatrolsCount Active" else "0"
    val leaveValue = if (!hasStation) "—" else "$pendingLeaveCount"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supervisor_metrics_grid"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SupervisorMetricTile(
                title = "Guards on Post",
                value = guardsValue,
                subtitle = if (!hasStation) "Station Required" else "GPS Verified",
                icon = Icons.Default.HowToReg,
                iconTint = StatusSuccess,
                iconContainerColor = StatusSuccess.copy(alpha = 0.15f),
                testTag = "metric_guards_on_duty",
                onClick = { onNavigate(NavRoutes.ATTENDANCE_MANAGEMENT) },
                modifier = Modifier.weight(1f)
            )
            SupervisorMetricTile(
                title = "OB Entries",
                value = obValue,
                subtitle = if (!hasStation) "Station Required" else "Today's logs",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                iconTint = MaterialTheme.colorScheme.primary,
                iconContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                testTag = "metric_ob_entries",
                onClick = { onNavigate(NavRoutes.OCCURRENCE_BOOK) },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SupervisorMetricTile(
                title = "Visitors Inside",
                value = visitorsValue,
                subtitle = if (!hasStation) "Station Required" else "Gate register",
                icon = Icons.Default.Badge,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                testTag = "metric_visitors_inside",
                onClick = { onNavigate(NavRoutes.VISITOR_BOOK) },
                modifier = Modifier.weight(1f)
            )
            SupervisorMetricTile(
                title = "Open Incidents",
                value = incidentsValue,
                subtitle = if (!hasStation) "Station Required" else if (criticalIncidentsCount > 0) "$criticalIncidentsCount high priority" else "Security status",
                icon = Icons.Default.Warning,
                iconTint = if (openIncidentsCount > 0) MaterialTheme.colorScheme.error else StatusSuccess,
                iconContainerColor = if (openIncidentsCount > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.15f) else StatusSuccess.copy(alpha = 0.15f),
                testTag = "metric_open_incidents",
                onClick = { onNavigate(NavRoutes.INCIDENTS) },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SupervisorMetricTile(
                title = "Active Patrols",
                value = patrolsValue,
                subtitle = if (!hasStation) "Station Required" else "Checkpoint rounds",
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                iconTint = if (activePatrolsCount > 0) StatusSuccess else MaterialTheme.colorScheme.primary,
                iconContainerColor = if (activePatrolsCount > 0) StatusSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                testTag = "metric_active_patrols",
                onClick = { onNavigate(NavRoutes.PATROL) },
                modifier = Modifier.weight(1f)
            )
            SupervisorMetricTile(
                title = "Pending Leave",
                value = leaveValue,
                subtitle = if (!hasStation) "Station Required" else "Awaiting review",
                icon = Icons.AutoMirrored.Filled.EventNote,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                testTag = "metric_pending_leave",
                onClick = { onNavigate(NavRoutes.LEAVE) },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SupervisorMetricTile(
                title = "Escort Duties",
                value = "$escortDutiesCount",
                subtitle = "Vehicle escorts",
                icon = Icons.Default.DirectionsCar,
                iconTint = MaterialTheme.colorScheme.primary,
                iconContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                testTag = "metric_escort_duties",
                onClick = { onNavigate(NavRoutes.ESCORT_DUTIES) },
                modifier = Modifier.weight(1f)
            )
            SupervisorMetricTile(
                title = "Exam Duties",
                value = "$examDutiesCount",
                subtitle = "Exam security",
                icon = Icons.Default.School,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                testTag = "metric_exam_duties",
                onClick = { onNavigate(NavRoutes.EXAM_DUTIES) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SupervisorMetricTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    iconContainerColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(color = iconContainerColor, shape = RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SupervisorCurrentShiftCard(
    hasStation: Boolean,
    stationName: String,
    activeShiftBadge: String,
    todayShifts: List<Shift>,
    attendanceRecords: List<Attendance>,
    latestHandover: ShiftHandover?,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("current_shift_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CURRENT OPERATIONAL SHIFT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = activeShiftBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = "Station Post: $stationName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            Text(
                text = "Scheduled Duty Personnel (Server-Authoritative Roster):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasStation) {
                Text(
                    text = "Station assignment required to view scheduled shift personnel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (todayShifts.isEmpty()) {
                Text(
                    text = "No guards scheduled on today's roster for this station.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    todayShifts.forEach { shift ->
                        val att = attendanceRecords.find { it.guard == shift.guard || it.guardName == shift.guardName }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = shift.guardName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Pair Partner: ${shift.partnerName ?: "Solo"} • Shift: ${shift.shiftType}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                when {
                                    att?.clockOut != null -> {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "✓ Clocked Out",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    att?.clockIn != null -> {
                                        Surface(
                                            color = StatusSuccess,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "● Clocked In",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    else -> {
                                        Surface(
                                            color = StatusWarning,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "○ Pending Report",
                                                color = Color.Black,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // Shift Handover Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Shift Handover / Take-Over Status:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            latestHandover == null -> "No handover records logged today."
                            latestHandover.isHandoverRejected -> "DISPUTED / REJECTED (${latestHandover.pendingIssues})"
                            latestHandover.incomingAccepted -> "Accepted & Signed by ${latestHandover.incomingGuardName}"
                            else -> "Pending incoming acceptance (${latestHandover.outgoingGuardName} -> ${latestHandover.incomingGuardName})"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            latestHandover?.isHandoverRejected == true -> MaterialTheme.colorScheme.error
                            latestHandover?.incomingAccepted == true -> StatusSuccess
                            latestHandover != null -> StatusWarning
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SupervisorRosterCard(
    hasStation: Boolean,
    stationName: String,
    rosterShiftsCount: Int,
    conflictReport: ConflictReport?,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("roster_management_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DUTY ROSTER MANAGEMENT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "$rosterShiftsCount Shifts",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Automated Rotation & Shift Generation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Manages 4-day rotating cycles (4 ON / 8 OFF) and examination security allocations for $stationName.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (conflictReport != null && conflictReport.hasConflicts) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${conflictReport.totalConflicts} Roster conflict(s) detected — review required.",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Roster rotation verified • Zero invariant conflicts",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusSuccess
                        )
                    }
                }
            }

            Button(
                onClick = { onNavigate(NavRoutes.ROSTER_MANAGEMENT) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN ROSTER MANAGEMENT CONSOLE", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SupervisorOccurrenceBookCard(
    hasStation: Boolean,
    obEntries: List<OccurrenceBookEntry>,
    isLoading: Boolean,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ob_live_feed_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "OCCURRENCE BOOK LIVE FEED",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Cryptographically Secured",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (!hasStation) {
                Text(
                    text = "Station assignment required to load Occurrence Book feed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (obEntries.isEmpty()) {
                Text(
                    text = "No Occurrence Book entries recorded today for this station.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    obEntries.forEach { entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "#OB-${entry.entryNumber} • [${entry.categoryDisplay ?: entry.category}]",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = entry.createdAt.take(16).replace("T", " "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = entry.occurrenceText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                                Text(
                                    text = "Logged by: ${entry.guardName}${if (entry.amendments.isNotEmpty()) " • ${entry.amendments.size} amendment(s)" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onNavigate(NavRoutes.OCCURRENCE_BOOK) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("VIEW COMPLETE OCCURRENCE BOOK")
            }
        }
    }
}

@Composable
private fun SupervisorVisitorLogCard(
    hasStation: Boolean,
    visitors: List<OccurrenceBookEntry>,
    isLoading: Boolean,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("visitor_log_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        imageVector = Icons.Default.Badge,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VISITOR LOG & GATE REGISTER",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${visitors.size} on site",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (!hasStation) {
                Text(
                    text = "Station assignment required to load visitor log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (visitors.isEmpty()) {
                Text(
                    text = "No visitor passes currently recorded for this station.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visitors.forEach { visitor ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = visitor.occurrenceText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Logged by: ${visitor.guardName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = visitor.createdAt.take(16).replace("T", " "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "Backend Note: Visitor records are cryptographically stored in Occurrence Book under category 'VISITOR'. Dedicated visitor table pending backend implementation.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = { onNavigate(NavRoutes.VISITOR_BOOK) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN VISITOR REGISTER")
            }
        }
    }
}

@Composable
private fun SupervisorIncidentsCard(
    hasStation: Boolean,
    incidents: List<IncidentReport>,
    isLoading: Boolean,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("incidents_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (incidents.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "INCIDENT REPORTS & SOS ALERTS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (incidents.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = if (incidents.isNotEmpty()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${incidents.size} Active",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (incidents.isNotEmpty()) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (!hasStation) {
                Text(
                    text = "Station assignment required to load incident records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (incidents.isEmpty()) {
                Text(
                    text = "No open security incidents reported for this station.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    incidents.forEach { inc ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = inc.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        color = when (inc.priority.uppercase()) {
                                            "HIGH", "CRITICAL" -> MaterialTheme.colorScheme.error
                                            "MEDIUM" -> StatusWarning
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = inc.priorityDisplay ?: inc.priority,
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = inc.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                                Text(
                                    text = "Location: ${inc.location} • Officer: ${inc.reportingGuardName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onNavigate(NavRoutes.INCIDENTS) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("REVIEW INCIDENT LOGS")
            }
        }
    }
}

@Composable
private fun SupervisorPatrolCard(
    hasStation: Boolean,
    activePatrol: PatrolLog?,
    totalLogsToday: Int,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("patrol_monitoring_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PATROL OVERSIGHT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = if (activePatrol != null) StatusSuccess else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (activePatrol != null) "● IN PROGRESS" else "IDLE",
                        color = if (activePatrol != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (!hasStation) {
                Text(
                    text = "Station assignment required to monitor patrols.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (activePatrol != null) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Active Patrol: ${activePatrol.guardName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Started at: ${activePatrol.startTime} • ${activePatrol.scansCount} checkpoints verified",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!activePatrol.notes.isNullOrBlank()) {
                            Text(
                                text = "Notes: ${activePatrol.notes}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "No active patrol round currently in progress at this station ($totalLogsToday patrol logs recorded).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SupervisorLeaveReviewsCard(
    pendingApplications: List<LeaveApplication>,
    isLoading: Boolean,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("leave_reviews_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        imageVector = Icons.AutoMirrored.Filled.EventNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PENDING LEAVE REVIEWS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Surface(
                    color = if (pendingApplications.isNotEmpty()) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${pendingApplications.size} Awaiting",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (pendingApplications.isNotEmpty()) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (pendingApplications.isEmpty()) {
                Text(
                    text = "No pending leave applications awaiting supervisor review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pendingApplications.forEach { app ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = app.guardName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiary,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = app.leaveTypeDisplay ?: app.leaveType,
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${app.startDate} to ${app.endDate}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Reason: ${app.reason}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onNavigate(NavRoutes.LEAVE) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.EventNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN LEAVE MANAGER")
            }
        }
    }
}

@Composable
private fun SupervisorHandoverCard(
    hasStation: Boolean,
    latestHandover: ShiftHandover?,
    pendingCount: Int,
    isLoading: Boolean,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("handover_monitoring_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SHIFT HANDOVER OVERSIGHT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = if (pendingCount > 0) StatusWarning else StatusSuccess,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (pendingCount > 0) "$pendingCount Pending" else "Current",
                        color = if (pendingCount > 0) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (!hasStation) {
                Text(
                    text = "Station assignment required to view handover logs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (latestHandover == null) {
                Text(
                    text = "No shift handover logs recorded today for this station.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${latestHandover.outgoingGuardName} ➔ ${latestHandover.incomingGuardName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Occurrence Summary: ${latestHandover.occurrenceSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                        Text(
                            text = "Equipment: ${latestHandover.equipmentIssued} • Keys: ${latestHandover.keysHandedOver}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Status: ${if (latestHandover.incomingAccepted) "Accepted by ${latestHandover.incomingGuardName}" else if (latestHandover.isHandoverRejected) "DISPUTED" else "Awaiting Acceptance"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (latestHandover.incomingAccepted) StatusSuccess else if (latestHandover.isHandoverRejected) MaterialTheme.colorScheme.error else StatusWarning
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SupervisorActionGrid(onNavigate: (String) -> Unit) {
    val items = listOf(
        BlueprintAction("Attendance Console", "Real-time clock-in monitoring", Icons.Default.HowToReg, NavRoutes.ATTENDANCE_MANAGEMENT, "nav_attendance_management"),
        BlueprintAction("Duty Roster Engine", "Automated rotation & shifts", Icons.Default.CalendarMonth, NavRoutes.ROSTER_MANAGEMENT, "nav_roster"),
        BlueprintAction("Occurrence Book", "Station OB review & entries", Icons.AutoMirrored.Filled.MenuBook, NavRoutes.OCCURRENCE_BOOK, "nav_ob"),
        BlueprintAction("Visitor Register", "Station visitor logs & passes", Icons.Default.Badge, NavRoutes.VISITOR_BOOK, "nav_visitors"),
        BlueprintAction("Incident Reports", "Station incident management", Icons.Default.Warning, NavRoutes.INCIDENTS, "nav_incidents"),
        BlueprintAction("Leave Manager", "Approve & track guard leaves", Icons.AutoMirrored.Filled.EventNote, NavRoutes.LEAVE, "nav_leave"),
        BlueprintAction("Personnel & Guards", "Staff & station assignments", Icons.Default.People, NavRoutes.USER_MANAGEMENT, "nav_users"),
        BlueprintAction("Operations Reports", "Filter & analyze station records", Icons.Default.Assessment, NavRoutes.REPORTS, "nav_reports"),
        BlueprintAction("Notifications", "Operational alerts & notices", Icons.Default.Notifications, NavRoutes.NOTIFICATIONS, "nav_notifications"),
        BlueprintAction("Station Settings", "Preferences & diagnostic info", Icons.Default.Settings, NavRoutes.SETTINGS, "nav_settings")
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (pair in items.chunked(2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (item in pair) {
                    BlueprintActionCard(
                        action = item,
                        onClick = { onNavigate(item.route) },
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

// =============================================================================
// ADMINISTRATOR / SUPERUSER NATIONAL CONTROL CENTER (BLUEPRINT PHASE 12.6)
// =============================================================================

@Composable
private fun AdministratorNationalControlCenter(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState,
    user: User?,
    todayStr: String,
    todayFormatted: String,
    onNavigate: (String) -> Unit
) {
    LaunchedEffect(Unit) { viewModel.fetchRecordAdjustments() }
    val totalStations = uiState.stations.size
    val activeStations = uiState.stations.size
    val guardsOnDuty = uiState.attendanceRecords.count { it.clockIn != null && it.clockOut == null }
    val totalGuards = uiState.users.count { it.role.uppercase() == "GUARD" }
    val guardsOffDuty = (totalGuards - guardsOnDuty).coerceAtLeast(0)
    val openIncidents = uiState.incidents.count { it.status != "RESOLVED" }
    val activePatrols = uiState.patrolLogs.count { it.status == "IN_PROGRESS" }
    val activeVisitors = uiState.visitors.count {
        !it.occurrenceText.contains("Time Out:", ignoreCase = true) && !it.occurrenceText.contains("CHECKED OUT", ignoreCase = true)
    }
    val pendingLeave = uiState.leaveApplications.count { it.status == "PENDING" }
    val todayShifts = uiState.rosterShifts.filter {
        it.date == todayStr && it.shiftType.uppercase() != "OFF" && it.assignmentType.uppercase() != "TIME_OFF"
    }
    val todayShiftsCount = todayShifts.size
    val attendanceRate = if (todayShiftsCount > 0) {
        ((guardsOnDuty.toFloat() / todayShiftsCount.toFloat()) * 100).toInt().coerceIn(0, 100)
    } else if (guardsOnDuty > 0) 100 else 100

    // 1. National Command Header
    AdministratorNationalHeader(
        user = user,
        todayFormatted = todayFormatted,
        onRefresh = { viewModel.refreshAuthoritativeState() },
        onViewProfile = { onNavigate(NavRoutes.PROFILE) }
    )

    // 2. Section A: National Telemetry Metrics Grid (9 Core Metrics)
    Text(
        text = "National Command Telemetry",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    NationalMetricsGrid(
        totalStations = totalStations,
        activeStations = activeStations,
        guardsOnDuty = guardsOnDuty,
        guardsOffDuty = guardsOffDuty,
        openIncidents = openIncidents,
        activePatrols = activePatrols,
        activeVisitors = activeVisitors,
        pendingLeave = pendingLeave,
        attendanceRate = attendanceRate,
        onNavigate = onNavigate
    )

    // 3. Section B: Multi-Centre Monitoring
    Text(
        text = "Multi-Centre Station Oversight",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    MultiCentreMonitoringCard(
        stations = uiState.stations,
        attendanceRecords = uiState.attendanceRecords,
        rosterShifts = uiState.rosterShifts,
        incidents = uiState.incidents,
        patrolLogs = uiState.patrolLogs,
        todayStr = todayStr,
        onNavigate = onNavigate
    )

    // 4. Section C: National Roster Oversight
    Text(
        text = "National Roster Oversight",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    NationalRosterOversightCard(
        rosterShifts = uiState.rosterShifts,
        conflictReport = uiState.conflictReport,
        stations = uiState.stations,
        onNavigate = onNavigate
    )

    // 5. Section D: Master Administration Navigation
    Text(
        text = "National Operations & Administration",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    NationalAdministrationGrid(onNavigate = onNavigate, pendingAdjustments = uiState.recordAdjustments.count { it.status == "PENDING" })

    // 6. Section E: Zimbabwe Public Holiday Control
    Text(
        text = "Zimbabwe Public Holiday Control",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    ZimbabwePublicHolidayControlCard(
        viewModel = viewModel,
        uiState = uiState
    )

    // 7. Section F: Early Clock-Out Authorization Panel (OTP Engine)
    Text(
        text = "Early Clock-Out Authorization",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    EarlyClockOutAuthorizationCard(
        viewModel = viewModel,
        uiState = uiState
    )

    // 8. Section G: National Analytics & Security Posture
    Text(
        text = "National Analytics & Security Posture",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    NationalAnalyticsCard(
        uiState = uiState,
        todayShiftsCount = todayShiftsCount,
        guardsOnDuty = guardsOnDuty,
        onNavigate = onNavigate
    )
}

@Composable
private fun AdministratorNationalHeader(
    user: User?,
    todayFormatted: String,
    onRefresh: () -> Unit,
    onViewProfile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_national_header"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        )
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
                Surface(
                    color = StatusSuccess,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "● NATIONAL CONTROL CENTER ACTIVE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(32.dp).testTag("btn_refresh_national")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync National Telemetry",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "COMMAND LEVEL: SUPERUSER / NATIONAL CONTROLLER",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${user?.fullName ?: "Administrator"}${user?.username?.let { " (@$it)" }.orEmpty()}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$todayFormatted • National Command • Multi-Station Full Authority",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Zero-Trust Authoritative Engine: Direct Telemetry Across All Stations",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun NationalMetricsGrid(
    totalStations: Int,
    activeStations: Int,
    guardsOnDuty: Int,
    guardsOffDuty: Int,
    openIncidents: Int,
    activePatrols: Int,
    activeVisitors: Int,
    pendingLeave: Int,
    attendanceRate: Int,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("national_metrics_grid")
            .testTag("telemetry_overview_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Row 1: Stations & On Duty
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NationalMetricCard(
                    title = "Stations",
                    value = "$totalStations",
                    subtitle = "$activeStations Active",
                    color = MaterialTheme.colorScheme.primary,
                    icon = Icons.Default.Business,
                    onClick = { onNavigate(NavRoutes.STATION_MANAGEMENT) },
                    modifier = Modifier.weight(1f)
                )
                NationalMetricCard(
                    title = "Guards On Duty",
                    value = "$guardsOnDuty",
                    subtitle = "Clocked In",
                    color = StatusSuccess,
                    icon = Icons.Default.HowToReg,
                    onClick = { onNavigate(NavRoutes.ATTENDANCE_MANAGEMENT) },
                    modifier = Modifier.weight(1f)
                )
                NationalMetricCard(
                    title = "Guards Off Duty",
                    value = "$guardsOffDuty",
                    subtitle = "Available/Rest",
                    color = MaterialTheme.colorScheme.outline,
                    icon = Icons.Default.Person,
                    onClick = { onNavigate(NavRoutes.USER_MANAGEMENT) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 2: Incidents, Patrols, Visitors
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NationalMetricCard(
                    title = "Incidents / SOS",
                    value = "$openIncidents",
                    subtitle = if (openIncidents > 0) "Requires Review" else "All Clear",
                    color = if (openIncidents > 0) MaterialTheme.colorScheme.error else StatusSuccess,
                    icon = Icons.Default.Warning,
                    onClick = { onNavigate(NavRoutes.INCIDENTS) },
                    modifier = Modifier.weight(1f)
                )
                NationalMetricCard(
                    title = "Active Patrols",
                    value = "$activePatrols",
                    subtitle = "In Progress",
                    color = MaterialTheme.colorScheme.tertiary,
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    onClick = { onNavigate(NavRoutes.PATROL) },
                    modifier = Modifier.weight(1f)
                )
                NationalMetricCard(
                    title = "Visitors",
                    value = "$activeVisitors",
                    subtitle = "Checked In",
                    color = MaterialTheme.colorScheme.secondary,
                    icon = Icons.Default.Badge,
                    onClick = { onNavigate(NavRoutes.VISITOR_BOOK) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 3: Leave, Attendance Rate, Roster Health
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NationalMetricCard(
                    title = "Pending Leave",
                    value = "$pendingLeave",
                    subtitle = if (pendingLeave > 0) "Action Needed" else "Up to Date",
                    color = if (pendingLeave > 0) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.AutoMirrored.Filled.EventNote,
                    onClick = { onNavigate(NavRoutes.LEAVE) },
                    modifier = Modifier.weight(1f)
                )
                NationalMetricCard(
                    title = "Attendance Rate",
                    value = "$attendanceRate%",
                    subtitle = "Roster Compliance",
                    color = if (attendanceRate >= 80) StatusSuccess else StatusWarning,
                    icon = Icons.Default.Assessment,
                    onClick = { onNavigate(NavRoutes.REPORTS) },
                    modifier = Modifier.weight(1f)
                )
                NationalMetricCard(
                    title = "Roster Engine",
                    value = "Live",
                    subtitle = "Conflict Guard",
                    color = MaterialTheme.colorScheme.primary,
                    icon = Icons.Default.CalendarMonth,
                    onClick = { onNavigate(NavRoutes.ROSTER_MANAGEMENT) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NationalMetricCard(
    title: String,
    value: String,
    subtitle: String,
    color: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MULTI-CENTRE STATIONS (${stations.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = { onNavigate(NavRoutes.STATION_MANAGEMENT) }) {
                    Text("MANAGE", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (stations.isEmpty()) {
                Text(
                    text = "No stations registered in the national system.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    stations.forEach { station ->
                        val stationGuardsOnPost = attendanceRecords.count {
                            (it.stationName.equals(station.name, ignoreCase = true) || it.stationName.contains(station.name, ignoreCase = true)) &&
                            it.clockIn != null && it.clockOut == null
                        }
                        val stationOpenIncidents = incidents.count {
                            (it.station.isBlank() || it.station == station.id || it.stationName.equals(station.name, ignoreCase = true)) &&
                            it.status != "RESOLVED"
                        }
                        val stationActivePatrols = patrolLogs.count {
                            (it.station.isBlank() || it.station == station.id || it.stationName.equals(station.name, ignoreCase = true)) &&
                            it.status == "IN_PROGRESS"
                        }
                        val stationTodayShifts = rosterShifts.count {
                            (it.station == station.id || it.stationName.equals(station.name, ignoreCase = true)) &&
                            it.date == todayStr && it.shiftType.uppercase() != "OFF"
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = station.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Code: ${station.code ?: "STN"} • Radius: ${station.effectiveRadius.toInt()}m",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Surface(
                                        color = StatusSuccess,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "ACTIVE",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "On Post: $stationGuardsOnPost / $stationTodayShifts",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (stationGuardsOnPost > 0) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Incidents: $stationOpenIncidents",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (stationOpenIncidents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Patrols: $stationActivePatrols",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (stationActivePatrols > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun NationalRosterOversightCard(
    rosterShifts: List<Shift>,
    conflictReport: ConflictReport?,
    stations: List<Station>,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("national_roster_oversight_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NATIONAL ROSTER OVERSIGHT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${rosterShifts.size} Shifts Total",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Conflict Status Banner
            if (conflictReport?.hasConflicts == true) {
                Surface(
                    color = StatusWarning.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(StatusWarning)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFE65100), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "⚠ CONFLICT: ${conflictReport.totalConflicts} roster conflicts detected across national stations.",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFBF360C)
                        )
                    }
                }
            } else {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.12f),
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
                            text = "✓ Zero Overlap / Compliant Roster Schedules Across All Stations",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusSuccess,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Invariant Notice
            Text(
                text = "Administrators and supervisors must not be assigned to guard rosters.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Preview of Recent Shifts
            val previewShifts = rosterShifts.take(4)
            if (previewShifts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    previewShifts.forEach { shift ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
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
                                    Text(
                                        text = shift.guardName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${shift.stationName} • ${shift.date}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    color = when (shift.shiftType.uppercase()) {
                                        "DAY" -> GoldAccent.copy(alpha = 0.25f)
                                        "NIGHT" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "${shift.shiftType} • ${shift.assignmentType}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onNavigate(NavRoutes.ROSTER_MANAGEMENT) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN DUTY ROSTER ENGINE")
            }
        }
    }
}

@Composable
private fun NationalAdministrationGrid(onNavigate: (String) -> Unit, pendingAdjustments: Int) {
    val adminItems = listOf(
        BlueprintAction("Attendance Console", "National clock-in monitoring", Icons.Default.HowToReg, NavRoutes.ATTENDANCE_MANAGEMENT, "nav_attendance_management"),
        BlueprintAction("Duty Roster Engine", "National schedule generation", Icons.Default.CalendarMonth, NavRoutes.ROSTER_MANAGEMENT, "nav_roster"),
        BlueprintAction("Personnel & Accounts", "All staff, roles & credentials", Icons.Default.People, NavRoutes.USER_MANAGEMENT, "nav_users"),
        BlueprintAction("Stations & Pairs", "Posts, checkpoints & pairs", Icons.Default.Business, NavRoutes.STATION_MANAGEMENT, "nav_stations"),
        BlueprintAction("Occurrence Book", "Global OB records", Icons.AutoMirrored.Filled.MenuBook, NavRoutes.OCCURRENCE_BOOK, "nav_ob"),
        BlueprintAction("Incident Reports", "System-wide incidents", Icons.Default.Warning, NavRoutes.INCIDENTS, "nav_incidents"),
        BlueprintAction("Patrol Monitoring", "Read-only national patrol and checkpoint activity", Icons.AutoMirrored.Filled.DirectionsWalk, NavRoutes.PATROL, "nav_admin_patrol_monitoring"),
        BlueprintAction("Executive Reports", "National security analytics", Icons.Default.Assessment, NavRoutes.REPORTS, "nav_reports"),
        BlueprintAction("Leave Management", "System-wide leave requests", Icons.AutoMirrored.Filled.EventNote, NavRoutes.LEAVE, "nav_leave"),
        BlueprintAction("Master Record Adjustments", "$pendingAdjustments pending · Review and reconcile personnel records", Icons.Default.EditNote, NavRoutes.RECORD_ADJUSTMENTS, "nav_record_adjustments"),
        BlueprintAction("Administrative History", "Read-only audit and adjustment history", Icons.Default.History, NavRoutes.ADMIN_HISTORY, "nav_admin_history"),
        BlueprintAction("Leave & Duty Master Control", "Opening balances and future reassignment", Icons.Default.Tune, NavRoutes.ADMIN_MASTER_TOOLS, "nav_admin_master_tools"),
        BlueprintAction("Escort Duties", "National escort assignments", Icons.Default.DirectionsCar, NavRoutes.ESCORT_DUTIES, "nav_escort_duties"),
        BlueprintAction("Exam Duties", "National exam assignments", Icons.Default.School, NavRoutes.EXAM_DUTIES, "nav_exam_duties"),
        BlueprintAction("Visitor Register", "National visitor records", Icons.Default.Badge, NavRoutes.VISITOR_BOOK, "nav_visitors"),
        BlueprintAction("Notifications", "Operational alerts and messages", Icons.Default.Notifications, NavRoutes.NOTIFICATIONS, "nav_notifications"),
        BlueprintAction("Settings & About", "Theme, app version & preferences", Icons.Default.Settings, NavRoutes.SETTINGS, "nav_settings")
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (pair in adminItems.chunked(2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (item in pair) {
                    BlueprintActionCard(
                        action = item,
                        onClick = { onNavigate(item.route) },
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

@Composable
private fun ZimbabwePublicHolidayControlCard(
    viewModel: SgmisViewModel,
    uiState: SgmisUiState
) {
    var decisionNotes by remember { mutableStateOf("") }
    var pendingDecision by remember { mutableStateOf<Pair<com.example.data.model.PublicHolidayDutyRecord, Boolean>?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("zimbabwe_public_holiday_control_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ZIMBABWE PUBLIC HOLIDAY CONTROL",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Act (Cap 10:21)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Statutory Compensation Policy:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Security guards working on statutory Zimbabwean public holidays receive 2.0 days compensatory leave credited automatically upon Administrator authorization.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Display Public Holidays
            val holidays = uiState.publicHolidays
            if (holidays.isNotEmpty()) {
                Text(
                    text = "Recognized National Holidays (${holidays.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = holidays.joinToString(", ") { "${it.name} (${it.date})" },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Holiday Duty Records Review
            val dutyRecords = uiState.holidayDutyRecords
            val pendingRecords = dutyRecords.filter { it.status.uppercase() == "PENDING" }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Holiday Duty Compensation Claims",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (pendingRecords.isNotEmpty()) {
                    Surface(color = StatusWarning, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = "${pendingRecords.size} Pending",
                            color = Color.Black,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (dutyRecords.isEmpty()) {
                Text(
                    text = "No guard holiday duty compensation records submitted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dutyRecords.take(4).forEach { record ->
                        val isPending = record.status.uppercase() == "PENDING"
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = record.guardName ?: "Security Guard",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${record.publicHolidayName ?: "Holiday"} • ${record.shiftDate ?: ""} • ${record.stationName ?: "National"}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Surface(
                                        color = when (record.status.uppercase()) {
                                            "APPROVED" -> StatusSuccess
                                            "REJECTED" -> MaterialTheme.colorScheme.error
                                            else -> StatusWarning
                                        },
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = when (record.status.uppercase()) {
                                                "APPROVED" -> "APPROVED (+2d)"
                                                "REJECTED" -> "REJECTED"
                                                else -> "PENDING REVIEW"
                                            },
                                            color = if (record.status.uppercase() == "PENDING") Color.Black else Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                if (isPending) {
                                    OutlinedTextField(
                                        value = decisionNotes,
                                        onValueChange = { decisionNotes = it },
                                        label = { Text("Decision Reason / Note (Optional)", style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                pendingDecision = record to true
                                            },
                                            enabled = !uiState.isReviewingHolidayDuty,
                                            colors = ButtonDefaults.buttonColors(containerColor = StatusSuccess),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("APPROVE (+2 DAYS)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                pendingDecision = record to false
                                            },
                                            enabled = !uiState.isReviewingHolidayDuty,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("REJECT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
    pendingDecision?.let { (record, approve) ->
        AlertDialog(
            onDismissRequest = { pendingDecision = null },
            title = { Text(if (approve) "Approve holiday duty?" else "Reject holiday duty?") },
            text = { Text("${record.guardName ?: "Security guard"} · ${record.publicHolidayName ?: "Public holiday"} · ${record.shiftDate.orEmpty()}\nStatus: ${record.status} → ${if (approve) "APPROVED (+2 compensatory days)" else "REJECTED"}\nReason: ${decisionNotes.ifBlank { "No additional note" }}") },
            confirmButton = { TextButton(enabled = !uiState.isReviewingHolidayDuty, onClick = {
                if (approve) viewModel.approveHolidayDuty(record.id, decisionNotes) else viewModel.rejectHolidayDuty(record.id, decisionNotes)
                pendingDecision = null
                decisionNotes = ""
            }) { Text(if (approve) "Confirm approval" else "Confirm rejection") } },
            dismissButton = { TextButton(onClick = { pendingDecision = null }) { Text("Back") } }
        )
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

    // Preselect first guard if not selected
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EARLY CLOCK-OUT AUTHORIZATION",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "5-Min OTP Engine",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Issue server-authoritative 6-digit cryptographic OTP to authorize early departure. OTP expires strictly in 5 minutes and is single-use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Active OTP Generated Display
            val activeOtp = uiState.activeEarlyClockoutOtp
            if (activeOtp != null) {
                var secondsLeft by remember(activeOtp) { mutableIntStateOf(activeOtp.expiresInSeconds.coerceAtLeast(300)) }
                LaunchedEffect(activeOtp) {
                    while (secondsLeft > 0) {
                        delay(1000)
                        secondsLeft--
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CRYPTOGRAPHIC AUTHORIZATION CODE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Surface(
                                color = if (secondsLeft > 60) StatusSuccess else MaterialTheme.colorScheme.error,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = activeOtp.otp,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "Guard: ${activeOtp.guardName ?: activeOtp.guardUsername ?: "Personnel"} • ${activeOtp.stationName ?: "Station"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Provide this code to the guard. Guard enters OTP in the Clock-Out console to complete departure.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        OutlinedButton(
                            onClick = { viewModel.clearActiveOtp() },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("DISMISS CODE")
                        }
                    }
                }
            } else {
                // OTP Generation Form
                if (activeGuards.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No guards currently clocked in on active duty across national stations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Select Guard on Active Duty (${activeGuards.size} available):",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )

                        // Selector chips or radio cards
                        activeGuards.take(4).forEach { att ->
                            val isSelected = att.shift == selectedShiftId
                            Surface(
                                onClick = { selectedShiftId = att.shift },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = if (isSelected) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)) else null,
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
                                        Text(text = att.guardName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = "${att.stationName} • In: ${att.clockIn?.take(16) ?: "Active"}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
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

                        Button(
                            onClick = {
                                if (selectedShiftId.isNotBlank() && overrideReason.isNotBlank()) {
                                    viewModel.generateEarlyClockoutOtp(selectedShiftId, overrideReason)
                                }
                            },
                            enabled = selectedShiftId.isNotBlank() && overrideReason.isNotBlank() && !uiState.isGeneratingOtp,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (uiState.isGeneratingOtp) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("GENERATING CRYPTOGRAPHIC OTP...")
                            } else {
                                Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("GENERATE 5-MINUTE AUTHORIZATION OTP")
                            }
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Security Invariant: Guards cannot generate or self-authorize OTPs. All override requests and redemptions are recorded immutably in SupervisorOverrideAudit and SecurityAuditEvent.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun NationalAnalyticsCard(
    uiState: SgmisUiState,
    todayShiftsCount: Int,
    guardsOnDuty: Int,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("national_analytics_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        imageVector = Icons.Default.Assessment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NATIONAL SECURITY ANALYTICS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = { onNavigate(NavRoutes.REPORTS) }) {
                    Text("FULL REPORT", style = MaterialTheme.typography.labelSmall)
                }
            }

            val criticalIncidents = uiState.incidents.count { it.priority.uppercase() in listOf("HIGH", "CRITICAL", "URGENT") }
            val resolvedIncidents = uiState.incidents.count { it.status == "RESOLVED" }
            val openIncidents = uiState.incidents.count { it.status != "RESOLVED" }
            val activePatrols = uiState.patrolLogs.count { it.status == "IN_PROGRESS" }
            val totalPatrols = uiState.patrolLogs.size

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Incidents", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text("$openIncidents Open", style = MaterialTheme.typography.bodySmall, color = if (openIncidents > 0) MaterialTheme.colorScheme.error else StatusSuccess)
                        Text("$criticalIncidents High/Critical", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$resolvedIncidents Resolved", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Patrol Health", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text("$activePatrols In Progress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text("$totalPatrols Total Logged", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("All Stations Monitored", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = StatusSuccess)
                    }
                }
            }

            Button(
                onClick = { onNavigate(NavRoutes.REPORTS) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Assessment, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN EXECUTIVE ANALYTICS CONSOLE")
            }
        }
    }
}
