import os
from django.utils import timezone
from django.db import models
from rest_framework import viewsets, status
from rest_framework.decorators import api_view, permission_classes
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from apps.incidents.models import IncidentReport, IncidentStatus, IncidentPriority
from apps.occurrence_book.models import OccurrenceBookEntry
from apps.patrols.models import PatrolLog, PatrolStatus
from apps.accounts.models import User, UserRole
from apps.stations.models import Station
from apps.escorts.models import EscortDuty, EscortStatus
from apps.shifts.models import Shift, Attendance
from apps.leave.models import LeaveApplication, LeaveStatus
from apps.accounts.permissions import IsAdministrator
from apps.core.models import RecordAdjustmentRequest, SecurityAuditEvent, SupervisorOverrideAudit
from apps.leave.models import LeaveAdjustmentRecord
from apps.core.serializers import RecordAdjustmentRequestSerializer

import sys
import traceback
from django.http import JsonResponse

_LAST_SERVER_ERROR = None

@api_view(["GET"])
@permission_classes([AllowAny])
def health_check(request):
    """
    Service health check endpoint.
    Safe for load balancers and container orchestrators.
    Supports ?diag=1 for safe read-only schema/simulation diagnosis.
    """
    if request.GET.get("diag"):
        from django.db import connection, transaction
        from django.contrib import admin
        data = {
            "status": "ok",
            "service": "sgmis-api",
            "last_server_error": _LAST_SERVER_ERROR,
        }
        try:
            with connection.cursor() as cur:
                # 1. Columns
                cur.execute("""
                    SELECT column_name, data_type, is_nullable, column_default 
                    FROM information_schema.columns 
                    WHERE table_name = 'accounts_user' 
                    ORDER BY ordinal_position;
                """)
                data["columns"] = [
                    {"name": r[0], "type": r[1], "nullable": r[2], "default": r[3]}
                    for r in cur.fetchall()
                ]

                # 2. Constraints
                cur.execute("""
                    SELECT conname, pg_get_constraintdef(c.oid) 
                    FROM pg_constraint c 
                    JOIN pg_namespace n ON n.oid = c.connamespace 
                    WHERE conrelid = 'accounts_user'::regclass;
                """)
                data["constraints"] = [
                    {"name": r[0], "def": r[1]} for r in cur.fetchall()
                ]

                # 3. Migrations
                cur.execute("""
                    SELECT app, name, applied 
                    FROM django_migrations 
                    WHERE app = 'accounts' 
                    ORDER BY applied;
                """)
                data["migrations"] = [
                    {"name": r[1], "applied": str(r[2])} for r in cur.fetchall()
                ]
        except Exception as db_e:
            data["db_error"] = str(db_e)

        # 4. Simulate Admin form creation in a dry-run rollback transaction
        try:
            from django.test import RequestFactory
            from django.contrib.sessions.middleware import SessionMiddleware
            from django.contrib.messages.storage.fallback import FallbackStorage

            sim = {}
            model_admin = admin.site._registry.get(User)
            sim["admin_class"] = model_admin.__class__.__name__
            sim["admin_bases"] = [b.__name__ for b in model_admin.__class__.__mro__]

            admin_user = User.objects.filter(is_superuser=True).first()
            rf = RequestFactory()

            # Test GET /admin/accounts/user/add/
            req_get = rf.get('/admin/accounts/user/add/')
            req_get.user = admin_user
            SessionMiddleware(lambda r: None).process_request(req_get)
            req_get.session.save()
            req_get._messages = FallbackStorage(req_get)

            FormClass = model_admin.get_form(req_get)
            sim["form_class"] = FormClass.__name__
            sim["form_fields"] = list(FormClass.base_fields.keys())

            try:
                resp_get = model_admin.add_view(req_get)
                if hasattr(resp_get, 'render'):
                    resp_get.render()
                sim["get_status"] = resp_get.status_code
            except Exception as get_e:
                sim["get_error"] = str(get_e)
                sim["get_traceback"] = traceback.format_exc()

            # Test POST /admin/accounts/user/add/
            req_post = rf.post('/admin/accounts/user/add/', data={
                "username": "diag_test_guard_xyz",
                "password": "TestPassword123!",
                "role": "GUARD",
                "rank": "Security Officer",
                "phone_number": "+263771234567",
                "address": "123 Test St",
                "is_active": "on",
                "_save": "Save",
            })
            req_post.user = admin_user
            req_post._dont_enforce_csrf_checks = True
            SessionMiddleware(lambda r: None).process_request(req_post)
            req_post.session.save()
            req_post._messages = FallbackStorage(req_post)

            try:
                with transaction.atomic():
                    resp_post = model_admin.add_view(req_post)
                    if hasattr(resp_post, 'render'):
                        resp_post.render()
                    sim["post_status"] = resp_post.status_code
                    if hasattr(resp_post, 'url'):
                        sim["post_redirect"] = resp_post.url
                    transaction.set_rollback(True)
            except Exception as post_e:
                sim["post_error"] = str(post_e)
                sim["post_traceback"] = traceback.format_exc()

            data["simulation"] = sim
        except Exception as sim_e:
            data["simulation_error"] = {
                "error": str(sim_e),
                "traceback": traceback.format_exc(),
            }

        return Response(data)

    return Response({
        "status": "ok",
        "service": "sgmis-api",
    })

