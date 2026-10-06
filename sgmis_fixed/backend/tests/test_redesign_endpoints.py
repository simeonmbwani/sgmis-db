import uuid
from datetime import date, timedelta
from django.utils import timezone
from django.contrib.auth import get_user_model
from rest_framework.test import APITestCase
from rest_framework import status

from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair, GuardPairReassignmentAudit
from apps.core.models import OrganizationPolicy, SecurityAuditEvent
from apps.shifts.models import Shift, ShiftType, AssignmentType, RosterStatus, DutyOverride, Attendance
from apps.leave.models import LeaveApplication, LeaveType, LeaveStatus, PublicHolidayCompensationLedger
from apps.patrols.models import PatrolLog, PatrolStatus, Checkpoint, CheckpointScan
from apps.incidents.models import IncidentReport, IncidentPriority, IncidentStatus

User = get_user_model()


class RedesignEndpointsTests(APITestCase):
    def setUp(self):
        self.station1 = Station.objects.create(
            name="Alpha Station",
            code="ALPHA",
            latitude=-17.824858,
            longitude=31.053028,
            geofence_radius_meters=150.0,
            is_active=True,
        )
        self.station2 = Station.objects.create(
            name="Beta Station",
            code="BETA",
            latitude=-17.900000,
            longitude=31.100000,
            geofence_radius_meters=200.0,
            is_active=True,
        )

        self.admin = User.objects.create_user(
            username="admin_redesign",
            password="Password123!",
            role=UserRole.ADMINISTRATOR,
            employee_number="ADM-001",
        )
        self.supervisor1 = User.objects.create_user(
            username="sup_alpha",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station1,
            employee_number="SUP-001",
        )
        self.supervisor2 = User.objects.create_user(
            username="sup_beta",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station2,
            employee_number="SUP-002",
        )
        self.guard1 = User.objects.create_user(
            username="guard1_alpha",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station1,
            employee_number="GRD-001",
        )
        self.guard2 = User.objects.create_user(
            username="guard2_alpha",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station1,
            employee_number="GRD-002",
        )
        self.guard3 = User.objects.create_user(
            username="guard3_alpha",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station1,
            employee_number="GRD-003",
        )
        self.guard_beta = User.objects.create_user(
            username="guard_beta",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station2,
            employee_number="GRD-004",
        )

        self.pair1 = GuardPair.objects.create(
            station=self.station1,
            guard_a=self.guard1,
            guard_b=self.guard2,
            rotation_order=1,
            is_active=True,
        )
        self.pair2 = GuardPair.objects.create(
            station=self.station1,
            guard_a=self.guard3,
            guard_b=self.guard2,  # placeholder
            rotation_order=2,
            is_active=True,
        )

    # 1. Supervisor Dashboard
    def test_supervisor_dashboard_returns_authoritative_metrics(self):
        self.client.force_authenticate(user=self.supervisor1)
        today = timezone.localdate()

        import datetime as dt
        shift = Shift.objects.create(
            guard=self.guard1,
            station=self.station1,
            date=today,
            start_time=dt.time(6, 0),
            end_time=dt.time(18, 0),
            shift_type=ShiftType.DAY,
        )

        Attendance.objects.create(
            shift=shift,
            guard=self.guard1,
            clock_in=timezone.now(),
        )

        # Active patrol
        PatrolLog.objects.create(
            name="Alpha Perimeter",
            guard=self.guard1,
            station=self.station1,
            status=PatrolStatus.IN_PROGRESS,
        )

        # Open incident
        IncidentReport.objects.create(
            station=self.station1,
            reporting_guard=self.guard1,
            title="Broken Fence",
            description="Perimeter breach detected",
            priority=IncidentPriority.HIGH,
            status=IncidentStatus.REPORTED,
        )

        resp = self.client.get("/api/core/dashboard/supervisor/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        data = resp.data
        self.assertEqual(data["station"]["code"], "ALPHA")
        self.assertEqual(data["supervisor"]["name"], "sup_alpha")
        self.assertEqual(data["guards_on_post"], 1)
        self.assertEqual(data["active_patrols"], 1)
        self.assertEqual(data["open_incidents"], 1)
        self.assertGreater(len(data["live_ops"]), 0)

    # 2. Admin Dashboard
    def test_admin_dashboard_returns_national_metrics(self):
        self.client.force_authenticate(user=self.admin)
        resp = self.client.get("/api/core/dashboard/admin/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        data = resp.data
        self.assertIn("total_guards", data)
        self.assertIn("total_stations", data)
        self.assertIn("active_duties", data)
        self.assertIn("active_patrols", data)
        self.assertIn("open_incidents", data)
        self.assertEqual(data["administrator"]["role"], UserRole.ADMINISTRATOR)

    # 3. Organization Policies
    def test_organization_policy_lifecycle(self):
        self.client.force_authenticate(user=self.admin)
        policy = OrganizationPolicy.objects.create(
            category=OrganizationPolicy.Category.LEAVE,
            title="Annual Leave Standard",
            summary="24 days entitlement",
            content="Detailed annual leave policy clauses.",
            version="1.0",
        )

        # Guard can read policies
        self.client.force_authenticate(user=self.guard1)
        list_resp = self.client.get("/api/core/policies/")
        self.assertEqual(list_resp.status_code, status.HTTP_200_OK)
        self.assertTrue(len(list_resp.data) >= 1)

        # Guard cannot create policy
        create_resp = self.client.post("/api/core/policies/", {
            "category": "DUTY",
            "title": "Hacked Policy",
            "content": "Malicious content",
        })
        self.assertEqual(create_resp.status_code, status.HTTP_403_FORBIDDEN)

        # Admin can update policy
        self.client.force_authenticate(user=self.admin)
        patch_resp = self.client.patch(f"/api/core/policies/{policy.id}/", {
            "version": "1.1",
            "summary": "Updated summary",
        })
        self.assertEqual(patch_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(patch_resp.data["version"], "1.1")

    # 4. Duty Override / Leave Interruption
    def test_duty_override_creates_relief_shift_and_compensation(self):
        self.client.force_authenticate(user=self.supervisor1)
        tomorrow = timezone.localdate() + timedelta(days=1)

        # Create approved leave for guard2
        leave = LeaveApplication.objects.create(
            guard=self.guard2,
            leave_type=LeaveType.ANNUAL,
            start_date=tomorrow,
            end_date=tomorrow + timedelta(days=2),
            reason="Vacation",
            status=LeaveStatus.APPROVED,
        )

        resp = self.client.post("/api/shifts/duty-overrides/", {
            "guard": str(self.guard2.id),
            "date": tomorrow.isoformat(),
            "shift_type": ShiftType.DAY,
            "override_type": "LEAVE_INTERRUPTION",
            "reason": "Emergency campus coverage required",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        override_id = resp.data["id"]

        # Check relief shift was created
        relief_shift = Shift.objects.filter(guard=self.guard2, date=tomorrow).first()
        self.assertIsNotNone(relief_shift)
        self.assertEqual(relief_shift.assignment_type, AssignmentType.RELIEF)

        # Check compensation ledger was credited
        ledger_entry = PublicHolidayCompensationLedger.objects.filter(duty_override_id=override_id, entry_type="EARNED").first()
        self.assertIsNotNone(ledger_entry)
        self.assertEqual(ledger_entry.days, 1.0)

        # Settle compensation
        settle_resp = self.client.post(f"/api/shifts/duty-overrides/{override_id}/settle_compensation/")
        self.assertEqual(settle_resp.status_code, status.HTTP_200_OK)
        self.assertTrue(settle_resp.data["override"]["compensation_settled"])

        # Supervisor cannot override duty for guard at another station
        bad_resp = self.client.post("/api/shifts/duty-overrides/", {
            "guard": str(self.guard_beta.id),
            "date": tomorrow.isoformat(),
            "reason": "Invalid cross station",
        })
        self.assertEqual(bad_resp.status_code, status.HTTP_403_FORBIDDEN)

    # 5. Guard Pair Reassignment
    def test_pair_reassignment_with_audit_trail(self):
        self.client.force_authenticate(user=self.supervisor1)
        today = timezone.localdate()

        resp = self.client.post("/api/stations/pairs/reassign_guard/", {
            "guard": str(self.guard1.id),
            "new_pair": str(self.pair2.id),
            "effective_date": today.isoformat(),
            "reason": "Re-pairing guard to balance experience level",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)

        # Verify audit record created
        audits = GuardPairReassignmentAudit.objects.filter(guard=self.guard1)
        self.assertEqual(audits.count(), 1)
        audit = audits.first()
        self.assertEqual(audit.station, self.station1)
        self.assertEqual(audit.new_pair, self.pair2)

        # Check historical pair reassignments endpoint
        hist_resp = self.client.get("/api/stations/pair-reassignments/")
        self.assertEqual(hist_resp.status_code, status.HTTP_200_OK)
        self.assertTrue(len(hist_resp.data) >= 1)

    # 6. Patrol Search & Filters
    def test_patrol_filtering_and_search(self):
        self.client.force_authenticate(user=self.supervisor1)
        p1 = PatrolLog.objects.create(
            name="North Gate Perimeter",
            guard=self.guard1,
            station=self.station1,
            status=PatrolStatus.IN_PROGRESS,
        )
        p2 = PatrolLog.objects.create(
            name="South Parking Check",
            guard=self.guard2,
            station=self.station1,
            status=PatrolStatus.ASSIGNED,
        )

        # Search by keyword
        resp_search = self.client.get("/api/patrols/logs/?search=North")
        self.assertEqual(resp_search.status_code, status.HTTP_200_OK)
        ids = [p["id"] for p in resp_search.data.get("results", resp_search.data)]
        self.assertIn(str(p1.id), ids)
        self.assertNotIn(str(p2.id), ids)

        # Filter by status
        resp_status = self.client.get("/api/patrols/logs/?status=ASSIGNED")
        self.assertEqual(resp_status.status_code, status.HTTP_200_OK)
        ids = [p["id"] for p in resp_status.data.get("results", resp_status.data)]
        self.assertIn(str(p2.id), ids)
        self.assertNotIn(str(p1.id), ids)

    # 7. Administrative History includes New Audit Types
    def test_administrative_history_includes_pair_and_duty_overrides(self):
        self.client.force_authenticate(user=self.admin)
        today = timezone.localdate()

        # Create pair reassignment audit
        GuardPairReassignmentAudit.objects.create(
            station=self.station1,
            guard=self.guard1,
            old_pair=self.pair1,
            new_pair=self.pair2,
            effective_date=today,
            reason="Routine rotation audit test",
            authorized_by=self.supervisor1,
        )

        # Create duty override
        DutyOverride.objects.create(
            guard=self.guard2,
            station=self.station1,
            date=today,
            reason="Operational coverage",
            authorized_by=self.supervisor1,
        )

        resp = self.client.get("/api/core/admin-history/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        kinds = [entry["kind"] for entry in resp.data]
        self.assertIn("PAIR_REASSIGNMENT", kinds)
        self.assertIn("DUTY_OVERRIDE", kinds)

        # Filter by kind
        resp_filtered = self.client.get("/api/core/admin-history/?kind=PAIR_REASSIGNMENT")
        self.assertEqual(resp_filtered.status_code, status.HTTP_200_OK)
        for entry in resp_filtered.data:
            self.assertEqual(entry["kind"], "PAIR_REASSIGNMENT")
