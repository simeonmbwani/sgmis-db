# SMART SECURITY — PHASE 14
## LEAVE, ACCRUAL, PUBLIC HOLIDAY & ROSTER IMPLEMENTATION AUDIT
### READ-ONLY PRE-IMPLEMENTATION FORENSIC ASSESSMENT

**Date:** September 27, 2026  
**Authoritative Base:** `C:\Projects\SGMIS_FIXED\sgmis_fixed`  
**Authoritative Input:** Phase 14 Authoritative Leave Business Rules Specification  
**Protected Backup Status:** `sgmis_fixed_BACKUP_BEFORE_GEMINI_MERGE` strictly excluded.  

---

## 1. EXISTING DATA MODELS AUDIT

### 1.1 `LeaveBalance` (`backend/apps/leave/models.py`)
- **Fields:** `guard`, `year`, `annual_days` (legacy), `sick_days` (legacy), `used_annual`, `used_sick`, `casual_days`, `vacation_days`, `used_casual`, `used_vacation`, `casual_accrual_rate` (1.0), `vacation_accrual_rate` (2.5), `vacation_cap` (90.0), `last_accrual_date`, `casual_cycle_start`.
- **Critical Findings & Violations:**
  1. *GET-Request Mutation:* In `LeaveBalanceViewSet.my_balance()` and `build_guard_leave_summary()`, `balance.accrue_to_date()` is invoked directly during HTTP GET calls. Merely opening the app or refreshing the dashboard mutates database fields.
  2. *Incomplete Month Accrual Risk:* The helper `_whole_months(start, end)` calculates elapsed calendar months without verifying that the working month has formally concluded. An incomplete current month could be prematurely credited.
  3. *Lack of Accrual Transaction Ledger:* There is currently no `LeaveAccrualRecord` model. Accruals increment balance columns directly, making it impossible to audit which specific months (e.g., January 2026, February 2026) were credited and who/what triggered the credit.
  4. *Holiday Compensation Leak:* Method `credit_public_holiday_duty()` adds compensatory days to `self.vacation_days`. This violates the strict separation between public holiday compensation and statutory vacation leave.

### 1.2 `LeaveApplication` (`backend/apps/leave/models.py`)
- **Fields:** `guard`, `leave_type`, `start_date`, `end_date`, `reason`, `emergency_phone`, `emergency_address`, `status`, `reviewer`, `reviewer_notes`, `rejection_reason`, `created_at`, `updated_at`.
- **Critical Findings & Violations:**
  1. *Missing `SPECIAL` Leave Type:* `LeaveType` choices currently include `CASUAL`, `VACATION`, `COMPENSATION`, `ANNUAL`, `SICK`, `EMERGENCY`, `COMPASSIONATE`. `SPECIAL` leave is missing.
  2. *Missing Supporting Documentation Fields:* There is no `doctor_report` or `medical_certificate` field for Sick Leave verification, and no `event_details` field for Special Leave justification.
  3. *Deduction Inconsistency:* When approving `SICK` leave, `balance.used_sick += days` increments legacy `used_sick`, but sick leave should not be constrained by an arbitrary fixed annual balance; it must be governed by the validated doctor's report.

### 1.3 `PublicHolidayCompensationLedger` (`backend/apps/leave/models.py`)
- **Fields:** `guard`, `entry_type` (`EARNED` vs `USED`), `days`, `duty_record`, `leave_application`, `notes`, `created_by`, `created_at`.
- **Unique Constraints:** `UniqueConstraint(duty_record, EARNED)` and `UniqueConstraint(leave_application, USED)`.
- **Status:** **EXCELLENT FOUNDATION.** The ledger cleanly guarantees idempotency and 2-day compensation per worked holiday. It only needs complete decoupling from `vacation_days`.

### 1.4 `PublicHoliday` & `PublicHolidayDutyRecord` (`backend/apps/shifts/models.py`)
- **Workflow:** Links an official `PublicHoliday` to an actual worked `Shift` and verified `Attendance` record (`attendance.clock_in`).
- **Status:** High integrity. Approving a record transitions `status=APPROVED` and inserts an `EARNED` transaction into `PublicHolidayCompensationLedger`.

---

## 2. ACCRUAL ENGINE FORENSICS

### 2.1 The Completed-Month Rule vs. Current Implementation
- **Authoritative Rule:**
  - Casual: 1 day per COMPLETED working month.
  - Vacation: 2.5 days per COMPLETED working month (capped at 90 days).
  - No advance accrual. No borrowing against future months. Incomplete current months must never be credited.
- **Current Defect:**
  In `models.py`:
  ```python
  months = self._whole_months(self.last_accrual_date, as_of)
  if months:
      self.casual_days = min(12.0, float(self.casual_days) + months * float(self.casual_accrual_rate))
      self.vacation_days = min(float(self.vacation_cap), float(self.vacation_days) + months * float(self.vacation_accrual_rate))
      self.last_accrual_date = as_of
      self.save(...)
  ```
  If `as_of` is the 15th of the month, and `last_accrual_date` was the 1st of the previous month, `_whole_months` returns 1, advancing `last_accrual_date` to the 15th, causing partial-month skew and triggering write transactions inside GET requests.