@api_view(["GET"])
@permission_classes([IsAuthenticated])
def telemetry_overview(request):
    """
    Returns real-time operational telemetry across all security operations.
    Authoritative database metrics scoped appropriately to user role,
    with structured breakdowns for expandable dashboard cards.
    """
    user = request.user
    today = timezone.localdate()

    # Querysets scoped by user role & station assignment
    incident_qs = IncidentReport.objects.filter(is_archived=False)
    ob_qs = OccurrenceBookEntry.objects.filter(is_archived=False)
    patrol_qs = PatrolLog.objects.all()
    shift_qs = Shift.objects.all()
    escort_qs = EscortDuty.objects.all()
    attendance_qs = Attendance.objects.all()
    leave_qs = LeaveApplication.objects.all()

    if user.role == UserRole.GUARD:
        if user.station:
            incident_qs = incident_qs.filter(station=user.station)
            ob_qs = ob_qs.filter(station=user.station)
            shift_qs = shift_qs.filter(station=user.station)
        else:
            incident_qs = incident_qs.filter(reporting_guard=user)
            ob_qs = ob_qs.filter(guard=user)
            shift_qs = shift_qs.filter(guard=user)
        patrol_qs = patrol_qs.filter(guard=user)
        escort_qs = escort_qs.filter(guard=user)
        attendance_qs = attendance_qs.filter(guard=user)
        leave_qs = leave_qs.filter(guard=user)
    elif user.role == UserRole.SUPERVISOR and user.station:
        incident_qs = incident_qs.filter(station=user.station)
        ob_qs = ob_qs.filter(station=user.station)
        patrol_qs = patrol_qs.filter(station=user.station)
        shift_qs = shift_qs.filter(station=user.station)
        escort_qs = escort_qs.filter(guard__station=user.station)
        attendance_qs = attendance_qs.filter(shift__station=user.station)

    total_incidents = incident_qs.count()
    open_incidents = incident_qs.filter(status__in=[IncidentStatus.REPORTED, IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING]).count()
    critical_incidents = incident_qs.filter(priority=IncidentPriority.CRITICAL).count()

    total_ob = ob_qs.count()
    today_ob = ob_qs.filter(created_at__date=today).count()

    active_patrols = patrol_qs.filter(status=PatrolStatus.IN_PROGRESS).count()
    completed_patrols = patrol_qs.filter(status=PatrolStatus.COMPLETED).count()

    active_guards = User.objects.filter(role=UserRole.GUARD, is_active=True).count()
    total_stations = Station.objects.filter(is_active=True).count()

    active_escorts = escort_qs.filter(status__in=[EscortStatus.SCHEDULED, EscortStatus.EN_ROUTE]).count()
    today_shifts = shift_qs.filter(date=today).count()
    today_attendance = attendance_qs.filter(shift__date=today, clock_in__isnull=False).count()
    pending_leaves = leave_qs.filter(status=LeaveStatus.PENDING).count()

    # Detailed expandable breakdowns
    incident_breakdown = {
        "critical": critical_incidents,
        "high": incident_qs.filter(priority=IncidentPriority.HIGH).count(),
        "medium": incident_qs.filter(priority=IncidentPriority.MEDIUM).count(),
        "low": incident_qs.filter(priority=IncidentPriority.LOW).count(),
        "reported": incident_qs.filter(status=IncidentStatus.REPORTED).count(),
        "acknowledged": incident_qs.filter(status=IncidentStatus.ACKNOWLEDGED).count(),
        "investigating": incident_qs.filter(status=IncidentStatus.INVESTIGATING).count(),
        "resolved": incident_qs.filter(status=IncidentStatus.RESOLVED).count(),
    }

    patrol_breakdown = {
        "in_progress": active_patrols,
        "completed": completed_patrols,
    }

    attendance_breakdown = {
        "clocked_in": today_attendance,
        "clocked_out": attendance_qs.filter(shift__date=today, clock_out__isnull=False).count(),
        "late": attendance_qs.filter(shift__date=today, is_late=True).count(),
    }

    station_breakdown = [
        {
            "id": str(s.id),
            "name": s.name,
            "active_guards": s.assigned_guards.filter(is_active=True).count(),
            "today_shifts": s.shifts.filter(date=today).count(),
        }
        for s in Station.objects.filter(is_active=True)[:10]
    ]

    return Response({
        "total_incidents": total_incidents,
        "open_incidents": open_incidents,
        "critical_incidents": critical_incidents,
        "total_ob_entries": total_ob,
        "today_ob_entries": today_ob,
        "active_patrols": active_patrols,
        "completed_patrols": completed_patrols,
        "active_guards": active_guards,
        "total_stations": total_stations,
        "active_escorts": active_escorts,
        "today_shifts": today_shifts,
        "today_attendance": today_attendance,
        "pending_leaves": pending_leaves,
        "incident_breakdown": incident_breakdown,
        "patrol_breakdown": patrol_breakdown,
        "attendance_breakdown": attendance_breakdown,
        "station_breakdown": station_breakdown,
    })


from django.http import JsonResponse

def api_bad_request(request, exception=None):
    return JsonResponse({
        "detail": "Bad request.",
        "error": "HTTP_400",
        "status_code": 400,
        "path": request.path,
    }, status=400)

def api_permission_denied(request, exception=None):
    return JsonResponse({
        "detail": "Permission denied.",
        "error": "HTTP_403",
        "status_code": 403,
        "path": request.path,
    }, status=403)

def api_not_found(request, exception=None):
    return JsonResponse({
        "detail": f"Resource not found at {request.path}",
        "error": "HTTP_404",
        "status_code": 404,
        "path": request.path,
    }, status=404)

def api_server_error(request):
    global _LAST_SERVER_ERROR
    exc_type, exc_value, exc_tb = sys.exc_info()
    tb_str = "".join(traceback.format_exception(exc_type, exc_value, exc_tb)) if exc_type else "No active exception"
    _LAST_SERVER_ERROR = {
        "path": request.path,
        "method": request.method,
        "exception_type": str(exc_type),
        "exception_value": str(exc_value),
        "traceback": tb_str,
    }
    sys.stderr.write(f"\n=== CRITICAL 500 ERROR ON {request.path} ===\n{tb_str}\n")
    return JsonResponse({
        "detail": "An internal server error occurred.",
        "error": "HTTP_500",
        "status_code": 500,
        "path": request.path,
    }, status=500)


from decimal import Decimal
from django.db.models import Q, Max
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.exceptions import PermissionDenied
from apps.accounts.permissions import IsAdministrator, IsSupervisorOrAdmin
from apps.core.models import RecordAdjustmentRequest, AdjustmentStatus, SecurityAuditEvent
from apps.core.serializers import RecordAdjustmentRequestSerializer
from apps.notifications.models import Notification
from apps.leave.models import (
    LeaveBalance,
    AdjustmentType,
    LeaveAdjustmentRecord,
    PublicHolidayCompensationLedger,
    CompensationLedgerEntryType,
)


