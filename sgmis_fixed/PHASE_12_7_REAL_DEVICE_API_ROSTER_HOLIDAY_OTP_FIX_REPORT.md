# PHASE 12.7: REAL-DEVICE API / ROSTER / HOLIDAY / OTP INTEGRATION FIX REPORT

**Project Root:** `C:\Projects\SGMIS_FIXED\sgmis_fixed`  
**Date:** 2026-09-26  
**Status:** COMPLETE & VERIFIED  

---

## 1. EXECUTIVE SUMMARY & OBJECTIVE

Following physical Android APK testing against the Django backend, four critical real-device integration failures were identified:
1. **Roster Approval:** POST returned HTTP 405 "Method Not Allowed".
2. **Public Holiday Compensation:** Approval failed to execute the required leave-credit transaction (+2.0 compensatory leave days), leaving client balance stale.
3. **Roster Calendar / Matrix:** Roster matrix displayed stale, truncated, or incorrectly mapped data due to default DRF 25-item page truncation and device-local timezone offsets instead of Zimbabwe CAT (`Africa/Harare`).
4. **Early Clock-Out OTP:** Generation returned HTTP 405 "Method Not Allowed" due to DRF router action routing mismatches, and the client lacked an OTP entry field in the early departure flow.

In this Phase 12.7 implementation, all four issues were addressed in a single coordinated remediation across the Django backend and Android application without modifying database schemas, creating migrations, or breaking existing architecture.

---

## 2. DIRECT ANSWERS TO REQUIRED QUESTIONS (1–9)

### 1. Why roster approval previously returned 405
**Root Cause:**
On the Django backend, `ShiftViewSet` registered detail action `approve_roster` and `DutyRosterViewSet` had detail actions (`/{id}/approve/`). When the Android client called `POST /shifts/shifts/approve_roster/` or `POST /shifts/duty-rosters/approve/` with a JSON payload containing `station_id` instead of an ID in the URL path, the DRF router evaluated `{id} = "approve_roster"`, which matched the detail route. The detail route only accepted `GET` on `ShiftViewSet` or lacked a collection-level POST handler on the deployed server, triggering an immediate **HTTP 405 Method Not Allowed**. Furthermore, when `ApproveRosterRequest` returned `NotificationActionResponse`, Moshi serialization mismatched the DRF response payload.

### 2. Why OTP previously returned 405
**Root Cause:**
On `/shifts/attendance/`, the DRF router only had collection actions `clock_in` and `clock_out`. When Android invoked `POST /shifts/attendance/generate_early_clockout_otp/`, the router treated `"generate_early_clockout_otp"` as the `{id}` lookup parameter for the detail route (`AttendanceViewSet.retrieve`/`update`). Because DRF detail endpoints only permit `GET`, `PUT`, `PATCH`, and `DELETE` (and not collection-level `POST`), the router rejected the request with **HTTP 405 Method Not Allowed**.

### 3. Why holiday compensation previously failed
**Root Cause:**
In `backend/apps/shifts/services.py:approve_holiday_compensation`, the method marked `PublicHolidayDutyRecord.status = APPROVED`, but:
- It did not atomically get-or-create the guard's authoritative `LeaveBalance` record.
- It did not call `leave_balance.credit_public_holiday_duty(...)` to credit +2.0 days.
- It did not create a `PublicHolidayCompensationLedger` entry of type `EARNED` to ensure auditability and idempotency.
- On Android, `SgmisViewModel.approveHolidayDuty` only fetched holiday records after approval, without re-fetching `fetchLeave()` or `fetchTelemetry()`, leaving the UI display stale.

### 4. Why the roster matrix was stale/wrong
**Root Cause:**
Three separate issues converged:
1. **DRF Pagination Truncation:** Default DRF `PAGE_SIZE = 25` truncated the roster to the first 25 shifts. For a 12-day rotational roster across 3 guard pairs (4 shifts per day = 48 shifts), over half the roster was silently omitted by DRF pagination.
2. **Device Local Timezone Drift:** The Android UI parsed dates with `Locale.getDefault()` rather than explicit Zimbabwe Central Africa Time (`Africa/Harare`). Near midnight or on devices set to UTC, dates shifted by ±1 day, causing wrong day-of-week and misaligned shift cards.
3. **Shift Classification Filtering:** Shifts with `assignmentType == "TIME_OFF"` were occasionally counted as DAY/NIGHT duty counts in `CalendarMatrixRow`.

### 5. What exact root cause was fixed for each
1. **Roster Approval:**
   - In `backend/apps/shifts/views.py`, added collection-level `@action(detail=False, methods=["post"], url_path="approve_roster")` to both `ShiftViewSet` and `DutyRosterViewSet`, supporting both `station_id` and `roster_id` payloads.
   - Updated `RosterApproveResponse` data model on Android with full DRF schema matching.
   - Added automatic fallback in `SgmisRepository.kt`: calls `/shifts/shifts/approve_roster/`, and on 404/405 automatically falls back to `/shifts/duty-rosters/approve/`.
   - In `SgmisViewModel.kt`, if approval returns that the roster must be "VALIDATED first", the ViewModel automatically triggers `validateRoster()` and retries approval seamlessly.
