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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.example.data.model.AppRole
import com.example.ui.navigation.NavRoutes
import com.example.ui.navigation.RoleRouter
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

    override fun onResume() {
        super.onResume()
        // Authoritative State Refresh: re-sync shifts, attendance & station state on app resume
        viewModelRef?.refreshAuthoritativeState()
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
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    val safeNavigate: (String) -> Unit = { route ->
        if (!RoleRouter.isRouteAllowed(route, uiState.appRole)) {
            viewModel.postSecurityAlert("Access Denied: You do not have permission to access this module.")
        } else if (!RoleRouter.isRouteAccessible(route, uiState.appRole, uiState.guardDutyState)) {
            viewModel.postSecurityAlert("Duty Lock: You must be CLOCKED IN (On Duty) to access operational records.")
        } else {
            navController.navigate(route)
        }
    }

    // React to login/logout state changes and route to role-authoritative dashboard
    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            val targetRoute = RoleRouter.getDashboardRoute(uiState.appRole)
            navController.navigate(targetRoute) {
                popUpTo(NavRoutes.LOGIN) { inclusive = true }
            }
        } else {
            navController.navigate(NavRoutes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (uiState.isLoggedIn) RoleRouter.getDashboardRoute(uiState.appRole) else NavRoutes.LOGIN,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(NavRoutes.LOGIN) {
                LoginScreen(viewModel = viewModel)
            }
            composable(NavRoutes.DASHBOARD) {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigate = safeNavigate
                )
            }
            composable(NavRoutes.GUARD_DASHBOARD) {
                DashboardScreen(
                    viewModel = viewModel,
                    roleMode = AppRole.GUARD,
                    onNavigate = safeNavigate
                )
            }
            composable(NavRoutes.SUPERVISOR_DASHBOARD) {
                DashboardScreen(
                    viewModel = viewModel,
                    roleMode = AppRole.SUPERVISOR,
                    onNavigate = safeNavigate
                )
            }
            composable(NavRoutes.ADMIN_DASHBOARD) {
                DashboardScreen(
                    viewModel = viewModel,
                    roleMode = AppRole.ADMINISTRATOR,
                    onNavigate = safeNavigate
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
            composable("escort_duties") {
                EscortDutiesScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("exam_duties") {
                ExamDutiesScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("record_adjustments") {
                RecordAdjustmentsScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable(NavRoutes.ADMIN_HISTORY) {
                AdministrativeHistoryScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable(NavRoutes.ADMIN_MASTER_TOOLS) {
                AdminMasterToolsScreen(viewModel = viewModel) { navController.popBackStack() }
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
