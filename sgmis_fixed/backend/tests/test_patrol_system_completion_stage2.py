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


class PatrolSystemCompletionStage2Tests(APITestCase):
    """
    Test suite verifying all 24 required operational criteria for
    Smart Security Patrol System Completion - Stage 2.
    """

    def setUp(self):
        self.password = "StrongPass123!"

        # Station A
        self.station_a = Station.objects.create(
            name="Alpha Facility",
            code="STN-ALPHA",
            latitude=-1.2921,
            longitude=36.8219,
            geofence_radius_meters=100.0,
            is_active=True,
        )

        # Station B
        self.station_b = Station.objects.create(
            name="Bravo Post",
            code="STN-BRAVO",
            latitude=-1.3000,
            longitude=36.8300,
            geofence_radius_meters=100.0,
            is_active=True,
        )

        # Users
        self.supervisor_a = UserModel.objects.create_user(
            username="sup_alpha",
            email="sup_alpha@sgmis.local",
            password=self.password,
            employee_number="SUP-001",
            role=UserRole.SUPERVISOR,
            station=self.station_a,
        )
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

        # Roster shifts
        self.today = timezone.localdate()
        Shift.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            date=self.today,
            start_time=timezone.now().time(),
            end_time=(timezone.now() + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
        )
        Shift.objects.create(
            guard=self.guard_b,
            station=self.station_b,
            date=self.today,
            start_time=timezone.now().time(),
            end_time=(timezone.now() + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
        )

        # Checkpoints for Station A
        self.cp1 = Checkpoint.objects.create(
            station=self.station_a,
            name="Main Gate Post",
            code="CP-MG-01",
            qr_code="QR-MG-SECURE-TOKEN-ALPHA-01",
            nfc_uid="04A1B2C3D4",
            latitude=-1.2921,
            longitude=36.8219,
            order=1,
            min_interval_seconds=0,
            is_active=True,
        )
        self.cp2 = Checkpoint.objects.create(
            station=self.station_a,
            name="Rear Warehouse",
            code="CP-RW-02",
            qr_code="QR-RW-SECURE-TOKEN-ALPHA-02",
            nfc_uid="04E5F6A7B8",
            latitude=-1.2925,
            longitude=36.8223,
            order=2,
            min_interval_seconds=30,
            is_active=True,
        )

        # Checkpoint for Station B
        self.cp_b1 = Checkpoint.objects.create(
            station=self.station_b,
            name="Bravo Barrier",
            code="CP-BB-01",
            qr_code="QR-BB-BRAVO-01",
            nfc_uid="0499887766",
            latitude=-1.3000,
            longitude=36.8300,
            order=1,
            min_interval_seconds=0,
            is_active=True,
        )

    # =========================================================================
    # 1. Supervisor can assign patrol
    # =========================================================================
    def test_01_supervisor_can_assign_patrol(self):
        self.client.force_authenticate(user=self.supervisor_a)
        now = timezone.now()
        deadline = now + timedelta(hours=2)

        resp = self.client.post("/api/patrols/logs/", {
            "guard": str(self.guard_a.id),
            "name": "Perimeter Night Patrol",
            "start_window": now.isoformat(),
            "deadline": deadline.isoformat(),
            "notes": "Inspect all perimeter checkpoints.",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(resp.data["status"], "ASSIGNED")
        self.assertEqual(str(resp.data["guard"]), str(self.guard_a.id))
        self.assertEqual(str(resp.data["station"]), str(self.station_a.id))
        self.assertEqual(str(resp.data["assigned_by"]), str(self.supervisor_a.id))
        self.assertEqual(resp.data["name"], "Perimeter Night Patrol")

    # =========================================================================
    # 2. Supervisor cannot assign patrol outside station
    # =========================================================================
    def test_02_supervisor_cannot_assign_patrol_outside_station(self):
        self.client.force_authenticate(user=self.supervisor_a)
        # Attempt to assign Guard B (who belongs to Station B)
        resp = self.client.post("/api/patrols/logs/", {
            "guard": str(self.guard_b.id),
            "name": "Cross Station Attempt",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("outside station", str(resp.data["detail"]).lower())

    # =========================================================================
    # 3. Guard cannot create arbitrary patrol
    # =========================================================================
    def test_03_guard_cannot_create_arbitrary_patrol(self):
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/api/patrols/logs/", {
            "name": "Guard Invented Patrol",
            "notes": "Attempting self-initiated patrol.",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("cannot create arbitrary patrols", str(resp.data["detail"]).lower())

    # =========================================================================
    # 4. Guard can start assigned patrol
    # =========================================================================
    def test_04_guard_can_start_assigned_patrol(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            name="Assigned Patrol 1",
            status=PatrolStatus.ASSIGNED,
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/start/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["status"], "IN_PROGRESS")

        patrol.refresh_from_db()
        self.assertEqual(patrol.status, PatrolStatus.IN_PROGRESS)
        self.assertIsNotNone(patrol.start_time)

    # =========================================================================
    # 5. Wrong guard cannot start assigned patrol
    # =========================================================================
    def test_05_wrong_guard_cannot_start_assigned_patrol(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            name="Assigned Patrol For A",
            status=PatrolStatus.ASSIGNED,
        )
        # Guard B attempts to start Guard A's patrol
        self.client.force_authenticate(user=self.guard_b)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/start/")
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("proxy action rejected", str(resp.data["detail"]).lower())

    # =========================================================================
    # 6. NFC valid tag accepted
    # =========================================================================
    def test_06_nfc_valid_tag_accepted(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": "04A1B2C3D4",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        scan = CheckpointScan.objects.get(id=resp.data["id"])
        self.assertEqual(scan.verification_method, "NFC_UID")

    # =========================================================================
    # 7. NFC wrong tag rejected
    # =========================================================================
    def test_07_nfc_wrong_tag_rejected(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": "04DEADBEEF",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("does not match", str(resp.data["detail"]).lower())

    # =========================================================================
    # 8. NFC tag from another checkpoint rejected
    # =========================================================================
    def test_08_nfc_tag_from_another_checkpoint_rejected(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Attempt to scan CP1 with CP2's valid NFC UID
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": str(self.cp2.nfc_uid),
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("does not match", str(resp.data["detail"]).lower())

    # =========================================================================
    # 9. NFC tag from another station rejected
    # =========================================================================
    def test_09_nfc_tag_from_another_station_rejected(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Attempt to scan CP1 with Station B CP's valid NFC UID
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": str(self.cp_b1.nfc_uid),
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("does not match", str(resp.data["detail"]).lower())

    # =========================================================================
    # 10. GPS valid verification accepted
    # =========================================================================
    def test_10_gps_valid_verification_accepted(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Coordinates ~15m from CP1 with high accuracy (8m)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "latitude": -1.2921,
            "longitude": 36.8220,
            "accuracy": 8.0,
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        scan = CheckpointScan.objects.get(id=resp.data["id"])
        self.assertEqual(scan.verification_method, "GPS_PROXIMITY")

    # =========================================================================
    # 11. Invalid GPS rejected safely
    # =========================================================================
    def test_11_invalid_gps_rejected_safely(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Low accuracy (250m error)
        resp_low_acc = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "latitude": -1.2921,
            "longitude": 36.8219,
            "accuracy": 250.0,
        })
        self.assertEqual(resp_low_acc.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("accuracy too low", str(resp_low_acc.data["detail"]).lower())

        # Far away location
        resp_far = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "latitude": -1.3500,
            "longitude": 36.9000,
            "accuracy": 10.0,
        })
        self.assertEqual(resp_far.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("geofence violation", str(resp_far.data["detail"]).lower())

    # =========================================================================
    # 12. Checkpoint sequence enforced
    # =========================================================================
    def test_12_checkpoint_sequence_enforced(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Attempt to scan CP2 (order=2) before scanning CP1 (order=1)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp2.id),
            "nfc_uid": str(self.cp2.nfc_uid),
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("out-of-sequence", str(resp.data["detail"]).lower())

    # =========================================================================
    # 13. Duplicate checkpoint rejected
    # =========================================================================
    def test_13_duplicate_checkpoint_rejected(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # First scan CP1
        resp1 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": str(self.cp1.nfc_uid),
        })
        self.assertEqual(resp1.status_code, status.HTTP_201_CREATED)

        # Attempt duplicate scan of CP1
        resp2 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": str(self.cp1.nfc_uid),
        })
        self.assertEqual(resp2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("duplicate scan rejected", str(resp2.data["detail"]).lower())

    # =========================================================================
    # 14. Minimum travel interval enforced
    # =========================================================================
    def test_14_minimum_travel_interval_enforced(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Scan CP1
        resp1 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp1.id),
            "nfc_uid": str(self.cp1.nfc_uid),
        })
        self.assertEqual(resp1.status_code, status.HTTP_201_CREATED)

        # CP2 has min_interval_seconds=30. Immediate scan (< 30s) must be rejected
        resp2 = self.client.post(f"/api/patrols/logs/{patrol.id}/scan/", {
            "checkpoint": str(self.cp2.id),
            "nfc_uid": str(self.cp2.nfc_uid),
        })
        self.assertEqual(resp2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("minimum transit time required", str(resp2.data["detail"]).lower())

    # =========================================================================
    # 15. Minimum total patrol duration enforced
    # =========================================================================
    def test_15_minimum_total_patrol_duration_enforced(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Attempt to finish immediately (< 60 seconds)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Fast patrol.",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("duration too short", str(resp.data["detail"]).lower())

    # =========================================================================
    # 16. Expired patrol cannot be completed
    # =========================================================================
    def test_16_expired_patrol_cannot_be_completed(self):
        past_deadline = timezone.now() - timedelta(minutes=30)
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
            deadline=past_deadline,
        )
        # Backdate start_time by 5 minutes
        PatrolLog.objects.filter(id=patrol.id).update(start_time=timezone.now() - timedelta(minutes=5))

        # Scan required checkpoints
        CheckpointScan.objects.create(
            patrol_log=patrol,
            checkpoint=self.cp1,
            verification_method="NFC_UID",
        )
        CheckpointScan.objects.create(
            patrol_log=patrol,
            checkpoint=self.cp2,
            verification_method="NFC_UID",
        )

        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Attempting to finish after deadline.",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        patrol.refresh_from_db()
        self.assertEqual(patrol.status, PatrolStatus.EXPIRED)

    # =========================================================================
    # 17. Patrol cannot be completed twice
    # =========================================================================
    def test_17_patrol_cannot_be_completed_twice(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.COMPLETED,
            end_time=timezone.now(),
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/finish/", {
            "notes": "Second finish call.",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("already completed", str(resp.data["detail"]).lower())

    # =========================================================================
    # 18. Offline event receives unique identifier
    # =========================================================================
    def test_18_offline_event_receives_unique_identifier(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        event_id = uuid.uuid4()
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/sync_events/", {
            "events": [
                {
                    "client_event_id": str(event_id),
                    "checkpoint": str(self.cp1.id),
                    "nfc_uid": str(self.cp1.nfc_uid),
                    "client_timestamp": timezone.now().isoformat(),
                    "accuracy": 5.0,
                    "notes": "Offline scanned CP1",
                }
            ]
        }, format="json")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["synced_count"], 1)

        scan = CheckpointScan.objects.get(client_event_id=event_id)
        self.assertEqual(scan.checkpoint, self.cp1)
        self.assertEqual(scan.verification_method, "NFC_UID")

    # =========================================================================
    # 19. Offline duplicate event cannot be accepted twice
    # =========================================================================
    def test_19_offline_duplicate_event_cannot_be_accepted_twice(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        event_id = uuid.uuid4()
        event_payload = {
            "client_event_id": str(event_id),
            "checkpoint": str(self.cp1.id),
            "nfc_uid": str(self.cp1.nfc_uid),
            "client_timestamp": timezone.now().isoformat(),
        }

        # First sync call
        resp1 = self.client.post(f"/api/patrols/logs/{patrol.id}/sync_events/", {
            "events": [event_payload]
        }, format="json")
        self.assertEqual(resp1.status_code, status.HTTP_200_OK)
        self.assertEqual(resp1.data["synced_count"], 1)

        # Re-submitting the exact same event ID (duplicate submission)
        resp2 = self.client.post(f"/api/patrols/logs/{patrol.id}/sync_events/", {
            "events": [event_payload]
        }, format="json")
        self.assertEqual(resp2.status_code, status.HTTP_200_OK)
        self.assertEqual(resp2.data["duplicate_count"], 1)
        self.assertEqual(resp2.data["synced_count"], 0)

        # Database must only have one scan record for this event_id
        self.assertEqual(CheckpointScan.objects.filter(client_event_id=event_id).count(), 1)

    # =========================================================================
    # 20. Synchronized offline event is server validated
    # =========================================================================
    def test_20_synchronized_offline_event_is_server_validated(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Attempt to submit an invalid event: CP1 with wrong NFC UID
        invalid_event_id = uuid.uuid4()
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/sync_events/", {
            "events": [
                {
                    "client_event_id": str(invalid_event_id),
                    "checkpoint": str(self.cp1.id),
                    "nfc_uid": "04INVALIDTAG",
                }
            ]
        }, format="json")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["rejected_count"], 1)
        self.assertEqual(resp.data["synced_count"], 0)
        self.assertFalse(CheckpointScan.objects.filter(client_event_id=invalid_event_id).exists())

        patrol.refresh_from_db()
        self.assertTrue(len(patrol.anomalies) > 0)

    # =========================================================================
    # 21. Wrong station patrol rejected
    # =========================================================================
    def test_21_wrong_station_patrol_rejected(self):
        patrol_b = PatrolLog.objects.create(
            guard=self.guard_b,
            station=self.station_b,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.IN_PROGRESS,
        )
        # Guard A tries to scan on Station B's patrol
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol_b.id}/scan/", {
            "checkpoint": str(self.cp_b1.id),
            "nfc_uid": str(self.cp_b1.nfc_uid),
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

    # =========================================================================
    # 22. Supervisor can monitor patrol
    # =========================================================================
    def test_22_supervisor_can_monitor_patrol(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            name="Alpha Monitored Patrol",
            status=PatrolStatus.IN_PROGRESS,
        )
        CheckpointScan.objects.create(
            patrol_log=patrol,
            checkpoint=self.cp1,
            verification_method="NFC_UID",
        )
        patrol.record_anomaly("TEST_ANOMALY", "Test anomaly detection.")

        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.get(f"/api/patrols/logs/{patrol.id}/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["scans_count"], 1)
        self.assertEqual(resp.data["anomalies_count"], 1)
        self.assertEqual(resp.data["name"], "Alpha Monitored Patrol")

    # =========================================================================
    # 23. Supervisor approval persists
    # =========================================================================
    def test_23_supervisor_approval_persists(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.COMPLETED,
            end_time=timezone.now(),
        )
        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/approve/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        patrol.refresh_from_db()
        self.assertTrue(patrol.is_approved)
        self.assertEqual(patrol.approved_by, self.supervisor_a)
        self.assertIsNotNone(patrol.approved_at)
        self.assertEqual(patrol.status, PatrolStatus.APPROVED)

    # =========================================================================
    # 24. Guard cannot approve own patrol
    # =========================================================================
    def test_24_guard_cannot_approve_own_patrol(self):
        patrol = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.COMPLETED,
            end_time=timezone.now(),
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/patrols/logs/{patrol.id}/approve/")
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        patrol.refresh_from_db()
        self.assertFalse(patrol.is_approved)