### 2.2 Idempotency & Ledger Requirement
To achieve absolute idempotency and eliminate duplicate accruals under repeated API requests:
- Accrual must be processed month-by-month as discrete calendar intervals `(year, month)`.
- A dedicated `LeaveAccrualRecord` model must store each completed month credited:
  `guard`, `year`, `month`, `casual_credited`, `vacation_credited`, `credited_at`, `created_by`.
- A database `UniqueConstraint(guard, year, month)` ensures that Month M of Year Y can **never** be credited twice, even under concurrent or repeated execution.

---

## 3. SPECIAL & SICK LEAVE RULES EVALUATION

### 3.1 Special Leave
- **Authoritative Rule:** No monthly accrual. Event-based (e.g. bereavement, special family event). The approved event determines the number of days. Must not consume Casual or Vacation balances.
- **Action Plan:**
  - Add `SPECIAL = "SPECIAL", "Special Leave"` to `LeaveType`.
  - Add `event_details` and `event_date` to `LeaveApplication`.
  - When approving `SPECIAL` leave, do NOT deduct from Casual or Vacation balances. Record approval in audit logs.

### 3.2 Sick Leave
- **Authoritative Rule:** No monthly accrual. Days determined by the doctor's report. Guard submits application + doctor's report. Supervisor reviews and verifies document before approval. Approved record is forwarded/audited for Superuser Administrator.
- **Action Plan:**
  - Add `doctor_report` to `LeaveApplication`.
  - Supervisor must verify `doctor_report` is present before approving `SICK` leave.
  - Forward approved sick leave notifications to Administration.
  - Do NOT deduct sick leave from Casual or Vacation balances.

---

## 4. PUBLIC HOLIDAY & COMPENSATORY DAYS EVALUATION

- **Authoritative Rule:** Qualifying guard working on a public holiday receives 2 compensatory days. Handled completely separately from Casual and Vacation.
- **Action Plan:**
  - Remove `leave_balance.credit_public_holiday_duty()` call in `approve_holiday_compensation()`.
  - Rely exclusively on `PublicHolidayCompensationLedger` as the single source of truth for earned and used compensatory days.
  - Expose compensatory days in "My Roster" table with clear statuses (`PUBLIC HOLIDAY` vs `COMPENSATORY DAY AVAILABLE`).

---

## 5. ROSTER & DUTY STATE INTEGRATION EVALUATION

- **Authoritative Rule:** Roster must distinguish: `ON DUTY`, `OFF/TIME OFF`, `ON LEAVE`, `PUBLIC HOLIDAY`, `COMPENSATORY DAY`. Approved leave must override scheduled duty.
- **Action Plan:**
  - In `DutyState` evaluation (`ShiftViewSet.duty_state`), check if guard has an active approved `LeaveApplication` for today. If so, return `status="ON_LEAVE"`, `can_clock_in=False`, and prohibit guard operational logging.
  - In `SgmisViewModel` and `GuardDutyPlanScreen.kt`, map each date to its authoritative status:
    - If approved leave covers the date -> `ON LEAVE` (with Leave Type).
    - If worked public holiday -> `PUBLIC HOLIDAY` (DUTY).
    - If compensatory day earned -> `COMPENSATORY DAY` (AVAILABLE).
    - Otherwise -> `ON DUTY` or `OFF/TIME OFF`.

---

## 6. SUPERVISOR & ADMINISTRATOR UI AUDIT

- **Supervisor Leave Console:**
  - Currently, `LeaveScreen.kt` is a hybrid guard/supervisor screen showing the same personal balance cards.
  - Needs a dedicated, command-oriented `SupervisorLeaveManagementView`:
    - Station guard table: `GUARD | EMPLOYEE NUMBER | LEAVE TYPE | ACCRUED | USED | REMAINING | PENDING | CURRENT STATUS`.
    - Pending approval queue with sick leave doctor report verification.
    - Read-only balance figures.
- **Administrator Leave Control:**
  - Multi-station, national oversight of all guard leave balances.
  - Full audit trail of accruals (`LeaveAccrualRecord`), compensatory ledger entries, and approved sick/special leaves.
  - Explicit, validated administrative correction actions.

---

## 7. API ENDPOINT RECONCILIATION SUMMARY

| Target Route | HTTP Method | Issue & Reconciliation Plan |
| :--- | :--- | :--- |
| `api/leave/balances/my-summary/` | GET | Fix route so both `my-summary` and `my_summary` resolve without trailing slash redirect issues. Remove GET mutation. |
| `api/leave/balances/accrue/` | POST | New admin/scheduled endpoint to process completed month accruals explicitly and idempotently. |
| `api/leave/applications/{id}/review/` | POST | Enforce doctor report presence for SICK leave, event details for SPECIAL leave, and strict balance sufficiency for CASUAL/VACATION/COMPENSATION. |
| `api/shifts/shifts/duty_state/` | GET | Incorporate active approved leave into `duty_state` response (`ON_LEAVE`). |
| `api/shifts/shifts/approve_roster/` | POST | Reconcile with `DutyRosterViewSet` collection approval to prevent 405/404 cascades. |

---

*End of Phase 14 Read-Only Audit. Proceeding to implementation.*
