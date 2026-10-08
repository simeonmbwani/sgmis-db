package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AdministrativeHistoryEntry
import com.example.data.model.AppRole
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdministrativeHistoryScreen(viewModel: SgmisViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }

    LaunchedEffect(Unit) {
        if (state.currentUser?.appRole != AppRole.ADMINISTRATOR) onBack()
        else {
            viewModel.clearMessages()
            viewModel.fetchAdministrativeHistory()
        }
    }

    // Auto-dismiss transient messages
    LaunchedEffect(state.successMessage, state.errorMessage) {
        if (state.successMessage != null || state.errorMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearMessages()
        }
    }

    val filteredList = remember(state.administrativeHistory, selectedFilter) {
        if (selectedFilter == "ALL") state.administrativeHistory
        else state.administrativeHistory.filter { it.kind.equals(selectedFilter, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "Administrative Audit History",
                subtitle = "Authoritative System Audit Trail",
                onNavigationClick = onBack,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("admin_history_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NavyDark
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.clearMessages()
                            viewModel.fetchAdministrativeHistory()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("refresh_admin_history_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = NavyDark)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(LightBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SgmisStatusCard(
                statusColor = NavyDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "IMMUTABLE AUDIT TRAIL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NavyDark
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Read-only Authoritative Audit Trail: Real database records from master adjustments, leave ledger balances, shift reassignments, and security audits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryLight
                )
            }

            // Filter Chips
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("All (${state.administrativeHistory.size})", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NavyDark,
                        selectedLabelColor = SurfaceCardLight
                    )
                )
                FilterChip(
                    selected = selectedFilter == "RECORD_ADJUSTMENT",
                    onClick = { selectedFilter = "RECORD_ADJUSTMENT" },
                    label = { Text("Adjustments", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NavyDark,
                        selectedLabelColor = SurfaceCardLight
                    )
                )
                FilterChip(
                    selected = selectedFilter == "LEAVE_ADJUSTMENT",
                    onClick = { selectedFilter = "LEAVE_ADJUSTMENT" },
                    label = { Text("Leave Ledger", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NavyDark,
                        selectedLabelColor = SurfaceCardLight
                    )
                )
                FilterChip(
                    selected = selectedFilter == "SECURITY_AUDIT",
                    onClick = { selectedFilter = "SECURITY_AUDIT" },
                    label = { Text("Security Audits", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NavyDark,
                        selectedLabelColor = SurfaceCardLight
                    )
                )
            }

            if (state.administrativeHistoryLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = NavyDark)
            }

            if (state.errorMessage != null) {
                Surface(
                    color = StatusError.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        state.errorMessage!!,
                        color = StatusError,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (filteredList.isEmpty() && !state.administrativeHistoryLoading) {
                SgmisEmptyState(
                    title = "No Audit Records",
                    description = "No administrative history records found for the selected category.",
                    icon = Icons.Default.HistoryEdu,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(filteredList) { row ->
                        AdminHistoryCard(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminHistoryCard(row: AdministrativeHistoryEntry) {
    val badgeVariant = when (row.kind.uppercase()) {
        "RECORD_ADJUSTMENT" -> BadgeVariant.Info
        "LEAVE_ADJUSTMENT" -> BadgeVariant.Success
        "SECURITY_AUDIT" -> BadgeVariant.Warning
        "SUPERVISOR_OVERRIDE" -> BadgeVariant.Danger
        else -> BadgeVariant.Neutral
    }

    SgmisCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: Action + Kind Badge
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.action.takeIf { !it.isNullOrBlank() } ?: row.kind,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryLight
                )
                SgmisBadge(
                    text = row.kind,
                    variant = badgeVariant
                )
            }

            HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)

            // Date & Administrator
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = row.timestamp?.take(19)?.replace("T", " ") ?: "Unknown date",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondaryLight
                )
                Text(
                    text = "Admin: ${row.actor ?: "System"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimaryLight
                )
            }

            // Affected Employee & Station
            val employee = row.details?.get("employee")?.toString() ?: "${row.targetModel.orEmpty()} ${row.targetId.orEmpty()}"
            val station = row.details?.get("station")?.toString()?.takeIf { it.isNotBlank() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Affected Record:", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                Text(
                    text = if (station != null) "$employee ($station)" else employee,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimaryLight
                )
            }

            // Value Change
            if (row.oldValue != null || row.newValue != null) {
                val field = row.details?.get("field")?.toString() ?: "Value"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$field Change:", style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
                    Text(
                        text = "${row.oldValue ?: "(empty)"} → ${row.newValue ?: "(empty)"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = NavyDark
                    )
                }
            }

            // Reason / Justification
            if (!row.reason.isNullOrBlank()) {
                Text(
                    text = "Reason: ${row.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimaryLight
                )
            }

            // Record ID
            Text(
                text = "Ref: ${row.id}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TextSecondaryLight
            )
        }
    }
}
