import uuid
from decimal import Decimal
from datetime import date, time, datetime, timedelta
from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework import status

from apps.accounts.models import User, UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, Attendance
from apps.leave.models import (
    LeaveBalance,
    AdjustmentType,
    LeaveAdjustmentRecord,
    LeaveAccrualRecord,
)
from apps.leave.services import process_guard_accruals
from apps.escorts.models import EscortDuty, EscortStatus
from apps.exams.models import ExamDuty, ExamStatus
from apps.core.models import (
    RecordAdjustmentRequest,
    AdjustmentStatus,
    SecurityAuditEvent,
    SupervisorOverrideAudit,
)
from apps.notifications.models import Notification
from apps.patrols.models import PatrolLog


class ControlledRecordsAndAssignedDutiesTests(TestCase):
    def setUp(self):
        self.client = APIClient()

        # Stations
        self.station1 = Station.objects.create(
            name="Alpha Station",
            code="ALP-01",
            latitude=-15.3875,
            longitude=28.3228,
            geofence_radius_meters=200.0,
            is_active=True,
        )
        self.station2 = Station.objects.create(
            name="Beta Station",
            code="BET-02",
            latitude=-15.3900,
            longitude=28.3300,
            geofence_radius_meters=200.0,
            is_active=True,
        )

        # Users
        self.admin = User.objects.create_superuser(
            username="admin_user",
            email="admin@sgmis.com",
            password="AdminPassword123!",
            role=UserRole.ADMINISTRATOR,
            first_name="Admin",
            last_name="Commander",
        )
        self.supervisor = User.objects.create_user(
            username="sup_user",
            email="sup@sgmis.com",
            password="SupPassword123!",
            role=UserRole.SUPERVISOR,
            first_name="Super",
            last_name="Visor",
            station=self.station1,
        )
        self.guard = User.objects.create_user(
            username="guard_user",
            email="guard@sgmis.com",
            password="GuardPassword123!",
            role=UserRole.GUARD,
            first_name="John",
            last_name="Guard",
            employee_number="G-1001",
            station=self.station1,
            phone_number="+260971000001",
        )
        self.guard2 = User.objects.create_user(
            username="guard_partner",
            email="guard2@sgmis.com",
            password="GuardPassword123!",
            role=UserRole.GUARD,
            first_name="Paul",
            last_name="Partner",
            employee_number="G-1002",
            station=self.station1,
            phone_number="+260971000002",
        )
        self.pair1 = GuardPair.objects.create(
            guard_a=self.guard,
            guard_b=self.guard2,
            station=self.station1,
        )

    def test_administrator_can_monitor_patrols_but_cannot_execute_them(self):
        patrol = PatrolLog.objects.create(guard=self.guard, station=self.station1)
        self.client.force_authenticate(user=self.admin)

        view_response = self.client.get("/patrols/logs/")
        self.assertEqual(view_response.status_code, status.HTTP_200_OK)
        patrol_rows = view_response.data.get("results", view_response.data) if isinstance(view_response.data, dict) else view_response.data
        self.assertIn(str(patrol.id), [str(row["id"]) for row in patrol_rows])

        create_response = self.client.post("/patrols/logs/", {
            "guard": str(self.guard.id),
            "station": str(self.station1.id),
        }, format="json")
        self.assertEqual(create_response.status_code, status.HTTP_403_FORBIDDEN)

    # 1. Superuser sets verified current employee number, name, station, shift, pair, roster position and leave balance on a guard record.
    def test_01_superuser_sets_verified_current_records(self):
        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            "/adjustments/adjustments/",
            {
                "guard": str(self.guard.id),
                "field_name": "employee_number",
                "requested_value": "EMP-9999",
                "effective_date": "2026-10-01",
                "reason": "Verified against physical personnel card",
                "status": "APPROVED",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_201_CREATED)
        self.guard.refresh_from_db()
        self.assertEqual(self.guard.employee_number, "EMP-9999")

    # 2. Historical attendance records prior to effective date remain unchanged when current record is set.
    def test_02_historical_attendance_records_remain_unchanged(self):
        past_date = date(2026, 9, 15)
        past_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            shift_type=ShiftType.DAY,
            date=past_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        att = Attendance.objects.create(
            shift=past_shift,
            guard=self.guard,
            clock_in=timezone.make_aware(datetime.combine(past_date, time(7, 5))),
            clock_out=timezone.make_aware(datetime.combine(past_date, time(18, 0))),
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            "/shifts/shifts/reassign_duty/",
            {
                "guard_id": str(self.guard.id),
                "effective_date": "2026-10-01",
                "shift_type": "NIGHT",
                "station_id": str(self.station2.id),
                "reason": "Station transfer from Oct 1 forward",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        # Historical attendance remains intact
        att.refresh_from_db()
        past_shift.refresh_from_db()
        self.assertEqual(past_shift.shift_type, ShiftType.DAY)
        self.assertEqual(past_shift.station, self.station1)
        self.assertIsNotNone(att.clock_in)
        self.assertIsNotNone(att.clock_out)

    # 3. Historical shift records prior to effective date remain unchanged.
    def test_03_historical_shift_records_remain_unchanged(self):
        past_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            shift_type=ShiftType.DAY,
            date=date(2026, 9, 20),
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        future_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            shift_type=ShiftType.DAY,
            date=date(2026, 10, 5),
            start_time=time(7, 0),
            end_time=time(18, 0),
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            "/shifts/shifts/reassign_duty/",
            {
                "guard_id": str(self.guard.id),
                "effective_date": "2026-10-01",
                "shift_type": "NIGHT",
                "reason": "Oct shift rotation adjustment",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        past_shift.refresh_from_db()
        future_shift.refresh_from_db()
        self.assertEqual(past_shift.shift_type, ShiftType.DAY)
        self.assertEqual(future_shift.shift_type, ShiftType.NIGHT)

    # 4. Historical leave accrual records prior to effective date remain unchanged.
    def test_04_historical_leave_accruals_remain_unchanged(self):
        bal = LeaveBalance.objects.create(
            guard=self.guard,
            year=2026,
            annual_days=21,
            vacation_days=Decimal("15.0"),
            casual_days=Decimal("5.0"),
        )
        accrual = LeaveAccrualRecord.objects.create(
            guard=self.guard,
            year=2026,
            month=8,
            casual_credited=Decimal("1.0"),
            vacation_credited=Decimal("2.5"),
            casual_balance_after=Decimal("5.0"),
            vacation_balance_after=Decimal("15.0"),
            created_by=self.admin,
            notes="August monthly accrual",
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            "/leave/balances/set_opening_balance/",
            {
                "guard_id": str(self.guard.id),
                "effective_date": "2026-10-01",
                "vacation_balance": 47.0,
                "casual_balance": 8.0,
                "source": "Physical ledger card #41",
                "reason": "Opening balance reconciliation",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        accrual.refresh_from_db()
        self.assertEqual(accrual.month, 8)
        self.assertEqual(accrual.vacation_credited, Decimal("2.5"))
        bal.refresh_from_db()
        self.assertEqual(bal.vacation_days, Decimal("47.0"))
        self.assertEqual(bal.casual_days, Decimal("8.0"))

    # 5. Supervisor submits record adjustment request: enters PENDING state, superuser receives notification.
    def test_05_supervisor_submits_adjustment_request_enters_pending(self):
        self.client.force_authenticate(user=self.supervisor)
        res = self.client.post(
            "/adjustments/adjustments/",
            {
                "guard": str(self.guard.id),
                "field_name": "vacation_balance",
                "requested_value": "42.0",
                "effective_date": "2026-10-01",
                "reason": "Physical muster ledger audit found uncredited days from 2025",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_201_CREATED)
        adj_id = res.data["id"]
        adj = RecordAdjustmentRequest.objects.get(id=adj_id)
        self.assertEqual(adj.status, AdjustmentStatus.PENDING)
        self.assertEqual(adj.requested_by, self.supervisor)

        # Superuser receives in-app notification
        notif = Notification.objects.filter(user=self.admin, notification_type="OPERATIONAL_ALERT").first()
        self.assertIsNotNone(notif)
        self.assertIn("submitted an adjustment request", notif.message)

    # 6. Supervisor cannot approve their own adjustment request.
    def test_06_supervisor_cannot_approve_adjustment_request(self):
        adj = RecordAdjustmentRequest.objects.create(
            guard=self.guard,
            field_name="vacation_balance",
            requested_value="35.0",
            effective_date=date(2026, 10, 1),
            reason="Supervisor correction proposal",
            requested_by=self.supervisor,
            status=AdjustmentStatus.PENDING,
        )
        self.client.force_authenticate(user=self.supervisor)
        res = self.client.post(f"/adjustments/adjustments/{adj.id}/approve/")
        self.assertEqual(res.status_code, status.HTTP_403_FORBIDDEN)
        adj.refresh_from_db()
        self.assertEqual(adj.status, AdjustmentStatus.PENDING)

    # 7. Superuser approves adjustment request: master record updated, audit record created, guard and supervisor notified.
    def test_07_superuser_approves_adjustment_request(self):
        bal = LeaveBalance.objects.create(guard=self.guard, year=2026, vacation_days=Decimal("10.0"))
        adj = RecordAdjustmentRequest.objects.create(
            guard=self.guard,
            field_name="vacation_balance",
            requested_value="45.0",
            effective_date=date(2026, 10, 1),
            reason="Verified opening balance from 2026 HR physical ledger",
            requested_by=self.supervisor,
            status=AdjustmentStatus.PENDING,
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(f"/adjustments/adjustments/{adj.id}/approve/")
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        adj.refresh_from_db()
        self.assertEqual(adj.status, AdjustmentStatus.APPROVED)
        self.assertEqual(adj.reviewed_by, self.admin)

        # Master record updated
        bal.refresh_from_db()
        self.assertEqual(bal.vacation_days, Decimal("45.0"))
        self.assertEqual(bal.opening_vacation_balance, Decimal("45.0"))

        # Audit record created
        audit = LeaveAdjustmentRecord.objects.filter(guard=self.guard, leave_type="VACATION").first()
        self.assertIsNotNone(audit)
        self.assertEqual(audit.new_balance, Decimal("45.0"))

        # Guard and Supervisor notified
        guard_notif = Notification.objects.filter(user=self.guard, notification_type="OPERATIONAL_ALERT").exists()
        sup_notif = Notification.objects.filter(user=self.supervisor, notification_type="OPERATIONAL_ALERT").exists()
        self.assertTrue(guard_notif)
        self.assertTrue(sup_notif)

    # 8. Superuser rejects adjustment request with reason: master record unchanged, status REJECTED, supervisor notified.
    def test_08_superuser_rejects_adjustment_request(self):
        self.guard.first_name = "Original"
        self.guard.save()
        adj = RecordAdjustmentRequest.objects.create(
            guard=self.guard,
            field_name="first_name",
            requested_value="FakeName",
            effective_date=date(2026, 10, 1),
            reason="Requested name update",
            requested_by=self.supervisor,
            status=AdjustmentStatus.PENDING,
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            f"/adjustments/adjustments/{adj.id}/reject/",
            {"rejection_reason": "NRC card does not match requested name."},
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        adj.refresh_from_db()
        self.assertEqual(adj.status, AdjustmentStatus.REJECTED)
        self.assertEqual(adj.rejection_reason, "NRC card does not match requested name.")

        self.guard.refresh_from_db()
        self.assertEqual(self.guard.first_name, "Original")

        sup_notif = Notification.objects.filter(user=self.supervisor, title="Record Adjustment Rejected").exists()
        self.assertTrue(sup_notif)

    # 9. Superuser modifies proposed value before approving: approved value applied, not the supervisor's original requested value.
    def test_09_superuser_modifies_proposed_value_before_approving(self):
        bal = LeaveBalance.objects.create(guard=self.guard, year=2026, casual_days=Decimal("2.0"))
        adj = RecordAdjustmentRequest.objects.create(
            guard=self.guard,
            field_name="casual_balance",
            requested_value="12.0",
            effective_date=date(2026, 10, 1),
            reason="Supervisor claimed 12 days casual",
            requested_by=self.supervisor,
            status=AdjustmentStatus.PENDING,
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            f"/adjustments/adjustments/{adj.id}/approve/",
            {"approved_value": "8.0"},
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        adj.refresh_from_db()
        self.assertEqual(adj.status, AdjustmentStatus.APPROVED)
        self.assertEqual(adj.approved_value, "8.0")

        bal.refresh_from_db()
        self.assertEqual(bal.casual_days, Decimal("8.0"))

    # 10. Ordinary guard cannot submit or approve record adjustments (permission denied).
    def test_10_ordinary_guard_cannot_submit_or_approve_adjustments(self):
        self.client.force_authenticate(user=self.guard)
        res = self.client.post(
            "/adjustments/adjustments/",
            {
                "guard": str(self.guard.id),
                "field_name": "vacation_balance",
                "requested_value": "90.0",
                "effective_date": "2026-10-01",
                "reason": "Guard self adjustment",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_403_FORBIDDEN)

        adj = RecordAdjustmentRequest.objects.create(
            guard=self.guard,
            field_name="vacation_balance",
            requested_value="50.0",
            effective_date=date(2026, 10, 1),
            reason="Audit entry",
            status=AdjustmentStatus.PENDING,
        )
        res_approve = self.client.post(f"/adjustments/adjustments/{adj.id}/approve/")
        self.assertEqual(res_approve.status_code, status.HTTP_403_FORBIDDEN)

    # 11. Superuser edits shift assignment (Day to Night) with future effective date: schedule from effective date updated; past shifts intact.
    def test_11_superuser_edits_shift_assignment_future_effective_date(self):
        past_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            shift_type=ShiftType.DAY,
            date=date(2026, 9, 28),
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        future_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            shift_type=ShiftType.DAY,
            date=date(2026, 10, 2),
            start_time=time(7, 0),
            end_time=time(18, 0),
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            "/shifts/shifts/reassign_duty/",
            {
                "guard_id": str(self.guard.id),
                "effective_date": "2026-10-01",
                "shift_type": "NIGHT",
                "reason": "Shift rotation to night operations",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        past_shift.refresh_from_db()
        future_shift.refresh_from_db()
        self.assertEqual(past_shift.shift_type, ShiftType.DAY)
        self.assertEqual(future_shift.shift_type, ShiftType.NIGHT)

    # 12. Superuser reassigns guard pair with future effective date: pair on and after effective date updated; past pair assignments intact.
    def test_12_superuser_reassigns_guard_pair_future_effective_date(self):
        new_partner = User.objects.create_user(
            username="new_partner",
            role=UserRole.GUARD,
            station=self.station1,
        )
        past_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            pair=self.pair1,
            shift_type=ShiftType.DAY,
            date=date(2026, 9, 25),
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        future_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            pair=self.pair1,
            shift_type=ShiftType.DAY,
            date=date(2026, 10, 3),
            start_time=time(7, 0),
            end_time=time(18, 0),
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            "/shifts/shifts/reassign_duty/",
            {
                "guard_id": str(self.guard.id),
                "pair_guard_id": str(new_partner.id),
                "effective_date": "2026-10-01",
                "reason": "Re-pairing guards for new tactical cycle",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        past_shift.refresh_from_db()
        future_shift.refresh_from_db()
        self.assertEqual(past_shift.pair, self.pair1)
        self.assertNotEqual(future_shift.pair, self.pair1)
        self.assertTrue(
            future_shift.pair.guard_a == new_partner or future_shift.pair.guard_b == new_partner
        )

    # 13. Superuser reassigns guard station with future effective date: station from effective date updated; past station shifts intact.
    def test_13_superuser_reassigns_guard_station_future_effective_date(self):
        past_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            shift_type=ShiftType.DAY,
            date=date(2026, 9, 26),
            start_time=time(7, 0),
            end_time=time(18, 0),
        )
        future_shift = Shift.objects.create(
            station=self.station1,
            guard=self.guard,
            shift_type=ShiftType.DAY,
            date=date(2026, 10, 4),
            start_time=time(7, 0),
            end_time=time(18, 0),
        )

        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            "/shifts/shifts/reassign_duty/",
            {
                "guard_id": str(self.guard.id),
                "station_id": str(self.station2.id),
                "effective_date": "2026-10-01",
                "reason": "Permanent transfer to Beta Station",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        past_shift.refresh_from_db()
        future_shift.refresh_from_db()
        self.assertEqual(past_shift.station, self.station1)
        self.assertEqual(future_shift.station, self.station2)

    # 14. Superuser sets opening leave balance (e.g. 47 vacation days, 8 casual days) with verified source: opening balance recorded distinctly from monthly accruals.
    def test_14_superuser_sets_opening_leave_balance_distinct_from_accruals(self):
        self.client.force_authenticate(user=self.admin)
        res = self.client.post(
            "/leave/balances/set_opening_balance/",
            {
                "guard_id": str(self.guard.id),
                "effective_date": "2026-10-01",
                "vacation_balance": 47.0,
                "casual_balance": 8.0,
                "source": "Physical ledger 2026 #88",
                "reason": "Reconciliation of opening balance",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        bal = LeaveBalance.objects.get(guard=self.guard, year=2026)
        self.assertEqual(bal.vacation_days, Decimal("47.0"))
        self.assertEqual(bal.casual_days, Decimal("8.0"))
        self.assertEqual(bal.opening_vacation_balance, Decimal("47.0"))
        self.assertEqual(bal.opening_casual_balance, Decimal("8.0"))

        adjustments = LeaveAdjustmentRecord.objects.filter(guard=self.guard)
        self.assertEqual(adjustments.count(), 2)

        # Monthly accruals table has 0 fake entries
        self.assertEqual(LeaveAccrualRecord.objects.filter(guard=self.guard).count(), 0)

    # 15. Monthly leave accrual runs after opening balance is set: adds 2.5 vacation and 1.0 casual to verified opening balance without overwriting it.
    def test_15_monthly_accruals_continue_after_opening_balance(self):
        bal = LeaveBalance.objects.create(
            guard=self.guard,
            year=2026,
            vacation_days=Decimal("47.0"),
            casual_days=Decimal("8.0"),
            opening_vacation_balance=Decimal("47.0"),
            opening_casual_balance=Decimal("8.0"),
            opening_balance_date=date(2026, 10, 1),
            opening_balance_source="Physical ledger 2026 #88",
        )

        # Process accruals for complete months up to end of October 2026
        records = process_guard_accruals(self.guard, as_of_date=date(2026, 11, 1), actor=self.admin)
        self.assertGreater(len(records), 0)

        bal.refresh_from_db()
        self.assertGreater(bal.vacation_days, Decimal("47.0"))
        self.assertGreater(bal.casual_days, Decimal("8.0"))
        self.assertEqual(bal.opening_vacation_balance, Decimal("47.0"))

    # 16. Vacation balance respects 90-day cap even after opening balance + accruals.
    def test_16_vacation_balance_respects_90_day_cap(self):
        bal = LeaveBalance.objects.create(
            guard=self.guard,
            year=2026,
            vacation_days=Decimal("89.0"),
            casual_days=Decimal("5.0"),
            opening_vacation_balance=Decimal("89.0"),
            opening_balance_date=date(2026, 10, 1),
        )

        records = process_guard_accruals(self.guard, as_of_date=date(2026, 11, 1), actor=self.admin)
        bal.refresh_from_db()
        self.assertEqual(bal.vacation_days, Decimal("90.0"))

    # 17. Supervisor creates escort duty assignment: guard receives in-app notification.
    def test_17_supervisor_creates_escort_duty_assignment(self):
        start = timezone.now() + timedelta(days=1)
        end = start + timedelta(hours=6)

        self.client.force_authenticate(user=self.supervisor)
        res = self.client.post(
            "/escorts/duties/",
            {
                "guard": str(self.guard.id),
                "mission_name": "Bank Cash Transit Escort",
                "origin": "Main Branch Vault",
                "destination": "Regional Processing Center",
                "purpose": "High-value currency transfer",
                "instructions": "Armed escort protocol; radio check-in every 30 minutes",
                "contact_numbers": "+260971111222",
                "start_time": start.isoformat(),
                "end_time": end.isoformat(),
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_201_CREATED)
        self.assertTrue(EscortDuty.objects.filter(guard=self.guard, mission_name="Bank Cash Transit Escort").exists())

        # Guard in-app notification
        notif = Notification.objects.filter(user=self.guard, title="Escort Mission Assigned").first()
        self.assertIsNotNone(notif)
        self.assertIn("Bank Cash Transit Escort", notif.message)

    # 18. Guard views assigned escort duties in mobile app showing origin, destination, purpose, instructions and contact details.
    def test_18_guard_views_assigned_escort_duties(self):
        start = timezone.now() + timedelta(days=2)
        end = start + timedelta(hours=4)
        duty = EscortDuty.objects.create(
            guard=self.guard,
            supervisor=self.supervisor,
            station=self.station1,
            mission_name="VIP Airport Transfer",
            origin="Grand Hotel",
            destination="International Terminal 2",
            purpose="Executive security escort",
            instructions="Maintain close formation; liaison with airport protocol",
            contact_numbers="+260979998887",
            start_time=start,
            end_time=end,
        )

        self.client.force_authenticate(user=self.guard)
        res = self.client.get("/escorts/duties/")
        self.assertEqual(res.status_code, status.HTTP_200_OK)
        duties = res.data.get("results", res.data) if isinstance(res.data, dict) else res.data
        duty_data = next((d for d in duties if d["id"] == str(duty.id)), None)
        self.assertIsNotNone(duty_data)
        self.assertEqual(duty_data["origin"], "Grand Hotel")
        self.assertEqual(duty_data["destination"], "International Terminal 2")
        self.assertEqual(duty_data["purpose"], "Executive security escort")
        self.assertEqual(duty_data["contact_numbers"], "+260979998887")
        self.assertTrue(duty_data["reference"].startswith("ESC-"))

    # 19. Guard acknowledges escort duty: status transitions to ACKNOWLEDGED, timestamp and guard recorded.
    def test_19_guard_acknowledges_escort_duty(self):
        duty = EscortDuty.objects.create(
            guard=self.guard,
            supervisor=self.supervisor,
            station=self.station1,
            mission_name="Asset Transport",
            origin="Station Alpha",
            destination="Station Beta",
            start_time=timezone.now() + timedelta(hours=2),
            end_time=timezone.now() + timedelta(hours=6),
            status=EscortStatus.SCHEDULED,
        )

        self.client.force_authenticate(user=self.guard)
        res = self.client.post(
            f"/escorts/duties/{duty.id}/acknowledge/",
            {"remarks": "Received orders; vehicle inspected"},
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_200_OK)

        duty.refresh_from_db()
        self.assertEqual(duty.status, EscortStatus.ACKNOWLEDGED)
        self.assertIsNotNone(duty.acknowledged_at)
        self.assertIn("Received orders", duty.remarks)

    # 20. Guard updates escort duty to EN ROUTE and COMPLETED: operational status progression tracked with timestamps.
    def test_20_guard_updates_escort_duty_progression(self):
        duty = EscortDuty.objects.create(
            guard=self.guard,
            supervisor=self.supervisor,
            station=self.station1,
            mission_name="Inter-facility Patrol",
            origin="Facility A",
            destination="Facility B",
            start_time=timezone.now() + timedelta(hours=1),
            end_time=timezone.now() + timedelta(hours=4),
            status=EscortStatus.ACKNOWLEDGED,
        )

        self.client.force_authenticate(user=self.guard)
        # EN_ROUTE
        res_en_route = self.client.post(
            f"/escorts/duties/{duty.id}/update_status/",
            {"status": "EN_ROUTE", "remarks": "Departed origin on route Alpha"},
            format="json",
        )
        self.assertEqual(res_en_route.status_code, status.HTTP_200_OK)
        duty.refresh_from_db()
        self.assertEqual(duty.status, EscortStatus.EN_ROUTE)
        self.assertIsNotNone(duty.departure_time)

        # COMPLETED
        res_completed = self.client.post(
            f"/escorts/duties/{duty.id}/update_status/",
            {"status": "COMPLETED", "remarks": "Safe arrival; asset handed over to client"},
            format="json",
        )
        self.assertEqual(res_completed.status_code, status.HTTP_200_OK)
        duty.refresh_from_db()
        self.assertEqual(duty.status, EscortStatus.COMPLETED)
        self.assertIsNotNone(duty.completion_time)

    # 21. Superuser views, edits and cancels escort duty with mandatory reason logging.
    def test_21_superuser_manages_escort_duty(self):
        duty = EscortDuty.objects.create(
            guard=self.guard,
            supervisor=self.supervisor,
            station=self.station1,
            mission_name="Special Convoy",
            origin="Base",
            destination="Site",
            start_time=timezone.now() + timedelta(days=3),
            end_time=timezone.now() + timedelta(days=3, hours=5),
            status=EscortStatus.SCHEDULED,
        )

        self.client.force_authenticate(user=self.admin)
        # Edit
        res_edit = self.client.patch(
            f"/escorts/duties/{duty.id}/",
            {
                "destination": "Secured Site Alternative",
                "purpose": "Secure transport",
                "instructions": "Maintain radio contact",
                "contact_numbers": "+260777000111",
                "notes": "Approved route updated",
            },
            format="json",
        )
        self.assertEqual(res_edit.status_code, status.HTTP_200_OK)
        duty.refresh_from_db()
        self.assertEqual(duty.destination, "Secured Site Alternative")
        self.assertEqual(duty.purpose, "Secure transport")
        self.assertEqual(duty.instructions, "Maintain radio contact")
        self.assertTrue(SecurityAuditEvent.objects.filter(target_model="EscortDuty", target_id=str(duty.id)).exists())

        self.client.force_authenticate(user=self.admin)
        forbidden_progress = self.client.post(
            f"/escorts/duties/{duty.id}/update_status/",
            {"status": "EN_ROUTE"},
            format="json",
        )
        self.assertEqual(forbidden_progress.status_code, status.HTTP_403_FORBIDDEN)

        # Cancel
        res_cancel = self.client.post(
            f"/escorts/duties/{duty.id}/update_status/",
            {"status": "CANCELLED", "remarks": "Cancelled due to client mission postponement"},
            format="json",
        )
        self.assertEqual(res_cancel.status_code, status.HTTP_200_OK)
        duty.refresh_from_db()
        self.assertEqual(duty.status, EscortStatus.CANCELLED)

    # 22. Supervisor creates exam period duty assignment: guard receives in-app notification.
    def test_22_supervisor_creates_exam_duty_assignment(self):
        target_date = date(2026, 10, 15)
        self.client.force_authenticate(user=self.supervisor)
        res = self.client.post(
            "/exams/duties/",
            {
                "guard": str(self.guard.id),
                "institution": "University Examination Center",
                "exam_title": "National Secondary Certificate Papers",
                "hall_post": "Hall A - South Gate",
                "supervisor_contact": "+260977888999",
                "instructions": "Candidate screening and biometric identity verification",
                "date": target_date.isoformat(),
                "reporting_time": "06:30:00",
                "start_time": "07:30:00",
                "end_time": "16:00:00",
            },
            format="json",
        )
        self.assertEqual(res.status_code, status.HTTP_201_CREATED)
        self.assertTrue(ExamDuty.objects.filter(guard=self.guard, institution="University Examination Center").exists())

        notif = Notification.objects.filter(user=self.guard, title="Exam Period Duty Assigned").first()
        self.assertIsNotNone(notif)
        self.assertIn("University Examination Center", notif.message)

    # 23. Guard views and acknowledges exam duty: status transitions to ACKNOWLEDGED.
    def test_23_guard_views_and_acknowledges_exam_duty(self):
        exam = ExamDuty.objects.create(
            guard=self.guard,
            supervisor=self.supervisor,
            station=self.station1,
            institution="Polytechnic Campus",
            exam_title="Engineering Finals Day 1",
            hall_post="Auditorium Post 3",
            supervisor_contact="+260971234567",
            instructions="Strict hall integrity; no mobile phones permitted",
            date=date(2026, 10, 20),
            reporting_time=time(6, 30),
            start_time=time(7, 30),
            end_time=time(14, 0),
            status=ExamStatus.ASSIGNED,
        )

        self.client.force_authenticate(user=self.guard)
        res_list = self.client.get("/exams/duties/")
        self.assertEqual(res_list.status_code, status.HTTP_200_OK)
        exams = res_list.data.get("results", res_list.data) if isinstance(res_list.data, dict) else res_list.data
        exam_data = next((e for e in exams if e["id"] == str(exam.id)), None)
        self.assertIsNotNone(exam_data)
        self.assertEqual(exam_data["institution"], "Polytechnic Campus")
        self.assertTrue(exam_data["reference"].startswith("EXAM-"))

        # Acknowledge
        res_ack = self.client.post(
            f"/exams/duties/{exam.id}/acknowledge/",
            {"remarks": "Confirmed duty reporting instructions"},
            format="json",
        )
        self.assertEqual(res_ack.status_code, status.HTTP_200_OK)
        exam.refresh_from_db()
        self.assertEqual(exam.status, ExamStatus.ACKNOWLEDGED)
        self.assertIsNotNone(exam.acknowledged_at)

    # 24. Superuser has master administrative control over exam period duties.
    def test_24_superuser_master_control_over_exam_duties(self):
        exam = ExamDuty.objects.create(
            guard=self.guard,
            supervisor=self.supervisor,
            station=self.station1,
            institution="National College",
            exam_title="Medical Board Exams",
            date=date(2026, 10, 25),
            start_time=time(8, 0),
            end_time=time(16, 0),
            status=ExamStatus.ASSIGNED,
        )

        self.client.force_authenticate(user=self.admin)
        # Edit
        res_edit = self.client.patch(
            f"/exams/duties/{exam.id}/",
            {
                "hall_post": "Main Hall Post 1",
                "supervisor_contact": "+260777000222",
                "instructions": "Screen each entrance",
                "reporting_time": "06:45:00",
                "notes": "Revised reporting instructions",
            },
            format="json",
        )
        self.assertEqual(res_edit.status_code, status.HTTP_200_OK)
        exam.refresh_from_db()
        self.assertEqual(exam.hall_post, "Main Hall Post 1")
        self.assertEqual(exam.supervisor_contact, "+260777000222")
        self.assertEqual(exam.instructions, "Screen each entrance")
        self.assertTrue(SecurityAuditEvent.objects.filter(target_model="ExamDuty", target_id=str(exam.id)).exists())

        forbidden_progress = self.client.post(
            f"/exams/duties/{exam.id}/update_status/",
            {"status": "IN_PROGRESS"},
            format="json",
        )
        self.assertEqual(forbidden_progress.status_code, status.HTTP_403_FORBIDDEN)

        # Cancel
        res_cancel = self.client.post(
            f"/exams/duties/{exam.id}/update_status/",
            {"status": "CANCELLED", "remarks": "Rescheduled by National Examinations Council"},
            format="json",
        )
        self.assertEqual(res_cancel.status_code, status.HTTP_200_OK)
        exam.refresh_from_db()
        self.assertEqual(exam.status, ExamStatus.CANCELLED)

    def test_25_administrative_history_is_admin_only_and_uses_existing_records(self):
        adjustment = RecordAdjustmentRequest.objects.create(
            guard=self.guard,
            field_name="employee_number",
            old_value="G-1001",
            requested_value="G-1009",
            approved_value="G-1009",
            effective_date=date(2026, 10, 1),
            reason="Verified from signed personnel ledger",
            status=AdjustmentStatus.APPROVED,
            requested_by=self.admin,
            reviewed_by=self.admin,
            reviewed_at=timezone.now(),
        )
        self.client.force_authenticate(user=self.admin)
        response = self.client.get("/core/admin-history/")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertTrue(any(row["id"] == str(adjustment.id) for row in response.data))

        self.client.force_authenticate(user=self.supervisor)
        denied = self.client.get("/core/admin-history/")
        self.assertEqual(denied.status_code, status.HTTP_403_FORBIDDEN)

    def test_26_single_shift_reassignment_updates_only_selected_shift_and_audits(self):
        target_date = date(2026, 10, 20)
        selected_shift = Shift.objects.create(
            station=self.station1, guard=self.guard, shift_type=ShiftType.DAY,
            date=target_date, start_time=time(7, 0), end_time=time(18, 0),
        )
        other_shift = Shift.objects.create(
            station=self.station1, guard=self.guard, shift_type=ShiftType.NIGHT,
            date=target_date + timedelta(days=1), start_time=time(18, 0), end_time=time(7, 0),
        )
        self.client.force_authenticate(user=self.admin)
        response = self.client.post(
            f"/shifts/shifts/{selected_shift.id}/reassign/",
            {"guard_id": str(self.guard2.id), "station_id": str(self.station2.id), "reason": "Approved single-post replacement"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        selected_shift.refresh_from_db()
        other_shift.refresh_from_db()
        self.assertEqual(selected_shift.guard, self.guard2)
        self.assertEqual(selected_shift.station, self.station2)
        self.assertEqual(other_shift.guard, self.guard)
        self.assertEqual(other_shift.station, self.station1)
        self.assertTrue(response.data["historical_preserved"])
        self.assertFalse(response.data["roster_regenerated"])
        self.assertTrue(SecurityAuditEvent.objects.filter(target_id=str(selected_shift.id), details__action="SINGLE_SHIFT_REASSIGNED").exists())

    def test_27_supervisor_cannot_create_national_guard_pair(self):
        self.client.force_authenticate(user=self.supervisor)
        response = self.client.post(
            "/stations/pairs/",
            {"station": str(self.station1.id), "guard_a": str(self.guard.id), "guard_b": str(self.guard2.id), "rotation_order": 2},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
