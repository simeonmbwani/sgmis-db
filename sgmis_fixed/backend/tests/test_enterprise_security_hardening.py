from datetime import datetime, date, time, timedelta
from django.utils import timezone
from django.test import TestCase
from django.core.exceptions import ValidationError
from rest_framework.test import APIClient
from rest_framework import status

from apps.accounts.models import User, UserRole, LoginAttempt, PasswordResetOTP, UserDeactivationAudit
from apps.stations.models import Station
from apps.shifts.models import Shift, ShiftType, Attendance, ShiftHandover
from apps.occurrence_book.models import OccurrenceBookEntry, OBCategory
from apps.incidents.models import IncidentReport, IncidentPriority, IncidentStatus
from apps.patrols.models import Checkpoint, PatrolLog, CheckpointScan, PatrolStatus
from apps.leave.models import LeaveApplication, LeaveStatus, LeaveRejectionReason
from apps.exams.models import ExamDuty
from apps.escorts.models import EscortDuty

class EnterpriseSecurityHardeningTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.station = Station.objects.create(
            name="Alpha Security Post",
            code="STN-ALPHA-01",
            latitude=-1.2921,
            longitude=36.8219,
            geofence_radius_meters=200.0,
            is_active=True,
        )
        self.admin = User.objects.create_superuser(
            username="admin_security",
            password="adminpassword123",
            email="admin@sgmis.corp",
            role=UserRole.ADMINISTRATOR,
            station=self.station,
        )
        self.supervisor = User.objects.create_user(
            username="supervisor_dan",
            password="superpassword123",
            email="dan@sgmis.corp",
            role=UserRole.SUPERVISOR,
            station=self.station,
        )
        self.guard_1 = User.objects.create_user(
            username="guard_alice",
            password="alicepassword123",
            email="alice@sgmis.corp",
            role=UserRole.GUARD,
            station=self.station,
            employee_number="SEC-101",
        )
        self.guard_2 = User.objects.create_user(
            username="guard_bob",
            password="bobpassword123",
            email="bob@sgmis.corp",
            role=UserRole.GUARD,
            station=self.station,
            employee_number="SEC-102",
        )

    def test_brute_force_lockout_after_five_failed_attempts(self):
        """5 failed login attempts trigger a 15-minute lockout (HTTP 429)."""
        for i in range(4):
            resp = self.client.post("/auth/login/", {
                "identifier": "guard_alice",
                "password": "wrongpassword!",
            })
            self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
            self.assertFalse(resp.data.get("is_locked", False))

        # 5th attempt triggers lockout
        fifth_resp = self.client.post("/auth/login/", {
            "identifier": "guard_alice",
            "password": "wrongpassword!",
        })
        self.assertEqual(fifth_resp.status_code, status.HTTP_429_TOO_MANY_REQUESTS)
        self.assertTrue(fifth_resp.data.get("is_locked", False))

        # 6th attempt (even with right password) is blocked by lockout
        blocked_resp = self.client.post("/auth/login/", {
            "identifier": "guard_alice",
            "password": "alicepassword123",
        })
        self.assertEqual(blocked_resp.status_code, status.HTTP_429_TOO_MANY_REQUESTS)

    def test_password_recovery_time_sensitive_otp(self):
        """Request OTP and confirm password recovery."""
        # 1. Request OTP
        req_resp = self.client.post("/auth/password_reset/request/", {
            "identifier": "SEC-101",
        })
        self.assertEqual(req_resp.status_code, status.HTTP_200_OK)
        otp = PasswordResetOTP.objects.filter(user=self.guard_1, is_used=False).first()
        self.assertIsNotNone(otp)
        self.assertEqual(len(otp.otp_code_hash), 64)
        self.assertEqual(otp.otp_code, "")
        otp_value = req_resp.data.get("dev_otp")

        # 2. Confirm reset
        confirm_resp = self.client.post("/auth/password_reset/confirm/", {
            "identifier": "SEC-101",
            "otp_code": otp_value,
            "new_password": "newSecurePassword2026!",
        })
        self.assertEqual(confirm_resp.status_code, status.HTTP_200_OK)

        # 3. Verify login with new password succeeds
        login_resp = self.client.post("/auth/login/", {
            "identifier": "SEC-101",
            "password": "newSecurePassword2026!",
        })
        self.assertEqual(login_resp.status_code, status.HTTP_200_OK)

    def test_immutable_audit_logs_ob_and_incidents(self):
        """Deleting OB or Incident records must raise ValidationError."""
        ob = OccurrenceBookEntry.objects.create(
            station=self.station,
            guard=self.guard_1,
            category=OBCategory.ROUTINE,
            occurrence_text="Armory lock inspected.",
        )
        with self.assertRaises(ValidationError):
            ob.delete()

        inc = IncidentReport.objects.create(
            station=self.station,
            reporting_guard=self.guard_1,
            priority=IncidentPriority.HIGH,
            title="Perimeter Breach Attempt",
            description="Suspicious motion detected at North fence.",
            location="Sector 4",
        )
        with self.assertRaises(ValidationError):
            inc.delete()

    def test_zero_proxy_actions_rejected(self):
        """A guard cannot create records or clock in for another guard."""
        self.client.force_authenticate(user=self.guard_1)

        # Attempt to create OB entry for guard_2
        resp = self.client.post("/occurrence_book/entries/", {
            "guard": str(self.guard_2.id),
            "category": "ROUTINE",
            "occurrence_text": "Proxy entry attempt.",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

        # Attempt to report incident under guard_2
        inc_resp = self.client.post("/incidents/reports/", {
            "reporting_guard": str(self.guard_2.id),
            "title": "Proxy incident",
            "description": "Proxy description",
            "location": "Post gate",
        })
        self.assertEqual(inc_resp.status_code, status.HTTP_403_FORBIDDEN)

    def test_verified_patrol_proof_enforcement(self):
        """Patrol check-in without QR token, NFC UID, or verified GPS proximity is rejected."""
        patrol = PatrolLog.objects.create(
            guard=self.guard_1,
            station=self.station,
            status=PatrolStatus.IN_PROGRESS,
        )
        chk = Checkpoint.objects.create(
            station=self.station,
            name="Vault Checkpoint",
            code="CHK-001",
            qr_code="QR-SECRET-VAULT-TOKEN-99",
            latitude=-1.2921,
            longitude=36.8219,
        )
        self.client.force_authenticate(user=self.guard_1)

        # Button-only check-in (no proof) -> 400 Rejected
        unverified_resp = self.client.post(f"/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(chk.id),
        })
        self.assertEqual(unverified_resp.status_code, status.HTTP_400_BAD_REQUEST)

        # Verified check-in with QR token -> 201 Created
        verified_resp = self.client.post(f"/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(chk.id),
            "qr_token": "QR-SECRET-VAULT-TOKEN-99",
        })
        self.assertEqual(verified_resp.status_code, status.HTTP_201_CREATED)

    def test_supervisor_guardrails_incident_resolution_and_downgrade(self):
        """Supervisor cannot resolve incident (must triage), cannot downgrade severity."""
        inc = IncidentReport.objects.create(
            station=self.station,
            reporting_guard=self.guard_1,
            priority=IncidentPriority.CRITICAL,
            title="Armed Incursion Alert",
            description="Perimeter breach by multiple intruders.",
            location="East Gate",
        )

        # Supervisor tries to resolve -> 403 Forbidden
        self.client.force_authenticate(user=self.supervisor)
        resolve_resp = self.client.post(f"/incidents/reports/{inc.id}/resolve/", {
            "resolution_notes": "Supervisor resolved.",
        })
        self.assertEqual(resolve_resp.status_code, status.HTTP_403_FORBIDDEN)

        # Supervisor can triage: acknowledge, assign, escalate
        ack_resp = self.client.post(f"/incidents/reports/{inc.id}/acknowledge/")
        self.assertEqual(ack_resp.status_code, status.HTTP_200_OK)

        assign_resp = self.client.post(f"/incidents/reports/{inc.id}/assign/", {
            "assigned_to": str(self.guard_2.id),
        })
        self.assertEqual(assign_resp.status_code, status.HTTP_200_OK)

        # Attempt to downgrade severity from CRITICAL to LOW -> 400 Bad Request
        downgrade_resp = self.client.patch(f"/incidents/reports/{inc.id}/", {
            "priority": "LOW",
        })
        self.assertEqual(downgrade_resp.status_code, status.HTTP_400_BAD_REQUEST)

        # Admin resolves incident -> 200 OK
        self.client.force_authenticate(user=self.admin)
        admin_resolve = self.client.post(f"/incidents/reports/{inc.id}/resolve/", {
            "resolution_notes": "Armed response team deployed and perimeter secured.",
        })
        self.assertEqual(admin_resolve.status_code, status.HTTP_200_OK)

    def test_leave_rejection_reason_mandatory(self):
        """Rejecting a leave application requires a structured rejection reason."""
        app = LeaveApplication.objects.create(
            guard=self.guard_1,
            leave_type="VACATION",
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 5),
            reason="Family gathering.",
        )
        self.client.force_authenticate(user=self.supervisor)

        # Rejection without reason -> 400 Bad Request
        fail_resp = self.client.post(f"/leave/applications/{app.id}/review/", {
            "status": "REJECTED",
        })
        self.assertEqual(fail_resp.status_code, status.HTTP_400_BAD_REQUEST)

        # Rejection with structured reason -> 200 OK
        succ_resp = self.client.post(f"/leave/applications/{app.id}/review/", {
            "status": "REJECTED",
            "rejection_reason": LeaveRejectionReason.MANPOWER_SHORTAGE,
            "reviewer_notes": "High alert operations period.",
        })
        self.assertEqual(succ_resp.status_code, status.HTTP_200_OK)
        app.refresh_from_db()
        self.assertEqual(app.rejection_reason, LeaveRejectionReason.MANPOWER_SHORTAGE)

    def test_auto_allocate_non_duty_guards_exams_and_escorts(self):
        """Auto allocate assigns only guards who have no scheduled shift on that date."""
        target_date = date(2026, 10, 15)
        # guard_1 has a shift on target_date
        Shift.objects.create(
            station=self.station,
            guard=self.guard_1,
            date=target_date,
            start_time=time(6, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
        )

        self.client.force_authenticate(user=self.supervisor)
        # Allocate exam duty on target_date: only guard_2 should be eligible
        resp = self.client.post("/exams/duties/auto_allocate/", {
            "date": target_date.isoformat(),
            "institution": "Technical College",
            "exam_title": "End-of-Term Finals",
            "strategy": "RANDOM",
            "count": 1,
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(len(resp.data["duties"]), 1)
        self.assertEqual(str(resp.data["duties"][0]["guard"]), str(self.guard_2.id))

    def test_json_only_api_middleware_error_responses(self):
        """API endpoints must return JSON errors (never HTML) for 401 and 404."""
        # Unauthenticated request to protected endpoint -> 401 JSON
        self.client.logout()
        resp_401 = self.client.get("/accounts/users/me/")
        self.assertEqual(resp_401.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertTrue(resp_401["Content-Type"].startswith("application/json"))
        self.assertIn("detail", resp_401.data)

        # 404 on API path -> 404 JSON (not HTML error template)
        resp_404 = self.client.get("/api/nonexistent_route_for_testing/")
        self.assertEqual(resp_404.status_code, status.HTTP_404_NOT_FOUND)
        self.assertTrue(resp_404["Content-Type"].startswith("application/json"))
        data = resp_404.json()
        self.assertIn("detail", data)

    def test_password_reset_noslash_and_lockout_recovery(self):
        """Password reset request and confirm must succeed with or without trailing slash and clear lockout."""
        from apps.accounts.models import LoginAttempt
        LoginAttempt.objects.create(
            identifier=self.guard_1.username,
            failed_attempts=5,
            locked_until=timezone.now() + timedelta(minutes=15)
        )

        # Request OTP without trailing slash
        req_resp = self.client.post("/auth/password_reset/request", {
            "identifier": self.guard_1.username,
        }, format="json")
        self.assertEqual(req_resp.status_code, status.HTTP_200_OK)
        self.assertIn("dev_otp", req_resp.data)
        otp_code = req_resp.data["dev_otp"]

        # Confirm OTP without trailing slash
        new_pass = "CourtAdmissiblePass2026!"
        conf_resp = self.client.post("/auth/password_reset/confirm", {
            "identifier": self.guard_1.username,
            "otp_code": otp_code,
            "new_password": new_pass,
        }, format="json")
        self.assertEqual(conf_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(conf_resp.data["status"], "success")

        # Lockout record must be cleared
        attempt = LoginAttempt.objects.get(identifier=self.guard_1.username)
        self.assertEqual(attempt.failed_attempts, 0)
        self.assertIsNone(attempt.locked_until)

        # Guard can now login with new password
        login_resp = self.client.post("/auth/login", {
            "identifier": self.guard_1.username,
            "password": new_pass,
        }, format="json")
        self.assertEqual(login_resp.status_code, status.HTTP_200_OK)
        self.assertIn("access", login_resp.data)

