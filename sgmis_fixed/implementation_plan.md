# Dynamic Guard + Supervisor-Controlled Duty Management
## Implementation Plan & System Architecture Audit

**Authoritative Project:** `C:\Projects\SGMIS_FIXED\sgmis_fixed`  
**Date:** October 2026  
**Status:** Audit Completed — Implementation Plan Ready for Execution  

---

### Executive Summary

An exhaustive read-only audit of the Smart Security application (Django backend and Jetpack Compose Android frontend) was performed. The application contains robust foundation models and operational capabilities, but exhibits specific role permission lockouts, UI fallbacks, and integration gaps that prevent it from functioning as a genuinely dynamic, supervisor-controlled guard management system.

This plan details the audit findings and provides the concrete roadmap to make the **Supervisor** the active operational authority for daily duty management, and the **Guard** application a purely dynamic reflection of supervisor-controlled assignments (supporting `DAY`, `NIGHT`, `OFF`, `ON LEAVE`, `EXAM`, and `ESCORT`), while eliminating all hardcoded fallbacks and preserving system stability without database migrations.

---

### 1. Current Architecture

#### Backend Architecture
- **Framework & Runtime:** Django 5.x, Django REST Framework, Python 3.12, SQLite (`backend/db.sqlite3`).
- **Authoritative Timezone:** CAT / Harare (`Africa/Harare`, UTC+2).
- **Core Domain Modules:**
  - `apps.accounts`: Custom `User` model with roles `GUARD`, `SUPERVISOR`, `ADMINISTRATOR`. Role-based permissions in `apps.accounts.permissions` (`IsGuard`, `IsSupervisor`, `IsAdministrator`, `IsSupervisorOrAdmin`).
  - `apps.stations`: `Station` model with geofence radius and GPS coordinates; `GuardPair` model pairing two guards (`guard_a`, `guard_b`) with `rotation_order`. Currently `GuardPairViewSet` restricts mutations to `IsAdministrator`.
  - `apps.shifts`:
    - Models: `DutyRoster`, `DutyRosterCycle`, `Shift` (`ShiftType`: `DAY`, `NIGHT`, `OFF`; `AssignmentType`: `NORMAL`, `EXAM`, `ESCORT`, `TIME_OFF`, `RELIEF`), `Attendance`, `SupervisorOverrideAudit`, `PublicHoliday`, `PublicHolidayDutyRecord`.
    - Roster Generation: 12-day rotational engine (3 pairs, 4 days ON / 8 days OFF; rotating Day and Night duties).
    - Shift Endpoints: `today`, `duty_state`, `operational`, `reassign_single` (POST `/shifts/shifts/{id}/reassign/`), `reassign_duty` (POST `/shifts/shifts/reassign_duty/`).
  - `apps.leave`: `LeaveApplication`, `LeaveBalance`, `LeaveLedger`, `LeaveAdjustmentHistory`. Synchronizes approved leaves by converting scheduled shifts on leave dates into `assignment_type=TIME_OFF`.
  - `apps.exams`: `ExamDuty` model (`guard`, `supervisor`, `station`, `institution`, `exam_title`, `date`, `start_time`, `end_time`, `status`).
  - `apps.escorts`: `EscortDuty` model (`guard`, `supervisor`, `station`, `mission_name`, `origin`, `destination`, `start_time`, `end_time`, `status`).
  - `apps.core`: `SecurityAuditEvent`, `SMSRecord`, SMS service, and Request Idempotency middleware.
  - `apps.notifications`: Real-time and persistent notification records.

