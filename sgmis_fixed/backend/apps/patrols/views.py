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
from apps.accounts.permissions import IsSupervisorOrAdmin, IsGuard
from apps.stations.utils import is_within_geofence, calculate_haversine_distance_meters
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

    def get_permissions(self):
        if self.action in ["create", "scan_checkpoint", "finish_patrol"]:
            return [IsGuard()]
        return [permission() for permission in self.permission_classes]

    def update(self, request, *args, **kwargs):
        raise PermissionDenied("Patrol history is read-only after creation.")

    def partial_update(self, request, *args, **kwargs):
        raise PermissionDenied("Patrol history is read-only after creation.")

    def destroy(self, request, *args, **kwargs):
        raise PermissionDenied("Patrol history is retained for operational audit.")

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

        if user.role != UserRole.GUARD:
            raise PermissionDenied("Only guards can initiate patrols.")

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
        if request.user.role != UserRole.GUARD:
            raise PermissionDenied("Only the assigned guard can record patrol checkpoint scans.")
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

        try:
            checkpoint = Checkpoint.objects.get(id=checkpoint_id)
        except (Checkpoint.DoesNotExist, ValueError):
            return Response({"detail": "Checkpoint not found."}, status=status.HTTP_404_NOT_FOUND)

        # Station isolation check: foreign station checkpoint cannot be submitted
        if checkpoint.station_id != patrol.station_id:
            return Response(
                {"detail": "Station isolation violation: Checkpoint belongs to a different station."},
                status=status.HTTP_403_FORBIDDEN,
            )

        if not checkpoint.is_active:
            return Response(
                {"detail": "Cannot scan inactive checkpoint."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # -------------------------------------------------------------
        # Server-Side Checkpoint Sequence Enforcement
        # -------------------------------------------------------------
        active_checkpoints = list(
            Checkpoint.objects.filter(station=patrol.station, is_active=True).order_by("order", "name", "id")
        )
        existing_scans = patrol.scans.order_by("scanned_at", "id")
        scanned_cp_ids = list(existing_scans.values_list("checkpoint_id", flat=True))

        # Duplicate scan rejection: Checkpoint already scanned in this patrol round
        if checkpoint.id in scanned_cp_ids:
            return Response(
                {
                    "detail": f"Duplicate scan rejected: Checkpoint '{checkpoint.name}' has already been scanned in this patrol round.",
                    "verified": False,
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Sequence order enforcement: Guard must scan in designated route order
        if active_checkpoints:
            distinct_scanned_count = len(set(scanned_cp_ids))
            if distinct_scanned_count < len(active_checkpoints):
                next_expected_cp = active_checkpoints[distinct_scanned_count]
                if checkpoint.id != next_expected_cp.id:
                    return Response(
                        {
                            "detail": f"Out-of-sequence checkpoint scan rejected. Checkpoint inspection must follow the designated route order.",
                            "expected_checkpoint": str(next_expected_cp.id),
                            "expected_order": next_expected_cp.order,
                            "verified": False,
                        },
                        status=status.HTTP_400_BAD_REQUEST,
                    )

        # -------------------------------------------------------------
        # Inter-Checkpoint Minimum Travel Time Enforcement
        # -------------------------------------------------------------
        now = timezone.now()
        last_scan = patrol.scans.order_by("-scanned_at").first()

        if checkpoint.min_interval_seconds > 0:
            if last_scan:
                elapsed_seconds = (now - last_scan.scanned_at).total_seconds()
                if elapsed_seconds < checkpoint.min_interval_seconds:
                    return Response(
                        {
                            "detail": f"Minimum transit time required between checkpoints ({int(elapsed_seconds)}s elapsed, {checkpoint.min_interval_seconds}s required). Physical travel between checkpoints requires more time.",
                            "elapsed_seconds": int(elapsed_seconds),
                            "required_seconds": checkpoint.min_interval_seconds,
                            "verified": False,
                        },
                        status=status.HTTP_400_BAD_REQUEST,
                    )
            else:
                elapsed_seconds = (now - patrol.start_time).total_seconds()
                if elapsed_seconds < checkpoint.min_interval_seconds:
                    return Response(
                        {
                            "detail": f"Minimum travel time required from patrol start ({int(elapsed_seconds)}s elapsed, {checkpoint.min_interval_seconds}s required).",
                            "elapsed_seconds": int(elapsed_seconds),
                            "required_seconds": checkpoint.min_interval_seconds,
                            "verified": False,
                        },
                        status=status.HTTP_400_BAD_REQUEST,
                    )

        # -------------------------------------------------------------
        # Verification Proof (NFC, QR, GPS Proximity)
        # -------------------------------------------------------------
        qr_token = request.data.get("qr_token") or request.data.get("qr_code", "")
        nfc_uid = request.data.get("nfc_uid", "")
        gps_coords = request.data.get("gps_coords", "") or request.data.get("gps", "")
        lat_val = request.data.get("latitude")
        lon_val = request.data.get("longitude")
        notes = request.data.get("notes", "Checkpoint verified secure.")

        parsed_lat = None
        parsed_lon = None
        if lat_val is not None and lon_val is not None:
            try:
                parsed_lat = float(lat_val)
                parsed_lon = float(lon_val)
            except (ValueError, TypeError):
                pass
        elif gps_coords and "," in str(gps_coords):
            try:
                parts = str(gps_coords).split(",")
                if len(parts) == 2:
                    parsed_lat = float(parts[0].strip())
                    parsed_lon = float(parts[1].strip())
            except (ValueError, TypeError):
                pass

        verified = False
        verification_method = "UNVERIFIED"
        rejection_reason = None

        # 1. Cryptographic QR token match
        if qr_token and checkpoint.qr_code and str(qr_token).strip() == checkpoint.qr_code.strip():
            verified = True
            verification_method = "QR_TOKEN"

        # 2. Registered NFC UID verification (Fixed bypass: exact registered match required)
        if not verified and nfc_uid:
            clean_nfc = str(nfc_uid).strip()
            if len(clean_nfc) < 4:
                rejection_reason = "Invalid NFC UID format: Scanned NFC UID must be at least 4 characters."
            elif not checkpoint.nfc_uid:
                rejection_reason = "NFC verification is not configured for this checkpoint (NFC_NOT_CONFIGURED)."
            elif clean_nfc.upper() != checkpoint.nfc_uid.strip().upper():
                rejection_reason = "NFC verification failed: Scanned tag UID does not match registered NFC tag for this checkpoint."
            else:
                verified = True
                verification_method = "NFC_UID"

        # 3. Server-validated GPS proximity (Fixed (0.0, 0.0) bypass and hardened validation)
        if not verified and (lat_val is not None or lon_val is not None or gps_coords):
            # Hardened GPS Checkpoint Coordinates Validation
            if checkpoint.latitude == 0.0 and checkpoint.longitude == 0.0:
                if not rejection_reason:
                    rejection_reason = "Checkpoint GPS coordinates are not configured (LOCATION_NOT_CONFIGURED). Physical verification requires registered NFC or QR token."
            elif parsed_lat is None or parsed_lon is None or (parsed_lat == 0.0 and parsed_lon == 0.0):
                if not rejection_reason:
                    rejection_reason = "Invalid GPS coordinates: Accurate device coordinates are required for GPS verification."
            else:
                acc_val = request.data.get("accuracy") or request.data.get("gps_accuracy")
                acc_float = None
                if acc_val is not None:
                    try:
                        acc_float = float(acc_val)
                    except (ValueError, TypeError):
                        pass

                if acc_float is not None and acc_float > 100.0:
                    if not rejection_reason:
                        rejection_reason = f"GPS accuracy too low ({acc_float:.1f}m error). High accuracy location fix required."
                else:
                    c_lat = checkpoint.latitude
                    c_lon = checkpoint.longitude
                    radius = 100.0
                    buffer_m = 50.0

                    if is_within_geofence(parsed_lat, parsed_lon, c_lat, c_lon, radius_meters=radius, buffer_meters=buffer_m):
                        verified = True
                        verification_method = "GPS_PROXIMITY"
                    else:
                        dist = calculate_haversine_distance_meters(parsed_lat, parsed_lon, c_lat, c_lon)
                        if not rejection_reason:
                            rejection_reason = f"Geofence violation: Device location is {dist:.1f}m away from checkpoint (max allowed: {radius + buffer_m:.0f}m)."

        if not verified:
            error_detail = rejection_reason or "Checkpoint verification failed. Check-ins must be validated via registered NFC UID, QR token, or verified GPS proximity."
            return Response({
                "detail": error_detail,
                "verified": False,
            }, status=status.HTTP_400_BAD_REQUEST)

        gps_str = f"{parsed_lat},{parsed_lon}" if (parsed_lat is not None and parsed_lon is not None) else (gps_coords or "")
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

        # Station boundary check
        if request.user.station and patrol.station != request.user.station:
            return Response(
                {"detail": "You cannot complete a patrol for a station you are not assigned to."},
                status=status.HTTP_403_FORBIDDEN,
            )

        # Minimum elapsed patrol duration enforcement (physical inspection cannot be instantaneous)
        now = timezone.now()
        elapsed_seconds = (now - patrol.start_time).total_seconds()
        MIN_PATROL_DURATION_SECONDS = 60
        if elapsed_seconds < MIN_PATROL_DURATION_SECONDS:
            return Response(
                {
                    "detail": f"Patrol duration too short ({int(elapsed_seconds)}s elapsed). Physical inspection requires at least {MIN_PATROL_DURATION_SECONDS} seconds before completion.",
                    "elapsed_seconds": int(elapsed_seconds),
                    "required_seconds": MIN_PATROL_DURATION_SECONDS,
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Station checkpoint completion enforcement
        active_checkpoints = Checkpoint.objects.filter(station=patrol.station, is_active=True)
        if active_checkpoints.exists():
            scanned_ids = set(patrol.scans.values_list("checkpoint_id", flat=True))
            missing_checkpoints = active_checkpoints.exclude(id__in=scanned_ids)
            if missing_checkpoints.exists():
                missing_names = ", ".join(missing_checkpoints.values_list("name", flat=True)[:3])
                return Response(
                    {
                        "detail": f"Cannot complete patrol: {missing_checkpoints.count()} required checkpoint(s) have not been scanned ({missing_names}). All active station checkpoints must be inspected and verified.",
                        "missing_count": missing_checkpoints.count(),
                    },
                    status=status.HTTP_400_BAD_REQUEST,
                )

        patrol.status = PatrolStatus.COMPLETED
        patrol.end_time = now
        notes = request.data.get("notes")
        if notes:
            patrol.notes = notes
        patrol.save()

        return Response(self.get_serializer(patrol).data, status=status.HTTP_200_OK)


    @action(detail=True, methods=["post"], url_path="approve", permission_classes=[IsSupervisorOrAdmin])
    def approve_patrol(self, request, pk=None):
        """
        Supervisors/Admins approve completed patrols.
        Conflict of interest: Users cannot approve their own patrols.
        Patrol must be COMPLETED before it can be approved.
        Approval metadata (is_approved, approved_by, approved_at) is persistently saved.
        """
        patrol = self.get_object()
        if request.user.role == UserRole.GUARD:
            return Response(
                {"detail": "Guards are not authorized to approve patrols."},
                status=status.HTTP_403_FORBIDDEN,
            )
        if patrol.guard == request.user:
            return Response(
                {"detail": "Conflict of interest: Supervisors cannot approve their own patrols."},
                status=status.HTTP_403_FORBIDDEN,
            )
        if patrol.status != PatrolStatus.COMPLETED:
            return Response(
                {"detail": "Cannot approve patrol: Patrol must be completed before supervisor approval."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        if patrol.is_approved:
            return Response(
                {"detail": "Patrol has already been approved."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        patrol.is_approved = True
        patrol.approved_by = request.user
        patrol.approved_at = timezone.now()
        patrol.save(update_fields=["is_approved", "approved_by", "approved_at"])

        return Response({
            "message": f"Patrol {patrol.id} approved by supervisor {request.user.username}.",
            "patrol": self.get_serializer(patrol).data
        }, status=status.HTTP_200_OK)
