# PHASE 13 — GUARD APPLICATION COMPLETE OPERATIONAL RECONSTRUCTION REPORT

**Date:** 2026-09-27  
**Project Directory:** `C:\Projects\SGMIS_FIXED\sgmis_fixed`  
**Target Environment:** Android (Jetpack Compose / Material 3) & Django REST Framework  

---

## 1. EXECUTIVE SUMMARY & SAFETY AUDIT

Phase 13 represents the comprehensive operational reconstruction of the Security Guard experience and all supporting backend services within the Smart Security Management Information System (SGMIS). The implementation strictly establishes a server-authoritative guard architecture, differentiating **Guard ON DUTY** from **Guard OFF DUTY** while eliminating mock data, hardcoded operational placeholders, and client-side authorization bypasses.

### Safety & Integrity Compliance
- **Protected Backup:** `C:\Projects\SGMIS_FIXED\sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE` was **NEVER** accessed, inspected, modified, copied from, deleted, or compared against.
- **Git State:** Strictly zero `git reset`, `clean`, `stash`, `checkout`, `restore`, `commit`, or `push` commands were executed.
- **Production Isolation:** Strictly zero deployments to Render, production databases, or external hosting environments.
- **Migration Justification:** Exactly one migration was created: `backend/apps/notifications/migrations/0003_directmessage.py`, which is strictly necessary to persist authenticated peer-to-peer and guard-to-supervisor operational communication without relying on third-party SMS or mock queues.

---

## 2. BUILD VERIFICATION & EXACT TEST COUNTS

### A. Django Backend Tests
- **Test Command:** `..\venv\Scripts\python.exe manage.py test tests.test_sgmis_api`
- **Result:**
  ```text
  Ran 49 tests in 1.767s
  OK
  Destroying test database for alias 'default'...
  ```
- **Passed:** **49 / 49 tests** (100% passing, 0 failures, 0 errors).

### B. Android Unit Tests
- **Test Command:** `.\gradlew.bat testDebugUnitTest` (with `-Djava.awt.headless=true`)
- **Suite Breakdown:**
  1. `com.example.ClockOutRequestTest`: **3 tests** (0 failures)
  2. `com.example.ExampleRobolectricTest`: **1 test** (0 failures)
  3. `com.example.ExampleUnitTest`: **1 test** (0 failures)
  4. `com.example.GreetingScreenshotTest`: **1 test** (0 failures)
  5. `com.example.MoshiAdapterTest`: **5 tests** (0 failures)
  6. `com.example.RoleAndDutyStateTest`: **50 tests** (0 failures)
  7. `com.example.SessionManagerTest`: **4 tests** (0 failures)
- **Total Android Unit Tests:** **65 / 65 tests** (100% passing, 0 failures, 0 errors, 0 skipped).

### C. Android Debug APK Compilation
- **Build Command:** `.\gradlew.bat assembleDebug`
- **Result:** `BUILD SUCCESSFUL in 1m 10s` (40 actionable tasks: 5 executed, 35 up-to-date)
- **APK Path:** `C:\Projects\SGMIS_FIXED\sgmis_fixed\app\build\outputs\apk\debug\app-debug.apk`
- **APK Size:** `24,667,379 bytes` (~23.52 MB)
- **Build Timestamp:** `2026-09-27 14:03:25`

---

## 3. AUDIT OF MODIFIED AND CREATED FILES

### Backend Files Modified / Created
1. `backend/apps/notifications/models.py`:
   - Added `DirectMessage` model supporting bidirectional guard-to-partner and guard-to-supervisor operational text messaging with read tracking.
2. `backend/apps/notifications/serializers.py`:
   - Added `DirectMessageSerializer` and `DirectMessageCreateSerializer`.
3. `backend/apps/notifications/views.py`:
   - Added `DirectMessageListCreateView` and `DirectMessageMarkReadView` with strict participant isolation.
4. `backend/apps/notifications/urls.py`:
   - Added routes `messages/` and `messages/<uuid:pk>/read/`.
