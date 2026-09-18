from datetime import date, time
from django.test import TestCase
from django.utils import timezone
from django.contrib.auth import get_user_model
from django.core.exceptions import ValidationError

from apps.accounts.models import UserRole
from apps.stations.models import Station
from apps.shifts.models import DutyRoster, RosterStatus, Shift, ShiftType, AssignmentType

UserModel = get_user_model()

class DutyRosterFoundationTests(TestCase):
    """
    Focused unit tests covering Smart Security Part 2:
    Authoritative DutyRoster data model, lifecycle validation, and Shift.roster relationship.
    """

    def setUp(self):
        self.password = "TestPass123!"

        self.station = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-HARARE-01",
            latitude=-17.8252,
            longitude=31.0335,
        )

        self.station2 = Station.objects.create(
            name="North Satellite Post",
            code="STN-HARARE-02",
            latitude=-17.8000,
            longitude=31.0500,
        )

        self.admin = UserModel.objects.create_user(
            username="roster_approver",
            email="approver@sgmis.local",
            password=self.password,
            employee_number="ADM-ROSTER-01",
            role=UserRole.ADMINISTRATOR,
            station=self.station,
            is_staff=True,
        )

        self.guard = UserModel.objects.create_user(
            username="duty_guard_1",
            email="guard1@sgmis.local",
            password=self.password,
            employee_number="SEC-DUTY-01",
            role=UserRole.GUARD,
            station=self.station,
        )

    def test_draft_roster_can_be_created(self):
        """1. DRAFT roster can be created and saved without approval metadata."""
        roster = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.DRAFT,
        )
        roster.clean()
        roster.save()

        self.assertIsNotNone(roster.id)
        self.assertEqual(roster.status, RosterStatus.DRAFT)
        self.assertIsNone(roster.approved_by)
        self.assertIsNone(roster.approved_at)
        self.assertIn("Draft", str(roster))


    def test_end_date_before_start_date_is_rejected(self):
        """2. end_date earlier than start_date is rejected in clean()."""
        roster = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 31),
            end_date=date(2026, 10, 1),
            status=RosterStatus.DRAFT,
        )
        with self.assertRaises(ValidationError) as ctx:
            roster.clean()
        self.assertIn("end_date", ctx.exception.message_dict)
        self.assertIn("earlier than start date", ctx.exception.message_dict["end_date"][0])

    def test_approved_roster_without_approved_by_is_rejected(self):
        """3. APPROVED roster without approved_by is rejected."""
        roster = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.APPROVED,
            approved_by=None,
            approved_at=timezone.now(),
        )
        with self.assertRaises(ValidationError) as ctx:
            roster.clean()
        self.assertIn("approved_by", ctx.exception.message_dict)

    def test_approved_roster_without_approved_at_is_rejected(self):
        """4. APPROVED roster without approved_at is rejected."""
        roster = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.APPROVED,
            approved_by=self.admin,
            approved_at=None,
        )
        with self.assertRaises(ValidationError) as ctx:
            roster.clean()
        self.assertIn("approved_at", ctx.exception.message_dict)

    def test_approved_roster_with_both_approval_fields_is_valid(self):
        """5. APPROVED roster with both approval fields is valid."""
        roster = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.APPROVED,
            approved_by=self.admin,
            approved_at=timezone.now(),
        )
        roster.clean()
        roster.save()

        self.assertEqual(roster.status, RosterStatus.APPROVED)
        self.assertEqual(roster.approved_by, self.admin)
        self.assertIsNotNone(roster.approved_at)

    def test_active_roster_requires_approval_metadata(self):
        """6. ACTIVE roster requires both approved_by and approved_at."""
        # Missing approved_by
        roster1 = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.ACTIVE,
            approved_by=None,
            approved_at=timezone.now(),
        )
        with self.assertRaises(ValidationError) as ctx:
            roster1.clean()
        self.assertIn("approved_by", ctx.exception.message_dict)

        # Missing approved_at
        roster2 = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.ACTIVE,
            approved_by=self.admin,
            approved_at=None,
        )
        with self.assertRaises(ValidationError) as ctx:
            roster2.clean()
        self.assertIn("approved_at", ctx.exception.message_dict)

        # With both: valid
        roster3 = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.ACTIVE,
            approved_by=self.admin,
            approved_at=timezone.now(),
        )
        roster3.clean()
        roster3.save()
        self.assertEqual(roster3.status, RosterStatus.ACTIVE)

    def test_archived_roster_requires_approval_metadata(self):
        """7. ARCHIVED roster requires both approved_by and approved_at."""
        roster = DutyRoster(
            station=self.station,
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 30),
            status=RosterStatus.ARCHIVED,
            approved_by=None,
            approved_at=None,
        )
        with self.assertRaises(ValidationError) as ctx:
            roster.clean()
        self.assertIn("approved_by", ctx.exception.message_dict)
        self.assertIn("approved_at", ctx.exception.message_dict)

    def test_draft_and_validated_roster_must_not_contain_approval_metadata(self):
        """8. DRAFT and VALIDATED rosters must not contain approval metadata."""
        # DRAFT with approved_by
        roster_draft = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.DRAFT,
            approved_by=self.admin,
            approved_at=None,
        )
        with self.assertRaises(ValidationError) as ctx:
            roster_draft.clean()
        self.assertIn("approved_by", ctx.exception.message_dict)

        # VALIDATED with approved_at
        roster_val = DutyRoster(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.VALIDATED,
            approved_by=None,
            approved_at=timezone.now(),
        )
        with self.assertRaises(ValidationError) as ctx:
            roster_val.clean()
        self.assertIn("approved_at", ctx.exception.message_dict)

    def test_existing_shift_records_survive_with_roster_null(self):
        """9. Existing Shift records survive with roster=NULL."""
        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard,
            date=date(2026, 10, 5),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        shift.refresh_from_db()
        self.assertIsNone(shift.roster)
        self.assertTrue(Shift.objects.filter(roster__isnull=True).filter(id=shift.id).exists())

    def test_shift_can_be_linked_to_duty_roster(self):
        """10. A Shift can be linked to a DutyRoster and reverse relationship works."""
        roster = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.DRAFT,
        )

        shift = Shift.objects.create(
            station=self.station,
            guard=self.guard,
            date=date(2026, 10, 6),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=roster,
        )

        shift.refresh_from_db()
        self.assertEqual(shift.roster, roster)
        self.assertEqual(roster.shifts.count(), 1)
        self.assertEqual(roster.shifts.first(), shift)

    def test_immutability_protection_on_approved_roster(self):
        """Protection prevents modifying station/dates, reverting status, or deleting approved roster."""
        roster = DutyRoster.objects.create(
            station=self.station,
            start_date=date(2026, 10, 1),
            end_date=date(2026, 10, 31),
            status=RosterStatus.APPROVED,
            approved_by=self.admin,
            approved_at=timezone.now(),
        )

        # Modifying station on approved roster is rejected
        roster.station = self.station2
        with self.assertRaises(ValidationError) as ctx:
            roster.clean()
        self.assertIn("station", ctx.exception.message_dict)

        # Reverting status to DRAFT is rejected
        roster.station = self.station
        roster.status = RosterStatus.DRAFT
        roster.approved_by = None
        roster.approved_at = None
        with self.assertRaises(ValidationError) as ctx:
            roster.clean()
        self.assertIn("status", ctx.exception.message_dict)

        # Deleting approved roster is rejected
        roster.refresh_from_db()
        with self.assertRaises(ValidationError):
            roster.delete()
