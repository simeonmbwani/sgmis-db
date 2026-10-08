package com.example.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.CreateUserRequest
import com.example.data.model.User
import com.example.data.model.UpdateUserRequest
import com.example.ui.components.*
import com.example.ui.theme.*
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
    var selectedUserForEdit by remember { mutableStateOf<User?>(null) }
    var userForActiveChange by remember { mutableStateOf<User?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedRoleFilter by remember { mutableStateOf("ALL") }

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

    val filteredUsers = remember(uiState.users, searchQuery, selectedRoleFilter) {
        uiState.users.filter { user ->
            val matchesSearch = searchQuery.isBlank() ||
                (user.fullName ?: "").contains(searchQuery, ignoreCase = true) ||
                user.username.contains(searchQuery, ignoreCase = true) ||
                (user.employeeNumber ?: "").contains(searchQuery, ignoreCase = true) ||
                (user.stationName ?: "").contains(searchQuery, ignoreCase = true)

            val matchesRole = when (selectedRoleFilter) {
                "GUARD" -> user.role.equals("GUARD", true)
                "SUPERVISOR" -> user.role.equals("SUPERVISOR", true)
                "ADMIN" -> user.role.equals("ADMINISTRATOR", true) || user.role.equals("ADMIN", true)
                else -> true
            }
            matchesSearch && matchesRole
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = stringResource(R.string.users_title),
                subtitle = "Personnel & Deployment Console",
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("users_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchUsers() },
                        modifier = Modifier.testTag("refresh_users_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            val role = uiState.currentUser?.role?.uppercase()
            if (role == "ADMINISTRATOR" || role == "ADMIN") {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Default.PersonAdd, null) },
                    text = { Text("Add Guard / User", fontWeight = FontWeight.SemiBold) },
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

            // Search and Filter Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                SgmisSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search by name, employee ID, station...",
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SgmisFilterChip(
                        selected = selectedRoleFilter == "ALL",
                        onClick = { selectedRoleFilter = "ALL" },
                        label = "All (${uiState.users.size})"
                    )
                    SgmisFilterChip(
                        selected = selectedRoleFilter == "GUARD",
                        onClick = { selectedRoleFilter = "GUARD" },
                        label = "Guards (${uiState.users.count { it.role.equals("GUARD", true) }})"
                    )
                    SgmisFilterChip(
                        selected = selectedRoleFilter == "SUPERVISOR",
                        onClick = { selectedRoleFilter = "SUPERVISOR" },
                        label = "Supervisors (${uiState.users.count { it.role.equals("SUPERVISOR", true) }})"
                    )
                    SgmisFilterChip(
                        selected = selectedRoleFilter == "ADMIN",
                        onClick = { selectedRoleFilter = "ADMIN" },
                        label = "Admins (${uiState.users.count { it.role.equals("ADMINISTRATOR", true) || it.role.equals("ADMIN", true) }})"
                    )
                }
            }

            if (uiState.adminLoading) {
                SgmisLoadingSkeleton(modifier = Modifier.padding(Spacing.md))
            } else if (filteredUsers.isEmpty()) {
                SgmisEmptyState(
                    icon = Icons.Outlined.PeopleOutline,
                    title = if (searchQuery.isNotBlank()) "No Matching Personnel" else "No Personnel Found",
                    description = if (searchQuery.isNotBlank()) "Try refining your search keyword." else "Registered security personnel will appear here.",
                    actionLabel = if (searchQuery.isBlank() && (uiState.currentUser?.role?.uppercase() in listOf("ADMINISTRATOR", "ADMIN"))) "Add Guard / User" else null,
                    onAction = { showCreateDialog = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
                    contentPadding = PaddingValues(top = Spacing.xs, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(filteredUsers) { u ->
                        UserCard(
                            user = u,
                            currentUserRole = uiState.currentUser?.role?.uppercase() ?: "GUARD",
                            onToggleActive = { userForActiveChange = u },
                            onAssignStation = { selectedUserForStation = u },
                            onEdit = { selectedUserForEdit = u }
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

    selectedUserForEdit?.let { user ->
        EditPersonnelDialog(user = user, onDismiss = { selectedUserForEdit = null }, onSave = { req ->
            viewModel.updateManagedUser(user.id, req) { selectedUserForEdit = null }
        })
    }
    userForActiveChange?.let { user ->
        AlertDialog(
            onDismissRequest = { userForActiveChange = null },
            title = { Text(if (user.isActive) "Deactivate account?" else "Reactivate account?", fontWeight = FontWeight.Bold) },
            text = { Text("${user.fullName ?: user.username} · ${user.employeeNumber.orEmpty()}\nAccount status: ${if (user.isActive) "Active" else "Inactive"} → ${if (user.isActive) "Inactive" else "Active"}") },
            confirmButton = {
                Button(
                    onClick = { viewModel.toggleUserActive(user); userForActiveChange = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (user.isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) { Text("Confirm Change") }
            },
            dismissButton = { TextButton(onClick = { userForActiveChange = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun UserCard(
    user: User,
    currentUserRole: String,
    onToggleActive: () -> Unit,
    onAssignStation: () -> Unit,
    onEdit: () -> Unit = {}
) {
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

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SgmisBadge(
                        text = if (user.isActive) "ACTIVE" else "INACTIVE",
                        variant = if (user.isActive) SgmisBadgeVariant.SUCCESS else SgmisBadgeVariant.ERROR
                    )
                }
            }

            Text(
                text = "Assigned Station: ${user.stationName ?: "Central (Unassigned)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )

            if (currentUserRole == "ADMINISTRATOR" || currentUserRole == "ADMIN") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onAssignStation) {
                        Text("Assign Station")
                    }
                    TextButton(onClick = onEdit) { Text("Edit Details") }
                    TextButton(
                        onClick = onToggleActive,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (user.isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (user.isActive) "Deactivate" else "Activate")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditPersonnelDialog(user: User, onDismiss: () -> Unit, onSave: (UpdateUserRequest) -> Unit) {
    var confirmSave by remember(user.id) { mutableStateOf(false) }
    var employeeNumber by remember(user.id) { mutableStateOf(user.employeeNumber.orEmpty()) }
    var firstName by remember(user.id) { mutableStateOf(user.firstName.orEmpty()) }
    var lastName by remember(user.id) { mutableStateOf(user.lastName.orEmpty()) }
    var phone by remember(user.id) { mutableStateOf(user.phoneNumber.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Personnel Record", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(employeeNumber, { employeeNumber = it }, label = { Text("Employee number") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(firstName, { firstName = it }, label = { Text("First name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lastName, { lastName = it }, label = { Text("Last name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { confirmSave = true }) { Text("Review Changes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text("Save these personnel changes?", fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Employee: ${user.fullName ?: user.username}")
            Text("Employee number: ${user.employeeNumber.orEmpty()} → $employeeNumber")
            Text("First name: ${user.firstName.orEmpty()} → $firstName")
            Text("Last name: ${user.lastName.orEmpty()} → $lastName")
            Text("Phone: ${user.phoneNumber.orEmpty()} → $phone")
        } },
        confirmButton = { Button(onClick = { confirmSave = false; onSave(UpdateUserRequest(employeeNumber = employeeNumber, firstName = firstName, lastName = lastName, phoneNumber = phone)) }) { Text("Confirm Save") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Back") } }
    )
}

@Composable
fun AssignStationDialog(
    user: User,
    stations: List<com.example.data.model.Station>,
    onDismiss: () -> Unit,
    onAssign: (String?) -> Unit
) {
    var selectedStationId by remember { mutableStateOf(user.station) }
    var confirmAssignment by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign Station: ${user.fullName ?: user.username}", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
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
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("Central Reserve (Unassigned)", fontWeight = FontWeight.SemiBold)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                stations.forEach { st ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedStationId == st.id,
                            onClick = { selectedStationId = st.id }
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
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
                onClick = { confirmAssignment = true },
                modifier = Modifier.testTag("save_station_assignment_button")
            ) {
                Text("Save Assignment")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
    if (confirmAssignment) AlertDialog(
        onDismissRequest = { confirmAssignment = false },
        title = { Text("Save this station assignment?", fontWeight = FontWeight.Bold) },
        text = { Text("${user.fullName ?: user.username}: ${user.stationName ?: "Unassigned"} → ${stations.firstOrNull { it.id == selectedStationId }?.name ?: "Unassigned"}") },
        confirmButton = { Button(onClick = { confirmAssignment = false; onAssign(selectedStationId) }) { Text("Confirm Assignment") } },
        dismissButton = { TextButton(onClick = { confirmAssignment = false }) { Text("Back") } }
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
        title = { Text("Add Security Officer / User", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SgmisFilterChip(
                        selected = role == "GUARD",
                        onClick = { role = "GUARD" },
                        label = "Guard"
                    )
                    SgmisFilterChip(
                        selected = role == "SUPERVISOR",
                        onClick = { role = "SUPERVISOR" },
                        label = "Supervisor"
                    )
                    SgmisFilterChip(
                        selected = role == "ADMINISTRATOR",
                        onClick = { role = "ADMINISTRATOR" },
                        label = "Admin"
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