def apply_record_adjustment(adjustment, approved_value, actor):
    guard = adjustment.guard
    field = adjustment.field_name.strip().lower()
    effective_date = adjustment.effective_date
    reason = adjustment.reason or "Record adjustment"

    if field in ["employee_number", "employee_id"]:
        guard.employee_number = approved_value
        guard.save()
    elif field == "first_name":
        guard.first_name = approved_value
        guard.save()
    elif field == "last_name":
        guard.last_name = approved_value
        guard.save()
    elif field == "station":
        station = None
        try:
            station = Station.objects.filter(id=approved_value).first()
        except Exception:
            pass
        if not station:
            station = Station.objects.filter(name__iexact=approved_value).first()
        if station:
            guard.station = station
            guard.save()
            Shift.objects.filter(guard=guard, date__gte=effective_date).update(station=station)
    elif field in ["shift", "shift_type"]:
        norm = approved_value.strip().upper()
        if norm in ["DAY", "NIGHT"]:
            from datetime import time
            start = time(7, 0) if norm == "DAY" else time(18, 0)
            end = time(18, 0) if norm == "DAY" else time(7, 0)
            Shift.objects.filter(guard=guard, date__gte=effective_date).update(
                shift_type=norm, start_time=start, end_time=end
            )
    elif field in ["pair", "pair_guard"]:
        from apps.stations.models import GuardPair
        pair_guard = User.objects.filter(
            Q(username=approved_value) | Q(employee_number=approved_value) | Q(id=approved_value if len(approved_value) == 36 else None)
        ).first()
        if pair_guard:
            pair = GuardPair.objects.filter(
                (Q(guard_a=guard, guard_b=pair_guard) | Q(guard_a=pair_guard, guard_b=guard))
            ).first()
            if not pair:
                max_order = GuardPair.objects.filter(station=guard.station).aggregate(Max("rotation_order"))["rotation_order__max"] or 0
                pair = GuardPair.objects.create(guard_a=guard, guard_b=pair_guard, station=guard.station, rotation_order=max_order + 1)
            Shift.objects.filter(guard=guard, date__gte=effective_date).update(pair=pair)
    elif field in ["roster_position", "rotation_order"]:
        try:
            target_order = int(approved_value)
            if target_order in [1, 2, 3]:
                from apps.stations.models import GuardPair
                pair = GuardPair.objects.filter(
                    (Q(guard_a=guard) | Q(guard_b=guard)),
                    station=guard.station,
                    is_active=True
                ).first()
                if pair:
                    other_pair = GuardPair.objects.filter(
                        station=guard.station,
                        rotation_order=target_order,
                        is_active=True
                    ).exclude(id=pair.id).first()
                    if other_pair:
                        other_pair.rotation_order = pair.rotation_order
                        other_pair.save()
                    pair.rotation_order = target_order
                    pair.save()
        except (ValueError, TypeError):
            pass
    elif field == "vacation_balance":
        bal, _ = LeaveBalance.objects.get_or_create(guard=guard, year=effective_date.year)
        prev = bal.vacation_days
        val = Decimal(str(approved_value))
        bal.vacation_days = val
        bal.opening_vacation_balance = val
        bal.opening_balance_date = effective_date
        bal.opening_balance_source = reason
        bal.opening_balance_verified_by = actor
        bal.save()
        LeaveAdjustmentRecord.objects.create(
            guard=guard,
            adjustment_type=AdjustmentType.OPENING_BALANCE,
            leave_type="VACATION",
            previous_balance=prev,
            new_balance=val,
            effective_date=effective_date,
            source=reason,
            reason=reason,
            authorized_by=actor,
        )
    elif field == "casual_balance":
        bal, _ = LeaveBalance.objects.get_or_create(guard=guard, year=effective_date.year)
        prev = bal.casual_days
        val = Decimal(str(approved_value))
        bal.casual_days = val
        bal.opening_casual_balance = val
        bal.opening_balance_date = effective_date
        bal.opening_balance_source = reason
        bal.opening_balance_verified_by = actor
        bal.save()
        LeaveAdjustmentRecord.objects.create(
            guard=guard,
            adjustment_type=AdjustmentType.OPENING_BALANCE,
            leave_type="CASUAL",
            previous_balance=prev,
            new_balance=val,
            effective_date=effective_date,
            source=reason,
            reason=reason,
            authorized_by=actor,
        )
    elif field in ["annual_balance", "annual_days"]:
        bal, _ = LeaveBalance.objects.get_or_create(guard=guard, year=effective_date.year)
        prev = bal.annual_days
        val = int(approved_value)
        bal.annual_days = val
        bal.save()
        LeaveAdjustmentRecord.objects.create(
            guard=guard,
            adjustment_type=AdjustmentType.MANUAL_ADJUSTMENT,
            leave_type="ANNUAL",
            previous_balance=prev,
            new_balance=val,
            effective_date=effective_date,
            source=reason,
            reason=reason,
            authorized_by=actor,
        )
    elif field in ["sick_balance", "sick_days"]:
        bal, _ = LeaveBalance.objects.get_or_create(guard=guard, year=effective_date.year)
        prev = bal.sick_days
        val = int(approved_value)
        bal.sick_days = val
        bal.save()
        LeaveAdjustmentRecord.objects.create(
            guard=guard,
            adjustment_type=AdjustmentType.MANUAL_ADJUSTMENT,
            leave_type="SICK",
            previous_balance=prev,
            new_balance=val,
            effective_date=effective_date,
            source=reason,
            reason=reason,
            authorized_by=actor,
        )
    elif field == "compensation_days":
        val = Decimal(str(approved_value))
        rem = PublicHolidayCompensationLedger.get_remaining_for_guard(guard)
        diff = val - rem
        if diff != Decimal("0.0"):
            PublicHolidayCompensationLedger.objects.create(
                guard=guard,
                entry_type=CompensationLedgerEntryType.EARNED if diff > 0 else CompensationLedgerEntryType.USED,
                days=abs(diff),
                notes=f"Administrative reconciliation: {reason}",
                created_by=actor,
            )
    elif field in ["assignment_type", "duty_assignment"]:
        norm = approved_value.strip().upper()
        if norm in ["NORMAL", "EXAM", "ESCORT"]:
            Shift.objects.filter(guard=guard, date__gte=effective_date).update(assignment_type=norm)
    elif field in ["duty_state", "attendance_status"]:
        norm = approved_value.strip().upper()
        Shift.objects.filter(guard=guard, date=effective_date).update(attendance_status=norm)


