package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.api.ApiClient
import com.example.data.api.SessionManager
import com.example.data.local.SgmisDatabase
import com.example.data.model.AppRole
import com.example.data.repository.SgmisRepository
import com.example.ui.components.*
import com.example.ui.navigation.NavRoutes
import com.example.ui.navigation.RoleRouter
import com.example.ui.screens.*
import com.example.ui.viewmodel.SgmisViewModel
import com.example.ui.viewmodel.SgmisViewModelFactory
import com.example.util.ApiErrorTranslator

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
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ""

    // Contextual Error Translation: Convert raw backend exceptions into 3-question guidance
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { rawMsg ->
            val guidance = ApiErrorTranslator.translate(rawMsg)
            val displayMessage = if (guidance.whyItHappened.isNotBlank() && guidance.whatHappened != rawMsg) {
                "${guidance.whatHappened} ${guidance.whyItHappened}"
            } else {
                guidance.whatHappened
            }
            snackbarHostState.showSnackbar(displayMessage)
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

    // Role-specific bottom navigation item definitions
    val bottomNavItems = remember(uiState.appRole) {
        when (uiState.appRole) {
            AppRole.GUARD -> listOf(
                SgmisBottomNavItem(
                    route = NavRoutes.GUARD_DASHBOARD,
                    label = "Home",
                    icon = Icons.Default.Home
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.GUARD_OPERATIONS_HUB,
                    label = "Operations",
                    icon = Icons.Default.Security
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.GUARD_SCHEDULE_HUB,
                    label = "Schedule",
                    icon = Icons.Default.CalendarMonth
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.PROFILE,
                    label = "Profile",
                    icon = Icons.Default.Person
                )
            )
            AppRole.SUPERVISOR -> listOf(
                SgmisBottomNavItem(
                    route = NavRoutes.SUPERVISOR_DASHBOARD,
                    label = "Command",
                    icon = Icons.Default.Dashboard
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.ROSTER_MANAGEMENT,
                    label = "Rosters",
                    icon = Icons.Default.CalendarMonth
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.SUPERVISOR_OPERATIONS_HUB,
                    label = "Operations",
                    icon = Icons.Default.Shield
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.SUPERVISOR_PERSONNEL_HUB,
                    label = "Personnel",
                    icon = Icons.Default.People
                )
            )
            AppRole.ADMINISTRATOR -> listOf(
                SgmisBottomNavItem(
                    route = NavRoutes.ADMIN_DASHBOARD,
                    label = "National",
                    icon = Icons.Default.Radar
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.ADMIN_GOVERNANCE_HUB,
                    label = "Governance",
                    icon = Icons.Default.Gavel
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.ADMIN_INFRASTRUCTURE_HUB,
                    label = "Stations & Users",
                    icon = Icons.Default.Business
                ),
                SgmisBottomNavItem(
                    route = NavRoutes.ADMIN_AUDIT_HUB,
                    label = "Audit",
                    icon = Icons.Default.HistoryEdu
                )
            )
        }
    }

    // Resolves active bottom navigation tab based on current destination
    fun resolveActiveTabRoute(route: String, role: AppRole): String {
        return when (role) {
            AppRole.GUARD -> when (route) {
                NavRoutes.GUARD_DASHBOARD, NavRoutes.DASHBOARD -> NavRoutes.GUARD_DASHBOARD
                NavRoutes.GUARD_OPERATIONS_HUB,
                NavRoutes.OCCURRENCE_BOOK,
                NavRoutes.PATROL,
                NavRoutes.VISITORS,
                NavRoutes.INCIDENTS,
                NavRoutes.HANDOVER,
                NavRoutes.ADDITIONAL_DUTIES,
                NavRoutes.EMERGENCY_SOS -> NavRoutes.GUARD_OPERATIONS_HUB

                NavRoutes.GUARD_SCHEDULE_HUB,
                NavRoutes.TODAY_SHIFT,
                NavRoutes.GUARD_DUTY_PLAN,
                NavRoutes.LEAVE,
                NavRoutes.EXAM_DUTIES,
                NavRoutes.ESCORT_DUTIES -> NavRoutes.GUARD_SCHEDULE_HUB

                NavRoutes.PROFILE,
                NavRoutes.SETTINGS,
                NavRoutes.ABOUT,
                NavRoutes.ORGANIZATION_POLICY,
                NavRoutes.NOTIFICATIONS -> NavRoutes.PROFILE

                else -> route
            }
            AppRole.SUPERVISOR -> when (route) {
                NavRoutes.SUPERVISOR_DASHBOARD, NavRoutes.DASHBOARD -> NavRoutes.SUPERVISOR_DASHBOARD
                NavRoutes.ROSTER_MANAGEMENT -> NavRoutes.ROSTER_MANAGEMENT
                NavRoutes.SUPERVISOR_OPERATIONS_HUB,
                NavRoutes.OCCURRENCE_BOOK,
                NavRoutes.PATROL,
                NavRoutes.VISITORS,
                NavRoutes.INCIDENTS,
                NavRoutes.HANDOVER,
                NavRoutes.ADDITIONAL_DUTIES -> NavRoutes.SUPERVISOR_OPERATIONS_HUB

                NavRoutes.SUPERVISOR_PERSONNEL_HUB,
                NavRoutes.ATTENDANCE_MANAGEMENT,
                NavRoutes.LEAVE,
                NavRoutes.RECORD_ADJUSTMENTS,
                NavRoutes.PROFILE -> NavRoutes.SUPERVISOR_PERSONNEL_HUB

                else -> route
            }
            AppRole.ADMINISTRATOR -> when (route) {
                NavRoutes.ADMIN_DASHBOARD, NavRoutes.DASHBOARD -> NavRoutes.ADMIN_DASHBOARD
                NavRoutes.ADMIN_GOVERNANCE_HUB,
                NavRoutes.ROSTER_MANAGEMENT,
                NavRoutes.REPORTS,
                NavRoutes.ORGANIZATION_POLICY -> NavRoutes.ADMIN_GOVERNANCE_HUB

                NavRoutes.ADMIN_INFRASTRUCTURE_HUB,
                NavRoutes.STATION_MANAGEMENT,
                NavRoutes.USER_MANAGEMENT -> NavRoutes.ADMIN_INFRASTRUCTURE_HUB

                NavRoutes.ADMIN_AUDIT_HUB,
                NavRoutes.ADMIN_HISTORY,
                NavRoutes.RECORD_ADJUSTMENTS,
                NavRoutes.ADMIN_MASTER_TOOLS -> NavRoutes.ADMIN_AUDIT_HUB

                else -> route
            }
        }
    }

    val activeTabRoute = resolveActiveTabRoute(currentRoute, uiState.appRole)

    val onTabSelected: (String) -> Unit = { targetRoute ->
        if (currentRoute != targetRoute) {
            navController.navigate(targetRoute) {
                popUpTo(RoleRouter.getDashboardRoute(uiState.appRole)) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val showBottomBar = uiState.isLoggedIn && currentRoute != NavRoutes.LOGIN

    SgmisAppShell(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                SgmisBottomNavigationBar(
                    items = bottomNavItems,
                    currentRoute = activeTabRoute,
                    onItemSelected = onTabSelected
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (uiState.isLoggedIn) RoleRouter.getDashboardRoute(uiState.appRole) else NavRoutes.LOGIN,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
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

            // Secondary Navigation Hubs
            composable(NavRoutes.GUARD_OPERATIONS_HUB) {
                GuardOperationsHubScreen(viewModel = viewModel, onNavigate = safeNavigate)
            }
            composable(NavRoutes.GUARD_SCHEDULE_HUB) {
                GuardScheduleHubScreen(viewModel = viewModel, onNavigate = safeNavigate)
            }
            composable(NavRoutes.SUPERVISOR_OPERATIONS_HUB) {
                SupervisorOperationsHubScreen(viewModel = viewModel, onNavigate = safeNavigate)
            }
            composable(NavRoutes.SUPERVISOR_PERSONNEL_HUB) {
                SupervisorPersonnelHubScreen(viewModel = viewModel, onNavigate = safeNavigate)
            }
            composable(NavRoutes.ADMIN_GOVERNANCE_HUB) {
                AdminGovernanceHubScreen(viewModel = viewModel, onNavigate = safeNavigate)
            }
            composable(NavRoutes.ADMIN_INFRASTRUCTURE_HUB) {
                AdminInfrastructureHubScreen(viewModel = viewModel, onNavigate = safeNavigate)
            }
            composable(NavRoutes.ADMIN_AUDIT_HUB) {
                AdminAuditHubScreen(viewModel = viewModel, onNavigate = safeNavigate)
            }

            // Preserved Operational & Feature Routes
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
                SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, onNavigate = safeNavigate)
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
            composable("about") {
                AboutAppScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("guard_duty_plan") {
                GuardDutyPlanScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable("attendance_management") {
                AttendanceManagementScreen(viewModel = viewModel) { navController.popBackStack() }
            }
            composable(NavRoutes.ORGANIZATION_POLICY) {
                OrganizationPolicyScreen(viewModel = viewModel) { navController.popBackStack() }
            }
        }

        // App Lock Overlay: Non-destructive 3-minute inactivity protection
        if (uiState.isLoggedIn && uiState.isAppLocked) {
            AppLockOverlay(viewModel = viewModel)
        }
    }
}
