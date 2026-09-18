from datetime import date, time, timedelta
from unittest.mock import patch
from django.test import TestCase
from django.utils import timezone
from django.contrib.auth import get_user_model
from rest_framework.test import APIClient
from rest_framework import status
from django.core.exceptions import ValidationError as DjangoValidationError, PermissionDenied as DjangoPermissionDenied
from rest_framework.exceptions import ValidationError as DRFValidationError, PermissionDenied as DRFPermissionDenied

from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import (
    DutyRoster,
    RosterStatus,
    Shift,
    ShiftType,
    AssignmentType,
    Attendance,
)
from apps.shifts.services import (
    generate_roster_for_station,
    validate_duty_roster,
    approve_duty_roster,
    get_authoritative_roster_for_station,
)

UserModel = get_user_model()

class Part4RosterValidationAndApprovalTests(TestCase):
    """
    Authoritative test suite for Smart Security Part 4A:
    Persistent Roster Validation & Approval Workflow.
    Follows strict lifecycle: DRAFT -> VALIDATED -> APPROVED.
    No auto_approve shortcuts; real end-to-end service and API execution.
    """

    def setUp(self):
        self.client = APIClient()
        self.password = "SecPass123!"

        # Main Station
        self.station = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-HARARE-01",
            latitude=-17.8252,
            longitude=31.0335,
        )

        # Branch Station (for cross-station supervisor tests)
        self.branch_station = Station.objects.create(
            name="Branch Campus Security Post",
            code="STN-BULAWAYO-01",
            latitude=-20.1500,
            longitude=28.5833,
        )

        # Administrator
        self.admin = UserModel.objects.create_user(
            username="system_admin",
            email="admin@sgmis.local",
            password=self.password,
            employee_number="ADM-001",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
            station=self.station,
        )

        # Supervisors
        self.supervisor_main = UserModel.objects.create_user(
            username="sup_main",
            email="sup_main@sgmis.local",
            password=self.password,
            employee_number="SUP-001",
            role=UserRole.SUPERVISOR,
            station=self.station,
        )

        self.supervisor_branch = UserModel.objects.create_user(
            username="sup_branch",
            email="sup_branch@sgmis.local",
            password=self.password,
            employee_number="SUP-002",
            role=UserRole.SUPERVISOR,
            station=self.branch_station,
        )

        # 6 Guards assigned to Main Station
        self.guards = []
        for i in range(1, 7):
            g = UserModel.objects.create_user(
                username=f"guard_{i}",
                email=f"guard_{i}@sgmis.local",
                password=self.password,
                employee_number=f"SEC-10{i}",
                role=UserRole.GUARD,
                station=self.station,
            )
            self.guards.append(g)

        # 3 GuardPairs for Main Station with rotation_order 1, 2, 3
        self.pair1 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )
        self.pair2 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[2],
            guard_b=self.guards[3],
            rotation_order=2,
            is_active=True,
        )
        self.pair3 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[4],
            guard_b=self.guards[5],
            rotation_order=3,
            is_active=True,
        )

        self.start_date = date(2026, 9, 14)
        self.cycle_days = 12

    # =========================================================================
    # A. GENERATION CREATES DRAFT ROSTER
    # =========================================================================
    def test_roster_generation_creates_draft_roster(self):
        """
        Roster generation produces a DutyRoster in DRAFT status with no approval metadata.
        """
        created_shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        self.assertEqual(len(created_shifts), 72)

        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        self.assertEqual(roster.status, RosterStatus.DRAFT)
        self.assertIsNone(roster.approved_by)
        self.assertIsNone(roster.approved_at)
        self.assertEqual(roster.shifts.count(), 72)

        # Authoritative lookup does not treat DRAFT as active/authoritative
        auth_roster = get_authoritative_roster_for_station(self.station, self.start_date)
        self.assertIsNone(auth_roster)

    # =========================================================================
    # B. DRAFT CANNOT BE APPROVED DIRECTLY
    # =========================================================================
    def test_draft_cannot_be_approved_directly(self):
        """
        Attempting to approve a DRAFT roster directly must be rejected.
        Roster must transition to VALIDATED before it can be approved.
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        self.assertEqual(roster.status, RosterStatus.DRAFT)

        # Service-level rejection
        with self.assertRaises((DjangoValidationError, DRFValidationError)) as cm:
            approve_duty_roster(roster, self.supervisor_main)
        self.assertIn("Cannot approve DRAFT roster directly", str(cm.exception))

        # API-level rejection
        self.client.force_authenticate(user=self.supervisor_main)
        resp = self.client.post(
            "/shifts/shifts/approve_roster/",
            {"roster_id": str(roster.id)},
            format="json",
        )
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("Cannot approve DRAFT roster directly", str(resp.data))

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.DRAFT)
        self.assertIsNone(roster.approved_by)

    # =========================================================================
    # C. VALIDATED ROSTER CAN BE APPROVED
    # =========================================================================
    def test_validated_roster_can_be_approved(self):
        """
        Proper workflow: generate -> validate -> approve succeeds.
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)

        # Validate
        val_res = validate_duty_roster(roster)
        self.assertTrue(val_res["valid"])
        self.assertEqual(val_res["status"], RosterStatus.VALIDATED)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.VALIDATED)

        # Approve
        app_res = approve_duty_roster(roster, self.supervisor_main)
        self.assertTrue(app_res["approved"])
        self.assertEqual(app_res["status"], RosterStatus.APPROVED)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.APPROVED)
        self.assertEqual(roster.approved_by, self.supervisor_main)
        self.assertIsNotNone(roster.approved_at)

        # Authoritative lookup returns this approved roster
        auth_roster = get_authoritative_roster_for_station(self.station, self.start_date)
        self.assertIsNotNone(auth_roster)
        self.assertEqual(auth_roster.id, roster.id)

    # =========================================================================
    # D. APPROVAL PERSISTS APPROVED_BY AND APPROVED_AT
    # =========================================================================
    def test_approval_persists_approved_by_and_approved_at(self):
        """
        Approval persists valid audit metadata (approved_by, approved_at).
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)

        validate_duty_roster(roster)
        approve_duty_roster(roster, self.admin)

        roster.refresh_from_db()
        self.assertEqual(roster.approved_by, self.admin)
        self.assertIsNotNone(roster.approved_at)
        self.assertEqual(roster.status, RosterStatus.APPROVED)

    # =========================================================================
    # E. UNAUTHORIZED GUARD CANNOT APPROVE
    # =========================================================================
    def test_unauthorized_guard_cannot_approve(self):
        """
        Security guards cannot approve a duty roster (HTTP 403 Forbidden).
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        validate_duty_roster(roster)

        # Service-level rejection
        with self.assertRaises((DjangoPermissionDenied, DRFPermissionDenied)):
            approve_duty_roster(roster, self.guards[0])

        # API-level rejection
        self.client.force_authenticate(user=self.guards[0])
        resp = self.client.post(
            "/shifts/shifts/approve_roster/",
            {"roster_id": str(roster.id)},
            format="json",
        )
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.VALIDATED)
        self.assertIsNone(roster.approved_by)

    # =========================================================================
    # F. SUPERVISOR FROM ANOTHER STATION CANNOT APPROVE
    # =========================================================================
    def test_supervisor_from_another_station_cannot_approve(self):
        """
        Supervisors cannot approve rosters for stations outside their assignment (HTTP 403 Forbidden).
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        validate_duty_roster(roster)

        # Service-level rejection
        with self.assertRaises((DjangoPermissionDenied, DRFPermissionDenied)):
            approve_duty_roster(roster, self.supervisor_branch)

        # API-level rejection
        self.client.force_authenticate(user=self.supervisor_branch)
        resp = self.client.post(
            "/shifts/shifts/approve_roster/",
            {"roster_id": str(roster.id)},
            format="json",
        )
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.VALIDATED)
        self.assertIsNone(roster.approved_by)

    # =========================================================================
    # G. SECOND APPROVAL IS REJECTED
    # =========================================================================
    def test_second_approval_is_rejected(self):
        """
        Once approved, attempting to approve again must raise an error.
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        validate_duty_roster(roster)
        approve_duty_roster(roster, self.supervisor_main)

        # Second service call
        with self.assertRaises((DjangoValidationError, DRFValidationError)) as cm:
            approve_duty_roster(roster, self.supervisor_main)
        self.assertIn("already been approved", str(cm.exception))

        # Second API call
        self.client.force_authenticate(user=self.supervisor_main)
        resp = self.client.post(
            "/shifts/shifts/approve_roster/",
            {"roster_id": str(roster.id)},
            format="json",
        )
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("already been approved", str(resp.data))

    # =========================================================================
    # H. FINAL VALIDATION FAILURE PREVENTS APPROVAL
    # =========================================================================
    def test_final_validation_failure_prevents_approval(self):
        """
        If conditions change between validation and approval, final validation prevents approval.
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        validate_duty_roster(roster)

        # Break station requirement: deactivate one pair
        self.pair3.is_active = False
        self.pair3.save()

        # Approval must fail during final re-validation
        with self.assertRaises((DjangoValidationError, DRFValidationError)) as cm:
            approve_duty_roster(roster, self.supervisor_main)
        self.assertIn("must have exactly 3 active guard pairs", str(cm.exception))

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.VALIDATED)
        self.assertIsNone(roster.approved_by)

    # =========================================================================
    # I. APPROVAL ATOMICITY (ROLLS BACK ON FAILURE)
    # =========================================================================
    def test_approval_atomicity_rolls_back_on_failure(self):
        """
        If an unexpected error occurs during approval persistence,
        the transaction rolls back completely and status remains VALIDATED.
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        validate_duty_roster(roster)

        # Mock save to simulate database error during save
        with patch.object(DutyRoster, "save", side_effect=RuntimeError("Database write error during approval")):
            with self.assertRaises(RuntimeError):
                approve_duty_roster(roster, self.supervisor_main)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.VALIDATED)
        self.assertIsNone(roster.approved_by)

    # =========================================================================
    # J. APPROVED ROSTER CANNOT BE REGENERATED OVER
    # =========================================================================
    def test_approved_roster_cannot_be_regenerated_over(self):
        """
        Roster generation is strictly blocked if an APPROVED roster covers the period.
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        validate_duty_roster(roster)
        approve_duty_roster(roster, self.supervisor_main)

        initial_shift_count = Shift.objects.filter(roster=roster).count()

        # Attempt regeneration
        with self.assertRaises((DjangoValidationError, DRFValidationError)) as cm:
            generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        self.assertIn("already covers the requested generation period", str(cm.exception))

        # Shifts remain unchanged
        self.assertEqual(Shift.objects.filter(roster=roster).count(), initial_shift_count)

    # =========================================================================
    # K. SPECIALIZED ASSIGNMENTS AND ATTENDANCE PRESERVED IN WORKFLOW
    # =========================================================================
    def test_specialized_assignments_and_attendance_preserved_in_approval(self):
        """
        Rosters containing specialized assignments (EXAM/ESCORT/RELIEF) and attendance records
        successfully validate and approve without corruption.
        """
        created_shifts = generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days, mode="EXAM")
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)

        # Find an EXAM shift generated for the exam period
        exam_shift = Shift.objects.filter(roster=roster, assignment_type=AssignmentType.EXAM).first()
        self.assertIsNotNone(exam_shift)

        att = Attendance.objects.create(
            shift=exam_shift,
            guard=exam_shift.guard,
            clock_in=timezone.now(),
            clock_in_gps=f"{self.station.latitude},{self.station.longitude}",
        )

        val_res = validate_duty_roster(roster)
        self.assertTrue(val_res["valid"])

        app_res = approve_duty_roster(roster, self.supervisor_main)
        self.assertTrue(app_res["approved"])

        exam_shift.refresh_from_db()
        self.assertEqual(exam_shift.assignment_type, AssignmentType.EXAM)
        self.assertEqual(exam_shift.attendance_records.first(), att)
        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.APPROVED)

    # =========================================================================
    # L. GUARDS ONLY RECEIVE APPROVED OR ACTIVE ROSTERS VIA ENDPOINT
    # =========================================================================
    def test_guards_only_receive_approved_or_active_rosters_via_endpoint(self):
        """
        Guards cannot see DRAFT or VALIDATED rosters in /shifts/duty-rosters/.
        Only APPROVED or ACTIVE rosters are visible.
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)

        # Guard client
        self.client.force_authenticate(user=self.guards[0])

        # When DRAFT:
        resp = self.client.get("/shifts/duty-rosters/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        # Empty for guards
        results = resp.data.get("results", resp.data) if isinstance(resp.data, dict) else resp.data
        self.assertEqual(len(results), 0)

        # Detail view returns 404 for guard
        detail_resp = self.client.get(f"/shifts/duty-rosters/{roster.id}/")
        self.assertEqual(detail_resp.status_code, status.HTTP_404_NOT_FOUND)

        # Supervisor can see DRAFT
        self.client.force_authenticate(user=self.supervisor_main)
        sup_resp = self.client.get("/shifts/duty-rosters/")
        self.assertEqual(sup_resp.status_code, status.HTTP_200_OK)
        sup_results = sup_resp.data.get("results", sup_resp.data) if isinstance(sup_resp.data, dict) else sup_resp.data
        self.assertEqual(len(sup_results), 1)

        # Validate roster
        validate_duty_roster(roster)

        # Guard still cannot see VALIDATED roster
        self.client.force_authenticate(user=self.guards[0])
        resp2 = self.client.get("/shifts/duty-rosters/")
        results2 = resp2.data.get("results", resp2.data) if isinstance(resp2.data, dict) else resp2.data
        self.assertEqual(len(results2), 0)

        # Approve roster
        approve_duty_roster(roster, self.supervisor_main)

        # Guard now sees APPROVED roster
        resp3 = self.client.get("/shifts/duty-rosters/")
        results3 = resp3.data.get("results", resp3.data) if isinstance(resp3.data, dict) else resp3.data
        self.assertEqual(len(results3), 1)
        self.assertEqual(results3[0]["id"], str(roster.id))
        self.assertEqual(results3[0]["status"], RosterStatus.APPROVED)

        # Guard can retrieve detail view of APPROVED roster
        detail_resp3 = self.client.get(f"/shifts/duty-rosters/{roster.id}/")
        self.assertEqual(detail_resp3.status_code, status.HTTP_200_OK)
        self.assertEqual(detail_resp3.data["id"], str(roster.id))

    # =========================================================================
    # M. API END-TO-END VALIDATE AND APPROVE WORKFLOW
    # =========================================================================
    def test_api_end_to_end_validate_and_approve(self):
        """
        Full API workflow:
        1. POST /shifts/shifts/validate_roster/
        2. POST /shifts/shifts/approve_roster/
        """
        generate_roster_for_station(self.station, self.start_date, cycle_days=self.cycle_days)
        roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)

        self.client.force_authenticate(user=self.supervisor_main)

        # 1. Validate
        val_resp = self.client.post(
            "/shifts/shifts/validate_roster/",
            {"roster_id": str(roster.id)},
            format="json",
        )
        self.assertEqual(val_resp.status_code, status.HTTP_200_OK)
        self.assertTrue(val_resp.data["valid"])
        self.assertEqual(val_resp.data["status"], RosterStatus.VALIDATED)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.VALIDATED)

        # 2. Approve
        app_resp = self.client.post(
            "/shifts/shifts/approve_roster/",
            {"roster_id": str(roster.id)},
            format="json",
        )
        self.assertEqual(app_resp.status_code, status.HTTP_200_OK)
        self.assertTrue(app_resp.data["approved"])
        self.assertEqual(app_resp.data["status"], RosterStatus.APPROVED)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.APPROVED)
        self.assertEqual(roster.approved_by, self.supervisor_main)
