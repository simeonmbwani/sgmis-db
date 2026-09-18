import io
import zoneinfo
from datetime import date, time, datetime, timedelta
from django.test import TestCase
from django.utils import timezone
from django.contrib.auth import get_user_model
from django.core.management import call_command

from apps.accounts.models import UserRole
from apps.stations.models import Station
from apps.shifts.models import (
    DutyRoster,
    RosterStatus,
    Shift,
    ShiftType,
    AssignmentType,
)
from apps.notifications.models import Notification

UserModel = get_user_model()
HARARE_TZ = zoneinfo.ZoneInfo("Africa/Harare")


class Part7CUpcomingDutyNotificationsTests(TestCase):
    """
    Authoritative test suite for Smart Security Phase 7C: Upcoming Duty Notifications.
    Covers:
    A. Upcoming normal DAY shift produces one notification.
    B. Upcoming NIGHT shift produces one notification and correctly describes overnight end time.
    C. Running command twice produces only one notification (idempotency).
    D. TIME_OFF does not generate an upcoming-duty notification.
    E. OFF does not generate an upcoming-duty notification.
    F. DRAFT roster does not generate a notification.
    G. VALIDATED roster does not generate a notification.
    H. APPROVED / ACTIVE roster does generate a notification.
    I. Inactive guard does not receive a notification.
    J. Different shifts generate different notifications (distinct dedup keys).
    K. Africa/Harare timezone is strictly used.
    L. A duty outside the reminder window (< 48h or > 84h) is not notified.
    M. Specialized ESCORT duty (06:00-17:00) is included and notified.
    """

    def setUp(self):
        self.station = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-HARARE-01",
            latitude=-17.8252,
            longitude=31.0335,
        )

        self.supervisor = UserModel.objects.create_user(
            username="sup_harare",
            email="sup@sgmis.local",
            password="SecPass123!",
            employee_number="SUP-701",
            role=UserRole.SUPERVISOR,
            station=self.station,
        )

        self.guard1 = UserModel.objects.create_user(
            username="guard_alpha",
            email="guard_a@sgmis.local",
            password="SecPass123!",
            employee_number="SEC-701",
            role=UserRole.GUARD,
            station=self.station,
            is_active=True,
        )

        self.guard2 = UserModel.objects.create_user(
            username="guard_bravo",
            email="guard_b@sgmis.local",
            password="SecPass123!",
            employee_number="SEC-702",
            role=UserRole.GUARD,
            station=self.station,
            is_active=True,
        )

        self.inactive_guard = UserModel.objects.create_user(
            username="guard_inactive",
            email="inactive@sgmis.local",
            password="SecPass123!",
            employee_number="SEC-799",
            role=UserRole.GUARD,
            station=self.station,
            is_active=False,
        )

        # Baseline execution reference time: 2026-09-21 at 06:00:00 in Africa/Harare
        self.ref_now_str = "2026-09-21T06:00:00"

        # Authoritative Approved Roster covering September 2026
        self.roster_approved = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 30),
            status=RosterStatus.APPROVED,
            approved_by=self.supervisor,
            approved_at=timezone.now(),
        )

        # Draft Roster
        self.roster_draft = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.DRAFT,
        )

        # Validated Roster
        self.roster_validated = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 11, 1),
            end_date=date(2026, 11, 30),
            status=RosterStatus.VALIDATED,
        )

    def _call_command(self, now_str=None, min_hours=48.0, max_hours=84.0):
        out = io.StringIO()
        err = io.StringIO()
        call_command(
            "send_upcoming_duty_notifications",
            now=now_str or self.ref_now_str,
            min_hours=min_hours,
            max_hours=max_hours,
            stdout=out,
            stderr=err,
        )
        return out.getvalue(), err.getvalue()

    # -------------------------------------------------------------------------
    # Test A: Upcoming normal DAY shift produces one notification
    # -------------------------------------------------------------------------
    def test_a_upcoming_normal_day_shift_produces_notification(self):
        # 24 September 2026 at 07:00 is exactly 73.0 hours from 21 September 06:00
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 24),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus Post 1",
            roster=self.roster_approved,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 1", out)

        notif = Notification.objects.filter(user=self.guard1, notification_type="UPCOMING_DUTY").first()
        self.assertIsNotNone(notif)
        self.assertEqual(notif.dedup_key, f"UPCOMING_DUTY:{shift.id}:{self.guard1.id}")
        self.assertIn("07:00 to 18:00", notif.message)
        self.assertIn("24 September 2026", notif.message)
        self.assertIn("Main Campus Post 1", notif.message)
        self.assertNotIn("next day", notif.message)

    # -------------------------------------------------------------------------
    # Test B: Upcoming NIGHT shift produces notification with overnight end time
    # -------------------------------------------------------------------------
    def test_b_upcoming_night_shift_correctly_handles_overnight(self):
        # 23 September 2026 at 18:00 is 60.0 hours from 21 September 06:00
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 23),
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus Perimeter",
            roster=self.roster_approved,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 1", out)

        notif = Notification.objects.filter(user=self.guard1, notification_type="UPCOMING_DUTY").first()
        self.assertIsNotNone(notif)
        self.assertEqual(notif.dedup_key, f"UPCOMING_DUTY:{shift.id}:{self.guard1.id}")
        self.assertIn("18:00 to 07:00 next day", notif.message)
        self.assertIn("23 September 2026", notif.message)
        self.assertIn("Main Campus Perimeter", notif.message)

    # -------------------------------------------------------------------------
    # Test C: Running command twice produces only one notification
    # -------------------------------------------------------------------------
    def test_c_running_command_twice_produces_only_one_notification(self):
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 24),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        out1, _ = self._call_command()
        self.assertIn("Notifications created: 1", out1)
        self.assertEqual(Notification.objects.filter(user=self.guard1).count(), 1)

        # Second run
        out2, _ = self._call_command()
        self.assertIn("Notifications created: 0", out2)
        self.assertIn("Already notified: 1", out2)
        self.assertEqual(
            Notification.objects.filter(user=self.guard1).count(),
            1,
            "Idempotent command must not duplicate notification rows.",
        )

    # -------------------------------------------------------------------------
    # Test D: TIME_OFF does not generate notification
    # -------------------------------------------------------------------------
    def test_d_time_off_does_not_generate_notification(self):
        Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 24),
            start_time=time(0, 0),
            end_time=time(0, 0),
            shift_type=ShiftType.OFF,
            assignment_type=AssignmentType.TIME_OFF,
            roster=self.roster_approved,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 0", out)
        self.assertEqual(Notification.objects.count(), 0)

    # -------------------------------------------------------------------------
    # Test E: OFF shift type does not generate notification
    # -------------------------------------------------------------------------
    def test_e_off_shift_type_does_not_generate_notification(self):
        Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 24),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.OFF,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 0", out)
        self.assertEqual(Notification.objects.count(), 0)

    # -------------------------------------------------------------------------
    # Test F: DRAFT roster does not generate notification
    # -------------------------------------------------------------------------
    def test_f_draft_roster_does_not_generate_notification(self):
        Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 10, 4),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_draft,
        )

        out, _ = self._call_command(now_str="2026-10-01T06:00:00")
        self.assertIn("Notifications created: 0", out)
        self.assertEqual(Notification.objects.count(), 0)

    # -------------------------------------------------------------------------
    # Test G: VALIDATED roster does not generate notification
    # -------------------------------------------------------------------------
    def test_g_validated_roster_does_not_generate_notification(self):
        Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 11, 4),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_validated,
        )

        out, _ = self._call_command(now_str="2026-11-01T06:00:00")
        self.assertIn("Notifications created: 0", out)
        self.assertEqual(Notification.objects.count(), 0)

    # -------------------------------------------------------------------------
    # Test H: APPROVED and ACTIVE rosters do generate notification
    # -------------------------------------------------------------------------
    def test_h_approved_and_active_rosters_generate_notification(self):
        # Shift 1 under APPROVED roster
        Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 24),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        # Roster ACTIVE
        roster_active = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 30),
            status=RosterStatus.ACTIVE,
            approved_by=self.supervisor,
            approved_at=timezone.now(),
        )

        # Shift 2 under ACTIVE roster
        Shift.objects.create(
            station=self.station,
            guard=self.guard2,
            date=date(2026, 9, 24),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=roster_active,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 2", out)
        self.assertEqual(Notification.objects.filter(notification_type="UPCOMING_DUTY").count(), 2)

    # -------------------------------------------------------------------------
    # Test I: Inactive guard does not receive notification
    # -------------------------------------------------------------------------
    def test_i_inactive_guard_does_not_receive_notification(self):
        Shift.objects.create(
            station=self.station,
            guard=self.inactive_guard,
            date=date(2026, 9, 24),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 0", out)
        self.assertEqual(Notification.objects.filter(user=self.inactive_guard).count(), 0)

    # -------------------------------------------------------------------------
    # Test J: Different shifts generate different notifications
    # -------------------------------------------------------------------------
    def test_j_different_shifts_generate_distinct_notifications(self):
        s1 = Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 23),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        s2 = Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 24),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 2", out)

        n1 = Notification.objects.get(dedup_key=f"UPCOMING_DUTY:{s1.id}:{self.guard1.id}")
        n2 = Notification.objects.get(dedup_key=f"UPCOMING_DUTY:{s2.id}:{self.guard1.id}")
        self.assertNotEqual(n1.id, n2.id)
        self.assertIn("23 September 2026", n1.message)
        self.assertIn("24 September 2026", n2.message)

    # -------------------------------------------------------------------------
    # Test K: Africa/Harare timezone is strictly used
    # -------------------------------------------------------------------------
    def test_k_africa_harare_timezone_strictly_used(self):
        # Shift start: 2026-09-24 at 07:00 Harare time
        Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 24),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        # 2026-09-21 04:00 UTC == 2026-09-21 06:00 Africa/Harare (+2)
        # Shift start in Harare: 2026-09-24 07:00 == exactly 73 hours ahead
        out, _ = self._call_command(now_str="2026-09-21T04:00:00+00:00")
        self.assertIn("Notifications created: 1", out)

    # -------------------------------------------------------------------------
    # Test L: Duty outside reminder window (<48h or >84h) is not notified
    # -------------------------------------------------------------------------
    def test_l_duty_outside_reminder_window_is_not_notified(self):
        # Shift 1: Only 25 hours away (2026-09-22 07:00 vs now 2026-09-21 06:00) -> < 48h
        s_too_soon = Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 22),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        # Shift 2: 121 hours away (2026-09-26 07:00 vs now 2026-09-21 06:00) -> > 84h
        s_too_far = Shift.objects.create(
            station=self.station,
            guard=self.guard2,
            date=date(2026, 9, 26),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster_approved,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 0", out)
        self.assertEqual(Notification.objects.filter(notification_type="UPCOMING_DUTY").count(), 0)

    # -------------------------------------------------------------------------
    # Test M: Specialized ESCORT duty (06:00-17:00) is included and notified
    # -------------------------------------------------------------------------
    def test_m_specialized_escort_duty_is_notified(self):
        # 24 September 2026 at 06:00 is 72.0 hours from 21 September 06:00
        escort_shift = Shift.objects.create(
            station=self.station,
            guard=self.guard1,
            date=date(2026, 9, 24),
            start_time=time(6, 0),
            end_time=time(17, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.ESCORT,
            duty_location="University National Centre - Paper Collection",
            roster=self.roster_approved,
        )

        out, _ = self._call_command()
        self.assertIn("Notifications created: 1", out)

        notif = Notification.objects.filter(user=self.guard1, notification_type="UPCOMING_DUTY").first()
        self.assertIsNotNone(notif)
        self.assertIn("06:00 to 17:00", notif.message)
        self.assertIn("Escort Duty", notif.message)
        self.assertIn("University National Centre - Paper Collection", notif.message)
