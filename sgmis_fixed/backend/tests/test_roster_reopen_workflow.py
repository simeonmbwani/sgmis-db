import uuid
from datetime import date, time, timedelta
from django.test import TestCase, RequestFactory
from django.utils import timezone
from django.contrib.auth import get_user_model
from django.contrib.admin.sites import AdminSite
from django.contrib.messages.storage.fallback import FallbackStorage
from django.core.exceptions import (
    ValidationError as DjangoValidationError,
    PermissionDenied as DjangoPermissionDenied,
)

from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.core.models import SecurityAuditEvent
from apps.shifts.models import (
    DutyRoster,
    RosterStatus,
    Shift,
    ShiftType,
    Attendance,
    ShiftHandover,
)
from apps.shifts.admin import DutyRosterAdmin
from apps.shifts.services import (
    generate_roster_for_station,
    validate_duty_roster,
    approve_duty_roster,
    reopen_duty_roster_as_draft,
)

UserModel = get_user_model()


class MockAdminSite(AdminSite):
    pass


class RosterReopenWorkflowTests(TestCase):
    """
    Comprehensive test suite for the administrator-only roster correction workflow.
    Verifies security gates, attendance/handover safety checks, atomicity,
    audit logging, immutability preservation, and end-to-end date correction.
    """

    def setUp(self):
        self.password = "AdminTest123!"

        # 1. Station
        self.station = Station.objects.create(
            name="Mashonaland West Campus",
            code="STN-MASH-01",
            latitude=-17.3667,
            longitude=30.2000,
        )

        # 2. Administrator
        self.admin_user = UserModel.objects.create_user(
            username="admin_officer",
            email="admin@sgmis.local",
            password=self.password,
            employee_number="ADM-901",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
            station=self.station,
        )

        # 3. Superuser (without explicit ADMINISTRATOR role)
        self.superuser = UserModel.objects.create_superuser(
            username="super_user",
            email="super@sgmis.local",
            password=self.password,
            employee_number="SU-001",
            role=UserRole.SUPERVISOR,
        )

        # 4. Supervisor
        self.supervisor = UserModel.objects.create_user(
            username="sup_roster",
            email="sup@sgmis.local",
            password=self.password,
            employee_number="SUP-901",
            role=UserRole.SUPERVISOR,
            is_staff=True,
            station=self.station,
        )

        # 5. Guards (6 guards for 3 pairs)
        self.guards = []
        for i in range(1, 7):
            g = UserModel.objects.create_user(
                username=f"reopen_guard_{i}",
                email=f"guard_{i}@sgmis.local",
                password=self.password,
                employee_number=f"GRD-90{i}",
                role=UserRole.GUARD,
                station=self.station,
            )
            self.guards.append(g)

        # 6. Active Pairs (1, 2, 3)
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

        self.start_date = date(2026, 10, 5)
        self.end_date = date(2026, 10, 8)

        # Generate, validate, and approve a baseline roster
        generate_roster_for_station(
            station=self.station,
            start_date=self.start_date,
            cycle_days=12,
            authorized_by=self.admin_user,
        )
        self.roster = DutyRoster.objects.get(station=self.station, start_date=self.start_date)
        validate_duty_roster(self.roster)
        approve_duty_roster(self.roster, self.admin_user)
        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.APPROVED)
        self.assertGreater(self.roster.shifts.count(), 0)

    def test_administrator_can_reopen_safe_approved_roster(self):
        """Administrator can reopen a safe approved roster to DRAFT."""
        initial_shifts_count = self.roster.shifts.count()
        self.assertGreater(initial_shifts_count, 0)

        deleted_shifts = reopen_duty_roster_as_draft(
            self.roster,
            admin_user=self.admin_user,
            reason="Incorrect start date entered; reopening for correction.",
        )

        self.assertEqual(deleted_shifts, initial_shifts_count)
        self.roster.refresh_from_db()

        self.assertEqual(self.roster.status, RosterStatus.DRAFT)
        self.assertIsNone(self.roster.approved_by)
        self.assertIsNone(self.roster.approved_at)
        self.assertEqual(self.roster.shifts.count(), 0)

        # Verify SecurityAuditEvent
        audit = SecurityAuditEvent.objects.filter(
            target_model="DutyRoster",
            target_id=str(self.roster.id),
        ).first()
        self.assertIsNotNone(audit)
        self.assertEqual(audit.actor, self.admin_user)
        self.assertEqual(audit.details["previous_status"], RosterStatus.APPROVED)
        self.assertEqual(audit.details["new_status"], RosterStatus.DRAFT)
        self.assertEqual(audit.details["deleted_generated_shift_count"], initial_shifts_count)
        self.assertIn("Incorrect start date", audit.details["reason"])

    def test_superuser_can_reopen_safe_approved_roster(self):
        """Superuser can reopen a safe approved roster even without ADMINISTRATOR role."""
        deleted_shifts = reopen_duty_roster_as_draft(
            self.roster,
            admin_user=self.superuser,
            reason="Superuser administrative correction",
        )
        self.assertGreater(deleted_shifts, 0)
        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.DRAFT)
        self.assertIsNone(self.roster.approved_by)

    def test_non_administrator_cannot_reopen(self):
        """Supervisors and Guards are denied from reopening rosters."""
        with self.assertRaises(DjangoPermissionDenied):
            reopen_duty_roster_as_draft(
                self.roster,
                admin_user=self.supervisor,
                reason="Supervisor trying to reopen",
            )

        with self.assertRaises(DjangoPermissionDenied):
            reopen_duty_roster_as_draft(
                self.roster,
                admin_user=self.guards[0],
                reason="Guard trying to reopen",
            )

        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.APPROVED)
        self.assertGreater(self.roster.shifts.count(), 0)

    def test_validated_roster_is_handled_correctly(self):
        """A VALIDATED roster (not APPROVED) cannot be reopened via this workflow."""
        # Create a second roster in VALIDATED status
        start_d = date(2026, 11, 1)
        generate_roster_for_station(self.station, start_date=start_d, cycle_days=12)
        val_roster = DutyRoster.objects.get(station=self.station, start_date=start_d)
        validate_duty_roster(val_roster)
        val_roster.refresh_from_db()
        self.assertEqual(val_roster.status, RosterStatus.VALIDATED)

        with self.assertRaises(DjangoValidationError) as cm:
            reopen_duty_roster_as_draft(
                val_roster,
                admin_user=self.admin_user,
                reason="Attempting to reopen validated roster",
            )
        self.assertIn("Only APPROVED rosters can be reopened", str(cm.exception))

    def test_draft_roster_is_not_reopened(self):
        """A DRAFT roster is rejected by the reopen workflow (it is already draft)."""
        start_d = date(2026, 11, 15)
        generate_roster_for_station(self.station, start_date=start_d, cycle_days=12)
        draft_roster = DutyRoster.objects.get(station=self.station, start_date=start_d)
        self.assertEqual(draft_roster.status, RosterStatus.DRAFT)

        with self.assertRaises(DjangoValidationError) as cm:
            reopen_duty_roster_as_draft(
                draft_roster,
                admin_user=self.admin_user,
                reason="Reopening draft roster",
            )
        self.assertIn("Only APPROVED rosters can be reopened", str(cm.exception))

    def test_roster_with_attendance_is_rejected(self):
        """Roster with any Attendance records cannot be reopened."""
        first_shift = self.roster.shifts.first()
        Attendance.objects.create(
            shift=first_shift,
            guard=first_shift.guard,
        )

        with self.assertRaises(DjangoValidationError) as cm:
            reopen_duty_roster_as_draft(
                self.roster,
                admin_user=self.admin_user,
                reason="Reopen attempt with attendance",
            )
        self.assertIn("attendance records exist", str(cm.exception))

        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.APPROVED)
        self.assertGreater(self.roster.shifts.count(), 0)

    def test_roster_with_clock_in_records_is_rejected(self):
        """Roster with active clock-in records is rejected."""
        first_shift = self.roster.shifts.first()
        Attendance.objects.create(
            shift=first_shift,
            guard=first_shift.guard,
            clock_in=timezone.now(),
        )

        with self.assertRaises(DjangoValidationError) as cm:
            reopen_duty_roster_as_draft(
                self.roster,
                admin_user=self.admin_user,
                reason="Reopen attempt with clock-in",
            )
        self.assertIn("clock-in records", str(cm.exception))

        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.APPROVED)

    def test_roster_with_handover_records_is_rejected(self):
        """Roster with ShiftHandover records is rejected."""
        first_shift = self.roster.shifts.first()
        ShiftHandover.objects.create(
            outgoing_shift=first_shift,
            outgoing_guard=self.guards[0],
            incoming_guard=self.guards[1],
            station=self.station,
            occurrence_summary="Operational log",
        )

        with self.assertRaises(DjangoValidationError) as cm:
            reopen_duty_roster_as_draft(
                self.roster,
                admin_user=self.admin_user,
                reason="Reopen attempt with handover",
            )
        self.assertIn("shift handover records exist", str(cm.exception))

        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.APPROVED)

    def test_empty_reason_is_rejected(self):
        """Empty reason string is rejected."""
        with self.assertRaises(DjangoValidationError) as cm:
            reopen_duty_roster_as_draft(
                self.roster,
                admin_user=self.admin_user,
                reason="   ",
            )
        self.assertIn("reason is required", str(cm.exception))

    def test_normal_approved_roster_immutability_remains_intact(self):
        """Normal edits, date changes, and deletes on approved rosters remain protected."""
        # 1. Date change rejected by clean()
        self.roster.start_date = date(2026, 10, 6)
        with self.assertRaises(DjangoValidationError) as cm:
            self.roster.clean()
        self.assertIn("Start date cannot be modified on an approved roster", str(cm.exception))

        # Reset in-memory start_date
        self.roster.start_date = self.start_date

        # 2. Status downgrade rejected by clean()
        self.roster.status = RosterStatus.DRAFT
        with self.assertRaises(DjangoValidationError) as cm:
            self.roster.clean()
        self.assertIn("An approved roster cannot be reverted to draft", str(cm.exception))

        # Reset in-memory status
        self.roster.status = RosterStatus.APPROVED

        # 3. Deletion rejected by delete()
        with self.assertRaises(DjangoValidationError) as cm:
            self.roster.delete()
        self.assertIn("An approved duty roster is protected and cannot be deleted", str(cm.exception))

    def test_admin_action_execution_and_permission_filter(self):
        """Django Admin action behaves correctly for authorized and unauthorized users."""
        factory = RequestFactory()
        admin_site = MockAdminSite()
        model_admin = DutyRosterAdmin(DutyRoster, admin_site)

        # 1. Non-admin does not have action in get_actions()
        request_sup = factory.post("/admin/shifts/dutyroster/")
        request_sup.user = self.supervisor
        actions = model_admin.get_actions(request_sup)
        self.assertNotIn("reopen_selected_rosters_to_draft", actions)

        # 2. Admin has action in get_actions()
        request_admin = factory.post("/admin/shifts/dutyroster/")
        request_admin.user = self.admin_user
        actions = model_admin.get_actions(request_admin)
        self.assertIn("reopen_selected_rosters_to_draft", actions)

        # 3. Execute action without reason -> rejected
        request_admin.POST = {"action": "reopen_selected_rosters_to_draft", "reason": ""}
        setattr(request_admin, "session", "session")
        messages = FallbackStorage(request_admin)
        setattr(request_admin, "_messages", messages)

        qs = DutyRoster.objects.filter(pk=self.roster.pk)
        model_admin.reopen_selected_rosters_to_draft(request_admin, qs)

        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.APPROVED)

        # 4. Execute action with valid reason -> success
        request_admin.POST = {
            "action": "reopen_selected_rosters_to_draft",
            "reason": "Administrative date correction via Admin action",
        }
        model_admin.reopen_selected_rosters_to_draft(request_admin, qs)

        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.DRAFT)
        self.assertEqual(self.roster.shifts.count(), 0)

    def test_reopened_roster_allows_date_correction_and_reapproval(self):
        """End-to-end workflow: reopen -> edit dates -> regenerate -> validate -> re-approve."""
        # 1. Reopen
        reopen_duty_roster_as_draft(
            self.roster,
            admin_user=self.admin_user,
            reason="Correcting dates from Oct 5 to Oct 12",
        )
        self.roster.refresh_from_db()
        self.assertEqual(self.roster.status, RosterStatus.DRAFT)
        self.assertEqual(self.roster.shifts.count(), 0)

        # 2. Edit dates on DRAFT roster
        new_start = date(2026, 10, 20)
        new_end = date(2026, 10, 31)
        self.roster.start_date = new_start
        self.roster.end_date = new_end
        self.roster.clean()  # Must pass clean() cleanly
        self.roster.save()

        # 3. Regenerate shifts for the new date range
        generate_roster_for_station(
            station=self.station,
            start_date=new_start,
            cycle_days=12,
            authorized_by=self.admin_user,
        )
        updated_roster = DutyRoster.objects.get(station=self.station, start_date=new_start)
        self.assertEqual(updated_roster.id, self.roster.id)
        self.assertGreater(updated_roster.shifts.count(), 0)

        # 4. Validate
        val_res = validate_duty_roster(updated_roster)
        self.assertTrue(val_res["valid"])
        updated_roster.refresh_from_db()
        self.assertEqual(updated_roster.status, RosterStatus.VALIDATED)

        # 5. Re-approve
        app_res = approve_duty_roster(updated_roster, self.admin_user)
        self.assertTrue(app_res["approved"])
        updated_roster.refresh_from_db()
        self.assertEqual(updated_roster.status, RosterStatus.APPROVED)
        self.assertEqual(updated_roster.start_date, new_start)
        self.assertEqual(updated_roster.end_date, new_end)
        self.assertEqual(updated_roster.approved_by, self.admin_user)
