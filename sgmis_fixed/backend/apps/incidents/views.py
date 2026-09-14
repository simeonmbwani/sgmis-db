from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import IncidentReport, IncidentStatus
from .serializers import IncidentReportSerializer
from apps.accounts.models import UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin

class IncidentReportViewSet(viewsets.ModelViewSet):
    queryset = IncidentReport.objects.all().select_related("station", "reporting_guard", "acknowledged_by")
    serializer_class = IncidentReportSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        station_id = self.request.query_params.get("station")
        priority = self.request.query_params.get("priority")
        incident_status = self.request.query_params.get("status")

        if station_id:
            qs = qs.filter(station_id=station_id)
        if priority:
            qs = qs.filter(priority=priority)
        if incident_status:
            qs = qs.filter(status=incident_status)

        if user.role == UserRole.GUARD:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.filter(reporting_guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station:
            qs = qs.filter(station=user.station)

        return qs

    def perform_create(self, serializer):
        user = self.request.user
        if user.role == UserRole.GUARD and not user.station:
            from rest_framework.exceptions import PermissionDenied
            raise PermissionDenied("Your account has no station assigned. Contact your supervisor or administrator.")

        station = serializer.validated_data.get("station") or user.station
        if not station:
            from rest_framework.exceptions import ValidationError
            raise ValidationError({"station": "Your account has no station assigned. Contact your supervisor or administrator."})

        serializer.save(reporting_guard=user, station=station)

    @action(detail=True, methods=["post"], url_path="acknowledge", permission_classes=[IsSupervisorOrAdmin])
    def acknowledge(self, request, pk=None):
        incident = self.get_object()
        incident.status = IncidentStatus.ACKNOWLEDGED
        incident.acknowledged_by = request.user
        incident.save()
        return Response(self.get_serializer(incident).data)

    @action(detail=True, methods=["post"], url_path="resolve", permission_classes=[IsSupervisorOrAdmin])
    def resolve(self, request, pk=None):
        incident = self.get_object()
        notes = request.data.get("resolution_notes", "").strip()
        incident.status = IncidentStatus.RESOLVED
        incident.resolution_notes = notes
        incident.save()
        return Response(self.get_serializer(incident).data)
