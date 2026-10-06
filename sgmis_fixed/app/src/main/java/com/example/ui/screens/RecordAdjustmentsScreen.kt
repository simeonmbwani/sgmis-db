package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AppRole
import com.example.data.model.CreateRecordAdjustmentRequest
import com.example.data.model.RecordAdjustmentRequest
import com.example.ui.viewmodel.SgmisViewModel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordAdjustmentsScreen(viewModel: SgmisViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val role = state.currentUser?.appRole ?: AppRole.GUARD
    val isAdmin = role == AppRole.ADMINISTRATOR
    val canRequest = role == AppRole.SUPERVISOR || isAdmin
    var showCreate by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RecordAdjustmentRequest?>(null) }
    var rejectTarget by remember { mutableStateOf<RecordAdjustmentRequest?>(null) }
    var rejectionReason by remember { mutableStateOf("") }
    var approvedValue by remember { mutableStateOf("") }
    var confirmApproval by remember { mutableStateOf(false) }
    var confirmRejection by remember { mutableStateOf(false) }

    LaunchedEffect(role) {
        if (role == AppRole.GUARD) onBack()
        viewModel.fetchRecordAdjustments()
        if (canRequest) viewModel.fetchUsers(role = "GUARD")
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Master Record Adjustments", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { viewModel.fetchRecordAdjustments() }) { Icon(Icons.Default.Refresh, "Refresh") } }
        )
    }, floatingActionButton = {
        if (canRequest) ExtendedFloatingActionButton(
            onClick = { showCreate = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text(if (isAdmin) "New Reconciliation" else "Request Adjustment") }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                if (isAdmin) "Superuser Master Adjustments: Apply direct reconciliations or review proposed supervisor requests. Approved adjustments immediately update master database records."
                else "Supervisor requests require administrator review. Approved values are applied by the backend workflow.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            if (state.adjustmentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.errorMessage != null) Text(state.errorMessage!!, color = MaterialTheme.colorScheme.error)
            if (state.recordAdjustments.isEmpty() && !state.adjustmentsLoading) {
                Text("No adjustment records found.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.recordAdjustments) { row ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { selected = row }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${row.guardName ?: row.guard} · ${row.fieldName}", fontWeight = FontWeight.Bold)
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = when (row.status) {
                                        "APPROVED" -> MaterialTheme.colorScheme.primaryContainer
                                        "REJECTED" -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = row.statusDisplay ?: row.status,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text("${row.oldValue.takeIf { !it.isNullOrBlank() } ?: "(empty)"}  →  ${row.approvedValue?.takeIf { it.isNotBlank() } ?: row.requestedValue}")
                            Text("Effective: ${row.effectiveDate} · Reason: ${row.reason}", style = MaterialTheme.typography.bodySmall)
                            Text("Requested by ${row.requestedByName ?: "Unknown"} · ${row.createdAt ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            if (row.reviewedByName != null) {
                                Text("Reviewed by ${row.reviewedByName} · ${row.reviewedAt.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (isAdmin && row.status == "PENDING") {
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { approvedValue = row.requestedValue; selected = row }) { Text("Review / Approve") }
                                    OutlinedButton(onClick = { rejectTarget = row }) { Text("Reject") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) AdjustmentRequestDialog(
        users = state.users.filter { it.role.uppercase() == "GUARD" },
        isAdmin = isAdmin,
        loading = state.adjustmentsLoading,
        onDismiss = { showCreate = false },
        onSubmit = { request -> viewModel.submitRecordAdjustment(request) { showCreate = false } }
    )

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Adjustment Detail") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Employee: ${row.guardName ?: row.guard} (${row.guardEmployeeId ?: "No employee ID"})")
                    Text("Field: ${row.fieldName} · Effective: ${row.effectiveDate}")
                    Text("Current/Old Value: ${row.oldValue.takeIf { !it.isNullOrBlank() } ?: "(empty)"}")
                    Text("Requested Value: ${row.requestedValue}")
                    if (row.approvedValue?.isNotBlank() == true) Text("Approved Value: ${row.approvedValue}")
                    Text("Status: ${row.statusDisplay ?: row.status}")
                    Text("Reason: ${row.reason}")
                    Text("Requested by: ${row.requestedByName ?: "Unknown"} · ${row.createdAt.orEmpty()}")
                    if (row.reviewedByName != null) Text("Reviewed by: ${row.reviewedByName} · ${row.reviewedAt.orEmpty()}")
                    if (!row.rejectionReason.isNullOrBlank()) Text("Rejection reason: ${row.rejectionReason}")
                    if (row.status == "PENDING" && isAdmin) {
                        OutlinedTextField(
                            value = approvedValue,
                            onValueChange = { approvedValue = it },
                            label = { Text("Approved value (defaults to requested)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                if (isAdmin && row.status == "PENDING") {
                    Button(onClick = { confirmApproval = true }) { Text("Review Approval") }
                } else {
                    TextButton(onClick = { selected = null }) { Text("Close") }
                }
            },
            dismissButton = {
                if (isAdmin && row.status == "PENDING") TextButton(onClick = { selected = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmApproval && selected != null) {
        val row = selected!!
        AlertDialog(
            onDismissRequest = { confirmApproval = false },
            title = { Text("Approve this adjustment?") },
            text = {
                Text("${row.guardName ?: row.guard} · ${row.fieldName}\nCurrent value: ${row.oldValue.orEmpty()}\nNew value: ${approvedValue.ifBlank { row.requestedValue }}\nEffective: ${row.effectiveDate}")
            },
            confirmButton = {
                Button(onClick = {
                    confirmApproval = false
                    viewModel.reviewRecordAdjustment(row.id, true, approvedValue.ifBlank { row.requestedValue })
                    selected = null
                }) { Text("Confirm Approval") }
            },
            dismissButton = { TextButton(onClick = { confirmApproval = false }) { Text("Back") } }
        )
    }

    rejectTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { rejectTarget = null; rejectionReason = "" },
            title = { Text("Reject Adjustment") },
            text = {
                OutlinedTextField(
                    value = rejectionReason,
                    onValueChange = { rejectionReason = it },
                    label = { Text("Mandatory rejection reason") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            },
            confirmButton = {
                Button(
                    enabled = rejectionReason.isNotBlank(),
                    onClick = { confirmRejection = true }
                ) { Text("Review Rejection") }
            },
            dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("Cancel") } }
        )
    }

    if (confirmRejection && rejectTarget != null) {
        val row = rejectTarget!!
        AlertDialog(
            onDismissRequest = { confirmRejection = false },
            title = { Text("Confirm Rejection") },
            text = {
                Text("${row.guardName ?: row.guard} · ${row.fieldName}\nCurrent value: ${row.oldValue.orEmpty()}\nRequested value: ${row.requestedValue}\nRejection Reason: $rejectionReason")
            },
            confirmButton = {
                Button(onClick = {
                    confirmRejection = false
                    viewModel.reviewRecordAdjustment(row.id, false, rejectionReason)
                    rejectTarget = null
                    rejectionReason = ""
                }) { Text("Confirm Rejection") }
            },
            dismissButton = { TextButton(onClick = { confirmRejection = false }) { Text("Back") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentRequestDialog(
    users: List<com.example.data.model.User>,
    isAdmin: Boolean,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (CreateRecordAdjustmentRequest) -> Unit
) {
    var guard by remember { mutableStateOf<com.example.data.model.User?>(null) }
    var field by remember { mutableStateOf("employee_number") }
    var value by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var reason by remember { mutableStateOf("") }
    var directReconciliation by remember { mutableStateOf(isAdmin) }

    var guardDropdownExpanded by remember { mutableStateOf(false) }
    var fieldDropdownExpanded by remember { mutableStateOf(false) }

    val fields = listOf(
        "employee_number",
        "first_name",
        "last_name",
        "station",
        "shift_type",
        "pair_guard",
        "roster_position",
        "vacation_balance",
        "casual_balance",
        "compensation_days",
        "annual_balance",
        "sick_balance",
        "assignment_type",
        "duty_state"
    )

    val currentValDisplay = remember(guard, field) {
        if (guard == null) "None"
        else when (field) {
            "employee_number" -> guard?.employeeNumber ?: "None"
            "first_name" -> guard?.firstName ?: "None"
            "last_name" -> guard?.lastName ?: "None"
            "station" -> guard?.stationName ?: guard?.station ?: "None"
            else -> "Authoritative record"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isAdmin) "New Record Reconciliation" else "Submit Record Adjustment Request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Guard Selector
                ExposedDropdownMenuBox(
                    expanded = guardDropdownExpanded,
                    onExpandedChange = { guardDropdownExpanded = !guardDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = guard?.let { "${it.fullName ?: it.username} (${it.employeeNumber.orEmpty()})" } ?: "Select guard",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Affected Guard") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = guardDropdownExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = guardDropdownExpanded,
                        onDismissRequest = { guardDropdownExpanded = false }
                    ) {
                        users.forEach { u ->
                            DropdownMenuItem(
                                text = { Text("${u.fullName ?: u.username} · ${u.employeeNumber.orEmpty()}") },
                                onClick = { guard = u; guardDropdownExpanded = false }
                            )
                        }
                    }
                }

                // Field Selector
                ExposedDropdownMenuBox(
                    expanded = fieldDropdownExpanded,
                    onExpandedChange = { fieldDropdownExpanded = !fieldDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = field,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Field to Adjust") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fieldDropdownExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = fieldDropdownExpanded,
                        onDismissRequest = { fieldDropdownExpanded = false }
                    ) {
                        fields.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = { field = item; fieldDropdownExpanded = false }
                            )
                        }
                    }
                }

                Text("Current value: $currentValDisplay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Requested / Target Value") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Effective Date (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason / Citation (Mandatory)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                if (isAdmin) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = directReconciliation,
                            onCheckedChange = { directReconciliation = it }
                        )
                        Text("Apply immediately as approved reconciliation", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && guard != null && value.isNotBlank() && reason.isNotBlank(),
                onClick = {
                    val selected = guard ?: return@Button
                    val targetStatus = if (isAdmin && directReconciliation) "APPROVED" else "PENDING"
                    onSubmit(
                        CreateRecordAdjustmentRequest(
                            guard = selected.id,
                            fieldName = field,
                            requestedValue = value,
                            effectiveDate = date,
                            reason = reason,
                            status = targetStatus
                        )
                    )
                }
            ) { Text(if (loading) "Submitting…" else if (isAdmin && directReconciliation) "Reconcile Now" else "Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
