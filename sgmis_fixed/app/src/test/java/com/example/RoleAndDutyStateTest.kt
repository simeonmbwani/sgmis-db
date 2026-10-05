package com.example

import com.example.data.model.*
import com.example.ui.navigation.NavRoutes
import com.example.ui.navigation.RoleRouter
import com.example.ui.screens.*
import org.junit.Assert.*
import org.junit.Test

class RoleAndDutyStateTest {

    // ==========================================
    // 1. Role Parsing & Defaults
    // ==========================================

    @Test
    fun testAppRoleFromString_handlesAdministratorVariants() {
        assertEquals(AppRole.ADMINISTRATOR, AppRole.fromString("ADMINISTRATOR"))
        assertEquals(AppRole.ADMINISTRATOR, AppRole.fromString("administrator"))
        assertEquals(AppRole.ADMINISTRATOR, AppRole.fromString("ADMIN"))
        assertEquals(AppRole.ADMINISTRATOR, AppRole.fromString("admin"))
        assertEquals(AppRole.ADMINISTRATOR, AppRole.fromString("SUPERUSER"))
        assertEquals(AppRole.ADMINISTRATOR, AppRole.fromString("superuser"))
    }

    @Test
    fun testAppRoleFromString_handlesSupervisor() {
        assertEquals(AppRole.SUPERVISOR, AppRole.fromString("SUPERVISOR"))
        assertEquals(AppRole.SUPERVISOR, AppRole.fromString("supervisor"))
        assertEquals(AppRole.SUPERVISOR, AppRole.fromString("STATION_SUPERVISOR"))
    }

    @Test
    fun testAppRoleFromString_handlesGuardAndFallbacks() {
        assertEquals(AppRole.GUARD, AppRole.fromString("GUARD"))
        assertEquals(AppRole.GUARD, AppRole.fromString("guard"))
        assertEquals(AppRole.GUARD, AppRole.fromString("SECURITY_GUARD"))
        assertEquals(AppRole.GUARD, AppRole.fromString("SECURITY GUARD"))
        assertEquals(AppRole.GUARD, AppRole.fromString("OFFICER"))
        assertEquals(AppRole.GUARD, AppRole.fromString(null))
        assertEquals(AppRole.GUARD, AppRole.fromString(""))
        assertEquals(AppRole.GUARD, AppRole.fromString("UNKNOWN_ROLE"))
    }

    // ==========================================
    // 2. User Extension Properties
    // ==========================================

    @Test
    fun testUserRoleExtensions() {
        val adminUser = User(
            id = "1",
            username = "admin1",
            email = "admin@example.com",
            role = "ADMINISTRATOR"
        )
        assertEquals(AppRole.ADMINISTRATOR, adminUser.appRole)
        assertTrue(adminUser.isAdmin)
        assertFalse(adminUser.isSupervisor)
        assertFalse(adminUser.isGuard)
        assertTrue(adminUser.isSupervisorOrAdmin)

        val supervisorUser = User(
            id = "2",
            username = "supervisor1",
            email = "sup@example.com",
            role = "SUPERVISOR"
        )
        assertEquals(AppRole.SUPERVISOR, supervisorUser.appRole)
        assertFalse(supervisorUser.isAdmin)
        assertTrue(supervisorUser.isSupervisor)
        assertFalse(supervisorUser.isGuard)
        assertTrue(supervisorUser.isSupervisorOrAdmin)

        val guardUser = User(
            id = "3",
            username = "guard1",
            email = "guard@example.com",
            role = "GUARD"
        )
        assertEquals(AppRole.GUARD, guardUser.appRole)
        assertFalse(guardUser.isAdmin)
        assertFalse(guardUser.isSupervisor)
        assertTrue(guardUser.isGuard)
        assertFalse(guardUser.isSupervisorOrAdmin)
    }

    // ==========================================
    // 3. Server-Authoritative Guard Duty State Transitions
    // ==========================================

    private fun createShift(
        shiftType: String = "DAY",
        assignmentType: String = "REGULAR",
        attendanceStatus: String = "NOT_CLOCKED_IN"
    ): Shift {
        return Shift(
            id = "shift-001",
            date = "2026-09-26",
            shiftType = shiftType,
            assignmentType = assignmentType,
            station = "station-001",
            stationName = "Station Alpha",
            startTime = "06:00",
            endTime = "18:00",
            guard = "guard-001",
            guardName = "John Doe",
            employeeNumber = "SEC-101",
            attendanceStatus = attendanceStatus
        )
    }

    @Test
    fun testDutyState_whenShiftIsNull_isOffDuty() {
        assertEquals(GuardDutyState.OFF_DUTY, GuardDutyState.fromShift(null))
    }

    @Test
    fun testDutyState_whenShiftTypeIsOff_isOffDuty() {
        val shift = createShift(shiftType = "OFF")
        assertEquals(GuardDutyState.OFF_DUTY, GuardDutyState.fromShift(shift))
        assertTrue(shift.isOffDuty)
        assertFalse(shift.isOnDuty)
        assertFalse(shift.isEligibleForDuty)
    }

    @Test
    fun testDutyState_whenAssignmentIsTimeOff_isOffDuty() {
        val shift = createShift(assignmentType = "TIME_OFF")
        assertEquals(GuardDutyState.OFF_DUTY, GuardDutyState.fromShift(shift))
    }

    @Test
    fun testDutyState_whenClockedIn_isOnDuty() {
        val shift = createShift(attendanceStatus = "CLOCKED_IN")
        assertEquals(GuardDutyState.ON_DUTY, GuardDutyState.fromShift(shift))
        assertTrue(shift.isOnDuty)
        assertFalse(shift.isOffDuty)
        assertFalse(shift.isEligibleForDuty)
    }

    @Test
    fun testDutyState_whenNotClockedIn_isEligibleForDuty() {
        val shift = createShift(attendanceStatus = "NOT_CLOCKED_IN")
        assertEquals(GuardDutyState.ELIGIBLE_FOR_DUTY, GuardDutyState.fromShift(shift))
        assertTrue(shift.isEligibleForDuty)
        assertFalse(shift.isOnDuty)
        assertFalse(shift.isOffDuty)
    }

    @Test
    fun testDutyState_whenClockedOutOrOffDuty_isOffDuty() {
        val shiftClockedOut = createShift(attendanceStatus = "CLOCKED_OUT")
        assertEquals(GuardDutyState.OFF_DUTY, GuardDutyState.fromShift(shiftClockedOut))

        val shiftOffDuty = createShift(attendanceStatus = "OFF_DUTY")
        assertEquals(GuardDutyState.OFF_DUTY, GuardDutyState.fromShift(shiftOffDuty))

        val shiftAbsent = createShift(attendanceStatus = "ABSENT")
        assertEquals(GuardDutyState.OFF_DUTY, GuardDutyState.fromShift(shiftAbsent))
    }

