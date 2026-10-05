import uuid
from datetime import timedelta
from django.utils import timezone
from django.contrib.auth import get_user_model
from rest_framework import status
from rest_framework.test import APITestCase

from apps.accounts.models import UserRole
from apps.stations.models import Station
from apps.shifts.models import Shift, ShiftType
from apps.patrols.models import Checkpoint, PatrolLog, CheckpointScan, PatrolStatus

UserModel = get_user_model()


class PatrolSecurityHardeningPhase1Tests(APITestCase):
    """
    Test suite verifying all 19 security hardening criteria (A through S)
    for Phase 1 Patrol Verification Hardening.
    """

    def setUp(self):
        self.password = "StrongPass123!"

        # Stations
        self.station_a = Station.objects.create(
            name="Alpha Facility",
            code="STN-ALPHA",
            latitude=-1.2921,
            longitude=36.8219,
            geofence_radius_meters=100.0,
            is_active=True,
        )
        self.station_b = Station.objects.create(
            name="Bravo Post",
            code="STN-BRAVO",
            latitude=-1.3000,
            longitude=36.8300,
            geofence_radius_meters=100.0,
            is_active=True,
        )

        # Users
        self.guard_a = UserModel.objects.create_user(
            username="guard_alpha",
            email="guard_alpha@sgmis.local",
            password=self.password,
            employee_number="SEC-001",
            role=UserRole.GUARD,
            station=self.station_a,
        )
        self.guard_b = UserModel.objects.create_user(
            username="guard_bravo",
            email="guard_bravo@sgmis.local",
            password=self.password,
            employee_number="SEC-002",
            role=UserRole.GUARD,
            station=self.station_b,
        )
        self.supervisor_a = UserModel.objects.create_user(
            username="sup_alpha",
            email="sup_alpha@sgmis.local",
            password=self.password,
            employee_number="SUP-001",
            role=UserRole.SUPERVISOR,
            station=self.station_a,
        )
        self.supervisor_b = UserModel.objects.create_user(
            username="sup_bravo",
            email="sup_bravo@sgmis.local",
            password=self.password,
            employee_number="SUP-002",
            role=UserRole.SUPERVISOR,
            station=self.station_b,
        )

        # Active shift for Guard A today (bypasses off-duty lockout)
        today = timezone.localdate()
        now_time = timezone.now().time()
        Shift.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            date=today,
            start_time=now_time,
            end_time=(timezone.now() + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
        )

        # Station A Checkpoints in sequential order
        self.cp1 = Checkpoint.objects.create(
            station=self.station_a,
            name="Alpha Gate",
            code="CP-A1",
            qr_code="QR-ALPHA-1",
            nfc_uid="NFC-TAG-001",
            latitude=-1.2921,
            longitude=36.8219,
            order=1,
            min_interval_seconds=0,
            is_active=True,
        )
        self.cp2 = Checkpoint.objects.create(
            station=self.station_a,
            name="Alpha Server Room",
            code="CP-A2",
            qr_code="QR-ALPHA-2",
            nfc_uid="NFC-TAG-002",
            latitude=-1.2922,
            longitude=36.8220,
            order=2,
            min_interval_seconds=10,  # Requires 10s transit time
            is_active=True,
        )
        self.cp3 = Checkpoint.objects.create(
            station=self.station_a,
            name="Alpha Vault",
            code="CP-A3",
            qr_code="QR-ALPHA-3",
            nfc_uid="NFC-TAG-003",
            latitude=-1.2923,
            longitude=36.8221,
            order=3,
            min_interval_seconds=0,
            is_active=True,
        )

        # Station B Checkpoint
        self.cp_b1 = Checkpoint.objects.create(
            station=self.station_b,
            name="Bravo Perimeter",
            code="CP-B1",
            qr_code="QR-BRAVO-1",
            nfc_uid="NFC-TAG-B01",
            latitude=-1.3000,
            longitude=36.8300,
            order=1,
            min_interval_seconds=0,
            is_active=True,
        )

    def _start_patrol_a(self):
        """Helper to create an active patrol for Guard A."""
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/api/patrols/logs/", {})
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        return PatrolLog.objects.get(id=resp.data["id"])

    # =========================================================================
    # A. Valid registered NFC UID accepted
    # =========================================================================
    def test_a_valid_registered_nfc_uid_accepted(self):
        patrol = self._start_patrol_a()
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": "NFC-TAG-001",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        scan = CheckpointScan.objects.get(patrol_log=patrol, checkpoint=self.cp1)
        self.assertIn("[NFC_UID]", scan.notes)

    # =========================================================================
    # B. Arbitrary 4-character NFC UID rejected
    # =========================================================================
    def test_b_arbitrary_4_character_nfc_uid_rejected(self):
        patrol = self._start_patrol_a()
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": "TEST",  # Unregistered 4-character string
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("does not match", str(resp.data).lower())
        self.assertFalse(CheckpointScan.objects.filter(patrol_log=patrol).exists())

    # =========================================================================
    # C. NFC UID belonging to another checkpoint rejected
    # =========================================================================
    def test_c_nfc_uid_belonging_to_another_checkpoint_rejected(self):
        patrol = self._start_patrol_a()
        # Attempt to scan CP1 using CP2's valid NFC UID
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": "NFC-TAG-002",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("does not match", str(resp.data).lower())

    # =========================================================================
    # D. NFC UID belonging to another station rejected
    # =========================================================================
    def test_d_nfc_uid_belonging_to_another_station_rejected(self):
        patrol = self._start_patrol_a()
        # Attempt to scan CP1 using Station B's valid NFC UID
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": "NFC-TAG-B01",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("does not match", str(resp.data).lower())

    # =========================================================================
    # E. Missing NFC configuration does not automatically pass
    # =========================================================================
    def test_e_missing_nfc_configuration_does_not_automatically_pass(self):
        station_e = Station.objects.create(
            name="Echo Post",
            code="STN-ECHO",
            latitude=-1.2921,
            longitude=36.8219,
            geofence_radius_meters=100.0,
            is_active=True,
        )
        guard_e = UserModel.objects.create_user(
            username="guard_echo",
            email="guard_echo@sgmis.local",
            password=self.password,
            employee_number="SEC-005",
            role=UserRole.GUARD,
            station=station_e,
        )
        Shift.objects.create(
            guard=guard_e,
            station=station_e,
            date=timezone.localdate(),
            start_time=timezone.now().time(),
            end_time=(timezone.now() + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
        )
        cp_unconfigured = Checkpoint.objects.create(
            station=station_e,
            name="Unconfigured NFC CP",
            code="CP-UNCONFIG",
            order=1,
            nfc_uid="",  # No registered NFC
            is_active=True,
        )
        self.client.force_authenticate(user=guard_e)
        p_resp = self.client.post("/api/patrols/logs/", {})
        patrol = PatrolLog.objects.get(id=p_resp.data["id"])

        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(cp_unconfigured.id),
            "nfc_uid": "NFC-TAG-001",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("not configured", str(resp.data).lower())

    # =========================================================================
    # F. Checkpoint with (0,0) coordinates does not automatically pass
    # =========================================================================
    def test_f_checkpoint_with_zero_zero_coordinates_does_not_pass(self):
        station_f = Station.objects.create(
            name="Foxtrot Post",
            code="STN-FOXTROT",
            latitude=-1.2921,
            longitude=36.8219,
            geofence_radius_meters=100.0,
            is_active=True,
        )
        guard_f = UserModel.objects.create_user(
            username="guard_foxtrot",
            email="guard_foxtrot@sgmis.local",
            password=self.password,
            employee_number="SEC-006",
            role=UserRole.GUARD,
            station=station_f,
        )
        Shift.objects.create(
            guard=guard_f,
            station=station_f,
            date=timezone.localdate(),
            start_time=timezone.now().time(),
            end_time=(timezone.now() + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
        )
        cp_zero = Checkpoint.objects.create(
            station=station_f,
            name="Zero Coords CP",
            code="CP-ZERO",
            latitude=0.0,
            longitude=0.0,
            order=1,
            is_active=True,
        )
        self.client.force_authenticate(user=guard_f)
        p_resp = self.client.post("/api/patrols/logs/", {})
        patrol = PatrolLog.objects.get(id=p_resp.data["id"])

        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(cp_zero.id),
            "latitude": -1.2921,
            "longitude": 36.8219,
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertTrue(
            "LOCATION_NOT_CONFIGURED" in str(resp.data) or "not configured" in str(resp.data).lower()
        )

    # =========================================================================
    # G. Invalid/missing GPS coordinates fail safely
    # =========================================================================
    def test_g_invalid_or_missing_gps_coordinates_fail_safely(self):
        patrol = self._start_patrol_a()
        # 1. Missing proof altogether
        resp_empty = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
        })
        self.assertEqual(resp_empty.status_code, status.HTTP_400_BAD_REQUEST)

        # 2. Corrupt coordinates
        resp_corrupt = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "gps_coords": "not_coordinates",
        })
        self.assertEqual(resp_corrupt.status_code, status.HTTP_400_BAD_REQUEST)

    # =========================================================================
    # H. Valid GPS inside checkpoint radius still works
    # =========================================================================
    def test_h_valid_gps_inside_checkpoint_radius_still_works(self):
        patrol = self._start_patrol_a()
        # Within 20 meters of CP1
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "latitude": -1.29212,
            "longitude": 36.82191,
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        scan = CheckpointScan.objects.get(patrol_log=patrol, checkpoint=self.cp1)
        self.assertIn("[GPS_PROXIMITY]", scan.notes)

    # =========================================================================
    # I. Checkpoint sequence 1 → 2 → 3 works
    # =========================================================================
    def test_i_checkpoint_sequence_1_2_3_works(self):
        patrol = self._start_patrol_a()

        # Step 1: Scan CP1 (order=1)
        resp1 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "qr_token": "QR-ALPHA-1",
        })
        self.assertEqual(resp1.status_code, status.HTTP_201_CREATED)

        # Fast-forward CP1 scan to satisfy CP2's 10s transit time
        CheckpointScan.objects.filter(patrol_log=patrol, checkpoint=self.cp1).update(
            scanned_at=timezone.now() - timedelta(seconds=15)
        )

        # Step 2: Scan CP2 (order=2)
        resp2 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp2.id),
            "qr_token": "QR-ALPHA-2",
        })
        self.assertEqual(resp2.status_code, status.HTTP_201_CREATED)

        # Step 3: Scan CP3 (order=3)
        resp3 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp3.id),
            "qr_token": "QR-ALPHA-3",
        })
        self.assertEqual(resp3.status_code, status.HTTP_201_CREATED)
        self.assertEqual(patrol.scans.count(), 3)

    # =========================================================================
    # J. Checkpoint sequence 1 → 3 is rejected
    # =========================================================================
    def test_j_checkpoint_sequence_1_3_is_rejected(self):
        patrol = self._start_patrol_a()

        # Step 1: Scan CP1 (order=1)
        resp1 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "qr_token": "QR-ALPHA-1",
        })
        self.assertEqual(resp1.status_code, status.HTTP_201_CREATED)

        # Step 2: Attempt to skip CP2 and scan CP3 directly
        resp_skip = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp3.id),
            "qr_token": "QR-ALPHA-3",
        })
        self.assertEqual(resp_skip.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("out-of-sequence", str(resp_skip.data).lower())

    # =========================================================================
    # K. Checkpoint sequence 1 → 2 → 2 is rejected (duplicate rejection)
    # =========================================================================
    def test_k_checkpoint_sequence_1_2_2_rejected_duplicate(self):
        patrol = self._start_patrol_a()

        # Step 1: CP1
        self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "qr_token": "QR-ALPHA-1",
        })

        CheckpointScan.objects.filter(patrol_log=patrol, checkpoint=self.cp1).update(
            scanned_at=timezone.now() - timedelta(seconds=15)
        )

        # Step 2: CP2
        resp2 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp2.id),
            "qr_token": "QR-ALPHA-2",
        })
        self.assertEqual(resp2.status_code, status.HTTP_201_CREATED)

        # Step 3: CP2 again (duplicate scan)
        resp_dup = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp2.id),
            "qr_token": "QR-ALPHA-2",
        })
        self.assertEqual(resp_dup.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("duplicate", str(resp_dup.data).lower())

    # =========================================================================
    # L. Impossible inter-checkpoint travel time is rejected
    # =========================================================================
    def test_l_impossible_inter_checkpoint_travel_time_rejected(self):
        patrol = self._start_patrol_a()

        # Scan CP1 at T=0
        self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "qr_token": "QR-ALPHA-1",
        })

        # Immediately scan CP2 (requires 10s transit time; 0s elapsed)
        resp_fast = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp2.id),
            "qr_token": "QR-ALPHA-2",
        })
        self.assertEqual(resp_fast.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("minimum transit time required", str(resp_fast.data).lower())

    # =========================================================================
    # M. Valid realistic travel time is accepted
    # =========================================================================
    def test_m_valid_realistic_travel_time_accepted(self):
        patrol = self._start_patrol_a()

        # Scan CP1
        self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "qr_token": "QR-ALPHA-1",
        })

        # Simulate 12 seconds elapsed since CP1
        CheckpointScan.objects.filter(patrol_log=patrol, checkpoint=self.cp1).update(
            scanned_at=timezone.now() - timedelta(seconds=12)
        )

        # Scan CP2 (10s required, 12s elapsed) -> Accepted
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp2.id),
            "qr_token": "QR-ALPHA-2",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)

    # =========================================================================
    # N. Existing minimum total patrol duration still works
    # =========================================================================
    def test_n_existing_minimum_total_patrol_duration_still_works(self):
        patrol = self._start_patrol_a()

        # Scan all 3 checkpoints
        CheckpointScan.objects.create(patrol_log=patrol, checkpoint=self.cp1)
        CheckpointScan.objects.create(patrol_log=patrol, checkpoint=self.cp2)
        CheckpointScan.objects.create(patrol_log=patrol, checkpoint=self.cp3)

        # Immediate finish attempt (< 60 seconds from patrol start)
        resp_early = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Fast patrol complete",
        })
        self.assertEqual(resp_early.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("patrol duration too short", str(resp_early.data).lower())

    # =========================================================================
    # O. Patrol cannot be completed twice
    # =========================================================================
    def test_o_patrol_cannot_be_completed_twice(self):
        patrol = self._start_patrol_a()
        CheckpointScan.objects.create(patrol_log=patrol, checkpoint=self.cp1)
        CheckpointScan.objects.create(patrol_log=patrol, checkpoint=self.cp2)
        CheckpointScan.objects.create(patrol_log=patrol, checkpoint=self.cp3)

        # Backdate patrol start by 2 minutes
        PatrolLog.objects.filter(id=patrol.id).update(
            start_time=timezone.now() - timedelta(minutes=2)
        )

        # First finish -> Success
        resp1 = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Valid finish",
        })
        self.assertEqual(resp1.status_code, status.HTTP_200_OK)

        # Second finish attempt -> Rejected
        resp2 = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Duplicate finish attempt",
        })
        self.assertEqual(resp2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("already completed", str(resp2.data).lower())

    # =========================================================================
    # P. Wrong guard cannot submit the checkpoint
    # =========================================================================
    def test_p_wrong_guard_cannot_submit_checkpoint(self):
        patrol_a = self._start_patrol_a()

        # Guard B attempts to scan checkpoint on Guard A's patrol
        self.client.force_authenticate(user=self.guard_b)
        resp = self.client.post(f"/api/patrols/logs/{patrol_a.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "qr_token": "QR-ALPHA-1",
        })
        self.assertIn(resp.status_code, [status.HTTP_403_FORBIDDEN, status.HTTP_404_NOT_FOUND])

    # =========================================================================
    # Q. Foreign station checkpoint cannot be submitted
    # =========================================================================
    def test_q_foreign_station_checkpoint_cannot_be_submitted(self):
        patrol = self._start_patrol_a()

        # Guard A attempts to scan Checkpoint B1 (belongs to Station B)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp_b1.id),
            "qr_token": "QR-BRAVO-1",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("station isolation", str(resp.data).lower())

    # =========================================================================
    # R. Supervisor approval is persisted
    # =========================================================================
    def test_r_supervisor_approval_is_persisted(self):
        patrol = self._start_patrol_a()
        patrol.status = PatrolStatus.COMPLETED
        patrol.end_time = timezone.now()
        patrol.save()

        # Supervisor A approves Guard A's completed patrol
        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/approve/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        patrol.refresh_from_db()
        self.assertTrue(patrol.is_approved)
        self.assertEqual(patrol.approved_by, self.supervisor_a)
        self.assertIsNotNone(patrol.approved_at)

    # =========================================================================
    # S. Guard cannot approve his own patrol
    # =========================================================================
    def test_s_guard_cannot_approve_his_own_patrol(self):
        patrol = self._start_patrol_a()
        patrol.status = PatrolStatus.COMPLETED
        patrol.end_time = timezone.now()
        patrol.save()

        # Guard A attempts to approve his own patrol
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/approve/")
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

        patrol.refresh_from_db()
        self.assertFalse(patrol.is_approved)
