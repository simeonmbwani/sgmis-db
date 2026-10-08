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
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import kotlinx.coroutines.delay

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
    var selectedStationForEdit by remember { mutableStateOf<Station?>(null) }
    var selectedPairForEdit by remember { mutableStateOf<GuardPair?>(null) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Stations, 1: Guard Pairs

    val role = uiState.currentUser?.role?.uppercase()
    val canManage = role in listOf("ADMINISTRATOR", "ADMIN")

    LaunchedEffect(Unit) {
        viewModel.fetchStations()
        viewModel.fetchGuardPairs()
        viewModel.fetchUsers()
    }

    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = stringResource(R.string.stations_title),
                subtitle = "Security Deployments & Rotation Pairs",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchStations(); viewModel.fetchGuardPairs() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (selectedTab == 0) showCreateStationDialog = true else showCreatePairDialog = true
                    },
                    icon = { Icon(if (selectedTab == 0) Icons.Default.AddLocation else Icons.Default.GroupAdd, null) },
                    text = { Text(if (selectedTab == 0) "Add Station" else "Add Guard Pair", fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Success & Error notification banners
            if (uiState.successMessage != null) {
                SgmisStatusCard(
                    status = SgmisCardStatus.SUCCESS,
                    title = "Success",
                    message = uiState.successMessage!!,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (uiState.errorMessage != null) {
                SgmisStatusCard(
                    status = SgmisCardStatus.ERROR,
                    title = "Error",
                    message = uiState.errorMessage!!,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(16.dp))
                            Text("Stations (${uiState.stations.size})", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Icon(Icons.Default.Groups, null, modifier = Modifier.size(16.dp))
                            Text("Guard Pairs (${uiState.guardPairs.size})", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
            }

            if (selectedTab == 0) {
                if (uiState.stations.isEmpty()) {
                    SgmisEmptyState(
                        icon = Icons.Outlined.LocationOff,
                        title = "No Stations Registered",
                        description = "No deployment stations registered in the master database.",
                        actionLabel = if (canManage) "Add Station" else null,
                        onAction = { showCreateStationDialog = true },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(uiState.stations) { st ->
                            StationCard(st, if (canManage) ({ selectedStationForEdit = st }) else null)
                        }
                    }
                }
            } else {
                if (uiState.guardPairs.isEmpty()) {
                    SgmisEmptyState(
                        icon = Icons.Outlined.GroupOff,
                        title = "No Guard Pairs Registered",
                        description = "Create paired rosters for 2-officer shift coverage.",
                        actionLabel = if (canManage) "Add Guard Pair" else null,
                        onAction = { showCreatePairDialog = true },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(uiState.guardPairs) { p ->
                            val guardAName = p.guardAName ?: uiState.users.find { it.id == p.guardA }?.let { it.fullName ?: it.username } ?: p.guardA
                            val guardBName = p.guardBName ?: uiState.users.find { it.id == p.guardB }?.let { it.fullName ?: it.username } ?: p.guardB
                            val stationName = p.stationName ?: uiState.stations.find { it.id == p.station }?.name ?: p.station

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(CornerRadius.md),
                                elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card)
                            ) {
                                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Pair #${p.rotationOrder ?: p.order}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                            SgmisBadge(
                                                text = if (p.isActive) "ACTIVE" else "INACTIVE",
                                                variant = if (p.isActive) SgmisBadgeVariant.SUCCESS else SgmisBadgeVariant.ERROR
                                            )
                                            SgmisBadge(
                                                text = stationName,
                                                variant = SgmisBadgeVariant.NEUTRAL
                                            )
                                        }
                                    }
                                    Text("Guard A: $guardAName", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("Guard B: $guardBName", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("Rotation Order: ${p.rotationOrder ?: p.order}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (canManage) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = { selectedPairForEdit = p }) { Text("Edit Pair") }
                                        }
                                    }
                                }
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

    selectedStationForEdit?.let { station ->
        EditStationDialog(station = station, onDismiss = { selectedStationForEdit = null }, onSave = { request ->
            viewModel.updateStation(station.id, request) { selectedStationForEdit = null }
        })
    }
    selectedPairForEdit?.let { pair ->
        EditGuardPairDialog(pair, uiState.stations, uiState.users, onDismiss = { selectedPairForEdit = null }, onSave = { request ->
            viewModel.updateGuardPair(pair.id, request) { selectedPairForEdit = null }
        })
    }
}

@Composable
fun StationCard(station: Station, onEdit: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(CornerRadius.md),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card)
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(station.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SgmisBadge(
                        text = if (station.isActive) "ACTIVE" else "INACTIVE",
                        variant = if (station.isActive) SgmisBadgeVariant.SUCCESS else SgmisBadgeVariant.ERROR
                    )
                    SgmisBadge(
                        text = station.code ?: "STN",
                        variant = SgmisBadgeVariant.INFO
                    )
                }
            }
            if (!station.address.isNullOrBlank()) {
                Text("Address: ${station.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Coordinates: ${station.latitude ?: 0.0}, ${station.longitude ?: 0.0} (Radius: ${station.effectiveRadius}m)", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("Assigned personnel: ${station.guardsCount} · Pairs: ${station.pairsCount}", style = MaterialTheme.typography.bodySmall)

            if (onEdit != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onEdit) { Text("Edit Station Settings") }
                }
            }
        }
    }
}

@Composable
private fun EditStationDialog(station: Station, onDismiss: () -> Unit, onSave: (com.example.data.model.UpdateStationRequest) -> Unit) {
    var confirmSave by remember(station.id) { mutableStateOf(false) }
    var name by remember(station.id) { mutableStateOf(station.name) }
    var code by remember(station.id) { mutableStateOf(station.code.orEmpty()) }
    var address by remember(station.id) { mutableStateOf(station.address.orEmpty()) }
    var radius by remember(station.id) { mutableStateOf(station.effectiveRadius.toString()) }
    var active by remember(station.id) { mutableStateOf(station.isActive) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Station", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Code") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(radius, { radius = it }, label = { Text("Geofence radius (m)") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(active, { active = it })
                    Spacer(modifier = Modifier.width(Spacing.xxs))
                    Text("Active Station")
                }
            }
        },
        confirmButton = { Button(onClick = { confirmSave = true }) { Text("Review Changes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text("Save these changes?", fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Station: ${station.name} → $name")
            Text("Code: ${station.code.orEmpty()} → $code")
            Text("Address: ${station.address.orEmpty()} → $address")
            Text("Geofence radius: ${station.effectiveRadius} m → ${radius} m")
            Text("Status: ${if (station.isActive) "Active" else "Inactive"} → ${if (active) "Active" else "Inactive"}")
        } },
        confirmButton = { Button(onClick = { confirmSave = false; onSave(com.example.data.model.UpdateStationRequest(name, code, address, station.latitude, station.longitude, radius.toDoubleOrNull(), active)) }) { Text("Confirm Save") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Back") } }
    )
}

@Composable
private fun EditGuardPairDialog(pair: GuardPair, stations: List<Station>, users: List<User>, onDismiss: () -> Unit, onSave: (com.example.data.model.UpdateGuardPairRequest) -> Unit) {
    var confirmSave by remember(pair.id) { mutableStateOf(false) }
    var station by remember(pair.id) { mutableStateOf(pair.station) }
    var guardA by remember(pair.id) { mutableStateOf(pair.guardA) }
    var guardB by remember(pair.id) { mutableStateOf(pair.guardB) }
    var order by remember(pair.id) { mutableStateOf((pair.rotationOrder ?: pair.order).toString()) }
    var active by remember(pair.id) { mutableStateOf(pair.isActive) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Guard Pair", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("Station", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                stations.forEach { item -> TextButton(onClick = { station = item.id }) { Text("${if (station == item.id) "✓ " else ""}${item.name}") } }
                Text("Guard A", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                users.filter { it.role.equals("GUARD", true) }.forEach { item -> TextButton(onClick = { guardA = item.id }) { Text("${if (guardA == item.id) "✓ " else ""}${item.fullName ?: item.username}") } }
                Text("Guard B", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                users.filter { it.role.equals("GUARD", true) }.forEach { item -> TextButton(onClick = { guardB = item.id }) { Text("${if (guardB == item.id) "✓ " else ""}${item.fullName ?: item.username}") } }
                OutlinedTextField(order, { order = it }, label = { Text("Rotation order (1–3)") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(active, { active = it })
                    Spacer(modifier = Modifier.width(Spacing.xxs))
                    Text("Active pair")
                }
            }
        },
        confirmButton = { Button(onClick = { confirmSave = true }) { Text("Review Changes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text("Save these changes?", fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Station: ${pair.stationName.orEmpty()} → ${stations.firstOrNull { it.id == station }?.name ?: station}")
            Text("Guard A: ${pair.guardAName ?: pair.guardA} → ${users.firstOrNull { it.id == guardA }?.fullName ?: guardA}")
            Text("Guard B: ${pair.guardBName ?: pair.guardB} → ${users.firstOrNull { it.id == guardB }?.fullName ?: guardB}")
            Text("Rotation order: ${pair.rotationOrder ?: pair.order} → ${order}")
            Text("Status: ${if (pair.isActive) "Active" else "Inactive"} → ${if (active) "Active" else "Inactive"}")
        } },
        confirmButton = { Button(onClick = { confirmSave = false; onSave(com.example.data.model.UpdateGuardPairRequest(station, guardA, guardB, order.toIntOrNull(), active)) }) { Text("Confirm Save") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Back") } }
    )
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
        title = { Text("Register Deployment Station", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (errorMessage != null) {
                    SgmisStatusCard(
                        status = SgmisCardStatus.ERROR,
                        title = "Validation Error",
                        message = errorMessage,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
                    Spacer(modifier = Modifier.width(Spacing.xs))
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
        title = { Text("Create Authoritative Guard Pair", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (errorMessage != null) {
                    SgmisStatusCard(
                        status = SgmisCardStatus.ERROR,
                        title = "Validation Error",
                        message = errorMessage,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                        Spacer(modifier = Modifier.width(Spacing.xxs))
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
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
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
