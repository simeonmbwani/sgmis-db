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