#### Android Architecture
- **Framework & UI:** Jetpack Compose, Material 3, Single-Activity pattern (`MainActivity.kt`) with `RoleRouter.kt`.
- **State Management:** `SgmisViewModel.kt` exposing a unified `SgmisUiState` StateFlow.
- **Data Models:** `Models.kt` containing data classes and Moshi JSON adapters.
- **Key Screens:**
  - Guard: `DashboardScreen.kt`, `TodayShiftScreen.kt`, `GuardDutyPlanScreen.kt`, `AttendanceManagementScreen.kt`.
  - Supervisor: `DashboardScreen.kt` (Station Live Metrics, Current Shift, Roster Card), `RosterManagementScreen.kt`, `LeaveScreen.kt`, `EscortDutiesScreen.kt`, `ExamDutiesScreen.kt`, `OccurrenceBookScreen.kt`, `PatrolScreen.kt`, `VisitorBookScreen.kt`, `IncidentReportScreen.kt`, `HandoverScreen.kt`.
  - Administrator: `AdminMasterToolsScreen.kt`, `UserManagementScreen.kt`, `RecordAdjustmentsScreen.kt`.

---

### 2. Existing Real Backend & Android Flows

1. **Roster Generation Flow:** Admin or Supervisor triggers `/shifts/roster/generate/`. The engine creates a DRAFT `DutyRoster` with 12 days of paired `Shift` records (4 days ON, 8 days OFF).
2. **Attendance Flow:** Guard accesses `TodayShiftScreen.kt`, server validates GPS geofence, duty window (rejecting >30 min early or expired shifts), and records `Attendance` clock-in/clock-out.
3. **Leave Flow:** Guard submits leave application; Supervisor reviews and approves it; `services.approve_leave_application` converts scheduled shifts during leave to `assignment_type = TIME_OFF`.
4. **Special Duty Flow:** Supervisor creates `ExamDuty` or `EscortDuty` records; guards view them in dedicated screens.
5. **Early Clock-Out OTP Flow:** Guard requests early clock-out; Supervisor generates single-use hashed OTP; server verifies before clocking out.

---

### 3. Critical Gaps, Fallbacks & Anti-Patterns Identified

#### A. Role Responsibility Inversions & Supervisor Lockout
1. **Supervisor Blocked from Reassigning Shifts (HTTP 403 Forbidden):**
   In `backend/apps/shifts/views.py:232`, `reassign_single` is decorated with `permission_classes=[IsAdministrator]`. When a supervisor attempts to reassign a single shift or assign relief for their station, the backend rejects it with 403 Forbidden.
2. **Supervisor Blocked in Android Roster Management UI:**
   In `RosterManagementScreen.kt:765`, the "Reassign This Shift" action button is conditionally wrapped in `if (isAdmin)`. Even if the supervisor opens the roster, they cannot trigger single-shift reassignment. Furthermore, line 70 only fetches guards if `isAdmin`, leaving the guard picker list empty for supervisors.
3. **Supervisor Blocked from Managing Guard Pairs:**
   In `backend/apps/stations/views.py:75`, `GuardPairViewSet` limits create/update/delete to `IsAdministrator`. A supervisor cannot create or adjust guard pairs for their own station.

#### B. Fallback Guard Selections & Data Leaks
1. **Dangerous Fallback Guard Selection in `GuardDutyPlanScreen.kt:74`:**
   ```kotlin
   val filtered = shifts.filter { s ->
       (uid != null && s.guard == uid) ||
       (uName != null && s.guardName.equals(uName, ignoreCase = true)) ||
       (emp != null && s.employeeNumber == emp)
   }
   if (filtered.isNotEmpty()) filtered else shifts
   ```
   If a guard has no scheduled shifts on the station roster, the screen falls back to showing **ALL station shifts**, presenting another guard's duties as the logged-in guard's schedule!
2. **Partner Shift Leak in Guard Dashboard (`DashboardScreen.kt:124-132`):**
   ```kotlin
   val nextDutyShift = remember(uiState.rosterShifts, todayStr) {
       uiState.rosterShifts
           .filter { s -> s.date >= todayStr && s.shiftType != "OFF" && s.assignmentType != "TIME_OFF" }
           .minByOrNull { it.date }
   }
   ```
   Because `/shifts/shifts/operational/` returns pair shifts for both Guard A and Guard B, an off-duty guard sees their partner's upcoming shift as their own next duty!
