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
        if (canRequest) ExtendedFloatingActionButton(onClick = { showCreate = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("${if (isAdmin) "New Reconciliation" else "Request Adjustment"}") })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Supervisor requests require administrator review. Approved values are applied by the existing backend workflow and recorded with the request.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            if (state.adjustmentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.errorMessage != null) Text(state.errorMessage!!, color = MaterialTheme.colorScheme.error)
            if (state.recordAdjustments.isEmpty() && !state.adjustmentsLoading) Text("No adjustment requests found.", modifier = Modifier.padding(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.recordAdjustments) { row ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { selected = row }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${row.guardName ?: row.guard} · ${row.fieldName}", fontWeight = FontWeight.Bold)
                            Text("${row.oldValue.orEmpty()}  →  ${row.approvedValue?.takeIf { it.isNotBlank() } ?: row.requestedValue}")
                            Text("Requested by ${row.requestedByName ?: "Unknown"} · ${row.createdAt ?: ""}", style = MaterialTheme.typography.bodySmall)
                            Text("${row.statusDisplay ?: row.status}${row.reviewedByName?.let { " · reviewed by $it" } ?: ""}${row.reviewedAt?.let { " · $it" } ?: ""}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            if (isAdmin && row.status == "PENDING") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { approvedValue = row.requestedValue; selected = row }) { Text("Review / Approve") }
                                OutlinedButton(onClick = { rejectTarget = row }) { Text("Reject") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) AdjustmentRequestDialog(
        users = state.users.filter { it.role.uppercase() == "GUARD" },
        loading = state.adjustmentsLoading,
        onDismiss = { showCreate = false },
        onSubmit = { request -> viewModel.submitRecordAdjustment(request) { showCreate = false } }
    )

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Adjustment ${row.id.take(8)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Employee: ${row.guardName ?: row.guard} (${row.guardEmployeeId ?: "no employee number"})")
                    Text("Field: ${row.fieldName} · effective ${row.effectiveDate}")
                    Text("Current: ${row.oldValue.orEmpty()}")
                    Text("Requested: ${row.requestedValue}")
                    Text("Reason: ${row.reason}")
                    Text("Requestor: ${row.requestedByName ?: "Unknown"} · ${row.createdAt.orEmpty()}")
                    if (row.status == "PENDING" && isAdmin) OutlinedTextField(approvedValue, { approvedValue = it }, label = { Text("Approved value (defaults to requested)") })
                    if (row.reviewedByName != null) Text("Reviewed by ${row.reviewedByName} · ${row.reviewedAt.orEmpty()}")
                    if (!row.rejectionReason.isNullOrBlank()) Text("Rejection reason: ${row.rejectionReason}")
                }
            },
            confirmButton = {
                if (isAdmin && row.status == "PENDING") TextButton(onClick = { confirmApproval = true }) { Text("Review approval") }
                else TextButton(onClick = { selected = null }) { Text("Close") }
            },
            dismissButton = { if (isAdmin && row.status == "PENDING") TextButton(onClick = { selected = null }) { Text("Cancel") } }
        )
    }

    if (confirmApproval && selected != null) {
        val row = selected!!
        AlertDialog(
            onDismissRequest = { confirmApproval = false },
            title = { Text("Approve this adjustment?") },
            text = { Text("${row.guardName ?: row.guard} · ${row.fieldName}\nCurrent value: ${row.oldValue.orEmpty()}\nNew value: ${approvedValue.ifBlank { row.requestedValue }}\nEffective: ${row.effectiveDate}") },
            confirmButton = { TextButton(onClick = { confirmApproval = false; viewModel.reviewRecordAdjustment(row.id, true, approvedValue.ifBlank { row.requestedValue }); selected = null }) { Text("Confirm approval") } },
            dismissButton = { TextButton(onClick = { confirmApproval = false }) { Text("Back") } }
        )
    }

    rejectTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { rejectTarget = null; rejectionReason = "" },
            title = { Text("Reject adjustment") },
            text = { OutlinedTextField(rejectionReason, { rejectionReason = it }, label = { Text("Reason (required)") }) },
            confirmButton = { TextButton(enabled = rejectionReason.isNotBlank(), onClick = { confirmRejection = true }) { Text("Review rejection") } },
            dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("Cancel") } }
        )
    }
    if (confirmRejection && rejectTarget != null) {
        val row = rejectTarget!!
        AlertDialog(
            onDismissRequest = { confirmRejection = false },
            title = { Text("Confirm rejection") },
            text = { Text("${row.guardName ?: row.guard} · ${row.fieldName}\nCurrent value: ${row.oldValue.orEmpty()}\nRequested value: ${row.requestedValue}\nReason: $rejectionReason") },
            confirmButton = { TextButton(onClick = { confirmRejection = false; viewModel.reviewRecordAdjustment(row.id, false, rejectionReason); rejectTarget = null; rejectionReason = "" }) { Text("Confirm rejection") } },
            dismissButton = { TextButton(onClick = { confirmRejection = false }) { Text("Back") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentRequestDialog(
    users: List<com.example.data.model.User>, loading: Boolean,
    onDismiss: () -> Unit, onSubmit: (CreateRecordAdjustmentRequest) -> Unit
) {
    var guard by remember { mutableStateOf<com.example.data.model.User?>(null) }
    var field by remember { mutableStateOf("employee_number") }
    var value by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var reason by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val fields = listOf("employee_number", "first_name", "last_name", "station", "shift_type", "pair_guard", "vacation_balance", "casual_balance", "compensation_days")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Submit record adjustment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Affected guard")
                users.forEach { user ->
                    if (guard == null || guard?.id == user.id) Text("${user.fullName ?: user.username} · ${user.employeeNumber.orEmpty()}", Modifier.clickable { guard = user })
                }
                if (guard != null) Text("Selected: ${guard?.fullName ?: guard?.username}")
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(field, {}, readOnly = true, label = { Text("Field") }, modifier = Modifier.menuAnchor())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        fields.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { field = item; expanded = false }) }
                    }
                }
                OutlinedTextField(value, { value = it }, label = { Text("Requested value") })
                OutlinedTextField(date, { date = it }, label = { Text("Effective date (YYYY-MM-DD)") })
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason / source citation") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && guard != null && value.isNotBlank() && reason.isNotBlank(), onClick = {
                val selected = guard ?: return@TextButton
                onSubmit(CreateRecordAdjustmentRequest(guard = selected.id, fieldName = field, requestedValue = value, effectiveDate = date, reason = reason))
            }) { Text(if (loading) "Submitting…" else "Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
