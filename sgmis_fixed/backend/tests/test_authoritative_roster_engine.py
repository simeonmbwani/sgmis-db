from datetime import date, time, timedelta
from django.utils import timezone
from rest_framework import status
from rest_framework.test import APITestCase
from apps.accounts.models import User, UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, AssignmentType
from apps.shifts.services import generate_roster_for_station

class AuthoritativeRosterEngineTests(APITestCase):
    def setUp(self):
        self.station = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-MC-001",
            latitude=1.2921,
            longitude=36.8219,
            geofence_radius_meters=100.0,
        )
        self.admin_user = User.objects.create_user(
            username="admin_roster",
            password="AdminPassword123!",
            role=UserRole.ADMINISTRATOR,
        )
        self.supervisor = User.objects.create_user(
            username="sup_roster",
            password="SupPassword123!",
            role=UserRole.SUPERVISOR,
            station=self.station,
        )

        # 6 Guards in 3 Pairs
        self.guards = []
        for i in range(1, 7):
            g = User.objects.create_user(
                username=f"guard_{i}",
                password="GuardPassword123!",
                role=UserRole.GUARD,
                station=self.station,
                first_name=f"Guard",
                last_name=f"Number{i}",
                employee_number=f"SEC-{100 + i}",
            )
            self.guards.append(g)

        self.pair1 = GuardPair.objects.create(station=self.station, guard_a=self.guards[0], guard_b=self.guards[1], rotation_order=1, is_active=True)
        self.pair2 = GuardPair.objects.create(station=self.station, guard_a=self.guards[2], guard_b=self.guards[3], rotation_order=2, is_active=True)
        self.pair3 = GuardPair.objects.create(station=self.station, guard_a=self.guards[4], guard_b=self.guards[5], rotation_order=3, is_active=True)

    def test_roster_generation_authoritative_model(self):
        start_date = date(2026, 10, 1)
        shifts = generate_roster_for_station(self.station, start_date=start_date, cycle_days=12)

        # 12 days * 6 guards = 72 shifts total
        self.assertEqual(len(shifts), 72)

        # Every day must have exactly 1 Day shift, 1 Night shift, and 4 TIME_OFF shifts
        for day_offset in range(12):
            cur_date = start_date + timedelta(days=day_offset)
            day_shifts = Shift.objects.filter(station=self.station, date=cur_date)
            self.assertEqual(day_shifts.count(), 6)

            active_day = day_shifts.filter(shift_type=ShiftType.DAY, assignment_type=AssignmentType.NORMAL)
            self.assertEqual(active_day.count(), 1)

            active_night = day_shifts.filter(shift_type=ShiftType.NIGHT, assignment_type=AssignmentType.NORMAL)
            self.assertEqual(active_night.count(), 1)

            time_off = day_shifts.filter(shift_type=ShiftType.OFF, assignment_type=AssignmentType.TIME_OFF)
            self.assertEqual(time_off.count(), 4)

        # Check Day/Night Swap:
        # Days 0-3 (Cycle 0 for Pair 1): Guard 1 is DAY, Guard 2 is NIGHT
        p1_d0_day = Shift.objects.get(station=self.station, date=start_date, shift_type=ShiftType.DAY)
        self.assertEqual(p1_d0_day.guard, self.guards[0])
        p1_d0_night = Shift.objects.get(station=self.station, date=start_date, shift_type=ShiftType.NIGHT)
        self.assertEqual(p1_d0_night.guard, self.guards[1])

        # Generate next 12 days to verify Cycle 1 swap for Pair 1 (Days 12-15)
        shifts_c2 = generate_roster_for_station(self.station, start_date=start_date + timedelta(days=12), cycle_days=12)
        p1_d12_day = Shift.objects.get(station=self.station, date=start_date + timedelta(days=12), shift_type=ShiftType.DAY)
        # In Cycle 1, guards swap! Guard 2 is DAY, Guard 1 is NIGHT
        self.assertEqual(p1_d12_day.guard, self.guards[1])
        p1_d12_night = Shift.objects.get(station=self.station, date=start_date + timedelta(days=12), shift_type=ShiftType.NIGHT)
        self.assertEqual(p1_d12_night.guard, self.guards[0])

    def test_operational_roster_endpoint_is_unpaginated_and_chronological(self):
        start_date = date(2026, 10, 1)
        generate_roster_for_station(self.station, start_date=start_date, cycle_days=12)

        # Admin requests operational roster
        self.client.force_authenticate(user=self.admin_user)
        resp = self.client.get("/shifts/shifts/operational/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        # Must return full 72 shifts in a flat JSON list (NOT paginated to 25)
        self.assertIsInstance(resp.data, list)
        self.assertEqual(len(resp.data), 72)

        # Check chronological ordering: date ASC, start_time ASC
        for i in range(len(resp.data) - 1):
            curr_item = resp.data[i]
            next_item = resp.data[i + 1]
            self.assertLessEqual(curr_item["date"], next_item["date"])
            if curr_item["date"] == next_item["date"]:
                self.assertLessEqual(curr_item["start_time"], next_item["start_time"])

    def test_operational_roster_guard_scoping(self):
        start_date = date(2026, 10, 1)
        generate_roster_for_station(self.station, start_date=start_date, cycle_days=12)

        # Guard 1 requests operational roster
        self.client.force_authenticate(user=self.guards[0])
        resp = self.client.get("/shifts/shifts/operational/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIsInstance(resp.data, list)
        # Guard 1 only sees their own shifts and their pair's shifts (Pair 1 = Guard 1 & Guard 2)
        # Guard 1 and Guard 2 have 12 shifts each over 12 days = 24 shifts total
        for s in resp.data:
            self.assertIn(str(s["guard"]), [str(self.guards[0].id), str(self.guards[1].id)])
