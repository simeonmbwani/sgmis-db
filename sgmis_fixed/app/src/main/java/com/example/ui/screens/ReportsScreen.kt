package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.viewmodel.SgmisViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

data class ReportDetailItem(
    val title: String,
    val subtitle: String,
    val timestamp: String? = null,
    val metadata: List<Pair<String, String>> = emptyList(),
    val content: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf("OB") }
    var selectedDetailItem by remember { mutableStateOf<ReportDetailItem?>(null) }

    val categories = listOf(
        "OB" to "Occurrence Book",
        "INCIDENTS" to "Incidents",
        "PATROLS" to "Patrols",
        "VISITORS" to "Visitors",
        "LEAVE" to "Leave",
        "ATTENDANCE" to "Attendance"
    )

    val userRole = uiState.currentUser?.role?.uppercase() ?: "GUARD"
    val isGuard = userRole == "GUARD"

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        if (uiState.successMessage != null || uiState.errorMessage != null) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reports_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("reports_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val shareText = buildString {
                                appendLine("SMART SECURITY - OPERATIONS REPORT: $selectedCategory")
                                appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
                                appendLine("Scope: ${if (isGuard) "Guard Station Records" else "Authoritative Company-Wide Records"}")
                                appendLine("----------------------------------------")
                                when (selectedCategory) {
                                    "OB" -> uiState.obEntries.forEach {
                                        appendLine("[${it.entryNumber}] ${it.createdAt.take(16).replace("T", " ")} | CR: ${it.crossReference ?: "N/A"}")
                                        appendLine("Post: ${it.stationName} | Guard: ${it.guardName}")
                                        appendLine("Entry: ${it.occurrenceText}")
                                        appendLine("---")
                                    }
                                    "INCIDENTS" -> uiState.incidents.forEach {
                                        appendLine("[${it.priority}] ${it.title} (${it.status})")
                                        appendLine("Location: ${it.location} | Guard: ${it.reportingGuardName} | Post: ${it.stationName}")
                                        appendLine("Details: ${it.description}")
                                        appendLine("---")
                                    }
                                    "PATROLS" -> uiState.patrolLogs.forEach {
                                        appendLine("[PATROL] ${it.stationName} (${it.status}) by ${it.guardName}")
                                        appendLine("Started: ${it.startTime.take(16).replace("T", " ")} | Scans: ${it.scansCount}")
                                        appendLine("---")
                                    }
                                    "VISITORS" -> uiState.visitors.forEach {
                                        appendLine("[VISITOR ${it.entryNumber}] ${it.createdAt.take(16).replace("T", " ")}")
                                        appendLine("Post: ${it.stationName} | Logged by: ${it.guardName}")
                                        appendLine("Details: ${it.occurrenceText}")
                                        appendLine("---")
                                    }
                                    "LEAVE" -> uiState.leaveApplications.forEach {
                                        appendLine("[LEAVE] ${it.guardName} (${it.leaveTypeDisplay ?: it.leaveType})")
                                        appendLine("Period: ${it.startDate} to ${it.endDate} | Status: ${it.statusDisplay ?: it.status}")
                                        appendLine("Reason: ${it.reason}")
                                        appendLine("---")
                                    }
                                    "ATTENDANCE" -> uiState.attendanceRecords.forEach {
                                        appendLine("[ATTENDANCE] ${it.guardName} (${it.guardEmployeeNumber ?: ""}) - ${it.shiftDate}")
                                        appendLine("Station: ${it.stationName} | Shift: ${it.shiftType}")
                                        appendLine("Clock-in: ${it.clockIn?.take(16)?.replace("T", " ") ?: "None"} | Clock-out: ${it.clockOut?.take(16)?.replace("T", " ") ?: "None"}")
                                        appendLine("---")
                                    }
                                }
                            }
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Export $selectedCategory Report"))
                        },
                        modifier = Modifier.testTag("export_reports_button")
                    ) {
                        Icon(Icons.Default.Share, "Export / Share Report")
                    }
                    IconButton(
                        onClick = { viewModel.refreshAllData() },
                        modifier = Modifier.testTag("refresh_reports_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Category Filter Chips
            ScrollableTabRow(
                selectedTabIndex = categories.indexOfFirst { it.first == selectedCategory }.coerceAtLeast(0),
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                categories.forEach { (catKey, catLabel) ->
                    Tab(
                        selected = selectedCategory == catKey,
                        onClick = { selectedCategory = catKey },
                        text = { Text(catLabel, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            // Summary Stats Card with Role Scope Indication (interactive)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedDetailItem = ReportDetailItem(
                            title = "Operations Summary: $selectedCategory",
                            subtitle = if (isGuard) "Local Station Scope" else "Authoritative Company Scope",
                            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                            metadata = listOf(
                                "Category" to selectedCategory,
                                "User Role" to userRole,
                                "Scope" to if (isGuard) "Station Assigned Records" else "All Posts / Company Audit",
                                "Active Total" to when (selectedCategory) {
                                    "OB" -> "${uiState.obEntries.size} entries"
                                    "INCIDENTS" -> "${uiState.incidents.size} incidents"
                                    "PATROLS" -> "${uiState.patrolLogs.size} logs"
                                    "VISITORS" -> "${uiState.visitors.size} visitors"
                                    "LEAVE" -> "${uiState.leaveApplications.size} records"
                                    "ATTENDANCE" -> "${uiState.attendanceRecords.size} logs"
                                    else -> "0"
                                }
                            ),
                            content = "Tap any individual record card below for detailed audit breakdown, cross-reference verification, and sharing options."
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isGuard) "AUTHORITATIVE GUARD RECORDS (YOUR STATION)" else "AUTHORITATIVE COMPANY-WIDE AUDIT RECORDS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (selectedCategory) {
                                "OB" -> "${uiState.obEntries.size} Log Entries"
                                "INCIDENTS" -> "${uiState.incidents.size} Recorded Incidents"
                                "PATROLS" -> "${uiState.patrolLogs.size} Completed/Active Patrols"
                                "VISITORS" -> "${uiState.visitors.size} Registered Visitors"
                                "LEAVE" -> "${uiState.leaveApplications.size} Leave Applications"
                                "ATTENDANCE" -> "${uiState.attendanceRecords.size} Clock-in Logs"
                                else -> "0 Records"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = "View Details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Report Data List
            when (selectedCategory) {
                "OB" -> {
                    if (uiState.obEntries.isEmpty()) {
                        EmptyReportPlaceholder("No Occurrence Book entries found.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(uiState.obEntries) { entry ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDetailItem = ReportDetailItem(
                                                title = entry.entryNumber,
                                                subtitle = "Occurrence Book Entry",
                                                timestamp = entry.createdAt.take(19).replace("T", " "),
                                                metadata = listOf(
                                                    "Station" to entry.stationName,
                                                    "Guard" to entry.guardName,
                                                    "Cross Reference (CR)" to (entry.crossReference ?: "None")
                                                ),
                                                content = entry.occurrenceText
                                            )
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(entry.entryNumber, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                            Text(entry.createdAt.take(16).replace("T", " "), style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text(entry.occurrenceText, style = MaterialTheme.typography.bodyMedium)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Post: ${entry.stationName} • Guard: ${entry.guardName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (!entry.crossReference.isNullOrBlank()) {
                                                Text("CR: ${entry.crossReference}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "INCIDENTS" -> {
                    if (uiState.incidents.isEmpty()) {
                        EmptyReportPlaceholder("No incidents recorded.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(uiState.incidents) { inc ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDetailItem = ReportDetailItem(
                                                title = inc.title,
                                                subtitle = "Priority: ${inc.priority} • Status: ${inc.status}",
                                                timestamp = inc.createdAt.take(19).replace("T", " "),
                                                metadata = listOf(
                                                    "Location" to inc.location,
                                                    "Station" to inc.stationName,
                                                    "Guard" to inc.reportingGuardName,
                                                    "Priority" to inc.priority,
                                                    "Status" to inc.status
                                                ),
                                                content = inc.description
                                            )
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(inc.title, fontWeight = FontWeight.Bold)
                                            Text(inc.priority, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                        Text(inc.description, style = MaterialTheme.typography.bodySmall)
                                        Text("Status: ${inc.status} • Location: ${inc.location} • Guard: ${inc.reportingGuardName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                "PATROLS" -> {
                    if (uiState.patrolLogs.isEmpty()) {
                        EmptyReportPlaceholder("No patrol logs recorded.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(uiState.patrolLogs) { p ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDetailItem = ReportDetailItem(
                                                title = "Patrol at ${p.stationName}",
                                                subtitle = "Status: ${p.status}",
                                                timestamp = p.startTime.take(19).replace("T", " "),
                                                metadata = listOf(
                                                    "Officer" to p.guardName,
                                                    "Station" to p.stationName,
                                                    "Total Scans" to "${p.scansCount}",
                                                    "Status" to p.status,
                                                    "End Time" to (p.endTime?.take(19)?.replace("T", " ") ?: "In Progress")
                                                ),
                                                content = "Patrol coverage verification log for post ${p.stationName}. Guard ${p.guardName} recorded ${p.scansCount} checkpoint scans during this patrol session."
                                            )
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(p.stationName, fontWeight = FontWeight.Bold)
                                            Text(p.status, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text("Officer: ${p.guardName} • Scans: ${p.scansCount}", style = MaterialTheme.typography.bodySmall)
                                        Text("Started: ${p.startTime.take(16).replace("T", " ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                "VISITORS" -> {
                    if (uiState.visitors.isEmpty()) {
                        EmptyReportPlaceholder("No visitor logs recorded.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(uiState.visitors) { v ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDetailItem = ReportDetailItem(
                                                title = "Visitor Pass: ${v.entryNumber}",
                                                subtitle = "Gate Log",
                                                timestamp = v.createdAt.take(19).replace("T", " "),
                                                metadata = listOf(
                                                    "Station" to v.stationName,
                                                    "Logged By" to v.guardName,
                                                    "Cross Reference" to (v.crossReference ?: "None")
                                                ),
                                                content = v.occurrenceText
                                            )
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(v.entryNumber, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                        Text(v.occurrenceText, style = MaterialTheme.typography.bodySmall)
                                        Text("Post: ${v.stationName} • Logged by: ${v.guardName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                "LEAVE" -> {
                    if (uiState.leaveApplications.isEmpty()) {
                        EmptyReportPlaceholder("No leave applications filed.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(uiState.leaveApplications) { l ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDetailItem = ReportDetailItem(
                                                title = "Leave Request: ${l.guardName}",
                                                subtitle = "${l.leaveTypeDisplay ?: l.leaveType} (${l.statusDisplay ?: l.status})",
                                                timestamp = "${l.startDate} to ${l.endDate}",
                                                metadata = listOf(
                                                    "Applicant" to l.guardName,
                                                    "Leave Type" to (l.leaveTypeDisplay ?: l.leaveType),
                                                    "Duration" to "${l.startDate} to ${l.endDate}",
                                                    "Status" to (l.statusDisplay ?: l.status),
                                                    "Emergency Phone" to (l.emergencyPhone ?: "N/A"),
                                                    "Emergency Address" to (l.emergencyAddress ?: "N/A"),
                                                    "Reviewer" to (l.reviewerName ?: "Pending"),
                                                    "Review Notes" to (l.reviewerNotes ?: "None")
                                                ),
                                                content = l.reason
                                            )
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("${l.leaveTypeDisplay ?: l.leaveType} (${l.guardName})", fontWeight = FontWeight.Bold)
                                            Text(l.statusDisplay ?: l.status, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text("${l.startDate} to ${l.endDate}: ${l.reason}", style = MaterialTheme.typography.bodySmall)
                                        if (!l.reviewerNotes.isNullOrBlank()) {
                                            Text("Reviewer: ${l.reviewerName ?: "Supervisor"} - ${l.reviewerNotes}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "ATTENDANCE" -> {
                    if (uiState.attendanceRecords.isEmpty()) {
                        EmptyReportPlaceholder("No attendance records found.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(uiState.attendanceRecords) { a ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDetailItem = ReportDetailItem(
                                                title = "Attendance: ${a.guardName}",
                                                subtitle = "${a.shiftType} Shift • ${a.shiftDate}",
                                                timestamp = a.shiftDate,
                                                metadata = listOf(
                                                    "Employee #" to (a.guardEmployeeNumber ?: "N/A"),
                                                    "Station" to a.stationName,
                                                    "Shift Type" to a.shiftType,
                                                    "Clock-In" to (a.clockIn?.take(19)?.replace("T", " ") ?: "Not Logged"),
                                                    "Clock-Out" to (a.clockOut?.take(19)?.replace("T", " ") ?: "Not Logged")
                                                ),
                                                content = "Authoritative muster roll entry for guard ${a.guardName} at ${a.stationName}."
                                            )
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("${a.guardName} (${a.guardEmployeeNumber ?: ""})", fontWeight = FontWeight.Bold)
                                            Text(a.shiftDate, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text("Station: ${a.stationName} • Shift: ${a.shiftType}", style = MaterialTheme.typography.bodySmall)
                                        Text("In: ${a.clockIn?.take(16)?.replace("T", " ") ?: "Not In"} • Out: ${a.clockOut?.take(16)?.replace("T", " ") ?: "Not Out"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedDetailItem?.let { item ->
        ReportDetailDialog(
            item = item,
            onDismiss = { selectedDetailItem = null }
        )
    }
}

@Composable
fun ReportDetailDialog(
    item: ReportDetailItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (item.subtitle.isNotBlank()) {
                    Text(item.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (item.timestamp != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Timestamp / Window: ${item.timestamp}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (item.metadata.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item.metadata.forEach { (k, v) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Text("Details / Description:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(item.content, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val shareText = buildString {
                        appendLine("RECORD: ${item.title}")
                        if (item.subtitle.isNotBlank()) appendLine("Type: ${item.subtitle}")
                        if (item.timestamp != null) appendLine("Timestamp: ${item.timestamp}")
                        item.metadata.forEach { (k, v) -> appendLine("$k: $v") }
                        appendLine("Details: ${item.content}")
                    }
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        type = "text/plain"
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Share Record"))
                }
            ) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share Record")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun EmptyReportPlaceholder(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Assessment, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
