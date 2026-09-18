from rest_framework import status
from rest_framework.test import APITestCase
from apps.accounts.models import User, UserRole
from apps.stations.models import Station
from apps.notifications.models import Notification

class NotificationsFlowTests(APITestCase):
    def setUp(self):
        self.station1 = Station.objects.create(name="Campus A", code="CA-01", latitude=1.0, longitude=36.0)
        self.station2 = Station.objects.create(name="Campus B", code="CB-02", latitude=1.1, longitude=36.1)

        self.admin = User.objects.create_user(username="admin_notif", password="Pass123!", role=UserRole.ADMINISTRATOR)
        self.supervisor = User.objects.create_user(username="sup_notif", password="Pass123!", role=UserRole.SUPERVISOR, station=self.station1)
        self.guard1 = User.objects.create_user(username="g1_notif", password="Pass123!", role=UserRole.GUARD, station=self.station1)
        self.guard2 = User.objects.create_user(username="g2_notif", password="Pass123!", role=UserRole.GUARD, station=self.station2)

    def test_guard_cannot_broadcast(self):
        self.client.force_authenticate(user=self.guard1)
        resp = self.client.post("/notifications/alerts/broadcast/", {
            "title": "Unauthorized",
            "message": "Should fail",
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)

    def test_supervisor_broadcast_is_station_scoped(self):
        self.client.force_authenticate(user=self.supervisor)
        resp = self.client.post("/notifications/alerts/broadcast/", {
            "title": "Station Briefing",
            "message": "Meeting at 0800",
            "priority": "HIGH",
        })
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)

        # Delivered to Guard 1 (Campus A), but NOT Guard 2 (Campus B)
        self.assertTrue(Notification.objects.filter(user=self.guard1, title="Station Briefing").exists())
        self.assertFalse(Notification.objects.filter(user=self.guard2, title="Station Briefing").exists())

    def test_admin_targeted_broadcast(self):
        self.client.force_authenticate(user=self.admin)
        resp = self.client.post("/notifications/alerts/broadcast/", {
            "title": "Targeted Alert",
            "message": "Special duty",
            "user_ids": [str(self.guard2.id)],
        }, format="json")
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(resp.data["recipients_count"], 1)

        self.assertTrue(Notification.objects.filter(user=self.guard2, title="Targeted Alert").exists())
        self.assertFalse(Notification.objects.filter(user=self.guard1, title="Targeted Alert").exists())

    def test_unread_count_and_mark_read(self):
        # Create 2 unread notifications for guard1
        n1 = Notification.objects.create(user=self.guard1, title="N1", message="M1")
        n2 = Notification.objects.create(user=self.guard1, title="N2", message="M2")

        self.client.force_authenticate(user=self.guard1)
        count_resp = self.client.get("/notifications/alerts/unread_count/")
        self.assertEqual(count_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(count_resp.data["unread_count"], 2)

        # Mark single as read
        read_resp = self.client.post(f"/notifications/alerts/{n1.id}/read/")
        self.assertEqual(read_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(read_resp.data["status"], "success")

        count_resp2 = self.client.get("/notifications/alerts/unread_count/")
        self.assertEqual(count_resp2.data["unread_count"], 1)

        # Mark all as read
        read_all_resp = self.client.post("/notifications/alerts/read_all/")
        self.assertEqual(read_all_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(read_all_resp.data["status"], "success")

        count_resp3 = self.client.get("/notifications/alerts/unread_count/")
        self.assertEqual(count_resp3.data["unread_count"], 0)
