package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.LeaveApplication
import com.example.data.model.LeaveBalance
import com.example.data.model.LeaveAccrualRecord
import com.example.data.model.LeaveCategoryRow
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaveScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showApplyDialog by remember { mutableStateOf(false) }
    var appToReject by remember { mutableStateOf<LeaveApplication?>(null) }
    var appToApprove by remember { mutableStateOf<LeaveApplication?>(null) }
    var confirmAccrualRun by remember { mutableStateOf(false) }
    val currentUserRole = uiState.currentUser?.role
    val isSupervisor = currentUserRole?.uppercase() == "SUPERVISOR"
    val isAdmin = currentUserRole?.uppercase() in listOf("ADMINISTRATOR", "ADMIN")

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentUserRole) {
        viewModel.fetchLeave()
        if (isSupervisor || isAdmin) {
            viewModel.fetchStationLeaveBalances()
        }
        if (isAdmin) {
            viewModel.fetchLeaveAccrualRecords()
        }
    }

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.leave_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("leave_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.fetchLeave()
                            if (isSupervisor || isAdmin) {
                                viewModel.fetchStationLeaveBalances()
                            }
                            if (isAdmin) {
                                viewModel.fetchLeaveAccrualRecords()
                            }
                        },
                        modifier = Modifier.testTag("refresh_leave_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh Leave")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Notification banners
            if (uiState.successMessage != null) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.successMessage!!,
                            color = StatusSuccess,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (uiState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Role-Specific Navigation Tabs
            if (isAdmin) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("National & Accruals", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("All Applications", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = { Text("My Leave", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp)) }
                    )
                }
            } else if (isSupervisor) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("Station Command", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.SupervisorAccount, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("My Leave", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            // Content per role and selected tab
            when {
                isAdmin && selectedTabIndex == 0 -> {
                    AdminNationalAccrualView(
                        balances = uiState.stationLeaveBalances,
                        accrualRecords = uiState.leaveAccrualRecords,
                        isProcessing = uiState.accrualProcessing,
                        onRunAccrual = { confirmAccrualRun = true }
                    )
                }
                isAdmin && selectedTabIndex == 1 -> {
                    ApplicationsReviewQueueView(
                        applications = uiState.leaveApplications,
                        canReview = true,
                        onApprove = { app -> appToApprove = app },
                        onReject = { app -> appToReject = app }
                    )
                }
                isSupervisor && selectedTabIndex == 0 -> {
                    SupervisorStationCommandView(
                        balances = uiState.stationLeaveBalances,
                        applications = uiState.leaveApplications.filter { it.status == "PENDING" },
                        onApprove = { app ->
                            val note = if (app.leaveType == "SICK") "Medical report verified and approved by Supervisor" else "Approved by Station Supervisor"
                            viewModel.reviewLeaveApplication(app.id, "APPROVED", note)
                        },
                        onReject = { app -> appToReject = app }
                    )
                }
                else -> {
                    // Guard default view / Personal Leave tab
                    PersonalLeaveView(
                        summary = uiState.leaveSummary,
                        balance = uiState.leaveBalance,
                        applications = uiState.leaveApplications,
                        canReview = false,
                        onApprove = {},
                        onReject = {},
                        onApplyLeave = { showApplyDialog = true }
                    )
                }
            }
        }
    }

    if (showApplyDialog) {
        ApplyLeaveDialog(
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showApplyDialog = false
            },
            onSubmit = { type, start, end, reason, emergencyPhone, emergencyAddress, doctorReport, eventDetails ->
                viewModel.applyForLeave(type, start, end, reason, emergencyPhone, emergencyAddress, doctorReport, eventDetails) {
                    showApplyDialog = false
                }
            }
        )
    }

    if (appToReject != null) {
        RejectLeaveDialog(
            app = appToReject!!,
            onDismiss = { appToReject = null },
            onConfirmReject = { reasonKey, notes ->
                viewModel.reviewLeaveApplication(
                    id = appToReject!!.id,
                    status = "REJECTED",
                    notes = notes,
                    rejectionReason = reasonKey
                )
                appToReject = null
            }
        )
    }
    appToApprove?.let { application ->
        AlertDialog(
            onDismissRequest = { appToApprove = null },
            title = { Text("Approve leave application?") },
            text = { Text("${application.guardName} · ${application.leaveType}\nDates: ${application.startDate} – ${application.endDate}\nStatus: ${application.status} → APPROVED\nApproved leave blocks roster duty for these dates.") },
            confirmButton = { TextButton(onClick = {
                viewModel.reviewLeaveApplication(application.id, "APPROVED", "Approved by National Administrator")
                appToApprove = null
            }) { Text("Confirm approval") } },
            dismissButton = { TextButton(onClick = { appToApprove = null }) { Text("Back") } }
        )
    }
    if (confirmAccrualRun) AlertDialog(
        onDismissRequest = { confirmAccrualRun = false },
        title = { Text("Process completed-month leave accruals?") },
        text = { Text("This credits eligible completed months to guard leave balances and records each credit in the backend accrual ledger. No incomplete month is credited.") },
        confirmButton = { TextButton(onClick = { confirmAccrualRun = false; viewModel.processMonthlyAccruals() }) { Text("Confirm accrual") } },
        dismissButton = { TextButton(onClick = { confirmAccrualRun = false }) { Text("Cancel") } }
    )
}

