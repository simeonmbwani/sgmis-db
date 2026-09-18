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
    DutyRoster,
    RosterStatus,
    Shift,
    ShiftType,
    AssignmentType,
    Attendance,
    PublicHoliday,
    PublicHolidayDutyRecord,
    HolidayCompensationStatus,
)
from apps.shifts.services import (
    generate_roster_for_station,
    validate_duty_roster,
    approve_duty_roster,
    notify_roster_approval,
    record_public_holiday_duty,
    approve_holiday_compensation,
    reject_holiday_compensation,
)
from apps.notifications.models import Notification

UserModel = get_user_model()


class Part7BDecisionNotificationsTests(TestCase):
    """
    Authoritative test suite for Smart Security Phase 7B: Decision Notifications.
    Covers:
    A. Approved roster creates notification for affected guard.
    B. Approved roster does not notify unrelated guard.
    C. Repeating roster approval/notification logic does not create duplicate notification rows.
    D. Public holiday compensation approval creates notification for the affected guard.
    E. Public holiday compensation rejection creates notification for the affected guard.
    F. Repeating the same compensation decision does not create duplicate notifications.
    G. Supervisor cannot cause a cross-station notification.
    H. Guard cannot fabricate the notification recipient.
    I. State machine safety and transaction atomicity.
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

        # Branch Station
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

        # 6 Main Station Guards
        self.main_guards = []
        for i in range(1, 7):
            g = UserModel.objects.create_user(
                username=f"main_guard_{i}",
                email=f"guard_{i}@sgmis.local",
                password=self.password,
                employee_number=f"SEC-M10{i}",
                role=UserRole.GUARD,
                station=self.station_main,
            )
            self.main_guards.append(g)

        # 3 GuardPairs for Main Station with rotation_order 1, 2, 3
        self.pair1 = GuardPair.objects.create(
            station=self.station_main,
            guard_a=self.main_guards[0],
            guard_b=self.main_guards[1],
            rotation_order=1,
            is_active=True,
        )
        self.pair2 = GuardPair.objects.create(
            station=self.station_main,
            guard_a=self.main_guards[2],
            guard_b=self.main_guards[3],
            rotation_order=2,
            is_active=True,
        )
        self.pair3 = GuardPair.objects.create(
            station=self.station_main,
            guard_a=self.main_guards[4],
            guard_b=self.main_guards[5],
            rotation_order=3,
            is_active=True,
        )

        # 2 Branch Station Guards (unrelated to main roster)
        self.branch_guards = []
        for i in range(1, 3):
            bg = UserModel.objects.create_user(
                username=f"branch_guard_{i}",
                email=f"branch_{i}@sgmis.local",
                password=self.password,
                employee_number=f"SEC-B10{i}",
                role=UserRole.GUARD,
                station=self.station_branch,
            )
            self.branch_guards.append(bg)

        # Public Holiday setup
        self.holiday_date = date(2026, 12, 25)
        self.holiday = PublicHoliday.objects.create(
            date=self.holiday_date,
            name="Christmas Day",
            country_code="ZW",
            is_active=True,
        )

    def _create_and_validate_roster(self, start_date=date(2026, 10, 1), days=12):
        generate_roster_for_station(self.station_main, start_date, cycle_days=days)
        roster = DutyRoster.objects.get(station=self.station_main, start_date=start_date)
        validate_duty_roster(roster)
        roster.refresh_from_db()
        return roster

    def _create_holiday_duty_record(self, guard=None, station=None):
        if guard is None:
            guard = self.main_guards[0]
        if station is None:
            station = self.station_main

        shift = Shift.objects.create(
            station=station,
            guard=guard,
            date=self.holiday_date,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location=station.name,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )

        clock_in_dt = timezone.make_aware(
            timezone.datetime.combine(self.holiday_date, time(7, 0))
        )
        clock_out_dt = timezone.make_aware(
            timezone.datetime.combine(self.holiday_date, time(18, 0))
        )

        attendance = Attendance.objects.create(
            shift=shift,
            guard=guard,
            clock_in=clock_in_dt,
            clock_out=clock_out_dt,
            clock_in_gps=f"{station.latitude},{station.longitude}",
            clock_out_gps=f"{station.latitude},{station.longitude}",
        )

        return record_public_holiday_duty(shift=shift, attendance=attendance)

    # -------------------------------------------------------------------------
    # Test A: Approved roster creates notification for affected guard
    # -------------------------------------------------------------------------
    def test_a_approved_roster_creates_notification_for_affected_guard(self):
        roster = self._create_and_validate_roster()
        approve_duty_roster(roster, self.supervisor_main)

        # Check each of the 6 active pair guards assigned to shifts
        for g in self.main_guards:
            notifs = Notification.objects.filter(user=g, notification_type="ROSTER_APPROVED")
            self.assertEqual(
                notifs.count(),
                1,
                f"Expected guard {g.username} to receive exactly 1 ROSTER_APPROVED notification.",
            )
            n = notifs.first()
            self.assertEqual(n.dedup_key, f"ROSTER_APPROVAL:{roster.id}:{g.id}")
            self.assertIn("Main Campus Security Post", n.title)
            self.assertIn(str(roster.start_date), n.message)
            self.assertIn(str(roster.end_date), n.message)
            self.assertIn("approved", n.message.lower())
            self.assertIn("active", n.message.lower())

    # -------------------------------------------------------------------------
    # Test B: Approved roster does not notify unrelated guard
    # -------------------------------------------------------------------------
    def test_b_approved_roster_does_not_notify_unrelated_guard(self):
        roster = self._create_and_validate_roster()
        approve_duty_roster(roster, self.supervisor_main)

        # Branch station guards (unrelated to main roster) must receive 0 notifications
        for bg in self.branch_guards:
            count = Notification.objects.filter(user=bg).count()
            self.assertEqual(count, 0, f"Unrelated guard {bg.username} should have received 0 notifications.")

    # -------------------------------------------------------------------------
    # Test C: Repeating roster approval/notification logic does not duplicate
    # -------------------------------------------------------------------------
    def test_c_repeating_roster_approval_does_not_create_duplicate_notifications(self):
        roster = self._create_and_validate_roster()
        approve_duty_roster(roster, self.supervisor_main)

        guard = self.main_guards[0]
        initial_count = Notification.objects.filter(user=guard, notification_type="ROSTER_APPROVED").count()
        self.assertEqual(initial_count, 1)

        # Call notification helper again directly to simulate repeat/retry
        notify_roster_approval(roster, self.supervisor_main)

        final_count = Notification.objects.filter(user=guard, notification_type="ROSTER_APPROVED").count()
        self.assertEqual(
            final_count,
            1,
            "Repeating notification logic for an approved roster must not create duplicate notifications.",
        )

    # -------------------------------------------------------------------------
    # Test D: Public holiday compensation approval creates notification for guard
    # -------------------------------------------------------------------------
    def test_d_holiday_compensation_approval_creates_notification_for_affected_guard(self):
        record = self._create_holiday_duty_record(guard=self.main_guards[0])
        approved_record = approve_holiday_compensation(
            record,
            self.supervisor_main,
            reason="Verified active Christmas attendance",
        )

        notifs = Notification.objects.filter(
            user=self.main_guards[0],
            notification_type="HOLIDAY_COMPENSATION",
        )
        self.assertEqual(notifs.count(), 1)
        n = notifs.first()
        self.assertEqual(n.dedup_key, f"HOLIDAY_COMPENSATION:{record.id}:APPROVED")
        self.assertIn("Approved", n.title)
        self.assertIn("APPROVED", n.message)
        self.assertIn("Christmas Day", n.message)
        self.assertIn("2.0 days", n.message)
        self.assertNotIn("vacation", n.message.lower(), "Must not describe compensation as vacation leave.")

    # -------------------------------------------------------------------------
    # Test E: Public holiday compensation rejection creates notification for guard
    # -------------------------------------------------------------------------
    def test_e_holiday_compensation_rejection_creates_notification_for_affected_guard(self):
        record = self._create_holiday_duty_record(guard=self.main_guards[1])
        reject_holiday_compensation(
            record,
            self.supervisor_main,
            reason="Unverified gate attendance log",
        )

        notifs = Notification.objects.filter(
            user=self.main_guards[1],
            notification_type="HOLIDAY_COMPENSATION",
        )
        self.assertEqual(notifs.count(), 1)
        n = notifs.first()
        self.assertEqual(n.dedup_key, f"HOLIDAY_COMPENSATION:{record.id}:REJECTED")
        self.assertIn("Rejected", n.title)
        self.assertIn("REJECTED", n.message)
        self.assertIn("Christmas Day", n.message)
        self.assertIn("Unverified gate attendance log", n.message)

    # -------------------------------------------------------------------------
    # Test F: Repeating same compensation decision does not duplicate notifications
    # -------------------------------------------------------------------------
    def test_f_repeating_holiday_compensation_does_not_duplicate_notifications(self):
        record = self._create_holiday_duty_record(guard=self.main_guards[2])
        approve_holiday_compensation(record, self.supervisor_main, reason="Initial approval")

        count_after_first = Notification.objects.filter(
            user=self.main_guards[2],
            notification_type="HOLIDAY_COMPENSATION",
        ).count()
        self.assertEqual(count_after_first, 1)

        # Re-trigger approval service helper / retry with dedup key check
        Notification.objects.get_or_create(
            dedup_key=f"HOLIDAY_COMPENSATION:{record.id}:APPROVED",
            defaults={
                "user": self.main_guards[2],
                "title": "Public Holiday Compensation Approved",
                "message": "Duplicate attempt",
                "notification_type": "HOLIDAY_COMPENSATION",
            },
        )

        count_after_repeat = Notification.objects.filter(
            user=self.main_guards[2],
            notification_type="HOLIDAY_COMPENSATION",
        ).count()
        self.assertEqual(count_after_repeat, 1)

    # -------------------------------------------------------------------------
    # Test G: Supervisor cannot cause a cross-station notification
    # -------------------------------------------------------------------------
    def test_g_supervisor_cannot_cause_cross_station_notification(self):
        roster = self._create_and_validate_roster()

        # Branch supervisor tries to approve main station roster
        with self.assertRaises((PermissionDenied, Exception)):
            approve_duty_roster(roster, self.supervisor_branch)

        # Verify no notifications were created for any main guard
        for g in self.main_guards:
            self.assertEqual(Notification.objects.filter(user=g).count(), 0)

        # Branch supervisor tries to approve main station holiday duty
        record = self._create_holiday_duty_record(guard=self.main_guards[0])
        with self.assertRaises((PermissionDenied, Exception)):
            approve_holiday_compensation(record, self.supervisor_branch)

        self.assertEqual(Notification.objects.filter(user=self.main_guards[0]).count(), 0)

    # -------------------------------------------------------------------------
    # Test H: Guard cannot fabricate the notification recipient
    # -------------------------------------------------------------------------
    def test_h_guard_cannot_fabricate_notification_recipient(self):
        roster = self._create_and_validate_roster()
        guard = self.main_guards[0]

        # Guard tries to approve roster
        with self.assertRaises((PermissionDenied, Exception)):
            approve_duty_roster(roster, guard)

        # Guard tries to approve holiday compensation
        record = self._create_holiday_duty_record(guard=self.main_guards[1])
        with self.assertRaises((PermissionDenied, Exception)):
            approve_holiday_compensation(record, guard)

        # No notifications generated
        self.assertEqual(Notification.objects.filter(notification_type="ROSTER_APPROVED").count(), 0)
        self.assertEqual(Notification.objects.filter(notification_type="HOLIDAY_COMPENSATION").count(), 0)

    # -------------------------------------------------------------------------
    # Test I: State machine safety & transaction atomicity
    # -------------------------------------------------------------------------
    def test_i_state_machine_safety_and_atomicity(self):
        record = self._create_holiday_duty_record(guard=self.main_guards[3])
        approve_holiday_compensation(record, self.supervisor_main)

        # Attempting to reject an already approved record must fail
        with self.assertRaises(ValidationError):
            reject_holiday_compensation(record, self.supervisor_main)

        # Must not have any REJECTED notification
        self.assertFalse(
            Notification.objects.filter(
                user=self.main_guards[3],
                dedup_key=f"HOLIDAY_COMPENSATION:{record.id}:REJECTED",
            ).exists()
        )

        record2 = self._create_holiday_duty_record(guard=self.main_guards[4])
        reject_holiday_compensation(record2, self.supervisor_main, reason="No punch")

        # Attempting to approve an already rejected record must fail
        with self.assertRaises(ValidationError):
            approve_holiday_compensation(record2, self.supervisor_main)

        # Must not have any APPROVED notification
        self.assertFalse(
            Notification.objects.filter(
                user=self.main_guards[4],
                dedup_key=f"HOLIDAY_COMPENSATION:{record2.id}:APPROVED",
            ).exists()
        )