5. `backend/apps/notifications/migrations/0003_directmessage.py` (New):
   - Database schema migration for `DirectMessage` model.
6. `backend/apps/shifts/views.py`:
   - Added `LateArrivalReportView` (generating authoritative case numbers `LAR-{STN}-{YYYYMMDD}-{count:03d}`).
   - Added `GuardDutyStateView` (/shifts/guard/duty-state/) resolving authoritative duty state.
   - Enhanced `ClockInView` to enforce mandatory late arrival case numbers when clock-in is $\ge 60$ minutes late.
7. `backend/apps/shifts/serializers.py`:
   - Added `LateArrivalReportSerializer` and `LateArrivalReportResponseSerializer`.
   - Exposed `late_report_required`, `is_serious_late`, `is_late`, and `raw_duty_state` fields on `ShiftSerializer`.
8. `backend/apps/shifts/services.py`:
   - Added helper methods for calculating late arrival thresholds and generating sequential daily case numbers.
9. `backend/apps/occurrence_book/views.py`:
   - Added `OccurrenceBookEntryAmendView` enforcing the strict 24-hour amendment cutoff.
10. `backend/apps/occurrence_book/serializers.py`:
    - Exposed `is_amendable` boolean dynamically calculated based on entry timestamp vs 24 hours.
11. `backend/apps/incidents/views.py`:
    - Added `EmergencySosView` (`/incidents/sos/`) creating high-priority incident beacons and auto-alerting dispatch.
12. `backend/apps/incidents/urls.py`:
    - Registered route `sos/`.
13. `backend/apps/patrols/views.py`:
    - Validated and ensured full logging of completed checkpoints during patrol debrief.
14. `backend/apps/leave/views.py`:
    - Ensured leave status and leave type are exposed for duty state integration.
15. `backend/sgmis_backend/urls.py`:
    - Added missing routing delegates for newly created endpoints.
16. `backend/tests/test_sgmis_api.py`:
    - Expanded test coverage from 41 to 49 tests, covering Direct Messaging, 24-hour OB immutability, Emergency SOS dispatch, late arrival case generation, and authoritative duty states.

### Android Files Modified / Created
1. `app/src/main/AndroidManifest.xml`:
   - Added `VIBRATE` permission and verified location/network permissions for real-device hardware alerts.
2. `app/src/main/java/com/example/util/NotificationHelper.kt` (New):
   - Implemented system notification channels (`EMERGENCY_ALERTS_CHANNEL` with high importance/audio/vibration pattern, and `OPERATIONAL_CHANNEL` for shift handover notifications).
3. `app/src/main/java/com/example/data/model/Models.kt`:
   - Added `DirectMessage`, `LateArrivalReportRequest`, `LateArrivalReportResponse`, `SosDistressRequest`, `SosDistressResponse`, and `DutyStateResponse`.
   - Extended `GuardDutyState` to include `TIME_OFF`, `ON_LEAVE`, and `EARLY_EXIT_PENDING`.
4. `app/src/main/java/com/example/data/api/ApiService.kt`:
   - Added REST endpoints for `getGuardDutyState`, `submitLateArrivalReport`, `triggerSos`, `getDirectMessages`, `sendDirectMessage`, `getUnreadMessageCount`, `markDirectMessageRead`, and `amendOBEntry`.
5. `app/src/main/java/com/example/data/repository/SgmisRepository.kt`:
   - Implemented repository wrapper methods for newly added API contracts with error mapping.
6. `app/src/main/java/com/example/ui/viewmodel/SgmisViewModel.kt`:
   - Wired states and actions for SOS triggering, late arrival reports, direct messages, 24-hr OB amendments, handover acceptance callback, and device location resolution.
7. `app/src/main/java/com/example/ui/screens/DashboardScreen.kt`:
   - Integrated dynamic GPS perimeter distance checking.
   - Implemented Communications Row with real-time `Messages [x]` and `Notifications [y]` badges.
   - Built complete `DirectMessagesDialog` for in-app peer communication.
   - Built dedicated `GuardDutyState.ON_LEAVE` and `TIME_OFF` dashboard views.