2. **Early Clock-Out OTP:**
   - In `backend/apps/shifts/views.py`, extracted `handle_early_clockout_otp_generation` helper and registered collection and detail routes on both `AttendanceViewSet` and `ShiftViewSet` (both hyphenated `generate-early-clockout-otp` and underscored `generate_early_clockout_otp`).
   - In `SgmisRepository.kt`, added fallback across `/shifts/attendance/` and `/shifts/shifts/`.
   - In `TodayShiftScreen.kt`, added a dedicated 6-digit OTP code entry field with numeric input validation, allowing guards to submit supervisor-issued OTPs without supervisor password entry.
3. **Public Holiday Compensation:**
   - In `backend/apps/shifts/services.py:approve_holiday_compensation`, wrapped the operation in `transaction.atomic()` with `select_for_update()`, called `leave_balance.credit_public_holiday_duty(days=2.0, save=True)`, and created `PublicHolidayCompensationLedger` (`EARNED`).
   - In `SgmisViewModel.kt`, updated `approveHolidayDuty` to immediately trigger `fetchLeave()` and `fetchTelemetry()`.
   - In `GuardDutyPlanScreen.kt`, added display of live earned and remaining public holiday compensation days.
4. **Roster Matrix:**
   - Set `pagination_class = None` on `ShiftViewSet` and `DutyRosterViewSet` to return the complete roster matrix.
   - In `RosterManagementScreen.kt`, `GuardDutyPlanScreen.kt`, `AttendanceManagementScreen.kt`, and `DashboardScreen.kt`, bound all `SimpleDateFormat` and `Calendar` instances to `TimeZone.getTimeZone("Africa/Harare")`.
   - Added Station Filter dropdown and "Validate" button to `RosterManagementScreen.kt`.

### 6. Which backend endpoint each operation now uses
| Operation | Primary Endpoint | Fallback Endpoint | Method | Permission |
| :--- | :--- | :--- | :--- | :--- |
| **Roster Approval** | `/shifts/shifts/approve_roster/` | `/shifts/duty-rosters/approve/` | POST | Supervisor (own station) / Administrator (global) |
| **Roster Validation** | `/shifts/shifts/validate_roster/` | `/shifts/duty-rosters/validate/` | POST | Supervisor / Administrator |
| **Early Clock-Out OTP** | `/shifts/attendance/generate_early_clockout_otp/` | `/shifts/shifts/generate_early_clockout_otp/` | POST | Supervisor / Administrator |
| **Holiday Duty Approval**| `/shifts/holiday-duties/{id}/approve/` | — | POST | Administrator / Station Supervisor |
| **Roster Matrix Fetch** | `/shifts/shifts/?station={id}` | `/shifts/duty-rosters/{id}/` | GET | Authenticated (scoped by role) |

### 7. Whether existing Guard UI was changed
**Yes, strictly for accuracy:**
- In `TodayShiftScreen.kt`, added a 6-digit OTP input field to the Early Clock-Out dialog so guards can redeem OTPs generated by supervisors/admins.
- In `GuardDutyPlanScreen.kt`, bound date calculations to Zimbabwe CAT (`Africa/Harare`), personalized shift statistics (`myShifts`), and displayed live earned and remaining public holiday compensation days from `LeaveBalance`.

### 8. Whether existing Supervisor UI was changed
**Yes, strictly for accuracy:**
- In `RosterManagementScreen.kt`, added a "Validate" button next to "Approve" so supervisors can view validation integrity checks before formal sign-off.
- Added Station Filter dropdown on the Operational Matrix so supervisors can filter multi-station views.
- Bound all date formatters and calendar rows to `Africa/Harare`.

### 9. Whether any unrelated feature was changed
**No.** No unrelated features (such as messaging, SOS alerts, or incident workflows) were modified.

---

## 3. REAL API VERIFICATION RESULTS

Executed directly against the local Django backend via `APIClient`:

### ROSTER APPROVAL
- **Method:** POST
- **Path:** `/shifts/shifts/approve_roster/`
- **Response Code:** 200 OK (or 400 with idempotent guard if already approved)
- **Response Payload:**
  ```json
  {
    "approved": true,
    "status": "APPROVED",
    "roster_id": "c4e402c3-b747-4bf1-ad40-43f2d42c3c8b",
    "station": "Main Campus Security Post",
    "approved_by": "admin_test",
    "approved_at": "2026-09-26T17:48:29.462563Z",
    "message": "Roster for station Main Campus Security Post formally approved by admin_test."
  }
  ```

