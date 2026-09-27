# SMART SECURITY — PHASE 12.3: ROLE + SERVER-AUTHORITATIVE DUTY-STATE ARCHITECTURE REPORT

**Document:** `PHASE_12_3_ROLE_DUTY_ARCHITECTURE_REPORT.md`  
**Project Root:** `C:\Projects\SGMIS_FIXED\sgmis_fixed`  
**Authoritative Blueprint:** `C:\Projects\Smart Security Original Blueprint.zip`  
**Date:** September 26, 2026  
**Status:** COMPLETE — PASSED (32 Android Unit Tests, 229 Backend Tests, Debug Build Succeeded)

---

## 1. Executive Summary

Phase 12.3 establishes the foundation for role-based access control and the server-authoritative duty-state architecture across Smart Security. Prior to this phase, the application had disparate client role strings, ambiguous guard states, and did not systematically guard operational logging actions from off-duty personnel.

Under Phase 12.3, the system implements a strict, server-authoritative state machine:
1. **Three Distinct Operational Roles:**
   - `SECURITY GUARD`: Operational post duties, occurrence logging, checkpoint patrols, shift handover, personal leave/duty plans.
   - `STATION SUPERVISOR`: Station-level command, attendance monitoring, guard roster management, personnel oversight, and post reviews.
   - `ADMINISTRATOR / SUPERUSER`: System-wide national telemetry, station configuration, pair allocation, and organizational administration.
2. **Server-Authoritative Guard Duty State Machine:**
   - `OFF_DUTY`: Guard has no active shift, is scheduled off, or has completed their shift. Operational actions (OB, Patrol, Handover, Visitors, Incidents, SOS) are strictly locked.
   - `ELIGIBLE_FOR_DUTY`: Guard is rostered for a shift today and eligible to report, but has not yet clocked in. Prominently directs the guard to Clock In.
   - `ON_DUTY`: Guard is formally clocked in with GPS verification. All operational logging actions are active and accessible.
3. **Centralized Routing & Client-Side Guarding:**
   - Centralized `RoleRouter` and `NavRoutes` ensure guards are routed to the Guard Console, supervisors to the Supervisor Console, and administrators to the National Control Center.
   - Operational routes are gated at navigation (`safeNavigate`) and within UI cards.
4. **Resilience & State Re-sync:**
   - Automatic re-sync of authoritative shifts and duty state on app resume (`onResume`) and user interaction.
   - Complete preservation of `AppLockOverlay` 3-minute inactivity auto-lock and `FLAG_SECURE` window protection.

All 32 Android unit tests passed, 229 backend Django tests passed with zero failures, and the Android debug APK compiled successfully (`assembleDebug`).

---

## 2. Implemented Role Architecture

The role architecture implements type-safe representation in Android matching the backend Django `UserRole` model:

```
┌────────────────────────────────────────────────────────┐
│                   Django Backend                       │
│    UserRole: ADMINISTRATOR | SUPERVISOR | GUARD        │
└───────────────────────────┬────────────────────────────┘
                            │ (JSON: role / app_role)
                            ▼
┌────────────────────────────────────────────────────────┐
│                   Android Client                       │
│                   enum class AppRole                   │
│         GUARD | SUPERVISOR | ADMINISTRATOR             │
└───────────────────────────┬────────────────────────────┘
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
      [GUARD APP]    [SUPERVISOR APP]  [CONTROL CENTER]
    Guard Dashboard  Supervisor Console   Admin Console
```

### Android Type Definition (`Models.kt`):
```kotlin
enum class AppRole(val apiValue: String) {
    GUARD("GUARD"),
    SUPERVISOR("SUPERVISOR"),
    ADMINISTRATOR("ADMINISTRATOR");

    companion object {
        fun fromString(role: String?): AppRole {
            return when (role?.trim()?.uppercase()) {
                "ADMIN", "ADMINISTRATOR", "SUPERUSER" -> ADMINISTRATOR
                "SUPERVISOR", "STATION_SUPERVISOR", "STATION SUPERVISOR" -> SUPERVISOR
                else -> GUARD
            }
        }
    }
}
```

### Extension Properties on `User`:
- `val User.appRole: AppRole get() = AppRole.fromString(role)`
- `val User.isAdmin: Boolean get() = appRole == AppRole.ADMINISTRATOR`
- `val User.isSupervisor: Boolean get() = appRole == AppRole.SUPERVISOR`
- `val User.isGuard: Boolean get() = appRole == AppRole.GUARD`
- `val User.isSupervisorOrAdmin: Boolean get() = isAdmin || isSupervisor`

