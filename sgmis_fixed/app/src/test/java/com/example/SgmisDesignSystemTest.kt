package com.example

import androidx.compose.ui.unit.dp
import com.example.ui.theme.*
import com.example.util.ApiErrorTranslator
import org.junit.Assert.*
import org.junit.Test

class SgmisDesignSystemTest {

    @Test
    fun testOutdoorTouchTargetAccessibilityStandard() {
        val touchTargets = SgmisTouchTarget()
        assertTrue(
            "Outdoor field controls must have a minimum touch target of at least 48dp",
            touchTargets.min >= 48.dp
        )
        assertTrue(
            "Comfortable touch target must be at least 56dp",
            touchTargets.comfortable >= 56.dp
        )
    }

    @Test
    fun testSpacingRhythmGrid() {
        val spacing = SgmisSpacing()
        assertEquals(4.dp, spacing.xs)
        assertEquals(8.dp, spacing.sm)
        assertEquals(12.dp, spacing.md)
        assertEquals(16.dp, spacing.lg)
        assertEquals(24.dp, spacing.xxl)
    }

    @Test
    fun testBrandAndShiftColorsDefined() {
        assertNotNull(NavyDark)
        assertNotNull(GoldAccent)
        assertNotNull(ShiftDayColor)
        assertNotNull(ShiftNightColor)
        assertNotNull(ShiftOffColor)
        assertNotNull(ShiftReliefColor)
        assertNotNull(ShiftLeaveColor)
        assertNotNull(ShiftExamColor)
        assertNotNull(ShiftEscortColor)
    }

    @Test
    fun testApiErrorTranslatorAnswersThreeQuestions() {
        // Test Roster Overlap Error
        val rawRosterError = "Cannot generate roster: An approved DutyRoster already covers the requested generation period"
        val rosterGuidance = ApiErrorTranslator.translate(rawRosterError)
        assertEquals("Roster could not be generated.", rosterGuidance.whatHappened)
        assertTrue(rosterGuidance.whyItHappened.contains("already generated or approved"))
        assertTrue(rosterGuidance.whatCanIDoNow.contains("Review existing rosters"))
        assertEquals("View Rosters", rosterGuidance.actionLabel)

        // Test Geofence Violation Error
        val rawGeofenceError = "Clock-in rejected: Device coordinates outside station geofence perimeter"
        val geofenceGuidance = ApiErrorTranslator.translate(rawGeofenceError)
        assertEquals("Duty clock-in blocked by post security.", geofenceGuidance.whatHappened)
        assertTrue(geofenceGuidance.whyItHappened.contains("outside the authorized station boundary"))
        assertTrue(geofenceGuidance.whatCanIDoNow.contains("move within the post boundary"))

        // Test Leave Conflict Error
        val rawLeaveError = "Guard SEC-001 is on approved leave (LeaveApplication ID 12)"
        val leaveGuidance = ApiErrorTranslator.translate(rawLeaveError)
        assertEquals("Guard is unavailable for assignment.", leaveGuidance.whatHappened)
        assertTrue(leaveGuidance.whyItHappened.contains("approved leave scheduled"))
        assertTrue(leaveGuidance.whatCanIDoNow.contains("Select an available off-duty guard"))

        // Test 403 Forbidden Error
        val rawForbidden = "HTTP 403: You do not have permission to perform this action"
        val forbiddenGuidance = ApiErrorTranslator.translate(rawForbidden)
        assertEquals("Access restricted to authorized personnel.", forbiddenGuidance.whatHappened)
        assertTrue(forbiddenGuidance.whyItHappened.contains("clearance"))

        // Test 401 Session Timeout Error
        val rawAuthError = "HTTP 401 Unauthorized: token expired"
        val authGuidance = ApiErrorTranslator.translate(rawAuthError)
        assertEquals("Security session expired.", authGuidance.whatHappened)
        assertEquals("login", authGuidance.actionRoute)

        // Test Phase 5 Invalid Credentials Error
        val rawInvalidCreds = "HTTP 401: Invalid credentials provided."
        val invalidCredsGuidance = ApiErrorTranslator.translate(rawInvalidCreds)
        assertEquals("Authentication could not be completed.", invalidCredsGuidance.whatHappened)
        assertTrue(invalidCredsGuidance.whyItHappened.contains("does not match system records"))
        assertTrue(invalidCredsGuidance.whatCanIDoNow.contains("re-check your credentials"))

        // Test Phase 5 Required Credentials Error
        val rawRequiredCreds = "Username and password are required."
        val requiredCredsGuidance = ApiErrorTranslator.translate(rawRequiredCreds)
        assertEquals("Credentials required.", requiredCredsGuidance.whatHappened)
        assertTrue(requiredCredsGuidance.whyItHappened.contains("must be entered"))
    }

    @Test
    fun testPhase4RoleNavigationRestrictions() {
        val guard = com.example.data.model.AppRole.GUARD
        val supervisor = com.example.data.model.AppRole.SUPERVISOR
        val admin = com.example.data.model.AppRole.ADMINISTRATOR

        // Guard route allowances
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.GUARD_DASHBOARD, guard))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.GUARD_OPERATIONS_HUB, guard))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.GUARD_SCHEDULE_HUB, guard))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.TODAY_SHIFT, guard))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.SUPERVISOR_DASHBOARD, guard))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.SUPERVISOR_OPERATIONS_HUB, guard))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ADMIN_DASHBOARD, guard))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ADMIN_GOVERNANCE_HUB, guard))

        // Supervisor route allowances
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.SUPERVISOR_DASHBOARD, supervisor))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.SUPERVISOR_OPERATIONS_HUB, supervisor))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.SUPERVISOR_PERSONNEL_HUB, supervisor))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ROSTER_MANAGEMENT, supervisor))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.TODAY_SHIFT, supervisor))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.GUARD_OPERATIONS_HUB, supervisor))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ADMIN_DASHBOARD, supervisor))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ADMIN_HISTORY, supervisor))

        // Administrator route allowances
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ADMIN_DASHBOARD, admin))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ADMIN_GOVERNANCE_HUB, admin))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ADMIN_INFRASTRUCTURE_HUB, admin))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.ADMIN_AUDIT_HUB, admin))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.TODAY_SHIFT, admin))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.GUARD_OPERATIONS_HUB, admin))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAllowed(com.example.ui.navigation.NavRoutes.SUPERVISOR_DASHBOARD, admin))
    }

    @Test
    fun testPhase4GuardDutyLockIntegrity() {
        val guard = com.example.data.model.AppRole.GUARD
        val offDuty = com.example.data.model.GuardDutyState.OFF_DUTY
        val onDuty = com.example.data.model.GuardDutyState.ON_DUTY

        // Off-duty guard can view hubs
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.GUARD_OPERATIONS_HUB, guard, offDuty))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.GUARD_SCHEDULE_HUB, guard, offDuty))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.TODAY_SHIFT, guard, offDuty))

        // Off-duty guard is blocked from operational logs
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.OCCURRENCE_BOOK, guard, offDuty))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.PATROL, guard, offDuty))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.VISITORS, guard, offDuty))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.INCIDENTS, guard, offDuty))
        assertFalse(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.HANDOVER, guard, offDuty))

        // On-duty guard has full access
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.OCCURRENCE_BOOK, guard, onDuty))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.PATROL, guard, onDuty))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.VISITORS, guard, onDuty))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.INCIDENTS, guard, onDuty))
        assertTrue(com.example.ui.navigation.RoleRouter.isRouteAccessible(com.example.ui.navigation.NavRoutes.HANDOVER, guard, onDuty))
    }
}
