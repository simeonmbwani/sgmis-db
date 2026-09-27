# PHASE 14 — COMPLETE LEAVE, ACCRUAL, PUBLIC HOLIDAY AND ROSTER BUSINESS-RULE RECONSTRUCTION
## Comprehensive Architectural, Backend, and Mobile Audit & Verification Report

---

### Executive Summary

Phase 14 represents the complete reconstruction of the leave management, monthly accrual execution, public holiday duty compensation, and roster business rules for the Smart Security Management Information System (**SGMIS**). 

This reconstruction rectifies previous prototype-level limitations and replaces invented policies with the authoritative commercial blueprint rules mandated for commercial security operations. The solution guarantees three independent leave and compensation ledgers, strictly server-driven transactional monthly accruals, three distinct role-based experiences (Guard, Supervisor, Administrator), and an authoritative 8-column roster matrix for operational transparency.

All 252 backend unit and integration tests are passing (100% OK), Android unit tests pass cleanly, and the production-ready Android Debug APK compiles and packages without error.

---

### 1. Authoritative Leave Business Rules Implementation

The system strictly adheres to the mandated business rules without cross-polluting ledgers:

#### A. Casual Leave
- **Accrual Rate:** Strictly **1.0 day** per completed working month.
- **Accrual Timing:** Accrues only upon the completion of a full working month. Never advances at the start of a month, during onboarding, or during GET/dashboard queries.
- **Forfeiture Cycle:** Governed by an authoritative 12-month forfeiture cycle tracked per guard. Unused casual leave is forfeited at the end of the annual cycle without rollover.
- **Independence:** Casual leave usage and balances are tracked in dedicated fields (`casual_days`, `used_casual`, `remaining_casual`) and never contaminate vacation or holiday compensation ledgers.

#### B. Vacation Leave
- **Accrual Rate:** Strictly **2.5 days** per completed working month.
- **Accrual Timing:** Accrues strictly on server-side completion of each working month. No advance crediting.
- **Cap:** Hard ceiling of **90.0 accrued days** enforced at model, service, and database levels. Any accrual exceeding 90.0 is capped with audit notes.
- **Rollover:** Permitted across calendar years, subject to the 90.0-day maximum accumulation limit.

#### C. Special Leave
- **Nature:** Strictly event-based (compassionate, bereavement, study, court subpoena, national duty).
- **Accrual:** No monthly accrual. Zero advance crediting.
- **Documentation:** Requires mandatory operational/event justification (`event_details`) submitted by the guard before filing is accepted.
- **Independence:** Deducts only from special event allowances and does not deduct from Casual, Vacation, or Public Holiday balances.

#### D. Sick Leave
- **Nature:** Determined strictly by qualified medical reports. No monthly accrual.
- **Documentation & Verification:** Filing requires mandatory medical certificate/doctor's report (`doctor_report`).
- **Approval Workflow:** Supervisors and Administrators must explicitly verify the doctor's report before approving the application. The system presents verification badges (`[REQUIRES VERIFICATION]` vs `[VERIFIED]`) and records the verifying officer.
- **Independence:** Kept strictly independent from Vacation and Casual Leave.

#### E. Public Holiday Duty Compensation
- **Compensation Rate:** Exactly **2.0 compensatory days** per official public holiday worked.
- **Ledger Isolation:** Managed in an independent transactional ledger (`PublicHolidayCompensationLedger`), completely isolated from Casual and Vacation leave.
- **Earning Trigger:** Credited upon verified attendance on official Zimbabwe public holidays (`PublicHolidayDutyRecord` with status `APPROVED`).
- **Visibility:** Reflected in the guard's official roster overview table and balance breakdown.

---

### 2. Three Distinct Leave Role Experiences

The implementation enforces strict permission separation across all application layers:

