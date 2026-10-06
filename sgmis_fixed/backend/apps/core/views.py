from django.utils import timezone
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

@api_view(["GET"])
@permission_classes([AllowAny])
def health_check(request):
    """
    Service health check endpoint.
    Safe for load balancers and container orchestrators.
    """
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

    entries.sort(key=lambda row: row["timestamp"], reverse=True)
    return Response(entries[:500])

