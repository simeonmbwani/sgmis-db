package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.Shift
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var earlySupervisorUsername by remember { mutableStateOf("") }
    var earlySupervisorPassword by remember { mutableStateOf("") }
    var earlyOverrideReason by remember { mutableStateOf("") }

    fun isShiftEarly(s: Shift?): Boolean {
        if (s == null) return false
        return try {
            val startH = s.startTime.take(5)
            val endH = s.endTime.take(5)
            val isOvernight = endH <= startH
            val endCal = Calendar.getInstance()
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
            Calendar.getInstance().before(endCal)
        } catch (e: Exception) {
            false
        }
    }

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.errorMessage?.contains("requires authenticated supervisor authorization", ignoreCase = true) == true) {
            showEarlyClockOutDialog = true
        }
        if (!showEarlyClockOutDialog && (uiState.successMessage != null || uiState.errorMessage != null)) {
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
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.today_shift_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("today_shift_back_button")) {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            if (uiState.shiftLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.errorMessage != null && shift == null) {
                // Dedicated Error State
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp)
                        .testTag("shift_error_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = StatusError,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Failed to Load Shift",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                viewModel.clearError()
                                viewModel.fetchTodayShift()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Loading Shift")
                        }
                    }
                }
            } else if (shift == null) {
                // Empty state
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp)
                        .testTag("no_shift_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EventBusy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.no_shift_scheduled),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isGuard) {
                                "You do not have an active shift assignment on today's roster."
                            } else {
                                "No active operational shift is currently scheduled for today on this roster."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { viewModel.fetchTodayShift() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Check Roster Again")
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Feedback Messages
                    if (uiState.successMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StatusSuccess.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(uiState.successMessage!!, style = MaterialTheme.typography.bodySmall, color = StatusSuccess)
                            }
                        }
                    }

                    if (uiState.errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Error, null, tint = StatusError, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(uiState.errorMessage!!, style = MaterialTheme.typography.bodySmall, color = StatusError)
                            }
                        }
                    }

                    // Main Shift Assignment Card
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
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = shift.stationName,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isGuard) "Official Duty Assignment" else "Authoritative Station Duty Schedule",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                ShiftTypeBadge(shift.shiftType)
                            }

                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

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

                    // Mandatory Shift Timing & Schedule Protocol Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("timing_protocol_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Mandatory Schedule & Timing Protocol", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("REPORTING WINDOW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("30 mins before start", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                                Column {
                                    Text("GRACE PERIOD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("15 mins max", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = StatusWarning)
                                }
                                Column {
                                    Text("SHIFT DURATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("12 Hours Standard", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
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

                    // Assigned Primary Duty Officer Card
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
                                .padding(20.dp),
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
                                    text = shift.guardName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!shift.employeeNumber.isNullOrBlank()) {
                                    Text(
                                        text = "Employee ID: ${shift.employeeNumber}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Assigned Guard Partner Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("partner_card"),
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
                                    .size(48.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Assigned Guard Partner",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = shift.partnerName ?: "Single Officer Post",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (shift.partnerEmployeeNumber != null) {
                                    Text(
                                        text = "Employee ID: ${shift.partnerEmployeeNumber}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Attendance Action Card
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
                                .padding(20.dp),
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
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val loc = LocationHelper.getDeviceLocation(context)
                                                viewModel.clockIn(
                                                    shiftId = shift.id,
                                                    lat = loc?.first,
                                                    lon = loc?.second
                                                )
                                            }
                                        },
                                        enabled = shift.attendanceStatus == "NOT_CLOCKED_IN" && !uiState.clockLoading,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("clock_in_button")
                                    ) {
                                        Icon(Icons.Default.Login, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.clock_in_button))
                                    }

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
                                        enabled = shift.attendanceStatus == "CLOCKED_IN" && !uiState.clockLoading,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("clock_out_button")
                                    ) {
                                        Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.clock_out_button))
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

                    Spacer(modifier = Modifier.height(88.dp))
                }
            }
        }

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
                                    supervisorUsername = earlySupervisorUsername.trim(),
                                    supervisorPassword = earlySupervisorPassword,
                                    overrideReason = earlyOverrideReason.trim(),
                                    onSuccess = {
                                        showEarlyClockOutDialog = false
                                        earlySupervisorUsername = ""
                                        earlySupervisorPassword = ""
                                        earlyOverrideReason = ""
                                    }
                                )
                            }
                        },
                        enabled = earlySupervisorUsername.isNotBlank() &&
                                earlySupervisorPassword.isNotBlank() &&
                                earlyOverrideReason.isNotBlank() &&
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
    }
}

@Composable
fun ShiftTypeBadge(type: String) {
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
