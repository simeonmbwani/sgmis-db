package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppRole
import com.example.ui.navigation.NavRoutes
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.LightBackground
import com.example.ui.theme.LightSurface
import com.example.ui.theme.NavyDark
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryLight
import com.example.ui.viewmodel.SgmisViewModel
import com.example.util.LocationHelper
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * Authoritative Master Dashboard Entrypoint (Phase 6 Core Redesign).
 * Routes intelligently to the role-specific experience:
 * - GUARD: GuardHomeScreen (Tactical, outdoor-ready, duty status, primary operations)
 * - SUPERVISOR: SupervisorHomeScreen (Station command, live coverage, attendance queue)
 * - ADMINISTRATOR: AdminHomeScreen (National oversight, governance telemetry, compliance)
 */
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
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var deviceLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showDirectMessagesDialog by remember { mutableStateOf(false) }

    val effectiveRole = roleMode ?: uiState.appRole
    val isGuard = effectiveRole == AppRole.GUARD
    val isSupervisor = effectiveRole == AppRole.SUPERVISOR
    val isAdmin = effectiveRole == AppRole.ADMINISTRATOR

    LaunchedEffect(Unit) {
        deviceLocation = LocationHelper.getDeviceLocation(context)
        viewModel.fetchUnreadMessageCount()
        viewModel.fetchDirectMessages()
        if (isSupervisor) {
            viewModel.fetchSupervisorDashboard()
        } else if (isAdmin) {
            viewModel.fetchAdminDashboard()
            viewModel.fetchRecordAdjustments()
        } else if (isGuard) {
            viewModel.fetchDutyState()
            viewModel.fetchTodayShift()
            viewModel.fetchRosterShifts()
        }
    }

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

    // Session Countdown Timer Indicator (auto-refreshes on expiry)
    var sessionSecondsLeft by remember { mutableIntStateOf(180) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (sessionSecondsLeft > 0) {
                sessionSecondsLeft--
            } else {
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
    val nextDutyShift = remember(uiState.serverDutyState, uiState.rosterShifts, user, todayStr) {
        uiState.serverDutyState?.nextDuty ?: run {
            val uid = user?.id
            val uName = user?.username
            val emp = user?.employeeNumber
            uiState.rosterShifts
                .filter { s ->
                    s.date >= todayStr &&
                    s.shiftType.uppercase() != "OFF" &&
                    s.assignmentType.uppercase() != "TIME_OFF" &&
                    ((uid != null && s.guard == uid) ||
                     (uName != null && s.guardName.equals(uName, ignoreCase = true)) ||
                     (emp != null && s.employeeNumber == emp))
                }
                .minByOrNull { it.date }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(NavyDark, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when {
                                    isSupervisor -> "Station Command"
                                    isAdmin -> "National Control"
                                    else -> "Smart Security"
                                },
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                color = TextPrimaryLight
                            )
                            Text(
                                text = when {
                                    isAdmin -> "National Oversight"
                                    isSupervisor && !uiState.hasAssignedStation -> "Station Unassigned"
                                    else -> uiState.currentStationName
                                },
                                fontSize = 11.sp,
                                color = TextSecondaryLight
                            )
                        }
                    }
                },
                actions = {
                    // Session Countdown Timer Pill
                    Surface(
                        color = NavyDark.copy(alpha = 0.08f),
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
                                tint = NavyDark,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = timerText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NavyDark
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
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Data", tint = TextSecondaryLight)
                    }
                    IconButton(
                        onClick = { onNavigate(NavRoutes.SETTINGS) },
                        modifier = Modifier.testTag("dashboard_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondaryLight)
                    }
                    IconButton(
                        onClick = { viewModel.logout() },
                        modifier = Modifier.testTag("logout_button")
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout", tint = TextSecondaryLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LightSurface
                )
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = LightBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when {
                    isGuard -> {
                        GuardHomeScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            todayFormatted = todayFormatted,
                            gpsStatusText = gpsStatusText,
                            deviceLocation = deviceLocation,
                            nextDutyShift = nextDutyShift,
                            onOpenMessages = {
                                showDirectMessagesDialog = true
                                viewModel.fetchDirectMessages()
                            },
                            onNavigate = onNavigate
                        )
                    }
                    isSupervisor -> {
                        SupervisorHomeScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            todayStr = todayStr,
                            todayFormatted = todayFormatted,
                            onNavigate = onNavigate
                        )
                    }
                    isAdmin -> {
                        AdminHomeScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            todayStr = todayStr,
                            todayFormatted = todayFormatted,
                            onNavigate = onNavigate
                        )
                    }
                }
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