@Composable
fun PersonalLeaveView(
    summary: com.example.data.model.LeaveSummary?,
    balance: LeaveBalance?,
    applications: List<LeaveApplication>,
    canReview: Boolean,
    onApprove: (LeaveApplication) -> Unit,
    onReject: (LeaveApplication) -> Unit,
    onApplyLeave: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Company Administration routing notice
        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Official Notice: Casual Leave (1.0/mo) and Vacation (2.5/mo) accrue after completed working months. Public holiday duties earn 2 compensatory days.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Authoritative Leave & Compensation Summary Table
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("leave_balance_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Official Leave & Compensation Summary (${summary?.year ?: balance?.year ?: 2026})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Table Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Category", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.8f))
                        Text("Accrued/Earned", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                        Text("Used", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
                        Text("Remaining", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.0f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Authoritative rows: Vacation, Casual, Public Holiday Compensation
                    val rows = if (!summary?.categories.isNullOrEmpty()) {
                        summary!!.categories
                    } else {
                        listOf(
                            LeaveCategoryRow("Vacation Leave", "Accrued", balance?.vacationDays ?: 0.0, balance?.usedVacation ?: 0.0, balance?.remainingVacation ?: 0.0),
                            LeaveCategoryRow("Casual Leave", "Accrued", balance?.casualDays ?: 0.0, balance?.usedCasual ?: 0.0, balance?.remainingCasual ?: 0.0),
                            LeaveCategoryRow("Public Holiday Compensation", "Earned", balance?.compensationEarned ?: 0.0, balance?.compensationUsed ?: 0.0, balance?.remainingCompensation ?: 0.0)
                        )
                    }

                    rows.forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(row.category, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.8f))
                            Text("%.1f".format(row.accruedOrEarned), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.3f))
                            Text("%.1f".format(row.used), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.9f))
                            Text("%.1f".format(row.remaining), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1.0f))
                        }
                    }
                }
            }
        }

        // Inline Apply for Leave Action Button (Single clear action)
        item {
            Button(
                onClick = onApplyLeave,
                modifier = Modifier.fillMaxWidth().testTag("apply_leave_inline_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply for Leave", fontWeight = FontWeight.SemiBold)
            }
        }

        // Applications History Section Header
        item {
            Text("Applications History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (applications.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No leave applications filed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(applications) { app ->
                LeaveAppCard(
                    app = app,
                    canReview = canReview,
                    onApprove = { onApprove(app) },
                    onReject = { onReject(app) }
                )
            }
        }
    }
}

