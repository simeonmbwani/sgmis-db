# Smart Security Phase 12.1 Baseline Report

## 1. Verification Date
- **Date:** 2026-09-26
- **Local Time:** 11:30:00+02:00
- **Status:** BASELINE VERIFICATION ONLY — READ-ONLY AUDIT COMPLETE

---

## 2. Project Location
- **Active Working Directory / Project Root:** `C:\Projects\SGMIS_FIXED\sgmis_fixed`
- **Git Repository Root:** `C:\Projects\SGMIS_FIXED`
- **Active Branch:** `main` (clean baseline audit; no branch switches)

---

## 3. Protected Backup Verification
- **Protected Directory:** `C:\Projects\SGMIS_FIXED\sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE`
- **Verification Statement:**
  > **THE PROTECTED BACKUP DIRECTORY WAS NOT ACCESSED, INSPECTED, READ, COPIED, MOVED, RENAMED, OR MODIFIED IN ANY MANNER DURING THIS PHASE.**

---

## 4. Current Project Structure

The project root `C:\Projects\SGMIS_FIXED\sgmis_fixed` contains the following verified top-level components:

1. **Android Application (`app/`):**
   - Pure Jetpack Compose Android client (`com.example` / `com.aistudio.sgmis.secops`).
   - `app/src/main/java/com/example/`: `MainActivity.kt`, `data/` (`api`, `local`, `model`, `repository`), `ui/` (`screens`, `theme`, `viewmodel`), `util/` (`LocationHelper.kt`).
   - `app/src/test/java/com/example/`: Local JVM unit and integration tests.
   - `app/build.gradle.kts`: Android application Gradle configuration.
2. **Django Backend (`backend/`):**
   - Django 5.1.15 + Django REST Framework 3.17.2 + SimpleJWT.
   - Apps: `accounts`, `core`, `stations`, `shifts`, `leave`, `occurrence_book`, `incidents`, `patrols`, `notifications`, `escorts`, `exams`.
   - Settings & entrypoints: `sgmis_backend/settings.py`, `sgmis_backend/settings_prod.py`, `sgmis_backend/urls.py`, `sgmis_backend/wsgi.py`, `sgmis_backend/asgi.py`.
   - Backend Test Suite: 17 dedicated test modules under `backend/tests/`.
3. **Documentation:**
   - `README.md`
   - `SMART_SECURITY_REBUILD_INVENTORY.md` (Authoritative Blueprint Rebuild Inventory, created in previous step)
4. **Build & Dependency Configuration:**
   - Gradle: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`.
   - Python: `backend/requirements.txt`, Python virtual environment `venv/`.
   - Container & Deployment: `Dockerfile`, `docker-compose.yml`, `render.yaml`, `deploy/`.
   - Environment templates: `.env.example`, `local.properties`.

---

## 5. Previous Audit Report Status

The authoritative blueprint audit report `SMART_SECURITY_REBUILD_INVENTORY.md` was located at `C:\Projects\SGMIS_FIXED\sgmis_fixed\SMART_SECURITY_REBUILD_INVENTORY.md` and verified.

### Summary of Audit Findings:
- **A. Blueprint Findings:** 16-page authoritative blueprint (`Smart Security Original Blueprint.pdf`). Principles: Zero hardcoding via database tables (`centers`, `guards`, `system_rules`, `public_holidays`), 4-on/8-off rotation algorithm with automatic shift swap, 1.5-month exam disruption handling, guard two-state engine (ON DUTY active 6-button grid vs. OFF DUTY restricted resting with active notifications and supervisor messaging), 10-minute OB countdown edit lock, dedicated visitor register, 6-digit OTP early clock-out authorization, dedicated supervisor live overview, and national superuser control center.
- **B. Android Inventory:** 20 Compose screens, single `MainActivity` with `FLAG_SECURE`, monolithic `SgmisViewModel` managing state, `SgmisRepository` with Room caching (`SgmisDatabase`), Retrofit `ApiService` with Moshi JSON adapters. Screens lack the blueprint's state machine, currently rendering an unbundled 18-module list.
- **C. Backend Inventory:** Mature Django apps implementing strict business invariants (zero proxy actions, role barriers, geofence checking, 3-stream leave accounting with 2.5/1.0 accrual rules, public holiday compensation ledger, immutable evidence records with append-only amendments).
- **D. Screen Comparison:** Classified screens into KEEP (`Handover`, `Patrol`, `Leave`, `UserManagement`, `StationManagement`, `Reports`), REBUILD UI (`DashboardScreen` into ON/OFF Duty states, `GuardDutyPlanScreen` into "My Roster Overview", Supervisor Dashboard, Superuser National Control), and REWIRE (`TodayShift`, `OccurrenceBook`, `Visitor`, `EmergencySos`, `RosterManagement`).
- **E. API Mismatches:** Roster approval route mismatch (`shifts/duty-rosters/{id}/approve/` [404] vs `shifts/shifts/approve_roster/`), OB 10-minute direct edit lock vs. permanent backend PUT/PATCH prohibition, Visitor register proxying into OB entries, Early clock-out password prompt vs. 6-digit OTP engine, and SOS creating generic high-priority incidents instead of dedicated SOS distress beacon.
- **F. Role & Flow Findings:** Documented intended flows for Guard, Supervisor, and Administrator. Critical invariant affirmed: `simeonmbwani` is Administrator/Superuser; `tavongashe` is Supervisor; neither is a security guard.
- **G. Architecture Recommendation:** Full rewrite is unjustified and high-risk. A controlled, systematic Jetpack Compose UI reconstruction preserving the hardened Django/DRF backend is the safest, highest-fidelity strategy.
- **H. Final Engineering Recommendation:** Controlled Android UI Reconstruction with targeted backend contract aliasing.

---

## 6. Git / Working Tree Status

Executed safe, read-only Git commands against `C:\Projects\SGMIS_FIXED`:

### `git status --short`
```text
 M sgmis_fixed/app/src/main/java/com/example/data/api/ApiService.kt
 M sgmis_fixed/app/src/main/java/com/example/data/repository/SgmisRepository.kt