    // ==========================================
    // 4. Role-Based Route Resolution & Access Control
    // ==========================================

    @Test
    fun testRoleRouter_getDashboardRoute() {
        assertEquals(NavRoutes.GUARD_DASHBOARD, RoleRouter.getDashboardRoute(AppRole.GUARD))
        assertEquals(NavRoutes.SUPERVISOR_DASHBOARD, RoleRouter.getDashboardRoute(AppRole.SUPERVISOR))
        assertEquals(NavRoutes.ADMIN_DASHBOARD, RoleRouter.getDashboardRoute(AppRole.ADMINISTRATOR))
    }

    @Test
    fun testRoleRouter_guardRouteAccessControl() {
        // Allowed guard routes
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.TODAY_SHIFT, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.OCCURRENCE_BOOK, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.PATROL, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.HANDOVER, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.VISITORS, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.INCIDENTS, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.EMERGENCY_SOS, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.GUARD_DUTY_PLAN, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.NOTIFICATIONS, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.PROFILE, AppRole.GUARD))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.SETTINGS, AppRole.GUARD))

        // Disallowed administrative / supervisory routes for Guard
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.ATTENDANCE_MANAGEMENT, AppRole.GUARD))
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.ROSTER_MANAGEMENT, AppRole.GUARD))
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.USER_MANAGEMENT, AppRole.GUARD))
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.STATION_MANAGEMENT, AppRole.GUARD))
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.ADMIN_DASHBOARD, AppRole.GUARD))
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.SUPERVISOR_DASHBOARD, AppRole.GUARD))
    }

    @Test
    fun testRoleRouter_supervisorRouteAccessControl() {
        // Supervisors can manage attendance, roster, users, view OB, patrol oversight, etc.
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.ATTENDANCE_MANAGEMENT, AppRole.SUPERVISOR))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.ROSTER_MANAGEMENT, AppRole.SUPERVISOR))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.USER_MANAGEMENT, AppRole.SUPERVISOR))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.OCCURRENCE_BOOK, AppRole.SUPERVISOR))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.PATROL, AppRole.SUPERVISOR))

        // Disallowed admin-only routes
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.STATION_MANAGEMENT, AppRole.SUPERVISOR))
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.ADMIN_DASHBOARD, AppRole.SUPERVISOR))

        // Disallowed guard-only operational execution routes
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.HANDOVER, AppRole.SUPERVISOR))
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.TODAY_SHIFT, AppRole.SUPERVISOR))
        assertFalse(RoleRouter.isRouteAllowed(NavRoutes.EMERGENCY_SOS, AppRole.SUPERVISOR))
    }

    @Test
    fun testRoleRouter_adminRouteAccessControl() {
        // Administrator has global system access
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.STATION_MANAGEMENT, AppRole.ADMINISTRATOR))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.USER_MANAGEMENT, AppRole.ADMINISTRATOR))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.ROSTER_MANAGEMENT, AppRole.ADMINISTRATOR))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.ATTENDANCE_MANAGEMENT, AppRole.ADMINISTRATOR))
        assertTrue(RoleRouter.isRouteAllowed(NavRoutes.ADMIN_DASHBOARD, AppRole.ADMINISTRATOR))
    }

    // ==========================================
    // 5. Duty-State Navigation Locking (Off-Duty vs On-Duty)
    // ==========================================

    @Test
    fun testRoleRouter_offDutyGuardCannotAccessOperationalRoutes() {
        val offDuty = GuardDutyState.OFF_DUTY

        // Operational routes locked
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.OCCURRENCE_BOOK, AppRole.GUARD, offDuty))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.PATROL, AppRole.GUARD, offDuty))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.HANDOVER, AppRole.GUARD, offDuty))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.VISITORS, AppRole.GUARD, offDuty))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.INCIDENTS, AppRole.GUARD, offDuty))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.EMERGENCY_SOS, AppRole.GUARD, offDuty))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.ADDITIONAL_DUTIES, AppRole.GUARD, offDuty))

        // Non-operational routes permitted off-duty
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.TODAY_SHIFT, AppRole.GUARD, offDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.GUARD_DUTY_PLAN, AppRole.GUARD, offDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.NOTIFICATIONS, AppRole.GUARD, offDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.PROFILE, AppRole.GUARD, offDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.SETTINGS, AppRole.GUARD, offDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.LEAVE, AppRole.GUARD, offDuty))
    }

    @Test
    fun testRoleRouter_onDutyGuardCanAccessOperationalRoutes() {
        val onDuty = GuardDutyState.ON_DUTY

        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.OCCURRENCE_BOOK, AppRole.GUARD, onDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.PATROL, AppRole.GUARD, onDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.HANDOVER, AppRole.GUARD, onDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.VISITORS, AppRole.GUARD, onDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.INCIDENTS, AppRole.GUARD, onDuty))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.EMERGENCY_SOS, AppRole.GUARD, onDuty))
    }

    @Test
    fun testRoleRouter_canPerformLiveOperation() {
        // Guards require ON_DUTY
        assertFalse(RoleRouter.canPerformLiveOperation(AppRole.GUARD, GuardDutyState.OFF_DUTY))
        assertFalse(RoleRouter.canPerformLiveOperation(AppRole.GUARD, GuardDutyState.ELIGIBLE_FOR_DUTY))
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.GUARD, GuardDutyState.ON_DUTY))

        // Supervisors and Admins are exempt from guard duty lockout
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.SUPERVISOR, GuardDutyState.OFF_DUTY))
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.ADMINISTRATOR, GuardDutyState.OFF_DUTY))
    }

    // ==========================================
    // 6. Phase 12.4 Guard Experience & Robustness Tests
    // ==========================================

    @Test
    fun testGuardDashboardState_OnDuty() {
        val shift = createShift(attendanceStatus = "CLOCKED_IN")
        val dutyState = GuardDutyState.fromShift(shift)
        assertEquals(GuardDutyState.ON_DUTY, dutyState)
        assertTrue(dutyState.isOnDuty)
        assertFalse(dutyState.isOffDuty)
        assertFalse(dutyState.isEligibleForDuty)

        // Operational actions unlocked
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.GUARD, dutyState))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.OCCURRENCE_BOOK, AppRole.GUARD, dutyState))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.VISITOR_BOOK, AppRole.GUARD, dutyState))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.SOS, AppRole.GUARD, dutyState))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.PATROL, AppRole.GUARD, dutyState))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.HANDOVER, AppRole.GUARD, dutyState))
    }

    @Test
    fun testGuardDashboardState_OffDuty() {
        val shift = createShift(shiftType = "OFF")
        val dutyState = GuardDutyState.fromShift(shift)
        assertEquals(GuardDutyState.OFF_DUTY, dutyState)
        assertTrue(dutyState.isOffDuty)
        assertFalse(dutyState.isOnDuty)

        // Operational actions locked
        assertFalse(RoleRouter.canPerformLiveOperation(AppRole.GUARD, dutyState))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.OCCURRENCE_BOOK, AppRole.GUARD, dutyState))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.VISITOR_BOOK, AppRole.GUARD, dutyState))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.SOS, AppRole.GUARD, dutyState))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.PATROL, AppRole.GUARD, dutyState))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.HANDOVER, AppRole.GUARD, dutyState))
    }

    @Test
    fun testGuardDashboardState_EligibleForDuty() {
        val shift = createShift(attendanceStatus = "NOT_CLOCKED_IN")
        val dutyState = GuardDutyState.fromShift(shift)
        assertEquals(GuardDutyState.ELIGIBLE_FOR_DUTY, dutyState)
        assertTrue(dutyState.isEligibleForDuty)
        assertFalse(dutyState.isOnDuty)

        // Operational actions locked until clock-in
        assertFalse(RoleRouter.canPerformLiveOperation(AppRole.GUARD, dutyState))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.OCCURRENCE_BOOK, AppRole.GUARD, dutyState))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.PATROL, AppRole.GUARD, dutyState))

        // Clock In Console is accessible
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.TODAY_SHIFT, AppRole.GUARD, dutyState))
    }

    @Test
    fun testMyRoster_AlwaysAccessibleInAllStates() {
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.MY_ROSTER, AppRole.GUARD, GuardDutyState.ON_DUTY))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.MY_ROSTER, AppRole.GUARD, GuardDutyState.ELIGIBLE_FOR_DUTY))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.MY_ROSTER, AppRole.GUARD, GuardDutyState.OFF_DUTY))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.GUARD_DUTY_PLAN, AppRole.GUARD, GuardDutyState.OFF_DUTY))
    }

    @Test
    fun testRestrictedOffDutyOperations() {
        val offDuty = GuardDutyState.OFF_DUTY
        val operationalRoutes = listOf(
            NavRoutes.OCCURRENCE_BOOK,
            NavRoutes.VISITOR_BOOK,
            NavRoutes.SOS,
            NavRoutes.PATROL,
            NavRoutes.HANDOVER
        )

        for (route in operationalRoutes) {
            assertTrue("Expected $route to be an operational route", RoleRouter.isOperationalRoute(route))
            assertFalse("Expected $route to be locked when off-duty", RoleRouter.isRouteAccessible(route, AppRole.GUARD, offDuty))
        }
    }

    @Test
    fun testGuardCannotAccessSupervisorRoutes() {
        val supervisorRoutes = listOf(
            NavRoutes.ATTENDANCE_MANAGEMENT,
            NavRoutes.ROSTER_MANAGEMENT,
            NavRoutes.USER_MANAGEMENT,
            NavRoutes.SUPERVISOR_DASHBOARD
        )

        for (route in supervisorRoutes) {
            assertFalse("Guard should never be permitted route: $route", RoleRouter.isRouteAllowed(route, AppRole.GUARD))
        }
    }

    @Test
    fun testGuardCannotAccessAdministratorRoutes() {
        val adminRoutes = listOf(
            NavRoutes.STATION_MANAGEMENT,
            NavRoutes.ADMIN_DASHBOARD
        )

        for (route in adminRoutes) {
            assertFalse("Guard should never be permitted admin route: $route", RoleRouter.isRouteAllowed(route, AppRole.GUARD))
        }
    }

    @Test
    fun testServerStateRefreshLogic() {
        // Simulates dynamic duty-state transitions as server state changes
        var currentShift = createShift(attendanceStatus = "NOT_CLOCKED_IN")
        assertEquals(GuardDutyState.ELIGIBLE_FOR_DUTY, GuardDutyState.fromShift(currentShift))

        // After clock-in server response
        currentShift = currentShift.copy(attendanceStatus = "CLOCKED_IN")
        assertEquals(GuardDutyState.ON_DUTY, GuardDutyState.fromShift(currentShift))

        // After clock-out server response
        currentShift = currentShift.copy(attendanceStatus = "CLOCKED_OUT")
        assertEquals(GuardDutyState.OFF_DUTY, GuardDutyState.fromShift(currentShift))
    }

    @Test
    fun testNullStationHandling() {
        val userNoStation = User(id = "u1", username = "guard1", email = "g@g.com", role = "GUARD", station = null, stationName = null)
        val state = com.example.ui.viewmodel.SgmisUiState(currentUser = userNoStation, todayShift = null)

        assertNull(state.currentStationId)
        assertEquals("Station Unassigned", state.currentStationName)
    }

    @Test
    fun testNullShiftHandling() {
        val state = com.example.ui.viewmodel.SgmisUiState(todayShift = null)
        assertEquals(GuardDutyState.OFF_DUTY, state.guardDutyState)
        assertFalse(state.isOnDuty)
        assertTrue(state.isOffDuty)
        assertFalse(state.isEligibleForDuty)
        assertEquals("Solo / Unassigned", state.assignedPartnerName)
        assertNull(state.assignedPartnerEmployeeNumber)
    }

    @Test
    fun testNullOrEmptyRosterHandling() {
        val state = com.example.ui.viewmodel.SgmisUiState(rosterShifts = emptyList())
        assertTrue(state.rosterShifts.isEmpty())

        // Ensure filtering on empty list returns null without exception
        val nextDuty = state.rosterShifts.filter { it.shiftType != "OFF" }.minByOrNull { it.date }
        assertNull(nextDuty)
    }

    // ==========================================
    // 5. Phase 12.5: Supervisor Role & Command Console Tests
    // ==========================================

    @Test
    fun testSupervisorRoleRecognition() {
        val supervisorRoles = listOf("SUPERVISOR", "supervisor", "STATION_SUPERVISOR", "Station Supervisor")
        for (roleStr in supervisorRoles) {
            val role = AppRole.fromString(roleStr)
            assertEquals("Expected $roleStr to resolve to SUPERVISOR", AppRole.SUPERVISOR, role)

            val user = User(id = "s-1", username = "tavongashe", email = "tav@zou.ac.zw", role = roleStr)
            assertEquals(AppRole.SUPERVISOR, user.appRole)
            assertTrue(user.isSupervisor)
            assertFalse(user.isGuard)
            assertFalse(user.isAdmin)
            assertTrue(user.isSupervisorOrAdmin)
        }

        assertEquals(NavRoutes.SUPERVISOR_DASHBOARD, RoleRouter.getDashboardRoute(AppRole.SUPERVISOR))
    }

    @Test
    fun testSupervisorRouteAccess() {
        val allowedSupervisorRoutes = listOf(
            NavRoutes.SUPERVISOR_DASHBOARD,
            NavRoutes.DASHBOARD,
            NavRoutes.ATTENDANCE_MANAGEMENT,
            NavRoutes.ROSTER_MANAGEMENT,
            NavRoutes.USER_MANAGEMENT,
            NavRoutes.OCCURRENCE_BOOK,
            NavRoutes.INCIDENTS,
            NavRoutes.PATROL,
            NavRoutes.LEAVE,
            NavRoutes.VISITORS,
            NavRoutes.REPORTS,
            NavRoutes.NOTIFICATIONS,
            NavRoutes.PROFILE,
            NavRoutes.SETTINGS
        )

        for (route in allowedSupervisorRoutes) {
            assertTrue("Supervisor must be permitted access to route: $route", RoleRouter.isRouteAllowed(route, AppRole.SUPERVISOR))
        }

        val prohibitedSupervisorRoutes = listOf(
            NavRoutes.HANDOVER,
            NavRoutes.TODAY_SHIFT,
            NavRoutes.EMERGENCY_SOS,
            NavRoutes.GUARD_DUTY_PLAN,
            NavRoutes.GUARD_DASHBOARD,
            NavRoutes.STATION_MANAGEMENT,
            NavRoutes.ADMIN_DASHBOARD
        )

        for (route in prohibitedSupervisorRoutes) {
            assertFalse("Supervisor must not have access to guard-execution or admin-only route: $route", RoleRouter.isRouteAllowed(route, AppRole.SUPERVISOR))
        }
    }

    @Test
    fun testSupervisorCannotAccessAdministratorRoutes() {
        val prohibitedAdminRoutes = listOf(
            NavRoutes.STATION_MANAGEMENT,
            NavRoutes.ADMIN_DASHBOARD
        )

        for (route in prohibitedAdminRoutes) {
            assertFalse("Supervisor must NEVER be permitted admin route: $route", RoleRouter.isRouteAllowed(route, AppRole.SUPERVISOR))
        }
    }

    @Test
    fun testSupervisorStationContextIsRespected() {
        val stationUser = User(
            id = "sup-1",
            username = "tavongashe",
            role = "STATION_SUPERVISOR",
            station = "station-mash-west",
            stationName = "ZOU Mash-West Main Campus"
        )
        val state = com.example.ui.viewmodel.SgmisUiState(currentUser = stationUser)

        assertEquals("station-mash-west", state.currentStationId)
        assertEquals("ZOU Mash-West Main Campus", state.currentStationName)
        assertTrue("Supervisor with station must be marked as hasAssignedStation", state.hasAssignedStation)
    }

    @Test
    fun testSupervisorNullStationHandling() {
        val unassignedSupervisor = User(
            id = "sup-2",
            username = "test_sup",
            role = "STATION_SUPERVISOR",
            station = null,
            stationName = null
        )
        val state = com.example.ui.viewmodel.SgmisUiState(currentUser = unassignedSupervisor)

        assertNull(state.currentStationId)
        assertEquals("Station Unassigned", state.currentStationName)
        assertFalse("Supervisor without station must have hasAssignedStation == false", state.hasAssignedStation)
    }

    @Test
    fun testSupervisorStationNameLookupFromStationsList() {
        val station = Station(id = "st-99", name = "Mutare Regional Center")
        val userWithStationIdOnly = User(
            id = "sup-3",
            username = "mutare_sup",
            role = "SUPERVISOR",
            station = "st-99",
            stationName = null
        )
        val state = com.example.ui.viewmodel.SgmisUiState(
            currentUser = userWithStationIdOnly,
            stations = listOf(station)
        )

        assertEquals("st-99", state.currentStationId)
        assertEquals("Mutare Regional Center", state.currentStationName)
        assertTrue(state.hasAssignedStation)
    }

    @Test
    fun testSupervisorNeverSubjectToGuardDutyLockout() {
        // Supervisors are not guards and are not locked out from live operations
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.SUPERVISOR, GuardDutyState.OFF_DUTY))
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.SUPERVISOR, GuardDutyState.ON_DUTY))
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.SUPERVISOR, GuardDutyState.ELIGIBLE_FOR_DUTY))
    }

    @Test
    fun testSupervisorPatrolOversightAccessAndStationIsolation() {
        // 1. Supervisor access to patrol oversight route
        assertTrue("Supervisor must be permitted access to patrol oversight", RoleRouter.isRouteAllowed(NavRoutes.PATROL, AppRole.SUPERVISOR))
        assertTrue("Supervisor must be able to navigate to patrol oversight without duty lock", RoleRouter.isRouteAccessible(NavRoutes.PATROL, AppRole.SUPERVISOR, GuardDutyState.OFF_DUTY))

        // 2. Guard patrol execution route and duty-lock preservation
        assertTrue("Guard must be allowed patrol route", RoleRouter.isRouteAllowed(NavRoutes.PATROL, AppRole.GUARD))
        assertFalse("Guard must NOT access patrol route when OFF_DUTY", RoleRouter.isRouteAccessible(NavRoutes.PATROL, AppRole.GUARD, GuardDutyState.OFF_DUTY))
        assertTrue("Guard can access patrol route when ON_DUTY", RoleRouter.isRouteAccessible(NavRoutes.PATROL, AppRole.GUARD, GuardDutyState.ON_DUTY))

        // 3. Administrator non-regression
        assertTrue("Administrator may monitor patrol telemetry", RoleRouter.isRouteAllowed(NavRoutes.PATROL, AppRole.ADMINISTRATOR))
        assertFalse("Supervisor must NOT access administrator dashboard", RoleRouter.isRouteAllowed(NavRoutes.ADMIN_DASHBOARD, AppRole.SUPERVISOR))
        assertFalse("Supervisor must NOT access station management", RoleRouter.isRouteAllowed(NavRoutes.STATION_MANAGEMENT, AppRole.SUPERVISOR))

        // 4. Station restriction preservation: supervisor ui state
        val stationUser = User(id = "sup-1", username = "sup", role = "SUPERVISOR", station = "station-a", stationName = "Station A")
        val state = com.example.ui.viewmodel.SgmisUiState(currentUser = stationUser)
        assertEquals("station-a", state.currentStationId)
        assertTrue(state.hasAssignedStation)

        val unassignedUser = User(id = "sup-2", username = "sup2", role = "SUPERVISOR", station = null, stationName = null)
        val unassignedState = com.example.ui.viewmodel.SgmisUiState(currentUser = unassignedUser)
        assertFalse(unassignedState.hasAssignedStation)
    }

    @Test
    fun testSupervisorEmptyMetricsHandling() {
        val state = com.example.ui.viewmodel.SgmisUiState(
            currentUser = User(id = "s-1", username = "sup1", role = "SUPERVISOR", station = "st-1", stationName = "Main Post"),
            attendanceRecords = emptyList(),
            rosterShifts = emptyList(),
            obEntries = emptyList(),
            visitors = emptyList(),
            incidents = emptyList(),
            patrolLogs = emptyList(),
            leaveApplications = emptyList()
        )

        val stationName = state.currentStationName
        val stationId = state.currentStationId

        // Verified safe computations with empty datasets
        val stationAttendance = state.attendanceRecords.filter { it.stationName == stationName }
        assertEquals(0, stationAttendance.size)

        val stationShifts = state.rosterShifts.filter { it.station == stationId }
        assertEquals(0, stationShifts.size)

        val openIncidents = state.incidents.filter { it.status != "RESOLVED" }
        assertEquals(0, openIncidents.size)

        val activePatrols = state.patrolLogs.filter { it.status == "IN_PROGRESS" }
        assertEquals(0, activePatrols.size)

        val pendingLeave = state.leaveApplications.filter { it.status == "PENDING" }
        assertEquals(0, pendingLeave.size)
    }

    @Test
    fun testSupervisorLoadingAndFailureStates() {
        val loadingState = com.example.ui.viewmodel.SgmisUiState(
            adminLoading = true,
            obLoading = true,
            incidentsLoading = true,
            visitorsLoading = true,
            leaveLoading = true,
            handoversLoading = true,
            errorMessage = "Server unreachable (503 Service Unavailable)"
        )

        assertTrue(loadingState.adminLoading)
        assertTrue(loadingState.obLoading)
        assertTrue(loadingState.incidentsLoading)
        assertTrue(loadingState.visitorsLoading)
        assertTrue(loadingState.leaveLoading)
        assertTrue(loadingState.handoversLoading)
        assertEquals("Server unreachable (503 Service Unavailable)", loadingState.errorMessage)
    }

    @Test
    fun testRosterApiContractPreservation() {
        val approveReq = ApproveRosterRequest(stationId = "st-1", startDate = "2026-10-01", endDate = "2026-10-12")
        assertEquals("st-1", approveReq.stationId)
        assertEquals("2026-10-01", approveReq.startDate)
        assertEquals("2026-10-12", approveReq.endDate)

        val validateReq = ValidateRosterRequest(stationId = "st-1", startDate = "2026-10-01", endDate = "2026-10-12")
        assertEquals("st-1", validateReq.stationId)

        val validateResp = ValidateRosterResponse(valid = true, status = "APPROVED", message = "Roster invariant satisfied")
        assertTrue(validateResp.valid)
        assertEquals("APPROVED", validateResp.status)
    }

    // ==========================================
    // Phase 12.5 Remediation: Supervisor Shift & Geofence Tests
    // ==========================================

    @Test
    fun testSupervisorShiftBadge_withAuthoritativeServerShift() {
        val serverShift = Shift(
            id = "shift-sup-1",
            date = "2026-09-26",
            shiftType = "DAY",
            assignmentType = "REGULAR",
            station = "st-1",
            stationName = "Main Station",
            startTime = "07:00:00",
            endTime = "19:00:00",
            guard = "sup-1",
            guardName = "Supervisor One"
        )
        val badge = computeSupervisorShiftBadge(serverShift)
        assertEquals("DAY SHIFT (07:00–19:00)", badge)
        assertFalse("Authoritative server shift badge must not contain DISPLAY ONLY", badge.contains("DISPLAY ONLY"))
    }

    @Test
    fun testSupervisorShiftBadge_whenServerShiftMissing() {
        val badge = computeSupervisorShiftBadge(null)
        assertTrue("Estimated shift badge must explicitly contain DISPLAY ONLY", badge.contains("DISPLAY ONLY"))
        assertTrue("Estimated shift badge must indicate DAY or NIGHT", badge.contains("DAY SHIFT") || badge.contains("NIGHT SHIFT"))
    }

    @Test
    fun testSupervisorGeofenceText_stationWithRadius200() {
        val text = computeSupervisorGeofenceText(hasStation = true, radius = 200.0)
        assertEquals("Operational Geofence: 200m Active", text)
        assertFalse("Must not contain hardcoded 100m", text.contains("100m"))
    }

    @Test
    fun testSupervisorGeofenceText_stationWithRadius150() {
        val text = computeSupervisorGeofenceText(hasStation = true, radius = 150.0)
        assertEquals("Operational Geofence: 150m Active", text)
        assertFalse("Must not contain hardcoded 100m", text.contains("100m"))
    }

    @Test
    fun testSupervisorGeofenceText_stationWithNullRadius() {
        val text = computeSupervisorGeofenceText(hasStation = true, radius = null)
        assertEquals("Operational Geofence: Unavailable", text)
        assertFalse("Must not contain hardcoded 100m", text.contains("100m"))
    }

    @Test
    fun testSupervisorGeofenceText_unassignedSupervisor() {
        val text = computeSupervisorGeofenceText(hasStation = false, radius = 200.0)
        assertEquals("Geofence Disabled (No Station)", text)
        assertFalse("Must not contain hardcoded 100m", text.contains("100m"))

        val textNull = computeSupervisorGeofenceText(hasStation = false, radius = null)
        assertEquals("Geofence Disabled (No Station)", textNull)
    }

    @Test
    fun testSupervisorUiStateGeofenceResolution() {
        // Station with geofenceRadius
        val station200 = Station(id = "st-1", name = "Headquarters", geofenceRadius = 200.0)
        val user1 = User(id = "sup-1", username = "sup1", role = "SUPERVISOR", station = "st-1", stationName = "Headquarters")
        val state1 = com.example.ui.viewmodel.SgmisUiState(currentUser = user1, stations = listOf(station200))
        assertEquals(station200, state1.currentStation)
        assertEquals(200.0, state1.stationGeofenceRadius)
        assertEquals("Operational Geofence: 200m Active", computeSupervisorGeofenceText(state1.hasAssignedStation, state1.stationGeofenceRadius))

        // Station with geofenceRadiusMeters
        val station150 = Station(id = "st-2", name = "Harare Depot", geofenceRadiusMeters = 150.0)
        val user2 = User(id = "sup-2", username = "sup2", role = "SUPERVISOR", station = "st-2", stationName = "Harare Depot")
        val state2 = com.example.ui.viewmodel.SgmisUiState(currentUser = user2, stations = listOf(station150))
        assertEquals(station150, state2.currentStation)
        assertEquals(150.0, state2.stationGeofenceRadius)
        assertEquals("Operational Geofence: 150m Active", computeSupervisorGeofenceText(state2.hasAssignedStation, state2.stationGeofenceRadius))

        // Station with null geofence
        val stationNull = Station(id = "st-3", name = "Remote Outpost")
        val user3 = User(id = "sup-3", username = "sup3", role = "SUPERVISOR", station = "st-3", stationName = "Remote Outpost")
        val state3 = com.example.ui.viewmodel.SgmisUiState(currentUser = user3, stations = listOf(stationNull))
        assertEquals(stationNull, state3.currentStation)
        assertNull(state3.stationGeofenceRadius)
        assertEquals("Operational Geofence: Unavailable", computeSupervisorGeofenceText(state3.hasAssignedStation, state3.stationGeofenceRadius))

        // Unassigned supervisor
        val unassignedSup = User(id = "sup-4", username = "unassigned", role = "SUPERVISOR", station = null, stationName = null)
        val state4 = com.example.ui.viewmodel.SgmisUiState(currentUser = unassignedSup, stations = listOf(station200))
        assertNull(state4.currentStation)
        assertNull(state4.stationGeofenceRadius)
        assertEquals("Geofence Disabled (No Station)", computeSupervisorGeofenceText(state4.hasAssignedStation, state4.stationGeofenceRadius))
    }

    // ==========================================
    // 10. Phase 12.6 — Administrator & National Control Center Tests
    // ==========================================

    @Test
    fun testAdministratorRoleRoutingAndGlobalAccess() {
        // Route resolution
        assertEquals(NavRoutes.ADMIN_DASHBOARD, RoleRouter.getDashboardRoute(AppRole.ADMINISTRATOR))

        // Administrator global route permissions
        val allAdminRoutes = listOf(
            NavRoutes.ADMIN_DASHBOARD,
            NavRoutes.USER_MANAGEMENT,
            NavRoutes.STATION_MANAGEMENT,
            NavRoutes.ROSTER_MANAGEMENT,
            NavRoutes.ATTENDANCE_MANAGEMENT,
            NavRoutes.REPORTS,
            NavRoutes.OCCURRENCE_BOOK,
            NavRoutes.INCIDENTS,
            NavRoutes.LEAVE,
            NavRoutes.VISITOR_BOOK,
            NavRoutes.NOTIFICATIONS,
            NavRoutes.SETTINGS,
            NavRoutes.PROFILE,
            NavRoutes.RECORD_ADJUSTMENTS,
            NavRoutes.ADMIN_HISTORY,
            NavRoutes.ADMIN_MASTER_TOOLS
        )
        for (route in allAdminRoutes) {
            assertTrue("Administrator must have access to $route", RoleRouter.isRouteAllowed(route, AppRole.ADMINISTRATOR))
        }
        for (guardExecutionRoute in listOf(NavRoutes.TODAY_SHIFT, NavRoutes.HANDOVER, NavRoutes.SOS, NavRoutes.GUARD_DUTY_PLAN)) {
            assertFalse("Administrator must not access guard execution route $guardExecutionRoute", RoleRouter.isRouteAllowed(guardExecutionRoute, AppRole.ADMINISTRATOR))
        }
        assertTrue("Administrator may monitor patrol telemetry", RoleRouter.isRouteAllowed(NavRoutes.PATROL, AppRole.ADMINISTRATOR))

        // Supervisor must be blocked from admin dashboard and station provisioning
        assertFalse("Supervisor must NOT access admin dashboard", RoleRouter.isRouteAllowed(NavRoutes.ADMIN_DASHBOARD, AppRole.SUPERVISOR))
        assertFalse("Supervisor must NOT access station management", RoleRouter.isRouteAllowed(NavRoutes.STATION_MANAGEMENT, AppRole.SUPERVISOR))
        assertTrue("Supervisor must access patrol oversight and assignment", RoleRouter.isRouteAllowed(NavRoutes.PATROL, AppRole.SUPERVISOR))
        assertFalse("Supervisor must NOT access guard handover execution", RoleRouter.isRouteAllowed(NavRoutes.HANDOVER, AppRole.SUPERVISOR))
        assertTrue("Supervisor may submit and track adjustment requests", RoleRouter.isRouteAllowed(NavRoutes.RECORD_ADJUSTMENTS, AppRole.SUPERVISOR))
        assertFalse("Supervisor must NOT access national audit history", RoleRouter.isRouteAllowed(NavRoutes.ADMIN_HISTORY, AppRole.SUPERVISOR))
        assertFalse("Supervisor must NOT access administrator master tools", RoleRouter.isRouteAllowed(NavRoutes.ADMIN_MASTER_TOOLS, AppRole.SUPERVISOR))

        // Guard must be blocked from all supervisory and administrative management consoles
        assertFalse("Guard must NOT access admin dashboard", RoleRouter.isRouteAllowed(NavRoutes.ADMIN_DASHBOARD, AppRole.GUARD))
        assertFalse("Guard must NOT access user management", RoleRouter.isRouteAllowed(NavRoutes.USER_MANAGEMENT, AppRole.GUARD))
        assertFalse("Guard must NOT access station management", RoleRouter.isRouteAllowed(NavRoutes.STATION_MANAGEMENT, AppRole.GUARD))
        assertFalse("Guard must NOT access roster management", RoleRouter.isRouteAllowed(NavRoutes.ROSTER_MANAGEMENT, AppRole.GUARD))
        assertFalse("Guard must NOT access attendance management", RoleRouter.isRouteAllowed(NavRoutes.ATTENDANCE_MANAGEMENT, AppRole.GUARD))
        assertFalse("Guard must NOT access reports", RoleRouter.isRouteAllowed(NavRoutes.REPORTS, AppRole.GUARD))
        assertFalse("Guard must NOT access record adjustments", RoleRouter.isRouteAllowed(NavRoutes.RECORD_ADJUSTMENTS, AppRole.GUARD))
    }

    @Test
    fun testIdentityInvariants_simeonmbwaniAndTavongashe() {
        // Authoritative Administrator Identity: simeonmbwani
        val admin = User(
            id = "user-admin",
            username = "simeonmbwani",
            email = "simeon.mbwani@smartsecurity.co.zw",
            role = "ADMINISTRATOR"
        )
        assertEquals(AppRole.ADMINISTRATOR, admin.appRole)
        assertTrue(admin.isAdmin)
        assertFalse(admin.isSupervisor)
        assertFalse(admin.isGuard)
        assertTrue(admin.isSupervisorOrAdmin)

        // Authoritative Supervisor Identity: tavongashe
        val supervisor = User(
            id = "user-sup",
            username = "tavongashe",
            email = "tavongashe@smartsecurity.co.zw",
            role = "SUPERVISOR",
            station = "st-harare",
            stationName = "Harare Main Station"
        )
        assertEquals(AppRole.SUPERVISOR, supervisor.appRole)
        assertFalse(supervisor.isAdmin)
        assertTrue(supervisor.isSupervisor)
        assertFalse(supervisor.isGuard)
        assertTrue(supervisor.isSupervisorOrAdmin)

        // Invariant: neither simeonmbwani nor tavongashe is a guard, preventing duty assignment
        val guards = listOf(
            User(id = "g-1", username = "guard1", role = "GUARD"),
            User(id = "g-2", username = "guard2", role = "GUARD"),
            admin,
            supervisor
        )
        val guardOnlyPool = guards.filter { it.isGuard }
        assertEquals(2, guardOnlyPool.size)
        assertFalse(guardOnlyPool.any { it.username == "simeonmbwani" })
        assertFalse(guardOnlyPool.any { it.username == "tavongashe" })
    }

    @Test
    fun testPublicHolidayModelsAndStatutoryCompensationContract() {
        val holiday = PublicHoliday(
            id = "hol-1",
            name = "Robert Mugabe National Youth Day",
            date = "2026-02-21",
            countryCode = "ZW",
            description = "Statutory national holiday",
            isActive = true
        )
        assertEquals("ZW", holiday.countryCode)
        assertTrue(holiday.isActive)
        assertEquals("Robert Mugabe National Youth Day", holiday.name)

        val dutyRecord = PublicHolidayDutyRecord(
            id = "rec-1",
            publicHoliday = "hol-1",
            publicHolidayName = "Robert Mugabe National Youth Day",
            shift = "shift-99",
            shiftDate = "2026-02-21",
            stationName = "Harare Station",
            guard = "g-1",
            guardName = "John Guard",
            attendance = "att-99",
            compensatedDays = 2.0,
            status = "PENDING"
        )
        assertEquals(2.0, dutyRecord.compensatedDays, 0.001)
        assertEquals("PENDING", dutyRecord.status)
        assertNull(dutyRecord.approvedBy)

        val reviewReq = ReviewHolidayDutyRequest(reason = "Verified holiday attendance on post")
        assertEquals("Verified holiday attendance on post", reviewReq.reason)
    }

    @Test
    fun testEarlyClockoutOtpModelsAndSecurityContracts() {
        val otpReq = GenerateEarlyClockoutOtpRequest(
            shiftId = "shift-123",
            reason = "Medical emergency override"
        )
        assertEquals("shift-123", otpReq.shiftId)
        assertEquals("Medical emergency override", otpReq.reason)

        val otpRes = GenerateEarlyClockoutOtpResponse(
            otp = "849201",
            expiresInSeconds = 300,
            shiftId = "shift-123",
            guardUsername = "guard1",
            guardName = "John Guard",
            stationName = "Harare Station",
            reason = "Medical emergency override"
        )
        assertEquals("849201", otpRes.otp)
        assertEquals(6, otpRes.otp.length)
        assertEquals(300, otpRes.expiresInSeconds)

        val clockOutReq = ClockOutRequest(
            shiftId = "shift-123",
            otpCode = "849201",
            overrideReason = "Medical emergency approved with OTP"
        )
        assertEquals("849201", clockOutReq.otpCode)
        assertNull(clockOutReq.supervisorUsername)
        assertNull(clockOutReq.supervisorPassword)
    }

    @Test
    fun testNationalTelemetryMetricsComputation() {
        val stations = listOf(
            Station(id = "st-1", name = "Station Alpha"),
            Station(id = "st-2", name = "Station Beta")
        )
        val users = listOf(
            User(id = "u-admin", username = "simeonmbwani", role = "ADMINISTRATOR"),
            User(id = "u-sup", username = "tavongashe", role = "SUPERVISOR"),
            User(id = "g-1", username = "guard1", role = "GUARD"),
            User(id = "g-2", username = "guard2", role = "GUARD"),
            User(id = "g-3", username = "guard3", role = "GUARD")
        )
        val attendance = listOf(
            Attendance(
                id = "att-1",
                shift = "s-1",
                shiftDate = "2026-09-26",
                shiftType = "DAY",
                guard = "g-1",
                guardName = "Guard One",
                stationName = "Station Alpha",
                clockIn = "2026-09-26T07:00:00Z",
                clockOut = null
            ),
            Attendance(
                id = "att-2",
                shift = "s-2",
                shiftDate = "2026-09-26",
                shiftType = "DAY",
                guard = "g-2",
                guardName = "Guard Two",
                stationName = "Station Beta",
                clockIn = "2026-09-26T07:05:00Z",
                clockOut = "2026-09-26T15:00:00Z"
            )
        )
        val incidents = listOf(
            IncidentReport(id = "inc-1", title = "Alarm Triggered", station = "st-1", stationName = "Station Alpha", reportingGuard = "g-1", reportingGuardName = "Guard One", priority = "HIGH", status = "INVESTIGATING", location = "Perimeter", description = "Perimeter sensor", createdAt = "2026-09-26T08:00:00Z"),
            IncidentReport(id = "inc-2", title = "Visitor Parking Disputed", station = "st-2", stationName = "Station Beta", reportingGuard = "g-2", reportingGuardName = "Guard Two", priority = "LOW", status = "RESOLVED", location = "Gate 1", description = "Resolved on site", createdAt = "2026-09-26T08:30:00Z")
        )
        val patrols = listOf(
            PatrolLog(id = "pat-1", station = "st-1", stationName = "Station Alpha", guard = "g-1", guardName = "Guard One", status = "IN_PROGRESS", startTime = "2026-09-26T08:00:00Z")
        )
        val visitors = listOf(
            OccurrenceBookEntry(
                id = "vis-1",
                entryNumber = "OB-001",
                station = "st-1",
                stationName = "Station Alpha",
                guard = "g-1",
                guardName = "Guard One",
                category = "VISITOR",
                occurrenceText = "Jane Doe (ID: 63-123456-X) admitted for Audit at 08:30",
                createdAt = "2026-09-26T08:30:00Z"
            )
        )
        val leaves = listOf(
            LeaveApplication(id = "lv-1", guard = "g-3", guardName = "Guard Three", leaveType = "CASUAL", startDate = "2026-09-28", endDate = "2026-09-29", reason = "Personal", status = "PENDING", createdAt = "2026-09-26")
        )

        val totalStations = stations.size
        assertEquals(2, totalStations)

        val guardsOnDuty = attendance.count { it.clockIn != null && it.clockOut == null }
        assertEquals(1, guardsOnDuty)

        val totalGuards = users.count { it.role.uppercase() == "GUARD" }
        assertEquals(3, totalGuards)

        val guardsOffDuty = (totalGuards - guardsOnDuty).coerceAtLeast(0)
        assertEquals(2, guardsOffDuty)

        val openIncidents = incidents.count { it.status != "RESOLVED" }
        assertEquals(1, openIncidents)

        val activePatrols = patrols.count { it.status == "IN_PROGRESS" }
        assertEquals(1, activePatrols)

        val activeVisitors = visitors.count { !it.occurrenceText.contains("Time Out:") }
        assertEquals(1, activeVisitors)

        val pendingLeave = leaves.count { it.status == "PENDING" }
        assertEquals(1, pendingLeave)
    }

    // ==========================================
    // 16. Dynamic Guard & Supervisor Duty Management (Exam, Escort, Pair Day/Night Swaps)
    // ==========================================

    @Test
    fun testExamAndEscortDutyStates() {
        val examState = GuardDutyState.EXAM
        assertTrue(examState.isSpecialDuty)
        assertFalse(examState.isOffDuty)

        val escortState = GuardDutyState.ESCORT
        assertTrue(escortState.isSpecialDuty)
        assertFalse(escortState.isOffDuty)

        val offState = GuardDutyState.OFF_DUTY
        assertFalse(offState.isSpecialDuty)
        assertTrue(offState.isOffDuty)

        val onDutyState = GuardDutyState.ON_DUTY
        assertFalse(onDutyState.isSpecialDuty)
        assertFalse(onDutyState.isOffDuty)
    }

    @Test
    fun testSpecialDutyOperationalPermissions() {
        // Special duties (Exam, Escort) grant operational event permission
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.GUARD, GuardDutyState.EXAM))
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.GUARD, GuardDutyState.ESCORT))
        assertTrue(RoleRouter.canPerformLiveOperation(AppRole.GUARD, GuardDutyState.ON_DUTY))
        assertFalse(RoleRouter.canPerformLiveOperation(AppRole.GUARD, GuardDutyState.OFF_DUTY))

        // Operational routes accessible when on special duty
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.OCCURRENCE_BOOK, AppRole.GUARD, GuardDutyState.EXAM))
        assertTrue(RoleRouter.isRouteAccessible(NavRoutes.INCIDENTS, AppRole.GUARD, GuardDutyState.ESCORT))
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.OCCURRENCE_BOOK, AppRole.GUARD, GuardDutyState.OFF_DUTY))

        // SOS is an operational route and is locked when off-duty
        assertFalse(RoleRouter.isRouteAccessible(NavRoutes.EMERGENCY_SOS, AppRole.GUARD, GuardDutyState.OFF_DUTY))
    }

    @Test
    fun testStationCoverageAndPairSwapModels() {
        val pair = StationCoveragePair(
            id = "pair-1",
            rotationOrder = 1,
            guardAId = "g-1",
            guardAName = "Alice Guard",
            guardBId = "g-2",
            guardBName = "Bob Guard"
        )
        assertEquals("pair-1", pair.id)
        assertEquals(1, pair.rotationOrder)
        assertEquals("Alice Guard", pair.guardAName)
        assertEquals("Bob Guard", pair.guardBName)

        val relief = AvailableReliefGuard(
            id = "g-3",
            username = "charlie",
            fullName = "Charlie Relief",
            employeeNumber = "SEC-003",
            stationName = "Harare Main"
        )
        assertEquals("Charlie Relief", relief.fullName)
        assertEquals("SEC-003", relief.employeeNumber)

        val coverage = StationCoverageResponse(
            stationId = "st-1",
            stationName = "Harare Main",
            date = "2026-10-04",
            isDayCovered = true,
            isNightCovered = true,
            coverageWarning = false,
            warningMessage = null,
            pair = pair,
            availableReliefGuards = listOf(relief)
        )
        assertFalse(coverage.coverageWarning)
        assertTrue(coverage.isDayCovered)
        assertTrue(coverage.isNightCovered)
        assertNotNull(coverage.pair)
        assertEquals(1, coverage.availableReliefGuards.size)
    }

    @Test
    fun testAuthoritativeDutyStateAndClockFlags() {
        val shiftEligible = Shift(
            id = "s-el-1",
            date = "2026-10-05",
            shiftType = "DAY",
            station = "st-1",
            stationName = "Station Alpha",
            startTime = "07:00",
            endTime = "18:00",
            guard = "g-1",
            guardName = "Test Guard",
            rawDutyState = "ELIGIBLE_FOR_DUTY",
            clockInEnabled = true,
            clockOutEnabled = false
        )
        assertEquals(GuardDutyState.ELIGIBLE_FOR_DUTY, shiftEligible.dutyState)
        assertTrue(shiftEligible.isEligibleForDuty)
        assertTrue(shiftEligible.clockInEnabled)
        assertFalse(shiftEligible.clockOutEnabled)

        val shiftLeave = Shift(
            id = "s-lv-1",
            date = "2026-10-05",
            shiftType = "DAY",
            station = "st-1",
            stationName = "Station Alpha",
            startTime = "07:00",
            endTime = "18:00",
            guard = "g-1",
            guardName = "Test Guard",
            rawDutyState = "ON_LEAVE",
            leaveType = "ANNUAL",
            clockInEnabled = false,
            clockOutEnabled = false
        )
        assertEquals(GuardDutyState.ON_LEAVE, shiftLeave.dutyState)
        assertTrue(shiftLeave.isOnLeave)
        assertTrue(shiftLeave.isOffDuty)
        assertFalse(shiftLeave.isEligibleForDuty)
    }
}