8. `app/src/main/java/com/example/ui/screens/TodayShiftScreen.kt`:
   - Added automatic detection for shifts $\ge 60$ minutes late.
   - Built `LateArrivalReportDialog` requiring reason, incident details, and arrival estimate to obtain case number before clock-in.
   - Added `LateArrivalWarningBanner`.
9. `app/src/main/java/com/example/ui/screens/OccurrenceBookScreen.kt`:
   - Replaced generic single-text form with dedicated inputs for all 6 categories (`ROUTINE`, `VEHICLE`, `VISITOR`, `INCIDENT`, `HANDOVER`, `MAINTENANCE`).
   - Implemented 24-hour amendment check disabling edit capability after 24 hours.
10. `app/src/main/java/com/example/ui/screens/PatrolScreen.kt`:
    - Added real-time elapsed patrol timer.
    - Added live checkpoint inspection progress indicators.
    - Blocked premature patrol debrief submission if 0 checkpoints are verified.
11. `app/src/main/java/com/example/ui/screens/HandoverScreen.kt`:
    - Added `AcceptHandoverDialog` requiring physical two-guard verification and checkbox confirmation before takeover.
    - Added operational audio/vibration notification upon pending takeover for incoming guard.
12. `app/src/main/java/com/example/ui/screens/EmergencySosScreen.kt`:
    - Wired confirmation dialog to authoritative `/incidents/sos/` endpoint with automatic device coordinates.
    - Implemented high-priority audio and vibration alert trigger via `NotificationHelper`.
    - Added high-visibility SOS broadcast status banner.

---

## 4. DETAILED OPERATIONAL ANSWERS (A THROUGH R)

### A. How ON DUTY Differs from OFF DUTY
- **ON DUTY (`ON_DUTY`):** The guard is formally clocked in on an authoritative shift and physically verified within the station geofence. The guard dashboard displays the active shift timer, station telemetry, and enables all live security operational actions: Occurrence Book entry submission, Visitor check-in/out, Live Patrol scans, Shift Handover submission, and Emergency SOS panic dispatch.
- **OFF DUTY (`OFF_DUTY`):** The guard has no active shift, has completed their scheduled shift, or is between duty cycles. The guard UI operates strictly in a **Read-Only / Information Mode**. Operational actions (logging OB, checking in visitors, starting patrols, submitting handovers, dispatching SOS) are locked. The guard sees an informational duty status card, their upcoming roster schedule, leave balances, notification alerts, and peer communications.

### B. How TIME_OFF Differs from LEAVE
- **TIME_OFF:** A scheduled rotational rest day automatically generated by the 4-on/8-off roster engine. It requires no application or supervisor approval. The dashboard informs the guard of their scheduled rest period and displays the exact date/time of their next scheduled duty shift.
- **ON_LEAVE:** A formally applied-for and supervisor-approved statutory absence (e.g. Annual Vacation, Sick Leave, Compassionate Leave). When active, the server returns `duty_state: ON_LEAVE` and `leave_type` (e.g. "ANNUAL"). The UI presents a prominent leave notice with the approved leave category, start/end dates, remaining leave balances, and provides access only to personal services (roster, balances, direct messages).

### C. How Normal Clock-Out is Locked
- Shift attendance security strictly blocks premature clock-out. In `backend/apps/shifts/views.py` (`ClockOutView`), if the current time is earlier than the scheduled shift end time (e.g. 18:00 for a Day Shift), the backend rejects the request with HTTP 400: *"Shift duty is still active... Early clock-out requires valid OTP authorization code or supervisor credentials."*
- On Android, `TodayShiftScreen` inspects `shift.endTime`. Until the scheduled duration is satisfied, the normal "Clock Out" button is replaced by a locked state, directing the guard to the Early Clock-Out OTP workflow if departure is operationally necessary.