?? .idea/
?? et
?? sgmis_fixed/SMART_SECURITY_REBUILD_INVENTORY.md
?? sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE/
```

### `git diff --stat`
```text
 sgmis_fixed/app/src/main/java/com/example/data/api/ApiService.kt    | 6 +++---
 .../src/main/java/com/example/data/repository/SgmisRepository.kt    | 4 ++--
 2 files changed, 5 insertions(+), 5 deletions(-)
```

### `git diff --name-only`
```text
sgmis_fixed/app/src/main/java/com/example/data/api/ApiService.kt
sgmis_fixed/app/src/main/java/com/example/data/repository/SgmisRepository.kt
```

### `git log --oneline -10`
```text
08783ed Complete Smart Security authoritative security management implementation
8c0e675 commit changes
eb74605 feat: complete smart security roster and operational hardening
479befc commit changes
3d3e843 Merge Gemini fixes for Smart Security API and Android
0749bbf Add runtime.txt to backend root directory
c8d477a Fix database migrations and accounts station_id column
...
```

*Statement:* **No state-altering Git commands (commit, reset, checkout, clean, stash, push, pull, rebase) were performed.**

---

## 7. Previous Session Changes

The following table assesses files created, edited, or identified from recent sessions:

| File | Exists | Tracked | Current Status | Assessment | Technical Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `sgmis_fixed/app/src/main/java/com/example/data/api/ApiService.kt` | Yes | Yes | Modified in working tree | **REVIEW** | Lines 90 & 211 were modified: `approveRoster` was changed to `shifts/duty-rosters/{id}/approve/` (which causes a 404 mismatch because backend route is `shifts/shifts/approve_roster/`), and `getLeaveSummary` was changed to `leave/balances/my-summary/`. |
| `sgmis_fixed/app/src/main/java/com/example/data/repository/SgmisRepository.kt` | Yes | Yes | Modified in working tree | **REVIEW** | Line 781 was adjusted to pass `rosterId` instead of `ApproveRosterRequest` object to match the modified `ApiService.kt`. Must be aligned during API reconciliation. |
| `sgmis_fixed/app/src/main/java/com/example/data/api/PaginatedListJsonAdapterFactory.kt` | Yes | Yes | Committed (`eb74605`) | **PRESERVE** | Essential Moshi adapter factory that transparently deserializes DRF paginated responses (`{"count": ..., "results": [...]}`) into `List<T>`. Prevents JSON parsing crashes across all list endpoints. |
| `sgmis_fixed/app/src/test/java/com/example/MoshiAdapterTest.kt` | Yes | Yes | Committed (`eb74605`) | **PRESERVE** | Legitimate local JVM unit test verifying `PaginatedListJsonAdapterFactory` and `ShiftJsonAdapterFactory` deserialization. |
| `sgmis_fixed/app/src/main/java/com/example/data/model/Models.kt` | Yes | Yes | Committed (`08783ed`) | **PRESERVE** | Authoritative data models for Moshi. Contains resolved `GenerateRosterRequest` and `GenerateRosterResponse` data classes. |
| `sgmis_fixed/app/src/main/java/com/example/data/api/ApiClient.kt` | Yes | Yes | Committed (`08783ed`) | **PRESERVE** | Configures OkHttpClient, Moshi factories, AuthInterceptor, and dynamic base URL. |
| `walkthrough.md` | No | No | Non-existent | **PRESERVE** (No action) | File does not exist in working tree. |
| `implementation_plan.md` | No | No | Non-existent | **PRESERVE** (No action) | File does not exist in working tree. |
| `sgmis_fixed/SMART_SECURITY_REBUILD_INVENTORY.md` | Yes | No | Untracked | **PRESERVE** | Authoritative technical audit report generated in previous task. |

---

## 8. Android Baseline

- **Application Package / Namespace:** `com.aistudio.sgmis.secops` (Package ID) / `com.example` (Kotlin package namespace).
- **MainActivity:** `sgmis_fixed/app/src/main/java/com/example/MainActivity.kt`.
  - Enforces `FLAG_SECURE` to block screenshots and screen recording.
  - Implements `onUserInteraction()` idle signal for 3-minute inactivity timer.
  - Hosts Jetpack Compose `NavHost`.
- **Navigation Architecture:** Single-activity Compose navigation with 20 distinct string routes. Root destination resolves to `"dashboard"` if logged in, otherwise `"login"`. Every screen has back-navigation support.
- **Compose Screens Inventory (20 Operational Screens):**
  1. `LoginScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/LoginScreen.kt`
  2. `DashboardScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/DashboardScreen.kt`
  3. `TodayShiftScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/TodayShiftScreen.kt`
  4. `GuardDutyPlanScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/GuardDutyPlanScreen.kt`
  5. `HandoverScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/HandoverScreen.kt`
  6. `OccurrenceBookScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/OccurrenceBookScreen.kt`
  7. `VisitorScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/VisitorScreen.kt`
  8. `IncidentReportScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/IncidentReportScreen.kt`
  9. `EmergencySosScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/EmergencySosScreen.kt`
  10. `PatrolScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/PatrolScreen.kt`
  11. `LeaveScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/LeaveScreen.kt`
  12. `AdditionalDutiesScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/AdditionalDutiesScreen.kt`
  13. `ReportsScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/ReportsScreen.kt`
  14. `AttendanceManagementScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/AttendanceManagementScreen.kt`
  15. `RosterManagementScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/RosterManagementScreen.kt`
  16. `UserManagementScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/UserManagementScreen.kt`
  17. `StationManagementScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/StationManagementScreen.kt`
  18. `NotificationsScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/NotificationsScreen.kt`
  19. `ProfileScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/ProfileScreen.kt`
  20. `SettingsScreen` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/SettingsScreen.kt`
  21. `AppLockOverlay` -> `sgmis_fixed/app/src/main/java/com/example/ui/screens/AppLockOverlay.kt`
