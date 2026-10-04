import uuid
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from django.utils import timezone
from rest_framework import status
from rest_framework.test import APITestCase
from apps.accounts.models import User, UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, AssignmentType, Attendance
from apps.notifications.models import Notification


class Phase6BAttendanceSecurityTests(APITestCase):
    """
    Focused test suite verifying Smart Security Phase 6B targeted attendance security rules:
    A. Missing latitude rejected on clock-in.
    B. Missing longitude rejected on clock-in.
    C. Missing latitude rejected on clock-out.
    D. Missing longitude rejected on clock-out.
    E. Out-of-geofence clock-in rejected.
    F. Out-of-geofence clock-out rejected.
    G. Guard assigned to Station A cannot clock into Station B shift.
    H. Clock-in 16 minutes after start: succeeds if otherwise valid, is_late=True, is_serious_late=False.
    I. Clock-in 60 minutes after start: serious lateness is recorded (is_serious_late=True).
    J. First serious-lateness occurrence: recorded, no repeated-lateness escalation.
    K. Second serious-lateness occurrence: recorded, no third-occurrence escalation.
    L. Third serious-lateness occurrence: supervisor notification/report created exactly once.
    M. Repeating the same request/processing must not create duplicate escalation notifications.
    N. Overnight NIGHT shift: scheduled 18:00 to 07:00 next day; remains authoritative after midnight.
    """

    def setUp(self):
        self.station_a = Station.objects.create(
            name="Alpha Security Post",
            code="STN-ALPHA-01",
            latitude=1.2921,
            longitude=36.8219,
            geofence_radius_meters=100.0,
        )
        self.station_b = Station.objects.create(
            name="Bravo Security Post",
            code="STN-BRAVO-02",
            latitude=1.3500,
            longitude=36.8800,
            geofence_radius_meters=100.0,
        )
        self.supervisor_a = User.objects.create_user(
            username="sup_alpha",
            password="SupervisorPassword123!",
            role=UserRole.SUPERVISOR,
            station=self.station_a,
            first_name="Alpha",
            last_name="Supervisor",
        )
        self.guard = User.objects.create_user(
            username="guard_alpha_one",
            password="GuardPassword123!",
            role=UserRole.GUARD,
            station=self.station_a,
            employee_number="SEC-ALPHA-01",
            first_name="John",
            last_name="Guard",
        )
        self.valid_lat = 1.2921
        self.valid_lon = 36.8219
        self.out_lat = 1.3500   # ~8.5 km away from Station A
        self.out_lon = 36.8800

    def test_A_missing_latitude_rejected_on_clock_in(self):
        start_dt = timezone.localtime() - timedelta(minutes=5)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "longitude": self.valid_lon,
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("GPS coordinates", resp.data["detail"])
        self.assertIn("required", resp.data["detail"])

    def test_B_missing_longitude_rejected_on_clock_in(self):
        start_dt = timezone.localtime() - timedelta(minutes=5)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": self.valid_lat,
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("GPS coordinates", resp.data["detail"])
        self.assertIn("required", resp.data["detail"])

    def test_C_missing_latitude_rejected_on_clock_out(self):
        yesterday = timezone.localdate() - timedelta(days=1)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=yesterday,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        Attendance.objects.create(
            shift=shift,
            guard=self.guard,
            clock_in=timezone.make_aware(datetime.combine(yesterday, time(7, 0))),
            clock_in_gps=f"{self.valid_lat},{self.valid_lon}",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "longitude": self.valid_lon,
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("GPS coordinates", resp.data["detail"])
        self.assertIn("required", resp.data["detail"])

    def test_D_missing_longitude_rejected_on_clock_out(self):
        yesterday = timezone.localdate() - timedelta(days=1)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=yesterday,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        Attendance.objects.create(
            shift=shift,
            guard=self.guard,
            clock_in=timezone.make_aware(datetime.combine(yesterday, time(7, 0))),
            clock_in_gps=f"{self.valid_lat},{self.valid_lon}",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "latitude": self.valid_lat,
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("GPS coordinates", resp.data["detail"])
        self.assertIn("required", resp.data["detail"])

    def test_E_out_of_geofence_clock_in_rejected(self):
        start_dt = timezone.localtime() - timedelta(minutes=5)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": self.out_lat,
            "longitude": self.out_lon,
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("Geofence violation", resp.data["detail"])

    def test_F_out_of_geofence_clock_out_rejected(self):
        yesterday = timezone.localdate() - timedelta(days=1)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=yesterday,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        Attendance.objects.create(
            shift=shift,
            guard=self.guard,
            clock_in=timezone.make_aware(datetime.combine(yesterday, time(7, 0))),
            clock_in_gps=f"{self.valid_lat},{self.valid_lon}",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_out/", {
            "shift_id": str(shift.id),
            "enforce_shift_end": True,
            "latitude": self.out_lat,
            "longitude": self.out_lon,
        })
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("Geofence violation", resp.data["detail"])

    def test_G_guard_assigned_to_station_a_cannot_clock_into_station_b_shift(self):
        start_dt = timezone.localtime() - timedelta(minutes=5)
        shift_b = Shift.objects.create(
            station=self.station_b,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Station B Main Gate",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift_b.id),
            "latitude": self.station_b.latitude,
            "longitude": self.station_b.longitude,
        })
        self.assertEqual(resp.status_code, status.HTTP_403_FORBIDDEN)
        self.assertIn("Station mismatch", resp.data["detail"])

    def test_H_clock_in_16_minutes_after_start_is_late_true(self):
        start_dt = timezone.localtime() - timedelta(minutes=16)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": self.valid_lat,
            "longitude": self.valid_lon,
            "late_reason": "Traffic congestion on commuter route",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertTrue(resp.data["is_late"])
        self.assertFalse(resp.data["is_serious_late"])
        self.assertEqual(resp.data["late_reason"], "Traffic congestion on commuter route")

        att = Attendance.objects.get(shift=shift, guard=self.guard)
        self.assertTrue(att.is_late)
        self.assertFalse(att.is_serious_late)

    def test_I_clock_in_60_minutes_after_start_serious_lateness_recorded(self):
        start_dt = timezone.localtime() - timedelta(minutes=65)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=8)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": self.valid_lat,
            "longitude": self.valid_lon,
            "late_reason": "Severe transport breakdown",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertTrue(resp.data["is_late"])
        self.assertTrue(resp.data["is_serious_late"])

        att = Attendance.objects.get(shift=shift, guard=self.guard)
        self.assertTrue(att.is_late)
        self.assertTrue(att.is_serious_late)

    def test_J_first_serious_lateness_recorded_no_escalation(self):
        Notification.objects.filter(notification_type="LATENESS_ESCALATION").delete()
        start_dt = timezone.localtime() - timedelta(minutes=70)
        shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=6)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Gate 1",
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(shift.id),
            "latitude": self.valid_lat,
            "longitude": self.valid_lon,
            "late_reason": "First serious late event",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertTrue(resp.data["is_serious_late"])

        # No escalation on 1st occurrence
        escalation_count = Notification.objects.filter(notification_type="LATENESS_ESCALATION").count()
        self.assertEqual(escalation_count, 0)

    def test_K_second_serious_lateness_recorded_no_escalation(self):
        Notification.objects.filter(notification_type="LATENESS_ESCALATION").delete()
        today = timezone.localdate()
        # Create 1 prior serious lateness occurrence this year
        s1 = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=today - timedelta(days=10),
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        Attendance.objects.create(
            shift=s1,
            guard=self.guard,
            clock_in=timezone.make_aware(datetime.combine(s1.date, time(8, 15))),
            is_late=True,
            is_serious_late=True,
        )

        # Clock into a 2nd shift seriously late
        start_dt = timezone.localtime() - timedelta(minutes=75)
        s2 = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=6)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(s2.id),
            "latitude": self.valid_lat,
            "longitude": self.valid_lon,
            "late_reason": "Second serious late event",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertTrue(resp.data["is_serious_late"])

        # Still no escalation on 2nd occurrence
        escalation_count = Notification.objects.filter(notification_type="LATENESS_ESCALATION").count()
        self.assertEqual(escalation_count, 0)

    def test_L_third_serious_lateness_creates_supervisor_notification(self):
        Notification.objects.filter(notification_type="LATENESS_ESCALATION").delete()
        today = timezone.localdate()
        # Create 2 prior serious lateness occurrences this year
        for i in [1, 2]:
            s_prev = Shift.objects.create(
                station=self.station_a,
                guard=self.guard,
                date=today - timedelta(days=i * 5),
                start_time=time(7, 0),
                end_time=time(18, 0),
                shift_type=ShiftType.DAY,
                assignment_type=AssignmentType.NORMAL,
            )
            Attendance.objects.create(
                shift=s_prev,
                guard=self.guard,
                clock_in=timezone.make_aware(datetime.combine(s_prev.date, time(8, 30))),
                is_late=True,
                is_serious_late=True,
            )

        # 3rd serious lateness occurrence
        start_dt = timezone.localtime() - timedelta(minutes=80)
        s3 = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=6)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.guard)
        resp = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(s3.id),
            "latitude": self.valid_lat,
            "longitude": self.valid_lon,
            "late_reason": "Third serious late event",
        })
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertTrue(resp.data["is_serious_late"])

        # Escalation notification must be created for supervisor
        notifs = Notification.objects.filter(notification_type="LATENESS_ESCALATION", user=self.supervisor_a)
        self.assertEqual(notifs.count(), 1)
        notif = notifs.first()
        self.assertIn("REPEATED SERIOUS LATENESS ESCALATION", notif.title)
        self.assertIn("Occurrence #3", notif.title)
        self.assertIn(self.guard.username, notif.message)
        self.assertIn(str(s3.id), notif.message)
        self.assertIn("60+ minutes past scheduled shift start", notif.message)

        att = Attendance.objects.get(shift=s3, guard=self.guard)
        self.assertTrue(att.escalation_notified)

    def test_M_repeating_same_request_does_not_create_duplicate_escalation_notifications(self):
        Notification.objects.filter(notification_type="LATENESS_ESCALATION").delete()
        today = timezone.localdate()
        # Create 2 prior occurrences
        for i in [1, 2]:
            s_prev = Shift.objects.create(
                station=self.station_a,
                guard=self.guard,
                date=today - timedelta(days=i * 5),
                start_time=time(7, 0),
                end_time=time(18, 0),
                shift_type=ShiftType.DAY,
                assignment_type=AssignmentType.NORMAL,
            )
            Attendance.objects.create(
                shift=s_prev,
                guard=self.guard,
                clock_in=timezone.make_aware(datetime.combine(s_prev.date, time(8, 30))),
                is_late=True,
                is_serious_late=True,
            )

        start_dt = timezone.localtime() - timedelta(minutes=80)
        s3 = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=start_dt.date(),
            start_time=start_dt.time(),
            end_time=(start_dt + timedelta(hours=6)).time(),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
        )
        self.client.force_authenticate(user=self.guard)
        resp1 = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(s3.id),
            "latitude": self.valid_lat,
            "longitude": self.valid_lon,
            "late_reason": "Third serious late",
        })
        self.assertEqual(resp1.status_code, status.HTTP_200_OK)

        # First request created exactly 1 notification
        self.assertEqual(Notification.objects.filter(notification_type="LATENESS_ESCALATION").count(), 1)

        # Repeating the request (idempotent check / duplicate prevention)
        resp2 = self.client.post("/shifts/attendance/clock_in/", {
            "shift_id": str(s3.id),
            "latitude": self.valid_lat,
            "longitude": self.valid_lon,
            "late_reason": "Third serious late",
        })
        self.assertEqual(resp2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("already clocked in", resp2.data["detail"])

        # Still exactly 1 notification, zero duplicates created
        self.assertEqual(Notification.objects.filter(notification_type="LATENESS_ESCALATION").count(), 1)

    def test_N_overnight_night_shift_remains_authoritative_after_midnight(self):
        yesterday = timezone.localdate() - timedelta(days=1)
        today = timezone.localdate()

        # Yesterday's night shift (18:00 to 07:00 next day)
        night_shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard,
            date=yesterday,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
            duty_location="Main Campus Overnight Post",
        )

        self.client.force_authenticate(user=self.guard)

        # When queried with an early morning time (before 07:00), today endpoint retrieves yesterday's NIGHT shift
        # We test today endpoint resolution:
        # 1. Without date param when before 07:00 -> resolves yesterday's NIGHT shift
        # 2. Or explicit date query for yesterday -> resolves yesterday's NIGHT shift
        resp = self.client.get(f"/shifts/shifts/today/?date={yesterday.strftime('%Y-%m-%d')}")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["id"], str(night_shift.id))
        self.assertEqual(resp.data["shift_type"], "NIGHT")
        self.assertEqual(resp.data["start_time"], "18:00:00")
        self.assertEqual(resp.data["end_time"], "07:00:00")