### D. How Early Clock-Out Authorization Works
1. The guard contacts their station supervisor or national control center requesting early departure with operational justification.
2. The supervisor accesses the Command Console and triggers `/shifts/early-clockout/otp/generate/`, generating a single-use, 5-digit cryptographic OTP valid for exactly 300 seconds (5 minutes).
3. The guard enters the OTP in the `EarlyClockoutDialog` in `TodayShiftScreen`.
4. The backend validates the OTP against the shift, verifies expiration, records the supervisor's authorization, consumes the OTP atomically, and sets attendance status to `CLOCKED_OUT`.

### E. How >1-Hour Late Clock-In Works
1. If a guard attempts to clock in $\ge 60$ minutes after the scheduled shift start time (or if the server flags `late_report_required: true`), the standard clock-in is intercepted.
2. `TodayShiftScreen` opens `LateArrivalReportDialog`.
3. The guard must submit an official explanation, optional incident details, and estimated arrival time.
4. The backend `/shifts/late-arrival-report/` endpoint validates the shift, logs an immutable record, and generates an official case number formatted as:
   `LAR-{STATION_CODE}-{YYYYMMDD}-{COUNT:03d}` (e.g., `LAR-HRE01-20260927-001`).
5. This case number is returned to the client and passed into the final `clockIn` request, ensuring complete chain-of-custody.

### F. How Individual Roster Matrix Gets Backend Data
- The guard roster matrix in `GuardDutyPlanScreen` and `RosterManagementScreen` calls `/shifts/my-shifts/` and `/shifts/roster/?station={id}&start_date={date}&cycle_days=12`.
- Dates and shift intervals are calculated in Zimbabwe standard time (`Africa/Harare`).
- The matrix dynamically maps `DAY`, `NIGHT`, and `OFF` shifts across calendar days for both the guard and their assigned pair, completely eliminating static/mock arrays.

### G. How Partner Communication Works
- The authoritative `GuardPair` assigned to the station determines the guard's designated partner (Guard A / Guard B).
- The guard dashboard displays the partner's name, employee number, and current status.
- A "Message Partner" action opens `DirectMessagesDialog`, allowing instant in-app text messaging backed by the `/notifications/messages/` API.

### H. How Supervisor Communication Works
- `DirectMessagesDialog` allows the guard to switch recipients between their assigned shift partner and their station supervisor / duty controller.
- Messages sent are persisted in the `DirectMessage` table with foreign keys to sender, recipient, station, and timestamp.

### I. How Unread Badges Work
- Unread notification counts are fetched from `/notifications/unread-count/` and direct message counts from `/notifications/messages/unread-count/`.
- Badges appear in the TopAppBar (bell icon) and prominently on the Dashboard Communications Row (`Messages [X]`, `Notifications [Y]`).
- Viewing or tapping items dispatches read receipts (`/notifications/<id>/read/` or `/notifications/messages/<id>/read/`), decrementing badge counters in real time.

### J. How Each of the Six OB Categories Differs
1. **`ROUTINE`:** Captures periodic perimeter checks, guard changes, physical post inspections, and lighting status.
2. **`VEHICLE`:** Specifically logs vehicle access: license registration number, driver name, vehicle make/model, gate pass ID, and cargo inspection results.
3. **`VISITOR`:** Records visitor entry: full name, national ID/passport number, purpose of visit, host employee/department, and badge number.
4. **`INCIDENT`:** Flags security breaches, vandalism, alarms, medical crises, or property damage, with severity tagging (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).
5. **`HANDOVER`:** Documents equipment serial numbers, post keys, physical site status, and outgoing/incoming guard signatures.
6. **`MAINTENANCE`:** Tracks facility defects: non-functional perimeter lights, compromised fences, damaged boom gates, and generator failures.

### K. How Amendments are Restricted by Time
- `OccurrenceBookEntry` tracks `created_at`.
- Both `backend/apps/occurrence_book/serializers.py` and `views.py` compute:
  `is_amendable = (timezone.now() - obj.created_at) < timedelta(hours=24)`
