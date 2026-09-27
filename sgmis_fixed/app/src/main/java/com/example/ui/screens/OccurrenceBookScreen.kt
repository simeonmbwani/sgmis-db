package com.example.ui.screens

import androidx.compose.foundation.background
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
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var amendingEntry by remember { mutableStateOf<OccurrenceBookEntry?>(null) }

    val currentUserRole = uiState.currentUser?.role
    val isSupervisor = currentUserRole == "SUPERVISOR"
    val canCreateEntry = !isSupervisor && (!uiState.isGuard || uiState.isOnDuty)

    LaunchedEffect(Unit) {
        viewModel.fetchOBEntries()
    }

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.occurrence_book_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("ob_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
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
            if (canCreateEntry) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Log OB Entry") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("log_ob_entry_fab")
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Off-duty guard notice
            if (uiState.isGuard && !uiState.isOnDuty) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Viewing mode: You must be CLOCKED IN (On Duty) to record official OB entries.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Notification banners
            if (uiState.successMessage != null) {
                Surface(
                    color = com.example.ui.theme.StatusSuccess.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = com.example.ui.theme.StatusSuccess, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.successMessage!!,
                            color = MaterialTheme.colorScheme.onSurface,
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
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.obEntries) { entry ->
                        OBEntryCard(
                            entry = entry,
                            onAmend = { amendingEntry = entry }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CreateOBEntryDialog(
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showAddDialog = false
            },
            onSubmit = { category, text, check, cr ->
                viewModel.submitOBEntry(category, text, check, cr) {
                    showAddDialog = false
                }
            }
        )
    }

    if (amendingEntry != null) {
        AmendOBDialog(
            entry = amendingEntry!!,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                amendingEntry = null
            },
            onSubmit = { reason, amendedText ->
                viewModel.amendOBEntry(amendingEntry!!.id, reason, amendedText) {
                    amendingEntry = null
                }
            }
        )
    }
}

