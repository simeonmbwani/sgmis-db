package com.example.ui.screens

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
fun AdditionalDutiesScreen(viewModel: SgmisViewModel, onBack: () -> Unit) {
    val state = viewModel.uiState.collectAsState().value
    Scaffold(topBar = { TopAppBar(title = { Text("Escort & Exam Duties") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Management Escorts", style = MaterialTheme.typography.titleLarge) }
            items(state.escortDuties, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(d.missionName, style = MaterialTheme.typography.titleMedium)
                    Text("${d.origin} → ${d.destination}")
                    Text("${d.startTime} – ${d.endTime}")
                    Text(d.statusDisplay ?: d.status)
                    if (!d.notes.isNullOrBlank()) Text(d.notes!!)
                } }
            }
            item { Text("Exam Escort Duties", style = MaterialTheme.typography.titleLarge) }
            items(state.examDuties, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(d.examTitle, style = MaterialTheme.typography.titleMedium)
                    Text(d.institution)
                    Text("${d.date}  ${d.startTime} – ${d.endTime}")
                    Text(d.status)
                    if (!d.notes.isNullOrBlank()) Text(d.notes!!)
                } }
            }
            if (state.escortDuties.isEmpty() && state.examDuties.isEmpty()) item { Text("No additional duties assigned.") }
        }
    }
}