- Any amendment attempt after 24 hours returns HTTP 400: *"OB Entry is immutable after 24 hours. Submit a new cross-referenced entry."*
- The Android UI checks `entry.isAmendable`: if expired, the "Amend" button is replaced with an "Immutable (24h+)" status badge.

### L. How SOS Creates an Audible Alert
- Dispatching SOS sends an urgent beacon to `/incidents/sos/` with device latitude/longitude and category.
- `NotificationHelper.triggerEmergencyNotification` dispatches a notification through `EMERGENCY_ALERTS_CHANNEL` configured with:
  - `NotificationManager.IMPORTANCE_HIGH`
  - Default alarm sound URI (`RingtoneManager.TYPE_NOTIFICATION`)
  - Continuous vibration pattern: `[0, 500, 200, 500, 200, 500] ms`
  - High-visibility full-screen intent / heads-up notification.

### M. How Patrol is Genuinely Recorded
- The guard initiates a patrol linked to active station checkpoints.
- `PatrolScreen` runs a live timer (`00:00:00`) tracking patrol duration.
- Guard physically scans QR or NFC checkpoints; each scan registers timestamp and coordinates.
- Completion requires all checkpoints to be inspected; premature completion with 0 scans is strictly blocked.
- Debrief logs are submitted to `/patrols/logs/` and stored in the database with supervisor review visibility.

### N. How Handover Works Between Two Guards
- The outgoing guard completes a structured handover form detailing occurrences, equipment issued (radios, torches, batons), perimeter keys, and pending issues.
- A toggle for "Emergency Early Handover" allows early handover if approved.
- The handover is recorded in `ShiftHandover` with `incoming_accepted = False`.

### O. How Incoming Guard Confirms Takeover
- The incoming guard's app detects the pending takeover and triggers an operational notification.
- In `HandoverScreen`, the incoming guard opens `AcceptHandoverDialog`.
- The dialog displays outgoing guard details, occurrence log, keys, equipment, and discrepancies.
- The guard must check: *"I physically verify and confirm receipt of all post equipment, keys, and security status from [Outgoing Guard]"*.
- Upon confirmation, `/shifts/handovers/{id}/accept/` is called, recording the incoming officer's identity and timestamp.

### P. How Supervisor Sees Handover/Takeover
- Supervisors access the station handover ledger.
- Each handover shows live status (`ACCEPTED`, `PENDING ACCEPTANCE`, or `REJECTED`).
- If rejected, discrepancies entered by the incoming guard are highlighted in red for supervisor intervention.
- The supervisor can review the exact timestamps of both outgoing submission and incoming acceptance.

### Q. Which Operational Text Was Removed from Hardcoding
- Hardcoded guard names, officer IDs, and dummy phone numbers were removed from `TodayShiftScreen`, `DashboardScreen`, `PatrolScreen`, `OccurrenceBookScreen`, and `HandoverScreen`.
- Station names, geofence coordinates, checkpoint codes, visitor logs, and incident reports now bind directly to backend entity models.
- Date representations dynamically use Zimbabwe/Harare timezone strings instead of static strings.

### R. How DAY/NIGHT and 4-on/8-off are Enforced
- Shift generation services strictly implement the national standard security shift pattern:
  - `DAY`: 06:00 to 18:00 (12 hours)
  - `NIGHT`: 18:00 to 06:00 (12 hours)
- Guard pairs operate on 4-day rotations (e.g. 4 consecutive day shifts or 4 consecutive night shifts) followed by mandatory off-duty days (8 days off / rest cycles across pair rotations), mathematically enforced by the authoritative roster algorithm.

---

## 5. CONCLUSION & FINAL SIGN-OFF

Phase 13 is **100% complete and fully verified**. All features specified in the authoritative blueprint for the Guard Experience and supporting backend systems have been integrated, unit-tested, and built into a working release-ready debug APK.