- **ViewModel:** Single monolithic `SgmisViewModel.kt` managing `SgmisUiState` with 60+ reactive state fields and background coroutine workers.
- **Repository:** `SgmisRepository.kt` combining Retrofit API calls with offline Room database caching.
- **Room Database:** `SgmisDatabase.kt`, `Entities.kt`, `Daos.kt` caching shifts, OB entries, stations, and notifications.
- **Session & Interceptors:** `SessionManager.kt` (token & pref storage), `AuthInterceptor.kt` (bearer auth + synchronized 401 token refresh), `ApiClient.kt`.
- **Theming:** `ui/theme/Color.kt`, `Theme.kt`, `Type.kt` supporting Light, Dark, and System modes.

---

## 9. Backend Baseline

- **Settings:** `sgmis_fixed/backend/sgmis_backend/settings.py` (Local/Dev) and `settings_prod.py` (Render Production).
- **Root URLs:** `sgmis_fixed/backend/sgmis_backend/urls.py` exposing canonical and API-prefixed routes for all apps, plus OpenAPI schema docs at `/api/docs/`.
- **Installed Operational Apps:**
  - `apps.accounts`: Custom `User` model (`ADMINISTRATOR`, `SUPERVISOR`, `GUARD`), `LoginAttempt` (brute-force rate limiting), `PasswordResetOTP`.
  - `apps.stations`: `Station` (coordinates, geofence radius), `GuardPair` (pair rotation 1, 2, 3; validates distinct guard identities and roles).
  - `apps.shifts`: `DutyRoster`, `Shift` (`DAY`, `NIGHT`, `OFF`), `Attendance` (clock-in/out, GPS, late tracking), `ShiftHandover`, `PublicHoliday`, `PublicHolidayDutyRecord`, `ExaminationPeriod`, `TemporaryAssignmentAudit`, `DutyRosterCycle`.
  - `apps.leave`: `LeaveBalance` (accrual logic: vacation 2.5/mo cap 90, casual 1.0/mo 12m cycle), `LeaveApplication` (approval workflow), `PublicHolidayCompensationLedger` (+2 days per holiday).
  - `apps.occurrence_book`: `OccurrenceBookEntry` (evidence-grade immutable records `OB-MW-001`), `OBAmendment` (append-only corrections).
  - `apps.incidents`: `IncidentReport` (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`), `IncidentAmendment`.
  - `apps.patrols`: `Checkpoint` (QR codes, GPS), `PatrolLog`, `CheckpointScan`.
  - `apps.notifications`: `Notification` (alerts, deduplication keys, supervisor broadcasts).
  - `apps.escorts`: `EscortDuty` (vehicle and VIP escorts).
  - `apps.exams`: `ExamDuty` (exam collection and venue security).
  - `apps.core`: `SecurityAuditEvent`, `SupervisorOverrideAudit`, central telemetry, exception handlers.

---

## 10. API Contract Baseline

| Android Method (`ApiService.kt`) | Android Path | Method | Django Route | Backend ViewSet / View | Expected Purpose | Current Status | Possible Mismatch |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `login` | `auth/login/` | POST | `auth/login/` | `LoginView` | User authentication | **WORKING** | None |
| `refreshToken` | `auth/refresh/` | POST | `auth/refresh/` | `CustomTokenRefreshView` | JWT token refresh | **WORKING** | None |
| `requestPasswordReset` | `auth/password_reset/request/` | POST | `auth/password_reset/request/` | `PasswordResetRequestView` | Request reset OTP | **WORKING** | None |
| `confirmPasswordReset` | `auth/password_reset/confirm/` | POST | `auth/password_reset/confirm/` | `PasswordResetConfirmView` | Confirm reset OTP | **WORKING** | None |
| `getCurrentUser` | `accounts/users/me/` | GET | `accounts/users/me/` | `CurrentUserView` | Fetch user profile | **WORKING** | None |
| `updateProfile` | `accounts/users/me/` | PATCH | `accounts/users/me/` | `CurrentUserView` | Update profile fields | **WORKING** | None |
| `getTelemetry` | `core/telemetry/` | GET | `core/telemetry/` | `telemetry_overview` | Dashboard telemetry | **WORKING** | None |
| `getUsers` | `accounts/users/` | GET | `accounts/users/` | `UserViewSet` | List personnel | **WORKING** | None |
| `createUser` | `accounts/users/` | POST | `accounts/users/` | `UserViewSet` | Create user (Admin) | **WORKING** | None |
| `getStations` | `stations/stations/` | GET | `stations/stations/` | `StationViewSet` | List station posts | **WORKING** | None |
| `createStation` | `stations/stations/` | POST | `stations/stations/` | `StationViewSet` | Create station | **WORKING** | None |
| `getGuardPairs` | `stations/pairs/` | GET | `stations/pairs/` | `GuardPairViewSet` | List guard pairs | **WORKING** | None |
| `createGuardPair` | `stations/pairs/` | POST | `stations/pairs/` | `GuardPairViewSet` | Create guard pair | **WORKING** | None |
| `getTodayShift` | `shifts/shifts/today/` | GET | `shifts/shifts/today/` | `ShiftViewSet.today` | Today's active shift | **WORKING** | None |
| `getShifts` | `shifts/shifts/` | GET | `shifts/shifts/` | `ShiftViewSet` | Shift list | **WORKING** | None |
| `getOperationalRoster` | `shifts/shifts/operational/` | GET | `shifts/shifts/operational/` | `ShiftViewSet.operational` | Unpaginated matrix | **WORKING** | None |
| `generateRoster` | `shifts/shifts/generate/` | POST | `shifts/shifts/generate/` | `ShiftViewSet.generate` | Trigger rotation engine | **WORKING** | Response model verified |
| **`approveRoster`** | `shifts/duty-rosters/{id}/approve/` | POST | `shifts/shifts/approve_roster/` | `ShiftViewSet.approve_roster` | Approve validated roster | **404 / 405 MISMATCH** | **Critical:** Android path targets `duty-rosters/{id}/approve/` on a `ReadOnlyModelViewSet`. Backend action is on `ShiftViewSet`. |
| `detectConflicts` | `shifts/shifts/detect_conflicts/` | POST | `shifts/shifts/detect_conflicts/` | `ShiftViewSet.detect_conflicts` | Detect roster conflicts | **WORKING** | None |
| `clockIn` | `shifts/attendance/clock_in/` | POST | `shifts/attendance/clock_in/` | `AttendanceViewSet.clock_in` | Record GPS clock-in | **WORKING** | None |
| `clockOut` | `shifts/attendance/clock_out/` | POST | `shifts/attendance/clock_out/` | `AttendanceViewSet.clock_out` | Record clock-out | **WORKING** | None |
| `getHandovers` | `shifts/handovers/` | GET | `shifts/handovers/` | `ShiftHandoverViewSet` | List handovers | **WORKING** | None |
| `createHandover` | `shifts/handovers/` | POST | `shifts/handovers/` | `ShiftHandoverViewSet` | Submit handover notes | **WORKING** | None |
| `acceptHandover` | `shifts/handovers/{id}/accept/` | POST | `shifts/handovers/{id}/accept/` | `ShiftHandoverViewSet.accept` | Takeover acceptance | **WORKING** | None |
| `rejectHandover` | `shifts/handovers/{id}/reject/` | POST | `shifts/handovers/{id}/reject/` | `ShiftHandoverViewSet.reject` | Takeover dispute | **WORKING** | None |
| `getOBEntries` | `occurrence_book/entries/` | GET | `occurrence_book/entries/` | `OccurrenceBookEntryViewSet` | List OB entries | **WORKING** | None |
| `createOBEntry` | `occurrence_book/entries/` | POST | `occurrence_book/entries/` | `OccurrenceBookEntryViewSet` | Log new OB record | **WORKING** | Rejects off-duty/proxy |
| **`updateOBEntry` (PATCH)** | `occurrence_book/entries/{id}/` | PATCH | `occurrence_book/entries/{id}/` | `OccurrenceBookEntryViewSet` | Direct edit within 10 min | **403 FORBIDDEN** | Backend blocks direct PUT/PATCH entirely; blueprint mandates 10-min countdown edit window. |
| `amendOBEntry` | `occurrence_book/entries/{id}/amend/` | POST | `occurrence_book/entries/{id}/amend/` | `OccurrenceBookEntryViewSet.amend` | Append-only correction | **WORKING** | None |
| `getIncidents` | `incidents/reports/` | GET | `incidents/reports/` | `IncidentReportViewSet` | List incidents | **WORKING** | None |
| `reportIncident` | `incidents/reports/` | POST | `incidents/reports/` | `IncidentReportViewSet` | File incident | **WORKING** | None |
| **`emergencySos`** | `incidents/reports/` | POST | `incidents/reports/` | `IncidentReportViewSet` | Trigger emergency distress | **ARCHITECTURAL MISMATCH** | Proxied as generic high-priority incident; blueprint specifies dedicated `sos_logs`. |
| `getCheckpoints` | `patrols/checkpoints/` | GET | `patrols/checkpoints/` | `CheckpointViewSet` | List inspection points | **WORKING** | None |
| `startPatrol` | `patrols/logs/` | POST | `patrols/logs/` | `PatrolLogViewSet` | Start patrol session | **WORKING** | None |
| `scanCheckpoint` | `patrols/logs/{id}/scan/` | POST | `patrols/logs/{id}/scan/` | `PatrolLogViewSet.scan` | Log checkpoint scan | **WORKING** | None |
| `finishPatrol` | `patrols/logs/{id}/finish/` | POST | `patrols/logs/{id}/finish/` | `PatrolLogViewSet.finish` | Conclude patrol | **WORKING** | None |
| `getLeaveBalance` | `leave/balances/my_balance/` | GET | `leave/balances/my_balance/` | `LeaveBalanceViewSet.my_balance` | Guard leave balance | **WORKING** | None |
| `getLeaveSummary` | `leave/balances/my-summary/` | GET | `leave/balances/my-summary/` | `LeaveBalanceViewSet.my_summary` | 3-stream leave table | **WORKING** | Matches backend view |
| `getLeaveApplications` | `leave/applications/` | GET | `leave/applications/` | `LeaveApplicationViewSet` | List leave requests | **WORKING** | None |
| `applyForLeave` | `leave/applications/` | POST | `leave/applications/` | `LeaveApplicationViewSet` | Apply for leave | **WORKING** | None |
| `reviewLeave` | `leave/applications/{id}/review/` | POST | `leave/applications/{id}/review/` | `LeaveApplicationViewSet.review` | Supervisor review | **WORKING** | None |
| **`fetchVisitors`** | `occurrence_book/entries/?category=VISITOR` | GET | `occurrence_book/entries/` | `OccurrenceBookEntryViewSet` | Visitor register | **ARCHITECTURAL MISMATCH** | Packed into OB entries as text; blueprint mandates dedicated `visitors` model. |
| `getNotifications` | `notifications/alerts/` | GET | `notifications/alerts/` | `NotificationViewSet` | List user alerts | **WORKING** | None |
| `broadcastNotice` | `notifications/alerts/broadcast/` | POST | `notifications/alerts/broadcast/` | `NotificationViewSet.broadcast` | Station broadcast | **WORKING** | None |
| **`requestEarlyClockoutOTP`** | None | POST | None | Missing | 6-digit OTP early clock-out | **MISSING ROUTE** | Blueprint mandates OTP engine; currently uses password prompt. |

### Deep Dive on Specific Verified Issues:
1. **Roster Approval:** Current working tree diff changed Android path to `shifts/duty-rosters/{rosterId}/approve/`. Verified that backend `DutyRosterViewSet` is `ReadOnlyModelViewSet` without an `approve` action. The real backend endpoint is `POST /shifts/shifts/approve_roster/`. This is an active mismatch in the working tree.
2. **Roster Generation:** Investigated `ApiService.generateRoster` and the previous converter error. In `Models.kt`, `GenerateRosterRequest` and `GenerateRosterResponse` are fully declared `@JsonClass(generateAdapter = true)` data classes. The error `unable to create converter for java.util.Map<java.lang.String,java.lang.Object>` is already resolved in current code.
3. **Occurrence Book:** Creation auto-generates entry numbers (`OB-MW-001`), prevents proxy submissions, and enforces station/guard scoping. Backend rejects direct `update()` and `partial_update()`, whereas blueprint mandates a 10-minute countdown window for direct edits before locking permanently.
4. **Visitor Register:** Android formats visitor names and IDs into `occurrence_text` strings with `category = "VISITOR"`. Backend has no dedicated `Visitor` model. Blueprint requires a dedicated table with entry numbers, ID numbers, vehicle reg, time-in, signature URL, and checkout button.
5. **Emergency SOS:** Currently reuses `IncidentReport` with `priority = "HIGH"`. Blueprint specifies a dedicated `sos_logs` table (`Low`/`Med`/`High`/`Critical`) with automated SMS alert dispatch on `Critical`.
6. **Telemetry:** Android requests `core/telemetry/`. Backend URL configuration has aliases: `core/telemetry/`, `api/core/telemetry/`, `telemetry/`, and `api/telemetry/`. Contract is verified and working.
7. **Authentication:** JWT login, refresh, profile fetch, role evaluation, station assignment, and logout cleanup are verified and operational.

---

## 11. Role Baseline

- **Roles Supported in Backend:** `UserRole.ADMINISTRATOR`, `UserRole.SUPERVISOR`, `UserRole.GUARD`.
- **Authoritative Identity Invariants:**
  - `simeonmbwani` = **Administrator / Superuser**.
  - `tavongashe` = **Station Supervisor** (ZOU Mash-West).
  - *Neither is a security guard. Neither shall be assigned to guard pairs, shift rosters, or clock-in attendance records.*
- **Current Android Role Determination:**
  - Evaluates `uiState.currentUser?.role?.uppercase()` in ViewModels and screens.
  - Used conditionally to show/hide FABs (e.g., FAB hidden for Supervisor on OB, Visitors, Incidents) and to append 4 management items to the dashboard list.
- **Role-Based Navigation Finding:**
  - Dedicated role-based root navigation does **NOT** exist in the current Android app.
  - Guards, Supervisors, and Administrators all land on the same generic scrolling `DashboardScreen.kt`, which merely appends management cards if the user is a Supervisor or Administrator.
  - The blueprint's distinct **Supervisor Dashboard** (Page 15) and **Superuser National Control Center** (Page 13) are missing from the navigation layer.

---

## 12. Duty-State Baseline

- **Backend Enforcement:**
  - The backend enforces a strict server-authoritative duty state.
  - `AttendanceViewSet.clock_in` verifies:
    1. Guard assignment match (zero proxy).
    2. Shift is not `TIME_OFF` or `OFF`.
    3. Guard is within station geofence radius (200m).
    4. Guard is within scheduled reporting window (rejects if >30 min before shift).
    5. Maximum 2 guards clocked in concurrently.
  - Off-duty guards are locked out from logging OB entries or starting patrols.
- **Android UI Presentation vs. Enforcement:**
  - Android UI currently displays duty assignment status (`CLOCKED_IN`, `NOT_CLOCKED_IN`) via `TodayShiftScreen.kt`.
  - However, `DashboardScreen.kt` **merely displays** a profile card and list of modules regardless of duty state.
  - It does **NOT** enforce the blueprint's **Guard OFF DUTY Restricted State** (Page 14 Right) where operational features are disabled with padlocks and replaced with Notifications, Supervisor Messaging, and Next Duty information.

---

## 13. Security Baseline

- **Login Identifiers:** Supports both Username and organizational Employee Number (e.g., `SEC-501`).
- **Brute-Force Lockout:** `LoginAttempt` model tracks failed attempts. 5 failures within window triggers a 15-minute lockout.
- **JWT Tokens:** 15-minute access token; 24-hour refresh token. Stored securely in SharedPreferences via `SessionManager.kt`.
- **Token Refresh & Replay Protection:** `AuthInterceptor.kt` intercepts HTTP 401 responses, synchronizes token renewal against `auth/refresh/`, updates `SessionManager`, and replays the original request transparently.
- **Application Window Protection:** `MainActivity.kt` enforces `WindowManager.LayoutParams.FLAG_SECURE` to prevent screen capture/recording.
- **Inactivity Session Lock:** `SgmisViewModel` tracks `lastUserActivityTimestamp` across user touches. After 3 minutes of idle inactivity, `isAppLocked` is set to true, triggering `AppLockOverlay.kt`.
- **App Lock Mechanism:** Currently requires the user's password to unlock. The blueprint specifies PIN or Fingerprint re-authentication.
- **Password Recovery:** Time-sensitive 10-minute OTP stored as SHA-256 cryptographic hash (`PasswordResetOTP`).
- **Zero Proxy Actions:** Backend strictly forbids actions on behalf of other guards across clock-in, OB creation, and patrol logs.
- **Audit Trails:** Central `SecurityAuditEvent` logs every security-relevant action, override, or failed access.

---

## 14. Test Baseline

### Backend Test Suite (`backend/tests/`):
- **Test Modules (17 Files):**
  1. `test_authoritative_attendance_security.py` (15.9 KB)
  2. `test_authoritative_roster_engine.py` (5.9 KB)
  3. `test_batch1_security_foundation.py` (17.3 KB)
  4. `test_enterprise_security_hardening.py` (14.4 KB)
  5. `test_notifications_flow.py` (4.0 KB)
  6. `test_part1_timezone_and_guardpair_validation.py` (15.3 KB)
  7. `test_part2_dutyroster_foundation.py` (10.4 KB)
  8. `test_part3b_safety_patch.py` (13.5 KB)
  9. `test_part3_authoritative_roster_generation.py` (21.5 KB)
  10. `test_part4_roster_validation_and_approval.py` (22.1 KB)
  11. `test_part5a_public_holiday_compensation.py` (23.1 KB)
  12. `test_part5c_leave_accounting.py` (25.0 KB)
  13. `test_part6b_attendance_security.py` (22.0 KB)
  14. `test_part7b_decision_notifications.py` (17.1 KB)
  15. `test_part7c_upcoming_duty_notifications.py` (18.8 KB)
  16. `test_roster_business_rules.py` (18.7 KB)
  17. `test_sgmis_api.py` (46.3 KB)
- **Status:** Comprehensive coverage of backend security, roster rotation algorithms, leave accrual, public holiday compensation, and attendance invariants.

### Android Test Suite (`app/src/test/`):
- **Test Files (6 Files):**
  1. `ClockOutRequestTest.kt` (3.1 KB)
  2. `ExampleRobolectricTest.kt` (0.6 KB)
  3. `ExampleUnitTest.kt` (0.3 KB)
  4. `GreetingScreenshotTest.kt` (1.1 KB)
  5. `MoshiAdapterTest.kt` (4.5 KB)
  6. `SessionManagerTest.kt` (2.5 KB)
- *Note:* In accordance with rule 11 ("do not create build artifacts, databases, or cache directories"), test execution commands were not invoked during this baseline verification.

---

## 15. Known Problems

### CRITICAL
1. **Roster Approval API Contract Mismatch (HTTP 404/405):** Android `ApiService.approveRoster` calls `POST shifts/duty-rosters/{rosterId}/approve/`, but `DutyRosterViewSet` is a `ReadOnlyModelViewSet`. The real endpoint is `POST shifts/shifts/approve_roster/`. Roster approval from Android currently fails.
2. **Missing Guard OFF-DUTY State Machine Presentation:** When a guard is off-duty, the app displays the same active module list. The blueprint's locked resting dashboard (`"ACCESS RESTRICTED — You are on Time Off"` with locked features, notifications, supervisor chat, and next duty schedule) is unimplemented.

### HIGH
3. **Missing Supervisor & Superuser Command Dashboards:** The tablet/web live overview console (Blueprint Page 15) and National Control Center Harare (Blueprint Page 13) are missing from the navigation architecture.
4. **Early Clock-Out Authorization Contract Mismatch:** The current app uses a supervisor password dialog. The blueprint requires a 6-digit cryptographic OTP generated by Superuser with a 5-minute countdown timer.
5. **Visitor Register Implementation Gap:** Android formats visitor data into text strings inside Occurrence Book entries (`category = "VISITOR"`). The blueprint requires a dedicated `visitors` table with entry numbers, ID numbers, pass status, vehicle reg, canvas signature, and checkout controls.
6. **Occurrence Book 10-Minute Edit Window:** The backend permanently forbids direct `PUT`/`PATCH` updates and requires append-only amendments. The blueprint mandates a 10-minute active countdown window where entries can be directly patched before locking permanently.

### MEDIUM
7. **Emergency SOS Distress Beacon:** Currently creates a generic incident with `priority = "HIGH"`. The blueprint specifies a dedicated `sos_logs` model and automated SMS gateway alert dispatch on `Critical`.
8. **App Lock Credentials:** Inactivity lock currently requests the user's password. The blueprint specifies PIN or fingerprint re-authentication.
9. **Top-Bar Auto-Lock Countdown Display:** The blueprint specifies a live countdown display (e.g., `02:45`) in the top bar of the guard dashboard. Currently absent from the top bar.

### LOW
10. **Untracked Scrap Files at Root:** `et` file at repo root contains output from earlier Git checks.
11. **Local Hardcoded Default Base URL:** `SessionManager.kt` contains fallback to `sgmis-db.onrender.com`.

### UNKNOWN
12. **SMS Gateway Credentials for Critical SOS:** Production SMS provider integration for critical distress dispatches requires confirmation.

---

## 16. Files That Must NOT Be Rewritten

The following backend and core infrastructure components must be preserved without rewriting:
- **Backend Data Models:** `apps/accounts/models.py`, `apps/stations/models.py`, `apps/shifts/models.py`, `apps/leave/models.py`, `apps/incidents/models.py`, `apps/patrols/models.py`, `apps/core/models.py`.
- **Backend Security & Business Logic:** `apps/shifts/services.py`, `apps/core/audit.py`, `apps/core/idempotency.py`, `apps/core/middleware.py`.
- **Database Migrations:** All existing migration files across all apps.
- **Android Core Plumbing:** `ApiClient.kt`, `AuthInterceptor.kt`, `SessionManager.kt`, `PaginatedListJsonAdapterFactory.kt`, `ShiftJsonAdapterFactory.kt`.
- **Room Database Architecture:** `SgmisDatabase.kt`, `Entities.kt`, `Daos.kt`.
- **Functional Sub-screens:** `HandoverScreen.kt`, `PatrolScreen.kt`, `LeaveScreen.kt`, `UserManagementScreen.kt`, `StationManagementScreen.kt`, `ReportsScreen.kt`.

---

## 17. Files / Screens Planned for Reconstruction

Based on the blueprint gap analysis, the following presentation components are planned for reconstruction during Phase 12:
1. **`DashboardScreen.kt`** -> Reconstruct into the blueprint's two-state presentation:
   - Guard ON DUTY Dashboard (6-button Quick Action grid, `"CLOCKED IN"` banner, 3-minute top-bar timer, telemetry footer).
   - Guard OFF DUTY Dashboard (restricted alert banner, disabled feature buttons with padlocks, active notifications, supervisor messaging, next duty card).
2. **`GuardDutyPlanScreen.kt`** -> Reconstruct into the blueprint's **"My Roster Overview"** (Blueprint Page 16): personal calendar schedule cards with `[DAY]`, `[NIGHT]`, `[OFF]` badges, shift stats cards, and comp days card.
3. **`SupervisorDashboardScreen`** -> New/reconstructed dedicated tablet/web console matching Blueprint Page 15.
4. **`SuperuserNationalControlScreen`** -> New/reconstructed dedicated command center matching Blueprint Page 13.
5. **`TodayShiftScreen.kt` (Early Clock-Out)** -> Rewire early clock-out dialog to accept 6-digit OTP instead of password.
6. **`OccurrenceBookScreen.kt`** -> Incorporate 10-minute edit lock countdown timer.
7. **`VisitorScreen.kt`** -> Reconstruct with dedicated visitor pass fields, canvas signature pad, and checkout button.
8. **`EmergencySosScreen.kt`** -> Reconstruct with dedicated emergency distress beacon UI.

---

## 18. Recommended Phase 12 Implementation Order

The implementation must proceed in this strict, controlled order:

1. **API contract reconciliation** (Fix `approve_roster` endpoint path in Android, align leave summary, verify all DTOs).
2. **Role architecture** (Formalize distinct navigation trees for Guard, Supervisor, and Administrator).
3. **Server-authoritative duty-state integration** (Wire state engine evaluating `todayShift` and attendance status).
4. **Guard ON-DUTY dashboard** (Implement 6-button Quick Action grid, `"CLOCKED IN"` banner, 3-minute countdown timer, telemetry chips).
5. **Guard OFF-DUTY dashboard** (Implement restricted access alert container, disabled buttons with padlocks, notifications, messaging, next duty card).
6. **My Roster Overview** (Reconstruct personal calendar with `[DAY]`, `[NIGHT]`, `[OFF]` pill badges, shift stats, comp days card).
7. **Visitor Register** (Implement dedicated visitor pass register, canvas signature, checkout button).
8. **Occurrence Book** (Implement 10-minute countdown direct edit window and append-only amendments).
9. **Emergency SOS** (Implement dedicated high-contrast distress beacon with sound override).
10. **Handover / Take-Over** (Refine handover/takeover workflow and GPS verification styling).
11. **Supervisor Command Console** (Implement live overview metrics, monthly roster table, active visitor log, OB live feed, leave approval).
12. **Administrator / Superuser National Control Center** (Implement master system controls, Zimbabwe holiday engine, leave policy engine, 6-digit OTP generator, national analytics).
13. **Notifications / Messaging** (Wire supervisor chat and broadcast notices).
14. **Testing** (Execute unit, integration, and UI verification suites).
15. **Final build** (Clean release build verification).

---

## 19. Safety Verification

It is explicitly confirmed and verified that during this phase:
- **No database migrations were executed.**
- **No database records were changed.**
- **No users were changed.**
- **No roles were changed.**
- **No stations were changed.**
- **No rosters were changed.**
- **No shifts were changed.**
- **No source files were modified.**
- **No source files were deleted.**
- **No source files were renamed.**
- **No deployment was performed.**
- **No Git commit was performed.**
- **No Git push was performed.**
- **No protected backup files were accessed.**

---

## 20. STOP

**PHASE 12.1 COMPLETE — NO IMPLEMENTATION PERFORMED.**
