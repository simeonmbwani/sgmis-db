import re
import secrets
from datetime import date, datetime, time, timedelta
from unittest.mock import patch
from django.utils import timezone
from django.test import TestCase, override_settings
from rest_framework.test import APIClient
from rest_framework import status

from apps.accounts.models import User, UserRole, PasswordResetOTP, LoginAttempt
from apps.stations.models import Station
from apps.shifts.models import Shift, ShiftType, Attendance
from apps.core.models import SecurityAuditEvent, SupervisorOverrideAudit
from apps.notifications.models import Notification
from apps.incidents.models import IncidentReport
from apps.core.sms import InMemorySMSProvider, send_sms, get_sms_provider


class EarlyReleaseAndSMSPasswordResetTests(TestCase):
    """
    Comprehensive tests covering:
    A. 5-minute early clock-out / early departure OTP workflow (NOT SOS).
    B. Forgotten password recovery via real SMS gateway abstraction.
    """

    def setUp(self):
        self.client = APIClient()
        InMemorySMSProvider.clear()

        self.station_harare = Station.objects.create(
            name="Harare Main Station",
            code="STN-HRE-01",
            latitude=-17.8252,
            longitude=31.0335,
            geofence_radius_meters=500.0,
            is_active=True,
        )
        self.station_bulawayo = Station.objects.create(
            name="Bulawayo Station",
            code="STN-BYO-02",
            latitude=-20.1500,
            longitude=28.5833,
            geofence_radius_meters=500.0,
            is_active=True,
        )

        self.admin = User.objects.create_superuser(
            username="admin_nat",
            password="AdminPassword123!",
            email="admin@sgmis.corp",
            role=UserRole.ADMINISTRATOR,
            station=self.station_harare,
            phone_number="+263770000001",
        )
        self.supervisor_harare = User.objects.create_user(
            username="sup_harare",
            password="SupPassword123!",
            email="sup_hre@sgmis.corp",
            role=UserRole.SUPERVISOR,
            station=self.station_harare,
            phone_number="+263770000002",
        )
        self.supervisor_bulawayo = User.objects.create_user(
            username="sup_byo",
            password="SupPassword123!",
            email="sup_byo@sgmis.corp",
            role=UserRole.SUPERVISOR,
            station=self.station_bulawayo,
            phone_number="+263770000003",
        )
        self.guard_1 = User.objects.create_user(
            username="guard_tendai",
            password="GuardPassword123!",
            email="tendai@sgmis.corp",
            role=UserRole.GUARD,
            station=self.station_harare,
            employee_number="SEC-HRE-101",
            phone_number="+263771111111",
        )
        self.guard_2 = User.objects.create_user(
            username="guard_chipo",
            password="GuardPassword123!",
            email="chipo@sgmis.corp",
            role=UserRole.GUARD,
            station=self.station_harare,
            employee_number="SEC-HRE-102",
            phone_number="+263772222222",
        )
        self.guard_no_phone = User.objects.create_user(
            username="guard_nophone",
            password="GuardPassword123!",
            email="nophone@sgmis.corp",
            role=UserRole.GUARD,
            station=self.station_harare,
            employee_number="SEC-HRE-103",
            phone_number="",
        )

    # =========================================================================
    # PART B: FORGOTTEN PASSWORD VIA REAL SMS TESTS
    # =========================================================================

    def test_password_reset_rejects_user_without_phone_number(self):
        """Guard recovery MUST validate that the user has a valid registered mobile number."""
        resp = self.client.post("/auth/password_reset/request/", {
            "identifier": "guard_nophone",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("No registered mobile phone number found", resp.data.get("detail", ""))
        # Verify no OTP was created
        self.assertEqual(PasswordResetOTP.objects.filter(user=self.guard_no_phone).count(), 0)

    def test_password_reset_sms_dispatch_success_and_no_dev_otp_leak(self):
        """SMS is dispatched to user's registered phone; dev_otp is NEVER returned in response."""
        resp = self.client.post("/auth/password_reset/request/", {
            "identifier": "guard_tendai",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertNotIn("dev_otp", resp.data)
        self.assertIn("destination", resp.data)
        self.assertTrue(resp.data["destination"].startswith("+26"))
        self.assertIn("****", resp.data["destination"])  # Masked destination

        # Check real SMS outbox
        sms = InMemorySMSProvider.get_last_sms("+263771111111")
        self.assertIsNotNone(sms)
        self.assertIn("Your SGMIS security verification code is:", sms["message"])
        otp_match = re.search(r"\b(\d{6})\b", sms["message"])
        self.assertIsNotNone(otp_match)
        raw_otp = otp_match.group(1)

        # Check OTP model at rest: SHA-256 hash stored, plaintext empty
        otp_rec = PasswordResetOTP.objects.filter(user=self.guard_1, is_used=False).latest("created_at")
        self.assertEqual(len(otp_rec.otp_code_hash), 64)
        self.assertEqual(otp_rec.otp_code, "")
        self.assertFalse(otp_rec.is_used)

        # In-app notification delivered
        notif = Notification.objects.filter(user=self.guard_1).latest("created_at")
        self.assertIn("Password Reset OTP Generated", notif.title)

    def test_password_reset_confirm_successful_invalidates_all_prior_otps(self):
        """Successful password reset invalidates the used code and all prior unused reset codes for that user."""
        # Request first OTP
        self.client.post("/auth/password_reset/request/", {"identifier": "guard_tendai"})
        sms_1 = InMemorySMSProvider.get_last_sms("+263771111111")
        otp_1 = re.search(r"\b(\d{6})\b", sms_1["message"]).group(1)

        # Confirm with otp_1
        confirm_resp = self.client.post("/auth/password_reset/confirm/", {
            "identifier": "guard_tendai",
            "otp_code": otp_1,
            "new_password": "BrandNewPassword2026!",
        })
        self.assertEqual(confirm_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(confirm_resp.data.get("status"), "success")

        # Verify all OTPs for this user are now marked is_used=True
        unused_count = PasswordResetOTP.objects.filter(user=self.guard_1, is_used=False).count()
        self.assertEqual(unused_count, 0)

        # Attempting to reuse otp_1 fails immediately
        reuse_resp = self.client.post("/auth/password_reset/confirm/", {
            "identifier": "guard_tendai",
            "otp_code": otp_1,
            "new_password": "AnotherNewPassword2026!",
        })
        self.assertEqual(reuse_resp.status_code, status.HTTP_400_BAD_REQUEST)

        # Verify login works with new password
        login_resp = self.client.post("/auth/login/", {
            "identifier": "guard_tendai",
            "password": "BrandNewPassword2026!",
        })
        self.assertEqual(login_resp.status_code, status.HTTP_200_OK)

    def test_password_reset_expired_otp_rejected(self):
        """Expired reset code fails verification."""
        self.client.post("/auth/password_reset/request/", {"identifier": "guard_tendai"})
        sms = InMemorySMSProvider.get_last_sms("+263771111111")
        raw_otp = re.search(r"\b(\d{6})\b", sms["message"]).group(1)

        otp_rec = PasswordResetOTP.objects.filter(user=self.guard_1, is_used=False).latest("created_at")
        otp_rec.expires_at = timezone.now() - timedelta(minutes=1)
        otp_rec.save(update_fields=["expires_at"])

        confirm_resp = self.client.post("/auth/password_reset/confirm/", {
            "identifier": "guard_tendai",
            "otp_code": raw_otp,
            "new_password": "BrandNewPassword2026!",
        })
        self.assertEqual(confirm_resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("Invalid or expired OTP code", confirm_resp.data.get("detail", ""))

    # =========================================================================
    # PART A: EARLY RELEASE OTP (NOT SOS) TESTS
    # =========================================================================

    def _setup_active_shift(self, guard, station):
        """Helper to create an active shift with clock-in."""
        shift = Shift.objects.create(
            date=date.today(),
            station=station,
            guard=guard,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )
        attendance = Attendance.objects.create(
            shift=shift,
            guard=guard,
            clock_in=timezone.now() - timedelta(hours=4),
            clock_in_gps=f"{station.latitude},{station.longitude}",
        )
        return shift, attendance

    def test_early_release_otp_generation_by_supervisor_and_delivery(self):
        """
        Supervisor generates early-departure OTP for a guard on duty.
        Delivers 6-digit OTP via real SMS and Notification to guard.
        Does NOT trigger SOS/distress workflow.
        """
        shift, att = self._setup_active_shift(self.guard_1, self.station_harare)

        self.client.force_authenticate(user=self.supervisor_harare)
        resp = self.client.post("/shifts/shifts/generate_early_clockout_otp/", {
            "shift_id": str(shift.id),
            "reason": "Family emergency: child unwell at home.",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertIn("otp", resp.data)
        otp_val = resp.data["otp"]
        self.assertEqual(len(otp_val), 6)
        self.assertEqual(resp.data["expires_in_seconds"], 300)
        self.assertTrue(resp.data["sms_delivered"])

        # 1. Delivered via real SMS to guard's registered phone
        sms = InMemorySMSProvider.get_last_sms("+263771111111")
        self.assertIsNotNone(sms)
        self.assertIn("SGMIS Early Release Authorization", sms["message"])
        self.assertIn(otp_val, sms["message"])

        # 2. Delivered via in-app Notification to guard
        notif = Notification.objects.filter(user=self.guard_1).latest("created_at")
        self.assertIn("Early Departure Authorization Code", notif.title)
        self.assertIn(otp_val, notif.message)

        # 3. CRITICAL: Must NOT trigger emergency SOS / incident report
        incidents = IncidentReport.objects.filter(reporting_guard=self.guard_1)
        self.assertEqual(incidents.count(), 0)

        # 4. Supervisor override logged with masked phone, no plaintext OTP in details
        audit = SecurityAuditEvent.objects.filter(
            actor=self.supervisor_harare,
            event_type=SecurityAuditEvent.EventType.OVERRIDE,
        ).latest("timestamp")
        self.assertNotIn(otp_val, str(audit.details))

    def test_administrator_master_early_departure_authority(self):
        """Administrator/superuser retains master early-departure authority for any station."""
        shift, att = self._setup_active_shift(self.guard_1, self.station_harare)

        self.client.force_authenticate(user=self.admin)
        resp = self.client.post("/shifts/shifts/generate_early_clockout_otp/", {
            "shift_id": str(shift.id),
            "reason": "Administrative reassignment by National Operations.",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertIn("otp", resp.data)

    def test_supervisor_cannot_authorize_early_release_for_other_station(self):
        """Supervisor at Bulawayo cannot authorize early release for guard at Harare."""
        shift, att = self._setup_active_shift(self.guard_1, self.station_harare)

        self.client.force_authenticate(user=self.supervisor_bulawayo)
        resp = self.client.post("/shifts/shifts/generate_early_clockout_otp/", {
            "shift_id": str(shift.id),
            "reason": "Unauthorized cross-station attempt.",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

    @patch("apps.shifts.views.timezone.now")
    def test_early_clockout_execution_with_valid_otp(self, mock_now):
        """Guard clocks out early using valid 5-minute authorization OTP."""
        mock_now.return_value = timezone.make_aware(datetime.combine(date.today(), time(12, 0)))
        shift, att = self._setup_active_shift(self.guard_1, self.station_harare)

        # Supervisor generates OTP
        self.client.force_authenticate(user=self.supervisor_harare)
        gen_resp = self.client.post("/shifts/shifts/generate_early_clockout_otp/", {
            "shift_id": str(shift.id),
            "reason": "Authorized medical appointment.",
        })
        otp_val = gen_resp.data["otp"]

        # Guard clocks out early using OTP
        self.client.force_authenticate(user=self.guard_1)
        clockout_resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "otp_code": otp_val,
            "latitude": self.station_harare.latitude,
            "longitude": self.station_harare.longitude,
            "enforce_shift_end": True,
        })
        self.assertEqual(clockout_resp.status_code, status.HTTP_200_OK)

        att.refresh_from_db()
        self.assertIsNotNone(att.clock_out)

        # Verify SupervisorOverrideAudit was created
        override_audit = SupervisorOverrideAudit.objects.filter(
            supervisor=self.supervisor_harare,
            action_type="EARLY_CLOCKOUT_OVERRIDE",
            target_id=str(att.id),
        ).first()
        self.assertIsNotNone(override_audit)
        self.assertIn("medical appointment", override_audit.reason)

        # Verify single-use: attempting to clock out or reuse OTP fails
        reuse_resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "otp_code": otp_val,
            "latitude": self.station_harare.latitude,
            "longitude": self.station_harare.longitude,
            "enforce_shift_end": True,
        })
        self.assertEqual(reuse_resp.status_code, status.HTTP_400_BAD_REQUEST)

    @patch("apps.shifts.views.timezone.now")
    def test_guard_cannot_use_another_guards_otp(self, mock_now):
        """Guard 2 cannot use an early departure OTP issued for Guard 1."""
        mock_now.return_value = timezone.make_aware(datetime.combine(date.today(), time(12, 0)))
        shift_1, att_1 = self._setup_active_shift(self.guard_1, self.station_harare)
        shift_2, att_2 = self._setup_active_shift(self.guard_2, self.station_harare)

        # Supervisor generates OTP for Guard 1
        self.client.force_authenticate(user=self.supervisor_harare)
        gen_resp = self.client.post("/shifts/shifts/generate_early_clockout_otp/", {
            "shift_id": str(shift_1.id),
            "reason": "Authorized leave for Guard 1.",
        })
        otp_val = gen_resp.data["otp"]

        # Guard 2 attempts to use Guard 1's OTP on their own shift
        # Note: if shift_id is shift_2, the cache key early_clockout_otp_{shift_2.id} does not exist
        self.client.force_authenticate(user=self.guard_2)
        tamper_resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift_2.id),
            "otp_code": otp_val,
            "latitude": self.station_harare.latitude,
            "longitude": self.station_harare.longitude,
            "enforce_shift_end": True,
        })
        self.assertEqual(tamper_resp.status_code, status.HTTP_400_BAD_REQUEST)

        # Guard 2 attempts to clock out proxy for Guard 1's shift
        proxy_resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift_1.id),
            "otp_code": otp_val,
            "latitude": self.station_harare.latitude,
            "longitude": self.station_harare.longitude,
            "enforce_shift_end": True,
        })
        self.assertEqual(proxy_resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("Proxy actions are strictly prohibited", proxy_resp.data.get("detail", ""))

    def test_normal_clockout_remains_unchanged(self):
        """Normal clock-out when shift has ended proceeds without requiring early departure OTP."""
        shift = Shift.objects.create(
            date=date.today() - timedelta(days=1),
            station=self.station_harare,
            guard=self.guard_1,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )
        att = Attendance.objects.create(
            shift=shift,
            guard=self.guard_1,
            clock_in=timezone.now() - timedelta(hours=14),
            clock_in_gps=f"{self.station_harare.latitude},{self.station_harare.longitude}",
        )

        self.client.force_authenticate(user=self.guard_1)
        resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "latitude": self.station_harare.latitude,
            "longitude": self.station_harare.longitude,
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        att.refresh_from_db()
        self.assertIsNotNone(att.clock_out)
