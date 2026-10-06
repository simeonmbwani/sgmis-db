from datetime import date, timedelta, time
from decimal import Decimal
from django.utils import timezone
from rest_framework.test import APITestCase
from rest_framework import status
from apps.accounts.models import User, UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType
from apps.leave.models import (
    LeaveBalance,
    LeaveAdjustmentRecord,
    PublicHolidayCompensationLedger,
    CompensationLedgerEntryType,
    AdjustmentType,
)
from apps.core.models import (
    RecordAdjustmentRequest,
    AdjustmentStatus,
    SecurityAuditEvent,
    SupervisorOverrideAudit,
)


class SuperuserMasterControlTests(APITestCase):
    def setUp(self):
        self.station_a = Station.objects.create(
            name="Station Alpha",
            code="STA-A",
            latitude=-17.8252,
            longitude=31.0335,
        )
        self.station_b = Station.objects.create(
            name="Station Beta",
            code="STA-B",
            latitude=-17.8300,
            longitude=31.0400,
        )

        self.admin = User.objects.create_user(
            username="admin_user",
            password="Password123!",
            role=UserRole.ADMINISTRATOR,
            is_staff=True,
            is_superuser=True,
            employee_number="ADM001",
            first_name="Alice",
            last_name="Admin",
        )

        self.supervisor_a = User.objects.create_user(
            username="sup_alpha",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station_a,
            employee_number="SUP001",
            first_name="Sam",
            last_name="Supervisor",
        )

        self.supervisor_b = User.objects.create_user(
            username="sup_beta",
            password="Password123!",
            role=UserRole.SUPERVISOR,
            station=self.station_b,
            employee_number="SUP002",
        )

        self.guard_1 = User.objects.create_user(
            username="guard_one",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station_a,
            employee_number="GRD001",
            first_name="George",
            last_name="One",
        )

        self.guard_2 = User.objects.create_user(
            username="guard_two",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station_a,
            employee_number="GRD002",
            first_name="Gina",
            last_name="Two",
        )

        self.guard_3 = User.objects.create_user(
            username="guard_three",
            password="Password123!",
            role=UserRole.GUARD,
            station=self.station_a,
            employee_number="GRD003",
        )

        self.pair_1 = GuardPair.objects.create(
            station=self.station_a,
            guard_a=self.guard_1,
            guard_b=self.guard_2,
            rotation_order=1,
            is_active=True,
        )

    def test_admin_set_opening_leave_balance(self):
        self.client.force_authenticate(user=self.admin)
        url = "/leave/balances/set_opening_balance/"
        today_str = date.today().isoformat()
        payload = {
            "guard_id": str(self.guard_1.id),
            "effective_date": today_str,
            "reason": "Muster roll physical ledger reconciliation 2026",
            "source": "Physical ledger page 44",
            "vacation_balance": 18.5,
            "casual_balance": 6.0,
            "compensation_balance": 4.0,
        }
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_200_OK, res.data)

        bal = LeaveBalance.objects.get(guard=self.guard_1, year=date.today().year)
        self.assertEqual(bal.vacation_days, Decimal("18.5"))
        self.assertEqual(bal.casual_days, Decimal("6.0"))

        adjustments = LeaveAdjustmentRecord.objects.filter(guard=self.guard_1)
        self.assertTrue(adjustments.filter(leave_type="VACATION").exists())
        self.assertTrue(adjustments.filter(leave_type="CASUAL").exists())
        self.assertEqual(adjustments.first().authorized_by, self.admin)

        rem_comp = PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard_1)
        self.assertEqual(rem_comp, Decimal("4.0"))

    def test_non_admin_cannot_set_opening_balance(self):
        url = "/leave/balances/set_opening_balance/"
        payload = {
            "guard_id": str(self.guard_1.id),
            "effective_date": date.today().isoformat(),
            "reason": "Unauthorized attempt",
            "vacation_balance": 20.0,
        }
        # Guard
        self.client.force_authenticate(user=self.guard_1)
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_403_FORBIDDEN)

        # Supervisor
        self.client.force_authenticate(user=self.supervisor_a)
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_403_FORBIDDEN)

    def test_admin_reassign_duty_preserves_history(self):
        past_date = date.today() - timedelta(days=2)
        future_date = date.today() + timedelta(days=2)

        past_shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard_1,
            date=past_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            pair=self.pair_1,
        )
        future_shift = Shift.objects.create(
            station=self.station_a,
            guard=self.guard_1,
            date=future_date,
            start_time=time(7, 0),
            end_time=time(18, 0),
            shift_type=ShiftType.DAY,
            pair=self.pair_1,
        )

        self.client.force_authenticate(user=self.admin)
        url = "/shifts/shifts/reassign_duty/"
        payload = {
            "guard_id": str(self.guard_1.id),
            "effective_date": future_date.isoformat(),
            "reason": "Operational rebalance to Station Beta Night Shift",
            "station_id": str(self.station_b.id),
            "shift_type": "NIGHT",
            "pair_guard_id": str(self.guard_3.id),
            "roster_position": 2,
            "assignment_type": "NORMAL",
        }
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_200_OK, res.data)

        # Verify historical shift untouched
        past_shift.refresh_from_db()
        self.assertEqual(past_shift.station, self.station_a)
        self.assertEqual(past_shift.shift_type, ShiftType.DAY)

        # Verify future shift updated
        future_shift.refresh_from_db()
        self.assertEqual(future_shift.station, self.station_b)
        self.assertEqual(future_shift.shift_type, ShiftType.NIGHT)
        self.assertEqual(future_shift.start_time, time(18, 0))
        self.assertEqual(future_shift.end_time, time(7, 0))

        # Verify audits created
        self.assertTrue(
            SupervisorOverrideAudit.objects.filter(
                supervisor=self.admin, action_type="SHIFT_DUTY_REASSIGNMENT"
            ).exists()
        )
        self.assertTrue(
            SecurityAuditEvent.objects.filter(
                actor=self.admin, target_id=str(self.guard_1.id)
            ).exists()
        )

    def test_non_admin_cannot_reassign_duty(self):
        url = "/shifts/shifts/reassign_duty/"
        payload = {
            "guard_id": str(self.guard_1.id),
            "effective_date": (date.today() + timedelta(days=1)).isoformat(),
            "reason": "Unauthorized attempt",
        }
        self.client.force_authenticate(user=self.supervisor_a)
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_403_FORBIDDEN)

    def test_admin_direct_reconciliation_record_adjustment(self):
        self.client.force_authenticate(user=self.admin)
        url = "/core/adjustments/"
        today_str = date.today().isoformat()

        # Direct employee_number correction
        payload = {
            "guard": str(self.guard_1.id),
            "field_name": "employee_number",
            "requested_value": "GRD-8888",
            "effective_date": today_str,
            "reason": "HR payroll national audit correction",
            "status": "APPROVED",
        }
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_201_CREATED, res.data)
        self.assertEqual(res.data["status"], "APPROVED")

        self.guard_1.refresh_from_db()
        self.assertEqual(self.guard_1.employee_number, "GRD-8888")

        audit = SecurityAuditEvent.objects.filter(
            actor=self.admin, event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT
        ).latest("timestamp")
        self.assertEqual(audit.details.get("new_value"), "GRD-8888")
        self.assertEqual(audit.details.get("employee_number"), "GRD001")

    def test_supervisor_submits_pending_and_admin_approves(self):
        # Supervisor A submits for Guard 1 (in Station A)
        self.client.force_authenticate(user=self.supervisor_a)
        url = "/core/adjustments/"
        today_str = date.today().isoformat()
        payload = {
            "guard": str(self.guard_1.id),
            "field_name": "vacation_balance",
            "requested_value": "25.0",
            "effective_date": today_str,
            "reason": "Supervisor station attendance review",
        }
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_201_CREATED, res.data)
        self.assertEqual(res.data["status"], "PENDING")
        adj_id = res.data["id"]

        # Supervisor cannot approve
        approve_url = f"/core/adjustments/{adj_id}/approve/"
        res_approve = self.client.post(approve_url, {}, format="json")
        self.assertEqual(res_approve.status_code, status.HTTP_403_FORBIDDEN)

        # Admin approves
        self.client.force_authenticate(user=self.admin)
        res_admin_approve = self.client.post(approve_url, {"approved_value": "25.0"}, format="json")
        self.assertEqual(res_admin_approve.status_code, status.HTTP_200_OK, res_admin_approve.data)
        self.assertEqual(res_admin_approve.data["adjustment"]["status"], "APPROVED")

        bal = LeaveBalance.objects.get(guard=self.guard_1, year=date.today().year)
        self.assertEqual(bal.vacation_days, Decimal("25.0"))

    def test_admin_rejects_adjustment_request(self):
        self.client.force_authenticate(user=self.supervisor_a)
        url = "/core/adjustments/"
        payload = {
            "guard": str(self.guard_1.id),
            "field_name": "casual_balance",
            "requested_value": "15.0",
            "effective_date": date.today().isoformat(),
            "reason": "Proposed casual adjustment",
        }
        res = self.client.post(url, payload, format="json")
        adj_id = res.data["id"]

        # Admin rejects with reason
        self.client.force_authenticate(user=self.admin)
        reject_url = f"/core/adjustments/{adj_id}/reject/"
        res_reject = self.client.post(
            reject_url, {"rejection_reason": "Casual entitlement exceeds cycle maximum"}, format="json"
        )
        self.assertEqual(res_reject.status_code, status.HTTP_200_OK, res_reject.data)
        self.assertEqual(res_reject.data["adjustment"]["status"], "REJECTED")

        req = RecordAdjustmentRequest.objects.get(id=adj_id)
        self.assertEqual(req.status, AdjustmentStatus.REJECTED)
        self.assertEqual(req.rejection_reason, "Casual entitlement exceeds cycle maximum")

    def test_supervisor_cross_station_rejected(self):
        # Supervisor B belongs to Station B; Guard 1 is Station A
        self.client.force_authenticate(user=self.supervisor_b)
        url = "/core/adjustments/"
        payload = {
            "guard": str(self.guard_1.id),
            "field_name": "employee_number",
            "requested_value": "GRD-9999",
            "effective_date": date.today().isoformat(),
            "reason": "Cross-station attempt",
        }
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_403_FORBIDDEN)

    def test_guard_cannot_submit_adjustment(self):
        self.client.force_authenticate(user=self.guard_1)
        url = "/core/adjustments/"
        payload = {
            "guard": str(self.guard_1.id),
            "field_name": "employee_number",
            "requested_value": "GRD-9999",
            "effective_date": date.today().isoformat(),
            "reason": "Self adjustment",
        }
        res = self.client.post(url, payload, format="json")
        self.assertEqual(res.status_code, status.HTTP_403_FORBIDDEN)

    def test_administrative_history_returns_authoritative_audit(self):
        # Create an administrative action
        self.client.force_authenticate(user=self.admin)
        set_url = "/leave/balances/set_opening_balance/"
        self.client.post(set_url, {
            "guard_id": str(self.guard_1.id),
            "effective_date": date.today().isoformat(),
            "reason": "Audit verification for history",
            "vacation_balance": 12.0,
        }, format="json")

        history_url = "/core/admin-history/"
        res = self.client.get(history_url)
        self.assertEqual(res.status_code, status.HTTP_200_OK)
        entries = res.data
        self.assertTrue(len(entries) > 0)

        # Check entry schema and contents
        leave_entry = next((e for e in entries if e.get("kind") == "LEAVE_ADJUSTMENT"), None)
        self.assertIsNotNone(leave_entry)
        self.assertIn("Alice Admin", leave_entry["actor"])
        self.assertIn("ADM001", leave_entry["actor"])
        self.assertEqual(leave_entry["target_model"], "LeaveBalance")
        self.assertEqual(leave_entry["new_value"], "12.0")
        self.assertIn("George One", leave_entry["details"]["employee"])
        self.assertEqual(leave_entry["details"]["station"], "Station Alpha")
        self.assertEqual(leave_entry["details"]["admin_employee_number"], "ADM001")

        # Guard and Supervisor cannot access administrative history
        self.client.force_authenticate(user=self.guard_1)
        res_guard = self.client.get(history_url)
        self.assertEqual(res_guard.status_code, status.HTTP_403_FORBIDDEN)

        self.client.force_authenticate(user=self.supervisor_a)
        res_sup = self.client.get(history_url)
        self.assertEqual(res_sup.status_code, status.HTTP_403_FORBIDDEN)
