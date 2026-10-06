import uuid
from django.utils import timezone
from rest_framework.test import APITestCase
from rest_framework import status
from apps.accounts.models import User, UserRole
from apps.stations.models import Station
from apps.patrols.models import PatrolLog, PatrolStatus
from apps.incidents.models import IncidentReport, IncidentPriority, IncidentStatus

class SupervisorPatrolAndIncidentResolutionTests(APITestCase):
    def setUp(self):
        # Station A and Station B
        self.station_a = Station.objects.create(
            name="Station Alpha",
            code="STA-A",
            latitude=-17.8252,
            longitude=31.0335,
        )
        self.station_b = Station.objects.create(
            name="Station Beta",
            code="STA-B",
            latitude=-17.8300,
            longitude=31.0400,
        )

        # Supervisor A (Station A)
        self.supervisor_a = User.objects.create_user(
            username="sup_alpha",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station_a,
            employee_number="SUP001",
        )

        # Supervisor B (Station B)
        self.supervisor_b = User.objects.create_user(
            username="sup_beta",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station_b,
            employee_number="SUP002",
        )

        # Guard A (Station A)
        self.guard_a = User.objects.create_user(
            username="guard_alpha",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station_a,
            employee_number="GRD001",
        )

        # Guard B (Station B)
        self.guard_b = User.objects.create_user(
            username="guard_beta",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station_b,
            employee_number="GRD002",
        )

        # Admin
        self.admin = User.objects.create_superuser(
            username="admin_user",
            password="Password123!",
            email="admin@example.com",
            role=UserRole.ADMINISTRATOR,
            employee_number="ADM001",
        )

    # -------------------------------------------------------------------------
    # PATROL TESTS
    # -------------------------------------------------------------------------

    def test_1_supervisor_can_assign_patrol_to_guard_at_same_station(self):
        """1. Supervisor can assign patrol to guard at same station, including blank/null notes."""
        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.post("/api/patrols/logs/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station_a.id),
            "name": "Perimeter Sweep Alpha",
            "notes": None,  # Regression: Android sends null when field is blank
        }, format="json")
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(str(resp.data["guard"]), str(self.guard_a.id))
        self.assertEqual(str(resp.data["station"]), str(self.station_a.id))
        self.assertEqual(resp.data["status"], PatrolStatus.ASSIGNED)
        self.assertEqual(resp.data["notes"], "")

    def test_2_supervisor_cannot_assign_guard_from_another_station(self):
        """2. Supervisor cannot assign guard from another station (Station isolation enforced)."""
        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.post("/api/patrols/logs/", {
            "guard": str(self.guard_b.id),
            "station": str(self.station_a.id),
            "name": "Cross-Station Sweep",
        }, format="json")
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

    def test_3_supervisor_cannot_assign_himself(self):
        """3. Supervisor cannot assign himself if existing rule prohibits proxy execution."""
        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.post("/api/patrols/logs/", {
            "guard": str(self.supervisor_a.id),
            "station": str(self.station_a.id),
            "name": "Self Assignment Sweep",
        }, format="json")
        # Prohibited by proxy execution rule
        self.assertIn(resp.status_code, [status.HTTP_400_BAD_REQUEST, status.HTTP_403_FORBIDDEN])

    def test_4_guard_cannot_arbitrarily_create_operational_patrol(self):
        """4. Guard cannot arbitrarily create operational patrol rounds."""
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post("/api/patrols/logs/", {
            "guard": str(self.guard_a.id),
            "station": str(self.station_a.id),
            "name": "Unauthorized Guard Patrol",
        }, format="json")
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

    def test_5_existing_admin_patrol_authority_still_works(self):
        """5. Existing admin patrol authority still works (monitoring across stations and approval)."""
        patrol_a = PatrolLog.objects.create(
            guard=self.guard_a,
            station=self.station_a,
            assigned_by=self.supervisor_a,
            status=PatrolStatus.COMPLETED,
        )
        self.client.force_authenticate(user=self.admin)
        resp = self.client.get("/api/patrols/logs/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        # Admin can approve completed patrol
        approve_resp = self.client.post(f"/api/patrols/logs/{patrol_a.id}/approve/")
        self.assertEqual(approve_resp.status_code, status.HTTP_200_OK)
        patrol_a.refresh_from_db()
        self.assertTrue(patrol_a.is_approved)

    # -------------------------------------------------------------------------
    # INCIDENT TESTS
    # -------------------------------------------------------------------------

    def test_7_supervisor_can_resolve_critical_incident_at_own_station(self):
        """7. Supervisor can resolve critical incident at own station."""
        incident = IncidentReport.objects.create(
            station=self.station_a,
            reporting_guard=self.guard_a,
            priority=IncidentPriority.CRITICAL,
            title="Perimeter Breach Incursion",
            description="Intruders breached sector 4.",
            location="North Fence",
        )
        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.post(f"/api/incidents/reports/{incident.id}/resolve/", {
            "resolution_notes": "Armed response secured sector 4. Intruders detained.",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        incident.refresh_from_db()
        self.assertEqual(incident.status, IncidentStatus.RESOLVED)
        self.assertEqual(incident.resolution_notes, "Armed response secured sector 4. Intruders detained.")

    def test_8_supervisor_cannot_resolve_critical_incident_from_another_station(self):
        """8. Supervisor cannot resolve critical incident from another station."""
        incident_b = IncidentReport.objects.create(
            station=self.station_b,
            reporting_guard=self.guard_b,
            priority=IncidentPriority.CRITICAL,
            title="Station B Incident",
            description="Power outage at station B.",
            location="Generator Room",
        )
        # Supervisor A attempts to resolve Station B incident -> Denied (404 via scoping or 403)
        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.post(f"/api/incidents/reports/{incident_b.id}/resolve/", {
            "resolution_notes": "Supervisor A trying to resolve station B.",
        })
        self.assertIn(resp.status_code, [status.HTTP_403_FORBIDDEN, status.HTTP_404_NOT_FOUND])
        incident_b.refresh_from_db()
        self.assertNotEqual(incident_b.status, IncidentStatus.RESOLVED)

    def test_9_guard_cannot_resolve_supervisor_managed_critical_incident(self):
        """9. Guard cannot resolve supervisor-managed critical incident."""
        incident = IncidentReport.objects.create(
            station=self.station_a,
            reporting_guard=self.guard_a,
            priority=IncidentPriority.CRITICAL,
            title="Critical Alarm Triggered",
            description="Sensor malfunction or tamper.",
            location="Main Vault",
        )
        self.client.force_authenticate(user=self.guard_a)
        resp = self.client.post(f"/api/incidents/reports/{incident.id}/resolve/", {
            "resolution_notes": "Guard attempting unauthorized close.",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        incident.refresh_from_db()
        self.assertNotEqual(incident.status, IncidentStatus.RESOLVED)

    def test_10_existing_admin_incident_authority_still_works(self):
        """10. Existing admin incident authority still works."""
        incident = IncidentReport.objects.create(
            station=self.station_a,
            reporting_guard=self.guard_a,
            priority=IncidentPriority.CRITICAL,
            title="Central Security Alert",
            description="System-wide review required.",
            location="Alpha Command",
        )
        self.client.force_authenticate(user=self.admin)
        resp = self.client.post(f"/api/incidents/reports/{incident.id}/resolve/", {
            "resolution_notes": "Admin verified and closed alert.",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        incident.refresh_from_db()
        self.assertEqual(incident.status, IncidentStatus.RESOLVED)

    def test_11_incident_status_and_audit_correct_after_resolution(self):
        """11. Incident status remains correct and resolution notes are preserved."""
        incident = IncidentReport.objects.create(
            station=self.station_a,
            reporting_guard=self.guard_a,
            priority=IncidentPriority.HIGH,
            title="Suspicious Vehicle",
            description="Vehicle parked near gate for 2 hours.",
            location="Gate 1",
            status=IncidentStatus.INVESTIGATING,
        )
        self.client.force_authenticate(user=self.supervisor_a)
        resp = self.client.post(f"/api/incidents/reports/{incident.id}/resolve/", {
            "resolution_notes": "Driver questioned, vehicle departed safely.",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["status"], IncidentStatus.RESOLVED)
        self.assertEqual(resp.data["resolution_notes"], "Driver questioned, vehicle departed safely.")
