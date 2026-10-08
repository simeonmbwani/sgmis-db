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
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.*
import com.example.ui.theme.*
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
            SgmisTopAppBar(
                title = stringResource(R.string.reports_title),
                subtitle = if (isGuard) "Station Assigned Records" else "Authoritative Company Ledger",
                onNavigationClick = onBack,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("reports_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = NavyDark
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
                                        appendLine("Started: ${it.startTime?.take(16)?.replace("T", " ") ?: "Not started"} | Scans: ${it.scansCount}")
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
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("export_reports_button")
                    ) {
                        Icon(Icons.Default.Share, "Export / Share Report", tint = NavyDark)
                    }
                    IconButton(
                        onClick = { viewModel.refreshAllData() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("refresh_reports_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = NavyDark)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(LightBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Category Filter Chips
            ScrollableTabRow(
                selectedTabIndex = categories.indexOfFirst { it.first == selectedCategory }.coerceAtLeast(0),
                edgePadding = 0.dp,
                containerColor = SurfaceCardLight,
                contentColor = NavyDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                categories.forEach { (catKey, catLabel) ->
                    val selected = selectedCategory == catKey
                    Tab(
                        selected = selected,
                        onClick = { selectedCategory = catKey },
                        text = {
                            Text(
                                catLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) NavyDark else TextSecondaryLight
                            )
                        }
                    )
                }
            }

            // Summary Stats Card with Role Scope Indication (interactive)
            SgmisCard(
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
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (isGuard) "AUTHORITATIVE GUARD RECORDS (YOUR STATION)" else "AUTHORITATIVE COMPANY-WIDE AUDIT RECORDS",
                            style = MaterialTheme.typography.labelSmall,
                            color = NavyDark,
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
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "View Details",
                        tint = TextSecondaryLight
                    )
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
                                SgmisCard(
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
                                    Column(
                                        modifier = Modifier.padding(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                entry.entryNumber,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = NavyDark
                                            )
                                            Text(
                                                entry.createdAt.take(16).replace("T", " "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondaryLight
                                            )
                                        }
                                        Text(
                                            entry.occurrenceText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextPrimaryLight
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Post: ${entry.stationName} • Guard: ${entry.guardName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondaryLight
                                            )
                                            if (!entry.crossReference.isNullOrBlank()) {
                                                SgmisBadge(
                                                    text = "CR: ${entry.crossReference}",
                                                    variant = BadgeVariant.Info
                                                )
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
                                val badgeVariant = when (inc.priority.uppercase()) {
                                    "CRITICAL", "HIGH" -> BadgeVariant.Danger
                                    "MEDIUM" -> BadgeVariant.Warning
                                    else -> BadgeVariant.Info
                                }
                                SgmisCard(
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
                                    Column(
                                        modifier = Modifier.padding(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                inc.title,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = TextPrimaryLight
                                            )
                                            SgmisBadge(
                                                text = inc.priority,
                                                variant = badgeVariant
                                            )
                                        }
                                        Text(
                                            inc.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimaryLight
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Post: ${inc.stationName} • Guard: ${inc.reportingGuardName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondaryLight
                                            )
                                            SgmisBadge(
                                                text = inc.status,
                                                variant = if (inc.status.equals("RESOLVED", true)) BadgeVariant.Success else BadgeVariant.Neutral
                                            )
                                        }
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
                                SgmisCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDetailItem = ReportDetailItem(
                                                title = "Patrol at ${p.stationName}",
                                                subtitle = "Status: ${p.status}",
                                                timestamp = p.startTime?.take(19)?.replace("T", " ") ?: "Not started",
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
                                    Column(
                                        modifier = Modifier.padding(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                p.stationName,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = TextPrimaryLight
                                            )
                                            SgmisBadge(
                                                text = p.status,
                                                variant = if (p.status.equals("COMPLETED", true)) BadgeVariant.Success else BadgeVariant.Warning
                                            )
                                        }
                                        Text(
                                            "Officer: ${p.guardName} • Scans: ${p.scansCount}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimaryLight
                                        )
                                        Text(
                                            "Started: ${p.startTime?.take(16)?.replace("T", " ") ?: "Not started"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondaryLight
                                        )
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
                                SgmisCard(
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
                                    Column(
                                        modifier = Modifier.padding(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                v.entryNumber,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = NavyDark
                                            )
                                            Text(
                                                v.createdAt.take(16).replace("T", " "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondaryLight
                                            )
                                        }
                                        Text(
                                            v.occurrenceText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimaryLight
                                        )
                                        Text(
                                            "Post: ${v.stationName} • Logged by: ${v.guardName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondaryLight
                                        )
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
                                val statusText = l.statusDisplay ?: l.status
                                val variant = when (statusText.uppercase()) {
                                    "APPROVED" -> BadgeVariant.Success
                                    "REJECTED" -> BadgeVariant.Danger
                                    else -> BadgeVariant.Warning
                                }
                                SgmisCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDetailItem = ReportDetailItem(
                                                title = "Leave Request: ${l.guardName}",
                                                subtitle = "${l.leaveTypeDisplay ?: l.leaveType} ($statusText)",
                                                timestamp = "${l.startDate} to ${l.endDate}",
                                                metadata = listOf(
                                                    "Applicant" to l.guardName,
                                                    "Leave Type" to (l.leaveTypeDisplay ?: l.leaveType),
                                                    "Duration" to "${l.startDate} to ${l.endDate}",
                                                    "Status" to statusText,
                                                    "Emergency Phone" to (l.emergencyPhone ?: "N/A"),
                                                    "Emergency Address" to (l.emergencyAddress ?: "N/A"),
                                                    "Reviewer" to (l.reviewerName ?: "Pending"),
                                                    "Review Notes" to (l.reviewerNotes ?: "None")
                                                ),
                                                content = l.reason
                                            )
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${l.leaveTypeDisplay ?: l.leaveType} (${l.guardName})",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = TextPrimaryLight
                                            )
                                            SgmisBadge(
                                                text = statusText,
                                                variant = variant
                                            )
                                        }
                                        Text(
                                            "${l.startDate} to ${l.endDate}: ${l.reason}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimaryLight
                                        )
                                        if (!l.reviewerNotes.isNullOrBlank()) {
                                            Text(
                                                "Reviewer: ${l.reviewerName ?: "Supervisor"} - ${l.reviewerNotes}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NavyDark,
                                                fontWeight = FontWeight.Medium
                                            )
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
                                SgmisCard(
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
                                    Column(
                                        modifier = Modifier.padding(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${a.guardName} (${a.guardEmployeeNumber ?: ""})",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = TextPrimaryLight
                                            )
                                            SgmisBadge(
                                                text = a.shiftDate,
                                                variant = BadgeVariant.Info
                                            )
                                        }
                                        Text(
                                            "Station: ${a.stationName} • Shift: ${a.shiftType}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimaryLight
                                        )
                                        Text(
                                            "In: ${a.clockIn?.take(16)?.replace("T", " ") ?: "Not In"} • Out: ${a.clockOut?.take(16)?.replace("T", " ") ?: "Not Out"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondaryLight
                                        )
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                if (item.subtitle.isNotBlank()) {
                    Text(item.subtitle, style = MaterialTheme.typography.labelMedium, color = NavyDark, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (item.timestamp != null) {
                    Surface(
                        color = NavyDark.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Timestamp / Window: ${item.timestamp}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = NavyDark,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (item.metadata.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCardLight, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item.metadata.forEach { (k, v) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(k, style = MaterialTheme.typography.labelSmall, color = TextSecondaryLight)
                                Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = TextPrimaryLight)
                            }
                        }
                    }
                }
                Text("Details / Description:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                Text(item.content, style = MaterialTheme.typography.bodyMedium, color = TextPrimaryLight)
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
                },
                colors = ButtonDefaults.buttonColors(containerColor = NavyDark)
            ) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share Record")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = TextSecondaryLight) }
        }
    )
}

@Composable
fun EmptyReportPlaceholder(message: String) {
    SgmisEmptyState(
        title = "No Records Found",
        description = message,
        icon = Icons.Outlined.Assessment,
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
    )
}
