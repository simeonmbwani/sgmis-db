from datetime import date, timedelta
from decimal import Decimal
from django.test import TestCase
from django.utils import timezone
from django.contrib.auth import get_user_model
from rest_framework.test import APIClient
from rest_framework import status

from apps.accounts.models import UserRole
from apps.stations.models import Station
from apps.shifts.models import (
    Shift,
    ShiftType,
    AssignmentType,
    Attendance,
    PublicHoliday,
    PublicHolidayDutyRecord,
    HolidayCompensationStatus,
)
from apps.shifts.services import (
    record_public_holiday_duty,
    approve_holiday_compensation,
)
from apps.leave.models import (
    LeaveBalance,
    LeaveApplication,
    LeaveStatus,
    LeaveType,
    LeaveAccrualRecord,
    PublicHolidayCompensationLedger,
    CompensationLedgerEntryType,
)
from apps.leave.services import (
    get_completed_months,
    process_guard_accruals,
    process_all_guards_accruals,
    approve_leave_application,
    reject_leave_application,
)

UserModel = get_user_model()


class Phase14AuthoritativeLeaveRulesTests(TestCase):
    """
    Exhaustive tests for Phase 14 Authoritative Leave & Accrual Business Rules:
    - Casual: 1.0 day/month completed, no advance, 12-month cycle forfeiture.
    - Vacation: 2.5 days/month completed, 90-day ceiling cap, no advance.
    - Special: Event-based, no monthly accrual, does not consume Casual/Vacation.
    - Sick: Doctor-supported, no monthly accrual, supervisor verified, forwarded to Admin.
    - Public Holiday Duty: +2 compensatory days in independent ledger.
    - Idempotency: Repeated processing never duplicates accrual.
    - Non-mutating GETs: Balance read endpoints never alter balances.
    - Roster Integration: Duty state becomes ON_LEAVE, shifts marked accordingly.
    """

    def setUp(self):
        self.client = APIClient()
        self.password = "SecPass123!"

        self.station = Station.objects.create(
            name="Alpha Command Post",
            code="STN-ALPHA-01",
            latitude=-17.8252,
            longitude=31.0335,
        )
        self.other_station = Station.objects.create(
            name="Beta Secondary Post",
            code="STN-BETA-02",
            latitude=-20.1500,
            longitude=28.5833,
        )

        self.admin = UserModel.objects.create_user(
            username="admin_national",
            email="admin@sgmis.corp",
            password=self.password,
            employee_number="ADM-001",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
            station=self.station,
        )

        self.supervisor = UserModel.objects.create_user(
            username="sup_alpha",
            email="sup_alpha@sgmis.corp",
            password=self.password,
            employee_number="SUP-001",
            role=UserRole.SUPERVISOR,
            station=self.station,
        )

        self.other_supervisor = UserModel.objects.create_user(
            username="sup_beta",
            email="sup_beta@sgmis.corp",
            password=self.password,
            employee_number="SUP-002",
            role=UserRole.SUPERVISOR,
            station=self.other_station,
        )

        self.guard = UserModel.objects.create_user(
            username="guard_tariro",
            email="tariro@sgmis.corp",
            password=self.password,
            employee_number="GRD-101",
            role=UserRole.GUARD,
            station=self.station,
        )

    # -------------------------------------------------------------------------
    # 1. CASUAL LEAVE ACCRUAL & RULES
    # -------------------------------------------------------------------------
    def test_casual_leave_does_not_accrue_for_incomplete_month(self):
        """Casual leave must NOT accrue when month is only halfway through."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"casual_cycle_start": date(2026, 1, 1)},
        )
        # Mid-month test: January 15, 2026 (Month 1 is NOT completed)
        created = process_guard_accruals(self.guard, as_of_date=date(2026, 1, 15))
        self.assertEqual(len(created), 0)
        balance.refresh_from_db()
        self.assertEqual(balance.casual_days, Decimal("0.0"))

    def test_casual_leave_accrues_exactly_one_day_after_month_completed(self):
        """Casual leave accrues 1 day once month has completed (Feb 1, 2026 completes Jan)."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"casual_cycle_start": date(2026, 1, 1)},
        )
        created = process_guard_accruals(self.guard, as_of_date=date(2026, 2, 1))
        self.assertEqual(len(created), 1)
        self.assertEqual(created[0].month, 1)
        self.assertEqual(created[0].casual_credited, Decimal("1.0"))

        balance.refresh_from_db()
        self.assertEqual(balance.casual_days, Decimal("1.0"))
        self.assertEqual(balance.remaining_casual, 1.0)

    def test_casual_leave_cannot_be_taken_in_advance(self):
        """A guard cannot apply for casual leave consuming unearned future entitlement."""
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard, year=2026)
        balance.casual_days = Decimal("1.0")
        balance.used_casual = Decimal("0.0")
        balance.save()

        # Applying for 2 days when only 1 is available must be rejected
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/api/leave/applications/", {
            "leave_type": "CASUAL",
            "start_date": "2026-03-01",
            "end_date": "2026-03-02",
            "reason": "Personal urgent matter",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)

    # -------------------------------------------------------------------------
    # 2. VACATION LEAVE ACCRUAL & 90-DAY CAP
    # -------------------------------------------------------------------------
    def test_vacation_leave_accrues_two_and_half_days_per_completed_month(self):
        """Vacation leave accrues 2.5 days per completed month."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"casual_cycle_start": date(2026, 1, 1)},
        )
        # 3 completed months (Jan, Feb, Mar): as of April 1, 2026
        created = process_guard_accruals(self.guard, as_of_date=date(2026, 4, 1))
        self.assertEqual(len(created), 3)

        balance.refresh_from_db()
        self.assertEqual(balance.vacation_days, Decimal("7.5"))
        self.assertEqual(balance.remaining_vacation, 7.5)

    def test_vacation_leave_enforces_maximum_cap_ninety_days(self):
        """Vacation balance must never exceed 90.0 days."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"casual_cycle_start": date(2026, 1, 1), "vacation_days": Decimal("89.0")},
        )
        process_guard_accruals(self.guard, as_of_date=date(2026, 2, 1))
        balance.refresh_from_db()
        self.assertEqual(balance.vacation_days, Decimal("90.0"))

        # Accrue another month; balance must stay at 90.0
        process_guard_accruals(self.guard, as_of_date=date(2026, 3, 1))
        balance.refresh_from_db()
        self.assertEqual(balance.vacation_days, Decimal("90.0"))

    # -------------------------------------------------------------------------
    # 3. SPECIAL LEAVE (EVENT-BASED)
    # -------------------------------------------------------------------------
    def test_special_leave_does_not_accrue_and_does_not_consume_casual_or_vacation(self):
        """Special leave is event-based and does not deduct from Casual or Vacation."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"casual_days": Decimal("5.0"), "vacation_days": Decimal("20.0")},
        )

        app = LeaveApplication.objects.create(
            guard=self.guard,
            leave_type=LeaveType.SPECIAL,
            start_date=date(2026, 5, 10),
            end_date=date(2026, 5, 15),
            reason="Bereavement - passing of father",
            event_details="Bereavement leave requested for funeral arrangements in Masvingo.",
        )

        approve_leave_application(app, reviewer=self.supervisor, reviewer_notes="Approved with condolences")

        app.refresh_from_db()
        balance.refresh_from_db()

        self.assertEqual(app.status, LeaveStatus.APPROVED)
        # Balances must be completely untouched
        self.assertEqual(balance.casual_days, Decimal("5.0"))
        self.assertEqual(balance.used_casual, Decimal("0.0"))
        self.assertEqual(balance.vacation_days, Decimal("20.0"))
        self.assertEqual(balance.used_vacation, Decimal("0.0"))

    # -------------------------------------------------------------------------
    # 4. SICK LEAVE (DOCTOR'S REPORT REQUIRED)
    # -------------------------------------------------------------------------
    def test_sick_leave_requires_doctor_report_and_does_not_consume_casual_or_vacation(self):
        """Sick leave requires a verified doctor's report and does not consume Casual/Vacation."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"casual_days": Decimal("4.0"), "vacation_days": Decimal("15.0")},
        )

        app_no_doc = LeaveApplication.objects.create(
            guard=self.guard,
            leave_type=LeaveType.SICK,
            start_date=date(2026, 6, 1),
            end_date=date(2026, 6, 3),
            reason="Severe malaria",
            doctor_report="",  # Missing doctor report
        )

        # Approval without doctor report must fail
        with self.assertRaises(Exception):
            approve_leave_application(app_no_doc, reviewer=self.supervisor)

        # Supply doctor report
        app_no_doc.doctor_report = "Dr. M. Moyo, Parirenyatwa Hospital. Ref: MED-2026-0891. 3 days bed rest advised."
        app_no_doc.save()

        approve_leave_application(app_no_doc, reviewer=self.supervisor, reviewer_notes="Medical certificate verified.")

        app_no_doc.refresh_from_db()
        balance.refresh_from_db()

        self.assertEqual(app_no_doc.status, LeaveStatus.APPROVED)
        self.assertTrue(app_no_doc.doctor_report_verified)
        self.assertEqual(balance.casual_days, Decimal("4.0"))
        self.assertEqual(balance.vacation_days, Decimal("15.0"))

    # -------------------------------------------------------------------------
    # 5. PUBLIC HOLIDAY COMPENSATION ISOLATION
    # -------------------------------------------------------------------------
    def test_public_holiday_compensation_tracked_in_ledger_separately(self):
        """Public holiday duty earns 2 days compensation without modifying vacation balance."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"vacation_days": Decimal("10.0")},
        )

        holiday = PublicHoliday.objects.create(
            name="Workers Day",
            date=date(2026, 5, 1),
            is_active=True,
        )
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard,
            date=date(2026, 5, 1),
            start_time="06:00",
            end_time="18:00",
            shift_type=ShiftType.DAY,
        )
        Attendance.objects.create(
            shift=shift,
            guard=self.guard,
            clock_in=timezone.now(),
        )

        record = record_public_holiday_duty(shift)
        approve_holiday_compensation(record, user=self.supervisor, reason="Verified holiday duty")

        balance.refresh_from_db()
        # Vacation balance must NOT be altered
        self.assertEqual(balance.vacation_days, Decimal("10.0"))

        # Compensation ledger must have 2 days earned
        earned = PublicHolidayCompensationLedger.get_total_earned_for_guard(self.guard)
        remaining = PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard)
        self.assertEqual(earned, Decimal("2.0"))
        self.assertEqual(remaining, Decimal("2.0"))

    # -------------------------------------------------------------------------
    # 6. IDEMPOTENCY & NON-MUTATING READS
    # -------------------------------------------------------------------------
    def test_repeated_accrual_processing_never_duplicates_records(self):
        """Repeatedly calling accrual engine produces zero duplicate credits."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"casual_cycle_start": date(2026, 1, 1)},
        )

        # Run 1
        created_1 = process_guard_accruals(self.guard, as_of_date=date(2026, 3, 1))
        self.assertEqual(len(created_1), 2)  # Jan, Feb

        # Run 2 with same date
        created_2 = process_guard_accruals(self.guard, as_of_date=date(2026, 3, 1))
        self.assertEqual(len(created_2), 0)

        # Run 3 with same date
        created_3 = process_guard_accruals(self.guard, as_of_date=date(2026, 3, 1))
        self.assertEqual(len(created_3), 0)

        balance.refresh_from_db()
        self.assertEqual(balance.casual_days, Decimal("2.0"))
        self.assertEqual(balance.vacation_days, Decimal("5.0"))
        self.assertEqual(LeaveAccrualRecord.objects.filter(guard=self.guard).count(), 2)

    def test_get_requests_never_mutate_balances(self):
        """HTTP GET to summary or balance endpoints must be strictly non-mutating."""
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"casual_days": Decimal("3.0"), "vacation_days": Decimal("10.0")},
        )
        self.client.force_authenticate(user=self.guard)

        # GET my-summary
        resp1 = self.client.get("/api/leave/my-summary/")
        self.assertEqual(resp1.status_code, status.HTTP_200_OK)

        # GET balances/my-summary/
        resp2 = self.client.get("/api/leave/balances/my-summary/")
        self.assertEqual(resp2.status_code, status.HTTP_200_OK)

        balance.refresh_from_db()
        self.assertEqual(balance.casual_days, Decimal("3.0"))
        self.assertEqual(balance.vacation_days, Decimal("10.0"))
        self.assertEqual(LeaveAccrualRecord.objects.filter(guard=self.guard).count(), 0)

    # -------------------------------------------------------------------------
    # 7. ROLE PERMISSION & SCOPING
    # -------------------------------------------------------------------------
    def test_supervisor_from_another_station_cannot_approve_leave(self):
        """Supervisor cannot approve leave for guards outside their assigned station."""
        app = LeaveApplication.objects.create(
            guard=self.guard,  # assigned to self.station
            leave_type=LeaveType.VACATION,
            start_date=date(2026, 7, 1),
            end_date=date(2026, 7, 3),
            reason="Family rest",
        )
        LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"vacation_days": Decimal("10.0")},
        )

        with self.assertRaises(Exception):
            approve_leave_application(app, reviewer=self.other_supervisor)  # beta station supervisor!

    def test_guard_cannot_approve_own_leave(self):
        """Guards cannot self-approve leave."""
        app = LeaveApplication.objects.create(
            guard=self.guard,
            leave_type=LeaveType.VACATION,
            start_date=date(2026, 7, 1),
            end_date=date(2026, 7, 3),
            reason="Family rest",
        )
        with self.assertRaises(Exception):
            approve_leave_application(app, reviewer=self.guard)

    # -------------------------------------------------------------------------
    # 8. ROSTER SYNCHRONIZATION
    # -------------------------------------------------------------------------
    def test_approved_leave_sets_roster_shifts_to_time_off(self):
        """Approving leave updates scheduled roster shifts to TIME_OFF."""
        shift1 = Shift.objects.create(
            station=self.station,
            guard=self.guard,
            date=date(2026, 8, 10),
            start_time="06:00",
            end_time="18:00",
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        shift2 = Shift.objects.create(
            station=self.station,
            guard=self.guard,
            date=date(2026, 8, 11),
            start_time="06:00",
            end_time="18:00",
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        LeaveBalance.objects.get_or_create(
            guard=self.guard,
            year=2026,
            defaults={"vacation_days": Decimal("10.0")},
        )

        app = LeaveApplication.objects.create(
            guard=self.guard,
            leave_type=LeaveType.VACATION,
            start_date=date(2026, 8, 10),
            end_date=date(2026, 8, 11),
            reason="Rest",
        )
        approve_leave_application(app, reviewer=self.supervisor)

        shift1.refresh_from_db()
        shift2.refresh_from_db()

        self.assertEqual(shift1.assignment_type, AssignmentType.TIME_OFF)
        self.assertEqual(shift2.assignment_type, AssignmentType.TIME_OFF)
        self.assertIn("Approved Vacation Leave", shift1.override_reason)