class RecordAdjustmentRequestViewSet(viewsets.ModelViewSet):
    """
    Auditable Record Correction & Reconciliation Workflow.
    - Supervisors submit adjustment requests (PENDING review).
    - Superusers review, approve, reject, or modify proposed values.
    - Approved values apply to master records without mutating historical attendance or shifts.
    """
    queryset = RecordAdjustmentRequest.objects.all().select_related("guard", "requested_by", "reviewed_by")
    serializer_class = RecordAdjustmentRequestSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station_id:
                return qs.filter(Q(requested_by=user) | Q(guard__station_id=user.station_id))
            return qs.filter(requested_by=user)
        return qs

    def perform_create(self, serializer):
        user = self.request.user
        if user.role == UserRole.GUARD:
            raise PermissionDenied("Guards cannot submit record adjustments.")

        guard = serializer.validated_data["guard"]
        if user.role == UserRole.SUPERVISOR:
            if user.station_id and guard.station_id and user.station_id != guard.station_id:
                raise PermissionDenied("Supervisor cannot request adjustments for a guard outside assigned station.")

        field_name = serializer.validated_data["field_name"].strip().lower()
        old_val = serializer.validated_data.get("old_value", "")
        if not old_val:
            if field_name in ["employee_number", "employee_id"]:
                old_val = getattr(guard, "employee_number", "") or ""
            elif field_name == "first_name":
                old_val = guard.first_name or ""
            elif field_name == "last_name":
                old_val = guard.last_name or ""
            elif field_name == "station":
                old_val = guard.station.name if guard.station else ""
            elif field_name in ["shift", "shift_type", "pair", "pair_guard"]:
                upcoming = Shift.objects.filter(guard=guard, date__gte=serializer.validated_data["effective_date"]).order_by("date").first()
                if upcoming:
                    if field_name in ["shift", "shift_type"]:
                        old_val = upcoming.shift_type
                    elif upcoming.pair:
                        partner = upcoming.pair.get_partner_for(guard)
                        old_val = (partner.employee_number or partner.username) if partner else ""
            elif field_name in ["roster_position", "rotation_order"]:
                from apps.stations.models import GuardPair
                gp = GuardPair.objects.filter(
                    (Q(guard_a=guard) | Q(guard_b=guard)),
                    station=guard.station,
                    is_active=True
                ).first()
                old_val = str(gp.rotation_order) if gp else "1"
            elif field_name == "vacation_balance":
                b = LeaveBalance.objects.filter(guard=guard).first()
                old_val = str(b.vacation_days) if b else "0.0"
            elif field_name == "casual_balance":
                b = LeaveBalance.objects.filter(guard=guard).first()
                old_val = str(b.casual_days) if b else "0.0"
            elif field_name in ["annual_balance", "annual_days"]:
                b = LeaveBalance.objects.filter(guard=guard).first()
                old_val = str(b.annual_days) if b else "21"
            elif field_name in ["sick_balance", "sick_days"]:
                b = LeaveBalance.objects.filter(guard=guard).first()
                old_val = str(b.sick_days) if b else "14"
            elif field_name == "compensation_days":
                old_val = str(PublicHolidayCompensationLedger.get_remaining_for_guard(guard))
            elif field_name in ["assignment_type", "duty_assignment"]:
                upcoming = Shift.objects.filter(guard=guard, date__gte=serializer.validated_data["effective_date"]).order_by("date").first()
                old_val = upcoming.assignment_type if upcoming else "NORMAL"
            elif field_name in ["duty_state", "attendance_status"]:
                upcoming = Shift.objects.filter(guard=guard, date=serializer.validated_data["effective_date"]).first()
                old_val = upcoming.attendance_status if upcoming else "NOT_CLOCKED_IN"

        is_admin = user.role == UserRole.ADMINISTRATOR or user.is_superuser
        req_status = self.request.data.get("status")
        initial_status = (
            AdjustmentStatus.APPROVED
            if (is_admin and (req_status == "APPROVED" or req_status is None))
            else AdjustmentStatus.PENDING
        )

        req_obj = serializer.save(
            requested_by=user,
            old_value=old_val,
            status=initial_status,
            reviewed_by=user if initial_status == AdjustmentStatus.APPROVED else None,
            reviewed_at=timezone.now() if initial_status == AdjustmentStatus.APPROVED else None,
            approved_value=serializer.validated_data["requested_value"] if initial_status == AdjustmentStatus.APPROVED else "",
        )

        if initial_status == AdjustmentStatus.APPROVED:
            prev_guard_emp = getattr(guard, "employee_number", "") or ""
            apply_record_adjustment(req_obj, req_obj.requested_value, user)
            SecurityAuditEvent.objects.create(
                event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
                actor=user,
                actor_username=user.username,
                target_model="User",
                target_id=str(guard.id),
                details={
                    "adjustment_id": str(req_obj.id),
                    "field": field_name,
                    "old_value": old_val,
                    "new_value": req_obj.requested_value,
                    "reason": req_obj.reason,
                    "employee": guard.get_full_name() or guard.username,
                    "employee_number": prev_guard_emp,
                    "station": guard.station.name if guard.station else "",
                    "admin_employee_number": user.employee_number or "",
                    "action": "APPROVED_DIRECT",
                    "status": "APPROVED",
                }
            )
        else:
            admins = User.objects.filter(role=UserRole.ADMINISTRATOR, is_active=True)
            for adm in admins:
                Notification.objects.create(
                    user=adm,
                    title="Record Adjustment Request",
                    message=f"Supervisor {user.get_full_name() or user.username} submitted an adjustment request for {guard.get_full_name() or guard.username} ({field_name}).",
                    notification_type="OPERATIONAL_ALERT",
                )

    @action(detail=True, methods=["post"], url_path="approve", permission_classes=[IsAdministrator])
    def approve(self, request, pk=None):
        adjustment = self.get_object()
        if adjustment.status == AdjustmentStatus.APPROVED:
            return Response({"detail": "Adjustment request is already approved."}, status=status.HTTP_400_BAD_REQUEST)

        approved_value = request.data.get("approved_value") or adjustment.requested_value
        apply_record_adjustment(adjustment, approved_value, request.user)

        adjustment.approved_value = approved_value
        adjustment.status = AdjustmentStatus.APPROVED
        adjustment.reviewed_by = request.user
        adjustment.reviewed_at = timezone.now()
        adjustment.save()

        Notification.objects.create(
            user=adjustment.guard,
            title="Record Adjustment Approved",
            message=f"Your {adjustment.field_name} record has been reconciled and updated.",
            notification_type="OPERATIONAL_ALERT",
        )
        if adjustment.requested_by and adjustment.requested_by != request.user:
            Notification.objects.create(
                user=adjustment.requested_by,
                title="Record Adjustment Approved",
                message=f"Your adjustment request for {adjustment.guard.username} ({adjustment.field_name}) was approved.",
                notification_type="OPERATIONAL_ALERT",
            )

        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=request.user,
            actor_username=request.user.username,
            target_model="User",
            target_id=str(adjustment.guard.id),
            details={
                "adjustment_id": str(adjustment.id),
                "field": adjustment.field_name,
                "old_value": adjustment.old_value,
                "new_value": approved_value,
                "reason": adjustment.reason,
                "employee": adjustment.guard.get_full_name() or adjustment.guard.username,
                "employee_number": adjustment.guard.employee_number or "",
                "station": adjustment.guard.station.name if adjustment.guard.station else "",
                "admin_employee_number": request.user.employee_number or "",
                "action": "APPROVED",
                "status": "APPROVED",
            }
        )

        return Response({
            "message": f"Successfully approved adjustment for {adjustment.guard.username}.",
            "adjustment": RecordAdjustmentRequestSerializer(adjustment).data,
        }, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="reject", permission_classes=[IsAdministrator])
    def reject(self, request, pk=None):
        adjustment = self.get_object()
        if adjustment.status == AdjustmentStatus.REJECTED:
            return Response({"detail": "Adjustment request is already rejected."}, status=status.HTTP_400_BAD_REQUEST)

        rejection_reason = request.data.get("rejection_reason", "").strip() or "Rejected by administrator."
        adjustment.status = AdjustmentStatus.REJECTED
        adjustment.rejection_reason = rejection_reason
        adjustment.reviewed_by = request.user
        adjustment.reviewed_at = timezone.now()
        adjustment.save()

        if adjustment.requested_by:
            Notification.objects.create(
                user=adjustment.requested_by,
                title="Record Adjustment Rejected",
                message=f"Your adjustment request for {adjustment.guard.username} ({adjustment.field_name}) was rejected: {rejection_reason}.",
                notification_type="OPERATIONAL_ALERT",
            )

        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=request.user,
            actor_username=request.user.username,
            target_model="User",
            target_id=str(adjustment.guard.id),
            details={
                "adjustment_id": str(adjustment.id),
                "field": adjustment.field_name,
                "old_value": adjustment.old_value,
                "requested_value": adjustment.requested_value,
                "reason": adjustment.reason,
                "rejection_reason": rejection_reason,
                "employee": adjustment.guard.get_full_name() or adjustment.guard.username,
                "employee_number": adjustment.guard.employee_number or "",
                "station": adjustment.guard.station.name if adjustment.guard.station else "",
                "admin_employee_number": request.user.employee_number or "",
                "action": "REJECTED",
                "status": "REJECTED",
            }
        )

        return Response({
            "message": "Adjustment request rejected.",
            "adjustment": RecordAdjustmentRequestSerializer(adjustment).data,
        }, status=status.HTTP_200_OK)


