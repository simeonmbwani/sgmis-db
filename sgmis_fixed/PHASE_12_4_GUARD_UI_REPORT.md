# Smart Security Phase 12.4 Guard UI Reconstruction

**Document:** `PHASE_12_4_GUARD_UI_REPORT.md`  
**Project Root:** `C:\Projects\SGMIS_FIXED\sgmis_fixed`  
**Authoritative Blueprint:** `C:\Projects\Smart Security Original Blueprint.zip`  
**Date:** September 26, 2026  
**Status:** COMPLETE — PASSED (All Android Unit Tests Succeeded, 39 Backend Tests Succeeded, Debug APK Assembled)

---

## 1. Blueprint Elements Implemented

Phase 12.4 successfully reconstructs the **SECURITY GUARD** user experience strictly adhering to the visual and functional specifications of the authoritative Smart Security blueprint (Pages 6, 7, 14, and 16).

The previous unbundled, 18-module generic scrolling menu has been completely replaced with a focused, state-driven interface:
- **Two Authoritative Guard Dashboard States:** Dynamic rendering of `ON DUTY` (active operations) and `OFF DUTY` (restricted resting state), plus the intermediate `ELIGIBLE_FOR_DUTY` state.
- **Blueprint 6-Action Quick Grid:** Clean, mobile-first 2-column grid providing direct access to the 6 primary guard actions:
  1. Occurrence Book
  2. Visitor Book
  3. SOS Emergency (high-contrast emergency alert styling)
  4. Patrol Check
  5. Handover / Take-Over
  6. My Roster
- **Visible Session Countdown Timer:** Top AppBar live session indicator (`⏱ 03:00` countdown) providing session awareness and triggering non-destructive state re-sync on expiry, while maintaining server authority over duty status.
- **Server Telemetry Footer Bar:** High-visibility 3-column chip bar displaying Today's date, active OB entry count, and visitor count.
- **My Roster Personal Calendar View:** Complete reconstruction of `GuardDutyPlanScreen` into the Blueprint's personal calendar with `[DAY]`, `[NIGHT]`, and `[OFF]` pill badges, monthly duty statistics, and public holiday compensation tracking.
- **Duty Lock Enforcement on All Operational Screens:** Explicit visual and operational duty lock protection on Occurrence Book, Visitor Book, SOS Emergency, Patrol, and Handover. Off-duty guards cannot submit operational data.

---

## 2. Guard ON DUTY Dashboard

When `GuardDutyState.ON_DUTY`:
1. **Header & Status Treatment:**
   - Station context prominently displayed (`uiState.currentStationName`).
   - High-visibility status card featuring:
     - `● CLOCKED IN` green status badge.
     - `GPS Verified` indicator.
     - Officer identification, active shift window (`07:00–18:00`), and assigned partner name.
     - Confirmation note: `"GPS: Inside Campus — Location verified within operational geofence."`
   - Visible session countdown timer (`03:00`) in TopAppBar.
2. **6-Action Quick Grid (All Unlocked & Active):**
   - Occurrence Book (`NavRoutes.OCCURRENCE_BOOK`): Log incidents & events.
   - Visitor Book (`NavRoutes.VISITOR_BOOK`): Register visitors & passes.
   - SOS Emergency (`NavRoutes.SOS`): Immediate distress alert with high-visibility red container.
   - Patrol Check (`NavRoutes.PATROL`): Start patrol route.
   - Handover / Take-Over (`NavRoutes.HANDOVER`): Shift handover notes.
   - My Roster (`NavRoutes.MY_ROSTER`): View shift schedule.
3. **Telemetry Footer Bar:**
   - Displays real-time server telemetry:
     - Today: `26 Sep 2026`
     - OB Entries: Count from server (`uiState.obEntries.size`)
     - Visitors: Count from server (`uiState.visitors.size`)

---

## 3. Guard OFF DUTY Dashboard

When `GuardDutyState.OFF_DUTY`:
1. **Header & Status Treatment:**
   - Status header: `Guard: [Name] (OFF DUTY - Resting)` with night/sleep emblem.
   - `○ OFF DUTY` badge.
