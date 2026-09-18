package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.IncidentReport
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentReportScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showReportDialog by remember { mutableStateOf(false) }
    var amendingIncident by remember { mutableStateOf<IncidentReport?>(null) }

    val currentUserRole = uiState.currentUser?.role
    val isSupervisor = currentUserRole == "SUPERVISOR"

    LaunchedEffect(Unit) {
        viewModel.fetchIncidents()
    }

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.incidents_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("incident_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchIncidents() },
                        modifier = Modifier.testTag("refresh_incidents_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh Incidents")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (!isSupervisor) {
                ExtendedFloatingActionButton(
                    onClick = { showReportDialog = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Report Incident") },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.testTag("report_incident_fab")
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Notification banners
            if (uiState.successMessage != null) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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

            if (uiState.incidentsLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.incidents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No incidents reported", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isSupervisorOrAdmin = currentUserRole?.uppercase() in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")
                    items(uiState.incidents) { inc ->
                        IncidentItemCard(
                            incident = inc,
                            canManage = isSupervisorOrAdmin,
                            onAcknowledge = { viewModel.acknowledgeIncident(inc.id) },
                            onResolve = { viewModel.resolveIncident(inc.id, "Resolved by Supervisor") },
                            onAmend = { amendingIncident = inc }
                        )
                    }
                }
            }
        }
    }

    if (showReportDialog) {
        CreateIncidentDialog(
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showReportDialog = false
            },
            onSubmit = { priority, title, desc, loc ->
                viewModel.submitIncident(priority, title, desc, loc) {
                    showReportDialog = false
                }
            }
        )
    }

    if (amendingIncident != null) {
        AmendIncidentDialog(
            incident = amendingIncident!!,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                amendingIncident = null
            },
            onSubmit = { reason, amendedDescription ->
                viewModel.amendIncident(amendingIncident!!.id, reason, amendedDescription) {
                    amendingIncident = null
                }
            }
        )
    }
}

@Composable
fun IncidentItemCard(
    incident: IncidentReport,
    canManage: Boolean = false,
    onAcknowledge: () -> Unit = {},
    onResolve: () -> Unit = {},
    onAmend: () -> Unit = {}
) {
    val (priorityBg, priorityFg) = when (incident.priority) {
        "CRITICAL" -> Pair(StatusError.copy(alpha = 0.2f), StatusError)
        "HIGH" -> Pair(StatusWarning.copy(alpha = 0.2f), StatusWarning)
        else -> Pair(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("incident_item_${incident.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = priorityBg, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = incident.priorityDisplay ?: incident.priority,
                            color = priorityFg,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = incident.createdAt.take(19).replace('T', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = if (incident.status == "RESOLVED") StatusSuccess.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = incident.statusDisplay ?: incident.status,
                        color = if (incident.status == "RESOLVED") StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = incident.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = incident.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (incident.amendments.isNotEmpty()) {
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Text(
                    text = "Official Amendments (${incident.amendments.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                incident.amendments.forEach { amendment ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Amendment by ${amendment.amendedByName ?: "Authorized Personnel"} (${amendment.createdAt.take(19).replace('T', ' ')})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Reason: ${amendment.reason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = amendment.amendedDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Location: ${incident.location}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Reported by: ${incident.reportingGuardName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onAmend,
                    modifier = Modifier.testTag("amend_incident_${incident.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Amend", style = MaterialTheme.typography.labelMedium)
                }

                if (canManage && incident.status != "RESOLVED") {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (incident.status == "REPORTED") {
                            OutlinedButton(onClick = onAcknowledge) {
                                Text("Acknowledge")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Button(onClick = onResolve) {
                            Text("Resolve")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateIncidentDialog(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String) -> Unit
) {
    var priority by remember { mutableStateOf("MEDIUM") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }

    val priorities = listOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
    val isValid = title.isNotBlank() && description.isNotBlank() && location.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Report Security Incident") },
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

                Text("Priority Level:", style = MaterialTheme.typography.labelSmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    priorities.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { if (!isLoading) priority = p },
                            label = { Text(p, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Incident Title *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("incident_title_input")
                )

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Exact Location / Post Zone *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("incident_location_input")
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Detailed Description *") },
                    minLines = 3,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("incident_desc_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid && !isLoading) {
                        onSubmit(priority, title, description, location)
                    }
                },
                enabled = isValid && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("submit_incident_confirm_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Filing Report...")
                } else {
                    Text("File Report")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

@Composable
fun AmendIncidentDialog(
    incident: IncidentReport,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    var amendedDescription by remember { mutableStateOf(incident.description) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Amend Incident: ${incident.title}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Original incident reports are immutable evidence. Amendments are appended to the permanent audit trail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Mandatory Justification / Reason *") },
                    placeholder = { Text("e.g. Additional details from witness or CCTV") },
                    singleLine = false,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("amend_incident_reason_input")
                )
                OutlinedTextField(
                    value = amendedDescription,
                    onValueChange = { amendedDescription = it },
                    label = { Text("Amended Incident Description *") },
                    minLines = 3,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("amend_incident_description_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (reason.isNotBlank() && amendedDescription.isNotBlank() && !isLoading) {
                        onSubmit(reason.trim(), amendedDescription.trim())
                    }
                },
                enabled = reason.isNotBlank() && amendedDescription.isNotBlank() && !isLoading,
                modifier = Modifier.testTag("submit_amend_incident_button")
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
                    Text("Append Amendment")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

