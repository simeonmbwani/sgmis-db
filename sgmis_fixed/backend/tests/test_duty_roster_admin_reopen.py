from datetime import date, time, timedelta
from unittest.mock import patch

from django.contrib import admin
from django.contrib.auth import get_user_model
from django.contrib.auth.models import Permission
from django.core.exceptions import PermissionDenied, ValidationError
from django.test import RequestFactory, TestCase
from django.utils import timezone

from apps.accounts.models import UserRole
from apps.core.models import SecurityAuditEvent
from apps.shifts.admin import DutyRosterAdmin
from apps.shifts.models import (
    Attendance,
    DutyRoster,
    RosterStatus,
    Shift,
    ShiftHandover,
    ShiftType,
)
from apps.shifts.services import reopen_duty_roster_as_draft
from apps.stations.models import Station


User = get_user_model()


class ReopenDutyRosterServiceTests(TestCase):
    def setUp(self):
        self.station = Station.objects.create(
            name="Reopen Workflow Station",
            code="REOPEN-01",
        )
        self.admin_user = User.objects.create_user(
            username="roster_admin",
            password="test-only-password",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
        )
        self.superuser = User.objects.create_superuser(
            username="roster_superuser",
            password="test-only-password",
            role=UserRole.GUARD,
        )
        self.guard = User.objects.create_user(
            username="roster_guard",
            password="test-only-password",
            role=UserRole.GUARD,
        )
        self.incoming_guard = User.objects.create_user(
            username="roster_incoming_guard",
            password="test-only-password",
            role=UserRole.GUARD,
        )
        self.reason = "Correct the approved roster date range against the signed duty plan."

    def make_roster(self, status=RosterStatus.APPROVED, with_shift=True):
        approved = status == RosterStatus.APPROVED
        roster = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 10, 5),
            end_date=date(2026, 10, 8),
            status=status,
            approved_by=self.admin_user if approved else None,
            approved_at=timezone.now() if approved else None,
        )
        shifts = []
        if with_shift:
            shifts.append(
                Shift.objects.create(
                    station=self.station,
                    guard=self.guard,
                    date=roster.start_date,
                    start_time=time(7, 0),
                    end_time=time(18, 0),
                    shift_type=ShiftType.DAY,
                    roster=roster,
                )
            )
        return roster, shifts

    def assert_roster_unchanged(self, roster, shift):
        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.APPROVED)
        self.assertIsNotNone(roster.approved_by_id)
        self.assertIsNotNone(roster.approved_at)
        self.assertTrue(Shift.objects.filter(pk=shift.pk).exists())

    def test_administrator_can_reopen_safe_approved_roster_and_audit_it(self):
        roster, shifts = self.make_roster()
        old_start, old_end = roster.start_date, roster.end_date

        deleted_count = reopen_duty_roster_as_draft(roster, self.admin_user, self.reason)

        roster.refresh_from_db()
        self.assertEqual(deleted_count, 1)
        self.assertEqual(roster.status, RosterStatus.DRAFT)
        self.assertIsNone(roster.approved_by)
        self.assertIsNone(roster.approved_at)
        self.assertFalse(Shift.objects.filter(pk=shifts[0].pk).exists())
        event = SecurityAuditEvent.objects.get(target_model="DutyRoster", target_id=str(roster.pk))
        self.assertEqual(event.actor, self.admin_user)
        self.assertEqual(event.event_type, SecurityAuditEvent.EventType.RECORD_AMENDMENT)
        self.assertEqual(event.details["action"], "reopen_approved_duty_roster_as_draft")
        self.assertEqual(event.details["previous_status"], RosterStatus.APPROVED)
        self.assertEqual(event.details["previous_start_date"], old_start.isoformat())
        self.assertEqual(event.details["previous_end_date"], old_end.isoformat())
        self.assertEqual(event.details["new_status"], RosterStatus.DRAFT)
        self.assertEqual(event.details["reason"], self.reason)
        self.assertEqual(event.details["deleted_generated_shift_count"], 1)
        self.assertIsNotNone(event.timestamp)

    def test_superuser_can_reopen_safe_approved_roster(self):
        roster, _ = self.make_roster()

        reopen_duty_roster_as_draft(roster.pk, self.superuser, self.reason)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.DRAFT)

    def test_reopened_roster_can_be_corrected_and_saved_as_draft(self):
        roster, _ = self.make_roster()
        reopen_duty_roster_as_draft(roster, self.admin_user, self.reason)

        roster.refresh_from_db()
        roster.start_date = date(2026, 10, 6)
        roster.end_date = date(2026, 10, 9)
        roster.full_clean()
        roster.save(update_fields=["start_date", "end_date", "updated_at"])
        roster.refresh_from_db()

        self.assertEqual(roster.status, RosterStatus.DRAFT)
        self.assertEqual(roster.start_date, date(2026, 10, 6))
        self.assertEqual(roster.end_date, date(2026, 10, 9))

    def test_non_administrator_cannot_reopen(self):
        roster, shifts = self.make_roster()

        with self.assertRaises(PermissionDenied):
            reopen_duty_roster_as_draft(roster, self.guard, self.reason)

        self.assert_roster_unchanged(roster, shifts[0])
        self.assertFalse(SecurityAuditEvent.objects.exists())

    def test_validated_roster_is_rejected_unchanged(self):
        roster, shifts = self.make_roster(status=RosterStatus.VALIDATED)

        with self.assertRaises(ValidationError):
            reopen_duty_roster_as_draft(roster, self.admin_user, self.reason)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.VALIDATED)
        self.assertTrue(Shift.objects.filter(pk=shifts[0].pk).exists())

    def test_draft_roster_is_not_reopened_or_modified(self):
        roster, shifts = self.make_roster(status=RosterStatus.DRAFT)

        with self.assertRaises(ValidationError):
            reopen_duty_roster_as_draft(roster, self.admin_user, self.reason)

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.DRAFT)
        self.assertTrue(Shift.objects.filter(pk=shifts[0].pk).exists())
        self.assertFalse(SecurityAuditEvent.objects.exists())

    def test_attendance_without_clock_in_rejects_and_preserves_shift(self):
        roster, shifts = self.make_roster()
        Attendance.objects.create(shift=shifts[0], guard=self.guard)

        with self.assertRaisesMessage(ValidationError, "attendance records exist"):
            reopen_duty_roster_as_draft(roster, self.admin_user, self.reason)

        self.assert_roster_unchanged(roster, shifts[0])

    def test_clock_in_record_rejects_and_preserves_shift(self):
        roster, shifts = self.make_roster()
        Attendance.objects.create(
            shift=shifts[0],
            guard=self.guard,
            clock_in=timezone.now(),
        )

        with self.assertRaisesMessage(ValidationError, "clock-in records"):
            reopen_duty_roster_as_draft(roster, self.admin_user, self.reason)

        self.assert_roster_unchanged(roster, shifts[0])

    def test_handover_rejects_and_preserves_shift(self):
        roster, shifts = self.make_roster()
        ShiftHandover.objects.create(
            outgoing_shift=shifts[0],
            outgoing_guard=self.guard,
            incoming_guard=self.incoming_guard,
            station=self.station,
            occurrence_summary="Routine handover test record.",
        )

        with self.assertRaisesMessage(ValidationError, "handover records exist"):
            reopen_duty_roster_as_draft(roster, self.admin_user, self.reason)

        self.assert_roster_unchanged(roster, shifts[0])

    def test_audit_failure_rolls_back_shift_deletion_and_roster_transition(self):
        roster, shifts = self.make_roster()

        with patch(
            "apps.shifts.services.SecurityAuditEvent.objects.create",
            side_effect=RuntimeError("audit write failed"),
        ):
            with self.assertRaisesRegex(RuntimeError, "audit write failed"):
                reopen_duty_roster_as_draft(roster, self.admin_user, self.reason)

        self.assert_roster_unchanged(roster, shifts[0])
        self.assertFalse(SecurityAuditEvent.objects.exists())

    def test_missing_roster_is_rejected(self):
        with self.assertRaisesMessage(ValidationError, "Duty roster not found"):
            reopen_duty_roster_as_draft(
                "00000000-0000-0000-0000-000000000001",
                self.admin_user,
                self.reason,
            )

    def test_invalid_roster_identifier_is_rejected(self):
        with self.assertRaisesMessage(ValidationError, "Duty roster not found"):
            reopen_duty_roster_as_draft("not-a-uuid", self.admin_user, self.reason)

    def test_reason_is_required(self):
        roster, shifts = self.make_roster()

        with self.assertRaisesMessage(ValidationError, "reason is required"):
            reopen_duty_roster_as_draft(roster, self.admin_user, "  ")

        self.assert_roster_unchanged(roster, shifts[0])

    def test_approved_roster_normal_immutability_and_delete_protection_remain(self):
        roster, _ = self.make_roster()
        roster.end_date += timedelta(days=1)

        with self.assertRaises(ValidationError):
            roster.full_clean()
        with self.assertRaises(ValidationError):
            DutyRoster.objects.get(pk=roster.pk).delete()