---

## 3. Role Mapping Table (Backend ↔ Android)

| Backend Role (`UserRole`) | Django Serializer Output | Android String Parsed | Android Type (`AppRole`) | Primary Route Target | Navigation Scope |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `ADMINISTRATOR` | `"ADMINISTRATOR"` | `"ADMINISTRATOR"`, `"ADMIN"`, `"SUPERUSER"` | `AppRole.ADMINISTRATOR` | `NavRoutes.ADMIN_DASHBOARD` | Full system access, station creation, national telemetry, user admin. |
| `SUPERVISOR` | `"SUPERVISOR"` | `"SUPERVISOR"`, `"STATION_SUPERVISOR"` | `AppRole.SUPERVISOR` | `NavRoutes.SUPERVISOR_DASHBOARD` | Station attendance console, roster engine, station guards, OB reviews. Barred from station creation. |
| `GUARD` | `"GUARD"` | `"GUARD"`, `"SECURITY_GUARD"`, or unknown | `AppRole.GUARD` | `NavRoutes.GUARD_DASHBOARD` | Guard duty console, OB entries, patrols, handover, visitors, roster plan. Barred from administrative modules. |

---

## 4. Server-Authoritative Duty-State Machine

The Guard Duty State is derived strictly from server-provided `Shift` and `Attendance` data. The client never invents or persists independent duty states locally.

```
                      ┌────────────────────────┐
                      │    No Shift Today /    │
                      │ Shift Type = OFF /     │
                      │ Attendance = CLOCKED_OUT│
                      └───────────┬────────────┘
                                  │
                                  ▼
                        ┌───────────────────┐
                        │     OFF_DUTY      │
                        │  (Actions Locked) │
                        └─────────┬─────────┘
                                  │
                  Shift rostered  │ Attendance = NOT_CLOCKED_IN
                                  ▼
                    ┌───────────────────────────┐
                    │    ELIGIBLE_FOR_DUTY      │
                    │  (Prompt to Clock In)     │
                    └─────────────┬─────────────┘
                                  │
                  Server Clock-in │ Attendance = CLOCKED_IN
                  (GPS Validated) │
                                  ▼
                        ┌───────────────────┐
                        │      ON_DUTY      │
                        │ (Actions Unlocked)│
                        └─────────┬─────────┘
                                  │
                  Server Clock-out│ Attendance = CLOCKED_OUT
                                  ▼
                        ┌───────────────────┐
                        │     OFF_DUTY      │
                        │  (Actions Locked) │
                        └───────────────────┘
```

### Android `GuardDutyState` Definition:
```kotlin
enum class GuardDutyState {
    OFF_DUTY,          // No shift today, shift is OFF/TIME_OFF, or shift completed
    ELIGIBLE_FOR_DUTY, // Scheduled shift today, within report window (not yet clocked in)
    ON_DUTY;           // Formally clocked in on authoritative shift, actively on post

    val isOnDuty: Boolean get() = this == ON_DUTY
    val isOffDuty: Boolean get() = this == OFF_DUTY
    val isEligibleForDuty: Boolean get() = this == ELIGIBLE_FOR_DUTY

    companion object {
        fun fromShift(shift: Shift?): GuardDutyState {
            if (shift == null) return OFF_DUTY
            val shiftTypeUpper = shift.shiftType.uppercase()
            val assignmentTypeUpper = shift.assignmentType.uppercase()
            if (shiftTypeUpper == "OFF" || assignmentTypeUpper == "TIME_OFF") {
                return OFF_DUTY
            }
            return when (shift.attendanceStatus.uppercase()) {
                "CLOCKED_IN" -> ON_DUTY
                "NOT_CLOCKED_IN" -> ELIGIBLE_FOR_DUTY
                "CLOCKED_OUT", "OFF_DUTY", "ABSENT" -> OFF_DUTY
                else -> OFF_DUTY
            }
        }
    }
}
```

---

## 5. Guard OFF DUTY vs ON DUTY State Specifications

### A. OFF DUTY State
- **Condition:** No shift returned for today, shift marked `OFF`/`TIME_OFF`, or attendance marked `CLOCKED_OUT`/`OFF_DUTY`/`ABSENT`.
- **Visual Presentation:**
  - Status Pill: `○ OFF DUTY` (Neutral surface).
  - Duty Card: "Currently Off Duty. No active shift logged. Operational actions (OB, Patrol, Handover) are disabled."
  - Action Prompt: "View Guard Duty Plan" button leading to `guard_duty_plan`.
  - Module Cards: Operational cards display `LOCKED` badge and muted contrast.
