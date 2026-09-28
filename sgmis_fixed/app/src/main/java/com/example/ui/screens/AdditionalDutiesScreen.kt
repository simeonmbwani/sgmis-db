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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.CreateEscortDutyRequest
import com.example.data.model.CreateExamDutyRequest
import com.example.data.model.EscortDuty
import com.example.data.model.ExamDuty
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.SgmisViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdditionalDutiesScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0: Vehicle Escorts, 1: Exam Escorts
    var showCreateEscortDialog by remember { mutableStateOf(false) }
    var showCreateExamDialog by remember { mutableStateOf(false) }
    var showPaperEscortDialog by remember { mutableStateOf(false) }
    var showAutoAllocateDialog by remember { mutableStateOf(false) }

    val isSupervisorOrAdmin = uiState.currentUser?.role?.uppercase() in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")

    LaunchedEffect(Unit) {
        viewModel.fetchEscortDuties()
        viewModel.fetchExamDuties()
        viewModel.fetchStations()
        if (isSupervisorOrAdmin) viewModel.fetchUsers()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTab == 0) "Vehicle Escort Operations" else "Exam Security Escorts", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (isSupervisorOrAdmin) {
                        IconButton(onClick = { showAutoAllocateDialog = true }) {
                            Icon(Icons.Default.AutoFixHigh, "Auto-Allocate Non-Duty Guards")
                        }
                    }
                    IconButton(onClick = {
                        viewModel.fetchEscortDuties()
                        viewModel.fetchExamDuties()
                    }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (isSupervisorOrAdmin) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (selectedTab == 0) showCreateEscortDialog = true else showCreateExamDialog = true
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(if (selectedTab == 0) "Assign Escort" else "Schedule Exam Duty") },
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
            // Success & Error notification banners
            if (uiState.successMessage != null) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
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

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Vehicle Escorts (${uiState.escortDuties.size})") },
                    icon = { Icon(Icons.Default.DirectionsCar, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Exam Escorts (${uiState.examDuties.size})") },
                    icon = { Icon(Icons.Default.School, null) }
                )
            }

            if (isSupervisorOrAdmin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Duty Allocation Engine",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selectedTab == 1) {
                            Button(
                                onClick = { showPaperEscortDialog = true },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Paper Escort (06-17h)", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        OutlinedButton(
                            onClick = { showAutoAllocateDialog = true },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Auto-Allocate (${if (selectedTab == 0) "Escorts" else "Exams"})", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                if (uiState.escortsLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (uiState.escortDuties.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No management or vehicle escort duties assigned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            } else {
                if (uiState.examsLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (uiState.examDuties.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No exam security escort duties scheduled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(uiState.examDuties) { duty ->
                            ExamDutyCard(
                                duty = duty,
                                onUpdateStatus = { st -> viewModel.updateExamStatus(duty.id, st) }
                            )
                        }
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

    if (showCreateExamDialog) {
        CreateExamDialog(
            guards = uiState.users.filter { it.role == "GUARD" }.ifEmpty { listOfNotNull(uiState.currentUser) },
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showCreateExamDialog = false
            },
            onSubmit = { req ->
                viewModel.createExamDuty(req) {
                    showCreateExamDialog = false
                }
            }
        )
    }

    if (showPaperEscortDialog) {
        SchedulePaperEscortDialog(
            stations = uiState.stations,
            guards = uiState.users.filter { it.role == "GUARD" }.ifEmpty { listOfNotNull(uiState.currentUser) },
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showPaperEscortDialog = false
            },
            onSubmit = { stId, date, guardIds ->
                viewModel.scheduleExamEscort(
                    stationId = stId,
                    date = date,
                    guardIds = guardIds,
                    startTime = "06:00:00",
                    endTime = "17:00:00",
                    reason = "Examination paper collection escort to University National Centre"
                ) {
                    showPaperEscortDialog = false
                }
            }
        )
    }

    if (showAutoAllocateDialog) {
        AutoAllocateDutyDialog(
            isExam = selectedTab == 1,
            isLoading = uiState.isLoading,
            onDismiss = { showAutoAllocateDialog = false },
            onAllocateExam = { date, strategy, count ->
                viewModel.autoAllocateExams(date, strategy, count) {
                    showAutoAllocateDialog = false
                }
            },
            onAllocateEscort = { start, end, strategy, count ->
                viewModel.autoAllocateEscorts(start, end, strategy, count) {
                    showAutoAllocateDialog = false
                }
            }
        )
    }
}

@Composable
fun EscortCard(
    duty: EscortDuty,
    onUpdateStatus: (String) -> Unit
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
                    Text(duty.missionName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    val ref = duty.reference ?: "ESC-${duty.id.take(8).uppercase()}"
                    Text("Ref: $ref", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
                Surface(
                    color = when (duty.status) {
                        "COMPLETED" -> StatusSuccess.copy(alpha = 0.2f)
                        "ACKNOWLEDGED", "EN_ROUTE" -> MaterialTheme.colorScheme.primaryContainer
                        "CANCELLED" -> MaterialTheme.colorScheme.errorContainer
                        else -> StatusWarning.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = duty.statusDisplay ?: duty.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (duty.status) {
                            "COMPLETED" -> StatusSuccess
                            "ACKNOWLEDGED", "EN_ROUTE" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "CANCELLED" -> MaterialTheme.colorScheme.error
                            else -> StatusWarning
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text("Route: ${duty.origin} → ${duty.destination}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Officer: ${duty.guardName}${if (!duty.guardEmployeeId.isNullOrBlank()) " (${duty.guardEmployeeId})" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            
            if (!duty.supervisorName.isNullOrBlank()) {
                Text("Authorising Supervisor: ${duty.supervisorName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!duty.stationName.isNullOrBlank()) {
                Text("Station: ${duty.stationName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!duty.purpose.isNullOrBlank()) {
                Text("Movement Purpose: ${duty.purpose}", style = MaterialTheme.typography.bodySmall)
            }
            if (!duty.instructions.isNullOrBlank()) {
                Text("Instructions: ${duty.instructions}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!duty.contactNumbers.isNullOrBlank()) {
                Text("Contact: ${duty.contactNumbers}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }

            Text("Scheduled: ${duty.startTime.take(16).replace("T", " ")} to ${duty.endTime.take(16).replace("T", " ")}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            
            if (!duty.departureTime.isNullOrBlank() || !duty.completionTime.isNullOrBlank()) {
                val dep = duty.departureTime?.take(16)?.replace("T", " ") ?: "Pending"
                val comp = duty.completionTime?.take(16)?.replace("T", " ") ?: "In Progress"
                Text("Departure: $dep | Completion: $comp", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }

            if (!duty.notes.isNullOrBlank()) {
                Text("Notes: ${duty.notes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!duty.remarks.isNullOrBlank()) {
                Text("Remarks: ${duty.remarks}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (duty.status != "COMPLETED" && duty.status != "CANCELLED") {
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (duty.status == "SCHEDULED" || duty.status == "ASSIGNED") {
                        OutlinedButton(onClick = { onUpdateStatus("ACKNOWLEDGED") }) {
                            Text("Acknowledge Duty")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { onUpdateStatus("EN_ROUTE") }) {
                            Text("Mark En Route")
                        }
                    } else if (duty.status == "ACKNOWLEDGED") {
                        Button(onClick = { onUpdateStatus("EN_ROUTE") }) {
                            Text("Depart (En Route)")
                        }
                    } else if (duty.status == "EN_ROUTE") {
                        Button(onClick = { onUpdateStatus("COMPLETED") }) {
                            Text("Complete Escort")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExamDutyCard(
    duty: ExamDuty,
    onUpdateStatus: (String) -> Unit
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
                    Text(duty.examTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    val ref = duty.reference ?: "EXAM-${duty.id.take(8).uppercase()}"
                    Text("Ref: $ref", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
                Surface(
                    color = when (duty.status) {
                        "COMPLETED" -> StatusSuccess.copy(alpha = 0.2f)
                        "ACKNOWLEDGED", "IN_PROGRESS" -> MaterialTheme.colorScheme.primaryContainer
                        "CANCELLED" -> MaterialTheme.colorScheme.errorContainer
                        else -> StatusWarning.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = duty.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (duty.status) {
                            "COMPLETED" -> StatusSuccess
                            "ACKNOWLEDGED", "IN_PROGRESS" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "CANCELLED" -> MaterialTheme.colorScheme.error
                            else -> StatusWarning
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text("Institution: ${duty.institution}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!duty.hallPost.isNullOrBlank()) {
                Text("Hall / Post: ${duty.hallPost}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            Text("Assigned Guard: ${duty.guardName}${if (!duty.guardEmployeeId.isNullOrBlank()) " (${duty.guardEmployeeId})" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            
            if (!duty.supervisorName.isNullOrBlank()) {
                Text("Supervisor: ${duty.supervisorName}${if (!duty.supervisorContact.isNullOrBlank()) " (${duty.supervisorContact})" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!duty.stationName.isNullOrBlank()) {
                Text("Station: ${duty.stationName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!duty.instructions.isNullOrBlank()) {
                Text("Examination Instructions: ${duty.instructions}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("Date: ${duty.date}${if (!duty.reportingTime.isNullOrBlank()) " | Reporting: ${duty.reportingTime}" else ""} (${duty.startTime} – ${duty.endTime})", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)

            if (!duty.notes.isNullOrBlank()) {
                Text("Notes: ${duty.notes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!duty.remarks.isNullOrBlank()) {
                Text("Remarks: ${duty.remarks}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (duty.status != "COMPLETED" && duty.status != "CANCELLED") {
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (duty.status == "ASSIGNED") {
                        OutlinedButton(onClick = { onUpdateStatus("ACKNOWLEDGED") }) {
                            Text("Acknowledge Duty")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { onUpdateStatus("IN_PROGRESS") }) {
                            Text("Report to Post")
                        }
                    } else if (duty.status == "ACKNOWLEDGED") {
                        Button(onClick = { onUpdateStatus("IN_PROGRESS") }) {
                            Text("Report to Post")
                        }
                    } else if (duty.status == "IN_PROGRESS") {
                        Button(onClick = { onUpdateStatus("COMPLETED") }) {
                            Text("Complete Exam Duty")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateEscortDialog(
    guards: List<com.example.data.model.User>,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (CreateEscortDutyRequest) -> Unit
) {
    val isoFormat = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val now = remember { Date() }
    val defaultStart = remember { isoFormat.format(now) }
    val defaultEnd = remember { isoFormat.format(Date(now.time + 8 * 3600 * 1000)) }

    var selectedGuardId by remember { mutableStateOf(guards.firstOrNull()?.id ?: "") }
    var missionName by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf(defaultStart) }
    var endTime by remember { mutableStateOf(defaultEnd) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Assign Vehicle Escort") },
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
                    value = missionName,
                    onValueChange = { missionName = it },
                    label = { Text("Mission / Travel Purpose *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = origin,
                        onValueChange = { origin = it },
                        label = { Text("Origin *") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        label = { Text("Destination *") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = startTime,
                    onValueChange = { startTime = it },
                    label = { Text("Departure (ISO 8601 UTC)") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endTime,
                    onValueChange = { endTime = it },
                    label = { Text("Expected Return (ISO 8601 UTC)") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedGuardId.isNotBlank() && missionName.isNotBlank() && origin.isNotBlank() && destination.isNotBlank()) {
                        onSubmit(
                            CreateEscortDutyRequest(
                                guard = selectedGuardId,
                                missionName = missionName.trim(),
                                origin = origin.trim(),
                                destination = destination.trim(),
                                startTime = startTime.trim(),
                                endTime = endTime.trim()
                            )
                        )
                    }
                },
                enabled = !isLoading && selectedGuardId.isNotBlank() && missionName.isNotBlank() && origin.isNotBlank() && destination.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scheduling...")
                } else {
                    Text("Schedule Escort")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

@Composable
fun CreateExamDialog(
    guards: List<com.example.data.model.User>,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (CreateExamDutyRequest) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val defaultDate = remember { dateFormat.format(Date()) }

    var selectedGuardId by remember { mutableStateOf(guards.firstOrNull()?.id ?: "") }
    var institution by remember { mutableStateOf("") }
    var examTitle by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(defaultDate) }
    var startTime by remember { mutableStateOf("08:00:00") }
    var endTime by remember { mutableStateOf("16:00:00") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Schedule Exam Escort Duty") },
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
                    value = examTitle,
                    onValueChange = { examTitle = it },
                    label = { Text("Exam Title / Session *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = institution,
                    onValueChange = { institution = it },
                    label = { Text("Institution / Venue *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (YYYY-MM-DD) *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text("Start Time") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text("End Time") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedGuardId.isNotBlank() && examTitle.isNotBlank() && institution.isNotBlank()) {
                        onSubmit(
                            CreateExamDutyRequest(
                                guard = selectedGuardId,
                                institution = institution.trim(),
                                examTitle = examTitle.trim(),
                                date = date.trim(),
                                startTime = startTime.trim(),
                                endTime = endTime.trim()
                            )
                        )
                    }
                },
                enabled = !isLoading && selectedGuardId.isNotBlank() && examTitle.isNotBlank() && institution.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scheduling...")
                } else {
                    Text("Schedule Exam Duty")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

@Composable
fun AutoAllocateDutyDialog(
    isExam: Boolean,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onAllocateExam: (date: String, strategy: String, count: Int) -> Unit,
    onAllocateEscort: (start: String, end: String, strategy: String, count: Int) -> Unit
) {
    val today = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
    var date by remember { mutableStateOf(today) }
    var startTime by remember { mutableStateOf("${today}T08:00") }
    var endTime by remember { mutableStateOf("${today}T18:00") }
    var strategy by remember { mutableStateOf("RANDOM") } // "RANDOM" or "PAIR"
    var countStr by remember { mutableStateOf("2") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isExam) "Auto-Allocate Non-Duty Guards (Exams)" else "Auto-Allocate Non-Duty Guards (Escorts)", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "System identifies rostered off-duty/rest guards to prevent conflicts with station shifts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isExam) {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Exam Date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text("Mission Start (YYYY-MM-DDTHH:mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text("Mission End (YYYY-MM-DDTHH:mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("Allocation Strategy:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = strategy == "RANDOM",
                        onClick = { strategy = "RANDOM" },
                        label = { Text("RANDOM (Available)") }
                    )
                    FilterChip(
                        selected = strategy == "PAIR",
                        onClick = { strategy = "PAIR" },
                        label = { Text("PAIR (Buddy Unit)") }
                    )
                }

                OutlinedTextField(
                    value = countStr,
                    onValueChange = { countStr = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Required Guards Count") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val count = countStr.toIntOrNull() ?: 2
                    if (isExam) {
                        onAllocateExam(date, strategy, count)
                    } else {
                        onAllocateEscort(startTime, endTime, strategy, count)
                    }
                },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text("Auto-Allocate")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SchedulePaperEscortDialog(
    stations: List<com.example.data.model.Station>,
    guards: List<com.example.data.model.User>,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (stationId: String, date: String, guardIds: List<String>) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val defaultDate = remember { dateFormat.format(Date()) }

    var selectedStationId by remember { mutableStateOf(stations.firstOrNull()?.id ?: "") }
    var date by remember { mutableStateOf(defaultDate) }
    val selectedGuardIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(stations) {
        if (selectedStationId.isBlank() && stations.isNotEmpty()) {
            selectedStationId = stations.first().id
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Exam Paper Collection Escort") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Mandatory Standard Requirements:",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("• Standard Hours: 06:00 → 17:00 (11 hours)", style = MaterialTheme.typography.labelSmall)
                        Text("• Crew Size: Exactly 2 Security Guards", style = MaterialTheme.typography.labelSmall)
                        Text("• Destination: University National Centre", style = MaterialTheme.typography.labelSmall)
                        Text("• Conflict Validation: Rejects guards with conflicting active shifts.", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = errorMessage, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Escort Date (YYYY-MM-DD) *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                if (stations.isNotEmpty()) {
                    Text("Select Station:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    stations.forEach { st ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selectedStationId == st.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedStationId == st.id,
                                    onClick = { selectedStationId = st.id },
                                    enabled = !isLoading
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(st.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Text("Select Exactly 2 Escort Guards (${selectedGuardIds.size}/2 selected):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                guards.forEach { g ->
                    val isChecked = selectedGuardIds.contains(g.id)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isChecked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (selectedGuardIds.size < 2) selectedGuardIds.add(g.id)
                                    } else {
                                        selectedGuardIds.remove(g.id)
                                    }
                                },
                                enabled = !isLoading && (isChecked || selectedGuardIds.size < 2)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(g.fullName ?: g.username, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedStationId.isNotBlank() && date.isNotBlank() && selectedGuardIds.size == 2) {
                        onSubmit(selectedStationId, date.trim(), selectedGuardIds.toList())
                    }
                },
                enabled = !isLoading && selectedStationId.isNotBlank() && date.isNotBlank() && selectedGuardIds.size == 2
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Deploy Paper Escort")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

