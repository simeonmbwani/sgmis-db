package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.Shift
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import com.example.util.LocationHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayShiftScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val shift = uiState.todayShift
    val userRole = uiState.currentUser?.role?.uppercase() ?: "GUARD"
    val isGuard = userRole == "GUARD"
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showEarlyClockOutDialog by remember { mutableStateOf(false) }
    var earlyOtpCode by remember { mutableStateOf("") }
    var earlySupervisorUsername by remember { mutableStateOf("") }
    var earlySupervisorPassword by remember { mutableStateOf("") }
    var earlyOverrideReason by remember { mutableStateOf("") }

    var showLateArrivalDialog by remember { mutableStateOf(false) }
    var lateArrivalReason by remember { mutableStateOf("") }
    var lateArrivalIncidentDetails by remember { mutableStateOf("") }
    var lateArrivalEstimatedArrival by remember { mutableStateOf("") }
    var lateArrivalError by remember { mutableStateOf<String?>(null) }

    val harareTz = remember { java.util.TimeZone.getTimeZone("Africa/Harare") }

    fun isShiftEarly(s: Shift?): Boolean {
        if (s == null) return false
        return try {
            val startH = s.startTime.take(5)
            val endH = s.endTime.take(5)
            val isOvernight = endH <= startH
            val endCal = Calendar.getInstance(harareTz)
            val dateParts = s.date.split("-")
            endCal.set(Calendar.YEAR, dateParts[0].toInt())
            endCal.set(Calendar.MONTH, dateParts[1].toInt() - 1)
            endCal.set(Calendar.DAY_OF_MONTH, dateParts[2].toInt())
            val endParts = endH.split(":")
            endCal.set(Calendar.HOUR_OF_DAY, endParts[0].toInt())
            endCal.set(Calendar.MINUTE, endParts[1].toInt())
            endCal.set(Calendar.SECOND, 0)
            endCal.set(Calendar.MILLISECOND, 0)
            if (isOvernight) {
                endCal.add(Calendar.DAY_OF_MONTH, 1)
            }
            Calendar.getInstance(harareTz).before(endCal)
        } catch (e: Exception) {
            false
        }
    }

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        val err = uiState.errorMessage ?: ""
        if (err.contains("requires authenticated supervisor authorization", ignoreCase = true)) {
            showEarlyClockOutDialog = true
        }
        if (err.contains("Late Arrival Report required", ignoreCase = true) ||
            err.contains("60 minutes", ignoreCase = true)
        ) {
            showLateArrivalDialog = true
        }
        if (!showEarlyClockOutDialog && !showLateArrivalDialog && (uiState.successMessage != null || uiState.errorMessage != null)) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = stringResource(R.string.today_shift_title),
                subtitle = if (shift != null) "${shift.stationName} • ${shift.date}" else "Operational Console",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("today_shift_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchTodayShift() },
                        modifier = Modifier.testTag("refresh_shift_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Shift"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (uiState.shiftLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SgmisLoadingSkeleton(modifier = Modifier.fillMaxWidth().height(140.dp))
                    SgmisLoadingSkeleton(modifier = Modifier.fillMaxWidth().height(90.dp))
                    SgmisLoadingSkeleton(modifier = Modifier.fillMaxWidth().height(160.dp))
                }
            } else if (uiState.errorMessage != null && shift == null) {
                // Dedicated Error State
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .testTag("shift_error_card")
                ) {
                    SgmisErrorState(
                        title = "Failed to Load Shift",
                        message = uiState.errorMessage ?: "Unknown error occurred while retrieving duty assignment.",
                        actionLabel = "Retry Loading Shift",
                        onAction = {
                            viewModel.clearError()
                            viewModel.fetchTodayShift()
                        }
                    )
                }
            } else if (shift == null) {
                // Dedicated Empty State
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .testTag("no_shift_card")
                ) {
                    SgmisEmptyState(
                        icon = Icons.Outlined.EventBusy,
                        title = stringResource(R.string.no_shift_scheduled),
                        message = if (isGuard) {
                            "You do not have an active shift assignment on today's roster."
                        } else {
                            "No active operational shift is currently scheduled for today on this roster."
                        },
                        actionLabel = "Check Roster Again",
                        onAction = { viewModel.fetchTodayShift() }
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Feedback Messages
                    if (uiState.successMessage != null) {
                        SgmisStatusCard(
                            status = CardStatus.SUCCESS,
                            title = "Operation Successful",
                            description = uiState.successMessage!!,
                            icon = Icons.Default.CheckCircle
                        )
                    }

                    if (uiState.errorMessage != null) {
                        SgmisStatusCard(
                            status = CardStatus.ERROR,
                            title = "Operational Notice",
                            description = uiState.errorMessage!!,
                            icon = Icons.Default.Error
                        )
                    }

                    // 1. Authoritative Shift Assignment Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shift_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = shift.stationName,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isGuard) "Official Duty Assignment" else "Authoritative Station Duty Schedule",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                ShiftTypeBadge(
                                    type = shift.shiftType,
                                    isOverride = shift.isOverride || uiState.guardDutyState.isReassigned
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                            // Details Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "DUTY DATE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = shift.date,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Column {
                                    Text(
                                        text = "SCHEDULED HOURS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${shift.startTime} - ${shift.endTime}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // 2. Mandatory Shift Timing & Schedule Protocol Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("timing_protocol_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Schedule,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Mandatory Schedule & Timing Protocol",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        "REPORTING WINDOW",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "30 mins before start",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Column {
                                    Text(
                                        "GRACE PERIOD",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "15 mins max",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = StatusWarning
                                    )
                                }
                                Column {
                                    Text(
                                        "SHIFT DURATION",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "12 Hours Standard",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Shift handover / takeover is authorized strictly upon completing full 12-hour duty unless emergency supervisor authorization is logged.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // 3. Assigned Primary Duty Officer Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("primary_guard_card"),
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
                                    .size(48.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isGuard) "Assigned Duty Officer (You)" else "Scheduled On-Duty Guard",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (isGuard) (uiState.currentUser?.fullName ?: uiState.currentUser?.username ?: shift.guardName) else shift.guardName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val empNum = if (isGuard) (uiState.currentUser?.employeeNumber ?: shift.employeeNumber) else shift.employeeNumber
                                if (!empNum.isNullOrBlank()) {
                                    Text(
                                        text = "Employee ID: $empNum",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // 4. Late Arrival Warning Banner
                    if (shift.lateReportRequired || shift.isSeriousLate || shift.isLate) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("late_arrival_warning_banner"),
                            colors = CardDefaults.cardColors(
                                containerColor = StatusWarning.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = StatusWarning,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (shift.lateReportRequired || shift.isSeriousLate) "LATE ARRIVAL WARNING (60+ MIN)" else "SHIFT IN PROGRESS / LATE",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StatusWarning
                                    )
                                    Text(
                                        text = if (shift.lateReportRequired || shift.isSeriousLate)
                                            "Reporting to duty >60 minutes past shift start (${shift.startTime.take(5)}). A formal Late Arrival Incident Report is required to clock in."
                                        else
                                            "Scheduled start was ${shift.startTime.take(5)}. Please clock in promptly.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // 5. Attendance Action Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Attendance Status",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                AttendanceStatusPill(shift.attendanceStatus)
                            }

                            Text(
                                text = "Authoritative clock-in records your timestamp and GPS coordinates at the duty post.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (isGuard) {
                                // Action Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val canClockIn = (shift.attendanceStatus == "NOT_CLOCKED_IN" || uiState.isEligibleForDuty) &&
                                            shift.attendanceStatus != "CLOCKED_IN" &&
                                            shift.attendanceStatus != "CLOCKED_OUT" &&
                                            !uiState.clockLoading

                                    Button(
                                        onClick = {
                                            if (shift.lateReportRequired || shift.isSeriousLate) {
                                                showLateArrivalDialog = true
                                            } else {
                                                coroutineScope.launch {
                                                    val loc = LocationHelper.getDeviceLocation(context)
                                                    viewModel.clockIn(
                                                        shiftId = shift.id,
                                                        lat = loc?.first,
                                                        lon = loc?.second
                                                    )
                                                }
                                            }
                                        },
                                        enabled = canClockIn,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .testTag("clock_in_button")
                                    ) {
                                        if (uiState.clockLoading && canClockIn) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        } else {
                                            Icon(Icons.AutoMirrored.Filled.Login, null, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            stringResource(R.string.clock_in_button),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    val canClockOut = shift.attendanceStatus == "CLOCKED_IN" && !uiState.clockLoading

                                    Button(
                                        onClick = {
                                            if (isShiftEarly(shift)) {
                                                showEarlyClockOutDialog = true
                                            } else {
                                                coroutineScope.launch {
                                                    val loc = LocationHelper.getDeviceLocation(context)
                                                    viewModel.clockOut(
                                                        shiftId = shift.id,
                                                        lat = loc?.first,
                                                        lon = loc?.second
                                                    )
                                                }
                                            }
                                        },
                                        enabled = canClockOut,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .testTag("clock_out_button")
                                    ) {
                                        if (uiState.clockLoading && canClockOut) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onSecondary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        } else {
                                            Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            stringResource(R.string.clock_out_button),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Security,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Supervisory Monitoring: Field clock-in and clock-out attendance is recorded directly by the assigned duty officer upon arrival at ${shift.stationName}.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        // Early Clock-Out Authorization Modal Dialog
        if (showEarlyClockOutDialog && shift != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!uiState.clockLoading) {
                        showEarlyClockOutDialog = false
                        earlySupervisorPassword = ""
                        viewModel.clearMessages()
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "EARLY CLOCK-OUT",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This shift is scheduled to end at ${shift.endTime.take(5)}.\nEarly clock-out requires supervisor authorization.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (uiState.errorMessage != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("early_clock_out_error")
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uiState.errorMessage!!,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "6-Digit Authorization OTP (Recommended)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Enter 5-minute single-use OTP issued by your Station Supervisor or Admin.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedTextField(
                            value = earlyOtpCode,
                            onValueChange = { if (it.length <= 6) earlyOtpCode = it.filter { c -> c.isDigit() } },
                            label = { Text("6-Digit OTP Code") },
                            placeholder = { Text("e.g. 123456") },
                            singleLine = true,
                            enabled = !uiState.clockLoading,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("early_clock_out_otp")
                        )

                        Text(
                            text = "— OR Supervisor Direct Sign-Off —",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        OutlinedTextField(
                            value = earlySupervisorUsername,
                            onValueChange = { earlySupervisorUsername = it },
                            label = { Text("Supervisor username") },
                            placeholder = { Text("e.g. supervisor1") },
                            singleLine = true,
                            enabled = !uiState.clockLoading,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("early_supervisor_username")
                        )

                        OutlinedTextField(
                            value = earlySupervisorPassword,
                            onValueChange = { earlySupervisorPassword = it },
                            label = { Text("Supervisor password") },
                            placeholder = { Text("Enter password") },
                            singleLine = true,
                            enabled = !uiState.clockLoading,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("early_supervisor_password")
                        )

                        OutlinedTextField(
                            value = earlyOverrideReason,
                            onValueChange = { earlyOverrideReason = it },
                            label = { Text("Reason / Justification") },
                            placeholder = { Text("Operational justification for early departure") },
                            minLines = 2,
                            maxLines = 4,
                            enabled = !uiState.clockLoading,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("early_override_reason")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val loc = LocationHelper.getDeviceLocation(context)
                                viewModel.clockOut(
                                    shiftId = shift.id,
                                    lat = loc?.first,
                                    lon = loc?.second,
                                    supervisorUsername = earlySupervisorUsername.trim().takeIf { it.isNotBlank() },
                                    supervisorPassword = earlySupervisorPassword.takeIf { it.isNotBlank() },
                                    overrideReason = earlyOverrideReason.trim().takeIf { it.isNotBlank() },
                                    otpCode = earlyOtpCode.trim().takeIf { it.isNotBlank() },
                                    onSuccess = {
                                        showEarlyClockOutDialog = false
                                        earlyOtpCode = ""
                                        earlySupervisorUsername = ""
                                        earlySupervisorPassword = ""
                                        earlyOverrideReason = ""
                                    }
                                )
                            }
                        },
                        enabled = (earlyOtpCode.length == 6 ||
                                (earlySupervisorUsername.isNotBlank() &&
                                earlySupervisorPassword.isNotBlank() &&
                                earlyOverrideReason.isNotBlank())) &&
                                !uiState.clockLoading,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("authorize_clock_out_button")
                    ) {
                        if (uiState.clockLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Authorize & Clock Out")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showEarlyClockOutDialog = false
                            earlySupervisorPassword = ""
                            viewModel.clearMessages()
                        },
                        enabled = !uiState.clockLoading,
                        modifier = Modifier.testTag("cancel_early_clock_out_button")
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Late Arrival Report Dialog
        if (showLateArrivalDialog && shift != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!uiState.isFilingLateReport && !uiState.clockLoading) {
                        showLateArrivalDialog = false
                        lateArrivalError = null
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ReportProblem,
                            contentDescription = null,
                            tint = StatusWarning,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LATE ARRIVAL REPORT",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Scheduled start was ${shift.startTime.take(5)} at ${shift.stationName}. Standard operating procedure mandates filing an official incident report before clock-in when arriving over 60 minutes late.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val activeError = lateArrivalError ?: uiState.errorMessage
                        if (activeError != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("late_arrival_error")
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = activeError,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = lateArrivalReason,
                            onValueChange = {
                                lateArrivalReason = it
                                lateArrivalError = null
                            },
                            label = { Text("Reason for Delay *") },
                            placeholder = { Text("e.g. Public transit breakdown, family emergency...") },
                            modifier = Modifier.fillMaxWidth().testTag("late_arrival_reason_input"),
                            singleLine = false,
                            maxLines = 3,
                            isError = lateArrivalError != null && lateArrivalReason.isBlank()
                        )

                        OutlinedTextField(
                            value = lateArrivalIncidentDetails,
                            onValueChange = { lateArrivalIncidentDetails = it },
                            label = { Text("Additional Incident Details (Optional)") },
                            placeholder = { Text("e.g. Route taken, bus breakdown location...") },
                            modifier = Modifier.fillMaxWidth().testTag("late_arrival_details_input"),
                            singleLine = false,
                            maxLines = 3
                        )

                        OutlinedTextField(
                            value = lateArrivalEstimatedArrival,
                            onValueChange = { lateArrivalEstimatedArrival = it },
                            label = { Text("Actual Arrival Time (Optional)") },
                            placeholder = { Text("HH:MM") },
                            modifier = Modifier.fillMaxWidth().testTag("late_arrival_time_input"),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (lateArrivalReason.isBlank()) {
                                lateArrivalError = "Reason for delay is mandatory."
                                return@Button
                            }
                            lateArrivalError = null
                            coroutineScope.launch {
                                val loc = LocationHelper.getDeviceLocation(context)
                                viewModel.submitLateArrivalReport(
                                    shiftId = shift.id,
                                    reason = lateArrivalReason.trim(),
                                    incidentDetails = lateArrivalIncidentDetails.trim(),
                                    estimatedArrival = lateArrivalEstimatedArrival.trim(),
                                    onSuccess = { caseNumber ->
                                        viewModel.clockIn(
                                            shiftId = shift.id,
                                            lat = loc?.first,
                                            lon = loc?.second,
                                            caseNumber = caseNumber,
                                            onSuccess = {
                                                showLateArrivalDialog = false
                                                lateArrivalReason = ""
                                                lateArrivalIncidentDetails = ""
                                                lateArrivalEstimatedArrival = ""
                                            }
                                        )
                                    }
                                )
                            }
                        },
                        enabled = !uiState.isFilingLateReport && !uiState.clockLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("submit_late_report_and_clock_in_button")
                    ) {
                        if (uiState.isFilingLateReport || uiState.clockLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Submit & Clock In")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showLateArrivalDialog = false
                            lateArrivalError = null
                        },
                        enabled = !uiState.isFilingLateReport && !uiState.clockLoading
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun ShiftTypeBadge(type: String, isOverride: Boolean = false) {
    if (isOverride) {
        Surface(
            color = Color(0xFF2563EB).copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = Color(0xFF2563EB),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "REASSIGNED DUTY ($type)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2563EB)
                )
            }
        }
        return
    }

    val isDay = type == "DAY"
    val bgColor = if (isDay) GoldAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer
    val textColor = if (isDay) GoldAccent else MaterialTheme.colorScheme.primary

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDay) Icons.Default.WbSunny else Icons.Default.Nightlight,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = type,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

@Composable
fun AttendanceStatusPill(status: String) {
    val (label, bg, fg) = when (status) {
        "CLOCKED_IN" -> Triple("ON DUTY", StatusSuccess.copy(alpha = 0.2f), StatusSuccess)
        "CLOCKED_OUT" -> Triple("COMPLETED", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        "REASSIGNED" -> Triple("REASSIGNED DUTY", Color(0xFF2563EB).copy(alpha = 0.15f), Color(0xFF2563EB))
        else -> Triple("NOT CLOCKED IN", StatusWarning.copy(alpha = 0.2f), StatusWarning)
    }

    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