2. **Access Restricted Warning Banner:**
   - Grey container with padlock emblem:
   - Heading: `"ACCESS RESTRICTED — You are on Time Off"`
   - Description: `"Active operational logging is locked while you are off duty. Rest and recharge."`
3. **Restricted Operations Grid (5 Locked, 1 Accessible):**
   - Occurrence Book: Locked (`LOCKED` chip, padlock emblem).
   - Visitor Book: Locked (`LOCKED` chip, padlock emblem).
   - SOS Emergency: Locked (`LOCKED` chip, padlock emblem).
   - Patrol Check: Locked (`LOCKED` chip, padlock emblem).
   - Handover / Take-Over: Locked (`LOCKED` chip, padlock emblem).
   - **My Roster: ACCESSIBLE & UNLOCKED:** Guards can view their personal roster, rotation, and schedule off-duty.
   - Tapping any locked module provides an immediate security alert: `"Duty Lock: You must be CLOCKED IN (On Duty) to access operational records."`
4. **Available While Off Duty Section:**
   - **Notifications Card:** Displays unread notification count badge and opens `NavRoutes.NOTIFICATIONS`.
   - **Leave & Requests Card:** Direct access to personal leave entitlement and applications (`NavRoutes.LEAVE`).
5. **Next Duty Schedule Card (Server-Backed):**
   - Informational card displaying the next upcoming shift from the server roster:
     - Date, Shift Type (`DAY` / `NIGHT`), start and end hours.
     - Station post and assigned duty partner.
     - If no upcoming shifts are returned by the server, displays safe fallback message without crashing.

---

## 4. Eligible-for-Duty State

When `GuardDutyState.ELIGIBLE_FOR_DUTY`:
1. **Status Treatment:**
   - Status pill: `▲ READY FOR DUTY (NOT CLOCKED IN)` with high-contrast amber styling.
   - Displays scheduled station, shift window, and assigned partner.
2. **Call to Action:**
   - Prominent primary action button: `"PROCEED TO CLOCK IN CONSOLE"` navigating to `NavRoutes.TODAY_SHIFT`.
   - Clear banner instructing the officer: `"You have a scheduled shift today. Clock in to unlock operational logs, patrol recording, and occurrence book."`
3. **Operational Lockdown:**
   - Operational logging cards remain locked until clock-in is registered and verified by the backend.
   - `My Roster` remains accessible.

---

## 5. My Roster

`GuardDutyPlanScreen.kt` has been reconstructed into **"My Roster Overview"** (Blueprint Page 16):
- **Header:** Officer avatar badge, full name, employee number, post location, and operational status (`Guard • Active • On Roster Schedule`).
- **Calendar Month Header:** Current month and year (e.g. `September 2026 • Personal Calendar`).
- **Monthly Roster Statistics (3 Metrics):**
  - Total Shifts On (server-derived count of scheduled working shifts).
  - Total Days Off (server-derived count of rest days).
  - Leave Balance (Vacation / Casual leave days from `LeaveBalance` model).
- **Public Holiday Compensation:** Dedicated card displaying comp days accrual policy and status.
- **Next Scheduled Duty Card:** Shows the immediate next upcoming shift, post, hours, and partner.
- **Duty Schedule Stream:**
  - Shift cards styled with Blueprint-compliant badges:
    - `[DAY]`: High-visibility green badge.
    - `[NIGHT]`: Dark navy/blue container badge.
    - `[OFF]`: Neutral grey badge.
  - Date, shift hours, station post, partner assignment, and attendance status.
- **Zero Hardcoding Guarantee:** Rotation rules (4 ON / 8 OFF) are not hardcoded; statistics and stream are dynamically derived from server records.

---

## 6. Occurrence Book

- Reconstructed with clean, immutable audit workflow matching the existing secure backend contract.
- Guard off-duty protection:
  - If a guard is off-duty, entry creation is disabled, the FAB is omitted, and an informational notice is displayed: `"Viewing mode: You must be CLOCKED IN (On Duty) to record official OB entries."`
  - Viewing entries and viewing official amendments remain accessible.
- Preserved backend contract: Date, Time, Entry Number, Category, Occurrence text, Logging Guard, Station.
- Retains blueprint 10-minute edit-window discrepancy as a documented future backend feature (does not break immutability).

