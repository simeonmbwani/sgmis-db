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
    Authoritative database metrics scoped appropriately to user role.
    """
    user = request.user
    today = timezone.localdate()

    # Querysets scoped by user role & station assignment
    incident_qs = IncidentReport.objects.all()
    ob_qs = OccurrenceBookEntry.objects.all()
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
    open_incidents = incident_qs.filter(status__in=[IncidentStatus.REPORTED, IncidentStatus.ACKNOWLEDGED]).count()
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
    })

