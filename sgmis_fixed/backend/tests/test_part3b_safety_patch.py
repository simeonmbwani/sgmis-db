from datetime import date, time, timedelta
from unittest.mock import patch
from django.test import TestCase
from django.utils import timezone
from django.contrib.auth import get_user_model
from rest_framework.exceptions import ValidationError

from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import (
    DutyRoster,
    RosterStatus,
    Shift,
    ShiftType,
    AssignmentType,
    Attendance,
)
from apps.shifts.services import generate_roster_for_station

UserModel = get_user_model()

class Part3BSafetyPatchTests(TestCase):
    """
    Focused unit tests for Smart Security Part 3B safety patch:
    1. Transaction atomicity: complete rollback on any failure during generation
    2. Protection of specialized assignments (EXAM, ESCORT, RELIEF)
    3. Attendance protection on EXAM-mode OFF shifts
    4. Preservation of 4-on/8-off and core Part 3 guarantees
    """

    def setUp(self):
        self.password = "TestPass123!"

        self.station = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-HARARE-01",
            latitude=-17.8252,
            longitude=31.0335,
        )

        self.admin = UserModel.objects.create_user(
            username="roster_admin",
            email="admin@sgmis.local",
            password=self.password,
            employee_number="ADM-ROSTER-01",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
        )

        # Create 6 active operational guards
        self.guards = []
        for i in range(1, 7):
            g = UserModel.objects.create_user(
                username=f"guard_{i}",
                email=f"guard_{i}@sgmis.local",
                password=self.password,
                employee_number=f"SEC-00{i}",
                role=UserRole.GUARD,
                station=self.station,
            )
            self.guards.append(g)

        # Create 3 permanent pairs
        self.pair1 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )
        self.pair2 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[2],
            guard_b=self.guards[3],
            rotation_order=2,
            is_active=True,
        )
        self.pair3 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[4],
            guard_b=self.guards[5],
            rotation_order=3,
            is_active=True,
        )

        self.start_date = date(2026, 9, 14)

    # =========================================================================
    # A. ATOMIC ROLLBACK TESTS
    # =========================================================================

    def test_atomic_rollback_on_mid_generation_failure(self):
        """
        Force an exception after several shifts have been created.
        Verify that no partial shifts or orphaned draft roster persist.
        """
        initial_shift_count = Shift.objects.count()
        initial_roster_count = DutyRoster.objects.count()

        original_update_or_create = Shift.objects.update_or_create
        call_counter = {"count": 0}

        def faulty_update_or_create(*args, **kwargs):
            call_counter["count"] += 1
            # Allow first 4 shifts to be processed, then raise error on 5th
            if call_counter["count"] > 4:
                raise RuntimeError("Simulated mid-generation database or server failure")
            return original_update_or_create(*args, **kwargs)

        with patch.object(Shift.objects, "update_or_create", side_effect=faulty_update_or_create):
            with self.assertRaises(RuntimeError):
                generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        # Entire transaction must be rolled back: 0 new shifts, 0 new rosters
        self.assertEqual(Shift.objects.count(), initial_shift_count)
        self.assertEqual(DutyRoster.objects.count(), initial_roster_count)
        self.assertFalse(DutyRoster.objects.filter(station=self.station, start_date=self.start_date).exists())

    def test_atomic_rollback_on_regeneration_failure_preserves_preexisting_state(self):
        """
        When regenerating a draft roster, if a failure occurs mid-way,
        the pre-existing state should roll back cleanly rather than leaving partial wipes.
        """
        # First generate a valid draft
        initial_shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=12)
        initial_count = Shift.objects.count()
        self.assertGreater(initial_count, 0)

        original_update_or_create = Shift.objects.update_or_create
        call_counter = {"count": 0}

        def faulty_update_or_create(*args, **kwargs):
            call_counter["count"] += 1
            if call_counter["count"] > 3:
                raise RuntimeError("Crash during draft regeneration")
            return original_update_or_create(*args, **kwargs)

        with patch.object(Shift.objects, "update_or_create", side_effect=faulty_update_or_create):
            with self.assertRaises(RuntimeError):
                generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        # Post-failure: transaction rollback restored all initial shifts
        self.assertEqual(Shift.objects.count(), initial_count)

    # =========================================================================
    # B. SPECIALIZED ASSIGNMENT PROTECTION TESTS
    # =========================================================================

    def test_protect_unattended_exam_shift_colliding_with_normal_generation(self):
        """
        An unattended EXAM shift colliding with normal DAY duty must NOT be
        overwritten to NORMAL duty or modified during normal roster generation.
        """
        exam_shift = Shift.objects.create(
            station=self.station,
            guard=self.guards[0],  # campus_day_guard for Day 1
            date=self.start_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.EXAM,
            duty_location="Special Exam Hall B",
        )

        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        exam_shift.refresh_from_db()
        self.assertEqual(exam_shift.assignment_type, AssignmentType.EXAM)
        self.assertEqual(exam_shift.duty_location, "Special Exam Hall B")
        self.assertEqual(exam_shift.guard, self.guards[0])

    def test_protect_unattended_escort_shift_colliding_with_normal_generation(self):
        """
        An unattended ESCORT shift colliding with normal DAY duty must NOT be
        overwritten to NORMAL duty or modified during normal roster generation.
        """
        escort_shift = Shift.objects.create(
            station=self.station,
            guard=self.guards[0],  # campus_day_guard for Day 1
            date=self.start_date,
            start_time=time(6, 0),
            end_time=time(17, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.ESCORT,
            duty_location="University National Centre - Paper Collection",
        )

        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        escort_shift.refresh_from_db()
        self.assertEqual(escort_shift.assignment_type, AssignmentType.ESCORT)
        self.assertEqual(escort_shift.duty_location, "University National Centre - Paper Collection")
        self.assertEqual(escort_shift.start_time, time(6, 0))
        self.assertEqual(escort_shift.end_time, time(17, 0))

    def test_protect_unattended_relief_shift_colliding_with_normal_generation(self):
        """
        An unattended RELIEF shift colliding with normal DAY duty must NOT be
        overwritten to NORMAL duty or modified during normal roster generation.
        """
        relief_shift = Shift.objects.create(
            station=self.station,
            guard=self.guards[0],  # campus_day_guard for Day 1
            date=self.start_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.RELIEF,
            duty_location="Campus Outer Perimeter Relief",
        )

        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        relief_shift.refresh_from_db()
        self.assertEqual(relief_shift.assignment_type, AssignmentType.RELIEF)
        self.assertEqual(relief_shift.duty_location, "Campus Outer Perimeter Relief")

    def test_protect_off_duty_guard_assigned_to_specialized_duty(self):
        """
        If an off-duty guard (e.g. in Pair 2) has an active ESCORT shift on a date,
        normal generation must NOT create a conflicting TIME_OFF shift for that guard.
        """
        escort_guard = self.guards[2]  # Pair 2 guard (normally off duty on Day 1)
        escort_shift = Shift.objects.create(
            station=self.station,
            guard=escort_guard,
            date=self.start_date,
            start_time=time(6, 0),
            end_time=time(17, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.ESCORT,
            duty_location="University National Centre - Escort",
        )

        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        escort_shift.refresh_from_db()
        self.assertEqual(escort_shift.assignment_type, AssignmentType.ESCORT)

        # Verify no conflicting OFF shift was created for escort_guard on start_date
        off_shift_for_guard = Shift.objects.filter(
            station=self.station,
            guard=escort_guard,
            date=self.start_date,
            shift_type=ShiftType.OFF,
        ).first()
        self.assertIsNone(off_shift_for_guard)

    # =========================================================================
    # C. ATTENDANCE-PROTECTED EXAM OFF TESTS
    # =========================================================================

    def test_attendance_protected_exam_off_shift_is_not_deleted(self):
        """
        In EXAM mode, line 341 deletes OFF shifts for exam guards.
        Verify that an OFF shift with Attendance is strictly protected
        and NOT deleted.
        """
        exam_guard = self.guards[2]  # Pair 2 guard (default relief pair in exam mode)
        off_shift = Shift.objects.create(
            station=self.station,
            guard=exam_guard,
            date=self.start_date,
            start_time=time(0, 0),
            end_time=time(0, 0),
            shift_type=ShiftType.OFF,
            assignment_type=AssignmentType.TIME_OFF,
            duty_location="Time Off",
        )

        att = Attendance.objects.create(
            shift=off_shift,
            guard=exam_guard,
            clock_in=timezone.now(),
        )

        # Run generation in EXAM mode
        generate_roster_for_station(self.station, self.start_date, cycle_days=5, mode="EXAM")

        # off_shift and att must still exist
        self.assertTrue(Shift.objects.filter(id=off_shift.id).exists())
        self.assertTrue(Attendance.objects.filter(id=att.id).exists())

    # =========================================================================
    # D. CORE PART 3 INVARIANTS PASS
    # =========================================================================

    def test_four_on_eight_off_and_swapping_still_pass(self):
        """
        Verify that 4-on/8-off and Day/Night alternation operate correctly.
        """
        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=24)
        draft_roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)

        # Over 24 days (2 complete 12-day cycles), each pair has 8 working days and 16 off days
        for p in [self.pair1, self.pair2, self.pair3]:
            for g in [p.guard_a, p.guard_b]:
                work_days = Shift.objects.filter(
                    station=self.station,
                    guard=g,
                    shift_type__in=[ShiftType.DAY, ShiftType.NIGHT],
                ).count()
                off_days = Shift.objects.filter(
                    station=self.station,
                    guard=g,
                    shift_type=ShiftType.OFF,
                ).count()
                self.assertEqual(work_days, 8)
                self.assertEqual(off_days, 16)

        # Pair 1: Day 1-4 Guard 1 is DAY, Guard 2 is NIGHT
        day1_day = Shift.objects.get(station=self.station, date=self.start_date, shift_type=ShiftType.DAY)
        day1_night = Shift.objects.get(station=self.station, date=self.start_date, shift_type=ShiftType.NIGHT)
        self.assertEqual(day1_day.guard, self.guards[0])
        self.assertEqual(day1_night.guard, self.guards[1])

        # Pair 1: Day 13-16 (Cycle 2) Guard 2 is DAY, Guard 1 is NIGHT
        cycle2_date = self.start_date + timedelta(days=12)
        c2_day = Shift.objects.get(station=self.station, date=cycle2_date, shift_type=ShiftType.DAY)
        c2_night = Shift.objects.get(station=self.station, date=cycle2_date, shift_type=ShiftType.NIGHT)
        self.assertEqual(c2_day.guard, self.guards[1])
        self.assertEqual(c2_night.guard, self.guards[0])
