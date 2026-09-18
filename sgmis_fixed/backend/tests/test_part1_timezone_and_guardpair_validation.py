import zoneinfo
from datetime import datetime
from django.test import TestCase
from django.conf import settings
from django.utils import timezone
from django.core.exceptions import ValidationError as DjangoValidationError
from django.contrib.auth import get_user_model
from rest_framework.test import APIClient
from rest_framework import status

from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.stations.serializers import GuardPairSerializer

UserModel = get_user_model()

class TimezoneConfigurationTests(TestCase):
    """
    Verifies that the Django project timezone is set to Africa/Harare
    and timezone-aware date/time handling operates correctly.
    """
    def test_timezone_setting_is_africa_harare(self):
        self.assertEqual(settings.TIME_ZONE, "Africa/Harare")
        self.assertTrue(settings.USE_TZ)

    def test_timezone_resolution_africa_harare(self):
        tz = timezone.get_current_timezone()
        self.assertEqual(getattr(tz, "key", str(tz)), "Africa/Harare")

        # Africa/Harare has UTC+2 offset (7200 seconds) with no daylight saving time
        now = timezone.now()
        local_now = timezone.localtime(now)
        self.assertEqual(local_now.utcoffset().total_seconds(), 7200)


class GuardPairValidationTests(TestCase):
    """
    Authoritative backend validation tests for GuardPair model and GuardPairSerializer.
    """
    def setUp(self):
        self.client = APIClient()
        self.password = "TestPass123!"

        # Create primary station
        self.station = Station.objects.create(
            name="Main Campus Security Post",
            code="STN-HARARE-01",
            latitude=-17.8252,
            longitude=31.0335,
        )

        # Create alternate station for station mismatch tests
        self.station2 = Station.objects.create(
            name="North Satellite Post",
            code="STN-HARARE-02",
            latitude=-17.8000,
            longitude=31.0500,
        )

        # Create supervisor for API requests
        self.supervisor = UserModel.objects.create_user(
            username="test_supervisor",
            email="supervisor@sgmis.local",
            password=self.password,
            employee_number="SUP-9001",
            role=UserRole.SUPERVISOR,
            station=self.station,
        )
        self.client.force_authenticate(user=self.supervisor)

        # Create 6 valid active guards assigned to primary station
        self.guards = []
        for i in range(1, 7):
            g = UserModel.objects.create_user(
                username=f"test_guard_{i}",
                email=f"guard_{i}@sgmis.local",
                password=self.password,
                employee_number=f"SEC-P1-00{i}",
                role=UserRole.GUARD,
                is_active=True,
                station=self.station,
                first_name=f"GuardFirst{i}",
                last_name=f"GuardLast{i}",
            )
            self.guards.append(g)

    def test_self_pairing_rejected(self):
        """Rule 1 & 2: A guard cannot be paired with himself/herself."""
        pair = GuardPair(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=self.guards[0],
            rotation_order=1,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            pair.clean()
        self.assertIn("guard_b", ctx.exception.message_dict)
        self.assertIn("himself/herself", ctx.exception.message_dict["guard_b"][0])

        # Test serializer validation rejects self-pairing
        serializer = GuardPairSerializer(data={
            "station": str(self.station.id),
            "guard_a": str(self.guards[0].id),
            "guard_b": str(self.guards[0].id),
            "rotation_order": 1,
            "is_active": True,
        })
        self.assertFalse(serializer.is_valid())
        self.assertIn("guard_b", serializer.errors)

    def test_inactive_guard_rejected(self):
        """Rule 4: Inactive guard cannot be assigned to a pair."""
        inactive_guard = UserModel.objects.create_user(
            username="inactive_guard",
            password=self.password,
            employee_number="SEC-INACTIVE",
            role=UserRole.GUARD,
            is_active=False,
            station=self.station,
        )

        # Inactive as Guard A
        pair_a = GuardPair(
            station=self.station,
            guard_a=inactive_guard,
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            pair_a.clean()
        self.assertIn("guard_a", ctx.exception.message_dict)
        self.assertIn("active", ctx.exception.message_dict["guard_a"][0])

        # Inactive as Guard B
        pair_b = GuardPair(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=inactive_guard,
            rotation_order=1,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            pair_b.clean()
        self.assertIn("guard_b", ctx.exception.message_dict)
        self.assertIn("active", ctx.exception.message_dict["guard_b"][0])

        # Test via Serializer
        serializer = GuardPairSerializer(data={
            "station": str(self.station.id),
            "guard_a": str(inactive_guard.id),
            "guard_b": str(self.guards[1].id),
            "rotation_order": 1,
        })
        self.assertFalse(serializer.is_valid())
        self.assertIn("guard_a", serializer.errors)

    def test_non_guard_user_rejected(self):
        """Rule 3: Only users with GUARD role can be assigned to a GuardPair."""
        supervisor_user = UserModel.objects.create_user(
            username="other_supervisor",
            password=self.password,
            employee_number="SUP-9002",
            role=UserRole.SUPERVISOR,
            station=self.station,
        )

        pair = GuardPair(
            station=self.station,
            guard_a=supervisor_user,
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            pair.clean()
        self.assertIn("guard_a", ctx.exception.message_dict)
        self.assertIn("GUARD role", ctx.exception.message_dict["guard_a"][0])

        # Test via Serializer
        serializer = GuardPairSerializer(data={
            "station": str(self.station.id),
            "guard_a": str(supervisor_user.id),
            "guard_b": str(self.guards[1].id),
            "rotation_order": 1,
        })
        self.assertFalse(serializer.is_valid())
        self.assertIn("guard_a", serializer.errors)

    def test_guard_assigned_to_different_station_rejected(self):
        """Rule 5: Guards must belong to the pair's station."""
        foreign_guard = UserModel.objects.create_user(
            username="foreign_guard",
            password=self.password,
            employee_number="SEC-FOREIGN",
            role=UserRole.GUARD,
            station=self.station2,
        )

        pair = GuardPair(
            station=self.station,
            guard_a=foreign_guard,
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            pair.clean()
        self.assertIn("guard_a", ctx.exception.message_dict)
        self.assertIn("station", ctx.exception.message_dict["guard_a"][0])

        # Test via Serializer
        serializer = GuardPairSerializer(data={
            "station": str(self.station.id),
            "guard_a": str(foreign_guard.id),
            "guard_b": str(self.guards[1].id),
            "rotation_order": 1,
        })
        self.assertFalse(serializer.is_valid())
        self.assertIn("guard_a", serializer.errors)

    def test_guard_in_two_active_pairs_rejected(self):
        """Rule 6: A guard cannot belong to more than one active pair at the same station."""
        # Create active Pair 1 with guards 0 and 1
        GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )

        # Attempt to create active Pair 2 using guard 0 (already in Pair 1)
        pair2 = GuardPair(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=self.guards[2],
            rotation_order=2,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            pair2.clean()
        self.assertIn("guard_a", ctx.exception.message_dict)
        self.assertIn("already assigned", ctx.exception.message_dict["guard_a"][0])

        # Attempt to create active Pair 2 using guard 1 as guard_b
        pair2_b = GuardPair(
            station=self.station,
            guard_a=self.guards[2],
            guard_b=self.guards[1],
            rotation_order=2,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            pair2_b.clean()
        self.assertIn("guard_b", ctx.exception.message_dict)
        self.assertIn("already assigned", ctx.exception.message_dict["guard_b"][0])

        # Test via Serializer
        serializer = GuardPairSerializer(data={
            "station": str(self.station.id),
            "guard_a": str(self.guards[0].id),
            "guard_b": str(self.guards[2].id),
            "rotation_order": 2,
            "is_active": True,
        })
        self.assertFalse(serializer.is_valid())
        self.assertIn("guard_a", serializer.errors)

    def test_reverse_pair_duplicate_rejected(self):
        """Rule 6: Duplicate pair membership in reverse order (e.g. A+B and B+A) must be rejected."""
        # Create Pair 1: guards[0] + guards[1]
        GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )

        # Attempt reverse pair: guards[1] + guards[0]
        reverse_pair = GuardPair(
            station=self.station,
            guard_a=self.guards[1],
            guard_b=self.guards[0],
            rotation_order=2,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            reverse_pair.clean()
        has_duplicate_error = (
            "guard_b" in ctx.exception.message_dict or
            "guard_a" in ctx.exception.message_dict or
            "__all__" in ctx.exception.message_dict
        )
        self.assertTrue(has_duplicate_error)

        # Test via Serializer
        serializer = GuardPairSerializer(data={
            "station": str(self.station.id),
            "guard_a": str(self.guards[1].id),
            "guard_b": str(self.guards[0].id),
            "rotation_order": 2,
            "is_active": True,
        })
        self.assertFalse(serializer.is_valid())

    def test_rotation_order_not_in_1_2_3_rejected(self):
        """Rule 7: rotation_order must be 1, 2, or 3 for 3-pair architecture."""
        for invalid_order in [0, 4, 5, 99]:
            pair = GuardPair(
                station=self.station,
                guard_a=self.guards[0],
                guard_b=self.guards[1],
                rotation_order=invalid_order,
                is_active=True,
            )
            with self.assertRaises(DjangoValidationError) as ctx:
                pair.clean()
            self.assertIn("rotation_order", ctx.exception.message_dict)
            self.assertIn("1, 2, or 3", ctx.exception.message_dict["rotation_order"][0])

            # Test via Serializer
            serializer = GuardPairSerializer(data={
                "station": str(self.station.id),
                "guard_a": str(self.guards[0].id),
                "guard_b": str(self.guards[1].id),
                "rotation_order": invalid_order,
                "is_active": True,
            })
            self.assertFalse(serializer.is_valid())
            self.assertIn("rotation_order", serializer.errors)

    def test_duplicate_active_rotation_order_rejected(self):
        """Rule 7: rotation_order cannot collide with another active pair at that station."""
        # Active Pair with order 1
        GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )

        # Attempt another pair with order 1 at same station
        duplicate_order_pair = GuardPair(
            station=self.station,
            guard_a=self.guards[2],
            guard_b=self.guards[3],
            rotation_order=1,
            is_active=True,
        )
        with self.assertRaises(DjangoValidationError) as ctx:
            duplicate_order_pair.clean()
        self.assertIn("rotation_order", ctx.exception.message_dict)
        self.assertIn("already exists", ctx.exception.message_dict["rotation_order"][0])

    def test_valid_three_pairs_accepted(self):
        """Normal scenario: 3 distinct active pairs with rotation orders 1, 2, 3 are valid."""
        p1 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[0],
            guard_b=self.guards[1],
            rotation_order=1,
            is_active=True,
        )
        p1.clean()

        p2 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[2],
            guard_b=self.guards[3],
            rotation_order=2,
            is_active=True,
        )
        p2.clean()

        p3 = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guards[4],
            guard_b=self.guards[5],
            rotation_order=3,
            is_active=True,
        )
        p3.clean()

        self.assertEqual(GuardPair.objects.filter(station=self.station, is_active=True).count(), 3)

    def test_api_viewset_returns_400_bad_request_on_invalid_pair(self):
        """API endpoints return clean 400 Bad Request with JSON error dictionary, not 500."""
        # Post self-pair via API
        response = self.client.post("/api/stations/pairs/", {
            "station": str(self.station.id),
            "guard_a": str(self.guards[0].id),
            "guard_b": str(self.guards[0].id),
            "rotation_order": 1,
            "is_active": True,
        }, format="json")

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("guard_b", response.data)
        self.assertIn("himself/herself", str(response.data["guard_b"]))

        # Post invalid rotation order via API
        response = self.client.post("/api/stations/pairs/", {
            "station": str(self.station.id),
            "guard_a": str(self.guards[0].id),
            "guard_b": str(self.guards[1].id),
            "rotation_order": 5,
            "is_active": True,
        }, format="json")

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("rotation_order", response.data)

