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
            Shift.objects.filter(guard=guard, date__gte=effective_date).update(shift_type=norm)
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
            elif field_name == "vacation_balance":
                b = LeaveBalance.objects.filter(guard=guard).first()
                old_val = str(b.vacation_days) if b else "0.0"
            elif field_name == "casual_balance":
                b = LeaveBalance.objects.filter(guard=guard).first()
                old_val = str(b.casual_days) if b else "0.0"
            elif field_name == "compensation_days":
                old_val = str(PublicHolidayCompensationLedger.get_remaining_for_guard(guard))

        is_admin = user.role == UserRole.ADMINISTRATOR or user.is_superuser
        initial_status = (
            AdjustmentStatus.APPROVED
            if (is_admin and self.request.data.get("status") == "APPROVED")
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
            apply_record_adjustment(req_obj, req_obj.requested_value, user)
            SecurityAuditEvent.objects.create(
                event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
                actor=user,
                actor_username=user.username,
                target_model="User",
                target_id=str(guard.id),
                details={
                    "field": field_name,
                    "old_value": old_val,
                    "new_value": req_obj.requested_value,
                    "status": "APPROVED_DIRECT",
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
                "action": "APPROVED",
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
                "rejection_reason": rejection_reason,
                "action": "REJECTED",
            }
        )

        return Response({
            "message": "Adjustment request rejected.",
            "adjustment": RecordAdjustmentRequestSerializer(adjustment).data,
        }, status=status.HTTP_200_OK)