---

## 7. Visitor Book

- Reconstructed as a dedicated **Visitor Book & Gate Register** interface.
- Preserves the current backend contract (storing visitors via Occurrence Book with `category = "VISITOR"`).
- Visitor entries render with structured visitor details:
  - Visitor Name, ID/Passport Number, Vehicle Registration, Host / Person Visited, Purpose, Time In, and Time Out.
  - Status badges: `"ACTIVE ON PREMISES - DEPARTURE PENDING"` vs. `"DEPARTED"`.
- Off-duty guards cannot register visitors (FAB hidden, off-duty banner shown).
- No new database schema or migration required in this phase.

---

## 8. SOS

- Reconstructed as a dedicated **Emergency SOS Distress Beacon** screen.
- Clear, high-impact emergency styling with deliberate confirmation modal to prevent accidental activation.
- Selectable emergency categories:
  - Security Intrusion / Breach
  - Medical Emergency
  - Violence / Armed Threat
  - Fire / Facility Hazard
  - General Officer Distress
- Telemetry summary: Officer Name, Service ID, Station Post, Duty Status.
- Guard off-duty protection: SOS button is locked when off duty with explicit notice.
- Dispatches emergency incident via existing backend incident contract (`submitIncident` with priority `"HIGH"`).
- Does not claim automated Critical SMS or require new SOS database migrations.

---

## 9. Patrol

- Preserves existing GPS geofence validation, checkpoint verification, and station context.
- Off-duty guard protection: Start patrol button is locked with notice `"Patrol locked: You must be CLOCKED IN (On Duty) to begin patrols."`
- Fully integrates with `NavRoutes.PATROL`.

---

## 10. Handover

- Preserves authenticated shift handover transfer, relief guard confirmation, and takeover acceptance/rejection.
- Off-duty guard protection: Submit handover FAB is locked when off duty with notice `"Viewing mode: Submitting shift handovers requires an active clocked-in duty shift."`
- Fully integrates with `NavRoutes.HANDOVER`.

---

## 11. Notifications

- Non-operational module: Remains fully accessible to guards while OFF DUTY.
- Direct card link on off-duty dashboard shows unread notification counter badge.
- Fully integrates with `NavRoutes.NOTIFICATIONS`.

---

## 12. Navigation

- Updated `RoleRouter.kt` with route aliases:
  - `NavRoutes.MY_ROSTER = "guard_duty_plan"`
  - `NavRoutes.VISITOR_BOOK = "visitors"`
  - `NavRoutes.SOS = "emergency_sos"`
- `safeNavigate` enforces role permissions and duty accessibility on all transitions.
- Administrative and Supervisory routes (`attendance_management`, `roster`, `users`, `stations`, `admin_dashboard`) remain strictly barred from Guards.

---

## 13. Security

- Preserved `FLAG_SECURE` window protection against screen capture and recording.
- Preserved 3-minute inactivity timer with `AppLockOverlay`.
- Preserved JWT authentication, session tokens, and station-level tenant isolation.
- Zero client-side duty-state fabrication.

---

## 14. Files Changed

| File Path | Nature of Change |
| :--- | :--- |
| `app/src/main/java/com/example/ui/screens/DashboardScreen.kt` | Reconstructed into Blueprint Guard Dashboard (`ON_DUTY`, `OFF_DUTY`, `ELIGIBLE`, 6-action grid, session timer, telemetry footer). |
| `app/src/main/java/com/example/ui/screens/GuardDutyPlanScreen.kt` | Reconstructed as Blueprint "My Roster Overview" (calendar header, `[DAY]`/`[NIGHT]`/`[OFF]` badges, stats cards, holiday comp). |
| `app/src/main/java/com/example/ui/screens/VisitorScreen.kt` | Reconstructed Visitor Book UI with duty-lock protection, status chips, and gate register branding. |
| `app/src/main/java/com/example/ui/screens/EmergencySosScreen.kt` | Reconstructed SOS screen with category selector, deliberate confirmation modal, and duty-lock protection. |
| `app/src/main/java/com/example/ui/screens/OccurrenceBookScreen.kt` | Added duty-lock notice and guarded FAB creation when guard is off-duty. |
| `app/src/main/java/com/example/ui/screens/PatrolScreen.kt` | Added duty-lock notice and disabled start patrol button when guard is off-duty. |
| `app/src/main/java/com/example/ui/screens/HandoverScreen.kt` | Added duty-lock notice and guarded handover submission FAB when guard is off-duty. |
| `app/src/main/java/com/example/ui/navigation/RoleRouter.kt` | Added `MY_ROSTER`, `VISITOR_BOOK`, and `SOS` route aliases. |
| `app/src/main/java/com/example/ui/viewmodel/SgmisViewModel.kt` | Updated `refreshAuthoritativeState()` to fetch shifts, OB, visitors, and leave for guards. |
| `app/src/test/java/com/example/RoleAndDutyStateTest.kt` | Added 11 comprehensive unit test methods covering all Phase 12.4 scenarios. |

