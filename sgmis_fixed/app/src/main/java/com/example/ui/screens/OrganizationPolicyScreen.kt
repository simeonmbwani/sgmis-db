package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppRole
import com.example.data.model.OrganizationPolicy
import com.example.ui.components.*
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationPolicyScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAdmin = uiState.appRole == AppRole.ADMINISTRATOR

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var policyToEdit by remember { mutableStateOf<OrganizationPolicy?>(null) }
    var editSummary by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.fetchOrganizationPolicies()
    }

    val categories = listOf("ALL", "LEAVE", "DUTY", "ROSTER", "PATROL", "ATTENDANCE", "COMPENSATION", "GEOFENCE", "GENERAL")

    val filteredPolicies = remember(uiState.organizationPolicies, searchQuery, selectedCategory) {
        uiState.organizationPolicies.filter { policy ->
            val matchesCategory = selectedCategory == "ALL" || policy.category.equals(selectedCategory, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() ||
                    policy.title.contains(searchQuery, ignoreCase = true) ||
                    policy.summary.contains(searchQuery, ignoreCase = true) ||
                    policy.content.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Organization Policies", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Standard Operating Procedures & Rules", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchOrganizationPolicies() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar
            SmartSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search operational policies and rules..."
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Strip
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { category ->
                    SmartFilterChip(
                        text = category,
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.policiesLoading) {
                SmartLoadingState(message = "Loading authoritative policies...")
            } else if (filteredPolicies.isEmpty()) {
                SmartEmptyState(
                    title = "No Policies Found",
                    message = if (searchQuery.isNotBlank() || selectedCategory != "ALL")
                        "No policies match your search or filter."
                    else
                        "No organization policies currently registered on the server."
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredPolicies, key = { it.id }) { policy ->
                        SmartCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isAdmin) {
                                    policyToEdit = policy
                                    editSummary = policy.summary
                                    editContent = policy.content
                                }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = policy.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SmartStatusChip(
                                        status = policy.category
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Category: ${policy.categoryDisplay ?: policy.category} • v${policy.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (policy.summary.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = policy.summary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                }

                                if (policy.content.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = policy.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (policy.updatedByName != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Last updated by: ${policy.updatedByName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isAdmin) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = {
                                                policyToEdit = policy
                                                editSummary = policy.summary
                                                editContent = policy.content
                                            }
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Edit Policy", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // Policy Edit Dialog for Administrators
    policyToEdit?.let { policy ->
        AlertDialog(
            onDismissRequest = { policyToEdit = null },
            title = { Text("Update Policy: ${policy.title}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Category: ${policy.category}", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editSummary,
                        onValueChange = { editSummary = it },
                        label = { Text("Policy Summary / Rule") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("Operational Details / Guidelines") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updates = mutableMapOf<String, Any>()
                        if (editSummary != policy.summary) updates["summary"] = editSummary
                        if (editContent != policy.content) updates["content"] = editContent
                        if (updates.isNotEmpty()) {
                            viewModel.updateOrganizationPolicy(
                                id = policy.id,
                                updates = updates
                            )
                        }
                        policyToEdit = null
                    }
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { policyToEdit = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