class DutyRosterAdminReopenActionTests(TestCase):
    def setUp(self):
        self.station = Station.objects.create(
            name="Reopen Action Station",
            code="REOPEN-ACTION-01",
        )
        self.admin_user = User.objects.create_user(
            username="roster_action_admin",
            password="test-only-password",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
        )
        self.guard = User.objects.create_user(
            username="roster_action_guard",
            password="test-only-password",
            role=UserRole.GUARD,
            is_staff=True,
        )
        self.reason = "Correct the approved dates using the signed duty plan."
        change_permission = Permission.objects.get(
            content_type__app_label="shifts",
            codename="change_dutyroster",
        )
        self.admin_user.user_permissions.add(change_permission)
        self.guard.user_permissions.add(change_permission)
        self.assertTrue(self.admin_user.has_perm("shifts.change_dutyroster"))
        self.model_admin = DutyRosterAdmin(DutyRoster, admin.site)
        self.factory = RequestFactory()

    def make_roster(self):
        shift_day = date(2026, 10, 5) + timedelta(days=DutyRoster.objects.count())
        roster = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 10, 5),
            end_date=date(2026, 10, 8),
            status=RosterStatus.APPROVED,
            approved_by=self.admin_user,
            approved_at=timezone.now(),
        )
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard,
            date=shift_day,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            roster=roster,
        )
        return roster, [shift]

    def assert_roster_unchanged(self, roster, shift):
        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.APPROVED)
        self.assertIsNotNone(roster.approved_by_id)
        self.assertIsNotNone(roster.approved_at)
        self.assertTrue(Shift.objects.filter(pk=shift.pk).exists())

    def test_admin_action_reopens_roster_with_required_reason(self):
        roster, shifts = self.make_roster()
        request = self.factory.post(
            "/admin/shifts/dutyroster/",
            {"reason": self.reason},
        )
        request.user = self.admin_user

        with patch.object(self.model_admin, "message_user") as message_user:
            self.model_admin.reopen_selected_rosters_to_draft(
                request,
                DutyRoster.objects.filter(pk=roster.pk),
            )

        roster.refresh_from_db()
        self.assertEqual(roster.status, RosterStatus.DRAFT)
        self.assertFalse(Shift.objects.filter(pk=shifts[0].pk).exists())
        message_user.assert_called_once()

    def test_admin_action_requires_reason_and_authorized_role(self):
        roster, shifts = self.make_roster()
        request = self.factory.post("/admin/shifts/dutyroster/", {})
        request.user = self.admin_user
        with patch.object(self.model_admin, "message_user") as message_user:
            self.model_admin.reopen_selected_rosters_to_draft(
                request,
                DutyRoster.objects.filter(pk=roster.pk),
            )
        self.assert_roster_unchanged(roster, shifts[0])
        self.assertIn(
            "reopen_selected_rosters_to_draft",
            self.model_admin.get_actions(self._request_for(self.admin_user)),
        )
        self.assertNotIn(
            "reopen_selected_rosters_to_draft",
            self.model_admin.get_actions(self._request_for(self.guard)),
        )
        message_user.assert_called_once()

    def test_admin_action_is_all_or_nothing_for_multiple_selected_rosters(self):
        safe_roster, safe_shifts = self.make_roster()
        blocked_roster, blocked_shifts = self.make_roster()
        Attendance.objects.create(shift=blocked_shifts[0], guard=self.guard)
        request = self.factory.post(
            "/admin/shifts/dutyroster/",
            {"reason": self.reason},
        )
        request.user = self.admin_user

        with patch.object(self.model_admin, "message_user") as message_user:
            self.model_admin.reopen_selected_rosters_to_draft(
                request,
                DutyRoster.objects.filter(pk__in=[safe_roster.pk, blocked_roster.pk]).order_by("pk"),
            )

        self.assert_roster_unchanged(safe_roster, safe_shifts[0])
        self.assert_roster_unchanged(blocked_roster, blocked_shifts[0])
        message_user.assert_called_once()
        self.assertFalse(SecurityAuditEvent.objects.exists())

    def _request_for(self, user):
        request = self.factory.get("/admin/shifts/dutyroster/")
        request.user = user
        return request
