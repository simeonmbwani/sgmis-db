package com.example.ui.screens

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.LeaveApplication
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
    val currentUserRole = uiState.currentUser?.role

    LaunchedEffect(Unit) {
        viewModel.fetchLeave()
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
                        onClick = { viewModel.fetchLeave() },
                        modifier = Modifier.testTag("refresh_leave_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh Leave")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showApplyDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Apply for Leave") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("apply_leave_fab")
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

            // Company Administration routing notice
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
                        text = "Official Notice: Leave applications are routed directly to Company Administration for review and approval.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Authoritative Leave & Compensation Summary Table (Phase 5C)
            val summary = uiState.leaveSummary
            val balance = uiState.leaveBalance
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
                            com.example.data.model.LeaveCategoryRow("Vacation Leave", "Accrued", balance?.vacationDays ?: 0.0, balance?.usedVacation ?: 0.0, balance?.remainingVacation ?: 0.0),
                            com.example.data.model.LeaveCategoryRow("Casual Leave", "Accrued", balance?.casualDays ?: 0.0, balance?.usedCasual ?: 0.0, balance?.remainingCasual ?: 0.0),
                            com.example.data.model.LeaveCategoryRow("Public Holiday Compensation", "Earned", 0.0, 0.0, 0.0)
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

            Text("Applications History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (uiState.leaveApplications.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No leave applications filed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val isSupervisorOrAdmin = uiState.currentUser?.role?.uppercase() in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.leaveApplications) { app ->
                        LeaveAppCard(
                            app = app,
                            canReview = isSupervisorOrAdmin && app.status == "PENDING",
                            onApprove = { viewModel.reviewLeaveApplication(app.id, "APPROVED", "Approved by Supervisor") },
                            onReject = { appToReject = app }
                        )
                    }
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
            onSubmit = { type, start, end, reason, emergencyPhone, emergencyAddress ->
                viewModel.applyForLeave(type, start, end, reason, emergencyPhone, emergencyAddress) {
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

            if (!app.reviewerNotes.isNullOrBlank()) {
                Text("Review: ${app.reviewerName ?: "Supervisor"} - ${app.reviewerNotes}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (canReview) {
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onReject, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Reject")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onApprove) {
                        Text("Approve")
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
    onSubmit: (String, String, String, String, String?, String?) -> Unit
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

    val leaveTypes = listOf("VACATION", "CASUAL", "COMPENSATION", "SICK", "EMERGENCY", "COMPASSIONATE")
    val isValid = reason.isNotBlank() && start.isNotBlank() && end.isNotBlank()

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
                    leaveTypes.take(3).forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { if (!isLoading) type = t },
                            label = { Text(t, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    leaveTypes.drop(3).forEach { t ->
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
                        onSubmit(type, start, end, reason, emergencyPhone.ifBlank { null }, emergencyAddress.ifBlank { null })
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