@Composable
fun OBEntryCard(
    entry: OccurrenceBookEntry,
    onAmend: () -> Unit = {}
) {
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
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.entryNumber,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val crCode = entry.crossReference ?: (if (entry.checkRecord?.startsWith("CR-") == true) entry.checkRecord else null)
                        if (!crCode.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = crCode,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = entry.createdAt.take(19).replace('T', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

            if (!entry.checkRecord.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Verification: ${entry.checkRecord}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            if (entry.amendments.isNotEmpty()) {
                Text(
                    text = "Official Amendments (${entry.amendments.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                entry.amendments.forEach { amendment ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Amendment by ${amendment.amendedByName ?: "Authorized Personnel"} (${amendment.createdAt.take(19).replace('T', ' ')})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Reason: ${amendment.reason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = amendment.amendedText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
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
                if (entry.isAmendable) {
                    TextButton(
                        onClick = onAmend,
                        modifier = Modifier.testTag("amend_ob_${entry.entryNumber}")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Amend", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Immutable (24h+)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateOBEntryDialog(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String?) -> Unit
) {
    var category by remember { mutableStateOf("ROUTINE") }
    var occurrenceText by remember { mutableStateOf("") }
    var checkRecord by remember { mutableStateOf("Verified & Logged") }
    var crossReference by remember { mutableStateOf("") }

    // Category-specific input states
    var vehicleReg by remember { mutableStateOf("") }
    var vehicleDriver by remember { mutableStateOf("") }
    var vehiclePurpose by remember { mutableStateOf("") }

    var visitorName by remember { mutableStateOf("") }
    var visitorIdNumber by remember { mutableStateOf("") }
    var visitorHost by remember { mutableStateOf("") }
    var visitorPassNumber by remember { mutableStateOf("") }

    var incidentType by remember { mutableStateOf("") }
    var incidentActionTaken by remember { mutableStateOf("") }
    var incidentPersonsInvolved by remember { mutableStateOf("") }

    var handoverRelievingOfficer by remember { mutableStateOf("") }
    var handoverKeysEquipment by remember { mutableStateOf("") }
    var handoverSpecialInstructions by remember { mutableStateOf("") }

    var maintenanceFacility by remember { mutableStateOf("") }
    var maintenanceDefect by remember { mutableStateOf("") }
    var maintenanceReportedTo by remember { mutableStateOf("") }

    fun getEffectiveOccurrenceText(): String {
        val prefix = when (category) {
            "VEHICLE" -> listOfNotNull(
                vehicleReg.ifBlank { null }?.let { "Reg: $it" },
                vehicleDriver.ifBlank { null }?.let { "Driver: $it" },
                vehiclePurpose.ifBlank { null }?.let { "Purpose: $it" }
            ).joinToString(" | ")
            "VISITOR" -> listOfNotNull(
                visitorName.ifBlank { null }?.let { "Visitor: $it" },
                visitorIdNumber.ifBlank { null }?.let { "ID: $it" },
                visitorHost.ifBlank { null }?.let { "Host: $it" },
                visitorPassNumber.ifBlank { null }?.let { "Pass: $it" }
            ).joinToString(" | ")
            "INCIDENT" -> listOfNotNull(
                incidentType.ifBlank { null }?.let { "Nature: $it" },
                incidentActionTaken.ifBlank { null }?.let { "Action: $it" },
                incidentPersonsInvolved.ifBlank { null }?.let { "Involved: $it" }
            ).joinToString(" | ")
            "HANDOVER" -> listOfNotNull(
                handoverRelievingOfficer.ifBlank { null }?.let { "Relieving: $it" },
                handoverKeysEquipment.ifBlank { null }?.let { "Keys/Eq: $it" },
                handoverSpecialInstructions.ifBlank { null }?.let { "Orders: $it" }
            ).joinToString(" | ")
            "MAINTENANCE" -> listOfNotNull(
                maintenanceFacility.ifBlank { null }?.let { "Facility: $it" },
                maintenanceDefect.ifBlank { null }?.let { "Defect: $it" },
                maintenanceReportedTo.ifBlank { null }?.let { "Reported To: $it" }
            ).joinToString(" | ")
            else -> ""
        }
        return if (prefix.isNotBlank() && occurrenceText.isNotBlank()) {
            "[$category] $prefix. Details: $occurrenceText"
        } else if (prefix.isNotBlank()) {
            "[$category] $prefix"
        } else {
            occurrenceText
        }
    }

    val categoriesRow1 = listOf("ROUTINE", "VISITOR", "INCIDENT")
    val categoriesRow2 = listOf("VEHICLE", "HANDOVER", "MAINTENANCE")

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Record OB Entry") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text("Official Occurrence Book log entry. Backend generates the sequential entry number and CR code.", style = MaterialTheme.typography.bodySmall)

                // Category selector (all 6 official categories)
                Text("Category:", style = MaterialTheme.typography.labelSmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categoriesRow1.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { if (!isLoading) category = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categoriesRow2.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { if (!isLoading) category = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // Category-Specific Form Fields
                when (category) {
                    "VEHICLE" -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Vehicle Particulars:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = vehicleReg,
                                    onValueChange = { vehicleReg = it },
                                    label = { Text("Vehicle Registration / Plate Number") },
                                    placeholder = { Text("e.g. AEZ-4591") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_vehicle_reg_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = vehicleDriver,
                                    onValueChange = { vehicleDriver = it },
                                    label = { Text("Driver / Operator Name") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_vehicle_driver_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = vehiclePurpose,
                                    onValueChange = { vehiclePurpose = it },
                                    label = { Text("Purpose / Cargo / Remarks") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_vehicle_purpose_input"),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    "VISITOR" -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Visitor Particulars:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = visitorName,
                                    onValueChange = { visitorName = it },
                                    label = { Text("Visitor Full Name") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_visitor_name_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = visitorIdNumber,
                                    onValueChange = { visitorIdNumber = it },
                                    label = { Text("National ID / Passport Number") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_visitor_id_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = visitorHost,
                                    onValueChange = { visitorHost = it },
                                    label = { Text("Host Official / Department") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_visitor_host_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = visitorPassNumber,
                                    onValueChange = { visitorPassNumber = it },
                                    label = { Text("Pass / Badge Number Issued") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_visitor_pass_input"),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    "INCIDENT" -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Incident Categorization:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                OutlinedTextField(
                                    value = incidentType,
                                    onValueChange = { incidentType = it },
                                    label = { Text("Incident Nature / Type") },
                                    placeholder = { Text("e.g. Perimeter breach, theft attempt, unauthorized entry") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_incident_type_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = incidentActionTaken,
                                    onValueChange = { incidentActionTaken = it },
                                    label = { Text("Immediate Action Taken") },
                                    placeholder = { Text("e.g. Apprehended, supervisor notified, dispatched") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_incident_action_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = incidentPersonsInvolved,
                                    onValueChange = { incidentPersonsInvolved = it },
                                    label = { Text("Persons / Witnesses Involved") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_incident_persons_input"),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    "HANDOVER" -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Handover Inventory:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = handoverRelievingOfficer,
                                    onValueChange = { handoverRelievingOfficer = it },
                                    label = { Text("Relieving Officer Name") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_handover_relieving_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = handoverKeysEquipment,
                                    onValueChange = { handoverKeysEquipment = it },
                                    label = { Text("Keys & Equipment Count") },
                                    placeholder = { Text("e.g. Master key bundle, radio #4, torch OK") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_handover_keys_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = handoverSpecialInstructions,
                                    onValueChange = { handoverSpecialInstructions = it },
                                    label = { Text("Special Orders / Instructions") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_handover_instructions_input"),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    "MAINTENANCE" -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Maintenance Defect Log:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = maintenanceFacility,
                                    onValueChange = { maintenanceFacility = it },
                                    label = { Text("Facility / Asset / Zone") },
                                    placeholder = { Text("e.g. East Gate Barrier, Floodlight #3") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_maint_facility_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = maintenanceDefect,
                                    onValueChange = { maintenanceDefect = it },
                                    label = { Text("Defect / Fault Description") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_maint_defect_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = maintenanceReportedTo,
                                    onValueChange = { maintenanceReportedTo = it },
                                    label = { Text("Reported To / Work Order #") },
                                    modifier = Modifier.fillMaxWidth().testTag("ob_maint_reported_input"),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = occurrenceText,
                    onValueChange = { occurrenceText = it },
                    label = { Text("Occurrence Description / Notes *") },
                    minLines = 3,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("ob_text_input")
                )

                OutlinedTextField(
                    value = crossReference,
                    onValueChange = { crossReference = it },
                    label = { Text("Cross Reference (CR) Code") },
                    placeholder = { Text("e.g. CR-MW-001 (auto-generated if empty)") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("ob_cr_input")
                )

                OutlinedTextField(
                    value = checkRecord,
                    onValueChange = { checkRecord = it },
                    label = { Text("Check / Verification Record") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val effectiveText = getEffectiveOccurrenceText()
            Button(
                onClick = {
                    if (effectiveText.isNotBlank() && !isLoading) {
                        onSubmit(category, effectiveText, checkRecord, crossReference.trim().ifBlank { null })
                    }
                },
                enabled = effectiveText.isNotBlank() && !isLoading,
                modifier = Modifier.testTag("submit_ob_entry_confirm_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Recording...")
                } else {
                    Text("Record Entry")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

@Composable
fun AmendOBDialog(
    entry: OccurrenceBookEntry,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    var amendedText by remember { mutableStateOf(entry.occurrenceText) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Amend Record ${entry.entryNumber}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Original entries are immutable evidence. Amendments are appended to the permanent audit trail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Mandatory Justification / Reason *") },
                    placeholder = { Text("e.g. Correction of vehicle registration number") },
                    singleLine = false,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("amend_ob_reason_input")
                )
                OutlinedTextField(
                    value = amendedText,
                    onValueChange = { amendedText = it },
                    label = { Text("Amended Record Content *") },
                    minLines = 3,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("amend_ob_content_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (reason.isNotBlank() && amendedText.isNotBlank() && !isLoading) {
                        onSubmit(reason.trim(), amendedText.trim())
                    }
                },
                enabled = reason.isNotBlank() && amendedText.isNotBlank() && !isLoading,
                modifier = Modifier.testTag("submit_amend_ob_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Submitting...")
                } else {
                    Text("Append Amendment")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

