package com.example.ui.screens

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
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EscortDutiesScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateEscortDialog by remember { mutableStateOf(false) }

    val isSupervisorOrAdmin = uiState.currentUser?.role?.uppercase() in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")

    LaunchedEffect(Unit) {
        viewModel.fetchEscortDuties()
        if (isSupervisorOrAdmin) {
            viewModel.fetchUsers()
            viewModel.fetchStations()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Escort Duties", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchEscortDuties() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (isSupervisorOrAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateEscortDialog = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Assign Escort") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.escortsLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.escortDuties.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No escort duties assigned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.escortDuties) { duty ->
                        EscortCard(
                            duty = duty,
                            onUpdateStatus = { st -> viewModel.updateEscortStatus(duty.id, st) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateEscortDialog) {
        CreateEscortDialog(
            guards = uiState.users.filter { it.role == "GUARD" }.ifEmpty { listOfNotNull(uiState.currentUser) },
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showCreateEscortDialog = false
            },
            onSubmit = { req ->
                viewModel.createEscortDuty(req) {
                    showCreateEscortDialog = false
                }
            }
        )
    }
}
