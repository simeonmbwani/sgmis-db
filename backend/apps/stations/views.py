from rest_framework import viewsets
from rest_framework.permissions import IsAuthenticated
from .models import Station, GuardPair
from .serializers import StationSerializer, GuardPairSerializer
from apps.accounts.permissions import IsSupervisorOrAdmin, IsAdministrator

class StationViewSet(viewsets.ModelViewSet):
    """
    CRUD for Stations.
    Authenticated guards can view stations.
    Supervisors and Administrators can manage stations.
    """
    queryset = Station.objects.all()
    serializer_class = StationSerializer

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

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "destroy"]:
            return [IsSupervisorOrAdmin()]
        return [IsAuthenticated()]

    def get_queryset(self):
        qs = super().get_queryset()
        station_id = self.request.query_params.get("station")
        if station_id:
            qs = qs.filter(station_id=station_id)
        return qs
