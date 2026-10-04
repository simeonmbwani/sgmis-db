import uuid
from datetime import date, time, timedelta, datetime
from django.utils import timezone
from django.test import TestCase
from rest_framework import status
from rest_framework.test import APIClient

from apps.accounts.models import User, UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, AssignmentType, DutyRoster, RosterStatus
from apps.leave.models import LeaveApplication, LeaveType, LeaveStatus
from apps.exams.models import ExamDuty, ExamStatus
from apps.escorts.models import EscortDuty, EscortStatus
from apps.core.models import SupervisorOverrideAudit, SecurityAuditEvent

class DynamicDutyManagementTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.today = timezone.localdate()
        self.tomorrow = self.today + timedelta(days=1)

        # Create Station
        self.station = Station.objects.create(
            name="Alpha Post",
            code="ALP01",
            latitude=-17.82,
            longitude=31.05,
            geofence_radius_meters=300.0,
            is_active=True,
        )

        self.other_station = Station.objects.create(
            name="Beta Post",
            code="BET02",
            latitude=-17.85,
            longitude=31.08,
            is_active=True,
        )

        # Users
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
            station=self.station,
        )

        self.other_supervisor = User.objects.create_user(
            username="sup_beta",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.other_station,
        )

        self.guard_1 = User.objects.create_user(
            username="guard_1",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station,
            employee_number="SG001",
        )

        self.guard_2 = User.objects.create_user(
            username="guard_2",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station,
            employee_number="SG002",
        )

        self.relief_guard = User.objects.create_user(
            username="guard_relief",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station,
            employee_number="SG003",
        )

    # 1. Guard Pair Creation & Assignment (Admin Master control; Supervisor read-only)
    def test_01_guard_pair_creation_and_assignment(self):
        # Admin can create guard pair
        self.client.force_authenticate(user=self.admin)
        resp = self.client.post("/stations/pairs/", {
            "station": str(self.station.id),
            "guard_a": str(self.guard_1.id),
            "guard_b": str(self.guard_2.id),
            "rotation_order": 1,
            "is_active": True,
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertTrue(GuardPair.objects.filter(station=self.station, rotation_order=1).exists())

        # Supervisor cannot create guard pair (403 Forbidden)
        self.client.force_authenticate(user=self.supervisor)
        resp_err = self.client.post("/stations/pairs/", {
            "station": str(self.station.id),
            "guard_a": str(self.guard_1.id),
            "guard_b": str(self.guard_2.id),
            "rotation_order": 2,
        })
        self.assertEqual(resp_err.status_code, status.HTTP_403_FORBIDDEN)

    # 2. Supervisor changing day guard to night guard
    def test_02_supervisor_change_day_guard_to_night_guard(self):
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=self.tomorrow,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post(f"/shifts/shifts/{shift.id}/reassign/", {
            "guard_id": str(self.guard_1.id),
            "shift_type": "NIGHT",
            "reason": "Operational rotation change to Night",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        shift.refresh_from_db()
        self.assertEqual(shift.shift_type, ShiftType.NIGHT)
        self.assertEqual(shift.start_time, time(18, 0))
        self.assertEqual(shift.end_time, time(7, 0))

    # 3. Supervisor changing night guard to day guard
    def test_03_supervisor_change_night_guard_to_day_guard(self):
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_2,
            date=self.tomorrow,
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(18, 0),
            end_time=time(7, 0),
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post(f"/shifts/shifts/{shift.id}/reassign/", {
            "guard_id": str(self.guard_2.id),
            "shift_type": "DAY",
            "reason": "Operational rotation change to Day",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        shift.refresh_from_db()
        self.assertEqual(shift.shift_type, ShiftType.DAY)
        self.assertEqual(shift.start_time, time(7, 0))
        self.assertEqual(shift.end_time, time(18, 0))

    # 4. Supervisor swapping pair guards
    def test_04_supervisor_swap_pair_guards(self):
        pair = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_1,
            guard_b=self.guard_2,
            rotation_order=1,
        )
        day_shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            pair=pair,
            date=self.tomorrow,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        night_shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_2,
            pair=pair,
            date=self.tomorrow,
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(18, 0),
            end_time=time(7, 0),
        )

        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post("/shifts/shifts/swap_pair_duties/", {
            "date": self.tomorrow.isoformat(),
            "pair_id": str(pair.id),
            "reason": "Mutual request approved by Supervisor",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        day_shift.refresh_from_db()
        night_shift.refresh_from_db()

        # Guard 2 should now occupy DAY, Guard 1 should now occupy NIGHT
        self.assertEqual(day_shift.guard, self.guard_2)
        self.assertEqual(night_shift.guard, self.guard_1)

        # Audit must exist
        self.assertTrue(SupervisorOverrideAudit.objects.filter(supervisor=self.supervisor, action_type="PAIR_DUTY_SWAP").exists())

    # 5. Supervisor assigning relief guard to uncovered shift
    def test_05_supervisor_assign_relief_guard_to_uncovered_shift(self):
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=self.tomorrow,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post(f"/shifts/shifts/{shift.id}/reassign/", {
            "guard_id": str(self.relief_guard.id),
            "reason": "Guard 1 unavailable, assigning relief guard",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        shift.refresh_from_db()
        self.assertEqual(shift.guard, self.relief_guard)
        self.assertEqual(shift.assignment_type, AssignmentType.RELIEF)

    # 6. Guard on leave unavailable for shift
    def test_06_guard_on_leave_unavailable_for_shift(self):
        LeaveApplication.objects.create(
            guard=self.guard_2,
            leave_type=LeaveType.VACATION,
            start_date=self.tomorrow,
            end_date=self.tomorrow + timedelta(days=3),
            status=LeaveStatus.APPROVED,
        )
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=self.tomorrow,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post(f"/shifts/shifts/{shift.id}/reassign/", {
            "guard_id": str(self.guard_2.id),
            "reason": "Attempting to assign guard on leave",
        })
        self.assertEqual(resp.status_code, status.HTTP_409_CONFLICT)
        self.assertIn("approved leave", resp.data["detail"])

    # 7. Guard assigned to exam duty unavailable for normal shift
    def test_07_guard_assigned_to_exam_duty_unavailable_for_normal_shift(self):
        ExamDuty.objects.create(
            guard=self.guard_2,
            station=self.station,
            institution="University Hall",
            exam_title="Computer Science Exam",
            date=self.tomorrow,
            start_time=time(8, 0),
            end_time=time(12, 0),
            status=ExamStatus.ASSIGNED,
        )
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=self.tomorrow,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post(f"/shifts/shifts/{shift.id}/reassign/", {
            "guard_id": str(self.guard_2.id),
            "reason": "Attempting to assign guard on exam duty",
        })
        self.assertEqual(resp.status_code, status.HTTP_409_CONFLICT)
        self.assertIn("examination duty", resp.data["detail"])

    # 8. Guard assigned to escort duty unavailable for normal shift
    def test_08_guard_assigned_to_escort_duty_unavailable_for_normal_shift(self):
        EscortDuty.objects.create(
            guard=self.guard_2,
            station=self.station,
            mission_name="VIP Transfer",
            origin="Station Alpha",
            destination="Headquarters",
            start_time=timezone.now() + timedelta(days=1),
            end_time=timezone.now() + timedelta(days=1, hours=4),
            status=EscortStatus.SCHEDULED,
        )
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=self.tomorrow,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post(f"/shifts/shifts/{shift.id}/reassign/", {
            "guard_id": str(self.guard_2.id),
            "reason": "Attempting to assign guard on escort duty",
        })
        self.assertEqual(resp.status_code, status.HTTP_409_CONFLICT)
        self.assertIn("escort duty", resp.data["detail"])

    # 9. Guard app dynamically showing correct state: DAY, NIGHT, OFF, ON LEAVE, EXAM, ESCORT
    def test_09_guard_duty_state_dynamic_evaluation(self):
        # A. OFF_DUTY (no shifts)
        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.get("/shifts/shifts/duty_state/")
        self.assertEqual(resp.data["duty_state"], "OFF_DUTY")

        # B. ON_LEAVE
        leave = LeaveApplication.objects.create(
            guard=self.guard_1,
            leave_type=LeaveType.VACATION,
            start_date=self.today,
            end_date=self.today,
            status=LeaveStatus.APPROVED,
        )
        resp = self.client.get("/shifts/shifts/duty_state/")
        self.assertEqual(resp.data["duty_state"], "ON_LEAVE")
        self.assertEqual(resp.data["leave_type"], LeaveType.VACATION)
        leave.delete()

        # C. EXAM
        exam = ExamDuty.objects.create(
            guard=self.guard_1,
            station=self.station,
            institution="Great Hall",
            exam_title="Law Finals",
            date=self.today,
            start_time=time(9, 0),
            end_time=time(13, 0),
            status=ExamStatus.ASSIGNED,
        )
        resp = self.client.get("/shifts/shifts/duty_state/")
        self.assertEqual(resp.data["duty_state"], "EXAM")
        self.assertIsNotNone(resp.data["exam_duty"])
        self.assertEqual(resp.data["exam_duty"]["exam_title"], "Law Finals")
        exam.delete()

        # D. ESCORT
        escort = EscortDuty.objects.create(
            guard=self.guard_1,
            station=self.station,
            mission_name="Cash Escort",
            origin="Vault",
            destination="Branch 1",
            start_time=timezone.now() - timedelta(minutes=10),
            end_time=timezone.now() + timedelta(hours=2),
            status=EscortStatus.ASSIGNED,
        )
        resp = self.client.get("/shifts/shifts/duty_state/")
        self.assertEqual(resp.data["duty_state"], "ESCORT")
        self.assertIsNotNone(resp.data["escort_duty"])
        self.assertEqual(resp.data["escort_duty"]["mission_name"], "Cash Escort")
        escort.delete()

        # E. DAY shift
        shift_day = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=self.today,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        resp = self.client.get("/shifts/shifts/duty_state/")
        self.assertIn(resp.data["duty_state"], ["ELIGIBLE_FOR_DUTY", "OFF_DUTY"])
        shift_day.delete()

    # 10. Supervisor dashboard showing station coverage warning when shift uncovered
    def test_10_station_coverage_warning_when_shift_uncovered(self):
        self.client.force_authenticate(user=self.supervisor)

        # Case 1: Station completely unmanned (no shifts on date)
        resp = self.client.get(f"/shifts/shifts/station_coverage/?date={self.tomorrow.isoformat()}")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertTrue(resp.data["coverage_warning"])
        self.assertFalse(resp.data["is_day_covered"])
        self.assertFalse(resp.data["is_night_covered"])
        self.assertIn("CRITICAL", resp.data["warning_message"])
        self.assertTrue(len(resp.data["available_relief_guards"]) > 0)

        # Case 2: Only DAY covered, NIGHT uncovered
        day_shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=self.tomorrow,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        resp2 = self.client.get(f"/shifts/shifts/station_coverage/?date={self.tomorrow.isoformat()}")
        self.assertTrue(resp2.data["coverage_warning"])
        self.assertTrue(resp2.data["is_day_covered"])
        self.assertFalse(resp2.data["is_night_covered"])
        self.assertIn("NIGHT SHIFT (18:00 – 07:00) UNCOVERED", resp2.data["warning_message"])

        # Case 3: Both DAY and NIGHT covered
        night_shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_2,
            date=self.tomorrow,
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
            start_time=time(18, 0),
            end_time=time(7, 0),
        )
        resp3 = self.client.get(f"/shifts/shifts/station_coverage/?date={self.tomorrow.isoformat()}")
        self.assertFalse(resp3.data["coverage_warning"])
        self.assertTrue(resp3.data["is_day_covered"])
        self.assertTrue(resp3.data["is_night_covered"])
        self.assertIsNone(resp3.data["warning_message"])
