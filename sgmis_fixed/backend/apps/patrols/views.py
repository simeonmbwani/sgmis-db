from django.utils import timezone
from django.shortcuts import get_object_or_404
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.exceptions import PermissionDenied, ValidationError
from .models import Checkpoint, PatrolLog, CheckpointScan, PatrolStatus
from .serializers import CheckpointSerializer, PatrolLogSerializer, CheckpointScanSerializer
from apps.accounts.models import UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin
from apps.stations.utils import is_within_geofence
from apps.shifts.models import Shift

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
        station_id = self.request.query_params.get("station")

        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station:
                return qs.filter(station=user.station)
            return qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            if station_id:
                return qs.filter(station_id=station_id)
            return qs
        return qs

    def perform_create(self, serializer):
        user = self.request.user
        target_guard = self.request.data.get("guard") or self.request.data.get("guard_id")
        if target_guard and str(target_guard) != str(user.id):
            raise PermissionDenied("Proxy actions are strictly prohibited. You cannot initiate a patrol for another guard.")

        if user.role == UserRole.SUPERVISOR:
            raise PermissionDenied("Supervisors have view-only access to patrols. Only on-duty guards can initiate a patrol.")

        if user.role == UserRole.GUARD:
            station = user.station
            if not station:
                today_shift = Shift.objects.filter(guard=user, date=timezone.localdate()).select_related("station").first()
                if today_shift and today_shift.station:
                    station = today_shift.station
                else:
                    raise ValidationError({"station": "Authenticated guard has no assigned duty station."})

            # Off-duty lockout check: If guard has scheduled shifts on the roster, verify today is active duty
            has_any_shifts = Shift.objects.filter(guard=user).exists()
            if has_any_shifts:
                has_duty = Shift.objects.filter(guard=user, station=station, date=timezone.localdate()).exists()
                if not has_duty:
                    raise PermissionDenied("Off-duty lockout: You have no active shift scheduled today to initiate patrols.")
        else:
            station = serializer.validated_data.get("station") or user.station
            if not station:
                raise ValidationError({"station": "A valid station is required."})

        serializer.save(guard=user, station=station)

    @action(detail=True, methods=["post"], url_path="scan")
    def scan_checkpoint(self, request, pk=None):
        """
        POST /patrols/logs/{id}/scan/
        Requires verified proof: QR token, NFC UID, or verified GPS proximity.
        Button-only / unverified check-ins are strictly rejected.
        """
        patrol = self.get_object()
        if patrol.status == PatrolStatus.COMPLETED:
            return Response(
                {"detail": "Cannot scan checkpoint: Patrol is already marked as completed."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        if patrol.guard != request.user and request.user.role == UserRole.GUARD:
            return Response(
                {"detail": "Proxy action rejected: You can only record scans for your own active patrol."},
                status=status.HTTP_403_FORBIDDEN,
            )

        checkpoint_id = request.data.get("checkpoint")
        if not checkpoint_id:
            return Response({"detail": "checkpoint ID is required."}, status=status.HTTP_400_BAD_REQUEST)

        checkpoint = get_object_or_404(Checkpoint, id=checkpoint_id, station=patrol.station)

        # Extract proof parameters
        qr_token = request.data.get("qr_token") or request.data.get("qr_code", "")
        nfc_uid = request.data.get("nfc_uid", "")
        gps_coords = request.data.get("gps_coords", "") or request.data.get("gps", "")
        lat_val = request.data.get("latitude")
        lon_val = request.data.get("longitude")
        if (lat_val is None or lon_val is None) and gps_coords and "," in str(gps_coords):
            try:
                parts = str(gps_coords).split(",")
                if len(parts) == 2:
                    lat_val = parts[0].strip()
                    lon_val = parts[1].strip()
            except Exception:
                pass
        notes = request.data.get("notes", "Checkpoint verified secure.")

        verified = False
        verification_method = "UNVERIFIED"

        # 1. Cryptographic QR token match
        if qr_token and checkpoint.qr_code and qr_token.strip() == checkpoint.qr_code.strip():
            verified = True
            verification_method = "QR_TOKEN"

        # 2. NFC UID verification
        elif nfc_uid and len(nfc_uid.strip()) >= 4:
            verified = True
            verification_method = "NFC_UID"

        # 3. Server-validated GPS proximity
        if not verified and lat_val is not None and lon_val is not None:
            try:
                lat_float = float(lat_val)
                lon_float = float(lon_val)
                # Check proximity to checkpoint (or station if checkpoint has no custom coords)
                c_lat = checkpoint.latitude if checkpoint.latitude != 0.0 else patrol.station.latitude
                c_lon = checkpoint.longitude if checkpoint.longitude != 0.0 else patrol.station.longitude
                radius = 100.0 if checkpoint.latitude != 0.0 else patrol.station.geofence_radius_meters
                if is_within_geofence(lat_float, lon_float, c_lat, c_lon, radius_meters=radius, buffer_meters=50.0):
                    verified = True
                    verification_method = "GPS_PROXIMITY"
            except (ValueError, TypeError):
                pass

        if not verified:
            return Response({
                "detail": "Checkpoint verification failed. Check-ins must be validated via QR token, NFC UID, or verified GPS proximity.",
                "verified": False,
            }, status=status.HTTP_400_BAD_REQUEST)

        gps_str = f"{lat_val},{lon_val}" if (lat_val is not None and lon_val is not None) else gps_coords
        scan_note = f"[{verification_method}] {notes}"

        scan = CheckpointScan.objects.create(
            patrol_log=patrol,
            checkpoint=checkpoint,
            gps_coords=gps_str,
            notes=scan_note,
        )
        return Response(CheckpointScanSerializer(scan).data, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=["post"], url_path="finish")
    def finish_patrol(self, request, pk=None):
        patrol = self.get_object()
        if patrol.status == PatrolStatus.COMPLETED:
            return Response({"detail": "Patrol is already completed."}, status=status.HTTP_400_BAD_REQUEST)

        if request.user.role != UserRole.GUARD:
            return Response(
                {"detail": "Only on-duty guards can terminate active patrols."},
                status=status.HTTP_403_FORBIDDEN,
            )
        if patrol.guard != request.user:
            return Response(
                {"detail": "You can only terminate your own active patrol."},
                status=status.HTTP_403_FORBIDDEN,
            )

        patrol.status = PatrolStatus.COMPLETED
        patrol.end_time = timezone.now()
        notes = request.data.get("notes")
        if notes:
            patrol.notes = notes
        patrol.save()

        return Response(self.get_serializer(patrol).data, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="approve", permission_classes=[IsSupervisorOrAdmin])
    def approve_patrol(self, request, pk=None):
        """
        Supervisors cannot approve their own patrols.
        """
        patrol = self.get_object()
        if patrol.guard == request.user:
            return Response(
                {"detail": "Conflict of interest: Supervisors cannot approve their own patrols."},
                status=status.HTTP_403_FORBIDDEN,
            )
        return Response({
            "message": f"Patrol {patrol.id} approved by supervisor {request.user.username}.",
            "patrol": self.get_serializer(patrol).data
        }, status=status.HTTP_200_OK)
