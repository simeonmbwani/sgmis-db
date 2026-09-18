from datetime import date, time, timedelta
from decimal import Decimal
from django.test import TestCase
from django.utils import timezone
from django.contrib.auth import get_user_model
from rest_framework.test import APIClient
from rest_framework import status
from rest_framework.exceptions import ValidationError, PermissionDenied

from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
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
    PublicHolidayCompensationLedger,
    CompensationLedgerEntryType,
)
from apps.leave.constants import (
    VACATION_ACCRUAL_RATE,
    CASUAL_ACCRUAL_RATE,
    MAX_VACATION_DAYS,
    PUBLIC_HOLIDAY_COMPENSATION_DAYS,
)

UserModel = get_user_model()


class Part5CLeaveAccountingTests(TestCase):
    """
    Authoritative test suite for Smart Security Phase 5C:
    Authoritative Leave & Public-Holiday Compensation Accounting.
    Covers the 3 independent streams:
    1. Vacation Leave: Accrued (2.5/mo), Used, Remaining (capped at 90.0).
    2. Casual Leave: Accrued (1.0/mo), Used, Remaining (12-month cycle).
    3. Public Holiday Compensation: Earned, Used, Remaining (2 days/duty, NO cap).
    """

    def setUp(self):
        self.client = APIClient()
        self.password = "SecPass123!"

        # Stations
        self.station_main = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-HARARE-01",
            latitude=-17.8252,
            longitude=31.0335,
        )
        self.station_branch = Station.objects.create(
            name="Branch Security Post",
            code="STN-BULAWAYO-01",
            latitude=-20.1500,
            longitude=28.5833,
        )

        # Admin
        self.admin = UserModel.objects.create_user(
            username="admin_user",
            email="admin@sgmis.local",
            password=self.password,
            employee_number="ADM-001",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
            station=self.station_main,
        )

        # Supervisors
        self.supervisor_main = UserModel.objects.create_user(
            username="sup_main",
            email="sup_main@sgmis.local",
            password=self.password,
            employee_number="SUP-001",
            role=UserRole.SUPERVISOR,
            station=self.station_main,
        )
        self.supervisor_branch = UserModel.objects.create_user(
            username="sup_branch",
            email="sup_branch@sgmis.local",
            password=self.password,
            employee_number="SUP-002",
            role=UserRole.SUPERVISOR,
            station=self.station_branch,
        )

        # Guards
        self.guard_1 = UserModel.objects.create_user(
            username="guard_one",
            email="guard_one@sgmis.local",
            password=self.password,
            employee_number="SEC-501",
            role=UserRole.GUARD,
            station=self.station_main,
        )
        self.guard_2 = UserModel.objects.create_user(
            username="guard_two",
            email="guard_two@sgmis.local",
            password=self.password,
            employee_number="SEC-502",
            role=UserRole.GUARD,
            station=self.station_main,
        )

        # Pair
        self.pair1 = GuardPair.objects.create(
            station=self.station_main,
            guard_a=self.guard_1,
            guard_b=self.guard_2,
            rotation_order=1,
            is_active=True,
        )

    def _create_and_approve_holiday_duty(self, guard, holiday_date, holiday_name="Public Holiday"):
        holiday, _ = PublicHoliday.objects.get_or_create(
            date=holiday_date,
            country_code="ZW",
            defaults={"name": holiday_name, "is_active": True},
        )
        shift = Shift.objects.create(
            station=self.station_main,
            guard=guard,
            date=holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=shift,
            guard=guard,
            clock_in=timezone.now(),
        )
        record = record_public_holiday_duty(shift, attendance=att)
        return approve_holiday_compensation(record, self.supervisor_main)

    # -------------------------------------------------------------------------
    # 1. ONE WORKED PUBLIC HOLIDAY = 2 EARNED
    # -------------------------------------------------------------------------
    def test_one_worked_public_holiday_equals_two_earned(self):
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1), "New Year's Day")
        earned = PublicHolidayCompensationLedger.get_total_earned_for_guard(self.guard_1)
        remaining = PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1)
        self.assertEqual(earned, Decimal("2.0"))
        self.assertEqual(remaining, Decimal("2.0"))

    # -------------------------------------------------------------------------
    # 2. SIX WORKED HOLIDAYS = 12 EARNED
    # -------------------------------------------------------------------------
    def test_six_worked_holidays_equals_twelve_earned(self):
        dates = [
            date(2026, 1, 1),
            date(2026, 2, 21),
            date(2026, 4, 18),
            date(2026, 5, 1),
            date(2026, 5, 25),
            date(2026, 8, 10),
        ]
        for d in dates:
            self._create_and_approve_holiday_duty(self.guard_1, d, f"Holiday on {d}")

        earned = PublicHolidayCompensationLedger.get_total_earned_for_guard(self.guard_1)
        self.assertEqual(earned, Decimal("12.0"))
        self.assertEqual(PublicHolidayCompensationLedger.objects.filter(guard=self.guard_1, entry_type=CompensationLedgerEntryType.EARNED).count(), 6)

    # -------------------------------------------------------------------------
    # 3. 12 EARNED - 4 USED = 8 REMAINING
    # -------------------------------------------------------------------------
    def test_twelve_earned_minus_four_used_equals_eight_remaining(self):
        dates = [
            date(2026, 1, 1),
            date(2026, 2, 21),
            date(2026, 4, 18),
            date(2026, 5, 1),
            date(2026, 5, 25),
            date(2026, 8, 10),
        ]
        for d in dates:
            self._create_and_approve_holiday_duty(self.guard_1, d, f"Holiday on {d}")

        # Guard requests 4 days of compensation leave
        app = LeaveApplication.objects.create(
            guard=self.guard_1,
            leave_type=LeaveType.COMPENSATION,
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 4),  # 4 days
            reason="Taking earned public holiday comp time.",
        )

        # Supervisor reviews and approves
        self.client.force_authenticate(user=self.supervisor_main)
        resp = self.client.post(f"/leave/applications/{app.id}/review/", {"status": "APPROVED", "reviewer_notes": "Approved."})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        earned = PublicHolidayCompensationLedger.get_total_earned_for_guard(self.guard_1)
        used = PublicHolidayCompensationLedger.get_total_used_for_guard(self.guard_1)
        remaining = PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1)

        self.assertEqual(earned, Decimal("12.0"))
        self.assertEqual(used, Decimal("4.0"))
        self.assertEqual(remaining, Decimal("8.0"))

    # -------------------------------------------------------------------------
    # 4. HOLIDAY COMPENSATION DOES NOT ALTER VACATION BALANCE
    # -------------------------------------------------------------------------
    def test_holiday_compensation_does_not_alter_vacation_balance(self):
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.vacation_days = Decimal("25.0")
        balance.used_vacation = Decimal("5.0")
        balance.save()

        # Approve holiday compensation
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1), "New Year")

        balance.refresh_from_db()
        self.assertEqual(balance.vacation_days, Decimal("25.0"))
        self.assertEqual(balance.used_vacation, Decimal("5.0"))
        self.assertEqual(balance.remaining_vacation, 20.0)

    # -------------------------------------------------------------------------
    # 5. HOLIDAY COMPENSATION DOES NOT ALTER CASUAL BALANCE
    # -------------------------------------------------------------------------
    def test_holiday_compensation_does_not_alter_casual_balance(self):
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.casual_days = Decimal("8.0")
        balance.used_casual = Decimal("2.0")
        balance.save()

        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1), "New Year")

        balance.refresh_from_db()
        self.assertEqual(balance.casual_days, Decimal("8.0"))
        self.assertEqual(balance.used_casual, Decimal("2.0"))
        self.assertEqual(balance.remaining_casual, 6.0)

    # -------------------------------------------------------------------------
    # 6. VACATION USAGE DOES NOT ALTER COMPENSATION BALANCE
    # -------------------------------------------------------------------------
    def test_vacation_usage_does_not_alter_compensation_balance(self):
        # Guard has 4 compensation days earned
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1))
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 2, 21))
        self.assertEqual(PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1), Decimal("4.0"))

        # Setup vacation balance
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.vacation_days = Decimal("30.0")
        balance.save()

        # Guard takes 5 days VACATION leave
        app = LeaveApplication.objects.create(
            guard=self.guard_1,
            leave_type=LeaveType.VACATION,
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 5),  # 5 days
            reason="Annual holiday trip.",
        )
        self.client.force_authenticate(user=self.supervisor_main)
        resp = self.client.post(f"/leave/applications/{app.id}/review/", {"status": "APPROVED"})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        balance.refresh_from_db()
        self.assertEqual(balance.used_vacation, Decimal("5.0"))
        # Compensation stream is completely unaffected
        self.assertEqual(PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1), Decimal("4.0"))

    # -------------------------------------------------------------------------
    # 7. CASUAL USAGE DOES NOT ALTER COMPENSATION BALANCE
    # -------------------------------------------------------------------------
    def test_casual_usage_does_not_alter_compensation_balance(self):
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1))
        self.assertEqual(PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1), Decimal("2.0"))

        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.casual_days = Decimal("6.0")
        balance.save()

        app = LeaveApplication.objects.create(
            guard=self.guard_1,
            leave_type=LeaveType.CASUAL,
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 2),  # 2 days
            reason="Personal urgent matters.",
        )
        self.client.force_authenticate(user=self.supervisor_main)
        resp = self.client.post(f"/leave/applications/{app.id}/review/", {"status": "APPROVED"})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        balance.refresh_from_db()
        self.assertEqual(balance.used_casual, Decimal("2.0"))
        self.assertEqual(PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1), Decimal("2.0"))

    # -------------------------------------------------------------------------
    # 8. COMPENSATION CAN EXCEED 90 DAYS (NO 90-DAY CAP)
    # -------------------------------------------------------------------------
    def test_compensation_can_exceed_ninety_days(self):
        # Simulate guard having worked 46 public holidays over multiple years/contracts = 92 days
        for i in range(46):
            d = date(2026, 1, 1) + timedelta(days=i * 5)
            h = PublicHoliday.objects.create(name=f"Hol-{i}", date=d, country_code="ZW")
            s = Shift.objects.create(
                station=self.station_main,
                guard=self.guard_1,
                date=d,
                start_time=time(7, 0),
                end_time=time(18, 0),
                shift_type=ShiftType.DAY,
                assignment_type=AssignmentType.NORMAL,
                duty_location="Main Campus",
                pair=self.pair1,
            )
            att = Attendance.objects.create(shift=s, guard=self.guard_1, clock_in=timezone.now())
            rec = record_public_holiday_duty(s, attendance=att)
            approve_holiday_compensation(rec, self.supervisor_main)

        total_earned = PublicHolidayCompensationLedger.get_total_earned_for_guard(self.guard_1)
        # 46 * 2 = 92.0 days -> strictly exceeds 90 with no capping!
        self.assertEqual(total_earned, Decimal("92.0"))
        self.assertEqual(PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1), Decimal("92.0"))

    # -------------------------------------------------------------------------
    # 9. VACATION REMAINS CAPPED AT 90 DAYS
    # -------------------------------------------------------------------------
    def test_vacation_remains_capped_at_ninety_days(self):
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.vacation_days = Decimal("89.0")
        balance.save()

        # Monthly accrual from Jan 1 to Jul 1 (6 months @ 2.5 = 15 days -> 89 + 15 = 104 -> capped at 90.0)
        balance.accrue_to_date(as_of=date(2026, 7, 1))
        self.assertEqual(balance.vacation_days, Decimal("90.0"))

    # -------------------------------------------------------------------------
    # 10. DUPLICATE HOLIDAY DUTY CANNOT CREATE DUPLICATE EARNED ENTRY
    # -------------------------------------------------------------------------
    def test_duplicate_holiday_duty_cannot_create_duplicate_earned_entry(self):
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1), "New Year")
        record = PublicHolidayDutyRecord.objects.get(shift__date=date(2026, 1, 1), guard=self.guard_1)

        # Attempt to create duplicate EARNED entry directly or via re-approval
        with self.assertRaises(ValidationError):
            approve_holiday_compensation(record, self.supervisor_main)

        earned_entries = PublicHolidayCompensationLedger.objects.filter(
            duty_record=record,
            entry_type=CompensationLedgerEntryType.EARNED,
        )
        self.assertEqual(earned_entries.count(), 1)

    # -------------------------------------------------------------------------
    # 11. CANNOT USE MORE COMPENSATION THAN REMAINING
    # -------------------------------------------------------------------------
    def test_cannot_use_more_compensation_than_remaining(self):
        # Guard has 2 days compensation earned
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1))

        # Guard requests 3 days (exceeds remaining balance of 2)
        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.post("/leave/applications/", {
            "leave_type": "COMPENSATION",
            "start_date": "2026-09-01",
            "end_date": "2026-09-03",  # 3 days
            "reason": "Excess usage attempt",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("Insufficient public holiday compensation balance", str(resp.data))

    # -------------------------------------------------------------------------
    # 12. APPROVED USAGE CREATES USED ENTRY
    # -------------------------------------------------------------------------
    def test_approved_usage_creates_used_entry(self):
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1))
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 2, 21))

        app = LeaveApplication.objects.create(
            guard=self.guard_1,
            leave_type=LeaveType.COMPENSATION,
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 2),  # 2 days
            reason="Approved comp time.",
        )
        self.client.force_authenticate(user=self.supervisor_main)
        resp = self.client.post(f"/leave/applications/{app.id}/review/", {"status": "APPROVED"})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        used_entries = PublicHolidayCompensationLedger.objects.filter(
            leave_application=app,
            entry_type=CompensationLedgerEntryType.USED,
        )
        self.assertEqual(used_entries.count(), 1)
        self.assertEqual(used_entries.first().days, Decimal("2.0"))

    # -------------------------------------------------------------------------
    # 13. REJECTED USAGE CREATES NO USED ENTRY
    # -------------------------------------------------------------------------
    def test_rejected_usage_creates_no_used_entry(self):
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1))

        app = LeaveApplication.objects.create(
            guard=self.guard_1,
            leave_type=LeaveType.COMPENSATION,
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 2),
            reason="Comp time request to be rejected.",
        )
        self.client.force_authenticate(user=self.supervisor_main)
        resp = self.client.post(f"/leave/applications/{app.id}/review/", {
            "status": "REJECTED",
            "rejection_reason": "MANPOWER_SHORTAGE",
            "reviewer_notes": "Critical shift coverage required.",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        used_entries = PublicHolidayCompensationLedger.objects.filter(
            leave_application=app,
            entry_type=CompensationLedgerEntryType.USED,
        )
        self.assertEqual(used_entries.count(), 0)
        self.assertEqual(PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1), Decimal("2.0"))

    # -------------------------------------------------------------------------
    # 14. GUARD SUMMARY RETURNS AUTHENTICATED GUARD'S OWN DATA
    # -------------------------------------------------------------------------
    def test_guard_summary_returns_authenticated_guard_own_data(self):
        # Guard 1 has 1 holiday worked = 2 days
        self._create_and_approve_holiday_duty(self.guard_1, date(2026, 1, 1))
        # Guard 2 has 2 holidays worked = 4 days
        self._create_and_approve_holiday_duty(self.guard_2, date(2026, 1, 1))
        self._create_and_approve_holiday_duty(self.guard_2, date(2026, 2, 21))

        # Query as Guard 1
        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.get("/leave/my-summary/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        data = resp.data

        self.assertEqual(data["guard_id"], str(self.guard_1.id))
        self.assertEqual(data["compensation"]["earned"], 2.0)
        self.assertEqual(data["compensation"]["remaining"], 2.0)

        # Verify 3 rows in categories table
        cat_names = [c["category"] for c in data["categories"]]
        self.assertIn("Vacation Leave", cat_names)
        self.assertIn("Casual Leave", cat_names)
        self.assertIn("Public Holiday Compensation", cat_names)

    # -------------------------------------------------------------------------
    # 15. ANOTHER GUARD'S ID CANNOT BE USED TO RETRIEVE SOMEBODY ELSE'S SUMMARY
    # -------------------------------------------------------------------------
    def test_guard_cannot_request_another_guard_summary(self):
        self.client.force_authenticate(user=self.guard_1)
        # Attempt to pass Guard 2's ID
        resp = self.client.get(f"/leave/my-summary/?guard_id={self.guard_2.id}")
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("Guards cannot request another guard's summary", str(resp.data))

    # -------------------------------------------------------------------------
    # 16. GUARD CANNOT MODIFY THE SUMMARY
    # -------------------------------------------------------------------------
    def test_guard_cannot_modify_summary(self):
        self.client.force_authenticate(user=self.guard_1)
        # POST or PUT or PATCH to /leave/my-summary/ is not allowed
        resp_post = self.client.post("/leave/my-summary/", {"remaining": 50})
        self.assertEqual(resp_post.status_code, status.HTTP_405_METHOD_NOT_ALLOWED)

        resp_put = self.client.put("/leave/my-summary/", {"remaining": 50})
        self.assertEqual(resp_put.status_code, status.HTTP_405_METHOD_NOT_ALLOWED)

    # -------------------------------------------------------------------------
    # 17. CLIENT CANNOT SUBMIT ARBITRARY BALANCE VALUES
    # -------------------------------------------------------------------------
    def test_client_cannot_submit_arbitrary_balance_values(self):
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        self.client.force_authenticate(user=self.guard_1)

        # Attempt to PUT/PATCH balance directly
        resp = self.client.patch(f"/leave/balances/{balance.id}/", {"vacation_days": 100, "remaining_vacation": 100})
        # LeaveBalanceViewSet is ReadOnlyModelViewSet -> 405 Method Not Allowed
        self.assertEqual(resp.status_code, status.HTTP_405_METHOD_NOT_ALLOWED)

    # -------------------------------------------------------------------------
    # 18. NO HARDCODED INDIVIDUAL GUARD BALANCES
    # -------------------------------------------------------------------------
    def test_no_hardcoded_individual_guard_balances(self):
        """
        Verify that a newly created guard has 0.0 earned compensation and balances
        derived authoritatively from policy accrual rules rather than hardcoded dummy numbers.
        """
        new_guard = UserModel.objects.create_user(
            username="guard_new",
            password=self.password,
            role=UserRole.GUARD,
            station=self.station_main,
        )
        self.client.force_authenticate(user=new_guard)
        resp = self.client.get("/leave/my-summary/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["compensation"]["earned"], 0.0)
        self.assertEqual(resp.data["compensation"]["used"], 0.0)
        self.assertEqual(resp.data["compensation"]["remaining"], 0.0)

    # -------------------------------------------------------------------------
    # 19. VACATION ACCRUAL CALCULATED FROM AUTHORITATIVE BACKEND RULE (2.5/mo)
    # -------------------------------------------------------------------------
    def test_vacation_accrual_calculated_from_authoritative_backend_rule(self):
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.vacation_days = Decimal("0.0")
        balance.last_accrual_date = date(2026, 1, 1)
        balance.save()

        # Accrue 4 months to May 1: 4 * 2.5 = 10.0 days
        balance.accrue_to_date(as_of=date(2026, 5, 1))
        self.assertEqual(balance.vacation_days, Decimal("10.0"))

    # -------------------------------------------------------------------------
    # 20. CASUAL ACCRUAL CALCULATED FROM AUTHORITATIVE BACKEND RULE (1.0/mo)
    # -------------------------------------------------------------------------
    def test_casual_accrual_calculated_from_authoritative_backend_rule(self):
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.casual_days = Decimal("0.0")
        balance.last_accrual_date = date(2026, 1, 1)
        balance.casual_cycle_start = date(2026, 1, 1)
        balance.save()

        # Accrue 7 months to Aug 1: 7 * 1.0 = 7.0 days
        balance.accrue_to_date(as_of=date(2026, 8, 1))
        self.assertEqual(balance.casual_days, Decimal("7.0"))

