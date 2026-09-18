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
    reject_holiday_compensation,
    get_active_public_holiday,
)
from apps.leave.models import LeaveBalance

UserModel = get_user_model()

class Part5APublicHolidayCompensationTests(TestCase):
    """
    Authoritative test suite for Smart Security Phase 5A:
    Public Holiday Duty & Compensation Foundation.
    Authoritative chain:
    PublicHoliday -> scheduled Shift -> Attendance -> PublicHolidayDutyRecord -> authorized compensation.
    """

    def setUp(self):
        self.client = APIClient()
        self.password = "SecPass123!"

        # Main Station
        self.station_main = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-HARARE-01",
            latitude=-17.8252,
            longitude=31.0335,
        )

        # Branch Station (for cross-station supervisor tests)
        self.station_branch = Station.objects.create(
            name="Branch Campus Security Post",
            code="STN-BULAWAYO-01",
            latitude=-20.1500,
            longitude=28.5833,
        )

        # Administrator
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

        # Operational Guards
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

        # Public Holiday: National Unity Day (22 December 2026)
        self.holiday_date = date(2026, 12, 22)
        self.holiday = PublicHoliday.objects.create(
            name="National Unity Day",
            date=self.holiday_date,
            country_code="ZW",
            description="Official Zimbabwe National Public Holiday",
            is_active=True,
        )

    # =========================================================================
    # 1. HOLIDAY WITH NO DUTY = NO CREDIT
    # =========================================================================
    def test_holiday_with_no_duty_yields_no_credit(self):
        """
        A public holiday itself does not mean a guard worked.
        A guard with no scheduled duty on that date receives no holiday duty record or compensation.
        """
        # Guard 1 has no shift on holiday_date
        records = PublicHolidayDutyRecord.objects.filter(guard=self.guard_1, public_holiday=self.holiday)
        self.assertEqual(records.count(), 0)

        # Calling record_public_holiday_duty with None raises ValidationError
        with self.assertRaises(ValidationError):
            record_public_holiday_duty(shift=None)

    # =========================================================================
    # 2. HOLIDAY + TIME_OFF = NO CREDIT
    # =========================================================================
    def test_holiday_plus_time_off_yields_no_credit(self):
        """
        A guard scheduled for TIME_OFF / OFF on a public holiday is strictly ineligible
        for public holiday duty records or compensation.
        """
        off_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(0, 0),
            end_time=time(23, 59),
            shift_type=ShiftType.OFF,
            assignment_type=AssignmentType.TIME_OFF,
            duty_location="Time Off",
            pair=self.pair1,
        )

        with self.assertRaises(ValidationError) as cm:
            record_public_holiday_duty(off_shift)
        self.assertIn("TIME_OFF/OFF shifts are not eligible", str(cm.exception))

        self.assertEqual(PublicHolidayDutyRecord.objects.filter(shift=off_shift).count(), 0)

    # =========================================================================
    # 3. HOLIDAY + SCHEDULED DUTY BUT NO ATTENDANCE = NO CREDIT
    # =========================================================================
    def test_holiday_plus_scheduled_duty_without_attendance_yields_no_credit(self):
        """
        A scheduled duty shift without valid attendance (proving the guard actually worked)
        cannot receive holiday duty recording.
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )

        # No attendance record exists yet
        with self.assertRaises(ValidationError) as cm:
            record_public_holiday_duty(duty_shift)
        self.assertIn("Valid attendance proving the guard actually worked is required", str(cm.exception))

        # Attendance exists but clock_in is None
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=None,
        )
        with self.assertRaises(ValidationError) as cm:
            record_public_holiday_duty(duty_shift, attendance=att)
        self.assertIn("Valid attendance proving the guard actually worked is required", str(cm.exception))

        self.assertEqual(PublicHolidayDutyRecord.objects.filter(shift=duty_shift).count(), 0)

    # =========================================================================
    # 4. HOLIDAY + VALID ATTENDANCE = ONE DUTY RECORD
    # =========================================================================
    def test_holiday_plus_valid_attendance_creates_one_duty_record(self):
        """
        When a scheduled duty shift on a public holiday has valid clock-in attendance,
        a PublicHolidayDutyRecord is created in PENDING status.
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
            clock_in_gps="-17.8252,31.0335",
        )

        record = record_public_holiday_duty(duty_shift, attendance=att)

        self.assertIsNotNone(record)
        self.assertEqual(record.public_holiday, self.holiday)
        self.assertEqual(record.shift, duty_shift)
        self.assertEqual(record.guard, self.guard_1)
        self.assertEqual(record.attendance, att)
        self.assertEqual(record.status, HolidayCompensationStatus.PENDING)
        self.assertEqual(record.compensated_days, Decimal("2.0"))
        self.assertIsNone(record.approved_by)
        self.assertIsNone(record.approved_at)

    # =========================================================================
    # 5. DUPLICATE PROCESSING = NO DUPLICATE CREDIT
    # =========================================================================
    def test_duplicate_processing_yields_no_duplicate_credit(self):
        """
        Processing the same worked holiday shift multiple times returns the existing
        record and prevents duplicate records or double compensation.
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
            clock_in_gps="-17.8252,31.0335",
        )

        rec1 = record_public_holiday_duty(duty_shift, attendance=att)
        rec2 = record_public_holiday_duty(duty_shift, attendance=att)

        self.assertEqual(rec1.id, rec2.id)
        self.assertEqual(PublicHolidayDutyRecord.objects.filter(shift=duty_shift).count(), 1)

    # =========================================================================
    # 6. NORMAL NON-HOLIDAY DUTY = NO HOLIDAY RECORD
    # =========================================================================
    def test_normal_non_holiday_duty_yields_no_holiday_record(self):
        """
        A shift worked on a normal, non-holiday calendar date cannot be recorded
        as public holiday duty.
        """
        non_holiday_date = date(2026, 9, 15)
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=non_holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
            clock_in_gps="-17.8252,31.0335",
        )

        with self.assertRaises(ValidationError) as cm:
            record_public_holiday_duty(duty_shift, attendance=att)
        self.assertIn("not a configured active public holiday", str(cm.exception))

        self.assertEqual(PublicHolidayDutyRecord.objects.filter(shift=duty_shift).count(), 0)

    # =========================================================================
    # 7. UNAUTHORIZED COMPENSATION APPROVAL REJECTED
    # =========================================================================
    def test_unauthorized_compensation_approval_rejected(self):
        """
        Security guards cannot grant or approve holiday compensation for themselves
        or other guards (HTTP 403 Forbidden).
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
        )
        record = record_public_holiday_duty(duty_shift, attendance=att)

        # Guard 1 tries to approve self
        with self.assertRaises(PermissionDenied):
            approve_holiday_compensation(record, self.guard_1)

        # Guard 2 tries to approve Guard 1
        with self.assertRaises(PermissionDenied):
            approve_holiday_compensation(record, self.guard_2)

        # API call as guard
        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.post(f"/shifts/holiday-duties/{record.id}/approve/", {"reason": "Self approval"})
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

        record.refresh_from_db()
        self.assertEqual(record.status, HolidayCompensationStatus.PENDING)
        self.assertIsNone(record.approved_by)

    # =========================================================================
    # 8. CROSS-STATION SUPERVISOR REJECTED
    # =========================================================================
    def test_cross_station_supervisor_rejected(self):
        """
        A supervisor assigned to another station cannot approve holiday compensation
        for a guard at a different station.
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
        )
        record = record_public_holiday_duty(duty_shift, attendance=att)

        # Supervisor Branch tries to approve
        with self.assertRaises(PermissionDenied) as cm:
            approve_holiday_compensation(record, self.supervisor_branch)
        self.assertIn("cannot approve holiday compensation for station", str(cm.exception))

        # API call as supervisor_branch
        self.client.force_authenticate(user=self.supervisor_branch)
        resp = self.client.post(f"/shifts/holiday-duties/{record.id}/approve/", {"reason": "Cross-station"})
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

        record.refresh_from_db()
        self.assertEqual(record.status, HolidayCompensationStatus.PENDING)

    # =========================================================================
    # 9. APPROVED COMPENSATION = 2 DAYS CREDITED TO VACATION LEAVE
    # =========================================================================
    def test_approved_compensation_grants_two_days(self):
        """
        When authorized supervisor approves compensation, exactly 2 days are credited
        to the guard's vacation leave balance.
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
        )
        record = record_public_holiday_duty(duty_shift, attendance=att)

        # Set initial vacation balance
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.vacation_days = Decimal("10.0")
        balance.save()

        # Approve compensation
        updated = approve_holiday_compensation(
            record,
            self.supervisor_main,
            reason="Verified active main campus duty on National Unity Day.",
        )

        self.assertEqual(updated.status, HolidayCompensationStatus.APPROVED)
        self.assertEqual(updated.approved_by, self.supervisor_main)
        self.assertIsNotNone(updated.approved_at)

        balance.refresh_from_db()
        # Phase 5C decoupled rule: public-holiday compensation NEVER modifies vacation_days
        self.assertEqual(balance.vacation_days, Decimal("10.0"))
        # Instead, it is recorded in the PublicHolidayCompensationLedger
        from apps.leave.models import PublicHolidayCompensationLedger, CompensationLedgerEntryType
        self.assertEqual(PublicHolidayCompensationLedger.get_total_earned_for_guard(self.guard_1), Decimal("2.0"))
        self.assertEqual(PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1), Decimal("2.0"))

    # =========================================================================
    # 10. 90-DAY VACATION CAP RESPECTED & INDEPENDENT COMPENSATION
    # =========================================================================
    def test_ninety_day_vacation_cap_respected(self):
        """
        Vacation remains subject to 90-day cap, while holiday compensation is decoupled
        and tracked independently in its own ledger with no vacation cap.
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
        )
        record = record_public_holiday_duty(duty_shift, attendance=att)

        # Set initial vacation balance near cap (89.5 days)
        balance, _ = LeaveBalance.objects.get_or_create(guard=self.guard_1, year=2026)
        balance.vacation_days = Decimal("89.5")
        balance.vacation_cap = Decimal("90.0")
        balance.save()

        # Approve 2 compensated days: compensation goes to independent ledger, vacation untouched
        approve_holiday_compensation(record, self.supervisor_main)

        balance.refresh_from_db()
        self.assertEqual(balance.vacation_days, Decimal("89.5"))
        from apps.leave.models import PublicHolidayCompensationLedger
        self.assertEqual(PublicHolidayCompensationLedger.get_total_earned_for_guard(self.guard_1), Decimal("2.0"))

    # =========================================================================
    # 11. AUDIT METADATA PERSISTED
    # =========================================================================
    def test_audit_metadata_persisted(self):
        """
        Verify all mandatory audit fields:
        guard, holiday, shift, attendance, who approved, approval timestamp, compensated days, status.
        Never stores passwords.
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
        )
        record = record_public_holiday_duty(duty_shift, attendance=att)

        reason_text = "Authorized operational coverage during public holiday."
        updated = approve_holiday_compensation(record, self.admin, reason=reason_text)

        updated.refresh_from_db()
        self.assertEqual(updated.guard, self.guard_1)
        self.assertEqual(updated.public_holiday, self.holiday)
        self.assertEqual(updated.shift, duty_shift)
        self.assertEqual(updated.attendance, att)
        self.assertEqual(updated.approved_by, self.admin)
        self.assertIsNotNone(updated.approved_at)
        self.assertEqual(updated.compensated_days, Decimal("2.0"))
        self.assertEqual(updated.status, HolidayCompensationStatus.APPROVED)
        self.assertEqual(updated.decision_reason, reason_text)
        self.assertNotIn("password", str(updated.__dict__))

    # =========================================================================
    # 12. API END-TO-END WORKFLOW (RECORD -> APPROVE -> REJECT)
    # =========================================================================
    def test_api_end_to_end_holiday_compensation_workflow(self):
        """
        Full API workflow:
        1. POST /shifts/holiday-duties/record_duty/
        2. POST /shifts/holiday-duties/{id}/approve/
        """
        duty_shift = Shift.objects.create(
            station=self.station_main,
            guard=self.guard_1,
            date=self.holiday_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
            pair=self.pair1,
        )
        att = Attendance.objects.create(
            shift=duty_shift,
            guard=self.guard_1,
            clock_in=timezone.now(),
        )

        self.client.force_authenticate(user=self.supervisor_main)

        # 1. Record duty
        rec_resp = self.client.post(
            "/shifts/holiday-duties/record_duty/",
            {"shift_id": str(duty_shift.id)},
            format="json",
        )
        self.assertEqual(rec_resp.status_code, status.HTTP_201_CREATED)
        record_id = rec_resp.data["id"]
        self.assertEqual(rec_resp.data["status"], HolidayCompensationStatus.PENDING)

        # 2. Approve compensation
        app_resp = self.client.post(
            f"/shifts/holiday-duties/{record_id}/approve/",
            {"reason": "Approved by supervisor via API"},
            format="json",
        )
        self.assertEqual(app_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(app_resp.data["status"], HolidayCompensationStatus.APPROVED)
        self.assertEqual(app_resp.data["approved_by_name"], self.supervisor_main.username)