```
+-----------------------------------------------------------------------------------+
| GUARD EXPERIENCE                                                                  |
| - View own balances (Casual, Vacation, Holiday Compensation)                      |
| - View personal application history & official status badges                      |
| - Submit new leave applications (with dynamic doctor report / event fields)       |
| - View personal Public Holiday duty compensation & authoritative roster table     |
| [RESTRICTIONS: Cannot approve, reject, edit balances, or run accruals]            |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| SUPERVISOR EXPERIENCE                                                             |
| - View station guards' leave balances (READ-ONLY OVERSIGHT)                       |
| - Review pending leave applications for station guards                            |
| - Verify medical documentation / doctor's reports before approving sick leave    |
| - Approve or reject applications (mandatory operational justification on reject)   |
| [RESTRICTIONS: Cannot run monthly accrual engine or alter ledger balance records]  |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| ADMINISTRATOR EXPERIENCE (NATIONAL LEAVE AUTHORITY)                              |
| - View company-wide balances across all stations                                  |
| - Review and approve/reject all applications nationwide                          |
| - Trigger the Authoritative Monthly Accrual Execution Engine                      |
| - Inspect Leave Accrual Audit Records (`LeaveAccrualRecord` history)              |
| - Full audit logging of all balance adjustments and ledger transactions          |
+-----------------------------------------------------------------------------------+
```

---

### 3. Server-Side Accrual Engine Architecture

- **Execution Endpoint:** `POST /leave/balances/process-accruals/`
- **Permission:** Strictly restricted to authenticated Administrators (`IsAdministrator`).
- **Audit Logging:** Every successful run creates immutable `LeaveAccrualRecord` entries recording:
  - Guard ID & Name
  - Calendar Year & Month
  - Casual Days Credited (+1.0)
  - Vacation Days Credited (+2.5, respecting 90-day cap)
  - Post-accrual Casual & Vacation balances
  - Executing Administrator User ID & Timestamp
- **Idempotency:** Unique composite constraint on `(guard, year, month)` in `LeaveAccrualRecord` ensures that running the accrual engine multiple times in the same month never duplicates leave credits.
- **Transaction Safety:** Wrapped in Django's atomic database transactions (`transaction.atomic()`).

---

### 4. Authoritative "My Roster" 8-Column Table

In `app/src/main/java/com/example/ui/screens/GuardDutyPlanScreen.kt`, the guard's personal roster overview features an official tabular matrix conforming to blueprint specifications:

| Column Header | Source Field / Business Logic |
| :--- | :--- |
| **DATE** | `shift.date` (YYYY-MM-DD format) |
| **DAY** | Day of the week (e.g., `MON`, `TUE`, `WED`) |
| **STATUS** | `LEAVE` (if on approved leave), `OFF` (time off), `DAY`, `NIGHT` |
| **SHIFT** | Scheduled hours e.g. `06:00 - 18:00` or `18:00 - 06:00` |
| **PARTNER** | Partner guard name from pair rotation or `Solo` |
| **LEAVE TYPE** | Approved leave type name or `Off Duty` / `-` |
| **PUBLIC HOLIDAY** | Official holiday name (e.g., `Heroes' Day`) if date is a holiday |
| **COMPENSATORY DAY** | `+2.0 Days (Earned)` / `Pending (+2d)` / `Used (-1d)` / `Eligible` |

The table is horizontally scrollable with alternating row highlights, distinct holiday badge tints, and a top-level toggle between "My Roster Table (Official)" and "Duty Stream Cards".

---

### 5. Backend Implementation Summary

1. **`backend/apps/leave/models.py`:**
   - Fixed decimal-float casting in `accrue_to_date()` (`Decimal(str(...))`).
   - `credit_public_holiday_duty()`: Updates `PublicHolidayCompensationLedger` with `entry_type=EARNED` without polluting `vacation_days`.
   - `LeaveApplication`: Includes `doctor_report`, `doctor_report_verified`, `doctor_report_verified_by`, `doctor_report_verified_at`, and `event_details`.
   - `LeaveAccrualRecord`: Model with composite unique constraint `(guard, year, month)` for idempotent accrual audit trails.

2. **`backend/apps/leave/serializers.py`:**
   - `LeaveApplicationSerializer`: Validates `VACATION`, `CASUAL`, `COMPENSATION`, `SICK`, and `SPECIAL` leave types.
   - Enforces required `doctor_report` when applying for `SICK` leave.
   - Enforces required `event_details` when applying for `SPECIAL` leave.
   - `LeaveAccrualRecordSerializer`: Serializes audit trail records.

3. **`backend/apps/leave/services.py`:**
   - Authoritative business services for approval, rejection, and monthly accrual.
   - Preserved legacy fallbacks (`ANNUAL` -> `annual_days`) to ensure complete backwards compatibility with existing automated suites.

4. **`backend/apps/leave/views.py`:**
   - Implemented `process_accruals` action on `LeaveBalanceViewSet`.
   - Added `accrual_records` action to fetch historical accrual audits.
   - Scoped `LeaveBalanceViewSet` listing: Guards view own; Supervisors view station guards (read-only); Administrators view all guards.

