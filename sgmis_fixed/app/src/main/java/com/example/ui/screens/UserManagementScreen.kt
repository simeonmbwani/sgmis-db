package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.CreateUserRequest
import com.example.data.model.User
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedUserForStation by remember { mutableStateOf<User?>(null) }

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fetchUsers()
        viewModel.fetchStations()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.users_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("users_back_button")) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchUsers() },
                        modifier = Modifier.testTag("refresh_users_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            val role = uiState.currentUser?.role?.uppercase()
            if (role == "ADMINISTRATOR" || role == "ADMIN") {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Default.PersonAdd, null) },
                    text = { Text("Add Guard / User") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_user_fab")
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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

            if (uiState.adminLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.users.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No personnel found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 88.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.users) { u ->
                        UserCard(
                            user = u,
                            currentUserRole = uiState.currentUser?.role?.uppercase() ?: "GUARD",
                            onToggleActive = { viewModel.toggleUserActive(u) },
                            onAssignStation = { selectedUserForStation = u }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateUserDialog(
            stations = uiState.stations,
            onDismiss = { showCreateDialog = false },
            onSubmit = { req ->
                viewModel.createUser(req) { showCreateDialog = false }
            }
        )
    }

    if (selectedUserForStation != null) {
        AssignStationDialog(
            user = selectedUserForStation!!,
            stations = uiState.stations,
            onDismiss = { selectedUserForStation = null },
            onAssign = { stId ->
                viewModel.assignUserStation(selectedUserForStation!!.id, stId)
                selectedUserForStation = null
            }
        )
    }
}

@Composable
fun UserCard(
    user: User,
    currentUserRole: String,
    onToggleActive: () -> Unit,
    onAssignStation: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Column {
                    Text(
                        text = user.fullName ?: user.username,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: ${user.employeeNumber ?: "None"} • ${user.role}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = if (user.isActive) StatusSuccess.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (user.isActive) "ACTIVE" else "INACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (user.isActive) StatusSuccess else StatusWarning,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = "Assigned Station: ${user.stationName ?: "Central (Unassigned)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (currentUserRole == "ADMINISTRATOR" || currentUserRole == "ADMIN" || currentUserRole == "SUPERVISOR") {
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onAssignStation) {
                        Text("Assign Station")
                    }
                    if (currentUserRole == "ADMINISTRATOR" || currentUserRole == "ADMIN") {
                        TextButton(onClick = onToggleActive) {
                            Text(if (user.isActive) "Deactivate" else "Activate")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AssignStationDialog(
    user: User,
    stations: List<com.example.data.model.Station>,
    onDismiss: () -> Unit,
    onAssign: (String?) -> Unit
) {
    var selectedStationId by remember { mutableStateOf(user.station) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign Station: ${user.fullName ?: user.username}", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Select target deployment post:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Option to unassign / set to Central Reserve
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedStationId == null,
                        onClick = { selectedStationId = null }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Central Reserve (Unassigned)", fontWeight = FontWeight.SemiBold)
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                stations.forEach { st ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedStationId == st.id,
                            onClick = { selectedStationId = st.id }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(st.name, fontWeight = FontWeight.Medium)
                            if (!st.code.isNullOrBlank()) {
                                Text("Code: ${st.code}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAssign(selectedStationId) },
                modifier = Modifier.testTag("save_station_assignment_button")
            ) {
                Text("Save Assignment")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CreateUserDialog(
    stations: List<com.example.data.model.Station>,
    onDismiss: () -> Unit,
    onSubmit: (CreateUserRequest) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("GUARD") }
    var stationId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Security Officer / User") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("First Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Last Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = role == "GUARD",
                        onClick = { role = "GUARD" },
                        label = { Text("Guard") }
                    )
                    FilterChip(
                        selected = role == "SUPERVISOR",
                        onClick = { role = "SUPERVISOR" },
                        label = { Text("Supervisor") }
                    )
                    FilterChip(
                        selected = role == "ADMINISTRATOR",
                        onClick = { role = "ADMINISTRATOR" },
                        label = { Text("Admin") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (username.isNotBlank() && email.isNotBlank()) {
                        onSubmit(
                            CreateUserRequest(
                                username = username.trim(),
                                email = email.trim(),
                                role = role,
                                firstName = firstName.trim().ifEmpty { null },
                                lastName = lastName.trim().ifEmpty { null },
                                station = stationId
                            )
                        )
                    }
                },
                enabled = username.isNotBlank() && email.isNotBlank()
            ) {
                Text("Create Account")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
