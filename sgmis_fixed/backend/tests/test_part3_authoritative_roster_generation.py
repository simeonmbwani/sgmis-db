from datetime import date, time, timedelta
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

class AuthoritativeRosterGenerationTests(TestCase):
    """
    Focused unit tests covering Smart Security Part 3:
    Authoritative normal roster generation, 12-day 4-on/8-off cycle,
    day/night role swapping, safe DRAFT regeneration, and protection of
    approved rosters and existing data.
    """

    def setUp(self):
        self.password = "TestPass123!"

        self.station = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-HARARE-01",
            latitude=-17.8252,
            longitude=31.0335,
        )

        self.station2 = Station.objects.create(
            name="North Satellite Post",
            code="STN-HARARE-02",
            latitude=-17.8000,
            longitude=31.0500,
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
                employee_number=f"SEC-GEN-0{i}",
                role=UserRole.GUARD,
                is_active=True,
                station=self.station,
                first_name=f"GuardFirst{i}",
                last_name=f"GuardLast{i}",
            )
            self.guards.append(g)

        # 3 permanent pairs with rotation orders 1, 2, 3
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

    def test_01_12_day_roster_creates_draft_duty_roster(self):
        """1. 12-day roster generation creates a DRAFT DutyRoster linking all child shifts."""
        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        rosters = DutyRoster.objects.filter(station=self.station)
        self.assertEqual(rosters.count(), 1)
        draft = rosters.first()

        self.assertEqual(draft.status, RosterStatus.DRAFT)
        self.assertEqual(draft.start_date, date(2026, 9, 14))
        self.assertEqual(draft.end_date, date(2026, 9, 25))
        self.assertEqual(len(shifts), 72)

        # All child shifts must be linked to this draft roster
        for s in shifts:
            self.assertEqual(s.roster_id, draft.id)

    def test_02_correct_three_pair_rotation(self):
        """2. Correct three-pair rotation: Pair 1 ON, then Pair 2 ON, then Pair 3 ON."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        # Block 1 (days 0..3: Sep 14-17): Pair 1 active
        for d in range(4):
            cur = self.start_date + timedelta(days=d)
            on_shifts = Shift.objects.filter(station=self.station, date=cur, assignment_type=AssignmentType.NORMAL)
            self.assertEqual(on_shifts.count(), 2)
            self.assertTrue(all(s.pair_id == self.pair1.id for s in on_shifts))

        # Block 2 (days 4..7: Sep 18-21): Pair 2 active
        for d in range(4, 8):
            cur = self.start_date + timedelta(days=d)
            on_shifts = Shift.objects.filter(station=self.station, date=cur, assignment_type=AssignmentType.NORMAL)
            self.assertEqual(on_shifts.count(), 2)
            self.assertTrue(all(s.pair_id == self.pair2.id for s in on_shifts))

        # Block 3 (days 8..11: Sep 22-25): Pair 3 active
        for d in range(8, 12):
            cur = self.start_date + timedelta(days=d)
            on_shifts = Shift.objects.filter(station=self.station, date=cur, assignment_type=AssignmentType.NORMAL)
            self.assertEqual(on_shifts.count(), 2)
            self.assertTrue(all(s.pair_id == self.pair3.id for s in on_shifts))

    def test_03_each_pair_receives_exactly_4_on_days(self):
        """3. Each pair receives exactly 4 ON days in a 12-day cycle."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        for p in [self.pair1, self.pair2, self.pair3]:
            on_shifts = Shift.objects.filter(station=self.station, pair=p, assignment_type=AssignmentType.NORMAL)
            dates = set(on_shifts.values_list("date", flat=True))
            self.assertEqual(len(dates), 4, f"Pair {p.rotation_order} must have exactly 4 ON dates")
            self.assertEqual(on_shifts.count(), 8, f"Pair {p.rotation_order} must have 8 ON shift records (2 per day)")

    def test_04_each_pair_receives_exactly_8_time_off_days_in_12_day_cycle(self):
        """4. Each pair receives exactly 8 TIME_OFF days in a 12-day cycle."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        for p in [self.pair1, self.pair2, self.pair3]:
            off_shifts = Shift.objects.filter(station=self.station, pair=p, assignment_type=AssignmentType.TIME_OFF)
            dates = set(off_shifts.values_list("date", flat=True))
            self.assertEqual(len(dates), 8, f"Pair {p.rotation_order} must have exactly 8 TIME_OFF dates")
            self.assertEqual(off_shifts.count(), 16, f"Pair {p.rotation_order} must have 16 TIME_OFF shift records (2 per day)")

    def test_05_six_operational_guards_represented_correctly(self):
        """5. Six operational guards are represented with exact 4-ON / 8-OFF balance."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        guard_ids_in_shifts = set(Shift.objects.filter(station=self.station).values_list("guard_id", flat=True))
        expected_guard_ids = {g.id for g in self.guards}
        self.assertEqual(guard_ids_in_shifts, expected_guard_ids)

        for g in self.guards:
            on_count = Shift.objects.filter(station=self.station, guard=g, assignment_type=AssignmentType.NORMAL).count()
            off_count = Shift.objects.filter(station=self.station, guard=g, assignment_type=AssignmentType.TIME_OFF).count()
            self.assertEqual(on_count, 4, f"Guard {g.username} must have exactly 4 ON shifts")
            self.assertEqual(off_count, 8, f"Guard {g.username} must have exactly 8 TIME_OFF shifts")

    def test_06_pair_1_starts_14_september_2026_when_requested(self):
        """6. Pair 1 starts on 14 September 2026."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        sep14_day = Shift.objects.get(station=self.station, date=date(2026, 9, 14), shift_type=ShiftType.DAY)
        sep14_night = Shift.objects.get(station=self.station, date=date(2026, 9, 14), shift_type=ShiftType.NIGHT)

        self.assertEqual(sep14_day.pair_id, self.pair1.id)
        self.assertEqual(sep14_night.pair_id, self.pair1.id)

    def test_07_pair_2_takes_next_4_day_block(self):
        """7. Pair 2 takes the next 4-day block (18–21 September)."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        for d in [18, 19, 20, 21]:
            cur = date(2026, 9, d)
            day = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.DAY)
            night = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.NIGHT)
            self.assertEqual(day.pair_id, self.pair2.id)
            self.assertEqual(night.pair_id, self.pair2.id)

    def test_08_pair_3_takes_next_4_day_block(self):
        """8. Pair 3 takes the next 4-day block (22–25 September)."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        for d in [22, 23, 24, 25]:
            cur = date(2026, 9, d)
            day = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.DAY)
            night = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.NIGHT)
            self.assertEqual(day.pair_id, self.pair3.id)
            self.assertEqual(night.pair_id, self.pair3.id)

    def test_09_day_night_assignment_in_cycle_1(self):
        """9. In cycle 1, guard_a works DAY and guard_b works NIGHT."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        # Pair 1: Sep 14-17
        for d in range(4):
            cur = self.start_date + timedelta(days=d)
            day = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.DAY)
            night = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.NIGHT)
            self.assertEqual(day.guard_id, self.pair1.guard_a_id)
            self.assertEqual(night.guard_id, self.pair1.guard_b_id)

    def test_10_day_night_assignment_swaps_in_cycle_2(self):
        """10. In cycle 2, guard_b works DAY and guard_a works NIGHT."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=24)

        # Pair 1 in Cycle 2 works days 12..15 (Sep 26-29)
        for d in range(12, 16):
            cur = self.start_date + timedelta(days=d)
            day = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.DAY)
            night = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.NIGHT)
            self.assertEqual(day.guard_id, self.pair1.guard_b_id, "Guard B must be DAY in Cycle 2")
            self.assertEqual(night.guard_id, self.pair1.guard_a_id, "Guard A must be NIGHT in Cycle 2")

    def test_11_day_night_assignment_swaps_back_in_cycle_3(self):
        """11. In cycle 3, roles swap back: guard_a works DAY and guard_b works NIGHT."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=36)

        # Pair 1 in Cycle 3 works days 24..27 (Oct 8-11)
        for d in range(24, 28):
            cur = self.start_date + timedelta(days=d)
            day = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.DAY)
            night = Shift.objects.get(station=self.station, date=cur, shift_type=ShiftType.NIGHT)
            self.assertEqual(day.guard_id, self.pair1.guard_a_id, "Guard A must be DAY in Cycle 3")
            self.assertEqual(night.guard_id, self.pair1.guard_b_id, "Guard B must be NIGHT in Cycle 3")

    def test_12_overnight_shift_ends_at_0700_following_day(self):
        """12. Overnight shift is anchored to start date (18:00 to 07:00 next day)."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        night_shift = Shift.objects.get(station=self.station, date=self.start_date, shift_type=ShiftType.NIGHT)
        self.assertEqual(night_shift.start_time, time(18, 0))
        self.assertEqual(night_shift.end_time, time(7, 0))
        self.assertEqual(night_shift.date, self.start_date)

    def test_13_no_guard_has_day_and_night_on_one_date(self):
        """13. No guard has both DAY and NIGHT on the same date."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=24)

        for d_offset in range(24):
            cur = self.start_date + timedelta(days=d_offset)
            for g in self.guards:
                active_count = Shift.objects.filter(
                    station=self.station,
                    guard=g,
                    date=cur,
                    shift_type__in=[ShiftType.DAY, ShiftType.NIGHT],
                ).count()
                self.assertLessEqual(active_count, 1, f"Guard {g.username} has duplicate active shifts on {cur}")

    def test_14_no_guard_has_on_and_time_off_on_one_date(self):
        """14. No guard has both an ON shift and TIME_OFF on the same date."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=24)

        for d_offset in range(24):
            cur = self.start_date + timedelta(days=d_offset)
            for g in self.guards:
                has_on = Shift.objects.filter(
                    station=self.station,
                    guard=g,
                    date=cur,
                    shift_type__in=[ShiftType.DAY, ShiftType.NIGHT],
                ).exists()

                has_off = Shift.objects.filter(
                    station=self.station,
                    guard=g,
                    date=cur,
                    shift_type=ShiftType.OFF,
                ).exists()

                self.assertFalse(has_on and has_off, f"Guard {g.username} has both ON and TIME_OFF on {cur}")

    def test_15_existing_approved_roster_blocks_regeneration(self):
        """15. An existing APPROVED DutyRoster blocks regeneration of any overlapping dates."""
        DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 9, 14),
            end_date=date(2026, 9, 25),
            status=RosterStatus.APPROVED,
            approved_by=self.admin,
            approved_at=timezone.now(),
        )

        with self.assertRaises(ValidationError) as ctx:
            generate_roster_for_station(self.station, date(2026, 9, 14), cycle_days=12)
        self.assertIn("approved", str(ctx.exception).lower())

    def test_16_existing_active_roster_blocks_regeneration(self):
        """16. An existing ACTIVE DutyRoster blocks regeneration of any overlapping dates."""
        DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 9, 14),
            end_date=date(2026, 9, 25),
            status=RosterStatus.ACTIVE,
            approved_by=self.admin,
            approved_at=timezone.now(),
        )

        with self.assertRaises(ValidationError) as ctx:
            generate_roster_for_station(self.station, date(2026, 9, 14), cycle_days=12)
        self.assertIn("active", str(ctx.exception).lower())

    def test_17_existing_draft_roster_is_safely_regenerated(self):
        """17. Existing matching DRAFT roster is reused and safely regenerated without duplicating shifts."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)
        initial_roster_id = DutyRoster.objects.get(station=self.station).id

        # Regenerate exact same period
        shifts_regen = generate_roster_for_station(self.station, self.start_date, cycle_days=12)
        after_roster = DutyRoster.objects.get(station=self.station)

        self.assertEqual(after_roster.id, initial_roster_id)
        self.assertEqual(len(shifts_regen), 72)
        self.assertEqual(Shift.objects.filter(station=self.station).count(), 72)

    def test_18_attendance_protected_shifts_are_never_deleted(self):
        """18. Shifts with attendance records are never deleted during draft regeneration."""
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        target_shift = Shift.objects.filter(
            station=self.station,
            shift_type=ShiftType.DAY,
            date=self.start_date,
        ).first()

        # Add attendance to target shift
        Attendance.objects.create(
            shift=target_shift,
            guard=target_shift.guard,
            clock_in=timezone.now(),
        )

        # Regenerate draft roster
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        # Shift must still exist with the exact same ID and attendance intact
        refreshed = Shift.objects.get(id=target_shift.id)
        self.assertEqual(refreshed.attendance_records.count(), 1)

    def test_19_exam_escort_relief_shifts_are_never_deleted(self):
        """19. EXAM, ESCORT, and RELIEF shifts are never deleted by normal roster regeneration."""
        draft = DutyRoster.objects.create(
            station=self.station,
            start_date=self.start_date,
            end_date=self.start_date + timedelta(days=11),
            status=RosterStatus.DRAFT,
        )

        exam_shift = Shift.objects.create(
            station=self.station,
            guard=self.guards[4],
            date=self.start_date + timedelta(days=1),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.EXAM,
            roster=draft,
        )

        # Generate normal roster
        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        # Exam shift must still exist
        self.assertTrue(Shift.objects.filter(id=exam_shift.id).exists())

    def test_20_conflicting_external_roster_shift_is_reported_not_deleted(self):
        """20. Conflicting shift belonging to another roster is reported via ValidationError and not deleted."""
        ext_roster = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 9, 10),
            end_date=date(2026, 9, 20),
            status=RosterStatus.DRAFT,
        )

        conflict_shift = Shift.objects.create(
            station=self.station,
            guard=self.guards[0],
            date=date(2026, 9, 15),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=ext_roster,
        )

        with self.assertRaises(ValidationError):
            generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        # Shift in external roster must NOT be deleted
        self.assertTrue(Shift.objects.filter(id=conflict_shift.id).exists())

    def test_21_generation_of_24_days_continues_12_day_cycle_correctly(self):
        """21. 24-day generation produces 2 full cycles (144 shifts) with proper swap."""
        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=24)
        self.assertEqual(len(shifts), 144)

        draft = DutyRoster.objects.get(station=self.station)
        self.assertEqual(draft.end_date, date(2026, 10, 7))

        # Each guard has 8 ON shifts and 16 TIME_OFF shifts
        for g in self.guards:
            on_count = Shift.objects.filter(station=self.station, guard=g, assignment_type=AssignmentType.NORMAL).count()
            off_count = Shift.objects.filter(station=self.station, guard=g, assignment_type=AssignmentType.TIME_OFF).count()
            self.assertEqual(on_count, 8)
            self.assertEqual(off_count, 16)

    def test_22_generation_of_36_days_continues_cycle_correctly(self):
        """22. 36-day generation produces 3 full cycles (216 shifts) with third cycle swap back."""
        shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=36)
        self.assertEqual(len(shifts), 216)

        draft = DutyRoster.objects.get(station=self.station)
        self.assertEqual(draft.end_date, date(2026, 10, 19))

        # Each guard has 12 ON shifts and 24 TIME_OFF shifts
        for g in self.guards:
            on_count = Shift.objects.filter(station=self.station, guard=g, assignment_type=AssignmentType.NORMAL).count()
            off_count = Shift.objects.filter(station=self.station, guard=g, assignment_type=AssignmentType.TIME_OFF).count()
            self.assertEqual(on_count, 12)
            self.assertEqual(off_count, 24)

    def test_23_invalid_pair_configuration_blocks_generation(self):
        """23. Station with invalid pair configuration is strictly rejected."""
        # Station with 0 pairs
        empty_station = Station.objects.create(name="Empty Post", code="STN-EMPTY")
        with self.assertRaises(ValidationError):
            generate_roster_for_station(empty_station, self.start_date, cycle_days=12)

        # Station with inactive pair
        self.pair3.is_active = False
        self.pair3.save()
        with self.assertRaises(ValidationError):
            generate_roster_for_station(self.station, self.start_date, cycle_days=12)

    def test_24_existing_legacy_adhoc_shifts_with_roster_null_survive(self):
        """24. Existing legacy/ad-hoc shifts with roster=NULL outside target dates survive."""
        adhoc_shift = Shift.objects.create(
            station=self.station,
            guard=self.guards[0],
            date=date(2026, 8, 1),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=None,
        )

        generate_roster_for_station(self.station, self.start_date, cycle_days=12)

        self.assertTrue(Shift.objects.filter(id=adhoc_shift.id, roster__isnull=True).exists())