3. **Hardcoded Fallback String in `GuardDutyPlanScreen.kt:173`:**
   `text = "Service ID: ${user?.employeeNumber ?: "SEC-ACTIVE"} • Post: ${uiState.currentStationName}"`
   Contains the hardcoded fallback `"SEC-ACTIVE"`.

#### C. Incomplete Dynamic Duty States (`EXAM` & `ESCORT`)
1. **Backend Ignored Exam & Escort in `duty_state` and `today`:**
   In `apps/shifts/views.py:339` and `428`, `duty_state` only checks `Shift` and `LeaveApplication`. If a guard is assigned to an `ExamDuty` or `EscortDuty` today, the backend returns `shift=None` and `duty_state="OFF_DUTY"`.
2. **Android `GuardDutyState` Missing Special Duties:**
   In `Models.kt:30-36`, `GuardDutyState` only defines `OFF_DUTY`, `ELIGIBLE_FOR_DUTY`, `ON_DUTY`, `TIME_OFF`, `ON_LEAVE`, and `EARLY_EXIT_PENDING`. It completely lacks `EXAM` and `ESCORT`.
   In `SgmisViewModel.kt:126-133`, any state outside these defaults falls through to `OFF_DUTY` ("Time Off / Resting").
3. **Guard Dashboard Lacks UI Cards for Special Duties:**
   When assigned to an Exam or Escort mission, the guard sees "TIME OFF / RESTING" instead of an authoritative Mission / Exam briefing card.

#### D. Absence of Station Coverage Warnings & Swap Workflow
1. **No Station Coverage Warning on Supervisor Dashboard:**
   When a shift is left uncovered (e.g., because a guard was granted leave, or no guard was assigned), the supervisor dashboard in `DashboardScreen.kt` simply renders a list of scheduled shifts. There is **zero warning banner** indicating "CRITICAL: DAY SHIFT UNCOVERED" or "NIGHT SHIFT UNCOVERED".
2. **No Interactive Pair Swap or Relief Assignment Workflow:**
   There is no UI or dedicated backend endpoint for a supervisor to swap Day and Night guards within a pair or assign an available relief guard directly from the supervisor console.
3. **Missing Availability & Conflict Checks:**
   When reassigning shifts, the backend only checks for existing shifts (`Shift.objects.filter(...)`), but fails to check whether the relief guard is on **approved leave**, assigned to an **ExamDuty**, or assigned to an **EscortDuty**.
4. **Random Selection in Auto-Allocation:**
   `apps/escorts/views.py:214` and `apps/exams/views.py:204` use `random.sample()` instead of checking availability or allowing explicit supervisor control.

---

### 4. What Supervisor Can Change vs MUST Be Able to Change

| Function | Current State | Target State (MUST) |
|---|---|---|
| Reassign single shift | Blocked (Admin only, HTTP 403) | **Permitted for Supervisor** on own station shifts with conflict validation |
| Swap Day/Night guards in a pair | Not supported | **Dedicated Supervisor endpoint & UI action** to swap pair duties on date D |
| Assign relief guard to uncovered shift | Blocked | **Supervisor can select eligible relief guard** (filtered: not on leave, not on exam/escort, no active shift) |
| View station coverage alerts | Not present | **Prominent warning banner** on supervisor dashboard if Day or Night shift is uncovered |
| Manage guard pairs | Blocked (Admin only) | **Supervisor can create/manage pairs** for their own station |

---

### 5. What Guard Currently Sees vs MUST See Dynamically

