package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.ProfileUpdateRequest
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: SgmisViewModel, onBack: () -> Unit) {
    val user = viewModel.uiState.collectAsState().value.currentUser
    var firstName by remember(user?.firstName) { mutableStateOf(user?.firstName ?: "") }
    var lastName by remember(user?.lastName) { mutableStateOf(user?.lastName ?: "") }
    var phone by remember(user?.phoneNumber) { mutableStateOf(user?.phoneNumber ?: "") }
    var photo by remember(user?.profilePhoto) { mutableStateOf(user?.profilePhoto ?: "") }

    Scaffold(topBar = { TopAppBar(title = { Text("My Profile") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(user?.username ?: "", style = MaterialTheme.typography.titleMedium)
            Text("Employee: ${user?.employeeNumber ?: "—"}  •  ${user?.role ?: "—"}")
            OutlinedTextField(firstName, { firstName = it }, label = { Text("First name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(lastName, { lastName = it }, label = { Text("Last name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(photo, { photo = it }, label = { Text("Profile photo URL") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.updateProfile(ProfileUpdateRequest(firstName, lastName, phone, photo)) }, modifier = Modifier.fillMaxWidth(), enabled = !viewModel.uiState.collectAsState().value.isLoading) { Text("Save Profile") }
        }
    }
}