@api_view(["GET"])
@permission_classes([IsAdministrator])
def administrative_history(request):
    """Read-only administrator history sourced from authoritative audit records."""
    entries = []
    
    # 1. Record Adjustment Requests
    for item in RecordAdjustmentRequest.objects.select_related(
        "guard", "guard__station", "requested_by", "reviewed_by"
    ).order_by("-created_at")[:300]:
        actor_user = item.reviewed_by if (item.status in ["APPROVED", "REJECTED"] and item.reviewed_by) else item.requested_by
        actor_emp = (actor_user.employee_number or "") if actor_user else ""
        actor_name = (actor_user.get_full_name() or actor_user.username) if actor_user else "System"
        actor_label = f"{actor_name} ({actor_emp})" if actor_emp else actor_name

        guard_name = item.guard.get_full_name() or item.guard.username
        guard_emp = item.guard.employee_number or ""
        station_name = item.guard.station.name if item.guard.station else ""

        entries.append({
            "id": str(item.id),
            "kind": "RECORD_ADJUSTMENT",
            "timestamp": (item.reviewed_at or item.created_at).isoformat(),
            "actor": actor_label,
            "target_model": "User",
            "target_id": str(item.guard_id),
            "action": f"{item.status} ({item.field_name})",
            "reason": item.rejection_reason or item.reason,
            "old_value": item.old_value,
            "new_value": item.approved_value if item.status == "APPROVED" else item.requested_value,
            "details": {
                "field": item.field_name,
                "employee": f"{guard_name} ({guard_emp})" if guard_emp else guard_name,
                "employee_number": guard_emp,
                "station": station_name,
                "requested_by": item.requested_by.get_full_name() if item.requested_by else "",
                "status": item.status,
                "request_id": str(item.id),
                "admin_employee_number": actor_emp,
            },
        })

    # 2. Leave Adjustment Records
    for item in LeaveAdjustmentRecord.objects.select_related(
        "guard", "guard__station", "authorized_by"
    ).order_by("-created_at")[:300]:
        actor_user = item.authorized_by
        actor_emp = (actor_user.employee_number or "") if actor_user else ""
        actor_name = (actor_user.get_full_name() or actor_user.username) if actor_user else "System"
        actor_label = f"{actor_name} ({actor_emp})" if actor_emp else actor_name

        guard_name = item.guard.get_full_name() or item.guard.username
        guard_emp = item.guard.employee_number or ""
        station_name = item.guard.station.name if item.guard.station else ""

        entries.append({
            "id": str(item.id),
            "kind": "LEAVE_ADJUSTMENT",
            "timestamp": item.created_at.isoformat(),
            "actor": actor_label,
            "target_model": "LeaveBalance",
            "target_id": str(item.guard_id),
            "action": f"{item.adjustment_type} ({item.leave_type})",
            "reason": item.reason,
            "old_value": str(item.previous_balance),
            "new_value": str(item.new_balance),
            "details": {
                "field": f"{item.leave_type.lower()}_balance",
                "employee": f"{guard_name} ({guard_emp})" if guard_emp else guard_name,
                "employee_number": guard_emp,
                "station": station_name,
                "leave_type": item.leave_type,
                "effective_date": item.effective_date.isoformat(),
                "source": item.source,
                "admin_employee_number": actor_emp,
            },
        })

    # 3. Security Audit Events
    for item in SecurityAuditEvent.objects.select_related("actor").order_by("-timestamp")[:300]:
        actor_user = item.actor
        actor_emp = (actor_user.employee_number or "") if actor_user else ""
        actor_name = item.actor_username or ((actor_user.get_full_name() or actor_user.username) if actor_user else "System")
        actor_label = f"{actor_name} ({actor_emp})" if actor_emp else actor_name

        details = dict(item.details or {})
        if actor_emp and "admin_employee_number" not in details:
            details["admin_employee_number"] = actor_emp

        entries.append({
            "id": str(item.id),
            "kind": "SECURITY_AUDIT",
            "timestamp": item.timestamp.isoformat(),
            "actor": actor_label,
            "target_model": item.target_model,
            "target_id": item.target_id,
            "action": details.get("action", item.event_type),
            "reason": details.get("reason") or details.get("rejection_reason", ""),
            "old_value": str(details.get("old_value")) if details.get("old_value") is not None else None,
            "new_value": str(details.get("new_value")) if details.get("new_value") is not None else None,
            "details": details,
        })

    # 4. Supervisor Override Audits
    for item in SupervisorOverrideAudit.objects.select_related(
        "supervisor", "supervisor__station"
    ).order_by("-created_at")[:300]:
        sup = item.supervisor
        sup_emp = (sup.employee_number or "") if sup else ""
        sup_name = (sup.get_full_name() or sup.username) if sup else "Supervisor"
        sup_label = f"{sup_name} ({sup_emp})" if sup_emp else sup_name

        station_name = sup.station.name if (sup and sup.station) else ""

        entries.append({
            "id": str(item.id),
            "kind": "SUPERVISOR_OVERRIDE",
            "timestamp": item.created_at.isoformat(),
            "actor": sup_label,
            "target_model": item.target_model,
            "target_id": item.target_id,
            "action": item.action_type,
            "reason": item.reason,
            "details": {
                "admin_notified": item.admin_notified,
                "station": station_name,
                "admin_employee_number": sup_emp,
            },
        })

    # 5. Public Holiday Compensation Ledger entries
    for item in PublicHolidayCompensationLedger.objects.filter(
        created_by__isnull=False
    ).select_related("guard", "guard__station", "created_by").order_by("-created_at")[:300]:
        actor_user = item.created_by
        actor_emp = (actor_user.employee_number or "") if actor_user else ""
        actor_name = (actor_user.get_full_name() or actor_user.username) if actor_user else "System"
        actor_label = f"{actor_name} ({actor_emp})" if actor_emp else actor_name

        guard_name = item.guard.get_full_name() or item.guard.username
        guard_emp = item.guard.employee_number or ""
        station_name = item.guard.station.name if item.guard.station else ""

        entries.append({
            "id": str(item.id),
            "kind": "COMPENSATION_LEDGER",
            "timestamp": item.created_at.isoformat(),
            "actor": actor_label,
            "target_model": "PublicHolidayCompensationLedger",
            "target_id": str(item.id),
            "action": f"COMPENSATION_{item.entry_type}",
            "reason": item.notes,
            "new_value": f"{item.days} days",
            "details": {
                "field": "compensation_days",
                "employee": f"{guard_name} ({guard_emp})" if guard_emp else guard_name,
                "employee_number": guard_emp,
                "station": station_name,
                "admin_employee_number": actor_emp,
            },
        })

    # 6. Guard Pair Reassignment Audits
    from apps.stations.models import GuardPairReassignmentAudit
    for item in GuardPairReassignmentAudit.objects.select_related(
        "station", "guard", "old_pair", "new_pair", "authorized_by"
    ).order_by("-created_at")[:300]:
        actor_user = item.authorized_by
        actor_emp = (actor_user.employee_number or "") if actor_user else ""
        actor_name = (actor_user.get_full_name() or actor_user.username) if actor_user else "Supervisor"
        actor_label = f"{actor_name} ({actor_emp})" if actor_emp else actor_name

        guard_name = item.guard.get_full_name() or item.guard.username
        guard_emp = item.guard.employee_number or ""
        station_name = item.station.name if item.station else ""

        entries.append({
            "id": str(item.id),
            "kind": "PAIR_REASSIGNMENT",
            "timestamp": item.created_at.isoformat(),
            "actor": actor_label,
            "target_model": "GuardPair",
            "target_id": str(item.new_pair_id or item.old_pair_id or item.id),
            "action": "PAIR_REASSIGNMENT",
            "reason": item.reason,
            "old_value": str(item.old_pair) if item.old_pair else "Unassigned",
            "new_value": str(item.new_pair) if item.new_pair else "Unassigned",
            "details": {
                "field": "guard_pair",
                "employee": f"{guard_name} ({guard_emp})" if guard_emp else guard_name,
                "employee_number": guard_emp,
                "station": station_name,
                "effective_date": item.effective_date.isoformat(),
                "admin_employee_number": actor_emp,
            },
        })

    # 7. Duty Overrides / Leave Interruptions
    from apps.shifts.models import DutyOverride
    for item in DutyOverride.objects.select_related(
        "station", "guard", "authorized_by", "original_leave"
    ).order_by("-created_at")[:300]:
        actor_user = item.authorized_by
        actor_emp = (actor_user.employee_number or "") if actor_user else ""
        actor_name = (actor_user.get_full_name() or actor_user.username) if actor_user else "Supervisor"
        actor_label = f"{actor_name} ({actor_emp})" if actor_emp else actor_name

        guard_name = item.guard.get_full_name() or item.guard.username
        guard_emp = item.guard.employee_number or ""
        station_name = item.station.name if item.station else ""

        entries.append({
            "id": str(item.id),
            "kind": "DUTY_OVERRIDE",
            "timestamp": item.created_at.isoformat(),
            "actor": actor_label,
            "target_model": "DutyOverride",
            "target_id": str(item.id),
            "action": f"{item.override_type} ({item.status})",
            "reason": item.reason,
            "old_value": "On Leave" if item.original_leave else "Scheduled Off",
            "new_value": f"{item.shift_type} Shift (Owed: {item.compensation_days_owed}d)",
            "details": {
                "field": "duty_override",
                "employee": f"{guard_name} ({guard_emp})" if guard_emp else guard_name,
                "employee_number": guard_emp,
                "station": station_name,
                "date": item.date.isoformat(),
                "override_type": item.override_type,
                "compensation_settled": item.compensation_settled,
                "admin_employee_number": actor_emp,
            },
        })

    # Optional server-side filtering
    search = request.query_params.get("search", "").strip().lower()
    kind = request.query_params.get("kind") or request.query_params.get("action_type")
    station_param = request.query_params.get("station", "").strip().lower()

    if search:
        entries = [
            e for e in entries
            if search in e.get("actor", "").lower()
            or search in e.get("action", "").lower()
            or search in e.get("reason", "").lower()
            or search in str(e.get("details", {})).lower()
        ]

    if kind:
        kind_clean = kind.strip().upper()
        entries = [e for e in entries if e.get("kind", "").upper() == kind_clean or kind_clean in e.get("action", "").upper()]

    if station_param:
        entries = [
            e for e in entries
            if station_param in str(e.get("details", {}).get("station", "")).lower()
        ]

    entries.sort(key=lambda row: row["timestamp"], reverse=True)
    return Response(entries[:500])


