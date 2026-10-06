package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AdministrativeHistoryEntry
import com.example.data.model.AppRole
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Administrative Audit History", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = {
                    viewModel.clearMessages()
                    viewModel.fetchAdministrativeHistory()
                }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        )
    }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "Read-only Authoritative Audit Trail: Real database records from master adjustments, leave ledger balances, shift reassignments, and security audits.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
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
                    label = { Text("All (${state.administrativeHistory.size})") }
                )
                FilterChip(
                    selected = selectedFilter == "RECORD_ADJUSTMENT",
                    onClick = { selectedFilter = "RECORD_ADJUSTMENT" },
                    label = { Text("Adjustments") }
                )
                FilterChip(
                    selected = selectedFilter == "LEAVE_ADJUSTMENT",
                    onClick = { selectedFilter = "LEAVE_ADJUSTMENT" },
                    label = { Text("Leave Ledger") }
                )
                FilterChip(
                    selected = selectedFilter == "SECURITY_AUDIT",
                    onClick = { selectedFilter = "SECURITY_AUDIT" },
                    label = { Text("Security Audits") }
                )
            }

            if (state.administrativeHistoryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        state.errorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (filteredList.isEmpty() && !state.administrativeHistoryLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No administrative history records found.", color = MaterialTheme.colorScheme.outline)
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredList) { row ->
                    AdminHistoryCard(row)
                }
            }
        }
    }
}

@Composable
private fun AdminHistoryCard(row: AdministrativeHistoryEntry) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Header: Action + Kind Badge
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.action.takeIf { !it.isNullOrBlank() } ?: row.kind,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (row.kind) {
                        "RECORD_ADJUSTMENT" -> MaterialTheme.colorScheme.primaryContainer
                        "LEAVE_ADJUSTMENT" -> MaterialTheme.colorScheme.secondaryContainer
                        "SECURITY_AUDIT" -> MaterialTheme.colorScheme.tertiaryContainer
                        "SUPERVISOR_OVERRIDE" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = row.kind,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider()

            // Date & Administrator
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = row.timestamp?.take(19)?.replace("T", " ") ?: "Unknown date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Admin: ${row.actor ?: "System"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Affected Employee & Station
            val employee = row.details?.get("employee")?.toString() ?: "${row.targetModel.orEmpty()} ${row.targetId.orEmpty()}"
            val station = row.details?.get("station")?.toString()?.takeIf { it.isNotBlank() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Affected Record:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = if (station != null) "$employee ($station)" else employee,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Value Change
            if (row.oldValue != null || row.newValue != null) {
                val field = row.details?.get("field")?.toString() ?: "Value"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$field Change:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = "${row.oldValue ?: "(empty)"} → ${row.newValue ?: "(empty)"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Reason / Justification
            if (!row.reason.isNullOrBlank()) {
                Text(
                    text = "Reason: ${row.reason}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Record ID
            Text(
                text = "Ref: ${row.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