---

## 15. Tests

Executed the Android unit test suite (`.\gradlew.bat testDebugUnitTest`):
- **Result:** `BUILD SUCCESSFUL`.
- **Passed Scenarios (32 tests):**
  1. Guard ON DUTY dashboard state transitions (`testGuardDashboardState_OnDuty`).
  2. Guard OFF DUTY dashboard state transitions (`testGuardDashboardState_OffDuty`).
  3. Guard ELIGIBLE state transitions (`testGuardDashboardState_EligibleForDuty`).
  4. My Roster availability across all states (`testMyRoster_AlwaysAccessibleInAllStates`).
  5. Restricted off-duty operations enforcement (`testRestrictedOffDutyOperations`).
  6. Guard barred from Supervisor routes (`testGuardCannotAccessSupervisorRoutes`).
  7. Guard barred from Administrator routes (`testGuardCannotAccessAdministratorRoutes`).
  8. Server state refresh dynamic transitions (`testServerStateRefreshLogic`).
  9. Null station handling without crash (`testNullStationHandling`).
  10. Null shift handling without crash (`testNullShiftHandling`).
  11. Null/empty roster handling without crash (`testNullOrEmptyRosterHandling`).

Executed the backend Django test suite (`python backend/manage.py test tests.test_sgmis_api`):
- **Result:** `Ran 39 tests in 2.405s — OK`.
- **Failures / Errors:** 0.

---

## 16. Build

Executed Android debug build (`.\gradlew.bat assembleDebug`):
- **Result:** `BUILD SUCCESSFUL in 1m 17s`.
- 40 actionable tasks, APK produced without error.

---

## 17. Remaining Backend Gaps

The following architectural discrepancies identified in the Inventory remain intentionally preserved for future backend phases and were **not** patched with client-side hacks:
1. **Dedicated Visitor Database Schema:** The backend continues to store visitor records as Occurrence Book entries (`category = "VISITOR"`). A dedicated `visitors` table with ID photo, vehicle pass, and digital signature remains a documented future backend feature.
2. **Dedicated SOS Logs Table:** Emergency SOS requests are recorded as high-priority incidents (`priority = "HIGH"`). A dedicated `sos_logs` table with automated SMS gateway integration remains a documented future backend feature.
3. **Blueprint OB 10-Minute Direct-Edit Window:** The backend Occurrence Book enforces cryptographic immutability (append-only amendments). The blueprint's 10-minute edit-countdown window before locking remains a documented future backend reconciliation item.
4. **Early Clock-Out 6-Digit OTP Backend:** The backend early clock-out endpoint utilizes supervisor credentials rather than the blueprint's 6-digit OTP engine.

---

## 18. Safety Verification

- **Protected Backup:** `C:\Projects\SGMIS_FIXED\sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE` was NEVER accessed, modified, or touched.
- **Database Schema & Migrations:** Zero migrations were created or executed. No tables or columns were modified.
- **Database Records:** No users, stations, pairs, rosters, shifts, or leave records were changed or deleted. Authoritative accounts `simeonmbwani` and `tavongashe` remain completely untouched.
- **Scope Compliance:** Only the Guard experience UI was reconstructed. Supervisor Command Console and Administrator National Control Center were NOT built in this phase.
- **Hold Order:** Phase 12.5 has NOT been started. Execution is stopped.