class OrganizationPolicyViewSet(viewsets.ModelViewSet):
    """
    Authoritative organization policies.
    Viewable by all authenticated personnel.
    Manageable by administrators only.
    """
    from .models import OrganizationPolicy
    from .serializers import OrganizationPolicySerializer
    queryset = OrganizationPolicy.objects.all()
    serializer_class = OrganizationPolicySerializer

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "destroy"]:
            return [IsAdministrator()]
        return [IsAuthenticated()]

    def get_queryset(self):
        from .models import OrganizationPolicy
        qs = OrganizationPolicy.objects.all()
        category = self.request.query_params.get("category")
        if category:
            qs = qs.filter(category=category.upper())
        search = self.request.query_params.get("search")
        if search:
            qs = qs.filter(
                models.Q(title__icontains=search) |
                models.Q(summary__icontains=search) |
                models.Q(content__icontains=search)
            )
        is_active = self.request.query_params.get("is_active")
        if is_active is not None:
            qs = qs.filter(is_active=is_active.lower() in ["true", "1"])
        return qs

    def perform_create(self, serializer):
        policy = serializer.save(updated_by=self.request.user)
        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=self.request.user,
            actor_username=self.request.user.username,
            target_model="OrganizationPolicy",
            target_id=str(policy.id),
            details={"action": "POLICY_CREATED", "title": policy.title, "category": policy.category},
        )

    def perform_update(self, serializer):
        policy = serializer.save(updated_by=self.request.user)
        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=self.request.user,
            actor_username=self.request.user.username,
            target_model="OrganizationPolicy",
            target_id=str(policy.id),
            details={"action": "POLICY_UPDATED", "title": policy.title, "category": policy.category, "version": policy.version},
        )


