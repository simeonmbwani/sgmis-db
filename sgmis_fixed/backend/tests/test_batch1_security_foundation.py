import uuid
from datetime import timedelta
from django.utils import timezone
from django.test import TestCase, override_settings
from rest_framework.test import APIClient
from rest_framework import status

from apps.accounts.models import User, UserRole, LoginAttempt, PasswordResetOTP
from apps.stations.models import Station
from apps.shifts.models import Shift, ShiftType, Attendance, ShiftHandover
from apps.occurrence_book.models import OccurrenceBookEntry, OBCategory, OBAmendment
from apps.incidents.models import IncidentReport, IncidentPriority, IncidentStatus, IncidentAmendment
from apps.core.models import SecurityAuditEvent, IdempotencyRecord

class Batch1SecurityFoundationTests(TestCase):
    def setUp(self):
        self.client = APIClient()

        self.station_a = Station.objects.create(
            name="North Station Alpha",
            code="STN-ALPHA-01",
            latitude=-1.2921,
            longitude=36.8219,
            geofence_radius_meters=300.0,
            is_active=True,
        )
        self.station_b = Station.objects.create(
            name="South Station Bravo",
            code="STN-BRAVO-02",
            latitude=-1.3000,
            longitude=36.8300,
            geofence_radius_meters=300.0,
            is_active=True,
        )

        self.admin = User.objects.create_superuser(
            username="admin_sec",
            password="adminpassword123",
            email="admin@sgmis.corp",
            role=UserRole.ADMINISTRATOR,
            station=self.station_a,
        )
        self.supervisor_a = User.objects.create_user(
            username="supervisor_alpha",
            password="superpassword123",
            email="sup_a@sgmis.corp",
            role=UserRole.SUPERVISOR,
            station=self.station_a,
        )
        self.supervisor_b = User.objects.create_user(
            username="supervisor_bravo",
            password="superpassword123",
            email="sup_b@sgmis.corp",
            role=UserRole.SUPERVISOR,
            station=self.station_b,
        )
        self.guard_a = User.objects.create_user(
            username="guard_alice",
            password="alicepassword123",
            email="alice@sgmis.corp",
            role=UserRole.GUARD,
            station=self.station_a,
            employee_number="SEC-101",
            phone_number="+263771234567",
        )
        self.guard_b = User.objects.create_user(
            username="guard_bob",
            password="bobpassword123",
            email="bob@sgmis.corp",
            role=UserRole.GUARD,
            station=self.station_b,
            employee_number="SEC-102",
        )

    # -------------------------------------------------------------------------
    # 1. AUTHENTICATION & LOGIN THROTTLING
    # -------------------------------------------------------------------------
    def test_login_throttling_5_attempts_and_lockout(self):
        """5 failed login attempts lock account for 15 minutes and create audit events."""
        for i in range(4):
            resp = self.client.post("/auth/login/", {
                "identifier": "guard_alice",
                "password": "wrong_password",
            })
            self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
            self.assertFalse(resp.data.get("is_locked", False))
            self.assertEqual(resp.data.get("remaining_attempts"), 4 - i)

        # 5th attempt locks account
        resp_5 = self.client.post("/auth/login/", {
            "identifier": "guard_alice",
            "password": "wrong_password",
        })
        self.assertEqual(resp_5.status_code, status.HTTP_429_TOO_MANY_REQUESTS)
        self.assertTrue(resp_5.data.get("is_locked", True))

        # Locked account rejects even correct password
        resp_blocked = self.client.post("/auth/login/", {
            "identifier": "guard_alice",
            "password": "alicepassword123",
        })
        self.assertEqual(resp_blocked.status_code, status.HTTP_429_TOO_MANY_REQUESTS)

        # Verify audit logs exist
        audit_events = SecurityAuditEvent.objects.filter(
            event_type=SecurityAuditEvent.EventType.LOGIN_FAILURE,
            actor_username="guard_alice",
        )
        self.assertGreaterEqual(audit_events.count(), 5)

    def test_login_success_resets_failures_and_audits(self):
        """Successful login resets failed attempts and logs LOGIN_SUCCESS."""
        # 2 failures
        self.client.post("/auth/login/", {"identifier": "guard_alice", "password": "bad"})
        self.client.post("/auth/login/", {"identifier": "guard_alice", "password": "bad"})

        # Success
        resp = self.client.post("/auth/login/", {
            "identifier": "guard_alice",
            "password": "alicepassword123",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIn("access", resp.data)

        # Check attempt record reset
        attempt = LoginAttempt.objects.filter(identifier="guard_alice").first()
        self.assertIsNotNone(attempt)
        self.assertEqual(attempt.failed_attempts, 0)
        self.assertIsNone(attempt.locked_until)

        # Check audit event
        self.assertTrue(SecurityAuditEvent.objects.filter(
            event_type=SecurityAuditEvent.EventType.LOGIN_SUCCESS,
            actor_username="guard_alice",
        ).exists())

    # -------------------------------------------------------------------------
    # 2. PASSWORD RESET OTP SECURITY
    # -------------------------------------------------------------------------
    @override_settings(DEBUG=True)
    def test_password_reset_hashing_and_cooldown(self):
        """OTP stored as SHA-256 hash. Plaintext not stored. 60s cooldown enforced."""
        # 1. Request OTP
        req_resp = self.client.post("/auth/password_reset/request/", {
            "identifier": "guard_alice",
        })
        self.assertEqual(req_resp.status_code, status.HTTP_200_OK)
        otp = PasswordResetOTP.objects.filter(user=self.guard_1 if hasattr(self, 'guard_1') else self.guard_a, is_used=False).first()
        self.assertIsNotNone(otp)
        self.assertEqual(len(otp.otp_code_hash), 64)
        self.assertEqual(otp.otp_code, "")
        self.assertNotIn("dev_otp", req_resp.data)

        from apps.core.sms import InMemorySMSProvider
        import re
        sms_record = InMemorySMSProvider.get_last_sms("+263771234567")
        self.assertIsNotNone(sms_record)
        self.assertIn("SGMIS security verification code", sms_record["message"])

        # 2. Rapid second request hits cooldown
        req_again = self.client.post("/auth/password_reset/request/", {
            "identifier": "guard_alice",
        })
        self.assertEqual(req_again.status_code, status.HTTP_429_TOO_MANY_REQUESTS)

        # 3. Nonexistent user returns generic message (no enumeration)
        req_nonexistent = self.client.post("/auth/password_reset/request/", {
            "identifier": "nonexistent_guard_user",
        })
        self.assertEqual(req_nonexistent.status_code, status.HTTP_200_OK)
        self.assertIn("If an active account matches", req_nonexistent.data.get("message", ""))

    @override_settings(DEBUG=True)
    def test_password_reset_max_five_attempts(self):
        """5 invalid OTP attempts invalidate the OTP."""
        req_resp = self.client.post("/auth/password_reset/request/", {
            "identifier": "guard_alice",
        })
        self.assertEqual(req_resp.status_code, status.HTTP_200_OK)
        self.assertNotIn("dev_otp", req_resp.data)

        from apps.core.sms import InMemorySMSProvider
        import re
        sms_record = InMemorySMSProvider.get_last_sms("+263771234567")
        self.assertIsNotNone(sms_record)
        raw_otp = re.search(r"\b(\d{6})\b", sms_record["message"]).group(1)

        # Submit 4 wrong attempts
        for _ in range(4):
            fail_resp = self.client.post("/auth/password_reset/confirm/", {
                "identifier": "guard_alice",
                "otp_code": "000000",
                "new_password": "validPassword123!",
            })
            self.assertEqual(fail_resp.status_code, status.HTTP_400_BAD_REQUEST)

        # 5th wrong attempt invalidates the OTP
        fail_resp_5 = self.client.post("/auth/password_reset/confirm/", {
            "identifier": "guard_alice",
            "otp_code": "000000",
            "new_password": "validPassword123!",
        })
        self.assertEqual(fail_resp_5.status_code, status.HTTP_400_BAD_REQUEST)

        # Even with the real OTP, it is now invalid
        try_real_otp = self.client.post("/auth/password_reset/confirm/", {
            "identifier": "guard_alice",
            "otp_code": raw_otp,
            "new_password": "validPassword123!",
        })
        self.assertEqual(try_real_otp.status_code, status.HTTP_400_BAD_REQUEST)

    # -------------------------------------------------------------------------
    # 3. STATION & ROLE SCOPING (ZERO OVERRIDE VIA QUERY PARAMS)
    # -------------------------------------------------------------------------
    def test_guard_station_scoping_cannot_override(self):
        """Guard cannot view other station records via ?station= or ?guard= query params."""
        ob_a = OccurrenceBookEntry.objects.create(
            station=self.station_a,
            guard=self.guard_a,
            category=OBCategory.ROUTINE,
            occurrence_text="Station A entry",
        )
        ob_b = OccurrenceBookEntry.objects.create(
            station=self.station_b,
            guard=self.guard_b,
            category=OBCategory.ROUTINE,
            occurrence_text="Station B entry",
        )

        self.client.force_authenticate(user=self.guard_a)

        # Normal query returns only Station A entries
        resp = self.client.get("/occurrence_book/entries/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        results = resp.data.get("results", resp.data)
        ids = [item["id"] for item in results]
        self.assertIn(str(ob_a.id), ids)
        self.assertNotIn(str(ob_b.id), ids)

        # Attempt to override with ?station=station_b.id
        resp_override = self.client.get(f"/occurrence_book/entries/?station={self.station_b.id}")
        self.assertEqual(resp_override.status_code, status.HTTP_200_OK)
        results_override = resp_override.data.get("results", resp_override.data)
        ids_override = [item["id"] for item in results_override]
        self.assertNotIn(str(ob_b.id), ids_override)

    def test_supervisor_station_scoping_cannot_view_other_stations(self):
        """Supervisor of Station A cannot see Station B incidents or shifts."""
        inc_b = IncidentReport.objects.create(
            station=self.station_b,
            reporting_guard=self.guard_b,
            priority=IncidentPriority.HIGH,
            title="Station B Incident",
            description="Station B sensitive incident details",
            location="Gate 2",
        )

        self.client.force_authenticate(user=self.supervisor_a)

        # Normal query
        resp = self.client.get("/incidents/reports/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        results = resp.data.get("results", resp.data)
        ids = [item["id"] for item in results]
        self.assertNotIn(str(inc_b.id), ids)

        # Attempt query param override
        resp_override = self.client.get(f"/incidents/reports/?station={self.station_b.id}")
        self.assertEqual(resp_override.status_code, status.HTTP_200_OK)
        results_override = resp_override.data.get("results", resp_override.data)
        ids_override = [item["id"] for item in results_override]
        self.assertNotIn(str(inc_b.id), ids_override)

    # -------------------------------------------------------------------------
    # 4. IMMUTABILITY & AMENDMENTS
    # -------------------------------------------------------------------------
    def test_ob_entry_direct_modification_prohibited(self):
        """Direct PUT, PATCH, and DELETE on OccurrenceBookEntry are rejected."""
        ob = OccurrenceBookEntry.objects.create(
            station=self.station_a,
            guard=self.guard_a,
            category=OBCategory.ROUTINE,
            occurrence_text="Original unalterable evidence text.",
        )
        self.client.force_authenticate(user=self.guard_a)

        # PUT rejected
        put_resp = self.client.put(f"/occurrence_book/entries/{ob.id}/", {
            "occurrence_text": "Tampered text.",
        })
        self.assertEqual(put_resp.status_code, status.HTTP_403_FORBIDDEN)

        # PATCH rejected
        patch_resp = self.client.patch(f"/occurrence_book/entries/{ob.id}/", {
            "occurrence_text": "Tampered text.",
        })
        self.assertEqual(patch_resp.status_code, status.HTTP_403_FORBIDDEN)

        # DELETE rejected
        del_resp = self.client.delete(f"/occurrence_book/entries/{ob.id}/")
        self.assertEqual(del_resp.status_code, status.HTTP_403_FORBIDDEN)

    def test_ob_entry_amendment_appends_snapshot_and_audits(self):
        """POST .../amend/ creates append-only amendment preserving original snapshot."""
        ob = OccurrenceBookEntry.objects.create(
            station=self.station_a,
            guard=self.guard_a,
            category=OBCategory.ROUTINE,
            occurrence_text="Original observation text.",
        )
        self.client.force_authenticate(user=self.guard_a)

        amend_resp = self.client.post(f"/occurrence_book/entries/{ob.id}/amend/", {
            "reason": "Clarified license plate digits",
            "amended_text": "Original observation text with updated plate XYZ-123.",
        })
        self.assertEqual(amend_resp.status_code, status.HTTP_201_CREATED)

        # Verify OBAmendment created
        amendment = OBAmendment.objects.filter(entry=ob).first()
        self.assertIsNotNone(amendment)
        self.assertEqual(amendment.original_text_snapshot, "Original observation text.")
        self.assertEqual(amendment.amended_text, "Original observation text with updated plate XYZ-123.")
        self.assertEqual(amendment.amended_by, self.guard_a)

        # Verify audit event
        self.assertTrue(SecurityAuditEvent.objects.filter(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            target_model="OccurrenceBookEntry",
            target_id=str(ob.id),
        ).exists())

    def test_incident_description_immutable_and_amendment(self):
        """Incident description cannot be edited via PUT/PATCH; must use amend."""
        inc = IncidentReport.objects.create(
            station=self.station_a,
            reporting_guard=self.guard_a,
            priority=IncidentPriority.MEDIUM,
            title="Broken Fence",
            description="Initial fence description.",
            location="East Perimeter",
        )
        self.client.force_authenticate(user=self.guard_a)

        # Guard cannot edit incident description via PATCH
        patch_resp = self.client.patch(f"/incidents/reports/{inc.id}/", {
            "description": "Tampered description.",
        })
        self.assertEqual(patch_resp.status_code, status.HTTP_403_FORBIDDEN)

        # Guard can submit official amendment
        amend_resp = self.client.post(f"/incidents/reports/{inc.id}/amend/", {
            "reason": "Corrected fence section number",
            "amended_description": "Initial fence description: section 4B confirmed.",
        })
        self.assertEqual(amend_resp.status_code, status.HTTP_201_CREATED)

        amendment = IncidentAmendment.objects.filter(incident=inc).first()
        self.assertIsNotNone(amendment)
        self.assertEqual(amendment.original_description_snapshot, "Initial fence description.")
        self.assertEqual(amendment.amended_description, "Initial fence description: section 4B confirmed.")

    # -------------------------------------------------------------------------
    # 5. IDEMPOTENCY
    # -------------------------------------------------------------------------
    def test_idempotency_prevents_duplicate_ob_entries(self):
        """Sending the same X-Idempotency-Key returns cached response without creating duplicate."""
        # Ensure today is an active shift for guard_a so duty check passes
        Shift.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            shift_type=ShiftType.DAY,
            date=timezone.localdate(),
            start_time="06:00:00",
            end_time="18:00:00",
        )

        self.client.force_authenticate(user=self.guard_a)
        idempotency_key = f"idemp-{uuid.uuid4()}"

        payload = {
            "category": "ROUTINE",
            "occurrence_text": "Authoritative log message under idempotency test.",
        }

        # 1st Request
        resp1 = self.client.post(
            "/occurrence_book/entries/",
            payload,
            HTTP_X_IDEMPOTENCY_KEY=idempotency_key,
        )
        self.assertEqual(resp1.status_code, status.HTTP_201_CREATED)
        created_id = resp1.data["id"]

        # Verify IdempotencyRecord was created
        self.assertTrue(IdempotencyRecord.objects.filter(key=idempotency_key).exists())

        # 2nd Request with same idempotency key
        resp2 = self.client.post(
            "/occurrence_book/entries/",
            payload,
            HTTP_X_IDEMPOTENCY_KEY=idempotency_key,
        )
        self.assertEqual(resp2.status_code, status.HTTP_201_CREATED)
        self.assertEqual(resp2.data["id"], created_id)

        # Count in DB must be exactly 1
        count = OccurrenceBookEntry.objects.filter(
            occurrence_text="Authoritative log message under idempotency test."
        ).count()
        self.assertEqual(count, 1)
