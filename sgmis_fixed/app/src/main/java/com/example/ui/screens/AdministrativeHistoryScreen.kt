package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AppRole
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdministrativeHistoryScreen(viewModel: SgmisViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) {
        if (state.currentUser?.appRole != AppRole.ADMINISTRATOR) onBack()
        else viewModel.fetchAdministrativeHistory()
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Administrative Audit History", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { viewModel.fetchAdministrativeHistory() }) { Icon(Icons.Default.Refresh, "Refresh") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("History is read-only and uses the adjustment, leave ledger, security audit, and supervisor override records already stored by the backend.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            if (state.administrativeHistoryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.errorMessage != null) Text(state.errorMessage!!, color = MaterialTheme.colorScheme.error)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.administrativeHistory) { row ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${row.kind} · ${row.action.orEmpty()}", fontWeight = FontWeight.Bold)
                            Text("${row.timestamp.orEmpty()} · ${row.actor ?: "Unknown actor"}", style = MaterialTheme.typography.bodySmall)
                            Text("${row.targetModel.orEmpty()} ${row.targetId.orEmpty()}")
                            row.details?.get("employee")?.let { Text("Employee: $it") }
                            row.oldValue?.let { Text("Old: $it") }
                            row.newValue?.let { Text("New: $it") }
                            row.reason?.takeIf { it.isNotBlank() }?.let { Text("Reason: $it") }
                            Text("Reference: ${row.id}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (state.administrativeHistory.isEmpty() && !state.administrativeHistoryLoading) item { Text("No administrative history found.") }
            }
        }
    }
}
