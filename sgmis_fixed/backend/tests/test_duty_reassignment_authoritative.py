import unittest.mock
from datetime import date, time, timedelta, datetime
from django.utils import timezone
from django.contrib.auth import get_user_model
from rest_framework.test import APITestCase
from rest_framework import status

from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, AssignmentType, DutyOverride, DutyOverrideStatus, DutyOverrideType, Attendance
from apps.shifts.services import resolve_guard_duty, resolve_incoming_guard
from apps.leave.models import LeaveApplication, LeaveType, LeaveStatus, PublicHolidayCompensationLedger
from apps.patrols.models import PatrolLog, PatrolStatus, Checkpoint

User = get_user_model()


class AuthoritativeDutyReassignmentTests(APITestCase):
    def setUp(self):
        self.today = timezone.localdate()
        self.tomorrow = self.today + timedelta(days=1)
        self.day_after = self.today + timedelta(days=2)

        self.mock_now = timezone.make_aware(datetime.combine(self.today, time(10, 0)))
        self.patcher = unittest.mock.patch("django.utils.timezone.now", return_value=self.mock_now)
        self.patcher.start()

        self.station = Station.objects.create(
            name="Main Security Headquarters",
            code="MSHQ",
            latitude=-17.824858,
            longitude=31.053028,
            geofence_radius_meters=200.0,
            is_active=True,
        )

        self.admin = User.objects.create_user(
            username="admin_super",
            password="Password123!",
            role=UserRole.ADMINISTRATOR,
            employee_number="ADM-999",
        )
        self.supervisor = User.objects.create_user(
            username="sup_hq",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station,
            employee_number="SUP-101",
        )
        self.guard_a = User.objects.create_user(
            username="guard_alpha",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station,
            employee_number="GRD-001",
            first_name="Alpha",
            last_name="Officer",
        )
        self.guard_b = User.objects.create_user(
            username="guard_bravo",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station,
            employee_number="GRD-002",
            first_name="Bravo",
            last_name="Officer",
        )

        self.pair = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_a,
            guard_b=self.guard_b,
            rotation_order=1,
            is_active=True,
        )

        # Guard A has approved leave for today through tomorrow
        self.leave = LeaveApplication.objects.create(
            guard=self.guard_a,
            leave_type=LeaveType.ANNUAL,
            start_date=self.today,
            end_date=self.tomorrow,
            reason="Approved annual rest period",
            status=LeaveStatus.APPROVED,
        )

        # Baseline: Before reassignment, resolve_guard_duty returns ON_LEAVE
        base_state = resolve_guard_duty(self.guard_a, date=self.today)
        self.assertEqual(base_state["duty_state"], "ON_LEAVE")
        self.assertTrue(base_state["is_on_leave"])
        self.assertFalse(base_state["clock_in_enabled"])

    def tearDown(self):
        self.patcher.stop()

    # 1 & 2. Guard on leave can be reassigned & creates authoritative duty event
    def test_01_and_02_guard_on_leave_can_be_reassigned_via_duty_override(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "override_type": "LEAVE_INTERRUPTION",
            "reason": "Emergency station reinforcement for VIP summit",
        }
        resp = self.client.post("/shifts/duty-overrides/", payload, format="json")
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED, resp.data)
        self.assertEqual(resp.data["status"], "ACTIVE")
        self.assertEqual(str(resp.data["guard"]), str(self.guard_a.id))

        # Verifies DutyOverride created in database
        override = DutyOverride.objects.get(id=resp.data["id"])
        self.assertEqual(override.status, DutyOverrideStatus.ACTIVE)
        self.assertEqual(override.original_leave_id, self.leave.id)
        self.assertIsNotNone(override.shift_created)
        self.assertEqual(override.shift_created.assignment_type, AssignmentType.RELIEF)
        self.assertTrue(override.shift_created.is_override)

    # 3. Effective duty state becomes ON DUTY / REASSIGNED
    def test_03_effective_duty_state_becomes_reassigned(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Critical coverage requirement",
        }
        self.client.post("/shifts/duty-overrides/", payload, format="json")

        resolved = resolve_guard_duty(self.guard_a, date=self.today, current_time=time(8, 0))
        self.assertEqual(resolved["duty_state"], "REASSIGNED")
        self.assertFalse(resolved["is_on_leave"])
        self.assertTrue(resolved["is_eligible_for_duty"])
        self.assertTrue(resolved["clock_in_enabled"])

    # 4. Guard API returns the new state
    def test_04_guard_api_returns_new_authoritative_state(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Replacement coverage assignment",
        }
        self.client.post("/shifts/duty-overrides/", payload, format="json")

        # Now authenticate as Guard A
        self.client.force_authenticate(user=self.guard_a)

        # GET /shifts/shifts/today/
        resp_today = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp_today.status_code, status.HTTP_200_OK)
        self.assertIsNotNone(resp_today.data.get("id"))
        self.assertEqual(resp_today.data["duty_state"], "REASSIGNED")
        self.assertFalse(resp_today.data["is_on_leave"])
        self.assertTrue(resp_today.data["is_override"])

        # GET /shifts/shifts/duty_state/
        resp_state = self.client.get("/shifts/shifts/duty_state/")
        self.assertEqual(resp_state.status_code, status.HTTP_200_OK)
        self.assertEqual(resp_state.data["duty_state"], "REASSIGNED")
        self.assertFalse(resp_state.data["is_on_leave"])
        self.assertIsNotNone(resp_state.data["shift"])

    # 5 & 6. Supervisor API and Admin API return the new state
    def test_05_and_06_supervisor_and_admin_api_return_new_state(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Administrative emergency coverage",
        }
        self.client.post("/shifts/duty-overrides/", payload, format="json")

        # Supervisor queries shift
        self.client.force_authenticate(user=self.supervisor)
        resp_sup = self.client.get(f"/shifts/shifts/today/?guard={self.guard_a.id}")
        self.assertEqual(resp_sup.status_code, status.HTTP_200_OK)
        self.assertEqual(resp_sup.data["duty_state"], "REASSIGNED")
        self.assertIsNone(resp_sup.data["leave_type"])

        # Admin queries shift
        self.client.force_authenticate(user=self.admin)
        resp_adm = self.client.get(f"/shifts/shifts/today/?guard={self.guard_a.id}")
        self.assertEqual(resp_adm.status_code, status.HTTP_200_OK)
        self.assertEqual(resp_adm.data["duty_state"], "REASSIGNED")

    # 7. Original leave remains in history / audit
    def test_07_original_leave_remains_in_database_and_auditable(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Leave interruption audit retention",
        }
        self.client.post("/shifts/duty-overrides/", payload, format="json")

        # Leave application still exists and remains approved
        leave_after = LeaveApplication.objects.get(id=self.leave.id)
        self.assertEqual(leave_after.status, LeaveStatus.APPROVED)
        self.assertEqual(leave_after.start_date, self.today)
        self.assertEqual(leave_after.end_date, self.tomorrow)

        # Override references the original leave
        override = DutyOverride.objects.get(guard=self.guard_a, date=self.today, status=DutyOverrideStatus.ACTIVE)
        self.assertEqual(override.original_leave_id, self.leave.id)

    # 8. Duplicate shifts are not created
    def test_08_duplicate_shifts_are_not_created(self):
        # Create an existing scheduled OFF shift on today
        Shift.objects.create(
            guard=self.guard_a,
            station=self.station,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.OFF,
            assignment_type=AssignmentType.TIME_OFF,
        )
        self.assertEqual(Shift.objects.filter(guard=self.guard_a, date=self.today).count(), 1)

        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Off-day call-in",
        }
        resp = self.client.post("/shifts/duty-overrides/", payload, format="json")
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)

        # Must have exactly 1 shift on this date, NOT duplicate
        total_shifts = Shift.objects.filter(guard=self.guard_a, date=self.today).count()
        self.assertEqual(total_shifts, 1)
        shift = Shift.objects.get(guard=self.guard_a, date=self.today)
        self.assertEqual(shift.shift_type, ShiftType.DAY)
        self.assertEqual(shift.assignment_type, AssignmentType.RELIEF)

    # 9. Two conflicting active duties cannot be created accidentally
    def test_09_conflicting_active_duties_cannot_be_created(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "First override",
        }
        resp1 = self.client.post("/shifts/duty-overrides/", payload, format="json")
        self.assertEqual(resp1.status_code, status.HTTP_201_CREATED)

        # Second override for same guard on same date must be rejected
        payload2 = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "NIGHT",
            "reason": "Conflicting second override",
        }
        resp2 = self.client.post("/shifts/duty-overrides/", payload2, format="json")
        self.assertEqual(resp2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("already exists", str(resp2.data))

    # 10. Attendance / clock-in eligibility reflects the new duty state
    def test_10_attendance_clock_in_eligibility_and_execution(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Emergency recall coverage",
        }
        resp = self.client.post("/shifts/duty-overrides/", payload, format="json")
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)

        override = DutyOverride.objects.get(id=resp.data["id"])
        shift = override.shift_created

        # Authenticate as Guard A and clock in
        self.client.force_authenticate(user=self.guard_a)
        clock_payload = {
            "shift_id": str(shift.id),
            "latitude": self.station.latitude,
            "longitude": self.station.longitude,
        }
        clock_resp = self.client.post("/shifts/attendance/clock_in/", clock_payload, format="json")
        self.assertEqual(clock_resp.status_code, status.HTTP_200_OK, clock_resp.data)

        # After clocking in, duty state must be ON_DUTY
        resolved = resolve_guard_duty(self.guard_a, date=self.today)
        self.assertEqual(resolved["duty_state"], "ON_DUTY")
        self.assertTrue(resolved["is_on_duty"])
        self.assertFalse(resolved["is_off_duty"])
        self.assertFalse(resolved["clock_in_enabled"])
        self.assertTrue(resolved["clock_out_enabled"])

    # 11. Patrol eligibility reflects the new duty state
    def test_11_patrol_eligibility_reflects_new_duty_state(self):
        # 1. Override duty for Guard A
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Patrol reinforcement",
        }
        resp = self.client.post("/shifts/duty-overrides/", payload, format="json")
        shift = DutyOverride.objects.get(id=resp.data["id"]).shift_created

        # 2. Clock in Guard A
        Attendance.objects.create(
            shift=shift,
            guard=self.guard_a,
            clock_in=timezone.now(),
            clock_in_gps=f"{self.station.latitude},{self.station.longitude}",
        )

        # 3. Supervisor assigns a patrol to Guard A
        patrol = PatrolLog.objects.create(
            station=self.station,
            guard=self.guard_a,
            assigned_by=self.supervisor,
            name="Alpha Perimeter Patrol",
            status=PatrolStatus.ASSIGNED,
        )

        # 4. Guard A starts the patrol
        self.client.force_authenticate(user=self.guard_a)
        start_resp = self.client.post(f"/patrols/logs/{patrol.id}/start/", format="json")
        self.assertEqual(start_resp.status_code, status.HTTP_200_OK, start_resp.data)
        patrol.refresh_from_db()
        self.assertEqual(patrol.status, PatrolStatus.IN_PROGRESS)

    # 12. Handover eligibility reflects the new duty state
    def test_12_handover_eligibility_reflects_new_duty_state(self):
        # Outgoing guard is Guard B on DAY shift
        outgoing_shift = Shift.objects.create(
            guard=self.guard_b,
            station=self.station,
            date=self.today,
            start_time=time(6, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )

        # Guard A was on leave, but reassigned to NIGHT shift on today
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "NIGHT",
            "reason": "Handover night coverage",
        }
        self.client.post("/shifts/duty-overrides/", payload, format="json")

        # Authoritative server incoming guard resolution
        incoming_guard = resolve_incoming_guard(outgoing_shift)
        self.assertIsNotNone(incoming_guard)
        self.assertEqual(incoming_guard.id, self.guard_a.id)

    # 13. Compensation remains correctly recorded for leave interruption
    def test_13_compensation_recorded_for_leave_interruption(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Statutory holiday coverage",
        }
        resp = self.client.post("/shifts/duty-overrides/", payload, format="json")
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)

        override = DutyOverride.objects.get(id=resp.data["id"])
        self.assertEqual(float(override.compensation_days_owed), 1.0)
        self.assertFalse(override.compensation_settled)

        # Check ledger
        ledger = PublicHolidayCompensationLedger.objects.filter(duty_override=override, entry_type="EARNED").first()
        self.assertIsNotNone(ledger)
        self.assertEqual(float(ledger.days), 1.0)
        self.assertEqual(ledger.guard_id, self.guard_a.id)

    # 14. Settling compensation does not restore old leave state while operationally assigned
    def test_14_settling_compensation_does_not_restore_leave_state(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Coverage compensation test",
        }
        create_resp = self.client.post("/shifts/duty-overrides/", payload, format="json")
        override_id = create_resp.data["id"]

        # Settle compensation
        settle_resp = self.client.post(f"/shifts/duty-overrides/{override_id}/settle_compensation/")
        self.assertEqual(settle_resp.status_code, status.HTTP_200_OK)

        override = DutyOverride.objects.get(id=override_id)
        self.assertTrue(override.compensation_settled)
        self.assertEqual(override.status, DutyOverrideStatus.ACTIVE)

        # Duty state remains REASSIGNED / active duty, NOT restored to ON_LEAVE
        resolved = resolve_guard_duty(self.guard_a, date=self.today, current_time=time(8, 0))
        self.assertEqual(resolved["duty_state"], "REASSIGNED")
        self.assertFalse(resolved["is_on_leave"])
        self.assertTrue(resolved["clock_in_enabled"])

    # 15. Once the reassignment period ends, normal roster behaviour resumes correctly
    def test_15_normal_roster_behaviour_resumes_after_reassignment(self):
        self.client.force_authenticate(user=self.supervisor)
        payload = {
            "guard": str(self.guard_a.id),
            "station": str(self.station.id),
            "date": self.today.isoformat(),
            "shift_type": "DAY",
            "reason": "Single day emergency recall",
        }
        self.client.post("/shifts/duty-overrides/", payload, format="json")

        # Today: Reassigned
        resolved_today = resolve_guard_duty(self.guard_a, date=self.today, current_time=time(8, 0))
        self.assertEqual(resolved_today["duty_state"], "REASSIGNED")
        self.assertFalse(resolved_today["is_on_leave"])

        # Tomorrow: No override on tomorrow, so approved leave is respected!
        resolved_tomorrow = resolve_guard_duty(self.guard_a, date=self.tomorrow)
        self.assertEqual(resolved_tomorrow["duty_state"], "ON_LEAVE")
        self.assertTrue(resolved_tomorrow["is_on_leave"])

        # Day after tomorrow: Leave has ended, scheduled shifts operate normally
        Shift.objects.create(
            guard=self.guard_a,
            station=self.station,
            date=self.day_after,
            start_time=time(6, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        resolved_day_after = resolve_guard_duty(self.guard_a, date=self.day_after, current_time=time(8, 0))
        self.assertEqual(resolved_day_after["duty_state"], "ELIGIBLE_FOR_DUTY")
        self.assertFalse(resolved_day_after["is_on_leave"])

    # Additional verification: Reassignment via reassign_single endpoint
    def test_16_reassign_single_shift_recalls_guard_on_leave(self):
        # Create a shift for Guard B on today
        shift_b = Shift.objects.create(
            guard=self.guard_b,
            station=self.station,
            date=self.today,
            start_time=time(6, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )

        # Supervisor reassigns shift_b to Guard A (who is on leave)
        self.client.force_authenticate(user=self.supervisor)
        reassign_payload = {
            "guard_id": str(self.guard_a.id),
            "station_id": str(self.station.id),
            "reason": "Emergency relief replacement",
            "allow_leave_interruption": True,
        }
        reassign_resp = self.client.post(f"/shifts/shifts/{shift_b.id}/reassign/", reassign_payload, format="json")
        self.assertEqual(reassign_resp.status_code, status.HTTP_200_OK, reassign_resp.data)

        # Guard A now has the shift with is_override=True
        shift_b.refresh_from_db()
        self.assertEqual(shift_b.guard_id, self.guard_a.id)
        self.assertTrue(shift_b.is_override)

        # Guard A duty state is REASSIGNED, not ON_LEAVE
        resolved = resolve_guard_duty(self.guard_a, date=self.today, current_time=time(8, 0))
        self.assertEqual(resolved["duty_state"], "REASSIGNED")
        self.assertFalse(resolved["is_on_leave"])
        self.assertTrue(resolved["clock_in_enabled"])