@api_view(["GET"])
@permission_classes([IsAuthenticated])
def supervisor_dashboard(request):
    """
    Station Command dashboard for station supervisors.
    Authoritative real-time KPIs and telemetry for the supervisor's assigned station.
    """
    user = request.user
    if user.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
        return Response({"detail": "Access restricted to supervisors and administrators."}, status=403)

    station = user.station
    if user.role == UserRole.ADMINISTRATOR:
        station_id = request.query_params.get("station")
        if station_id:
            station = Station.objects.filter(id=station_id).first()
        elif not station:
            station = Station.objects.filter(is_active=True).first()

    if not station:
        return Response({
            "station": None,
            "supervisor": {
                "id": str(user.id),
                "name": user.get_full_name() or user.username,
                "employee_number": user.employee_number or "",
                "role": user.role,
            },
            "operational_status": "NO_STATION_ASSIGNED",
            "guards_on_post": 0,
            "guards_available": 0,
            "guards_on_leave": 0,
            "active_patrols": 0,
            "open_incidents": 0,
            "today_pending_duties": 0,
            "attendance_rate": 0.0,
            "live_ops": [],
        })

    today = timezone.localdate()

    station_guards = User.objects.filter(station=station, role=UserRole.GUARD, is_active=True)

    active_shifts = Shift.objects.filter(
        station=station,
        date=today,
    ).exclude(shift_type="OFF")

    today_attendance = Attendance.objects.filter(
        shift__station=station,
        shift__date=today,
        clock_in__isnull=False,
    )
    clocked_in_guard_ids = set(today_attendance.filter(clock_out__isnull=True).values_list("guard_id", flat=True))
    guards_on_post = len(clocked_in_guard_ids)

    approved_leave_guard_ids = set(
        LeaveApplication.objects.filter(
            guard__station=station,
            status=LeaveStatus.APPROVED,
            start_date__lte=today,
            end_date__gte=today,
        ).values_list("guard_id", flat=True)
    )
    from apps.shifts.models import DutyOverride, DutyOverrideStatus
    active_override_guard_ids = set(
        DutyOverride.objects.filter(
            station=station,
            date=today,
            status=DutyOverrideStatus.ACTIVE,
        ).values_list("guard_id", flat=True)
    )
    approved_leave_guard_ids = approved_leave_guard_ids - active_override_guard_ids
    guards_on_leave = len(approved_leave_guard_ids)

    all_guard_ids = set(station_guards.values_list("id", flat=True))
    unavailable_ids = clocked_in_guard_ids | approved_leave_guard_ids
    guards_available = max(0, len(all_guard_ids - unavailable_ids))

    active_patrols = PatrolLog.objects.filter(
        station=station,
        status__in=[PatrolStatus.IN_PROGRESS, PatrolStatus.ACTIVE],
    ).count()

    open_incidents = IncidentReport.objects.filter(
        station=station,
        is_archived=False,
        status__in=[IncidentStatus.REPORTED, IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING],
    ).count()

    scheduled_shift_count = active_shifts.count()
    clocked_in_count = today_attendance.count()
    today_pending_duties = max(0, scheduled_shift_count - clocked_in_count)

    attendance_rate = round((clocked_in_count / scheduled_shift_count * 100.0), 1) if scheduled_shift_count > 0 else 100.0

    live_ops = []
    for p in PatrolLog.objects.filter(station=station).order_by("-start_time")[:3]:
        live_ops.append({
            "id": str(p.id),
            "type": "PATROL",
            "title": f"Patrol {p.name}",
            "status": p.status,
            "guard_name": p.guard.get_full_name() or p.guard.username,
            "timestamp": p.start_time.isoformat() if p.start_time else "",
        })
    for inc in IncidentReport.objects.filter(station=station, is_archived=False).order_by("-created_at")[:3]:
        live_ops.append({
            "id": str(inc.id),
            "type": "INCIDENT",
            "title": inc.title,
            "status": inc.status,
            "guard_name": inc.reporting_guard.get_full_name() if inc.reporting_guard else "Station",
            "timestamp": inc.created_at.isoformat(),
        })

    return Response({
        "station": {
            "id": str(station.id),
            "name": station.name,
            "code": station.code,
            "latitude": station.latitude,
            "longitude": station.longitude,
            "geofence_radius_meters": station.geofence_radius_meters,
        },
        "supervisor": {
            "id": str(user.id),
            "name": user.get_full_name() or user.username,
            "employee_number": user.employee_number or "",
            "role": user.role,
        },
        "operational_status": "NORMAL" if open_incidents == 0 else "ATTENTION_REQUIRED",
        "guards_on_post": guards_on_post,
        "guards_available": guards_available,
        "guards_on_leave": guards_on_leave,
        "active_patrols": active_patrols,
        "open_incidents": open_incidents,
        "today_pending_duties": today_pending_duties,
        "attendance_rate": attendance_rate,
        "live_ops": live_ops,
    })


