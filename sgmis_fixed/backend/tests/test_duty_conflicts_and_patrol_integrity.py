import io
from datetime import date, time, timedelta, datetime
from django.utils import timezone
from django.test import TestCase
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.exceptions import ValidationError
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
    ShiftHandover,
)
from apps.shifts.services import validate_guard_duty_availability
from apps.exams.models import ExamDuty
from apps.escorts.models import EscortDuty
from apps.leave.models import LeaveApplication, LeaveType, LeaveStatus
from apps.patrols.models import PatrolLog, PatrolStatus, Checkpoint, CheckpointScan
from apps.notifications.models import Notification


class DutyConflictsAndPatrolIntegrityTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.today = timezone.localdate()
        self.tomorrow = self.today + timedelta(days=1)
        self.future_date = self.today + timedelta(days=7)

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
            name="Bravo Station",
            code="BRV01",
            latitude=-17.83,
            longitude=31.06,
            geofence_radius_meters=300.0,
            is_active=True,
        )

        # Users
        self.admin = User.objects.create_superuser(
            username="admin_user",
            password="AdminPassword123!",
            email="admin@example.com",
            role=UserRole.ADMINISTRATOR,
            employee_number="ADM001",
        )

        self.supervisor = User.objects.create_user(
            username="sup_alpha",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station1,
            employee_number="SUP001",
            first_name="Alpha",
            last_name="Supervisor",
        )

        self.guard_a = User.objects.create_user(
            username="guard_a",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station1,
            employee_number="GRD001",
            first_name="Alice",
            last_name="Guard",
        )

        self.guard_b = User.objects.create_user(
            username="guard_b",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station1,
            employee_number="GRD002",
            first_name="Bob",
            last_name="Guard",
        )

        self.guard_c = User.objects.create_user(
            username="guard_c",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station2,
            employee_number="GRD003",
            first_name="Charlie",
            last_name="Guard",
        )

        # Rosters
        self.roster1 = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.future_date + timedelta(days=7),
            status=RosterStatus.APPROVED,
        )

    # =========================================================================
    # 1. NORMAL + EXAM same date = rejected (today)
    # =========================================================================
    def test_01_normal_vs_exam_same_date_rejected(self):
        Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post("/api/exams/duties/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station1.id),
            "date": str(self.today),
            "institution": "University Campus",
            "exam_title": "Security 101",
            "start_time": "08:00:00",
            "end_time": "12:00:00",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("NORMAL", str(resp.data).upper())

    # =========================================================================
    # 2. NORMAL + ESCORT same date = rejected (today)
    # =========================================================================
    def test_02_normal_vs_escort_same_date_rejected(self):
        Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.supervisor)
        start_dt = timezone.now()
        end_dt = start_dt + timedelta(hours=4)
        resp = self.client.post("/api/escorts/duties/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station1.id),
            "mission_name": "Cash Transit",
            "start_time": start_dt.isoformat(),
            "end_time": end_dt.isoformat(),
            "origin": "Main Gate",
            "destination": "Treasury",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("NORMAL", str(resp.data).upper())

    # =========================================================================
    # 3. EXAM + NORMAL same date = rejected
    # =========================================================================
    def test_03_exam_vs_normal_same_date_rejected(self):
        ExamDuty.objects.create(
            guard=self.guard_a,
            date=self.today,
            institution="University Campus",
            exam_title="Law 201",
            start_time=time(8, 0),
            end_time=time(12, 0),
            status="ASSIGNED",
        )
        shift = Shift(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        with self.assertRaises(ValidationError) as ctx:
            shift.clean()
        self.assertIn("EXAM", str(ctx.exception).upper())

    # =========================================================================
    # 4. ESCORT + NORMAL same date = rejected
    # =========================================================================
    def test_04_escort_vs_normal_same_date_rejected(self):
        now = timezone.now()
        EscortDuty.objects.create(
            guard=self.guard_a,
            mission_name="Depot Patrol",
            start_time=now,
            end_time=now + timedelta(hours=4),
            origin="Depot",
            destination="Campus",
            status="SCHEDULED",
        )
        shift = Shift(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        with self.assertRaises(ValidationError) as ctx:
            shift.clean()
        self.assertIn("ESCORT", str(ctx.exception).upper())

    # =========================================================================
    # 5. Overlapping EXAM + ESCORT = rejected
    # =========================================================================
    def test_05_overlapping_exam_and_escort_rejected(self):
        ExamDuty.objects.create(
            guard=self.guard_a,
            date=self.today,
            institution="University Campus",
            exam_title="Security 301",
            start_time=time(8, 0),
            end_time=time(12, 0),
            status="ASSIGNED",
        )
        self.client.force_authenticate(user=self.supervisor)
        start_dt = timezone.make_aware(
            datetime.combine(self.today, time(11, 0)),
            timezone.get_current_timezone()
        )
        end_dt = timezone.make_aware(
            datetime.combine(self.today, time(15, 0)),
            timezone.get_current_timezone()
        )
        resp = self.client.post("/api/escorts/duties/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station1.id),
            "mission_name": "Overlapping Escort",
            "start_time": start_dt.isoformat(),
            "end_time": end_dt.isoformat(),
            "origin": "Hall C",
            "destination": "Main Gate",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("OVERLAP", str(resp.data).upper())

    # =========================================================================
    # 6. Future NORMAL + future EXAM = rejected
    # =========================================================================
    def test_06_future_normal_blocks_future_exam(self):
        Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.future_date,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post("/api/exams/duties/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station1.id),
            "date": str(self.future_date),
            "institution": "University Campus",
            "exam_title": "Security 401",
            "start_time": "08:00:00",
            "end_time": "12:00:00",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("NORMAL", str(resp.data).upper())

    # =========================================================================
    # 7. Future NORMAL + future ESCORT = rejected
    # =========================================================================
    def test_07_future_normal_blocks_future_escort(self):
        Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.future_date,
            shift_type=ShiftType.NIGHT,
            start_time=time(18, 0),
            end_time=time(6, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.supervisor)
        start_dt = timezone.make_aware(
            datetime.combine(self.future_date, time(9, 0)),
            timezone.get_current_timezone()
        )
        end_dt = timezone.make_aware(
            datetime.combine(self.future_date, time(13, 0)),
            timezone.get_current_timezone()
        )
        resp = self.client.post("/api/escorts/duties/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station1.id),
            "mission_name": "Perimeter Escort",
            "start_time": start_dt.isoformat(),
            "end_time": end_dt.isoformat(),
            "origin": "Perimeter",
            "destination": "Gate 2",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("NORMAL", str(resp.data).upper())

    # =========================================================================
    # 8. Non-overlapping duties permitted
    # =========================================================================
    def test_08_non_overlapping_duties_permitted(self):
        start_dt = timezone.make_aware(
            datetime.combine(self.today, time(8, 0)),
            timezone.get_current_timezone()
        )
        end_dt = timezone.make_aware(
            datetime.combine(self.today, time(11, 0)),
            timezone.get_current_timezone()
        )
        EscortDuty.objects.create(
            guard=self.guard_a,
            mission_name="Morning Escort",
            start_time=start_dt,
            end_time=end_dt,
            origin="Station A",
            destination="Station B",
            status="SCHEDULED",
        )
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post("/api/exams/duties/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station1.id),
            "date": str(self.today),
            "institution": "University Campus",
            "exam_title": "Afternoon Exam",
            "start_time": "13:00:00",
            "end_time": "17:00:00",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)

    # =========================================================================
    # 9. Approved leave blocks active duty
    # =========================================================================
    def test_09_approved_leave_blocks_active_duty(self):
        LeaveApplication.objects.create(
            guard=self.guard_a,
            leave_type=LeaveType.ANNUAL,
            start_date=self.today,
            end_date=self.today + timedelta(days=3),
            reason="Vacation",
            status=LeaveStatus.APPROVED,
        )
        # 1. Normal shift rejected
        shift = Shift(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        with self.assertRaises(ValidationError) as ctx:
            shift.clean()
        self.assertIn("LEAVE", str(ctx.exception).upper())

        # 2. Exam duty rejected
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post("/api/exams/duties/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station1.id),
            "date": str(self.today),
            "institution": "University Campus",
            "exam_title": "Security 999",
            "start_time": "08:00:00",
            "end_time": "12:00:00",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("LEAVE", str(resp.data).upper())

    # =========================================================================
    # 10. Valid relief shift creation succeeds
    # =========================================================================
    def test_10_valid_relief_shift_creation_succeeds(self):
        self.guard_a.is_relief = True
        self.guard_a.save()
        shift = Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.RELIEF,
        )
        self.assertIsNotNone(shift.id)
        self.assertEqual(shift.assignment_type, AssignmentType.RELIEF)

    # =========================================================================
    # 11. Handover notification delivered only to incoming guard
    # =========================================================================
    def test_11_handover_notification_delivered_only_to_incoming_guard(self):
        shift = Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_b,
            date=self.today,
            shift_type=ShiftType.NIGHT,
            start_time=time(18, 0),
            end_time=time(6, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/api/shifts/handovers/", {
            "outgoing_shift": str(shift.id),
            "incoming_guard": str(self.guard_b.id),
            "station": str(self.station1.id),
            "occurrence_summary": "All quiet on station Alpha.",
            "equipment_issued": "Radio, torch, keys.",
            "keys_handed_over": "Master keys 1-4.",
            "pending_issues": "None.",
            "outgoing_signed": True,
            "supervisor_emergency_override": True,
            "supervisor_id": str(self.supervisor.id),
            "override_reason": "Testing handover dispatch",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)

        # Incoming guard must receive 1 takeover notification
        incoming_notifs = Notification.objects.filter(user=self.guard_b)
        self.assertEqual(incoming_notifs.count(), 1)
        self.assertIn("HANDOVER", incoming_notifs.first().title.upper())

    # =========================================================================
    # 12. Outgoing & unrelated guards do not receive incoming takeover alert
    # =========================================================================
    def test_12_unrelated_guards_do_not_receive_incoming_takeover_alert(self):
        shift = Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_b,
            date=self.today,
            shift_type=ShiftType.NIGHT,
            start_time=time(18, 0),
            end_time=time(6, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.guard_a)
        self.client.post("/api/shifts/handovers/", {
            "outgoing_shift": str(shift.id),
            "incoming_guard": str(self.guard_b.id),
            "station": str(self.station1.id),
            "occurrence_summary": "All quiet.",
            "equipment_issued": "Standard kit.",
            "keys_handed_over": "Gate keys.",
            "pending_issues": "None.",
            "outgoing_signed": True,
            "supervisor_emergency_override": True,
            "supervisor_id": str(self.supervisor.id),
            "override_reason": "Testing handover dispatch",
        })
        # Outgoing guard does not receive the incoming takeover alert
        self.assertEqual(Notification.objects.filter(user=self.guard_a).count(), 0)
        # Unrelated guard does not receive any notification
        self.assertEqual(Notification.objects.filter(user=self.guard_c).count(), 0)

    # =========================================================================
    # 13. Patrol cannot instantly complete (< 60s rejected)
    # =========================================================================
    def test_13_patrol_cannot_instantly_complete(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station1,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Fast completed patrol.",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("60 SECONDS", str(resp.data).upper())

    # =========================================================================
    # 14. Patrol cannot be completed twice
    # =========================================================================
    def test_14_patrol_cannot_be_completed_twice(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station1,
            status=PatrolStatus.COMPLETED,
            end_time=timezone.now(),
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Try finishing again.",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("ALREADY COMPLETED", str(resp.data).upper())

    # =========================================================================
    # 15. Patrol completion validates correct guard
    # =========================================================================
    def test_15_patrol_completion_validates_correct_guard(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station1,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_b)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Bob attempting to finish Alice's patrol.",
        })
        self.assertIn(resp.status_code, [status.HTTP_403_FORBIDDEN, status.HTTP_404_NOT_FOUND])

    # =========================================================================
    # 16. Patrol completion verifies required station checkpoints
    # =========================================================================
    def test_16_patrol_completion_verifies_required_station_checkpoints(self):
        cp1 = Checkpoint.objects.create(
            station=self.station1,
            name="North Gate",
            code="CP_N1",
            order=1,
            is_active=True,
        )
        cp2 = Checkpoint.objects.create(
            station=self.station1,
            name="South Gate",
            code="CP_S1",
            order=2,
            is_active=True,
        )
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station1,
            status=PatrolStatus.IN_PROGRESS,
        )
        # Backdate patrol start time by 2 minutes
        PatrolLog.objects.filter(id=patrol.id).update(
            start_time=timezone.now() - timedelta(minutes=2)
        )
        self.client.force_authenticate(user=self.guard_a)

        # 1. Finishing with 0 scans fails because checkpoints exist
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Finishing without scans.",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertTrue(
            "NOT BEEN SCANNED" in str(resp.data).upper() or "CHECKPOINT" in str(resp.data).upper()
        )

        # 2. Scan only cp1 - still fails cp2 missing
        CheckpointScan.objects.create(patrol_log=patrol, checkpoint=cp1)
        resp2 = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Finishing with only 1 scan.",
        })
        self.assertEqual(resp2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("SOUTH GATE", str(resp2.data).upper())

        # 3. Scan cp2 - now succeeds
        CheckpointScan.objects.create(patrol_log=patrol, checkpoint=cp2)
        resp3 = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Finishing with all scans complete.",
        })
        self.assertEqual(resp3.status_code, status.HTTP_200_OK)
        patrol.refresh_from_db()
        self.assertEqual(patrol.status, PatrolStatus.COMPLETED)

    # =========================================================================
    # 17. Profile photo upload succeeds and returns valid User
    # =========================================================================
    def test_17_profile_photo_upload_succeeds_and_returns_valid_user(self):
        gif_bytes = (
            b"GIF89a\x01\x00\x01\x00\x80\x00\x00\xff\xff\xff\x00\x00\x00!\xf9\x04"
            b"\x01\x00\x00\x00\x00,\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00;"
        )
        photo_file = SimpleUploadedFile("avatar.gif", gif_bytes, content_type="image/gif")

        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(
            "/api/accounts/users/me/photo/",
            {"photo": photo_file},
            format="multipart",
        )
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        # Must contain top-level User fields for Android Retrofit Response<User>
        self.assertIn("id", resp.data)
        self.assertIn("username", resp.data)
        self.assertIn("role", resp.data)
        self.assertIn("profile_photo", resp.data)
        self.assertEqual(resp.data["username"], "guard_a")

    # =========================================================================
    # 18. Protected profile fields remain read-only
    # =========================================================================
    def test_18_protected_profile_fields_remain_read_only(self):
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.patch("/api/accounts/users/me/", {
            "role": "ADMINISTRATOR",
            "is_staff": True,
            "is_superuser": True,
            "employee_number": "HAX007",
        })
        self.guard_a.refresh_from_db()
        self.assertEqual(self.guard_a.role, UserRole.GUARD)
        self.assertFalse(self.guard_a.is_staff)
        self.assertFalse(self.guard_a.is_superuser)
        self.assertEqual(self.guard_a.employee_number, "GRD001")

    # =========================================================================
    # 19. Guard feature consistency across multiple guards
    # =========================================================================
    def test_19_guard_feature_consistency_across_multiple_guards(self):
        for guard in [self.guard_a, self.guard_b, self.guard_c]:
            self.client.force_authenticate(user=guard)
            resp_profile = self.client.get("/api/accounts/users/me/")
            self.assertEqual(resp_profile.status_code, status.HTTP_200_OK)
            resp_notifs = self.client.get("/api/notifications/")
            self.assertEqual(resp_notifs.status_code, status.HTTP_200_OK)
            resp_shifts = self.client.get("/api/shifts/shifts/")
            self.assertEqual(resp_shifts.status_code, status.HTTP_200_OK)

    # =========================================================================
    # 20. Off-duty restrictions on operational logging work
    # =========================================================================
    def test_20_off_duty_restrictions_on_operational_logging(self):
        # Guard A is off-duty (no active attendance clock-in)
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/api/shifts/attendance/clock_out/", {
            "shift_id": "00000000-0000-0000-0000-000000000000",
        })
        self.assertIn(resp.status_code, [status.HTTP_400_BAD_REQUEST, status.HTTP_404_NOT_FOUND])

    # =========================================================================
    # 21. Bounded operational query verification
    # =========================================================================
    def test_21_bounded_operational_query_verification(self):
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.get("/api/shifts/shifts/operational/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIsInstance(resp.data, list)

    # =========================================================================
    # 22. Multi-station assignment protection intact
    # =========================================================================
    def test_22_multi_station_assignment_protection_intact(self):
        # Guard A assigned to station 1
        Shift.objects.create(
            roster=self.roster1,
            station=self.station1,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        # Attempting to assign Guard A to station 2 on same date
        roster2 = DutyRoster.objects.create(
            station=self.station2,
            start_date=self.today,
            end_date=self.future_date,
            status=RosterStatus.APPROVED,
        )
        conflicting_shift = Shift(
            roster=roster2,
            station=self.station2,
            guard=self.guard_a,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
            assignment_type=AssignmentType.NORMAL,
        )
        with self.assertRaises(ValidationError) as ctx:
            conflicting_shift.clean()
        self.assertTrue(
            "STATION" in str(ctx.exception).upper() or "ANOTHER SHIFT" in str(ctx.exception).upper()
        )
