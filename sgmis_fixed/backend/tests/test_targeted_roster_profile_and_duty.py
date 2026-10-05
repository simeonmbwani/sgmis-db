import io
from datetime import date, time, timedelta
from django.utils import timezone
from django.test import TestCase
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.exceptions import ValidationError
from rest_framework import status
from rest_framework.test import APIClient

from apps.accounts.models import User, UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import (
    Shift,
    ShiftType,
    AssignmentType,
    DutyRoster,
    RosterStatus,
    TemporaryAssignmentAudit,
)
from apps.shifts.services import resolve_next_guard_duty, resolve_guard_duty
from apps.leave.models import LeaveApplication, LeaveType, LeaveStatus


class TargetedRosterProfileAndDutyTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.today = timezone.localdate()
        self.tomorrow = self.today + timedelta(days=1)
        self.day_after = self.today + timedelta(days=2)

        # Stations
        self.station1 = Station.objects.create(
            name="Alpha Station",
            code="ALP01",
            latitude=-17.82,
            longitude=31.05,
            geofence_radius_meters=300.0,
            is_active=True,
        )
        self.station2 = Station.objects.create(
            name="Bravo Station",
            code="BRV01",
            latitude=-17.83,
            longitude=31.06,
            geofence_radius_meters=300.0,
            is_active=True,
        )

        # Users
        self.supervisor = User.objects.create_user(
            username="sup_alpha",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station1,
            first_name="Alpha",
            last_name="Supervisor",
        )

        self.guard_a = User.objects.create_user(
            username="guard_a",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station1,
            first_name="Alice",
            last_name="Guard",
            employee_number="SEC-101",
            phone_number="+263771111111",
            email="alice@example.com",
            address="123 Alpha St",
        )

        self.guard_b = User.objects.create_user(
            username="guard_b",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station1,
            first_name="Bob",
            last_name="Guard",
            employee_number="SEC-102",
            phone_number="+263772222222",
            email="bob@example.com",
            address="456 Beta St",
        )

        self.guard_c = User.objects.create_user(
            username="guard_c",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station2,
            first_name="Charlie",
            last_name="Guard",
            employee_number="SEC-103",
            phone_number="+263773333333",
            email="charlie@example.com",
            address="789 Charlie St",
        )

        # Pairs
        self.pair1 = GuardPair.objects.create(
            station=self.station1,
            guard_a=self.guard_a,
            guard_b=self.guard_b,
            rotation_order=1,
            is_active=True,
        )

        # Rosters
        self.roster1 = DutyRoster.objects.create(
            station=self.station1,
            start_date=self.today,
            end_date=self.today + timedelta(days=6),
            status=RosterStatus.APPROVED,
            approved_by=self.supervisor,
            approved_at=timezone.now(),
        )
        self.roster2 = DutyRoster.objects.create(
            station=self.station2,
            start_date=self.today,
            end_date=self.today + timedelta(days=6),
            status=RosterStatus.APPROVED,
            approved_by=self.supervisor,
            approved_at=timezone.now(),
        )

    def test_one_guard_one_active_station_rule(self):
        """A guard cannot have two active shifts at different stations on the same date."""
        # Shift 1 for Guard A at Station 1
        shift1 = Shift.objects.create(
            station=self.station1,
            guard=self.guard_a,
            roster=self.roster1,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )

        # Attempt to create Shift 2 for Guard A at Station 2 on the same day -> clean() should raise ValidationError
        shift2 = Shift(
            station=self.station2,
            guard=self.guard_a,
            roster=self.roster2,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )
        with self.assertRaises(ValidationError) as ctx:
            shift2.clean()
        self.assertIn("already has an active assignment at another station", str(ctx.exception))

    def test_normal_station_pair_cannot_have_duplicate_day_or_night(self):
        """A normal station pair cannot have two DAY guards or two NIGHT guards on the same date."""
        # Guard A is DAY at Station 1
        Shift.objects.create(
            station=self.station1,
            guard=self.guard_a,
            roster=self.roster1,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )

        # Attempt to create another DAY shift for Guard B at Station 1 on the same date
        shift_b = Shift(
            station=self.station1,
            guard=self.guard_b,
            roster=self.roster1,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )
        with self.assertRaises(ValidationError) as ctx:
            shift_b.clean()
        self.assertIn("already has an active DAY shift", str(ctx.exception))

        # But Guard B CAN be assigned NIGHT
        shift_b.shift_type = ShiftType.NIGHT
        shift_b.start_time = time(18, 0)
        shift_b.end_time = time(6, 0)
        # clean() should pass without error
        shift_b.clean()
        shift_b.save()
        self.assertEqual(Shift.objects.filter(station=self.station1, date=self.today).count(), 2)

    def test_reassign_endpoint_prevents_conflict_with_formatted_error(self):
        """Supervisor reassigning a guard with an active conflict gets a 409 formatted message."""
        # Guard C is already assigned DAY at Station 2
        Shift.objects.create(
            station=self.station2,
            guard=self.guard_c,
            roster=self.roster2,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )

        # Shift at Station 1 currently for Guard A
        shift_a = Shift.objects.create(
            station=self.station1,
            guard=self.guard_a,
            roster=self.roster1,
            date=self.today,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )

        self.client.force_authenticate(user=self.supervisor)
        url = f"/shifts/shifts/{shift_a.id}/reassign/"
        response = self.client.post(url, {
            "guard_id": str(self.guard_c.id),
            "reason": "Test conflict check",
        })

        self.assertEqual(response.status_code, status.HTTP_409_CONFLICT)
        detail = response.data.get("detail", "")
        self.assertIn("GUARD ALREADY ASSIGNED", detail)
        self.assertIn("Bravo Station", detail)
        self.assertIn(str(self.today), detail)

    def test_temporary_relief_preserves_guard_permanent_station(self):
        """Temporary relief assignment preserves guard's permanent station and creates audit log."""
        # Shift at Station 1 for Guard A
        shift_a = Shift.objects.create(
            station=self.station1,
            guard=self.guard_a,
            roster=self.roster1,
            date=self.today,
            shift_type=ShiftType.DAY,
            duty_location=self.station1.name,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )

        self.client.force_authenticate(user=self.supervisor)
        url = f"/shifts/shifts/{shift_a.id}/reassign/"
        response = self.client.post(url, {
            "guard_id": str(self.guard_c.id),
            "reason": "Covering leave",
            "is_permanent": False,
        })

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        # Guard C's permanent station should NOT change
        self.guard_c.refresh_from_db()
        self.assertEqual(self.guard_c.station, self.station2)

        # TemporaryAssignmentAudit should be recorded
        audit = TemporaryAssignmentAudit.objects.filter(guard=self.guard_c).first()
        self.assertIsNotNone(audit)
        self.assertEqual(audit.location, self.station1.name)

    def test_guard_can_update_profile_and_operational_fields_are_protected(self):
        """Guard can update contact information; operational fields cannot be modified."""
        self.client.force_authenticate(user=self.guard_a)
        response = self.client.patch("/accounts/users/me/", {
            "phone_number": "+263779999999",
            "email": "alice_updated@example.com",
            "address": "999 New Address Rd",
            "first_name": "Alicia",
            "last_name": "Gardner",
            # Operational fields that should be silently ignored / protected
            "role": "ADMINISTRATOR",
            "employee_number": "HACK-999",
            "is_staff": True,
            "is_superuser": True,
        })

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.guard_a.refresh_from_db()

        # Permitted fields are updated
        self.assertEqual(self.guard_a.phone_number, "+263779999999")
        self.assertEqual(self.guard_a.email, "alice_updated@example.com")
        self.assertEqual(self.guard_a.address, "999 New Address Rd")
        self.assertEqual(self.guard_a.first_name, "Alicia")
        self.assertEqual(self.guard_a.last_name, "Gardner")

        # Protected fields remain strictly unchanged
        self.assertEqual(self.guard_a.role, UserRole.GUARD)
        self.assertEqual(self.guard_a.employee_number, "SEC-101")
        self.assertFalse(self.guard_a.is_staff)
        self.assertFalse(self.guard_a.is_superuser)

    def test_guard_profile_photo_upload(self):
        """Guard can upload a profile photo via POST /accounts/users/me/photo/."""
        gif_bytes = (
            b"GIF89a\x01\x00\x01\x00\x80\x00\x00\xff\xff\xff\x00\x00\x00!\xf9\x04"
            b"\x01\x00\x00\x00\x00,\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00;"
        )
        photo_file = SimpleUploadedFile("avatar.gif", gif_bytes, content_type="image/gif")

        self.client.force_authenticate(user=self.guard_a)
        response = self.client.post(
            "/accounts/users/me/photo/",
            {"photo": photo_file},
            format="multipart",
        )

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.guard_a.refresh_from_db()
        self.assertTrue(bool(self.guard_a.profile_photo))
        self.assertIn("profile_photos/", str(self.guard_a.profile_photo))

    def test_next_duty_resolution_and_endpoint(self):
        """Next duty endpoint returns next upcoming shift; returns NO UPCOMING DUTY if none."""
        # 1. No upcoming shift yet
        self.client.force_authenticate(user=self.guard_a)
        res_empty = self.client.get("/shifts/shifts/next_duty/")
        self.assertEqual(res_empty.status_code, status.HTTP_200_OK)
        self.assertEqual(res_empty.data.get("detail"), "NO UPCOMING DUTY")
        self.assertIsNone(res_empty.data.get("next_duty"))

        # 2. Add an upcoming shift tomorrow
        upcoming_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard_a,
            roster=self.roster1,
            date=self.tomorrow,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )

        res_next = self.client.get("/shifts/shifts/next_duty/")
        self.assertEqual(res_next.status_code, status.HTTP_200_OK)
        next_duty_obj = res_next.data.get("next_duty")
        self.assertIsNotNone(next_duty_obj)
        self.assertEqual(str(next_duty_obj.get("id")), str(upcoming_shift.id))
        self.assertEqual(next_duty_obj.get("date"), str(self.tomorrow))

        # 3. If guard is on approved leave tomorrow, resolve_next_guard_duty skips tomorrow
        # and finds shift on day after
        LeaveApplication.objects.create(
            guard=self.guard_a,
            leave_type=LeaveType.ANNUAL,
            start_date=self.tomorrow,
            end_date=self.tomorrow,
            status=LeaveStatus.APPROVED,
        )

        day_after_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard_a,
            roster=self.roster1,
            date=self.day_after,
            shift_type=ShiftType.DAY,
            start_time=time(6, 0),
            end_time=time(18, 0),
        )

        resolved_next = resolve_next_guard_duty(self.guard_a, reference_date=self.today)
        self.assertIsNotNone(resolved_next)
        self.assertEqual(resolved_next.id, day_after_shift.id)
        self.assertEqual(resolved_next.date, self.day_after)