- **Allowed Actions:**
  - View Today's Shift (`today_shift`)
  - View Guard Duty Plan / Upcoming Rotation (`guard_duty_plan`)
  - View Notifications (`notifications`)
  - View Profile (`profile`)
  - Change Settings & App Preferences (`settings`)
  - Submit Leave Request (`leave`)
- **Restricted Actions:**
  - Creating Occurrence Book entries (`occurrence_book`)
  - Submitting Checkpoint Patrol scans (`patrol`)
  - Completing Shift Handover (`handover`)
  - Logging Visitors / Issuing Gate Passes (`visitors`)
  - Reporting Incidents (`incidents`)
  - Triggering Emergency SOS Beacon (`emergency_sos`)
  - Escort Duties (`additional_duties`)

### B. ELIGIBLE FOR DUTY State
- **Condition:** Active shift assigned on server for today, but `attendanceStatus == "NOT_CLOCKED_IN"`.
- **Visual Presentation:**
  - Status Pill: `▲ ELIGIBLE FOR DUTY` (Amber surface).
  - Duty Card: "Scheduled at [Station Name]. You have not clocked in yet. Clock in to unlock operational logging and patrols."
  - Action Prompt: Prominent primary button "Proceed to Clock In Console" navigating to `today_shift`.
  - Module Cards: Operational cards display `LOCKED` badge until clock-in is completed.

### C. ON DUTY State
- **Condition:** Shift assigned and `attendanceStatus == "CLOCKED_IN"`.
- **Visual Presentation:**
  - Status Pill: `● ON DUTY` (Green surface).
  - Duty Card: Displays Assigned Post, Shift Window (`06:00 - 18:00`), Assigned Partner, and confirmation: "Operational logging, patrols, and occurrence book are ACTIVE."
  - Module Cards: All operational cards are unlocked and interactive with zero restriction.
- **Allowed Actions:** Full operational logging and all standard actions.

---

## 6. Navigation & Routing Implementation

### Centralized `NavRoutes` and `RoleRouter` (`RoleRouter.kt`):
- `NavRoutes` defines all canonical route constants:
  - Dashboards: `LOGIN`, `DASHBOARD`, `GUARD_DASHBOARD`, `SUPERVISOR_DASHBOARD`, `ADMIN_DASHBOARD`.
  - Guard: `TODAY_SHIFT`, `HANDOVER`, `OCCURRENCE_BOOK`, `INCIDENTS`, `PATROL`, `LEAVE`, `VISITORS`, `EMERGENCY_SOS`, `GUARD_DUTY_PLAN`.
  - Management: `ATTENDANCE_MANAGEMENT`, `ROSTER_MANAGEMENT`, `USER_MANAGEMENT`, `STATION_MANAGEMENT`, `REPORTS`, `ADDITIONAL_DUTIES`.
  - Common: `NOTIFICATIONS`, `SETTINGS`, `PROFILE`.

### Dynamic Navigation Gate in `MainActivity.kt`:
```kotlin
val safeNavigate: (String) -> Unit = { route ->
    if (!RoleRouter.isRouteAllowed(route, uiState.appRole)) {
        viewModel.postSecurityAlert("Access Denied: You do not have permission to access this module.")
    } else if (!RoleRouter.isRouteAccessible(route, uiState.appRole, uiState.guardDutyState)) {
        viewModel.postSecurityAlert("Duty Lock: You must be CLOCKED IN (On Duty) to access operational records.")
    } else {
        navController.navigate(route)
    }
}
```

### Dashboard Partitioning (`DashboardScreen.kt`):
- Guards are NEVER presented with administrative modules:
  - `attendance_management`, `roster`, `users`, `stations` are completely omitted from the Guard menu grid.
- Supervisors receive management modules but are excluded from station provisioning (`stations`).
- Administrators receive full system modules with National Control Center telemetry.

---

## 7. Off-Duty Guard Enforcement Verification

Enforcement occurs at two levels:
1. **Visual Level:** Off-duty operational cards display a distinct `LOCKED` chip, with muted opacity (`0.65f`) and lock icons.
2. **Navigation Intercept Level:** If a guard attempts to access an operational route while off-duty, `safeNavigate` intercepts the call, blocks navigation, and triggers `viewModel.postSecurityAlert("Duty Lock: You must be CLOCKED IN (On Duty) to access operational records.")`. A Material3 `Snackbar` alerts the user.
3. **Backend API Level:** The backend enforces duty status and station pairing independently on all mutating endpoints (`/api/v1/occurrence-book/`, `/api/v1/patrol-logs/`, etc.).