@Composable
fun SupervisorStationCommandView(
    balances: List<LeaveBalance>,
    applications: List<LeaveApplication>,
    onApprove: (LeaveApplication) -> Unit,
    onReject: (LeaveApplication) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SupervisorAccount, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Station Leave Command: Read-only balances oversight. Review pending leave applications and verify doctor's reports before approving sick leave.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Text(
                text = "Station Guards Leave Balances (${balances.size} Guards)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            StationGuardLeaveTable(balances = balances)
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pending Leave Review Queue (${applications.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (applications.isNotEmpty()) {
                    Surface(
                        color = StatusWarning.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "ACTION REQUIRED",
                            color = StatusWarning,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        if (applications.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No pending leave applications awaiting supervisor review.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(applications) { app ->
                LeaveAppCard(
                    app = app,
                    canReview = true,
                    onApprove = { onApprove(app) },
                    onReject = { onReject(app) }
                )
            }
        }
    }
}

@Composable
fun AdminNationalAccrualView(
    balances: List<LeaveBalance>,
    accrualRecords: List<LeaveAccrualRecord>,
    isProcessing: Boolean,
    onRunAccrual: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Accrual Engine Action Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Authoritative Monthly Accrual Engine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = "Calculates and credits Casual (+1.0 day/mo) and Vacation (+2.5 days/mo, 90 cap) strictly for completed working months. Fully idempotent and audited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Button(
                        onClick = onRunAccrual,
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth().testTag("btn_run_monthly_accrual"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Processing Accruals Transactionally...")
                        } else {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Execute Monthly Accrual Now")
                        }
                    }
                }
            }
        }

        // Accrual Audit Trail
        item {
            Text(
                text = "Accrual Ledger Audit Records (${accrualRecords.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            AuthoritativeAccrualAuditTable(records = accrualRecords)
        }

        // National Guard Balances Table
        item {
            Text(
                text = "Company-Wide Guard Balances (${balances.size} Guards)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            StationGuardLeaveTable(balances = balances)
        }
    }
}

@Composable
fun ApplicationsReviewQueueView(
    applications: List<LeaveApplication>,
    canReview: Boolean,
    onApprove: (LeaveApplication) -> Unit,
    onReject: (LeaveApplication) -> Unit
) {
    if (applications.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No leave applications in system.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(applications) { app ->
                LeaveAppCard(
                    app = app,
                    canReview = canReview && app.status == "PENDING",
                    onApprove = { onApprove(app) },
                    onReject = { onReject(app) }
                )
            }
        }
    }
}