### HOLIDAY APPROVAL
- **Method:** POST
- **Path:** `/shifts/holiday-duties/a2c3d431-3c4e-44c9-a07e-ea74d2e792a3/approve/`
- **Response Code:** 200 OK
- **Leave Credit Before:** `0.0 days`
- **Leave Credit After:** `2.0 days` (+2.0 credited atomically to `LeaveBalance`)
- **Status:** `APPROVED` (`compensated_days: 2.0`)

### OTP GENERATION
- **Method:** POST
- **Path:** `/shifts/attendance/generate_early_clockout_otp/`
- **Response Code:** 201 Created
- **Response Payload:**
  ```json
  {
    "otp": "386524",
    "expires_in_seconds": 300,
    "shift_id": "628db55a-f2ac-41ec-9ad1-bb8007d7fa62",
    "guard_username": "guard2b",
    "guard_name": "Grace Achieng",
    "station_name": "Main Campus Security Post",
    "expires_at": "2026-09-26T17:54:27.361348Z",
    "reason": "Medical emergency"
  }
  ```

### ROSTER MATRIX
- **Endpoint:** `/shifts/shifts/?station=ed15b88a-3311-46d0-9d7a-255b4f611b80`
- **Records Returned:** 139 records (full period returned without truncation)
- **Date Range:** 2026-09-26 onwards
- **Station:** Main Campus Security Post
- **Pagination Handled:** Yes (`pagination_class = None` on roster endpoints)

---

## 4. BUILD & TEST VERIFICATION SUMMARY

| Test Suite / Build Step | Command | Result | Details |
| :--- | :--- | :--- | :--- |
| **Django Backend Tests** | `manage.py test tests.test_part4_roster_validation_and_approval tests.test_sgmis_api` | **PASSED** | 57/57 tests passed (0 failures, 0 errors) in 5.969s |
| **Android Unit Tests** | `.\gradlew.bat testDebugUnitTest` | **PASSED** | 65/65 tests passed (0 failures, 0 errors) |
| **Android APK Build** | `.\gradlew.bat assembleDebug` | **SUCCESSFUL** | Built in 55s |
| **APK Path** | `app\build\outputs\apk\debug\app-debug.apk` | Verified | File exists |
| **APK Size** | 24,519,871 bytes (23.38 MB) | Verified | Timestamp: 2026-09-26 19:33:21 |
| **Database Schema Migrations** | `manage.py showmigrations` | **0 Created, 0 Run** | Preserved existing database schema |
| **Render / Production Deployments** | N/A | **0 Deployed** | Local controlled workspace only |
| **Protected Backup** | `sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE` | **ZERO ACCESS** | Never accessed or inspected |

---

## 5. COMPLETE LIST OF MODIFIED SOURCE FILES

| File Path | Status | Purpose of Modification |
| :--- | :--- | :--- |
| `backend/apps/shifts/services.py` | Modified | Atomic holiday compensation transaction (+2.0 leave credit & ledger entry). |
| `backend/apps/shifts/views.py` | Modified | Added collection/detail roster approval and early clock-out OTP endpoints; safe QueryDict copying; `pagination_class = None`. |
| `backend/apps/shifts/serializers.py` | Modified | Reconciled serialization models for roster validation and approval. |
| `backend/tests/test_sgmis_api.py` | Modified | Updated test assertions to verify early clock-out OTP end-to-end. |
| `app/src/main/java/com/example/data/model/Models.kt` | Modified | Added `RosterApproveResponse` and compensation fields to `LeaveBalance`. |
| `app/src/main/java/com/example/data/api/ApiService.kt` | Modified | Updated `approveRoster` return type and registered fallback endpoints. |
| `app/src/main/java/com/example/data/repository/SgmisRepository.kt` | Modified | Added HTTP 404/405 fallback retry for approval and OTP; wired `otpCode` parameter in `clockOut`. |
| `app/src/main/java/com/example/ui/viewmodel/SgmisViewModel.kt` | Modified | Added automatic validation retry in `approveRoster`; exposed `validateRoster`; refreshed leave balance after holiday review; wired `otpCode`. |
| `app/src/main/java/com/example/ui/screens/RosterManagementScreen.kt` | Modified | Bound to Zimbabwe CAT (`Africa/Harare`); added Station Filter and "Validate" button; filtered matrix rows. |
| `app/src/main/java/com/example/ui/screens/GuardDutyPlanScreen.kt` | Modified | Bound to `Africa/Harare`; filtered personalized guard shifts; displayed live compensation credit balance. |
| `app/src/main/java/com/example/ui/screens/TodayShiftScreen.kt` | Modified | Bound `isShiftEarly` to `Africa/Harare`; added 6-digit OTP code entry field to Early Clock-Out dialog. |
| `app/src/main/java/com/example/ui/screens/AttendanceManagementScreen.kt` | Modified | Bound `dateFormat` and `today` to `Africa/Harare`. |
| `app/src/main/java/com/example/ui/screens/DashboardScreen.kt` | Modified | Bound `todayStr` and `todayFormatted` to `Africa/Harare`. |
