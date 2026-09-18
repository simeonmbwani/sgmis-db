import uuid
from datetime import date, datetime, time, timedelta
from django.utils import timezone
from rest_framework import status
from rest_framework.test import APITestCase
from apps.accounts.models import User, UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, AssignmentType, Attendance
from apps.shifts.services import generate_roster_for_station
from apps.core.models import SupervisorOverrideAudit, SecurityAuditEvent

class AuthoritativeAttendanceSecurityTests(APITestCase):
    def setUp(self):
        self.station = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-MC-001",
            latitude=1.2921,
            longitude=36.8219,
            geofence_radius_meters=100.0,
        )
        self.supervisor = User.objects.create_user(
            username="test_supervisor",
            password="SupervisorPassword123!",
            role=UserRole.SUPERVISOR,
            station=self.station,
            first_name="Super",
            last_name="Visor",
        )
        self.admin_user = User.objects.create_user(
            username="test_admin",
            password="AdminPassword123!",
            role=UserRole.ADMINISTRATOR,
            first_name="Admin",
            last_name="Chief",
        )
        self.guard_1 = User.objects.create_user(
            username="guard_alpha",
            password="GuardPassword123!",
            role=UserRole.GUARD,
            station=self.station,
            first_name="Alpha",
            last_name="One",
            employee_number="SEC-001",
        )
        self.guard_2 = User.objects.create_user(
            username="guard_bravo",
            password="GuardPassword123!",
            role=UserRole.GUARD,
            station=self.station,
            first_name="Bravo",
            last_name="Two",
            employee_number="SEC-002",
        )
        self.other_guard = User.objects.create_user(
            username="guard_charlie",
            password="GuardPassword123!",
            role=UserRole.GUARD,
            station=self.station,
            first_name="Charlie",
            last_name="Three",
            employee_number="SEC-003",
        )
        self.pair = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_1,
            guard_b=self.guard_2,
            rotation_order=1,
            is_active=True,
        )

    def test_guard_cannot_clock_in_for_another_guard(self):
        today = timezone.localdate()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        self.client.force_authenticate(user=self.other_guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": 1.2921,
            "longitude": 36.8219,
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("Proxy actions are strictly prohibited", resp.data["detail"])

    def test_guard_cannot_clock_in_to_time_off_shift(self):
        today = timezone.localdate()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=time(0, 0),
            end_time=time(0, 0),
            shift_type=ShiftType.OFF,
            assignment_type=AssignmentType.TIME_OFF,
            duty_location="Time Off",
        )
        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("TIME OFF", resp.data["detail"])

    def test_guard_cannot_clock_in_to_expired_shift(self):
        yesterday = timezone.localdate() - timedelta(days=2)
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=yesterday,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("Shift expired", resp.data["detail"])

    def test_guard_cannot_clock_in_too_early(self):
        today = timezone.localdate()
        future_time = (timezone.localtime() + timedelta(hours=2)).time()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=future_time,
            end_time=time(23, 59),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("Duty time not reached", resp.data["detail"])

    def test_duplicate_clock_in_rejected(self):
        today = timezone.localdate()
        active_time = (timezone.localtime() - timedelta(minutes=5)).time()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=active_time,
            end_time=(timezone.localtime() + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        self.client.force_authenticate(user=self.guard_1)
        resp1 = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": 1.2921,
            "longitude": 36.8219,
        })
        self.assertEqual(resp1.status_code, status.HTTP_200_OK)

        resp2 = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": 1.2921,
            "longitude": 36.8219,
        })
        self.assertEqual(resp2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("already clocked in", resp2.data["detail"])

    def test_guard_early_clock_out_requires_supervisor_credentials(self):
        today = timezone.localdate()
        active_time = (timezone.localtime() - timedelta(minutes=10)).time()
        end_time = (timezone.localtime() + timedelta(hours=5)).time()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=active_time,
            end_time=end_time,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        att = Attendance.objects.create(
            shift=shift,
            guard=self.guard_1,
            clock_in=timezone.now() - timedelta(minutes=10),
        )

        self.client.force_authenticate(user=self.guard_1)

        # 1. Guard tries early clock out with self-declared override - rejected!
        resp_unauth = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "supervisor_emergency_override": True,
            "override_reason": "I want to leave early",
        })
        self.assertEqual(resp_unauth.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("requires authenticated supervisor authorization", resp_unauth.data["detail"])

        # 2. Guard tries with wrong supervisor password - rejected!
        resp_bad_pw = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "supervisor_username": "test_supervisor",
            "supervisor_password": "WrongPassword!",
            "override_reason": "Medical emergency",
        })
        self.assertEqual(resp_bad_pw.status_code, status.HTTP_403_FORBIDDEN)

        # 3. Guard tries with valid supervisor credentials and mandatory reason - succeeds!
        resp_valid = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "latitude": 1.2921,
            "longitude": 36.8219,
            "supervisor_username": "test_supervisor",
            "supervisor_password": "SupervisorPassword123!",
            "override_reason": "Severe acute medical emergency reported on duty post",
        })
        self.assertEqual(resp_valid.status_code, status.HTTP_200_OK)
        self.assertIsNotNone(resp_valid.data["clock_out"])

        # Verify audit records created
        audit = SupervisorOverrideAudit.objects.filter(supervisor=self.supervisor).first()
        self.assertIsNotNone(audit)
        self.assertEqual(audit.action_type, "EARLY_CLOCKOUT_OVERRIDE")
        self.assertIn("medical emergency", audit.reason)

        sec_event = SecurityAuditEvent.objects.filter(event_type=SecurityAuditEvent.EventType.OVERRIDE).first()
        self.assertIsNotNone(sec_event)
        self.assertEqual(sec_event.actor, self.supervisor)

    def test_normal_clock_out_at_scheduled_end_succeeds_without_supervisor(self):
        yesterday = timezone.localdate() - timedelta(days=1)
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=yesterday,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        Attendance.objects.create(
            shift=shift,
            guard=self.guard_1,
            clock_in=timezone.make_aware(datetime.combine(yesterday, time(7, 0))),
        )
        self.client.force_authenticate(user=self.guard_1)
        # Normal clock out at or after scheduled end requires NO supervisor credentials
        resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "latitude": 1.2921,
            "longitude": 36.8219,
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIsNotNone(resp.data["clock_out"])

    def test_early_clock_out_missing_reason_fails(self):
        today = timezone.localdate()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=(timezone.localtime() - timedelta(hours=2)).time(),
            end_time=(timezone.localtime() + timedelta(hours=4)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        Attendance.objects.create(
            shift=shift,
            guard=self.guard_1,
            clock_in=timezone.now() - timedelta(hours=2),
        )
        self.client.force_authenticate(user=self.guard_1)
        # Has supervisor credentials but empty/whitespace reason
        resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "supervisor_username": "test_supervisor",
            "supervisor_password": "SupervisorPassword123!",
            "override_reason": "   ",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("mandatory operational justification reason is required", resp.data["detail"])

    def test_early_clock_out_forged_supervisor_identity_fails(self):
        today = timezone.localdate()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=(timezone.localtime() - timedelta(hours=2)).time(),
            end_time=(timezone.localtime() + timedelta(hours=4)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        Attendance.objects.create(
            shift=shift,
            guard=self.guard_1,
            clock_in=timezone.now() - timedelta(hours=2),
        )
        self.client.force_authenticate(user=self.guard_1)
        # Guard tries to use another guard's credentials as supervisor
        resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "supervisor_username": "guard_bravo",
            "supervisor_password": "GuardPassword123!",
            "override_reason": "Medical issue",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("Invalid supervisor credentials", resp.data["detail"])

    def test_supervisor_password_never_appears_in_audit_records(self):
        today = timezone.localdate()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=(timezone.localtime() - timedelta(hours=2)).time(),
            end_time=(timezone.localtime() + timedelta(hours=4)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus",
        )
        Attendance.objects.create(
            shift=shift,
            guard=self.guard_1,
            clock_in=timezone.now() - timedelta(hours=2),
        )
        self.client.force_authenticate(user=self.guard_1)
        secret_password = "SupervisorPassword123!"
        resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "latitude": 1.2921,
            "longitude": 36.8219,
            "supervisor_username": "test_supervisor",
            "supervisor_password": secret_password,
            "override_reason": "Emergency post relocation",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        # Audit check: password must not be present in SupervisorOverrideAudit or SecurityAuditEvent
        override_audit = SupervisorOverrideAudit.objects.filter(supervisor=self.supervisor).last()
        self.assertNotIn(secret_password, override_audit.reason)
        self.assertNotIn(secret_password, str(override_audit.__dict__))

        sec_audit = SecurityAuditEvent.objects.filter(actor=self.supervisor).last()
        self.assertNotIn(secret_password, str(sec_audit.details))

    def test_today_endpoint_returns_off_duty_for_time_off(self):
        today = timezone.localdate()
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=today,
            start_time=time(0, 0),
            end_time=time(0, 0),
            shift_type=ShiftType.OFF,
            assignment_type=AssignmentType.TIME_OFF,
            duty_location="Time Off",
        )
        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["assignment_type"], "TIME_OFF")
        self.assertEqual(resp.data["attendance_status"], "OFF_DUTY")
