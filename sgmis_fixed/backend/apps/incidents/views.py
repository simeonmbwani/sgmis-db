from django.utils import timezone
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.exceptions import PermissionDenied, ValidationError
from .models import IncidentReport, IncidentStatus, IncidentPriority, IncidentAmendment
from .serializers import IncidentReportSerializer, IncidentAmendmentSerializer
from apps.accounts.models import User, UserRole
from apps.accounts.views import get_client_ip
from apps.accounts.permissions import IsAdministrator, IsSupervisorOrAdmin
from apps.shifts.models import Shift
from apps.notifications.models import Notification
from apps.core.models import SecurityAuditEvent
from apps.core.audit import log_security_event
from apps.core.idempotency import check_idempotency, store_idempotency

SEVERITY_WEIGHT = {
    IncidentPriority.LOW: 1,
    IncidentPriority.MEDIUM: 2,
    IncidentPriority.HIGH: 3,
    IncidentPriority.CRITICAL: 4,
}

class IncidentReportViewSet(viewsets.ModelViewSet):
    queryset = IncidentReport.objects.all().select_related("station", "reporting_guard", "acknowledged_by", "assigned_to").prefetch_related("amendments")
    serializer_class = IncidentReportSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        priority = self.request.query_params.get("priority")
        incident_status = self.request.query_params.get("status")
        show_archived = self.request.query_params.get("archived")

        if show_archived is None or show_archived.lower() not in ("true", "1"):
            qs = qs.filter(is_archived=False)

        if priority:
            qs = qs.filter(priority=priority)
        if incident_status:
            qs = qs.filter(status=incident_status)

        # Strictly enforce role and station scoping
        if user.role == UserRole.GUARD:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.filter(reporting_guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            station_id = self.request.query_params.get("station")
            if station_id:
                qs = qs.filter(station_id=station_id)
            guard_id = self.request.query_params.get("guard")
            if guard_id:
                qs = qs.filter(reporting_guard_id=guard_id)

        return qs

    def create(self, request, *args, **kwargs):
        cached = check_idempotency(request)
        if cached:
            return cached
        response = super().create(request, *args, **kwargs)
        store_idempotency(request, response)
        return response

    def perform_create(self, serializer):
        user = self.request.user
        # Zero Proxy Actions check
        target_guard = self.request.data.get("reporting_guard") or self.request.data.get("guard")
        if target_guard and str(target_guard) != str(user.id):
            raise PermissionDenied("Proxy actions are strictly prohibited. You cannot submit incident reports for another guard.")

        if user.role == UserRole.SUPERVISOR:
            raise PermissionDenied("Supervisors have view-only access to incident submissions. Only on-duty guards may file incident reports.")

        if user.role != UserRole.GUARD:
            raise PermissionDenied("Administrators have incident oversight access; only guards may submit operational incident reports.")

        if user.role == UserRole.GUARD and not user.station:
            raise PermissionDenied("Your account has no station assigned. Contact your supervisor or administrator.")

        station = serializer.validated_data.get("station") or user.station
        if not station:
            raise ValidationError({"station": "Your account has no station assigned. Contact your supervisor or administrator."})

        serializer.save(reporting_guard=user, station=station)

    def update(self, request, *args, **kwargs):
        user = request.user
        if user.role == UserRole.GUARD:
            raise PermissionDenied("Guards cannot modify submitted incidents. Submit an amendment via /amend/ instead.")

        immutable_fields = {"description", "title", "station", "reporting_guard"}
        if any(field in request.data for field in immutable_fields):
            raise PermissionDenied("Evidence text (description, title) and station details are strictly immutable. Submit an amendment via /amend/ instead.")

        incident = self.get_object()
        new_priority = request.data.get("priority")
        if new_priority and new_priority in SEVERITY_WEIGHT:
            current_weight = SEVERITY_WEIGHT.get(incident.priority, 1)
            new_weight = SEVERITY_WEIGHT.get(new_priority, 1)
            if new_weight < current_weight:
                raise ValidationError({"priority": "Incident severity downgrade is strictly prohibited under security audit policy."})
        return super().update(request, *args, **kwargs)

    def partial_update(self, request, *args, **kwargs):
        user = request.user
        if user.role == UserRole.GUARD:
            raise PermissionDenied("Guards cannot modify submitted incidents. Submit an amendment via /amend/ instead.")

        immutable_fields = {"description", "title", "station", "reporting_guard"}
        if any(field in request.data for field in immutable_fields):
            raise PermissionDenied("Evidence text (description, title) and station details are strictly immutable. Submit an amendment via /amend/ instead.")

        incident = self.get_object()
        new_priority = request.data.get("priority")
        if new_priority and new_priority in SEVERITY_WEIGHT:
            current_weight = SEVERITY_WEIGHT.get(incident.priority, 1)
            new_weight = SEVERITY_WEIGHT.get(new_priority, 1)
            if new_weight < current_weight:
                raise ValidationError({"priority": "Incident severity downgrade is strictly prohibited under security audit policy."})
        return super().partial_update(request, *args, **kwargs)

    def destroy(self, request, *args, **kwargs):
        raise PermissionDenied("Deletion of evidence-grade incident reports is strictly prohibited. Use archival instead.")

    @action(detail=True, methods=["post"], url_path="amend")
    def amend(self, request, pk=None):
        incident = self.get_object()
        user = request.user

        # Boundary check: Guards & Supervisors can only amend entries belonging to their station
        if user.role in (UserRole.GUARD, UserRole.SUPERVISOR):
            if not user.station or incident.station_id != user.station_id:
                raise PermissionDenied("You can only amend records from your assigned station.")

        reason = str(request.data.get("reason", "")).strip()
        amended_description = str(request.data.get("amended_description", "")).strip()

        if not reason:
            raise ValidationError({"reason": "A mandatory reason is required to amend an incident record."})
        if not amended_description:
            raise ValidationError({"amended_description": "Amended description content cannot be blank."})

        amendment = IncidentAmendment.objects.create(
            incident=incident,
            amended_by=user,
            reason=reason,
            original_description_snapshot=incident.description,
            amended_description=amended_description,
        )

        log_security_event(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=user,
            actor_username=user.username,
            ip_address=get_client_ip(request),
            target_model="IncidentReport",
            target_id=str(incident.id),
            details={"amendment_id": str(amendment.id), "reason": reason}
        )

        return Response({
            "message": "Incident amendment recorded successfully.",
            "amendment": IncidentAmendmentSerializer(amendment).data
        }, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=["post"], url_path="acknowledge", permission_classes=[IsSupervisorOrAdmin])
    def acknowledge(self, request, pk=None):
        incident = self.get_object()
        incident.status = IncidentStatus.ACKNOWLEDGED
        incident.acknowledged_by = request.user
        incident.save()
        return Response(self.get_serializer(incident).data)

    @action(detail=True, methods=["post"], url_path="assign", permission_classes=[IsSupervisorOrAdmin])
    def assign(self, request, pk=None):
        incident = self.get_object()
        assigned_user_id = request.data.get("assigned_to")
        if not assigned_user_id:
            return Response({"detail": "assigned_to user ID is required."}, status=status.HTTP_400_BAD_REQUEST)

        assigned_user = User.objects.filter(id=assigned_user_id).first()
        if not assigned_user:
            return Response({"detail": "Target personnel not found."}, status=status.HTTP_404_NOT_FOUND)

        incident.assigned_to = assigned_user
        incident.status = IncidentStatus.INVESTIGATING
        incident.save()

        Notification.objects.create(
            user=assigned_user,
            title=f"Incident Assigned: {incident.title}",
            message=f"You have been assigned to investigate incident at {incident.station.name}: {incident.description[:100]}",
            notification_type="ALERT",
        )

        return Response(self.get_serializer(incident).data)

    @action(detail=True, methods=["post"], url_path="escalate", permission_classes=[IsSupervisorOrAdmin])
    def escalate(self, request, pk=None):
        incident = self.get_object()
        incident.escalated_to_admin = True
        incident.save(update_fields=["escalated_to_admin"])

        admins = User.objects.filter(role=UserRole.ADMINISTRATOR, is_active=True)
        for admin in admins:
            Notification.objects.create(
                user=admin,
                title=f"ESCALATION: {incident.title} [{incident.priority}]",
                message=f"Supervisor {request.user.username} has escalated incident at {incident.station.name} to Administration.",
                notification_type="EMERGENCY",
            )

        return Response({
            "message": "Incident successfully escalated to Administration.",
            "incident": self.get_serializer(incident).data
        }, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="resolve", permission_classes=[IsSupervisorOrAdmin])
    def resolve(self, request, pk=None):
        """
        Administrators have global incident resolution authority.
        Station Supervisors have operational incident resolution authority for their assigned station.
        Guards cannot resolve incidents.
        """
        incident = self.get_object()
        user = request.user

        if user.role == UserRole.SUPERVISOR:
            if not user.station or incident.station_id != user.station_id:
                raise PermissionDenied("Station isolation: You can only resolve incidents belonging to your assigned station.")

        notes = request.data.get("resolution_notes", "").strip()
        incident.status = IncidentStatus.RESOLVED
        incident.resolution_notes = notes
        incident.save()
        return Response(self.get_serializer(incident).data)

    @action(detail=True, methods=["post"], url_path="archive", permission_classes=[IsAdministrator])
    def archive(self, request, pk=None):
        incident = self.get_object()
        incident.is_archived = True
        incident.save(update_fields=["is_archived"])
        return Response({
            "message": f"Incident {incident.title} has been archived.",
            "is_archived": True,
        }, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="sos")
    def trigger_sos(self, request):
        """
        POST /incidents/reports/sos/ (and /incidents/sos/)
        Real SOS Distress Beacon activation:
        - Confirmed emergency action by authenticated officer.
        - Records critical emergency incident.
        - Captures guard, employee number, station, GPS coordinates, timestamp, category.
        - Sends immediate high-priority emergency notifications to Station Supervisors & Central Administration.
        """
        user = request.user
        category = request.data.get("category", "General Officer Distress").strip()
        gps = request.data.get("gps", "").strip() or request.data.get("gps_coords", "").strip()
        latitude = request.data.get("latitude")
        longitude = request.data.get("longitude")
        if not gps and latitude is not None and longitude is not None:
            gps = f"{latitude},{longitude}"

        station = user.station
        if not station:
            today_shift = Shift.objects.filter(guard=user, date=timezone.localdate()).select_related("station").first()
            if today_shift:
                station = today_shift.station

        if not station:
            raise ValidationError({"station": "Your account has no station assigned to broadcast an SOS distress alert."})

        now_dt = timezone.now()
        emp_num = getattr(user, "employee_number", "N/A") or "N/A"
        guard_name = user.get_full_name().strip() or user.username

        title = f"EMERGENCY SOS: {category} - {guard_name}"
        location_str = f"{station.name} (GPS: {gps})" if gps else station.name
        description = (
            f"CRITICAL SOS EMERGENCY BEACON TRIGGERED.\n"
            f"Officer: {guard_name} (Employee ID: {emp_num}, Username: {user.username}).\n"
            f"Station: {station.name}.\n"
            f"Category: {category}.\n"
            f"Coordinates: {gps or 'No GPS Fix'}.\n"
            f"Timestamp: {now_dt.strftime('%Y-%m-%d %H:%M:%S UTC')}.\n"
            f"Priority 1 Dispatch Alert."
        )

        incident = IncidentReport.objects.create(
            station=station,
            reporting_guard=user,
            priority=IncidentPriority.CRITICAL,
            title=title,
            description=description,
            location=location_str,
            status=IncidentStatus.REPORTED,
        )

        supervisors = list(User.objects.filter(role=UserRole.SUPERVISOR, station=station, is_active=True))
        admins = list(User.objects.filter(role=UserRole.ADMINISTRATOR, is_active=True))
        recipients = {u.id: u for u in (supervisors + admins)}

        for r in recipients.values():
            Notification.objects.create(
                user=r,
                title=f"🚨 CRITICAL SOS DISTRESS BEACON: {station.name} - {guard_name}",
                message=f"Officer {guard_name} triggered Emergency SOS at {station.name}. Category: {category}. Coordinates: {gps}. Immediate response required.",
                notification_type="EMERGENCY_SOS",
            )

        log_security_event(
            event_type="SOS_BEACON_ACTIVATION",
            actor=user,
            actor_username=user.username,
            ip_address=get_client_ip(request),
            target_model="IncidentReport",
            target_id=str(incident.id),
            details={
                "category": category,
                "station": station.name,
                "gps": gps,
                "timestamp": now_dt.isoformat(),
            }
        )

        return Response({
            "status": "SOS_DISPATCHED",
            "sos_dispatched": True,
            "incident_id": str(incident.id),
            "title": incident.title,
            "station": station.name,
            "officer": guard_name,
            "employee_number": emp_num,
            "timestamp": now_dt.isoformat(),
            "message": "Emergency SOS distress alarm dispatched to station supervisors and administration.",
        }, status=status.HTTP_201_CREATED)

