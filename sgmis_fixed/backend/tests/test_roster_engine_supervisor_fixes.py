from datetime import date, time, timedelta
from django.utils import timezone
from django.test import TestCase
from django.core.exceptions import ValidationError as DjangoValidationError
from rest_framework.exceptions import ValidationError as DRFValidationError
from rest_framework import status
from rest_framework.test import APIClient

from apps.accounts.models import User, UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import (
    Shift,
    ShiftType,
    AssignmentType,
    DutyRoster,
    RosterStatus,
    TemporaryAssignmentAudit,
)
from apps.shifts.services import (
    detect_roster_conflicts,
    validate_duty_roster,
    approve_duty_roster,
    generate_roster_for_station,
)
from apps.leave.models import LeaveApplication, LeaveType, LeaveStatus


class SupervisorRosterEngineFixesTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.today = timezone.localdate()
        self.tomorrow = self.today + timedelta(days=1)

        # Stations
        self.station1 = Station.objects.create(
            name="Alpha Station",
            code="ALP01",
            latitude=-17.82,
            longitude=31.05,
            geofence_radius_meters=300.0,
            is_active=True,
        )
        self.station2 = Station.objects.create(
            name="Beta Station",
            code="BET01",
            latitude=-17.85,
            longitude=31.08,
            geofence_radius_meters=300.0,
            is_active=True,
        )

        # Supervisor & Admin
        self.admin = User.objects.create_user(
            username="admin_user",
            password="Password123!",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
        )
        self.supervisor = User.objects.create_user(
            username="sup_alpha",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station1,
        )

        # 6 Guards for Station 1 (3 pairs)
        self.guards = []
        for i in range(1, 7):
            g = User.objects.create_user(
                username=f"guard_{i}",
                password="Password123!",
                role=UserRole.GUARD,
                station=self.station1,
                employee_number=f"SEC-{100+i}",
            )
            self.guards.append(g)

        # Pair 1, 2, 3
        self.pair1 = GuardPair.objects.create(
            station=self.station1,
            guard_a=self.guards[0],
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )
        self.pair2 = GuardPair.objects.create(
            station=self.station1,
            guard_a=self.guards[2],
            guard_b=self.guards[3],
            rotation_order=2,
            is_active=True,
        )
        self.pair3 = GuardPair.objects.create(
            station=self.station1,
            guard_a=self.guards[4],
            guard_b=self.guards[5],
            rotation_order=3,
            is_active=True,
        )

        # External / Relief guard
        self.relief_guard = User.objects.create_user(
            username="guard_relief",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station2,
            employee_number="SEC-999",
        )

    def test_scenario_01_full_db_scan_prevented_when_dates_omitted(self):
        """Scenario 1: Full DB scan prevented when dates omitted. Audit bounded to operational roster window."""
        # Create an old historical shift outside the operational window
        past_date = self.today - timedelta(days=60)
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            date=past_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )

        # Create active operational roster for today to today + 5
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=5),
            status=RosterStatus.APPROVED,
            approved_by=self.supervisor,
        )
        # Add normal working shift within roster
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[1],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
        )

        # Conflict audit without date parameters
        report = detect_roster_conflicts(self.station1)
        self.assertEqual(report["start_date"], self.today.isoformat())
        self.assertEqual(report["end_date"], (self.today + timedelta(days=5)).isoformat())
        # The past date should NOT be audited
        conflict_dates = [c.get("date") for c in report.get("conflicts", [])]
        self.assertNotIn(past_date.isoformat(), conflict_dates)

    def test_scenario_02_archived_rosters_excluded_from_conflict_audit(self):
        """Scenario 2: Archived rosters completely excluded from conflict audit."""
        archived_roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=3),
            status=RosterStatus.ARCHIVED,
        )
        # Shift in archived roster with missing night shift
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            roster=archived_roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )

        # Run conflict audit on today
        report = detect_roster_conflicts(self.station1, start_date=self.today, end_date=self.today)
        # Because the only shift is in an ARCHIVED roster, it's excluded from audit
        # No shifts scanned for this archived roster
        self.assertEqual(report["shifts_scanned"], 0)

    def test_scenario_03_approved_leave_guard_excluded_from_active_coverage_count(self):
        """Scenario 3: Approved leave guard excluded from active post coverage count."""
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=3),
            status=RosterStatus.APPROVED,
        )
        # Guard 0 has DAY shift on today
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        # Guard 1 has NIGHT shift on today
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[1],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
        )

        # Guard 0 is on approved leave today
        LeaveApplication.objects.create(
            guard=self.guards[0],
            leave_type=LeaveType.VACATION,
            start_date=self.today,
            end_date=self.today,
            status=LeaveStatus.APPROVED,
            reason="Holiday",
        )

        report = detect_roster_conflicts(self.station1, start_date=self.today, end_date=self.today)
        # Guard 0 is on leave without relief -> UNCOVERED_POST for DAY shift!
        uncovered = [c for c in report["conflicts"] if c["type"] == "UNCOVERED_POST" and c.get("shift_type") == ShiftType.DAY]
        self.assertTrue(len(uncovered) > 0)
        self.assertIn("approved leave without relief", uncovered[0]["message"])

    def test_scenario_04_valid_relief_guard_covers_post_no_overstaffing(self):
        """Scenario 4: Valid relief guard covers post (UNCOVERED_POST cleared, count = 1, LOCATION_OVERSTAFFED NOT raised)."""
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=3),
            status=RosterStatus.APPROVED,
        )
        # Guard 0 is on leave
        LeaveApplication.objects.create(
            guard=self.guards[0],
            leave_type=LeaveType.VACATION,
            start_date=self.today,
            end_date=self.today,
            status=LeaveStatus.APPROVED,
            reason="Holiday",
        )
        # Guard 0's original shift
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        # Relief guard assigned for DAY shift
        Shift.objects.create(
            station=self.station1,
            guard=self.relief_guard,
            roster=roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.RELIEF,
            is_override=True,
        )
        # Guard 1 covers NIGHT shift
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[1],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
        )

        report = detect_roster_conflicts(self.station1, start_date=self.today, end_date=self.today)
        # DAY shift should NOT be uncovered
        day_uncovered = [c for c in report["conflicts"] if c["type"] == "UNCOVERED_POST" and c.get("shift_type") == ShiftType.DAY]
        self.assertEqual(len(day_uncovered), 0)
        # DAY shift should NOT be overstaffed (relief guard + leave guard != 2 active guards)
        overstaffed = [c for c in report["conflicts"] if c["type"] == "LOCATION_OVERSTAFFED"]
        self.assertEqual(len(overstaffed), 0)

    def test_scenario_05_uncovered_post_correctly_raised_when_leave_without_relief(self):
        """Scenario 5: Uncovered post correctly raised when guard on leave has no relief."""
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=2),
            status=RosterStatus.APPROVED,
        )
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[1],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
        )
        LeaveApplication.objects.create(
            guard=self.guards[1],
            leave_type=LeaveType.SICK,
            start_date=self.today,
            end_date=self.today,
            status=LeaveStatus.APPROVED,
            reason="Illness",
        )

        report = detect_roster_conflicts(self.station1, start_date=self.today, end_date=self.today)
        night_uncovered = [c for c in report["conflicts"] if c["type"] == "UNCOVERED_POST" and c.get("shift_type") == ShiftType.NIGHT]
        self.assertTrue(len(night_uncovered) > 0)

    def test_scenario_06_relief_guard_validated_in_validate_duty_roster(self):
        """Scenario 6: Relief guard properly validated in validate_duty_roster() with TemporaryAssignmentAudit and no conflicting duties."""
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today,
            status=RosterStatus.DRAFT,
        )
        # Create audit for relief guard
        TemporaryAssignmentAudit.objects.create(
            guard=self.relief_guard,
            original_assignment="TIME_OFF",
            temporary_assignment="RELIEF",
            location="Alpha Station",
            start_date=self.today,
            end_date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            reason="Coverage for sick guard",
            authorized_by=self.supervisor,
        )
        # Relief shift for relief guard
        Shift.objects.create(
            station=self.station1,
            guard=self.relief_guard,
            roster=roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.RELIEF,
        )
        # Night shift for guard 1
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[1],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
        )

        # Calling validate_duty_roster should accept the RELIEF shift without raising ValidationError
        result = validate_duty_roster(roster)
        self.assertTrue(result["valid"])

    def test_scenario_07_main_campus_3_pair_rotation_rules_preserved_for_normal(self):
        """Scenario 7: Main Campus 3-pair rotation rules preserved for NORMAL assignment type."""
        # Generating a 12-day roster for station 1 (Main Campus)
        # Should generate with all 3 pairs in 4-on-8-off rotation
        created_shifts = generate_roster_for_station(
            station=self.station1,
            start_date=self.today,
            cycle_days=12,
            mode="NORMAL",
        )
        self.assertEqual(len(created_shifts), 72)
        roster = created_shifts[0].roster
        # validate_duty_roster must pass for this generated NORMAL roster
        val_result = validate_duty_roster(roster)
        self.assertTrue(val_result["valid"])

    def test_scenario_08_shift_viewset_operational_excludes_archived_rosters(self):
        """Scenario 8: ShiftViewSet.operational excludes ARCHIVED rosters."""
        archived_roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=2),
            status=RosterStatus.ARCHIVED,
        )
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            roster=archived_roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )

        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.get(f"/shifts/shifts/operational/?start_date={self.today}&end_date={self.today}")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        # No shifts should be returned because the roster is ARCHIVED
        self.assertEqual(len(resp.data), 0)

    def test_scenario_09_shift_viewset_operational_scopes_to_roster_when_dates_omitted(self):
        """Scenario 9: ShiftViewSet.operational scopes to operational roster window when start_date/end_date omitted."""
        # Operational roster: today to today + 3
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=3),
            status=RosterStatus.APPROVED,
            approved_by=self.supervisor,
        )
        # Shift within window
        s_inside = Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            roster=roster,
            date=self.today + timedelta(days=1),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        # Shift far outside window without roster
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[1],
            date=self.today + timedelta(days=50),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )

        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.get("/shifts/shifts/operational/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        shift_ids = [s["id"] for s in resp.data]
        self.assertIn(str(s_inside.id), shift_ids)
        self.assertEqual(len(resp.data), 1)

    def test_scenario_10_multi_station_conflict_prevention_on_shift_save(self):
        """Scenario 10: Multi-station conflict prevention on Shift.save() (ValidationError if active working duty at another station on same date)."""
        # Guard 0 has active working duty at station 1
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )

        # Attempting to save a working shift for Guard 0 at station 2 on the same date must raise ValidationError
        conflicting_shift = Shift(
            station=self.station2,
            guard=self.guards[0],
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            conflicting_shift.full_clean()
        self.assertIn("already has an active", str(ctx.exception))

    def test_scenario_11_multi_station_conflict_during_roster_generation(self):
        """Scenario 11: Multi-station conflict prevention during roster generation (_check_other_station_active)."""
        # Guard 0 has active working duty at Station 2
        Shift.objects.create(
            station=self.station2,
            guard=self.guards[0],
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )

        # Attempting to generate a roster for Station 1 that schedules Guard 0 to work on today must fail
        with self.assertRaises(DRFValidationError) as ctx:
            generate_roster_for_station(
                station=self.station1,
                start_date=self.today,
                cycle_days=12,
                mode="NORMAL",
            )
        self.assertIn("already has an active", str(ctx.exception))

    def test_scenario_12_dual_station_duty_flagged_in_detect_roster_conflicts(self):
        """Scenario 12: Dual station duty flagged as DUAL_STATION_DUTY in detect_roster_conflicts()."""
        # Guard 0 has shift at station 1
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=1),
            status=RosterStatus.APPROVED,
        )
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            roster=roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        # Force a shift at Station 2 directly via super().save() to bypass the model guard
        s2 = Shift(
            station=self.station2,
            guard=self.guards[0],
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.RELIEF,
        )
        super(Shift, s2).save()

        report = detect_roster_conflicts(self.station1, start_date=self.today, end_date=self.today)
        dual_station_conflicts = [c for c in report["conflicts"] if c["type"] == "DUAL_STATION_DUTY"]
        self.assertTrue(len(dual_station_conflicts) > 0)
        self.assertIn("Beta Station", dual_station_conflicts[0]["message"])

    def test_scenario_13_roster_approval_safely_transitions_previous_to_archived(self):
        """Scenario 13: Roster approval safely transitions previous approved rosters for station with end_date < new_roster.start_date to ARCHIVED."""
        # Previous approved roster
        old_roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today - timedelta(days=20),
            end_date=self.today - timedelta(days=1),
            status=RosterStatus.APPROVED,
            approved_by=self.supervisor,
        )

        # New validated roster
        new_roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today,
            status=RosterStatus.VALIDATED,
        )
        # Create normal shifts for new roster (both day and night)
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[0],
            pair=self.pair1,
            roster=new_roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[1],
            pair=self.pair1,
            roster=new_roster,
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
        )

        approved_data = approve_duty_roster(new_roster, user=self.supervisor)
        self.assertTrue(approved_data.get("approved", False))
        self.assertEqual(approved_data.get("status"), RosterStatus.APPROVED)

        new_roster.refresh_from_db()
        self.assertEqual(new_roster.status, RosterStatus.APPROVED)

        # Old roster should now have status ARCHIVED
        old_roster.refresh_from_db()
        self.assertEqual(old_roster.status, RosterStatus.ARCHIVED)

    def test_scenario_14_guard_on_leave_does_not_accumulate_consecutive_days(self):
        """Scenario 14: Guard on approved leave does not accumulate consecutive working days towards EXCESSIVE_CONSECUTIVE_DAYS."""
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=7),
            status=RosterStatus.APPROVED,
        )
        # Create 7 days of shifts for Guard 0
        for i in range(7):
            d = self.today + timedelta(days=i)
            Shift.objects.create(
                station=self.station1,
                guard=self.guards[0],
                roster=roster,
                date=d,
                start_time=time(7, 0),
                end_time=time(18, 0),
                shift_type=ShiftType.DAY,
                assignment_type=AssignmentType.NORMAL,
            )

        # Put Guard 0 on approved leave on day 3
        LeaveApplication.objects.create(
            guard=self.guards[0],
            leave_type=LeaveType.VACATION,
            start_date=self.today + timedelta(days=3),
            end_date=self.today + timedelta(days=3),
            status=LeaveStatus.APPROVED,
            reason="Holiday",
        )

        report = detect_roster_conflicts(self.station1, start_date=self.today, end_date=self.today + timedelta(days=7))
        # Streak broken on day 3: 3 days before, 3 days after. None >= 6 days.
        excessive = [c for c in report["conflicts"] if c["type"] == "EXCESSIVE_CONSECUTIVE_DAYS"]
        self.assertEqual(len(excessive), 0)

    def test_scenario_15_relief_guard_rejected_if_inactive_or_conflicting(self):
        """Scenario 15: Relief guard who is not active GUARD role or has conflicting assignment is rejected in validate_duty_roster()."""
        roster = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today,
            status=RosterStatus.DRAFT,
        )

        # Authorize relief assignment for supervisor (who has role SUPERVISOR, not GUARD)
        TemporaryAssignmentAudit.objects.create(
            guard=self.supervisor,
            original_assignment="TIME_OFF",
            temporary_assignment="RELIEF",
            location="Alpha Station",
            start_date=self.today,
            end_date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            reason="Coverage attempt by supervisor",
            authorized_by=self.admin,
        )

        # Day shift with supervisor as relief
        Shift.objects.create(
            station=self.station1,
            guard=self.supervisor,
            roster=roster,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.RELIEF,
        )
        # Night shift with normal guard
        Shift.objects.create(
            station=self.station1,
            guard=self.guards[1],
            pair=self.pair1,
            roster=roster,
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
        )

        with self.assertRaises(DRFValidationError) as ctx:
            validate_duty_roster(roster)
        self.assertIn("must be an active user with role GUARD", str(ctx.exception))
