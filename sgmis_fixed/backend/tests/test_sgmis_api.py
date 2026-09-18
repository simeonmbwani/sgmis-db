from datetime import date, time, timedelta, datetime
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

        self.guard_e = UserModel.objects.create_user(
            username="guard_e",
            email="guard_e@sgmis.local",
            password=self.password,
            employee_number="SEC-505",
            role=UserRole.GUARD,
            station=self.station,
        )
        self.guard_f = UserModel.objects.create_user(
            username="guard_f",
            email="guard_f@sgmis.local",
            password=self.password,
            employee_number="SEC-506",
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
        self.pair3 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_e,
            guard_b=self.guard_f,
            rotation_order=3,
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
        # Ensure scheduled start time is within the clock-in window regardless of test execution time
        shift.start_time = (timezone.localtime() - timedelta(minutes=5)).time()
        shift.save()

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

        # Guard A submits handover without override when <12 hours elapsed
        self.client.force_authenticate(user=self.guard_a)
        handover_early = self.client.post("/shifts/handovers/", {
            "outgoing_shift": str(day_shift.id),
            "occurrence_summary": "All quiet on station. Perimeter secure.",
            "equipment_issued": "2 flashlights, 1 radio transceiver, 1 patrol baton.",
            "keys_handed_over": "Station gate keys, master padlock set.",
            "pending_issues": "None.",
            "supervisor_emergency_override": False,
        })
        # If less than 12 hours elapsed, it requires emergency authorization
        now = timezone.now()
        shift_start = timezone.make_aware(datetime.combine(day_shift.date, day_shift.start_time), timezone.get_current_timezone())
        if (now - shift_start).total_seconds() < 12 * 3600:
            self.assertEqual(handover_early.status_code, status.HTTP_400_BAD_REQUEST)
            self.assertIn("12-hour shift", handover_early.data["detail"])

        # With supervisor emergency authorization flag, handover is permitted
        handover_resp = self.client.post("/shifts/handovers/", {
            "outgoing_shift": str(day_shift.id),
            "occurrence_summary": "All quiet on station. Perimeter secure.",
            "equipment_issued": "2 flashlights, 1 radio transceiver, 1 patrol baton.",
            "keys_handed_over": "Station gate keys, master padlock set.",
            "pending_issues": "None.",
            "supervisor_emergency_override": True,
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

    def test_handover_rejection(self):
        today = timezone.now().date()
        generate_roster_for_station(self.station, today, cycle_days=2)
        day_shift = Shift.objects.filter(station=self.station, date=today, shift_type=ShiftType.DAY, guard=self.guard_a).first()
        self.assertIsNotNone(day_shift)
        resolved_incoming = resolve_incoming_guard(day_shift)
        self.assertIsNotNone(resolved_incoming)

        # Guard A submits handover with emergency override
        self.client.force_authenticate(user=self.guard_a)
        handover_resp = self.client.post("/shifts/handovers/", {
            "outgoing_shift": str(day_shift.id),
            "occurrence_summary": "Perimeter checked.",
            "equipment_issued": "Radio only.",
            "keys_handed_over": "Keys transferred.",
            "pending_issues": "None.",
            "supervisor_emergency_override": True,
        })
        self.assertEqual(handover_resp.status_code, status.HTTP_201_CREATED)
        handover_id = handover_resp.data["id"]

        # Supervisor cannot accept or reject (view-only)
        self.client.force_authenticate(user=self.supervisor)
        sup_rej = self.client.post(f"/shifts/handovers/{handover_id}/reject/")
        self.assertEqual(sup_rej.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("view-only", sup_rej.data["detail"])

        # Resolved incoming guard rejects handover with reason
        self.client.force_authenticate(user=resolved_incoming)
        reject_resp = self.client.post(f"/shifts/handovers/{handover_id}/reject/", {
            "reason": "Missing torch and broken baton."
        })
        self.assertEqual(reject_resp.status_code, status.HTTP_200_OK)
        self.assertFalse(reject_resp.data["incoming_accepted"])
        self.assertTrue(reject_resp.data["is_rejected"])
        self.assertIn("Missing torch and broken baton", reject_resp.data["pending_issues"])

    def test_generate_roster_endpoint(self):
        self.client.force_authenticate(user=self.admin)
        today = timezone.now().date()
        resp = self.client.post("/shifts/shifts/generate/", {
            "station_id": str(self.station.id),
            "start_date": str(today),
            "cycle_days": 4
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertIn("shifts_count", resp.data)
        self.assertIn("shifts_created", resp.data)
        self.assertGreater(resp.data["shifts_count"], 0)


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
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
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

    def test_acceptance_supervisor_station_creation(self):
        """
        TEST: Authorized supervisor can create a station.
        POST /stations/stations/
        -> HTTP 201
        -> record persists
        """
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post("/stations/stations/", {
            "name": "Supervisor Created Depot",
            "code": "STN-SUP01",
            "address": "Depot Road",
            "latitude": -1.2900,
            "longitude": 36.8200,
            "geofence_radius": 250,
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(resp.data["geofence_radius_meters"], 250.0)
        self.assertTrue(Station.objects.filter(code="STN-SUP01").exists())

    def test_regression_station_duplicate_code_returns_400(self):
        """
        TEST: Attempting to create a station with an existing code
        returns HTTP 400 with 'station with this code already exists.'
        """
        self.client.force_authenticate(user=self.admin)
        resp = self.client.post("/stations/stations/", {
            "name": "Another Bravo Post",
            "code": self.station.code,  # Already exists in DB from setUp
            "address": "Somewhere",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("code", resp.data)
        self.assertIn("already exists", str(resp.data["code"][0]))

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

    def test_patrol_start_auto_station_assignment(self):
        """
        Tests Patrol start:
        1. Guard with station calls POST /patrols/logs/ with empty payload
        2. Station is automatically derived from guard.station
        3. Response is 201 Created and station matches
        """
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/patrols/logs/", {})
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(str(resp.data["station"]), str(self.station.id))
        self.assertEqual(str(resp.data["guard"]), str(self.guard_a.id))
        self.assertEqual(resp.data["status"], "IN_PROGRESS")

    def test_patrol_start_unassigned_guard_rejected(self):
        """Guard without station receives clear error when starting patrol."""
        unassigned = UserModel.objects.create_user(
            username="unassigned_patrol_guard",
            password=self.password,
            role=UserRole.GUARD,
            station=None,
        )
        self.client.force_authenticate(user=unassigned)
        resp = self.client.post("/patrols/logs/", {})
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("station", str(resp.data).lower())

    def test_leave_rules_casual_and_vacation_accrual(self):
        """
        Verifies:
        - 1 month casual accrual = 1 day
        - 1 month vacation accrual = 2.5 days
        """
        balance = LeaveBalance.objects.create(
            guard=self.guard_a,
            year=2026,
            casual_days=0,
            vacation_days=0,
            last_accrual_date=date(2026, 1, 1),
            casual_cycle_start=date(2026, 1, 1),
        )
        # Accrue 1 month (Feb 1, 2026)
        balance.accrue_to_date(as_of=date(2026, 2, 1))
        self.assertEqual(float(balance.casual_days), 1.0)
        self.assertEqual(float(balance.vacation_days), 2.5)

    def test_leave_rules_casual_forfeiture_after_12_months(self):
        """
        Verifies unused casual leave is forfeited after 12 months.
        """
        balance = LeaveBalance.objects.create(
            guard=self.guard_a,
            year=2026,
            casual_days=10.0,
            used_casual=2.0,
            last_accrual_date=date(2026, 11, 1),
            casual_cycle_start=date(2026, 1, 1),
        )
        # Fast-forward 13 months to Feb 1, 2027
        balance.accrue_to_date(as_of=date(2027, 2, 1))
        # Previous cycle forfeited; only 1 month accrued in new cycle (Jan -> Feb)
        self.assertEqual(float(balance.casual_days), 1.0)
        self.assertEqual(float(balance.used_casual), 0.0)

    def test_leave_rules_vacation_cap_at_90_days(self):
        """
        Verifies vacation leave cannot exceed 90 days.
        """
        balance = LeaveBalance.objects.create(
            guard=self.guard_a,
            year=2026,
            vacation_days=88.0,
            last_accrual_date=date(2026, 1, 1),
            casual_cycle_start=date(2026, 1, 1),
        )
        # Accrue 2 months (adds 5 days: 88 + 5 = 93 -> capped at 90)
        balance.accrue_to_date(as_of=date(2026, 3, 1))
        self.assertEqual(float(balance.vacation_days), 90.0)

    def test_leave_rules_public_holiday_duty_compensation(self):
        """
        Verifies public holiday duty awards 2 days of leave compensation.
        """
        balance = LeaveBalance.objects.create(
            guard=self.guard_a,
            year=2026,
            vacation_days=10.0,
        )
        balance.credit_public_holiday_duty(days=2.0)
        self.assertEqual(float(balance.vacation_days), 12.0)

        # Also cannot exceed 90 days
        balance.vacation_days = 89.0
        balance.save()
        balance.credit_public_holiday_duty(days=2.0)
        self.assertEqual(float(balance.vacation_days), 90.0)

    def test_leave_deduction_and_supervisor_approval(self):
        """
        Verifies leave application, approval, and balance deduction.
        """
        today = date.today()
        balance = LeaveBalance.objects.create(
            guard=self.guard_a,
            year=2026,
            casual_days=5.0,
            used_casual=0.0,
            vacation_days=10.0,
            used_vacation=0.0,
            last_accrual_date=today,
            casual_cycle_start=today,
        )
        self.client.force_authenticate(user=self.guard_a)
        # Guard applies for 3 days of vacation leave
        apply_resp = self.client.post("/leave/applications/", {
            "leave_type": "VACATION",
            "start_date": str(today + timedelta(days=5)),
            "end_date": str(today + timedelta(days=7)),
            "reason": "Rest and recuperation",
        })
        self.assertEqual(apply_resp.status_code, status.HTTP_201_CREATED)
        app_id = apply_resp.data["id"]

        # Supervisor approves
        self.client.force_authenticate(user=self.supervisor)
        review_resp = self.client.post(f"/leave/applications/{app_id}/review/", {
            "status": "APPROVED",
            "reviewer_notes": "Granted.",
        })
        self.assertEqual(review_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(review_resp.data["status"], "APPROVED")

        # Check balance updated
        balance.refresh_from_db()
        self.assertEqual(float(balance.used_vacation), 3.0)
        self.assertEqual(float(balance.remaining_vacation), 7.0)

    def test_regression_guard_with_station_creates_ob_entry(self):
        """
        Regression Test A:
        Guard with assigned station can create an OB entry.
        - POST /occurrence_book/entries/ returns 201 Created.
        - Auto-generated entry_number is present and starts with 'OB-'.
        - Entry is saved to database with the guard's assigned station.
        """
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "Security checkpoint verified. All doors secured.",
            "check_record": "Door locks checked.",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertTrue(resp.data["entry_number"].startswith("OB-"))
        self.assertEqual(str(resp.data["station"]), str(self.station.id))

        entry = OccurrenceBookEntry.objects.get(id=resp.data["id"])
        self.assertEqual(entry.guard, self.guard_a)
        self.assertEqual(entry.station, self.station)
        self.assertIsNotNone(entry.station_id)

    def test_regression_guard_without_station_receives_http_400(self):
        """
        Regression Test B:
        Guard without station receives HTTP 400 instead of HTTP 500.
        - POST /occurrence_book/entries/ returns 400 Bad Request (ValidationError).
        - No 500 Internal Server Error occurs.
        - No database record is inserted with station_id=NULL.
        """
        unassigned_guard = UserModel.objects.create_user(
            username="unassigned_guard_reg",
            email="unassigned_reg@sgmis.local",
            password=self.password,
            employee_number="SEC-888",
            role=UserRole.GUARD,
            station=None,
        )
        initial_count = OccurrenceBookEntry.objects.count()
        self.client.force_authenticate(user=unassigned_guard)
        resp = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "Attempting OB entry without an assigned station.",
            "check_record": "Verified & Logged",
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertNotEqual(resp.status_code, status.HTTP_500_INTERNAL_SERVER_ERROR)
        error_msg = str(resp.data)
        self.assertIn("station", error_msg.lower())
        self.assertIn("assigned", error_msg.lower())
        self.assertEqual(OccurrenceBookEntry.objects.count(), initial_count)

    def test_regression_client_cannot_override_authenticated_user_station(self):
        """
        Regression Test C:
        Client cannot override the authenticated user's station.
        - Guard assigned to Station A passes Station B's ID in request payload.
        - Backend authoritatively binds entry to Station A (ignoring Station B).
        - DB record has station = Station A.
        """
        station_b = Station.objects.create(
            name="Station Beta",
            code="STN-BETA",
            latitude=-1.2921,
            longitude=36.8219,
        )
        self.client.force_authenticate(user=self.guard_a)
        # guard_a is assigned to self.station (not station_b)
        resp = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "Perimeter inspection log.",
            "check_record": "Verified",
            "station": str(station_b.id),
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        # Authoritative station must be self.station, NOT station_b
        self.assertEqual(str(resp.data["station"]), str(self.station.id))
        self.assertNotEqual(str(resp.data["station"]), str(station_b.id))

        entry = OccurrenceBookEntry.objects.get(id=resp.data["id"])
        self.assertEqual(entry.station, self.station)
        self.assertNotEqual(entry.station, station_b)

    def test_regression_patrol_guard_cannot_override_station(self):
        """
        Regression Test D (Patrol Station Authority):
        - Guard assigned to Station A passes Station B's ID in patrol start payload.
        - Backend authoritatively binds patrol log to Station A (ignoring Station B).
        - DB record has station = Station A.
        """
        station_b = Station.objects.create(
            name="Station Beta Patrol",
            code="STN-BETA-P",
            latitude=-1.2921,
            longitude=36.8219,
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/patrols/logs/", {
            "station": str(station_b.id),
            "notes": "Attempting to spoof station assignment.",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(str(resp.data["station"]), str(self.station.id))
        self.assertNotEqual(str(resp.data["station"]), str(station_b.id))

        log = PatrolLog.objects.get(id=resp.data["id"])
        self.assertEqual(log.station, self.station)
        self.assertNotEqual(log.station, station_b)

    def test_regression_guard_cannot_modify_authoritative_profile_fields(self):
        """
        Regression Test E (Profile Security Authority):
        - Guard calls PATCH /accounts/users/me/ attempting to promote self to ADMIN,
          change employee number, change duty station, and alter staff status.
        - Backend UserProfileUpdateSerializer permits ONLY first_name, last_name, phone_number, profile_photo.
        - Authoritative fields (role, employee_number, station, is_staff, is_superuser) remain unchanged.
        """
        new_station = Station.objects.create(
            name="Rogue Station",
            code="STN-ROGUE",
            latitude=-1.2921,
            longitude=36.8219,
        )
        original_role = self.guard_a.role
        original_emp_no = self.guard_a.employee_number
        original_station = self.guard_a.station

        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.patch("/accounts/users/me/", {
            "role": "ADMINISTRATOR",
            "employee_number": "HACKED_EMP_001",
            "station": str(new_station.id),
            "is_staff": True,
            "is_superuser": True,
            "first_name": "SimeonUpdated",
            "last_name": "MbwaniUpdated",
            "phone_number": "+263771234567",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        self.guard_a.refresh_from_db()
        # Permitted fields were updated
        self.assertEqual(self.guard_a.first_name, "SimeonUpdated")
        self.assertEqual(self.guard_a.last_name, "MbwaniUpdated")
        self.assertEqual(self.guard_a.phone_number, "+263771234567")

        # Authoritative security fields remain completely UNCHANGED
        self.assertEqual(self.guard_a.role, original_role)
        self.assertEqual(self.guard_a.employee_number, original_emp_no)
        self.assertEqual(self.guard_a.station, original_station)
        self.assertFalse(self.guard_a.is_staff)
        self.assertFalse(self.guard_a.is_superuser)

    def test_supervisor_restrictions_ob_incident_patrol(self):
        """Supervisors have view-only access to OB, Incident creation, and Patrol starting."""
        self.client.force_authenticate(user=self.supervisor)
        # OB creation
        ob_resp = self.client.post("/occurrence_book/entries/", {
            "category": "ROUTINE",
            "occurrence_text": "Supervisor attempting OB log.",
        })
        self.assertEqual(ob_resp.status_code, status.HTTP_403_FORBIDDEN)

        # Incident report creation
        inc_resp = self.client.post("/incidents/reports/", {
            "priority": "LOW",
            "title": "Supervisor incident attempt",
            "description": "Attempting incident submission as supervisor.",
            "location": "Post 1",
        })
        self.assertEqual(inc_resp.status_code, status.HTTP_403_FORBIDDEN)

        # Patrol start
        patrol_resp = self.client.post("/patrols/logs/", {
            "station": str(self.station.id),
            "notes": "Supervisor starting patrol",
        })
        self.assertEqual(patrol_resp.status_code, status.HTTP_403_FORBIDDEN)

    def test_guard_patrol_termination_guard_only(self):
        """Only on-duty guards can terminate their own active patrol."""
        self.client.force_authenticate(user=self.guard_a)
        start_resp = self.client.post("/patrols/logs/", {
            "notes": "Guard starting patrol",
        })
        self.assertEqual(start_resp.status_code, status.HTTP_201_CREATED)
        patrol_id = start_resp.data["id"]

        # Supervisor cannot terminate patrol
        self.client.force_authenticate(user=self.supervisor)
        sup_fin = self.client.post(f"/patrols/logs/{patrol_id}/finish/")
        self.assertEqual(sup_fin.status_code, status.HTTP_403_FORBIDDEN)

        # Guard terminates own patrol
        self.client.force_authenticate(user=self.guard_a)
        fin_resp = self.client.post(f"/patrols/logs/{patrol_id}/finish/", {"notes": "Patrol completed securely."})
        self.assertEqual(fin_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(fin_resp.data["status"], "COMPLETED")

    def test_leave_application_emergency_details_and_routing(self):
        """Leave application stores emergency contacts and notifies administrators."""
        self.client.force_authenticate(user=self.guard_a)
        today = timezone.now().date()
        leave_resp = self.client.post("/leave/applications/", {
            "leave_type": "VACATION",
            "start_date": str(today + timedelta(days=15)),
            "end_date": str(today + timedelta(days=17)),
            "reason": "Family gathering",
            "emergency_phone": "+263772998877",
            "emergency_address": "12 Samora Machel Ave, Harare",
        })
        self.assertEqual(leave_resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(leave_resp.data["emergency_phone"], "+263772998877")
        self.assertEqual(leave_resp.data["emergency_address"], "12 Samora Machel Ave, Harare")

        # Verify admin received notification
        from apps.notifications.models import Notification
        admin_notif = Notification.objects.filter(user=self.admin, notification_type="LEAVE_REQUEST").first()
        self.assertIsNotNone(admin_notif)

    def test_broadcast_notifications_authorization(self):
        """Only supervisors and admins can broadcast notices."""
        self.client.force_authenticate(user=self.guard_a)
        guard_resp = self.client.post("/notifications/alerts/broadcast/", {
            "title": "Guard Notice",
            "message": "Notice from guard.",
        })
        self.assertEqual(guard_resp.status_code, status.HTTP_403_FORBIDDEN)

        # Supervisor can broadcast
        self.client.force_authenticate(user=self.supervisor)
        sup_resp = self.client.post("/notifications/alerts/broadcast/", {
            "title": "Urgent Post Inspection",
            "message": "All guards ensure night gear is ready.",
        })
        self.assertEqual(sup_resp.status_code, status.HTTP_201_CREATED)
        self.assertGreater(sup_resp.data["recipients_count"], 0)

    def test_escort_duty_conflict_prevention(self):
        """Reject overlapping assignments if guard is active on another post/duty."""
        self.client.force_authenticate(user=self.admin)
        today = timezone.now().date()
        # Ensure guard_a has a shift today
        Shift.objects.get_or_create(
            station=self.station,
            guard=self.guard_a,
            date=today,
            defaults={
                "shift_type": ShiftType.DAY,
                "start_time": time(6, 0),
                "end_time": time(18, 0),
            }
        )

        # Attempt to assign guard_a to escort duty overlapping today
        now = timezone.now()
        conflict_resp = self.client.post("/escorts/duties/", {
            "guard": str(self.guard_a.id),
            "mission_name": "VIP Transport",
            "origin": "Airport",
            "destination": "Embassy",
            "start_time": now.isoformat(),
            "end_time": (now + timedelta(hours=4)).isoformat(),
        })
        self.assertEqual(conflict_resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("already assigned to active duty on another post", str(conflict_resp.data))

    def test_today_shift_for_supervisor_and_admin(self):
        today = timezone.localdate()
        generate_roster_for_station(self.station, today, cycle_days=2)

        # 1. Supervisor with station assigned views today's shift
        self.client.force_authenticate(user=self.supervisor)
        resp_sup = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp_sup.status_code, status.HTTP_200_OK)
        self.assertIn("station_name", resp_sup.data)
        self.assertEqual(resp_sup.data["station_name"], "Command Station Bravo")
        self.assertIsNotNone(resp_sup.data["guard_name"])
        self.assertIsNotNone(resp_sup.data["start_time"])
        self.assertIsNotNone(resp_sup.data["end_time"])

        # 2. Administrator views today's shift across the board
        self.client.force_authenticate(user=self.admin)
        resp_adm = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp_adm.status_code, status.HTTP_200_OK)
        self.assertIn("station_name", resp_adm.data)
        self.assertIsNotNone(resp_adm.data["guard_name"])

    def test_notification_broadcast_endpoints(self):
        self.client.force_authenticate(user=self.supervisor)

        # 1. Standard with trailing slash
        resp_slash = self.client.post("/notifications/alerts/broadcast/", {
            "title": "Weather Warning",
            "message": "Severe thunderstorm inbound. Secure all perimeter gates.",
        }, format="json")
        self.assertEqual(resp_slash.status_code, status.HTTP_201_CREATED)
        self.assertIn("Broadcast delivered", resp_slash.data["message"])

        # 2. Without trailing slash
        resp_no_slash = self.client.post("/notifications/alerts/broadcast", {
            "title": "Radio Check",
            "message": "Mandatory comms check at 14:00.",
        }, format="json")
        self.assertEqual(resp_no_slash.status_code, status.HTTP_201_CREATED)

        # 3. GET status probe
        resp_get = self.client.get("/notifications/alerts/broadcast/")
        self.assertEqual(resp_get.status_code, status.HTTP_200_OK)