@api_view(["GET"])
@permission_classes([IsAdministrator])
def admin_dashboard(request):
    """
    National Command Center dashboard for Superusers / Administrators.
    Authoritative countrywide KPIs and operational telemetry.
    """
    user = request.user
    today = timezone.localdate()

    total_guards = User.objects.filter(role=UserRole.GUARD, is_active=True).count()
    total_stations = Station.objects.filter(is_active=True).count()

    active_attendance = Attendance.objects.filter(
        shift__date=today,
        clock_in__isnull=False,
        clock_out__isnull=True,
    )
    active_duties = active_attendance.count()

    active_patrols = PatrolLog.objects.filter(
        status__in=[PatrolStatus.IN_PROGRESS, PatrolStatus.ACTIVE],
    ).count()

    open_incidents = IncidentReport.objects.filter(
        is_archived=False,
        status__in=[IncidentStatus.REPORTED, IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING],
    ).count()

    critical_incidents = IncidentReport.objects.filter(
        is_archived=False,
        priority=IncidentPriority.CRITICAL,
        status__in=[IncidentStatus.REPORTED, IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING],
    ).count()

    guards_on_leave = LeaveApplication.objects.filter(
        status=LeaveStatus.APPROVED,
        start_date__lte=today,
        end_date__gte=today,
    ).exclude(
        guard__duty_overrides__date=today,
        guard__duty_overrides__status="ACTIVE",
    ).values("guard_id").distinct().count()

    scheduled_today = Shift.objects.filter(date=today).exclude(shift_type="OFF").count()
    attended_today = Attendance.objects.filter(shift__date=today, clock_in__isnull=False).count()
    attendance_rate = round((attended_today / scheduled_today * 100.0), 1) if scheduled_today > 0 else 100.0

    live_ops = []
    for inc in IncidentReport.objects.filter(is_archived=False).order_by("-created_at")[:5]:
        live_ops.append({
            "id": str(inc.id),
            "type": "INCIDENT",
            "title": f"[{inc.station.name if inc.station else 'HQ'}] {inc.title}",
            "status": inc.status,
            "priority": inc.priority,
            "timestamp": inc.created_at.isoformat(),
        })
    for p in PatrolLog.objects.filter(status__in=[PatrolStatus.IN_PROGRESS, PatrolStatus.ACTIVE]).order_by("-start_time")[:5]:
        live_ops.append({
            "id": str(p.id),
            "type": "PATROL",
            "title": f"[{p.station.name}] {p.name}",
            "status": p.status,
            "guard_name": p.guard.get_full_name() or p.guard.username,
            "timestamp": p.start_time.isoformat() if p.start_time else "",
        })

    return Response({
        "administrator": {
            "id": str(user.id),
            "name": user.get_full_name() or user.username,
            "employee_number": user.employee_number or "",
            "role": user.role,
        },
        "system_status": "NORMAL" if critical_incidents == 0 else "ATTENTION_REQUIRED",
        "total_guards": total_guards,
        "total_stations": total_stations,
        "active_duties": active_duties,
        "active_patrols": active_patrols,
        "open_incidents": open_incidents,
        "critical_incidents": critical_incidents,
        "guards_on_leave": guards_on_leave,
        "attendance_rate": attendance_rate,
        "live_ops": live_ops,
    })


