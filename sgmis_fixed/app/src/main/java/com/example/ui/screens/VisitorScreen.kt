package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.OccurrenceBookEntry
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitorScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLogDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }

    val currentUserRole = uiState.currentUser?.role?.uppercase()
    val isSupervisor = currentUserRole == "SUPERVISOR"
    val canLogVisitor = !isSupervisor && (!uiState.isGuard || uiState.isOnDuty)

    LaunchedEffect(Unit) {
        viewModel.fetchVisitors()
    }

    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    val filteredVisitors = remember(uiState.visitors, searchQuery, selectedFilter) {
        uiState.visitors.filter { entry ->
            val matchesSearch = searchQuery.isBlank() ||
                entry.occurrenceText.contains(searchQuery, ignoreCase = true) ||
                entry.entryNumber.contains(searchQuery, ignoreCase = true) ||
                entry.guardName.contains(searchQuery, ignoreCase = true)

            val isActive = entry.occurrenceText.contains("ACTIVE ON SITE", ignoreCase = true) ||
                !entry.occurrenceText.contains("Time Out:", ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "ACTIVE" -> isActive
                "DEPARTED" -> !isActive
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    val activeCount = remember(uiState.visitors) {
        uiState.visitors.count {
            it.occurrenceText.contains("ACTIVE ON SITE", ignoreCase = true) ||
            !it.occurrenceText.contains("Time Out:", ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "Gate Visitor Book",
                subtitle = "Official Register • ${uiState.currentStationName}",
                onNavigationClick = onBack,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("visitor_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = NavyDark
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchVisitors() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("refresh_visitors_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh Visitors", tint = NavyDark)
                    }
                }
            )
        },
        floatingActionButton = {
            if (canLogVisitor) {
                ExtendedFloatingActionButton(
                    onClick = { showLogDialog = true },
                    icon = { Icon(Icons.Default.PersonAdd, null) },
                    text = { Text("Log Visitor", fontWeight = FontWeight.SemiBold) },
                    containerColor = NavyDark,
                    contentColor = SurfaceCardLight,
                    modifier = Modifier.testTag("log_visitor_fab")
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(LightBackground)
        ) {
            // Off-duty guard notice
            if (uiState.isGuard && !uiState.isOnDuty) {
                SgmisStatusCard(
                    statusColor = StatusWarning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("DUTY RESTRICTED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusWarning)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Viewing mode: You must be CLOCKED IN (On Duty) to register visitors or issue gate passes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryLight
                    )
                }
            }

            // Notification banners
            if (uiState.successMessage != null) {
                SgmisStatusCard(
                    statusColor = StatusSuccess,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("SUCCESS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusSuccess)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(uiState.successMessage!!, style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                }
            }

            if (uiState.errorMessage != null) {
                SgmisStatusCard(
                    statusColor = StatusError,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("ERROR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusError)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(uiState.errorMessage!!, style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                }
            }

            if (isSupervisor) {
                SgmisStatusCard(
                    statusColor = NavyDark,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("SUPERVISOR VIEW-ONLY MODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NavyDark)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Gate visitor records are entered by operational security guards on post.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryLight
                    )
                }
            }

            // Search and quick stats
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SgmisSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search visitor name, ID, vehicle...",
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text("All (${uiState.visitors.size})", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NavyDark,
                            selectedLabelColor = SurfaceCardLight
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == "ACTIVE",
                        onClick = { selectedFilter = "ACTIVE" },
                        label = { Text("On Site ($activeCount)", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NavyDark,
                            selectedLabelColor = SurfaceCardLight
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == "DEPARTED",
                        onClick = { selectedFilter = "DEPARTED" },
                        label = { Text("Departed (${uiState.visitors.size - activeCount})", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NavyDark,
                            selectedLabelColor = SurfaceCardLight
                        )
                    )
                }
            }

            if (uiState.visitorsLoading) {
                SgmisLoadingSkeleton(modifier = Modifier.padding(16.dp))
            } else if (filteredVisitors.isEmpty()) {
                SgmisEmptyState(
                    icon = Icons.Outlined.Badge,
                    title = if (searchQuery.isNotBlank()) "No Matching Visitors" else "No Visitor Records Found",
                    description = if (searchQuery.isNotBlank()) "Try refining your search keyword." else "Authorized visitors logged at the gate will appear here.",
                    actionText = if (canLogVisitor && searchQuery.isBlank()) "Log First Visitor" else null,
                    onActionClick = { showLogDialog = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredVisitors) { entry ->
                        VisitorEntryCard(entry)
                    }
                }
            }
        }
    }

    if (showLogDialog) {
        LogVisitorDialog(
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showLogDialog = false
            },
            onSubmit = { name, idNum, person, purpose, inTime, outTime, vehicleReg ->
                viewModel.logVisitor(name, idNum, person, purpose, inTime, outTime, vehicleReg) {
                    showLogDialog = false
                }
            }
        )
    }
}

@Composable
fun VisitorEntryCard(entry: OccurrenceBookEntry) {
    val isDeparturePending = entry.occurrenceText.contains("ACTIVE ON SITE", ignoreCase = true) ||
        !entry.occurrenceText.contains("Time Out:", ignoreCase = true)

    SgmisCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("visitor_entry_${entry.entryNumber}")
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.entryNumber,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = NavyDark
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isDeparturePending) {
                        SgmisBadge(
                            text = "ACTIVE ON PREMISES",
                            variant = BadgeVariant.Warning
                        )
                    } else {
                        SgmisBadge(
                            text = "DEPARTED",
                            variant = BadgeVariant.Success
                        )
                    }
                    SgmisBadge(
                        text = "VISITOR PASS",
                        variant = BadgeVariant.Neutral
                    )
                }
            }

            Text(
                text = entry.occurrenceText,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimaryLight
            )

            HorizontalDivider(
                color = BorderSubtleLight,
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Post: ${entry.stationName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryLight
                )
                Text(
                    text = "Logging Guard: ${entry.guardName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryLight
                )
            }
        }
    }
}

@Composable
fun LogVisitorDialog(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String?, String, String, String, String?, String?) -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val currentTime = remember { timeFormat.format(Date()) }

    var visitorName by remember { mutableStateOf("") }
    var idNumber by remember { mutableStateOf("") }
    var vehicleRegNumber by remember { mutableStateOf("") }
    var personToVisit by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var timeIn by remember { mutableStateOf(currentTime) }
    var timeOut by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Text(
                "Log Official Visitor",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        color = StatusError.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = StatusError,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = visitorName,
                    onValueChange = { visitorName = it },
                    label = { Text("Full Name of Visitor *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("visitor_name_input")
                )

                OutlinedTextField(
                    value = idNumber,
                    onValueChange = { idNumber = it },
                    label = { Text("National ID / Passport No.") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = vehicleRegNumber,
                    onValueChange = { vehicleRegNumber = it },
                    label = { Text("Vehicle Registration No. (Optional)") },
                    placeholder = { Text("e.g. ABC-1234") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("visitor_vehicle_reg_input")
                )

                OutlinedTextField(
                    value = personToVisit,
                    onValueChange = { personToVisit = it },
                    label = { Text("Person / Department Visited *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it },
                    label = { Text("Purpose of Visit *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeIn,
                        onValueChange = { timeIn = it },
                        label = { Text("Time In *") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = timeOut,
                        onValueChange = { timeOut = it },
                        label = { Text("Time Out") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (visitorName.isNotBlank() && personToVisit.isNotBlank() && purpose.isNotBlank()) {
                        onSubmit(
                            visitorName.trim(),
                            idNumber.trim().ifEmpty { null },
                            personToVisit.trim(),
                            purpose.trim(),
                            timeIn.trim(),
                            timeOut.trim().ifEmpty { null },
                            vehicleRegNumber.trim().ifEmpty { null }
                        )
                    }
                },
                enabled = !isLoading && visitorName.isNotBlank() && personToVisit.isNotBlank() && purpose.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NavyDark),
                modifier = Modifier.testTag("submit_visitor_button").heightIn(min = 48.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = SurfaceCardLight
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Registering...")
                } else {
                    Text("Register Visitor")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel", color = TextSecondaryLight) }
        }
    )
}
