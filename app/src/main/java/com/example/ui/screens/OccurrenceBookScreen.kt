package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.OccurrenceBookEntry
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OccurrenceBookScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.occurrence_book_title), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchOBEntries() },
                        modifier = Modifier.testTag("refresh_ob_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh OB")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Log OB Entry") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("log_ob_entry_fab")
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.obLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.obEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Book, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No Occurrence Book records found", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.obEntries) { entry ->
                        OBEntryCard(entry)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CreateOBEntryDialog(
            onDismiss = { showAddDialog = false },
            onSubmit = { category, text, check ->
                viewModel.submitOBEntry(category, text, check) {
                    showAddDialog = false
                }
            }
        )
    }
}

@Composable
fun OBEntryCard(entry: OccurrenceBookEntry) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("ob_entry_${entry.entryNumber}"),
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
                Text(
                    text = entry.entryNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = entry.categoryDisplay ?: entry.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = entry.occurrenceText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Post: ${entry.stationName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Officer: ${entry.guardName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CreateOBEntryDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit
) {
    var category by remember { mutableStateOf("ROUTINE") }
    var occurrenceText by remember { mutableStateOf("") }
    var checkRecord by remember { mutableStateOf("Verified & Logged") }

    val categories = listOf("ROUTINE", "VISITOR", "INCIDENT", "VEHICLE", "HANDOVER", "MAINTENANCE")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record OB Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Official Occurrence Book log entry. Backend generates the entry number.", style = MaterialTheme.typography.bodySmall)

                // Category selector
                Text("Category:", style = MaterialTheme.typography.labelSmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.take(3).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = occurrenceText,
                    onValueChange = { occurrenceText = it },
                    label = { Text("Occurrence Description *") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("ob_text_input")
                )

                OutlinedTextField(
                    value = checkRecord,
                    onValueChange = { checkRecord = it },
                    label = { Text("Check / Verification Record") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (occurrenceText.isNotBlank()) {
                        onSubmit(category, occurrenceText, checkRecord)
                    }
                },
                enabled = occurrenceText.isNotBlank(),
                modifier = Modifier.testTag("submit_ob_entry_confirm_button")
            ) {
                Text("Record Entry")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
