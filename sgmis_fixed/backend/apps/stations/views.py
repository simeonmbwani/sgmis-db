from rest_framework import viewsets
from rest_framework.permissions import IsAuthenticated
from .models import Station, GuardPair
from .serializers import StationSerializer, GuardPairSerializer
from apps.accounts.permissions import IsAdministrator
from apps.core.models import SecurityAuditEvent

class StationViewSet(viewsets.ModelViewSet):
    """
    CRUD for Stations.
    Authenticated guards can view stations.
    Supervisors and Administrators can manage stations.
    """
    queryset = Station.objects.all()
    serializer_class = StationSerializer

    def perform_create(self, serializer):
        station = serializer.save()
        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=self.request.user, actor_username=self.request.user.username,
            target_model="Station", target_id=str(station.id),
            details={"action": "STATION_CREATED", "name": station.name, "code": station.code},
        )

    def perform_update(self, serializer):
        station = serializer.save()
        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=self.request.user, actor_username=self.request.user.username,
            target_model="Station", target_id=str(station.id),
            details={"action": "STATION_UPDATED", "changed_fields": {key: str(value) for key, value in serializer.validated_data.items()}},
        )

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "destroy"]:
            return [IsAdministrator()]
        return [IsAuthenticated()]

    def get_queryset(self):
        qs = super().get_queryset()
        active = self.request.query_params.get("is_active")
        if active is not None:
            qs = qs.filter(is_active=active.lower() in ("true", "1"))
        return qs

class GuardPairViewSet(viewsets.ModelViewSet):
    """
    CRUD for Guard Pairs.
    """
    queryset = GuardPair.objects.all().select_related("station", "guard_a", "guard_b")
    serializer_class = GuardPairSerializer

    def perform_create(self, serializer):
        pair = serializer.save()
        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=self.request.user, actor_username=self.request.user.username,
            target_model="GuardPair", target_id=str(pair.id),
            details={"action": "GUARD_PAIR_CREATED", "station_id": str(pair.station_id),
                     "guard_a_id": str(pair.guard_a_id), "guard_b_id": str(pair.guard_b_id)},
        )

    def perform_update(self, serializer):
        pair = serializer.save()
        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=self.request.user, actor_username=self.request.user.username,
            target_model="GuardPair", target_id=str(pair.id),
            details={"action": "GUARD_PAIR_UPDATED", "changed_fields": {key: str(value) for key, value in serializer.validated_data.items()}},
        )

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "destroy"]:
            return [IsAdministrator()]
        return [IsAuthenticated()]

    def get_queryset(self):
        qs = super().get_queryset()
        station_id = self.request.query_params.get("station")
        if station_id:
            qs = qs.filter(station_id=station_id)
        return qs