5. **`backend/tests/test_phase14_authoritative_leave_rules.py`:**
   - 13 comprehensive unit tests asserting all Phase 14 requirements:
     - Completed month accrual requirement
     - Casual 1.0 day accrual and forfeiture
     - Vacation 2.5 day accrual and 90-day cap
     - Special leave independence and event details requirement
     - Sick leave doctor report requirement and supervisor verification
     - Public holiday 2-day compensation in separate ledger
     - Role-based permissions (Guard vs Supervisor vs Admin)
     - Accrual engine idempotency and audit record generation

---

### 6. Mobile Implementation Summary (Android Jetpack Compose)

1. **`com.example.data.model.Models.kt`:**
   - Added `doctorReport`, `doctorReportVerified`, `eventDetails` to `LeaveApplication`.
   - Added `doctorReport`, `eventDetails` to `CreateLeaveRequest`.
   - Added `LeaveAccrualRecord` and `ProcessAccrualResponse` data classes.
   - Added `compensationEarned`, `compensationUsed`, `remainingCompensation` to `LeaveBalance`.

2. **`com.example.data.api.ApiService.kt`:**
   - Added `getAllLeaveBalances(@Query("station") stationId: String?)`.
   - Added `getLeaveAccrualRecords()`.
   - Added `processMonthlyAccruals(@Body body: Map<String, String>)`.

3. **`com.example.data.repository.SgmisRepository.kt`:**
   - Updated `applyForLeave()` with `doctorReport` and `eventDetails`.
   - Added `fetchAllLeaveBalances()`, `fetchLeaveAccrualRecords()`, `processMonthlyAccruals()`.

4. **`com.example.ui.viewmodel.SgmisViewModel.kt`:**
   - Added `stationLeaveBalances`, `leaveAccrualRecords`, `accrualProcessing` to `SgmisUiState`.
   - Implemented `fetchStationLeaveBalances()`, `fetchLeaveAccrualRecords()`, `processMonthlyAccruals()`.

5. **`com.example.ui.screens.LeaveScreen.kt`:**
   - Role-scoped tabbed navigation:
     - **Guard:** Personal leave summary table + application history.
     - **Supervisor:** `Station Leave Command` (station guard balances table + pending review queue) & `My Leave`.
     - **Administrator:** `National & Accruals` (monthly accrual execution engine + audit records table + company-wide balances) & `All Applications` & `My Leave`.
   - `StationGuardLeaveTable`: Horizontally scrollable read-only table of station guards' balances.
   - `AuthoritativeAccrualAuditTable`: Tabular view of server-side accrual history.
   - `ApplyLeaveDialog`: Dynamic form supporting all 7 leave types with required medical/event fields.
   - `RejectLeaveDialog`: Dropdown of authoritative operational justifications with notes.

6. **`com.example.ui.screens.GuardDutyPlanScreen.kt`:**
   - `AuthoritativeRosterTable`: 8-column matrix with public holiday duty compensation indicators and toggle.

---

### 7. Test and Compilation Verification

#### Backend Test Suite
```
python manage.py test apps tests
----------------------------------------------------------------------
Ran 252 tests in 29.770s

OK
```
```
python manage.py test tests.test_phase14_authoritative_leave_rules
----------------------------------------------------------------------
Ran 13 tests in 0.244s

OK
```

#### Android Build & Unit Tests
```
.\gradlew testDebugUnitTest
----------------------------------------------------------------------
BUILD SUCCESSFUL in 5m 33s
34 actionable tasks: 8 executed, 26 up-to-date
```
```
.\gradlew assembleDebug
----------------------------------------------------------------------
BUILD SUCCESSFUL in 1m 19s
40 actionable tasks: 5 executed, 35 up-to-date
```

---

### 8. Project & Git Safety Protocol Adherence

- **Strict Backup Preservation:** `C:\Projects\SGMIS_FIXED\sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE` was never accessed, inspected, copied, modified, or touched. Added to root `.gitignore` to prevent any git tracking.
- **Non-Destructive Operations:** Strictly zero calls to `git reset`, `git clean`, `git stash`, `git checkout`, or `git restore`.
- **Deployment Control:** Zero deployments to Render were executed. Deployment remains deferred until explicit user authorization.
