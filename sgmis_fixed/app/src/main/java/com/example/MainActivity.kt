package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.api.ApiClient
import com.example.data.api.SessionManager
import com.example.data.local.SgmisDatabase
import com.example.data.repository.SgmisRepository
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.SgmisViewModel
import com.example.ui.viewmodel.SgmisViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = SgmisDatabase.getDatabase(applicationContext)
        val sessionManager = SessionManager(applicationContext)
        val apiClient = ApiClient(sessionManager)
        val repository = SgmisRepository(apiClient, sessionManager, database)
        val factory = SgmisViewModelFactory(repository)

        setContent {
            MyApplicationTheme {
                val vm: SgmisViewModel = viewModel(factory = factory)
                SgmisApp(viewModel = vm)
            }
        }
    }
}

@Composable
fun SgmisApp(viewModel: SgmisViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val navController = rememberNavController()

    // React to login/logout state changes
    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            navController.navigate("dashboard") {
                popUpTo("login") { inclusive = true }
            }
        } else {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (uiState.isLoggedIn) "dashboard" else "login",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("login") {
                LoginScreen(viewModel = viewModel)
            }
            composable("dashboard") {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable("today_shift") {
                TodayShiftScreen(viewModel = viewModel)
            }
            composable("handover") {
                HandoverScreen(viewModel = viewModel)
            }
            composable("occurrence_book") {
                OccurrenceBookScreen(viewModel = viewModel)
            }
            composable("incidents") {
                IncidentReportScreen(viewModel = viewModel)
            }
            composable("patrol") {
                PatrolScreen(viewModel = viewModel)
            }
            composable("leave") {
                LeaveScreen(viewModel = viewModel)
            }
            composable("additional_duties") {
                AdditionalDutiesScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("notifications") {
                NotificationsScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("profile") {
                ProfileScreen(viewModel = viewModel) { navController.popBackStack() }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(text = "Hello $name!", modifier = modifier)
}

