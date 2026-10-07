package com.example.ui.navigation

import com.example.data.model.AppRole
import com.example.data.model.GuardDutyState
import com.example.data.model.Shift

/**
 * Centralized registry of application navigation routes.
 */
object NavRoutes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val GUARD_DASHBOARD = "guard_dashboard"
    const val SUPERVISOR_DASHBOARD = "supervisor_dashboard"
    const val ADMIN_DASHBOARD = "admin_dashboard"

    // Guard & Operational Routes
    const val TODAY_SHIFT = "today_shift"
    const val HANDOVER = "handover"
    const val OCCURRENCE_BOOK = "occurrence_book"
    const val INCIDENTS = "incidents"
    const val PATROL = "patrol"
    const val LEAVE = "leave"
    const val VISITORS = "visitors"
    const val VISITOR_BOOK = "visitors"
    const val EMERGENCY_SOS = "emergency_sos"
    const val SOS = "emergency_sos"
    const val GUARD_DUTY_PLAN = "guard_duty_plan"
    const val MY_ROSTER = "guard_duty_plan"

    // Common Non-Operational Routes (accessible off-duty)
    const val NOTIFICATIONS = "notifications"
    const val MESSAGES = "messages"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val ABOUT = "about"

    // Supervisory & Administrative Management Routes
    const val ATTENDANCE_MANAGEMENT = "attendance_management"
    const val ROSTER_MANAGEMENT = "roster"
    const val USER_MANAGEMENT = "users"
    const val STATION_MANAGEMENT = "stations"
    const val REPORTS = "reports"
    const val ADDITIONAL_DUTIES = "additional_duties"
    const val ESCORT_DUTIES = "escort_duties"
    const val EXAM_DUTIES = "exam_duties"
    const val RECORD_ADJUSTMENTS = "record_adjustments"
    const val ADMIN_HISTORY = "admin_history"
    const val ADMIN_MASTER_TOOLS = "admin_master_tools"
    const val ORGANIZATION_POLICY = "organization_policy"
}

/**
 * Centralized role-based routing and client access control.
 * The server remains the ultimate authoritative security layer.
 */
object RoleRouter {

    val OPERATIONAL_ROUTES = setOf(
        NavRoutes.OCCURRENCE_BOOK,
        NavRoutes.PATROL,
        NavRoutes.HANDOVER,
        NavRoutes.VISITORS,
        NavRoutes.INCIDENTS,
        NavRoutes.EMERGENCY_SOS,
        NavRoutes.ADDITIONAL_DUTIES
    )

    /**
     * Checks if a route constitutes a live operational security action.
     */
    fun isOperationalRoute(route: String): Boolean = route in OPERATIONAL_ROUTES

    /**
     * Resolves the primary dashboard route based strictly on the authenticated server role.
     */
    fun getDashboardRoute(role: AppRole): String {
        return when (role) {
            AppRole.GUARD -> NavRoutes.GUARD_DASHBOARD
            AppRole.SUPERVISOR -> NavRoutes.SUPERVISOR_DASHBOARD
            AppRole.ADMINISTRATOR -> NavRoutes.ADMIN_DASHBOARD
        }
    }

    /**
     * Client-side access guard to prevent privilege leaks.
     * Prevents guards or supervisors from accessing unauthorized management routes.
     */
    fun isRouteAllowed(route: String, role: AppRole): Boolean {
        return when (role) {
            AppRole.ADMINISTRATOR -> route !in listOf(
                // National oversight does not grant guard execution authority.
                NavRoutes.TODAY_SHIFT,
                NavRoutes.HANDOVER,
                NavRoutes.EMERGENCY_SOS,
                NavRoutes.GUARD_DUTY_PLAN,
                NavRoutes.GUARD_DASHBOARD
            )
            AppRole.SUPERVISOR -> {
                // Supervisors focus on supervisory command, roster management, attendance/override,
                // inspection, verification, and station oversight (including patrol oversight & assignment).
                // Guard-only operational execution routes (shift handover submission,
                // guard today shift, guard panic SOS) and admin-only routes are disallowed.
                route !in listOf(
                    NavRoutes.HANDOVER,
                    NavRoutes.TODAY_SHIFT,
                    NavRoutes.EMERGENCY_SOS,
                    NavRoutes.GUARD_DUTY_PLAN,
                    NavRoutes.GUARD_DASHBOARD,
                    NavRoutes.STATION_MANAGEMENT,
                    NavRoutes.ADMIN_DASHBOARD,
                    NavRoutes.ADMIN_HISTORY,
                    NavRoutes.ADMIN_MASTER_TOOLS
                )
            }
            AppRole.GUARD -> {
                // Guards can only access guard application routes and common non-operational routes.
                // Strictly barred from administrative and supervisory console routes.
                route in listOf(
                    NavRoutes.LOGIN,
                    NavRoutes.DASHBOARD,
                    NavRoutes.GUARD_DASHBOARD,
                    NavRoutes.TODAY_SHIFT,
                    NavRoutes.HANDOVER,
                    NavRoutes.OCCURRENCE_BOOK,
                    NavRoutes.INCIDENTS,
                    NavRoutes.PATROL,
                    NavRoutes.LEAVE,
                    NavRoutes.VISITORS,
                    NavRoutes.ADDITIONAL_DUTIES,
                    NavRoutes.ESCORT_DUTIES,
                    NavRoutes.EXAM_DUTIES,
                    NavRoutes.EMERGENCY_SOS,
                    NavRoutes.GUARD_DUTY_PLAN,
                    NavRoutes.NOTIFICATIONS,
                    NavRoutes.MESSAGES,
                    NavRoutes.SETTINGS,
                    NavRoutes.PROFILE,
                    NavRoutes.ABOUT,
                    NavRoutes.ORGANIZATION_POLICY
                )
            }
        }
    }

    /**
     * Complete navigation gate verifying both role permission AND guard duty-state.
     * Off-duty guards cannot navigate into operational logging modules until clocked in.
     */
    fun isRouteAccessible(route: String, role: AppRole, dutyState: GuardDutyState): Boolean {
        if (!isRouteAllowed(route, role)) return false
        if (role == AppRole.GUARD && isOperationalRoute(route) && dutyState != GuardDutyState.ON_DUTY && !dutyState.isSpecialDuty) {
            return false
        }
        return true
    }

    /**
     * Checks if a user is permitted to perform live operational events.
     * Guards must be ON_DUTY (clocked in) or on authorized SPECIAL_DUTY (Exam/Escort) to submit live operational records.
     * Non-guards (Supervisors/Admins) are not subject to guard duty-lockout.
     */
    fun canPerformLiveOperation(role: AppRole, dutyState: GuardDutyState): Boolean {
        if (role != AppRole.GUARD) return true
        return dutyState == GuardDutyState.ON_DUTY || dutyState.isSpecialDuty
    }
}
