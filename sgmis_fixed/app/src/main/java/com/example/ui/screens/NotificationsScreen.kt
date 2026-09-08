package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(viewModel: SgmisViewModel, onBack: () -> Unit) {
    val state = viewModel.uiState.collectAsState().value
    Scaffold(topBar = { TopAppBar(title = { Text("Notifications") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { TextButton(onClick = viewModel::markAllNotificationsRead) { Text("Read all") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.notifications, key = { it.id }) { n ->
                Card(Modifier.fillMaxWidth().clickable { viewModel.markNotificationRead(n.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(n.title, style = MaterialTheme.typography.titleMedium)
                        Text(n.message, style = MaterialTheme.typography.bodyMedium)
                        Text(if (n.read) "Read" else "Unread", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (state.notifications.isEmpty()) item { Text("No notifications.") }
        }
    }
}
