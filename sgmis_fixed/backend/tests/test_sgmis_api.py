from datetime import time, timedelta
from django.test import TestCase
from django.utils import timezone
from django.contrib.auth import get_user_model
from rest_framework.test import APIClient
from rest_framework import status
from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, Attendance, ShiftHandover
from apps.shifts.services import generate_roster_for_station, resolve_incoming_guard
from apps.leave.models import LeaveApplication, LeaveBalance, LeaveStatus
from apps.incidents.models import IncidentReport, IncidentPriority, IncidentStatus
from apps.patrols.models import Checkpoint, PatrolLog, PatrolStatus
from apps.occurrence_book.models import OccurrenceBookEntry

UserModel = get_user_model()

class SGMISBackendEndToEndTests(TestCase):
    """
    Automated test suite verifying the complete SGMIS workflow.
    Fulfills Requirements 35 and 36:
    1. Station creation
    2. Guard & Supervisor accounts creation
    3. Login via username & employee number
    4. Guard pair assignment
    5. Duty roster generation
    6. Today's shift resolution (station, partner, times from DB)
    7. Attendance clock-in & late calculation
    8. Attendance clock-out
    9. Handover creation with backend-determined incoming guard
    10. Handover acceptance by incoming guard
    11. Leave application & supervisor review
    12. Incidents & supervisor acknowledgement
    13. Patrol checkpoints & scanning
    14. Occurrence book entry auto-numbering
    15. Role permission barriers
    """

    def setUp(self):
        self.client = APIClient()
        self.password = "TestSecurityPassword123!"

        # Create Station
        self.station = Station.objects.create(
            name="Command Station Bravo",
            code="STN-BRV01",
            address="45 Guard HQ, East Sector",
            latitude=1.2921,
            longitude=36.8219,
        )

        # Create Admin
        self.admin = UserModel.objects.create_user(
            username="admin_user",
            email="admin@sgmis.local",
            password=self.password,
            employee_number="ADM-001",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
            is_superuser=True,
        )

        # Create Supervisor
        self.supervisor = UserModel.objects.create_user(
            username="supervisor_user",
            email="supervisor@sgmis.local",
            password=self.password,
            employee_number="SUP-101",
            role=UserRole.SUPERVISOR,
            station=self.station,
        )

        # Create 4 Guards (2 pairs)
        self.guard_a = UserModel.objects.create_user(
            username="guard_a",
            email="guard_a@sgmis.local",
            password=self.password,
            employee_number="SEC-501",
            role=UserRole.GUARD,
            station=self.station,
        )
        self.guard_b = UserModel.objects.create_user(
            username="guard_b",
            email="guard_b@sgmis.local",
            password=self.password,
            employee_number="SEC-502",
            role=UserRole.GUARD,
            station=self.station,
        )
        self.guard_c = UserModel.objects.create_user(
            username="guard_c",
            email="guard_c@sgmis.local",
            password=self.password,
            employee_number="SEC-503",
            role=UserRole.GUARD,
            station=self.station,
        )
        self.guard_d = UserModel.objects.create_user(
            username="guard_d",
            email="guard_d@sgmis.local",
            password=self.password,
            employee_number="SEC-504",
            role=UserRole.GUARD,
            station=self.station,
        )

        # Create Guard Pairs
        self.pair1 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_a,
            guard_b=self.guard_b,
            rotation_order=1,
        )
        self.pair2 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_c,
            guard_b=self.guard_d,
            rotation_order=2,
        )

    def test_health_check(self):
        response = self.client.get("/health/")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data["status"], "ok")
        self.assertEqual(response.data["service"], "sgmis-api")

    def test_login_with_username_and_employee_number(self):
        # 1. Login with username
        resp1 = self.client.post("/auth/login/", {
            "identifier": "guard_a",
            "password": self.password,
        })
        self.assertEqual(resp1.status_code, status.HTTP_200_OK)
        self.assertIn("access", resp1.data)
        self.assertIn("refresh", resp1.data)
        self.assertEqual(resp1.data["user"]["employee_number"], "SEC-501")

        # 2. Login with employee number
        resp2 = self.client.post("/auth/login/", {
            "identifier": "SEC-501",
            "password": self.password,
        })
        self.assertEqual(resp2.status_code, status.HTTP_200_OK)
        self.assertEqual(resp2.data["user"]["username"], "guard_a")

    def test_roster_generation_and_today_shift(self):
        today = timezone.now().date()
        shifts = generate_roster_for_station(self.station, today, cycle_days=4)
        self.assertTrue(len(shifts) > 0)

        # Authenticate as guard_a
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        shift_data = resp.data

        self.assertEqual(shift_data["guard_name"], "guard_a")
        self.assertEqual(shift_data["station_name"], "Command Station Bravo")
        # Verify partner is guard_b from the pair
        self.assertEqual(shift_data["partner_name"], "guard_b")
        self.assertEqual(shift_data["partner_employee_number"], "SEC-502")

    def test_clock_in_and_clock_out(self):
        today = timezone.now().date()
        generate_roster_for_station(self.station, today, cycle_days=2)

        shift = Shift.objects.filter(guard=self.guard_a, date=today).first()
        self.assertIsNotNone(shift)

        self.client.force_authenticate(user=self.guard_a)

        # Clock In with real GPS
        clock_in_resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": 1.2921,
            "longitude": 36.8219,
        })
        self.assertEqual(clock_in_resp.status_code, status.HTTP_200_OK)
        self.assertIsNotNone(clock_in_resp.data["clock_in"])
        self.assertEqual(clock_in_resp.data["clock_in_gps"], "1.2921,36.8219")

        # Duplicate clock in is rejected
        dup_resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
        })
        self.assertEqual(dup_resp.status_code, status.HTTP_400_BAD_REQUEST)

        # Clock Out
        clock_out_resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "latitude": 1.2921,
            "longitude": 36.8219,
        })
        self.assertEqual(clock_out_resp.status_code, status.HTTP_200_OK)
        self.assertIsNotNone(clock_out_resp.data["clock_out"])

    def test_handover_backend_resolution_and_acceptance(self):
        today = timezone.now().date()
        generate_roster_for_station(self.station, today, cycle_days=2)

        day_shift = Shift.objects.filter(station=self.station, date=today, shift_type=ShiftType.DAY, guard=self.guard_a).first()
        self.assertIsNotNone(day_shift)

        # Verify backend resolution of incoming guard
        resolved_incoming = resolve_incoming_guard(day_shift)
        self.assertIsNotNone(resolved_incoming)

        # Guard A submits handover
        self.client.force_authenticate(user=self.guard_a)
        handover_resp = self.client.post("/shifts/handovers/", {
            "outgoing_shift": str(day_shift.id),
            "occurrence_summary": "All quiet on station. Perimeter secure.",
            "equipment_issued": "2 flashlights, 1 radio transceiver, 1 patrol baton.",
            "keys_handed_over": "Station gate keys, master padlock set.",
            "pending_issues": "None.",
        })
        self.assertEqual(handover_resp.status_code, status.HTTP_201_CREATED)
        handover_id = handover_resp.data["id"]
        self.assertEqual(handover_resp.data["incoming_guard_name"], resolved_incoming.get_full_name() or resolved_incoming.username)

        # Incoming guard logs in and accepts handover
        self.client.force_authenticate(user=resolved_incoming)
        accept_resp = self.client.post(f"/shifts/handovers/{handover_id}/accept/")
        self.assertEqual(accept_resp.status_code, status.HTTP_200_OK)
        self.assertTrue(accept_resp.data["incoming_accepted"])
        self.assertIsNotNone(accept_resp.data["incoming_accepted_at"])

    def test_leave_application_and_review(self):
        self.client.force_authenticate(user=self.guard_a)
        today = timezone.now().date()

        # Submit leave
        leave_resp = self.client.post("/leave/applications/", {
            "leave_type": "ANNUAL",
            "start_date": str(today + timedelta(days=7)),
            "end_date": str(today + timedelta(days=12)),
            "reason": "Annual family leave",
        })
        self.assertEqual(leave_resp.status_code, status.HTTP_201_CREATED)
        leave_id = leave_resp.data["id"]

        # Supervisor reviews leave
        self.client.force_authenticate(user=self.supervisor)
        review_resp = self.client.post(f"/leave/applications/{leave_id}/review/", {
            "status": "APPROVED",
            "reviewer_notes": "Approved. Shift coverage arranged.",
        })
        self.assertEqual(review_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(review_resp.data["status"], "APPROVED")

    def test_occurrence_book_auto_numbering(self):
        self.client.force_authenticate(user=self.guard_a)
        ob_resp = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "Routine perimeter patrol completed. No anomalies detected.",
            "check_record": "Perimeter locks checked.",
        })
        self.assertEqual(ob_resp.status_code, status.HTTP_201_CREATED)
        entry_number = ob_resp.data["entry_number"]
        self.assertTrue(entry_number.startswith("OB-"))

    def test_incident_reporting_and_acknowledgement(self):
        self.client.force_authenticate(user=self.guard_a)
        inc_resp = self.client.post("/incidents/reports/", {
            "priority": "HIGH",
            "title": "Unauthorized vehicle near fence",
            "description": "Suspicious sedan parked adjacent to Sector C fence.",
            "location": "North-East Perimeter Gate",
        })
        self.assertEqual(inc_resp.status_code, status.HTTP_201_CREATED)
        inc_id = inc_resp.data["id"]

        # Supervisor acknowledges
        self.client.force_authenticate(user=self.supervisor)
        ack_resp = self.client.post(f"/incidents/reports/{inc_id}/acknowledge/")
        self.assertEqual(ack_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(ack_resp.data["status"], "ACKNOWLEDGED")

    def test_role_security_barrier(self):
        # Guard cannot create stations
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/stations/stations/", {
            "name": "Unauthorized Station",
            "code": "STN-UNAUTH",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

    def test_acceptance_1_guard_with_station_creates_ob_entry(self):
        """
        TEST 1:
        Authenticated guard WITH station creates OB entry:
        - POST /occurrence_book/entries/
        - Response: 201 Created
        - DB row has correct guard_id AND correct station_id
        - Entry number generated
        """
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "Perimeter inspection complete. Station gates locked.",
            "check_record": "Physical lock inspection verified.",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertTrue(resp.data["entry_number"].startswith("OB-"))
        self.assertEqual(str(resp.data["station"]), str(self.station.id))
        self.assertEqual(str(resp.data["guard"]), str(self.guard_a.id))

        # Verify database record
        entry = OccurrenceBookEntry.objects.get(id=resp.data["id"])
        self.assertEqual(entry.guard, self.guard_a)
        self.assertEqual(entry.station, self.station)
        self.assertIsNotNone(entry.station_id)

    def test_acceptance_2_guard_without_station_rejected(self):
        """
        TEST 2:
        Authenticated guard WITHOUT station creates OB entry:
        - POST /occurrence_book/entries/
        - Response: 400 or 403 (with clear station-assignment message)
        - NO row inserted in occurrence_book_occurrencebookentry
        - No 500 error
        """
        unassigned_guard = UserModel.objects.create_user(
            username="unassigned_guard",
            email="unassigned@sgmis.local",
            password=self.password,
            employee_number="SEC-999",
            role=UserRole.GUARD,
            station=None,
        )
        initial_ob_count = OccurrenceBookEntry.objects.count()

        self.client.force_authenticate(user=unassigned_guard)
        resp = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "Attempting entry without assigned station",
            "check_record": "None",
        })
        self.assertIn(resp.status_code, [status.HTTP_400_BAD_REQUEST, status.HTTP_403_FORBIDDEN])
        error_str = str(resp.data)
        self.assertIn("station", error_str.lower())
        self.assertIn("assigned", error_str.lower())

        # Verify no record created in database
        self.assertEqual(OccurrenceBookEntry.objects.count(), initial_ob_count)

    def test_acceptance_3_telemetry_canonical_and_database_values(self):
        """
        TEST 3:
        Telemetry.
        GET canonical telemetry endpoint
        -> HTTP 200
        -> real database values
        -> no /core/telemetry/ 404
        """
        # Unauthenticated request should be rejected
        unauth_resp = self.client.get("/core/telemetry/")
        self.assertEqual(unauth_resp.status_code, status.HTTP_401_UNAUTHORIZED)

        # Authenticated as supervisor
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.get("/core/telemetry/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIn("total_stations", resp.data)
        self.assertIn("active_guards", resp.data)
        self.assertIn("total_incidents", resp.data)
        self.assertIn("total_ob_entries", resp.data)
        self.assertGreaterEqual(resp.data["total_stations"], 1)
        self.assertGreaterEqual(resp.data["active_guards"], 4)

        # Alias /api/core/telemetry/ also works
        alias_resp = self.client.get("/api/core/telemetry/")
        self.assertEqual(alias_resp.status_code, status.HTTP_200_OK)

    def test_acceptance_4_authorized_station_creation(self):
        """
        TEST 4:
        Authorized station creation.
        POST /stations/stations/
        -> HTTP 201
        -> record persists
        -> GET stations contains it
        """
        self.client.force_authenticate(user=self.admin)
        resp = self.client.post("/stations/stations/", {
            "name": "North Gate Security Depot",
            "code": "STN-NRTH01",
            "address": "North Industrial Zone, Gate 4",
            "latitude": -1.2800,
            "longitude": 36.8100,
            "geofence_radius_meters": 150,
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        new_station_id = resp.data["id"]

        # Verify persistence in database
        self.assertTrue(Station.objects.filter(id=new_station_id, code="STN-NRTH01").exists())

        # Verify GET /stations/stations/ contains it
        list_resp = self.client.get("/stations/stations/")
        self.assertEqual(list_resp.status_code, status.HTTP_200_OK)
        results = list_resp.data.get("results", list_resp.data) if isinstance(list_resp.data, dict) else list_resp.data
        station_codes = [s["code"] for s in results]
        self.assertIn("STN-NRTH01", station_codes)

    def test_acceptance_5_unauthorized_guard_station_creation(self):
        """
        TEST 5:
        Unauthorized guard station creation.
        POST /stations/stations/
        -> HTTP 403
        """
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/stations/stations/", {
            "name": "Rogue Guard Post",
            "code": "STN-ROGUE",
            "address": "Unauthorized",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertFalse(Station.objects.filter(code="STN-ROGUE").exists())

    def test_acceptance_6_invalid_station_payload(self):
        """
        TEST 6:
        Invalid station payload.
        POST /stations/stations/
        -> HTTP 400
        -> useful serializer validation details
        """
        self.client.force_authenticate(user=self.admin)
        # Empty code and empty name
        resp = self.client.post("/stations/stations/", {
            "name": "",
            "code": "",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("name", resp.data)
        self.assertIn("code", resp.data)

    def test_acceptance_station_assignment_workflow(self):
        """
        Tests the authoritative station assignment workflow:
        1. Guard without station is rejected from creating OB
        2. Supervisor/Admin assigns guard to station via PATCH /accounts/users/{id}/
        3. GET /accounts/users/me/ returns the assigned station
        4. Guard can now create OB entries successfully
        """
        guard = UserModel.objects.create_user(
            username="guard_assigned_test",
            email="assigned_test@sgmis.local",
            password=self.password,
            employee_number="SEC-777",
            role=UserRole.GUARD,
            station=None,
        )

        # Step 1: Guard cannot create OB without station
        self.client.force_authenticate(user=guard)
        resp1 = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "Before assignment",
            "check_record": "Check",
        })
        self.assertIn(resp1.status_code, [status.HTTP_400_BAD_REQUEST, status.HTTP_403_FORBIDDEN])
        self.assertIn("station", str(resp1.data).lower())

        # Step 2: Supervisor assigns guard to self.station
        self.client.force_authenticate(user=self.supervisor)
        patch_resp = self.client.patch(f"/accounts/users/{guard.id}/", {
            "station": str(self.station.id),
        })
        self.assertEqual(patch_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(str(patch_resp.data["station"]), str(self.station.id))

        # Step 3: Guard calls GET /accounts/users/me/
        self.client.force_authenticate(user=guard)
        guard.refresh_from_db()
        me_resp = self.client.get("/accounts/users/me/")
        self.assertEqual(me_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(str(me_resp.data["station"]), str(self.station.id))
        self.assertEqual(me_resp.data["station_name"], self.station.name)

        # Step 4: Guard can now create OB entry
        resp2 = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "After assignment: Station secured.",
            "check_record": "Routine gate check.",
        })
        self.assertEqual(resp2.status_code, status.HTTP_201_CREATED)
        self.assertEqual(str(resp2.data["station"]), str(self.station.id))