@Composable
fun StationGuardLeaveTable(balances: List<LeaveBalance>) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("station_guard_leave_table"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("GUARD NAME", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(130.dp))
                Text("CASUAL (ACC/USE/REM)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(140.dp))
                Text("VACATION (ACC/USE/REM)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(145.dp))
                Text("HOLIDAY COMP (EARN/USE/REM)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(160.dp))
                Text("LAST ACCRUAL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
            }

            if (balances.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("No guard balances loaded for station.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            } else {
                balances.forEach { b ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = b.guardName ?: "Guard ${b.guard.take(6)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(130.dp)
                        )
                        Text(
                            text = "%.1f / %.1f / %.1f".format(b.casualDays, b.usedCasual, b.remainingCasual),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(140.dp)
                        )
                        Text(
                            text = "%.1f / %.1f / %.1f".format(b.vacationDays, b.usedVacation, b.remainingVacation),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(145.dp)
                        )
                        Text(
                            text = "%.1f / %.1f / %.1f".format(b.compensationEarned, b.compensationUsed, b.remainingCompensation),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(160.dp)
                        )
                        Text(
                            text = b.lastAccrualDate ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            modifier = Modifier.width(100.dp)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun AuthoritativeAccrualAuditTable(records: List<LeaveAccrualRecord>) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("accrual_audit_table"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MONTH", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
                Text("GUARD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
                Text("CASUAL (+)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(75.dp))
                Text("VACATION (+)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(85.dp))
                Text("POST BALANCES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
                Text("TIMESTAMP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(130.dp))
                Text("BY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
            }

            if (records.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("No monthly accrual audit records logged.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            } else {
                records.take(50).forEach { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${r.year}-${"%02d".format(r.month)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(70.dp)
                        )
                        Text(
                            text = r.guardName ?: "Guard ${r.guard.take(6)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(120.dp)
                        )
                        Text(
                            text = "+%.1f".format(r.casualCredited),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusSuccess,
                            modifier = Modifier.width(75.dp)
                        )
                        Text(
                            text = "+%.1f".format(r.vacationCredited),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusSuccess,
                            modifier = Modifier.width(85.dp)
                        )
                        Text(
                            text = "C:%.1f V:%.1f".format(r.casualBalanceAfter, r.vacationBalanceAfter),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.width(120.dp)
                        )
                        Text(
                            text = r.createdAt.take(19).replace("T", " "),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            modifier = Modifier.width(130.dp)
                        )
                        Text(
                            text = r.createdByName ?: "System Engine",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            modifier = Modifier.width(90.dp)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun LeaveAppCard(
    app: LeaveApplication,
    canReview: Boolean = false,
    onApprove: () -> Unit = {},
    onReject: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("leave_item_${app.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Horizontal Status Badges and Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${app.leaveTypeDisplay ?: app.leaveType} • ${app.guardName}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = when (app.status) {
                            "APPROVED" -> StatusSuccess.copy(alpha = 0.2f)
                            "REJECTED" -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                            else -> StatusWarning.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = app.statusDisplay ?: app.status,
                            color = when (app.status) {
                                "APPROVED" -> StatusSuccess
                                "REJECTED" -> MaterialTheme.colorScheme.error
                                else -> StatusWarning
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (app.status == "REJECTED" && !app.rejectionReason.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = app.rejectionReasonDisplay ?: app.rejectionReason ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
            Text("Period: ${app.startDate} to ${app.endDate}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(app.reason, style = MaterialTheme.typography.bodyMedium)

            if (!app.rejectionReason.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Rejection Ground: ${app.rejectionReasonDisplay ?: app.rejectionReason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (!app.emergencyPhone.isNullOrBlank()) {
                Text("Emergency Phone: ${app.emergencyPhone}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            if (!app.emergencyAddress.isNullOrBlank()) {
                Text("Emergency Address: ${app.emergencyAddress}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!app.doctorReport.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MedicalServices, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (app.doctorReportVerified) "Doctor's Report [VERIFIED]" else "Doctor's Report [REQUIRES VERIFICATION]",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = app.doctorReport,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (!app.eventDetails.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.EventNote, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Special Event Justification",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = app.eventDetails,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (!app.reviewerNotes.isNullOrBlank()) {
                Text("Review: ${app.reviewerName ?: "Supervisor"} - ${app.reviewerNotes}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (canReview) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onReject, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Reject")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onApprove) {
                        Text(if (app.leaveType == "SICK") "Verify & Approve" else "Approve")
                    }
                }
            }
        }
    }
}

@Composable
fun ApplyLeaveDialog(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String, String?, String?, String?, String?) -> Unit
) {
    var type by remember { mutableStateOf("VACATION") }
    val todayStr = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    }
    val defaultEndStr = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(
            java.util.Date(System.currentTimeMillis() + 3 * 86400000L)
        )
    }
    var start by remember { mutableStateOf(todayStr) }
    var end by remember { mutableStateOf(defaultEndStr) }
    var reason by remember { mutableStateOf("") }
    var emergencyPhone by remember { mutableStateOf("") }
    var emergencyAddress by remember { mutableStateOf("") }
    var doctorReport by remember { mutableStateOf("") }
    var eventDetails by remember { mutableStateOf("") }

    val leaveTypes = listOf("VACATION", "CASUAL", "COMPENSATION", "SICK", "SPECIAL", "EMERGENCY", "COMPASSIONATE")
    val isSickValid = type != "SICK" || doctorReport.isNotBlank()
    val isSpecialValid = type != "SPECIAL" || eventDetails.isNotBlank()
    val isValid = reason.isNotBlank() && start.isNotBlank() && end.isNotBlank() && isSickValid && isSpecialValid

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Apply for Official Leave") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text("Leave Type:", style = MaterialTheme.typography.labelSmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    leaveTypes.take(4).forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { if (!isLoading) type = t },
                            label = { Text(t, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    leaveTypes.drop(4).forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { if (!isLoading) type = t },
                            label = { Text(t, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Start Date (YYYY-MM-DD) *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("End Date (YYYY-MM-DD) *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason for Application *") },
                    minLines = 2,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("leave_reason_input")
                )

                if (type == "SICK") {
                    OutlinedTextField(
                        value = doctorReport,
                        onValueChange = { doctorReport = it },
                        label = { Text("Doctor's Report / Medical Certificate Details *") },
                        minLines = 2,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth().testTag("leave_doctor_report_input")
                    )
                }

                if (type == "SPECIAL") {
                    OutlinedTextField(
                        value = eventDetails,
                        onValueChange = { eventDetails = it },
                        label = { Text("Event Justification / Documentation *") },
                        minLines = 2,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth().testTag("leave_event_details_input")
                    )
                }

                OutlinedTextField(
                    value = emergencyPhone,
                    onValueChange = { emergencyPhone = it },
                    label = { Text("Emergency Contact Phone") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("leave_emergency_phone_input")
                )

                OutlinedTextField(
                    value = emergencyAddress,
                    onValueChange = { emergencyAddress = it },
                    label = { Text("Emergency Physical Address") },
                    minLines = 2,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("leave_emergency_address_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid && !isLoading) {
                        onSubmit(
                            type,
                            start,
                            end,
                            reason,
                            emergencyPhone.ifBlank { null },
                            emergencyAddress.ifBlank { null },
                            doctorReport.ifBlank { null },
                            eventDetails.ifBlank { null }
                        )
                    }
                },
                enabled = isValid && !isLoading,
                modifier = Modifier.testTag("submit_leave_confirm_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Submitting...")
                } else {
                    Text("Submit Application")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RejectLeaveDialog(
    app: LeaveApplication,
    onDismiss: () -> Unit,
    onConfirmReject: (reasonKey: String, notes: String) -> Unit
) {
    val reasons = listOf(
        "MANPOWER_SHORTAGE" to "Manpower Shortage / High Demand",
        "CRITICAL_SCHEDULE" to "Critical Event / VIP Schedule",
        "INSUFFICIENT_DAYS" to "Insufficient Accrued Days",
        "SPECIAL_FUNCTIONS" to "Special Upcoming Functions",
        "OTHER" to "Other Operational Grounds"
    )
    var selectedReason by remember { mutableStateOf(reasons.first().first) }
    var notes by remember { mutableStateOf("") }
    var expandedDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reject Leave Application", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "A mandatory operational justification is required for evidence and audit integrity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ExposedDropdownMenuBox(
                    expanded = expandedDropdown,
                    onExpandedChange = { expandedDropdown = !expandedDropdown }
                ) {
                    OutlinedTextField(
                        value = reasons.firstOrNull { it.first == selectedReason }?.second ?: selectedReason,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Rejection Reason *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedDropdown,
                        onDismissRequest = { expandedDropdown = false }
                    ) {
                        reasons.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedReason = key
                                    expandedDropdown = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Supervisor Comments") },
                    placeholder = { Text("Additional notes regarding rejection...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmReject(selectedReason, notes.ifBlank { "Rejected by Supervisor" })
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Confirm Rejection")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