---

## 8. Station & Pair Context Implementation

- **Station Context:**
  - Resolved directly from server-authoritative data: `currentUser.stationName` or `todayShift.stationName`.
  - No client-side station switching is permitted for Guards.
  - Profile card and Duty status card explicitly display: `"Station: ${uiState.currentStationName ?: "Central Operations"}"`.
- **Guard Pair Context:**
  - Resolved from `ShiftSerializer`: `partner`, `partner_name`, `partner_employee_number`.
  - UI state exposes `uiState.assignedPartnerName` and `uiState.assignedPartnerEmployeeNumber`.
  - Displayed in Guard profile banner and shift duty card:
    - If paired: `"Partner: [Name] ([Employee Number])"`.
    - If unpaired: `"Partner: Solo Assignment"`.

---

## 9. Security & Auto-Lock Compliance Verification

1. **FLAG_SECURE Window Protection:**
   - Preserved in `MainActivity.onCreate()`:
     ```kotlin
     window.setFlags(
         WindowManager.LayoutParams.FLAG_SECURE,
         WindowManager.LayoutParams.FLAG_SECURE
     )
     ```
   - Prevents OS-level screen captures, recent app previews, and screen recording of sensitive security records.
2. **3-Minute Inactivity Auto-Lock:**
   - Preserved in `MainActivity.onUserInteraction()`: resets ViewModel inactivity timer.
   - `AppLockOverlay` displays over the entire screen when locked without clearing background session data or state.
3. **App Resume Re-sync (`onResume`):**
   - Implemented in `MainActivity.onResume()`:
     ```kotlin
     override fun onResume() {
         super.onResume()
         viewModelRef?.refreshAuthoritativeState()
     }
     ```
   - Whenever the app returns from background, authoritative shift and duty status are automatically fetched.

---

## 10. Android Unit Tests & Coverage

A dedicated test suite was created in `app/src/test/java/com/example/RoleAndDutyStateTest.kt` with **14 comprehensive unit test methods**:

```
RoleAndDutyStateTest
├── testAppRoleFromString_handlesAdministratorVariants [PASSED]
├── testAppRoleFromString_handlesSupervisor [PASSED]
├── testAppRoleFromString_handlesGuardAndFallbacks [PASSED]
├── testUserRoleExtensions [PASSED]
├── testDutyState_whenShiftIsNull_isOffDuty [PASSED]
├── testDutyState_whenShiftTypeIsOff_isOffDuty [PASSED]
├── testDutyState_whenAssignmentIsTimeOff_isOffDuty [PASSED]
├── testDutyState_whenClockedIn_isOnDuty [PASSED]
├── testDutyState_whenNotClockedIn_isEligibleForDuty [PASSED]
├── testDutyState_whenClockedOutOrOffDuty_isOffDuty [PASSED]
├── testRoleRouter_getDashboardRoute [PASSED]
├── testRoleRouter_guardRouteAccessControl [PASSED]
├── testRoleRouter_supervisorRouteAccessControl [PASSED]
├── testRoleRouter_adminRouteAccessControl [PASSED]
├── testRoleRouter_offDutyGuardCannotAccessOperationalRoutes [PASSED]
├── testRoleRouter_onDutyGuardCanAccessOperationalRoutes [PASSED]
└── testRoleRouter_canPerformLiveOperation [PASSED]
```

**Total Android Test Execution:** 32 tests completed, 0 failures, 0 skipped.

---

## 11. Backend API Test Suite Verification

Ran the full Django test suite (`python manage.py test tests`):
- **Discovered Tests:** 229 test cases across all Django apps.
- **Result:** `Ran 229 tests in 62.999s — OK`.
- **Failures:** 0.
- **Errors:** 0.
- **Regression:** Zero regression introduced to the Django backend.

---

## 12. Build & Verification Results

| Verification Task | Command | Result | Notes |
| :--- | :--- | :--- | :--- |
| Android Unit Tests | `.\gradlew.bat testDebugUnitTest` | **SUCCESS** | 32 tests passed in 3m 37s |
| Backend API Tests | `python backend/manage.py test tests.test_sgmis_api` | **SUCCESS** | 39 tests passed in 3.3s |
| Full Backend Suite | `python backend/manage.py test tests` | **SUCCESS** | 229 tests passed in 63.0s |
| Android Debug Build | `.\gradlew.bat assembleDebug` | **SUCCESS** | Built APK successfully in 2m 07s |

---

## 13. Files Modified & Added

