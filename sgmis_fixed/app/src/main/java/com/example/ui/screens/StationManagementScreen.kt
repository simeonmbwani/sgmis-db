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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.GuardPair
import com.example.data.model.Station
import com.example.data.model.User
import com.example.ui.theme.StatusSuccess
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationManagementScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateStationDialog by remember { mutableStateOf(false) }
    var showCreatePairDialog by remember { mutableStateOf(false) }

    val role = uiState.currentUser?.role?.uppercase()
    val canManage = role in listOf("ADMINISTRATOR", "ADMIN", "SUPERVISOR")

    LaunchedEffect(Unit) {
        viewModel.fetchStations()
        viewModel.fetchGuardPairs()
        viewModel.fetchUsers()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stations_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchStations() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateStationDialog = true },
                    icon = { Icon(Icons.Default.AddLocation, null) },
                    text = { Text("Add Station") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Success & Error notification banners
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

            if (uiState.stations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No deployment stations registered.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("Active Stations (${uiState.stations.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    items(uiState.stations) { st ->
                        StationCard(st)
                    }
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Guard Pairs (${uiState.guardPairs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (canManage) {
                                TextButton(onClick = { showCreatePairDialog = true }) {
                                    Icon(Icons.Default.GroupAdd, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add Pair")
                                }
                            }
                        }
                    }
                    items(uiState.guardPairs) { p ->
                        val guardAName = p.guardAName ?: uiState.users.find { it.id == p.guardA }?.let { it.fullName ?: it.username } ?: p.guardA
                        val guardBName = p.guardBName ?: uiState.users.find { it.id == p.guardB }?.let { it.fullName ?: it.username } ?: p.guardB
                        val stationName = p.stationName ?: uiState.stations.find { it.id == p.station }?.name ?: p.station

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Pair #${p.rotationOrder ?: p.order}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = stationName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text("Guard A: $guardAName", style = MaterialTheme.typography.bodyMedium)
                                Text("Guard B: $guardBName", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateStationDialog) {
        CreateStationDialog(
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showCreateStationDialog = false
            },
            onSubmit = { name, code, addr, lat, lon, geo ->
                viewModel.createStation(name, code, addr, lat, lon, geo) {
                    showCreateStationDialog = false
                }
            }
        )
    }

    if (showCreatePairDialog) {
        CreateGuardPairDialog(
            stations = uiState.stations,
            users = uiState.users,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showCreatePairDialog = false
            },
            onSubmit = { stationId, guardA, guardB, order ->
                viewModel.createGuardPair(stationId, guardA, guardB, order) {
                    showCreatePairDialog = false
                    viewModel.fetchGuardPairs()
                }
            }
        )
    }
}

@Composable
fun StationCard(station: Station) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(station.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = station.code ?: "STN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (!station.address.isNullOrBlank()) {
                Text("Address: ${station.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Coordinates: ${station.latitude ?: 0.0}, ${station.longitude ?: 0.0} (Radius: ${station.effectiveRadius}m)", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun CreateStationDialog(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, Double, Double, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("0.0") }
    var lon by remember { mutableStateOf("0.0") }
    var radius by remember { mutableStateOf("200") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Register Deployment Station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Station Name *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Station Code (e.g. STN-HQ01) *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Physical Address") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lon,
                        onValueChange = { lon = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = radius,
                    onValueChange = { radius = it },
                    label = { Text("Geofence Radius (meters)") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && code.isNotBlank()) {
                        onSubmit(
                            name.trim(),
                            code.trim(),
                            address.trim(),
                            lat.toDoubleOrNull() ?: 0.0,
                            lon.toDoubleOrNull() ?: 0.0,
                            radius.toDoubleOrNull() ?: 200.0
                        )
                    }
                },
                enabled = !isLoading && name.isNotBlank() && code.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Registering...")
                } else {
                    Text("Register")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

@Composable
fun CreateGuardPairDialog(
    stations: List<Station>,
    users: List<User>,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, Int) -> Unit
) {
    var selectedStationId by remember { mutableStateOf(stations.firstOrNull()?.id ?: "") }
    val guards = remember(users) { users.filter { it.role.equals("GUARD", ignoreCase = true) } }
    var selectedGuardA by remember { mutableStateOf(guards.firstOrNull()?.id ?: "") }
    var selectedGuardB by remember { mutableStateOf(guards.getOrNull(1)?.id ?: "") }
    var rotationOrder by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Create Authoritative Guard Pair") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Text("Station:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                stations.forEach { st ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedStationId == st.id,
                            onClick = { selectedStationId = st.id }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(st.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text("Guard A:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                var expandedA by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { expandedA = true }, modifier = Modifier.fillMaxWidth()) {
                        val gName = guards.find { it.id == selectedGuardA }?.let { it.fullName ?: it.username } ?: "Select Guard A"
                        Text(gName)
                    }
                    DropdownMenu(expanded = expandedA, onDismissRequest = { expandedA = false }) {
                        guards.forEach { g ->
                            DropdownMenuItem(
                                text = { Text(g.fullName ?: g.username) },
                                onClick = {
                                    selectedGuardA = g.id
                                    expandedA = false
                                }
                            )
                        }
                    }
                }

                Text("Guard B:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                var expandedB by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { expandedB = true }, modifier = Modifier.fillMaxWidth()) {
                        val gName = guards.find { it.id == selectedGuardB }?.let { it.fullName ?: it.username } ?: "Select Guard B"
                        Text(gName)
                    }
                    DropdownMenu(expanded = expandedB, onDismissRequest = { expandedB = false }) {
                        guards.filter { it.id != selectedGuardA }.forEach { g ->
                            DropdownMenuItem(
                                text = { Text(g.fullName ?: g.username) },
                                onClick = {
                                    selectedGuardB = g.id
                                    expandedB = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = rotationOrder,
                    onValueChange = { rotationOrder = it },
                    label = { Text("Rotation Order (1, 2, or 3)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val orderInt = rotationOrder.toIntOrNull() ?: 1
                    if (selectedStationId.isNotBlank() && selectedGuardA.isNotBlank() && selectedGuardB.isNotBlank() && selectedGuardA != selectedGuardB) {
                        onSubmit(selectedStationId, selectedGuardA, selectedGuardB, orderInt)
                    }
                },
                enabled = !isLoading && selectedStationId.isNotBlank() && selectedGuardA.isNotBlank() && selectedGuardB.isNotBlank() && selectedGuardA != selectedGuardB
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save Pair")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
