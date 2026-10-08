package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.NotificationAlert
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showBroadcastDialog by remember { mutableStateOf(false) }

    val currentUserRole = state.currentUser?.role?.uppercase()
    val isSupervisorOrAdmin = currentUserRole in listOf("SUPERVISOR", "ADMINISTRATOR", "ADMIN")

    LaunchedEffect(Unit) {
        viewModel.fetchNotifications()
    }

    // Auto-dismiss transient messages after 3.5 seconds
    LaunchedEffect(state.successMessage, state.errorMessage) {
        if (state.successMessage != null || state.errorMessage != null) {
            delay(3500)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "Notification Hub",
                subtitle = "Security Communications & Dispatch",
                onNavigationClick = onBack,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("notifications_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NavyDark
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.markAllNotificationsRead() },
                        modifier = Modifier.testTag("read_all_notifications_button")
                    ) {
                        Text("Mark all read", color = NavyDark, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(
                        onClick = { viewModel.fetchNotifications() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("refresh_notifications_button")
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = NavyDark)
                    }
                }
            )
        },
        floatingActionButton = {
            if (isSupervisorOrAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { showBroadcastDialog = true },
                    icon = { Icon(Icons.Default.Campaign, null) },
                    text = { Text("Broadcast Notice", fontWeight = FontWeight.Bold) },
                    containerColor = NavyDark,
                    contentColor = SurfaceCardLight,
                    modifier = Modifier.testTag("broadcast_notice_fab")
                )
            }
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
            // Notification banners
            if (state.successMessage != null) {
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
                            text = state.successMessage!!,
                            color = StatusSuccess,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (state.errorMessage != null) {
                Surface(
                    color = StatusError.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = StatusError, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.errorMessage!!,
                            color = StatusError,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Summary Card
            val unreadCount = state.notifications.count { !it.read }
            SgmisCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "SECURITY COMMUNICATIONS & DISPATCH",
                            style = MaterialTheme.typography.labelSmall,
                            color = NavyDark,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$unreadCount Unread Alerts (${state.notifications.size} Total)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }
                    SgmisBadge(
                        text = if (unreadCount > 0) "$unreadCount NEW" else "ALL READ",
                        variant = if (unreadCount > 0) BadgeVariant.Danger else BadgeVariant.Success
                    )
                }
            }

            var selectedTab by remember { mutableIntStateOf(0) }
            val unreadAlerts = remember(state.notifications) { state.notifications.filter { !it.read } }
            val readAlerts = remember(state.notifications) { state.notifications.filter { it.read } }
            val recentAlerts = remember(state.notifications, unreadAlerts) {
                if (unreadAlerts.isNotEmpty()) unreadAlerts else state.notifications.take(15)
            }
            val archiveAlerts = readAlerts

            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceCardLight,
                contentColor = NavyDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            if (unreadAlerts.isNotEmpty()) "Recent Alerts (${unreadAlerts.size})"
                            else "Recent Alerts",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    icon = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            if (archiveAlerts.isNotEmpty()) "Archive (${archiveAlerts.size})"
                            else "Archive",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    icon = { Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            val displayedAlerts = if (selectedTab == 0) recentAlerts else archiveAlerts

            if (displayedAlerts.isEmpty()) {
                SgmisEmptyState(
                    title = if (selectedTab == 0) "No Active Alerts" else "No Archived Alerts",
                    description = if (selectedTab == 0) "You are completely caught up on all security alerts." else "No read alerts currently in archive.",
                    icon = if (selectedTab == 0) Icons.Outlined.NotificationsNone else Icons.Default.Archive,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    items(displayedAlerts, key = { it.id }) { n ->
                        NotificationCard(
                            notification = n,
                            onMarkRead = { viewModel.markNotificationRead(n.id) },
                            onTriggerWhatsApp = {
                                try {
                                    val text = "Smart Security Alert [${n.title}]: ${n.message}"
                                    val uri = Uri.parse("https://wa.me/?text=${Uri.encode(text)}")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open WhatsApp: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onTriggerCall = {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open dialer: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onTriggerSms = {
                                try {
                                    val text = "Smart Security: ${n.title} - ${n.message}"
                                    val uri = Uri.parse("smsto:?body=${Uri.encode(text)}")
                                    val intent = Intent(Intent.ACTION_SENDTO, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open SMS: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onTriggerEmail = {
                                try {
                                    val uri = Uri.parse("mailto:?subject=${Uri.encode("Smart Security Alert: " + n.title)}&body=${Uri.encode(n.message)}")
                                    val intent = Intent(Intent.ACTION_SENDTO, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open Email: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showBroadcastDialog) {
        BroadcastNoticeDialog(
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
            stations = state.stations.map { it.id to it.name },
            onDismiss = {
                viewModel.clearError()
                showBroadcastDialog = false
            },
            onBroadcast = { title, message, role, stnId ->
                viewModel.broadcastNotice(title, message, role, stnId) {
                    showBroadcastDialog = false
                }
            }
        )
    }
}

@Composable
fun NotificationCard(
    notification: NotificationAlert,
    onMarkRead: () -> Unit,
    onTriggerWhatsApp: () -> Unit,
    onTriggerCall: () -> Unit,
    onTriggerSms: () -> Unit,
    onTriggerEmail: () -> Unit
) {
    SgmisCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onMarkRead() }
            .testTag("notification_card_${notification.id}"),
        backgroundColor = if (!notification.read) NavyDark.copy(alpha = 0.04f) else SurfaceCardLight,
        borderColor = if (!notification.read) NavyDark.copy(alpha = 0.3f) else BorderSubtleLight
    ) {
        Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!notification.read) {
                        SgmisBadge(
                            text = "NEW",
                            variant = BadgeVariant.Danger
                        )
                    }
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )
                }
                Text(
                    text = notification.createdAt?.take(16)?.replace("T", " ") ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondaryLight
                )
            }

            Text(
                text = notification.message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimaryLight
            )

            // Direct Communication Channel Triggers
            HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
            Text("Quick Communication Channels:", style = MaterialTheme.typography.labelSmall, color = TextSecondaryLight)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onTriggerWhatsApp,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                ) {
                    Text("WhatsApp", style = MaterialTheme.typography.labelSmall, color = NavyDark, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onTriggerCall,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                ) {
                    Text("Call", style = MaterialTheme.typography.labelSmall, color = NavyDark, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onTriggerSms,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                ) {
                    Text("SMS", style = MaterialTheme.typography.labelSmall, color = NavyDark, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onTriggerEmail,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                ) {
                    Text("Email", style = MaterialTheme.typography.labelSmall, color = NavyDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastNoticeDialog(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    stations: List<Pair<String, String>> = emptyList(),
    onDismiss: () -> Unit,
    onBroadcast: (title: String, message: String, role: String?, stationId: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf<String?>(null) } // null = ALL
    var selectedStationId by remember { mutableStateOf<String?>(null) }
    var stationDropdownExpanded by remember { mutableStateOf(false) }

    val roles = listOf(
        null to "All Personnel",
        "GUARD" to "Guards Only",
        "SUPERVISOR" to "Supervisors Only"
    )

    val isValid = title.isNotBlank() && message.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Broadcast Security Notice", fontWeight = FontWeight.Bold, color = TextPrimaryLight) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        color = StatusError.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, null, tint = StatusError, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(errorMessage, color = StatusError, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Notice Title *") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("broadcast_title_input")
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message Content *") },
                    minLines = 3,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("broadcast_message_input")
                )

                Text("Target Role Audience:", style = MaterialTheme.typography.labelSmall, color = TextSecondaryLight)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    roles.forEach { (roleKey, label) ->
                        FilterChip(
                            selected = selectedRole == roleKey,
                            onClick = { if (!isLoading) selectedRole = roleKey },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NavyDark,
                                selectedLabelColor = SurfaceCardLight
                            )
                        )
                    }
                }

                Text("Target Station Post:", style = MaterialTheme.typography.labelSmall, color = TextSecondaryLight)
                ExposedDropdownMenuBox(
                    expanded = stationDropdownExpanded,
                    onExpandedChange = { if (!isLoading) stationDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = stations.firstOrNull { it.first == selectedStationId }?.second ?: "All Stations (Company-Wide)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Station Scope") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stationDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = stationDropdownExpanded,
                        onDismissRequest = { stationDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Stations (Company-Wide)") },
                            onClick = {
                                selectedStationId = null
                                stationDropdownExpanded = false
                            }
                        )
                        stations.forEach { (stnId, stnName) ->
                            DropdownMenuItem(
                                text = { Text(stnName) },
                                onClick = {
                                    selectedStationId = stnId
                                    stationDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid && !isLoading) {
                        onBroadcast(title, message, selectedRole, selectedStationId)
                    }
                },
                enabled = isValid && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = NavyDark),
                modifier = Modifier.testTag("submit_broadcast_button").heightIn(min = 48.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = SurfaceCardLight, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Text("Dispatch Broadcast")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel", color = TextSecondaryLight) }
        }
    )
}