### Added Files:
1. `app/src/main/java/com/example/ui/navigation/RoleRouter.kt`
   - Defines `NavRoutes` and `RoleRouter` access evaluation logic.
2. `app/src/test/java/com/example/RoleAndDutyStateTest.kt`
   - Unit tests for roles, duty state transitions, and access gates.

### Modified Files:
1. `app/src/main/java/com/example/data/model/Models.kt`
   - Added `AppRole` enum with string parser.
   - Added `GuardDutyState` enum with authoritative `fromShift` mapping.
   - Added role helper extensions on `User` (`isAdmin`, `isSupervisor`, `isGuard`, `isSupervisorOrAdmin`).
   - Added duty state helper extensions on `Shift` (`dutyState`, `isOnDuty`, `isOffDuty`, `isEligibleForDuty`).
2. `app/src/main/java/com/example/ui/viewmodel/SgmisViewModel.kt`
   - Extended `SgmisUiState` with role and duty properties (`appRole`, `guardDutyState`, `isOnDuty`, `canPerformGuardOperations`, `assignedPartnerName`, `currentStationName`).
   - Added `refreshAuthoritativeState()` to coordinate shift, station, and user re-sync.
   - Added `postSecurityAlert(message: String)`.
3. `app/src/main/java/com/example/MainActivity.kt`
   - Added `onResume()` state refresh.
   - Added `safeNavigate` navigation gate checking `RoleRouter.isRouteAllowed` and `RoleRouter.isRouteAccessible`.
   - Added `SnackbarHost` and error alert observer.
   - Registered `GUARD_DASHBOARD`, `SUPERVISOR_DASHBOARD`, and `ADMIN_DASHBOARD`.
4. `app/src/main/java/com/example/ui/screens/DashboardScreen.kt`
   - Added `roleMode: AppRole? = null` parameter for explicit dashboard variants.
   - Added `GuardDutyStatePill` showing `● ON DUTY`, `▲ ELIGIBLE FOR DUTY`, `○ OFF DUTY`.
   - Reconstructed Guard duty card showing post, shift hours, and partner.
   - Partitioned operational module grid strictly by role.
   - Added visual lock indicators for operational modules when a guard is off-duty.

---

## 14. Blueprint Alignment Matrix

| Blueprint Specification | Previous State | Phase 12.3 Implementation |
| :--- | :--- | :--- |
| Distinct Security Guard Experience | Unified dashboard showing admin modules | Dedicated Guard Console with partner/post context |
| Distinct Station Supervisor Console | Supervisor saw same menu items | Supervisor Command Status with telemetry & roster |
| Distinct National Control Center | Administrator saw standard screen | National Telemetry overview with station provisioning |
| Guard OFF DUTY vs ON DUTY State | Ambiguous attendance string | Explicit server-authoritative state machine |
| Guard Operational Module Lockout | Guards could open OB/Patrol off-duty | Cards visually locked; `safeNavigate` intercepts |
| Station & Pair Context | Hardcoded text fallback | Server-provided station & paired partner details |
| Non-Operational Access Off-Duty | Undefined | Roster, leave, notifications, profile accessible |
| Resilience & Auto-Lock | Present | Preserved with added `onResume()` state sync |

---

## 15. Architectural Safeguards Maintained

- **Zero Database Tampering:** No migrations were run; no production database records or schemas were modified.
- **Protected Accounts Unchanged:** Authoritative users `simeonmbwani` and `tavongashe` remain completely untouched and are not assigned as guards or scheduled in shifts.
- **Protected Backup Directory Untouched:** `C:\Projects\SGMIS_FIXED\sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE` was never accessed, modified, or moved.
- **Server Remains Authoritative:** The Android client cannot unilaterally force a clock-in or alter duty state without server confirmation and GPS validation.

---

## 16. Remaining Technical Debt / Next Steps

1. **Dashboard UI Polish (Phase 12.4):**
   - In Phase 12.4, rebuild the visual dashboard widgets to match the blueprint's layout, card geometry, and typography.
2. **Individual Screen Duty Guards:**
   - In Phase 12.5+, add inline duty-state banner prompts within individual screens (`OccurrenceBookScreen`, `PatrolScreen`) if deep-linked or refreshed while off-duty.

---

## 17. Readiness for Phase 12.4

The role and duty-state architecture is complete, verified, and stable. The application is fully prepared for **Phase 12.4 (Visual Dashboard Rebuild)**.

---

## 18. Sign-off & Stop Notice

Phase 12.3 is complete. Per the engineering constraints:
- **DO NOT START PHASE 12.4.**
- **STOPPED AND AWAITING INSTRUCTION.**
