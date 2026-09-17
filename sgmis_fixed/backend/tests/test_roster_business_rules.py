from datetime import date, time, timedelta
from django.test import TestCase
from django.contrib.auth import get_user_model
from rest_framework import status
from rest_framework.test import APIClient
from rest_framework.exceptions import ValidationError

from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, AssignmentType, ExaminationPeriod, TemporaryAssignmentAudit
from apps.shifts.services import (
    generate_roster_for_station,
    schedule_exam_escort,
    detect_roster_conflicts,
    resume_normal_roster,
    resolve_incoming_guard,
)

UserModel = get_user_model()

class RosterBusinessRulesTests(TestCase):
    """
    Unit and integration tests verifying all 16 client requirements
    for the authoritative Smart Security roster system.
    """

    def setUp(self):
        self.client = APIClient()
        self.password = "TestPass123!"
        self.start_date = date(2026, 10, 1)

        # Setup Station
        self.station = Station.objects.create(
            name="University Main Campus",
            code="STN-UNI01",
            latitude=1.2921,
            longitude=36.8219,
        )

        # Create Admin
        self.admin = UserModel.objects.create_user(
            username="admin_roster",
            email="admin_roster@sgmis.local",
            password=self.password,
            employee_number="ADM-ROSTER",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
            is_superuser=True,
        )

        # Create 6 Security Guards
        self.guards = []
        for i, code in enumerate(["A", "B", "C", "D", "E", "F"], start=1):
            g = UserModel.objects.create_user(
                username=f"guard_{code.lower()}",
                email=f"guard_{code.lower()}@sgmis.local",
                password=self.password,
                employee_number=f"SEC-10{i}",
                role=UserRole.GUARD,
                station=self.station,
            )
            self.guards.append(g)

        self.guard_a, self.guard_b, self.guard_c, self.guard_d, self.guard_e, self.guard_f = self.guards

        # Configure 3 Pairs
        self.pair1 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_a,
            guard_b=self.guard_b,
            rotation_order=1,
            is_active=True,
        )
        self.pair2 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_c,
            guard_b=self.guard_d,
            rotation_order=2,
            is_active=True,
        )
        self.pair3 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_e,
            guard_b=self.guard_f,
            rotation_order=3,
            is_active=True,
        )

    # TEST 1: 6 guards / 3 pairs generate valid normal coverage.
    def test_1_six_guards_three_pairs_generate_valid_normal_coverage(self):
        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=12)
        self.assertTrue(len(shifts) > 0)
        
        # Verify for every single day in the 12-day cycle, exactly 1 DAY and 1 NIGHT shift exist for Main Campus
        for day_offset in range(12):
            d = self.start_date + timedelta(days=day_offset)
            day_shifts = Shift.objects.filter(station=self.station, date=d, duty_location="Main Campus", shift_type=ShiftType.DAY)
            night_shifts = Shift.objects.filter(station=self.station, date=d, duty_location="Main Campus", shift_type=ShiftType.NIGHT)
            self.assertEqual(day_shifts.count(), 1, f"Day {d} must have exactly 1 Day guard on Main Campus")
            self.assertEqual(night_shifts.count(), 1, f"Day {d} must have exactly 1 Night guard on Main Campus")

        # Conflict check should return no errors
        conflicts = detect_roster_conflicts(self.station, self.start_date, self.start_date + timedelta(days=11))
        self.assertFalse(conflicts["has_conflicts"])

    # TEST 2: Each pair provides one DAY and one NIGHT guard.
    def test_2_each_pair_provides_one_day_and_one_night_guard(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)
        # Block 0 (Days 0..3): Pair 1 active
        for d_offset in range(4):
            d = self.start_date + timedelta(days=d_offset)
            day_shift = Shift.objects.get(station=self.station, date=d, shift_type=ShiftType.DAY)
            night_shift = Shift.objects.get(station=self.station, date=d, shift_type=ShiftType.NIGHT)
            pair_guards = {self.pair1.guard_a, self.pair1.guard_b}
            self.assertEqual({day_shift.guard, night_shift.guard}, pair_guards)

    # TEST 3: DAY = 07:00-18:00.
    def test_3_day_shift_hours(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=4)
        day_shift = Shift.objects.filter(station=self.station, shift_type=ShiftType.DAY).first()
        self.assertEqual(day_shift.start_time, time(7, 0))
        self.assertEqual(day_shift.end_time, time(18, 0))

    # TEST 4: NIGHT = 18:00-07:00.
    def test_4_night_shift_hours(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=4)
        night_shift = Shift.objects.filter(station=self.station, shift_type=ShiftType.NIGHT).first()
        self.assertEqual(night_shift.start_time, time(18, 0))
        self.assertEqual(night_shift.end_time, time(7, 0))

    # TEST 5: After the next working cycle, the pair swaps DAY/NIGHT assignments.
    def test_5_cycle_swap_day_night_assignments(self):
        # 24-day generation covers 2 full cycles for all 3 pairs (12 days per macro cycle)
        generate_roster_for_station(self.station, self.start_date, cycle_days=24)
        
        # Cycle 1 for Pair 1: Days 0..3 (Block 0)
        c1_day = Shift.objects.get(station=self.station, date=self.start_date, shift_type=ShiftType.DAY)
        c1_night = Shift.objects.get(station=self.station, date=self.start_date, shift_type=ShiftType.NIGHT)
        self.assertEqual(c1_day.guard, self.guard_a)
        self.assertEqual(c1_night.guard, self.guard_b)

        # Cycle 2 for Pair 1: Days 12..15 (Block 3)
        c2_date = self.start_date + timedelta(days=12)
        c2_day = Shift.objects.get(station=self.station, date=c2_date, shift_type=ShiftType.DAY)
        c2_night = Shift.objects.get(station=self.station, date=c2_date, shift_type=ShiftType.NIGHT)
        # CRITICAL SWAP: Guard A is now NIGHT, Guard B is now DAY
        self.assertEqual(c2_day.guard, self.guard_b, "In cycle 2, Guard B must swap to DAY")
        self.assertEqual(c2_night.guard, self.guard_a, "In cycle 2, Guard A must swap to NIGHT")

        # Check Pair 2 swap: Cycle 1 (Day 4) vs Cycle 2 (Day 16)
        p2_c1_date = self.start_date + timedelta(days=4)
        p2_c2_date = self.start_date + timedelta(days=16)
        p2_c1_day = Shift.objects.get(station=self.station, date=p2_c1_date, shift_type=ShiftType.DAY)
        p2_c2_day = Shift.objects.get(station=self.station, date=p2_c2_date, shift_type=ShiftType.DAY)
        self.assertEqual(p2_c1_day.guard, self.guard_c)
        self.assertEqual(p2_c2_day.guard, self.guard_d, "In cycle 2, Pair 2 Guard D must swap to DAY")

    # TEST 6: Normal duty allows 4 consecutive working days.
    def test_6_normal_duty_allows_four_consecutive_working_days(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)
        report = detect_roster_conflicts(self.station, self.start_date, self.start_date + timedelta(days=11))
        # 4 consecutive days on normal duty must NOT produce any conflict
        self.assertFalse(report["has_conflicts"])

        # Artificially add a 5th consecutive normal working day for guard_a
        Shift.objects.create(
            station=self.station,
            guard=self.guard_a,
            date=self.start_date + timedelta(days=4),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
            duty_location="Main Campus",
        )
        report_with_5 = detect_roster_conflicts(self.station, self.start_date, self.start_date + timedelta(days=4))
        self.assertTrue(report_with_5["has_conflicts"])
        consecutive_errs = [c for c in report_with_5["conflicts"] if c["type"] == "EXCESSIVE_CONSECUTIVE_DAYS"]
        self.assertTrue(len(consecutive_errs) > 0)
        self.assertIn("4-day normal duty limit", consecutive_errs[0]["message"])

    # TEST 7: Exam duty allows 5 consecutive working days.
    def test_7_exam_duty_allows_five_consecutive_working_days(self):
        # Generate 5 consecutive exam days for guard_c and guard_d
        for d_offset in range(5):
            d = self.start_date + timedelta(days=d_offset)
            Shift.objects.create(
                station=self.station,
                guard=self.guard_c,
                date=d,
                shift_type=ShiftType.DAY,
                assignment_type=AssignmentType.EXAM,
                start_time=time(7, 0),
                end_time=time(18, 0),
                duty_location="Exam Hall A",
            )
            Shift.objects.create(
                station=self.station,
                guard=self.guard_d,
                date=d,
                shift_type=ShiftType.DAY,
                assignment_type=AssignmentType.EXAM,
                start_time=time(7, 0),
                end_time=time(18, 0),
                duty_location="Exam Hall A",
            )
            # Add main campus coverage so uncover errors do not trigger
            # Days 0..3: Pair 1 covers Main Campus (4 days)
            # Day 4: Pair 3 covers Main Campus (1 day) so Pair 1 does not exceed 4 normal duty days
            if d_offset < 4:
                day_g, night_g = self.guard_a, self.guard_b
            else:
                day_g, night_g = self.guard_e, self.guard_f
            Shift.objects.create(
                station=self.station, guard=day_g, date=d,
                shift_type=ShiftType.DAY, assignment_type=AssignmentType.NORMAL,
                start_time=time(7, 0), end_time=time(18, 0), duty_location="Main Campus"
            )
            Shift.objects.create(
                station=self.station, guard=night_g, date=d,
                shift_type=ShiftType.NIGHT, assignment_type=AssignmentType.NORMAL,
                start_time=time(18, 0), end_time=time(7, 0), duty_location="Main Campus"
            )

        report = detect_roster_conflicts(self.station, self.start_date, self.start_date + timedelta(days=4))
        # MUST NOT be flagged as a violation
        excessive_errs = [c for c in report["conflicts"] if c["type"] == "EXCESSIVE_CONSECUTIVE_DAYS"]
        self.assertEqual(len(excessive_errs), 0, "5 consecutive exam days must NOT be flagged as a violation")

    # TEST 8: Exam mode provides: 1 main-campus day guard, 1 main-campus night guard, 2 exam venue day guards.
    def test_8_exam_mode_provides_campus_and_exam_coverage(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=5, mode="EXAM")
        for d_offset in range(5):
            d = self.start_date + timedelta(days=d_offset)
            campus_day = Shift.objects.filter(station=self.station, date=d, duty_location="Main Campus", shift_type=ShiftType.DAY).count()
            campus_night = Shift.objects.filter(station=self.station, date=d, duty_location="Main Campus", shift_type=ShiftType.NIGHT).count()
            exam_day = Shift.objects.filter(station=self.station, date=d, assignment_type=AssignmentType.EXAM, shift_type=ShiftType.DAY).count()

            self.assertEqual(campus_day, 1, f"Main Campus must have exactly 1 Day guard on {d}")
            self.assertEqual(campus_night, 1, f"Main Campus must have exactly 1 Night guard on {d}")
            self.assertEqual(exam_day, 2, f"Exam venue must have exactly 2 Day guards on {d}")

    # TEST 9: Exam venue guards do not get assigned simultaneous main-campus duty.
    def test_9_exam_venue_guards_no_simultaneous_main_campus_duty(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=5, mode="EXAM")
        for d_offset in range(5):
            d = self.start_date + timedelta(days=d_offset)
            exam_guards = set(Shift.objects.filter(station=self.station, date=d, assignment_type=AssignmentType.EXAM).values_list("guard_id", flat=True))
            campus_guards = set(Shift.objects.filter(station=self.station, date=d, duty_location="Main Campus").values_list("guard_id", flat=True))
            
            # Intersection must be empty (zero overlap)
            overlap = exam_guards.intersection(campus_guards)
            self.assertEqual(len(overlap), 0, f"Guard(s) {overlap} assigned simultaneously to Main Campus and Exam Venue on {d}")

    # TEST 10: Escort requires 2 guards.
    def test_10_escort_requires_two_guards(self):
        target_date = self.start_date + timedelta(days=2)
        # Attempt with 1 guard
        with self.assertRaises(ValidationError):
            schedule_exam_escort(self.station, target_date, [self.guard_e.id])

        # Attempt with 3 guards
        with self.assertRaises(ValidationError):
            schedule_exam_escort(self.station, target_date, [self.guard_c.id, self.guard_d.id, self.guard_e.id])

        # Exactly 2 guards succeeds
        shifts = schedule_exam_escort(self.station, target_date, [self.guard_e.id, self.guard_f.id])
        self.assertEqual(len(shifts), 2)

    # TEST 11: Escort time is 06:00-17:00.
    def test_11_escort_timing_zero_six_to_seventeen(self):
        target_date = self.start_date + timedelta(days=2)
        shifts = schedule_exam_escort(self.station, target_date, [self.guard_e.id, self.guard_f.id])
        for s in shifts:
            self.assertEqual(s.start_time, time(6, 0))
            self.assertEqual(s.end_time, time(17, 0))
            self.assertEqual(s.assignment_type, AssignmentType.ESCORT)

    # TEST 12: Escort cannot overlap another mandatory duty.
    def test_12_escort_cannot_overlap_mandatory_duty(self):
        target_date = self.start_date
        generate_roster_for_station(self.station, self.start_date, cycle_days=4)
        
        # On start_date, guard_a is assigned to Main Campus Day (07:00 - 18:00)
        # Attempting to assign guard_a to escort (06:00 - 17:00) must be rejected
        with self.assertRaises(ValidationError):
            schedule_exam_escort(self.station, target_date, [self.guard_a.id, self.guard_e.id])

    # TEST 13: Temporary exam assignments do not destroy the underlying normal pair.
    def test_13_temporary_exam_assignments_preserve_underlying_pair(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=5, mode="EXAM")
        
        # Verify all 3 pairs in database remain intact and active
        self.pair1.refresh_from_db()
        self.pair2.refresh_from_db()
        self.pair3.refresh_from_db()

        self.assertTrue(self.pair1.is_active)
        self.assertEqual(self.pair1.guard_a, self.guard_a)
        self.assertEqual(self.pair1.guard_b, self.guard_b)

        self.assertTrue(self.pair2.is_active)
        self.assertEqual(self.pair2.guard_a, self.guard_c)
        self.assertEqual(self.pair2.guard_b, self.guard_d)

        self.assertTrue(self.pair3.is_active)

        # Verify audit trail records temporary reassignments
        audits = TemporaryAssignmentAudit.objects.filter(temporary_assignment="EXAM")
        self.assertTrue(audits.count() >= 2)

    # TEST 14: After examination end date, normal roster resumes.
    def test_14_normal_roster_resumes_after_exam_period(self):
        exam_period = ExaminationPeriod.objects.create(
            station=self.station,
            name="Semester 1 Final Exams",
            venue_name="University Examination Centre",
            start_date=self.start_date,
            end_date=self.start_date + timedelta(days=4),
            is_active=True,
            authorized_by=self.admin,
        )
        generate_roster_for_station(self.station, self.start_date, cycle_days=5, mode="EXAM", examination_period=exam_period)

        # Resume normal roster after exam period
        resumption_date = self.start_date + timedelta(days=5)
        resume_normal_roster(self.station, after_date=resumption_date, cycle_days=8, authorized_by=self.admin)

        exam_period.refresh_from_db()
        self.assertFalse(exam_period.is_active)

        # Check shifts after resumption are NORMAL duty
        post_exam_shifts = Shift.objects.filter(station=self.station, date__gte=resumption_date)
        for s in post_exam_shifts:
            self.assertIn(s.assignment_type, [AssignmentType.NORMAL, AssignmentType.TIME_OFF])
            self.assertNotEqual(s.assignment_type, AssignmentType.EXAM)

    # TEST 15: No mandatory post is left uncovered.
    def test_15_no_mandatory_post_is_left_uncovered(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=4)
        # Delete day shift on day 2
        d2 = self.start_date + timedelta(days=2)
        Shift.objects.filter(station=self.station, date=d2, shift_type=ShiftType.DAY).delete()

        report = detect_roster_conflicts(self.station, self.start_date, self.start_date + timedelta(days=3))
        self.assertTrue(report["has_conflicts"])
        uncovered = [c for c in report["conflicts"] if c["type"] == "UNCOVERED_POST"]
        self.assertTrue(len(uncovered) > 0)
        self.assertIn("Main Campus Day shift has no assigned security guard", uncovered[0]["message"])

    # TEST 16: No guard receives overlapping duties.
    def test_16_no_guard_receives_overlapping_duties(self):
        generate_roster_for_station(self.station, self.start_date, cycle_days=2)
        station2 = Station.objects.create(name="Exam Annex", code="STN-ANNEX01")
        # Intentionally create overlapping second duty for guard_a
        Shift.objects.create(
            station=station2,
            guard=self.guard_a,
            date=self.start_date,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.EXAM,
            start_time=time(10, 0),
            end_time=time(16, 0),
            duty_location="Exam Hall",
        )

        report = detect_roster_conflicts(self.station, self.start_date, self.start_date + timedelta(days=1))
        self.assertTrue(report["has_conflicts"])
        overlaps = [c for c in report["conflicts"] if c["type"] in ["OVERLAPPING_DUTIES", "DUAL_VENUE_ASSIGNMENT"]]
        self.assertTrue(len(overlaps) > 0)
