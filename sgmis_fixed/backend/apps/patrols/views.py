from django.utils import timezone
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import Checkpoint, PatrolLog, CheckpointScan, PatrolStatus
from .serializers import CheckpointSerializer, PatrolLogSerializer, CheckpointScanSerializer
from apps.accounts.models import UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin

class CheckpointViewSet(viewsets.ModelViewSet):
    queryset = Checkpoint.objects.all().select_related("station")
    serializer_class = CheckpointSerializer
    permission_classes = [IsAuthenticated]

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

class PatrolLogViewSet(viewsets.ModelViewSet):
    queryset = PatrolLog.objects.all().select_related("guard", "station").prefetch_related("scans", "scans__checkpoint")
    serializer_class = PatrolLogSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station:
            return qs.filter(station=user.station)
        return qs

    def perform_create(self, serializer):
        serializer.save(guard=self.request.user)

    @action(detail=True, methods=["post"], url_path="scan")
    def scan_checkpoint(self, request, pk=None):
        patrol = self.get_object()
        if patrol.status == PatrolStatus.COMPLETED:
            return Response(
                {"detail": "Cannot scan checkpoint: Patrol is already marked as completed."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        checkpoint_id = request.data.get("checkpoint")
        if not checkpoint_id:
            return Response({"detail": "checkpoint ID is required."}, status=status.HTTP_400_BAD_REQUEST)

        gps_coords = request.data.get("gps_coords", "")
        notes = request.data.get("notes", "Checkpoint verified secure.")

        scan = CheckpointScan.objects.create(
            patrol_log=patrol,
            checkpoint_id=checkpoint_id,
            gps_coords=gps_coords,
            notes=notes,
        )
        return Response(CheckpointScanSerializer(scan).data, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=["post"], url_path="finish")
    def finish_patrol(self, request, pk=None):
        patrol = self.get_object()
        if patrol.status == PatrolStatus.COMPLETED:
            return Response({"detail": "Patrol is already completed."}, status=status.HTTP_400_BAD_REQUEST)

        patrol.status = PatrolStatus.COMPLETED
        patrol.end_time = timezone.now()
        notes = request.data.get("notes")
        if notes:
            patrol.notes = notes
        patrol.save()

        return Response(self.get_serializer(patrol).data, status=status.HTTP_200_OK)
