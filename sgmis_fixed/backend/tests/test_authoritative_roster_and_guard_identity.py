import uuid
import re
from datetime import date, time, timedelta, datetime
from django.utils import timezone
from django.test import TestCase
from rest_framework import status
from rest_framework.test import APIClient
from django.core.exceptions import ValidationError

from apps.accounts.models import User, UserRole, LoginAttempt, PasswordResetOTP
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, AssignmentType, DutyRoster, RosterStatus, Attendance
from apps.shifts.services import resolve_guard_duty
from apps.leave.models import LeaveApplication, LeaveType, LeaveStatus
from apps.exams.models import ExamDuty, ExamStatus
from apps.escorts.models import EscortDuty, EscortStatus
from apps.core.sms import InMemorySMSProvider

class AuthoritativeRosterAndGuardIdentityTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.today = timezone.localdate()
        self.tomorrow = self.today + timedelta(days=1)

        # Station
        self.station = Station.objects.create(
            name="Alpha Station",
            code="ALP01",
            latitude=-17.82,
            longitude=31.05,
            geofence_radius_meters=300.0,
            is_active=True,
        )

        # Supervisor
        self.supervisor = User.objects.create_user(
            username="sup_alpha",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station,
            first_name="Alpha",
            last_name="Supervisor",
        )

        # Guard A & Guard B in the same pair
        self.guard_a = User.objects.create_user(
            username="guard_a",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station,
            first_name="Alice",
            last_name="Guard",
            employee_number="SEC-101",
            phone_number="+263771111111",
        )

        self.guard_b = User.objects.create_user(
            username="guard_b",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station,
            first_name="Bob",
            last_name="Guard",
            employee_number="SEC-102",
            phone_number="+263772222222",
        )

        self.pair = GuardPair.objects.create(
            station=self.station,
            guard_a=self.guard_a,
            guard_b=self.guard_b,
            rotation_order=1,
            is_active=True,
        )

        # Approved active duty roster
        self.roster = DutyRoster.objects.create(
            station=self.station,
            start_date=self.today,
            end_date=self.today + timedelta(days=11),
            status=RosterStatus.APPROVED,
            approved_by=self.supervisor,
            approved_at=timezone.now(),
        )

        # Shift assignments: Guard A -> DAY, Guard B -> NIGHT
        self.shift_a = Shift.objects.create(
            station=self.station,
            guard=self.guard_a,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            pair=self.pair,
            roster=self.roster,
        )

        self.shift_b = Shift.objects.create(
            station=self.station,
            guard=self.guard_b,
            date=self.today,
            start_time=time(18, 0),
            end_time=time(7, 0),
            shift_type=ShiftType.NIGHT,
            assignment_type=AssignmentType.NORMAL,
            pair=self.pair,
            roster=self.roster,
        )

    # =========================================================================
    # 1. CRITICAL RULE: GUARD MUST ONLY SEE THEIR OWN DATA (NO CROSS-LEAKAGE)
    # =========================================================================
    def test_guard_a_and_guard_b_strict_isolation(self):
        """
        Log in as Guard A: Every guard-specific endpoint identifies Guard A and returns ONLY Guard A's shifts.
        Log in as Guard B: Every guard-specific endpoint identifies Guard B and returns ONLY Guard B's shifts.
        """
        # --- Guard A ---
        self.client.force_authenticate(user=self.guard_a)

        # Today endpoint
        resp_today_a = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp_today_a.status_code, status.HTTP_200_OK)
        self.assertEqual(str(resp_today_a.data["guard"]), str(self.guard_a.id))
        self.assertEqual(resp_today_a.data["guard_name"], "Alice Guard")
        self.assertEqual(resp_today_a.data["shift_type"], "DAY")

        # Shifts list endpoint
        resp_list_a = self.client.get("/shifts/shifts/")
        self.assertEqual(resp_list_a.status_code, status.HTTP_200_OK)
        shift_ids_a = [str(s["id"]) for s in resp_list_a.data]
        self.assertIn(str(self.shift_a.id), shift_ids_a)
        self.assertNotIn(str(self.shift_b.id), shift_ids_a)
        for s in resp_list_a.data:
            self.assertEqual(str(s["guard"]), str(self.guard_a.id))

        # Operational endpoint
        resp_op_a = self.client.get("/shifts/shifts/operational/")
        self.assertEqual(resp_op_a.status_code, status.HTTP_200_OK)
        for s in resp_op_a.data:
            self.assertEqual(str(s["guard"]), str(self.guard_a.id))

        # My current roster endpoint
        resp_roster_a = self.client.get("/shifts/shifts/my_current_roster/")
        self.assertEqual(resp_roster_a.status_code, status.HTTP_200_OK)
        for s in resp_roster_a.data:
            self.assertEqual(str(s["guard"]), str(self.guard_a.id))

        # --- Guard B ---
        self.client.force_authenticate(user=self.guard_b)

        # Today endpoint
        resp_today_b = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp_today_b.status_code, status.HTTP_200_OK)
        self.assertEqual(str(resp_today_b.data["guard"]), str(self.guard_b.id))
        self.assertEqual(resp_today_b.data["guard_name"], "Bob Guard")
        self.assertEqual(resp_today_b.data["shift_type"], "NIGHT")

        # Shifts list endpoint
        resp_list_b = self.client.get("/shifts/shifts/")
        self.assertEqual(resp_list_b.status_code, status.HTTP_200_OK)
        shift_ids_b = [str(s["id"]) for s in resp_list_b.data]
        self.assertIn(str(self.shift_b.id), shift_ids_b)
        self.assertNotIn(str(self.shift_a.id), shift_ids_b)
        for s in resp_list_b.data:
            self.assertEqual(str(s["guard"]), str(self.guard_b.id))

        # Operational endpoint
        resp_op_b = self.client.get("/shifts/shifts/operational/")
        self.assertEqual(resp_op_b.status_code, status.HTTP_200_OK)
        for s in resp_op_b.data:
            self.assertEqual(str(s["guard"]), str(self.guard_b.id))

        # My current roster endpoint
        resp_roster_b = self.client.get("/shifts/shifts/my_current_roster/")
        self.assertEqual(resp_roster_b.status_code, status.HTTP_200_OK)
        for s in resp_roster_b.data:
            self.assertEqual(str(s["guard"]), str(self.guard_b.id))

    # =========================================================================
    # 2. NORMAL PAIR RULE (1 DAY, 1 NIGHT per pair)
    # =========================================================================
    def test_pair_invariant_enforces_1_day_1_night(self):
        """Shift.clean() rejects duplicate DAY or >2 guards on same date for same pair."""
        # Attempt duplicate DAY shift for Guard B under same pair
        duplicate_day = Shift(
            station=self.station,
            guard=self.guard_b,
            date=self.today,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            pair=self.pair,
            roster=self.roster,
        )
        with self.assertRaises(ValidationError):
            duplicate_day.clean()

    # =========================================================================
    # 3. ROBUST DAY ⇄ NIGHT SWAP (ATOMIC, CLEAN ERRORS)
    # =========================================================================
    def test_swap_pair_duties_success_and_reflected_on_both_guards(self):
        """Supervisor swaps Day and Night duties. Both guard apps reflect the change."""
        self.client.force_authenticate(user=self.supervisor)
        swap_resp = self.client.post("/shifts/shifts/swap_pair_duties/", {
            "station_id": str(self.station.id),
            "date": self.today.isoformat(),
            "pair_id": str(self.pair.id),
            "reason": "Operational rotation requested by station commander",
        })
        self.assertEqual(swap_resp.status_code, status.HTTP_200_OK)

        self.shift_a.refresh_from_db()
        self.shift_b.refresh_from_db()

        # Guard A was DAY -> now NIGHT
        # Guard B was NIGHT -> now DAY
        self.assertEqual(self.shift_a.guard_id, self.guard_b.id)
        self.assertEqual(self.shift_b.guard_id, self.guard_a.id)

        # Check Guard A sees NIGHT now
        self.client.force_authenticate(user=self.guard_a)
        resp_a = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp_a.data["shift_type"], "NIGHT")

        # Check Guard B sees DAY now
        self.client.force_authenticate(user=self.guard_b)
        resp_b = self.client.get("/shifts/shifts/today/")
        self.assertEqual(resp_b.data["shift_type"], "DAY")

    def test_swap_pair_duties_validation_errors(self):
        """Malformed date or UUID returns clean 400 Bad Request, never 500."""
        self.client.force_authenticate(user=self.supervisor)

        # Malformed date
        resp_date = self.client.post("/shifts/shifts/swap_pair_duties/", {
            "date": "not-a-valid-date",
            "reason": "Test swap",
        })
        self.assertEqual(resp_date.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("Invalid date format", resp_date.data["detail"])

        # Malformed pair_id UUID
        resp_uuid = self.client.post("/shifts/shifts/swap_pair_duties/", {
            "date": self.today.isoformat(),
            "pair_id": "not-a-uuid",
            "reason": "Test swap",
        })
        self.assertEqual(resp_uuid.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("Invalid pair_id format", resp_uuid.data["detail"])

    # =========================================================================
    # 4. REASSIGNMENT EXCLUDES ARCHIVED ROSTERS AND CLEANS ORPHAN OFF RECORDS
    # =========================================================================
    def test_reassign_single_cleans_orphan_off_records(self):
        """Reassigning an active shift clears any orphan TIME_OFF record for the new guard."""
        # Create an orphan OFF shift for Guard B on tomorrow
        Shift.objects.create(
            station=self.station,
            guard=self.guard_b,
            date=self.tomorrow,
            start_time=time(0, 0),
            end_time=time(0, 0),
            shift_type=ShiftType.OFF,
            assignment_type=AssignmentType.TIME_OFF,
        )

        # Tomorrow shift assigned to Guard A
        tomorrow_shift = Shift.objects.create(
            station=self.station,
            guard=self.guard_a,
            date=self.tomorrow,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster,
        )

        self.client.force_authenticate(user=self.supervisor)
        reassign_resp = self.client.post(f"/shifts/shifts/{tomorrow_shift.id}/reassign/", {
            "guard_id": str(self.guard_b.id),
            "reason": "Guard A on sick leave substitution",
        })
        self.assertEqual(reassign_resp.status_code, status.HTTP_200_OK)

        # Confirm the orphan OFF shift for Guard B was deleted
        self.assertFalse(
            Shift.objects.filter(guard=self.guard_b, date=self.tomorrow, shift_type=ShiftType.OFF).exists()
        )

    # =========================================================================
    # 5. STRICT LEAVE EXPIRY AND DUTY RESOLUTION
    # =========================================================================
    def test_leave_expiry_restores_authoritative_duty_state(self):
        """When approved leave ends, guard automatically becomes eligible for duty per roster."""
        # Put Guard A on leave covering today
        leave = LeaveApplication.objects.create(
            guard=self.guard_a,
            leave_type=LeaveType.CASUAL,
            start_date=self.today - timedelta(days=2),
            end_date=self.today,
            status=LeaveStatus.APPROVED,
            reason="Family matter",
        )

        # During leave: resolve_guard_duty returns ON_LEAVE
        resolved_today = resolve_guard_duty(self.guard_a, date=self.today)
        self.assertEqual(resolved_today["duty_state"], "ON_LEAVE")
        self.assertTrue(resolved_today["is_on_leave"])
        self.assertFalse(resolved_today["clock_in_enabled"])

        # Day after leave ends: resolve_guard_duty does NOT return ON_LEAVE
        # If Guard A has a scheduled shift on tomorrow, they are ELIGIBLE_FOR_DUTY
        Shift.objects.create(
            station=self.station,
            guard=self.guard_a,
            date=self.tomorrow,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            roster=self.roster,
        )
        resolved_tomorrow = resolve_guard_duty(self.guard_a, date=self.tomorrow, current_time=time(8, 0))
        self.assertEqual(resolved_tomorrow["duty_state"], "ELIGIBLE_FOR_DUTY")
        self.assertFalse(resolved_tomorrow["is_on_leave"])
        self.assertTrue(resolved_tomorrow["clock_in_enabled"])

    # =========================================================================
    # 6. AUTHENTICATION LOCKOUT (3 FAILED ATTEMPTS) & SMS RESET UNLOCK
    # =========================================================================
    def test_three_failed_logins_lockout_and_sms_recovery_unlock(self):
        """
        Attempt 1: retry allowed.
        Attempt 2: retry allowed.
        Attempt 3: locked for 15 minutes.
        Reset password via SMS OTP unlocks the account immediately.
        """
        # Attempt 1
        r1 = self.client.post("/auth/login/", {"identifier": "guard_a", "password": "wrong"})
        self.assertEqual(r1.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(r1.data.get("remaining_attempts"), 2)
        self.assertFalse(r1.data.get("is_locked", False))

        # Attempt 2
        r2 = self.client.post("/auth/login/", {"identifier": "guard_a", "password": "wrong"})
        self.assertEqual(r2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(r2.data.get("remaining_attempts"), 1)
        self.assertFalse(r2.data.get("is_locked", False))

        # Attempt 3 -> Locked
        r3 = self.client.post("/auth/login/", {"identifier": "guard_a", "password": "wrong"})
        self.assertEqual(r3.status_code, status.HTTP_429_TOO_MANY_REQUESTS)
        self.assertTrue(r3.data.get("is_locked", False))
        self.assertEqual(r3.data.get("lockout_remaining_minutes"), 15)

        # Attempt with correct password while locked is rejected
        r_blocked = self.client.post("/auth/login/", {"identifier": "guard_a", "password": "Password123!"})
        self.assertEqual(r_blocked.status_code, status.HTTP_429_TOO_MANY_REQUESTS)

        # Request recovery OTP via SMS while locked
        req_otp = self.client.post("/auth/password_reset/request/", {"identifier": "guard_a"})
        self.assertEqual(req_otp.status_code, status.HTTP_200_OK)

        sms = InMemorySMSProvider.get_last_sms("+263771111111")
        self.assertIsNotNone(sms)
        raw_otp = re.search(r"\b(\d{6})\b", sms["message"]).group(1)

        # Confirm password reset
        confirm_resp = self.client.post("/auth/password_reset/confirm/", {
            "identifier": "guard_a",
            "otp_code": raw_otp,
            "new_password": "NewSecretPassword2026!",
        })
        self.assertEqual(confirm_resp.status_code, status.HTTP_200_OK)

        # Account is unlocked: Login with new password succeeds immediately
        login_success = self.client.post("/auth/login/", {
            "identifier": "guard_a",
            "password": "NewSecretPassword2026!",
        })
        self.assertEqual(login_success.status_code, status.HTTP_200_OK)
        self.assertIn("access", login_success.data)