| Guard Operational State | Current View | Target Dynamic View (MUST) |
|---|---|---|
| Scheduled DAY shift | Day shift card | **DAY DUTY card** with post, reporting window, clock-in button |
| Scheduled NIGHT shift | Night shift card | **NIGHT DUTY card** with post, overnight hours, clock-in button |
| Off-duty / Rest day | Time Off card (or partner's shift!) | **OFF-DUTY card** displaying strictly the guard's own next duty date |
| Approved Leave | On Leave card | **ON LEAVE card** showing leave type, dates, and remaining balance |
| Exam Duty assigned | "Time Off / Resting" (BUG) | **EXAM DUTY card** showing institution, exam title, reporting time, instructions |
| Escort Duty assigned | "Time Off / Resting" (BUG) | **ESCORT DUTY card** showing mission name, route (origin -> destination), times |

---

### 6. Safe Changes Required

#### Backend Changes:
1. **`backend/apps/shifts/views.py`**:
   - Update `reassign_single`: Change permissions to `[IsSupervisorOrAdmin]`. Enforce station boundary for supervisors (`shift.station == request.user.station`).
   - Add conflict checks in `reassign_single`:
     - Exclude guards with approved `LeaveApplication` covering `shift.date`.
     - Exclude guards with active `ExamDuty` on `shift.date`.
     - Exclude guards with active `EscortDuty` overlapping `shift.date`.
   - Add `@action(detail=False, methods=["post"], url_path="swap_pair_duties", permission_classes=[IsSupervisorOrAdmin])`:
     - Swaps the assignments of Guard A and Guard B in a pair for a given date or effective period.
     - Automatically updates shift records and creates `SupervisorOverrideAudit` + `SecurityAuditEvent` + `Notification`.
   - Add `@action(detail=False, methods=["get"], url_path="station_coverage", permission_classes=[IsSupervisorOrAdmin])`:
     - Returns station coverage status for a given date (`date`, `is_day_covered`, `day_guard`, `is_night_covered`, `night_guard`, `coverage_warning`, `uncovered_shifts`, `available_relief_guards`).
   - Update `duty_state` and `today` endpoints:
     - Check for active `ExamDuty` (`status in [ASSIGNED, ACKNOWLEDGED, IN_PROGRESS]`). If found, set `duty_state = "EXAM"` and attach exam details.
     - Check for active `EscortDuty` (`status in [SCHEDULED, ASSIGNED, ACKNOWLEDGED, EN_ROUTE]`). If found, set `duty_state = "ESCORT"` and attach escort details.
2. **`backend/apps/stations/views.py`**:
   - Update `GuardPairViewSet.get_permissions`: Allow `IsSupervisorOrAdmin` for pair creation/editing, scoping supervisors to their assigned station.
3. **`backend/apps/exams/views.py` & `backend/apps/escorts/views.py`**:
   - Exclude guards on approved leave and guards with conflicting special duties during candidate selection.
4. **`backend/tests/test_authoritative_attendance_security.py`**:
   - Fix midnight rollover in line 132 (`future_time = ...`) to prevent test failure during late-night test execution.

#### Android Changes:
1. **`Models.kt`**:
   - Add `EXAM` and `ESCORT` to `GuardDutyState` enum.
   - Update `fromShift` companion method to handle `EXAM` and `ESCORT`.
   - Add `ExamDuty` and `EscortDuty` fields to `ServerDutyState`.
2. **`SgmisViewModel.kt`**:
   - Map `"EXAM"` and `"ESCORT"` from server duty state to `GuardDutyState.EXAM` and `GuardDutyState.ESCORT`.
   - Add state flows and methods: `fetchStationCoverage(stationId, date)`, `swapPairDuties(...)`, `reassignSingleShift(...)`.
   - Ensure `fetchUsers(role="GUARD")` is called for supervisors to populate relief guard selectors.
3. **`GuardDutyPlanScreen.kt`**:
   - Remove line 74 fallback (`if (filtered.isNotEmpty()) filtered else shifts`). If empty, return empty list and display an informative empty state.
   - Remove `"SEC-ACTIVE"` fallback in line 173.
4. **`DashboardScreen.kt`**:
   - Fix line 124: Strictly filter `nextDutyShift` to the logged-in guard.
   - Add Guard Dashboard state cards for `GuardDutyState.EXAM` and `GuardDutyState.ESCORT`.
   - Add Supervisor Station Coverage Warning Banner (`CRITICAL: DAY/NIGHT SHIFT UNCOVERED`).
   - Add Supervisor "MANAGE DUTY / RELIEF" dialog/sheet enabling:
     - Day <-> Night pair swap.
     - Relief guard assignment with live availability checking.
5. **`RosterManagementScreen.kt`**:
   - Allow supervisors (`isSupervisor || isAdmin`) to reassign single shifts.

---

### 7. Files Modified vs Protected

#### Files That WILL Be Modified:
- `backend/apps/shifts/views.py`
- `backend/apps/stations/views.py`
- `backend/apps/exams/views.py`
- `backend/apps/escorts/views.py`
- `backend/tests/test_authoritative_attendance_security.py`
- `backend/tests/test_dynamic_duty_management.py` (New comprehensive test suite)
- `app/src/main/java/com/example/data/model/Models.kt`
- `app/src/main/java/com/example/ui/viewmodel/SgmisViewModel.kt`
- `app/src/main/java/com/example/ui/screens/DashboardScreen.kt`
- `app/src/main/java/com/example/ui/screens/GuardDutyPlanScreen.kt`
- `app/src/main/java/com/example/ui/screens/RosterManagementScreen.kt`
- `app/src/test/java/com/example/RoleAndDutyStateTest.kt`

#### Files That Must NOT Be Modified:
- `C:\Projects\SGMIS_FIXED\sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE` (**ABSOLUTE PROHIBITION**)
- `C:\Projects\Smart Security Original Blueprint.zip` (**DO NOT MODIFY**)
- `backend/apps/leave/models.py` & core leave balance accrual services
- `backend/apps/core/models.py` (security audit models)
- Existing Django migrations (**NO NEW MIGRATIONS REQUIRED**)

---

### 8. Database Impact
- **Zero Migrations Required:** All necessary tables and columns (`Shift`, `GuardPair`, `Station`, `ExamDuty`, `EscortDuty`, `Attendance`, `LeaveApplication`) exist and already have full support for `shift_type`, `assignment_type`, `status`, and relationships.
- **Zero Database Resets:** The database is not dropped, wiped, or modified structurally. Existing operational records remain preserved.

---

### 9. Test Verification Plan (10 Mandatory Scenarios)

| # | Scenario | Verification Method |
|---|---|---|
| 1 | Guard pair creation & assignment | Backend API test: Supervisor/Admin creates `GuardPair` for station; verified via `/stations/pairs/`. |
| 2 | Supervisor changing day guard to night guard | Backend API test: Supervisor updates shift to `NIGHT`; shift saved, audit created, notification sent. |
| 3 | Supervisor changing night guard to day guard | Backend API test: Supervisor updates shift to `DAY`; shift saved, audit created, notification sent. |
| 4 | Supervisor swapping pair guards | Backend API test: Call `/shifts/shifts/swap_pair_duties/`; Guard A and Guard B swap Day/Night duties on target date. |
| 5 | Supervisor assigning relief guard to uncovered shift | Backend API test: Uncovered shift assigned to an off-duty guard; guard becomes active, shift marked `RELIEF`. |
| 6 | Guard on leave unavailable for shift | Backend API test: Attempting to assign a guard on approved leave to a shift returns HTTP 409 Conflict. |
| 7 | Guard on exam duty unavailable for normal shift | Backend API test: Attempting to assign a guard on active `ExamDuty` to a shift returns HTTP 409 Conflict. |
| 8 | Guard on escort duty unavailable for normal shift | Backend API test: Attempting to assign a guard on active `EscortDuty` to a shift returns HTTP 409 Conflict. |
| 9 | Guard app dynamically showing correct state | Android Unit & ViewModel tests: Guard duty states evaluated for `DAY`, `NIGHT`, `OFF`, `ON_LEAVE`, `EXAM`, `ESCORT`. |
| 10 | Supervisor dashboard showing station coverage warning | Backend & Android tests: Uncovered shift produces `coverage_warning` on `/station_coverage/` and renders banner in UI. |

---

### 10. Execution Summary & Safety Assurances
- **No changes have been made during this audit phase.**
- Execution will proceed only upon user review and authorization.
- Full test suites (Django tests and Android `./gradlew testDebugUnitTest` + `assembleDebug`) will be verified before final completion.
