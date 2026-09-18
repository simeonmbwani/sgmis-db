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
import android.view.WindowManager
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.SgmisViewModel
import com.example.ui.viewmodel.SgmisViewModelFactory

class MainActivity : ComponentActivity() {
    private var viewModelRef: SgmisViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen Protection: Enforce FLAG_SECURE window flags to prevent sensitive operational data exposure
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()

        val database = SgmisDatabase.getDatabase(applicationContext)
        val sessionManager = SessionManager(applicationContext)
        val apiClient = ApiClient(sessionManager)
        val repository = SgmisRepository(apiClient, sessionManager, database)
        val factory = SgmisViewModelFactory(repository)

        setContent {
            val vm: SgmisViewModel = viewModel(factory = factory)
            viewModelRef = vm
            val uiState by vm.uiState.collectAsState()
            com.example.ui.theme.SmartSecurityTheme(themeMode = uiState.themeMode) {
                SgmisApp(viewModel = vm)
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Signal user interaction to reset 3-minute idle inactivity auto-logout timer
        viewModelRef?.onUserInteraction()
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
                TodayShiftScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("handover") {
                HandoverScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("occurrence_book") {
                OccurrenceBookScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("incidents") {
                IncidentReportScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("patrol") {
                PatrolScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("leave") {
                LeaveScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("visitors") {
                VisitorScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("additional_duties") {
                AdditionalDutiesScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("reports") {
                ReportsScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("emergency_sos") {
                EmergencySosScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("users") {
                UserManagementScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("stations") {
                StationManagementScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("roster") {
                RosterManagementScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("notifications") {
                NotificationsScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("profile") {
                ProfileScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("guard_duty_plan") {
                GuardDutyPlanScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("attendance_management") {
                AttendanceManagementScreen(viewModel = viewModel) { navController.popBackStack() }
            }
        }

        // App Lock Overlay: Non-destructive 3-minute inactivity protection
        if (uiState.isLoggedIn && uiState.isAppLocked) {
            AppLockOverlay(viewModel = viewModel)
        }
    }
}

