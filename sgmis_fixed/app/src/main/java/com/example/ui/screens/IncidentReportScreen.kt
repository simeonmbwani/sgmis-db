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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.IncidentReport
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import kotlinx.coroutines.delay

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
    var searchQuery by remember { mutableStateOf("") }
    var selectedPriorityFilter by remember { mutableStateOf("ALL") }

    val currentUserRole = uiState.currentUser?.role?.uppercase()
    val isSupervisor = currentUserRole == "SUPERVISOR"
    val isSupervisorOrAdmin = currentUserRole in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")

    LaunchedEffect(Unit) {
        viewModel.fetchIncidents()
    }

    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    val filteredIncidents = remember(uiState.incidents, searchQuery, selectedPriorityFilter) {
        uiState.incidents.filter { inc ->
            val matchesSearch = searchQuery.isBlank() ||
                inc.title.contains(searchQuery, ignoreCase = true) ||
                inc.description.contains(searchQuery, ignoreCase = true) ||
                inc.location.contains(searchQuery, ignoreCase = true) ||
                inc.reportingGuardName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedPriorityFilter) {
                "CRITICAL" -> inc.priority == "CRITICAL"
                "HIGH" -> inc.priority == "HIGH"
                "OPEN" -> inc.status != "RESOLVED"
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = stringResource(R.string.incidents_title),
                subtitle = "Active Post: ${uiState.currentStationName}",
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
                }
            )
        },
        floatingActionButton = {
            if (!isSupervisor) {
                ExtendedFloatingActionButton(
                    onClick = { showReportDialog = true },
                    icon = { Icon(Icons.Default.AddAlert, null) },
                    text = { Text("Report Incident", fontWeight = FontWeight.SemiBold) },
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
                SgmisStatusCard(
                    status = SgmisCardStatus.SUCCESS,
                    title = "Success",
                    message = uiState.successMessage!!,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )
            }

            if (uiState.errorMessage != null) {
                SgmisStatusCard(
                    status = SgmisCardStatus.ERROR,
                    title = "Error",
                    message = uiState.errorMessage!!,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )
            }

            // Search and Filters
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                SgmisSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search incidents by title, details, guard...",
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SgmisFilterChip(
                        selected = selectedPriorityFilter == "ALL",
                        onClick = { selectedPriorityFilter = "ALL" },
                        label = "All (${uiState.incidents.size})"
                    )
                    SgmisFilterChip(
                        selected = selectedPriorityFilter == "OPEN",
                        onClick = { selectedPriorityFilter = "OPEN" },
                        label = "Open (${uiState.incidents.count { it.status != "RESOLVED" }})"
                    )
                    SgmisFilterChip(
                        selected = selectedPriorityFilter == "CRITICAL",
                        onClick = { selectedPriorityFilter = "CRITICAL" },
                        label = "Critical (${uiState.incidents.count { it.priority == "CRITICAL" }})"
                    )
                    SgmisFilterChip(
                        selected = selectedPriorityFilter == "HIGH",
                        onClick = { selectedPriorityFilter = "HIGH" },
                        label = "High (${uiState.incidents.count { it.priority == "HIGH" }})"
                    )
                }
            }

            if (uiState.incidentsLoading) {
                SgmisLoadingSkeleton(modifier = Modifier.padding(Spacing.md))
            } else if (filteredIncidents.isEmpty()) {
                SgmisEmptyState(
                    icon = Icons.Outlined.Warning,
                    title = if (searchQuery.isNotBlank()) "No Matching Incidents" else "No Incidents Reported",
                    description = if (searchQuery.isNotBlank()) "Try another search term." else "All quiet. Logged security incidents will appear here.",
                    actionLabel = if (!isSupervisor && searchQuery.isBlank()) "File First Incident" else null,
                    onAction = { showReportDialog = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
                    contentPadding = PaddingValues(top = Spacing.xs, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(filteredIncidents) { inc ->
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
    val (priorityVariant, statusVariant) = when (incident.priority) {
        "CRITICAL" -> Pair(SgmisBadgeVariant.ERROR, SgmisBadgeVariant.ERROR)
        "HIGH" -> Pair(SgmisBadgeVariant.WARNING, SgmisBadgeVariant.WARNING)
        "MEDIUM" -> Pair(SgmisBadgeVariant.INFO, SgmisBadgeVariant.NEUTRAL)
        else -> Pair(SgmisBadgeVariant.NEUTRAL, SgmisBadgeVariant.NEUTRAL)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("incident_item_${incident.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(CornerRadius.md),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    SgmisBadge(
                        text = incident.priorityDisplay ?: incident.priority,
                        variant = priorityVariant
                    )
                    Text(
                        text = incident.createdAt.take(19).replace('T', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SgmisBadge(
                    text = incident.statusDisplay ?: incident.status,
                    variant = if (incident.status == "RESOLVED") SgmisBadgeVariant.SUCCESS else SgmisBadgeVariant.NEUTRAL
                )
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "Official Amendments (${incident.amendments.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                incident.amendments.forEach { amendment ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(CornerRadius.sm),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
                    Spacer(modifier = Modifier.width(Spacing.xxs))
                    Text("Amend", style = MaterialTheme.typography.labelMedium)
                }

                if (canManage && incident.status != "RESOLVED") {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (incident.status == "REPORTED") {
                            OutlinedButton(
                                onClick = onAcknowledge,
                                modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                            ) {
                                Text("Acknowledge")
                            }
                            Spacer(modifier = Modifier.width(Spacing.xs))
                        }
                        Button(
                            onClick = onResolve,
                            modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                        ) {
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
        title = {
            Text(
                "Report Security Incident",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (errorMessage != null) {
                    SgmisStatusCard(
                        status = SgmisCardStatus.ERROR,
                        title = "Submission Error",
                        message = errorMessage,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("Priority Level:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    priorities.forEach { p ->
                        SgmisFilterChip(
                            selected = priority == p,
                            onClick = { if (!isLoading) priority = p },
                            label = p
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
                    Spacer(modifier = Modifier.width(Spacing.xs))
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
        title = {
            Text(
                "Amend Incident: ${incident.title}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = "Original incident reports are immutable evidence. Amendments are appended to the permanent audit trail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorMessage != null) {
                    SgmisStatusCard(
                        status = SgmisCardStatus.ERROR,
                        title = "Amendment Error",
                        message = errorMessage,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    Spacer(modifier = Modifier.width(Spacing.xs))
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
