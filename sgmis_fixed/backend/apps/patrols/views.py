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
        user = self.request.user
        station_id = self.request.query_params.get("station")
        if station_id:
            qs = qs.filter(station_id=station_id)
        elif user.is_authenticated and user.role == UserRole.SUPERVISOR:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.none()
        return qs

class PatrolLogViewSet(viewsets.ModelViewSet):
    queryset = PatrolLog.objects.all().select_related("guard", "station").prefetch_related("scans", "scans__checkpoint")
    serializer_class = PatrolLogSerializer
    permission_classes = [IsAuthenticated]

    def get_permissions(self):
        if self.action in ["scan_checkpoint", "finish_patrol", "start_patrol", "sync_events"]:
            return [IsGuard()]
        elif self.action in ["cancel_patrol", "reassign_patrol", "reject_patrol", "approve_patrol"]:
            return [IsSupervisorOrAdmin()]
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

    def get_object(self):
        queryset = PatrolLog.objects.all().select_related("guard", "station")
        lookup_url_kwarg = self.lookup_url_kwarg or self.lookup_field
        filter_kwargs = {self.lookup_field: self.kwargs[lookup_url_kwarg]}
        obj = get_object_or_404(queryset, **filter_kwargs)
        self.check_object_permissions(self.request, obj)
        user = self.request.user
        if user and user.is_authenticated:
            if user.role == UserRole.SUPERVISOR:
                if not user.station or obj.station_id != user.station_id:
                    raise PermissionDenied("Station isolation: Access denied to patrol from another station.")
        return obj

    def perform_create(self, serializer):
        user = self.request.user
        if user.role == UserRole.GUARD:
            raise PermissionDenied("Guards cannot create arbitrary patrols. Patrol rounds must be assigned by a station supervisor.")
        if user.role != UserRole.SUPERVISOR:
            raise PermissionDenied("Administrators have view-only monitoring. Operational patrols must be assigned by station supervisors.")

        from django.contrib.auth import get_user_model
        UserModel = get_user_model()

        if not user.station:
            raise ValidationError({"station": "Supervisor has no assigned station."})
        station = user.station
        target_guard_id = self.request.data.get("guard") or self.request.data.get("guard_id")
        if not target_guard_id or str(target_guard_id) == str(user.id):
            raise PermissionDenied("Supervisors cannot execute patrols for themselves. Patrols must be assigned to an eligible on-duty guard.")
        try:
            target_guard = UserModel.objects.get(id=target_guard_id)
        except (UserModel.DoesNotExist, ValueError):
            raise ValidationError({"guard": "Assigned guard does not exist."})

        if target_guard.role != UserRole.GUARD:
            raise ValidationError({"guard": "Assigned user must be a security guard."})

        # Station isolation: supervisor cannot assign patrol outside station
        if target_guard.station_id != station.id:
            raise PermissionDenied("Supervisor cannot assign patrol outside station. Guard is assigned to a different station.")

        name = self.request.data.get("name") or serializer.validated_data.get("name") or "Routine Station Patrol"
        start_window = serializer.validated_data.get("start_window") or self.request.data.get("start_window")
        deadline = serializer.validated_data.get("deadline") or self.request.data.get("deadline")
        notes = serializer.validated_data.get("notes")
        if notes is None:
            notes = self.request.data.get("notes") or ""

        serializer.save(
            guard=target_guard,
            station=station,
            assigned_by=user,
            name=name,
            start_window=start_window,
            deadline=deadline,
            notes=notes,
            status=PatrolStatus.ASSIGNED,
        )

    @action(detail=True, methods=["post"], url_path="start")
    def start_patrol(self, request, pk=None):
        """
        POST /patrols/logs/{id}/start/
        Assigned guard starts the supervisor-assigned patrol.
        """
        patrol = self.get_object()
        if request.user.role != UserRole.GUARD:
            raise PermissionDenied("Only assigned guards can start patrol rounds.")
        if patrol.guard != request.user:
            raise PermissionDenied("Proxy action rejected: You can only start a patrol assigned to you.")
        if patrol.status != PatrolStatus.ASSIGNED:
            return Response(
                {"detail": f"Cannot start patrol: Patrol is in status '{patrol.status}' (expected '{PatrolStatus.ASSIGNED}')."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Station boundary check
        if request.user.station and patrol.station != request.user.station:
            return Response(
                {"detail": "You cannot start a patrol for a station you are not assigned to."},
                status=status.HTTP_403_FORBIDDEN,
            )

        # Off-duty lockout check: If guard has scheduled shifts on the roster, verify today is active duty
        has_any_shifts = Shift.objects.filter(guard=request.user).exists()
        if has_any_shifts:
            has_duty = Shift.objects.filter(guard=request.user, station=patrol.station, date=timezone.localdate()).exists()
            if not has_duty:
                raise PermissionDenied("Off-duty lockout: You have no active shift scheduled today to start patrols.")

        now = timezone.now()
        # Start window check
        if patrol.start_window and now < patrol.start_window:
            return Response(
                {
                    "detail": "Patrol start window has not opened yet.",
                    "start_window": patrol.start_window.isoformat(),
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Deadline check
        if patrol.deadline and now > patrol.deadline:
            patrol.status = PatrolStatus.EXPIRED
            patrol.record_anomaly("EXPIRED_PATROL", f"Guard attempted to start patrol after deadline ({patrol.deadline.isoformat()}).")
            patrol.save(update_fields=["status"])
            return Response(
                {
                    "detail": "Patrol deadline has passed and the patrol has expired.",
                    "deadline": patrol.deadline.isoformat(),
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        patrol.status = PatrolStatus.IN_PROGRESS
        patrol.start_time = now
        patrol.save(update_fields=["status", "start_time"])
        return Response(self.get_serializer(patrol).data, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="cancel", permission_classes=[IsSupervisorOrAdmin])
    def cancel_patrol(self, request, pk=None):
        """
        POST /patrols/logs/{id}/cancel/
        Supervisor cancels an unstarted patrol.
        """
        patrol = self.get_object()
        if request.user.role == UserRole.SUPERVISOR and request.user.station and patrol.station != request.user.station:
            raise PermissionDenied("Station isolation: You cannot cancel patrols from another station.")
        if patrol.status != PatrolStatus.ASSIGNED:
            return Response(
                {"detail": f"Cannot cancel patrol: Only unstarted patrols can be cancelled (current status: {patrol.status})."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        patrol.status = PatrolStatus.CANCELLED
        patrol.save(update_fields=["status"])
        return Response(
            {"detail": "Patrol cancelled successfully.", "patrol": self.get_serializer(patrol).data},
            status=status.HTTP_200_OK,
        )

    @action(detail=True, methods=["post"], url_path="reassign", permission_classes=[IsSupervisorOrAdmin])
    def reassign_patrol(self, request, pk=None):
        """
        POST /patrols/logs/{id}/reassign/
        Supervisor reassigns an unstarted patrol to another eligible guard.
        """
        patrol = self.get_object()
        if request.user.role == UserRole.SUPERVISOR and request.user.station and patrol.station != request.user.station:
            raise PermissionDenied("Station isolation: You cannot reassign patrols from another station.")
        if patrol.status != PatrolStatus.ASSIGNED:
            return Response(
                {"detail": f"Cannot reassign patrol: Only unstarted patrols can be reassigned (current status: {patrol.status})."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        new_guard_id = request.data.get("guard") or request.data.get("guard_id")
        if not new_guard_id:
            return Response({"detail": "guard ID is required for reassignment."}, status=status.HTTP_400_BAD_REQUEST)
        from django.contrib.auth import get_user_model
        UserModel = get_user_model()
        try:
            new_guard = UserModel.objects.get(id=new_guard_id)
        except (UserModel.DoesNotExist, ValueError):
            return Response({"detail": "Assigned guard does not exist."}, status=status.HTTP_404_NOT_FOUND)

        if new_guard.role != UserRole.GUARD:
            return Response({"detail": "Reassigned user must be a guard."}, status=status.HTTP_400_BAD_REQUEST)
        if new_guard.station_id != patrol.station_id:
            return Response({"detail": "Station isolation violation: Target guard is assigned to a different station."}, status=status.HTTP_400_BAD_REQUEST)

        patrol.guard = new_guard
        patrol.save(update_fields=["guard"])
        return Response(
            {"detail": f"Patrol reassigned to {new_guard.username}.", "patrol": self.get_serializer(patrol).data},
            status=status.HTTP_200_OK,
        )

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
        if patrol.status in [PatrolStatus.COMPLETED, PatrolStatus.APPROVED, PatrolStatus.FAILED, PatrolStatus.EXPIRED, PatrolStatus.CANCELLED]:
            return Response(
                {"detail": f"Cannot scan checkpoint: Patrol is marked as {patrol.status}."},
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
            patrol.record_anomaly("DUPLICATE_SCAN", f"Duplicate scan rejected: Checkpoint '{checkpoint.name}' already scanned.")
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
                    patrol.record_anomaly("OUT_OF_SEQUENCE", f"Out-of-sequence scan rejected: Checkpoint '{checkpoint.name}' (order {checkpoint.order}), expected '{next_expected_cp.name}' (order {next_expected_cp.order}).")
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
                    patrol.record_anomaly("RAPID_TRANSIT", f"Minimum transit time required between checkpoints ({int(elapsed_seconds)}s elapsed, {checkpoint.min_interval_seconds}s required).")
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
                    patrol.record_anomaly("RAPID_TRANSIT", f"Minimum travel time required from patrol start ({int(elapsed_seconds)}s elapsed, {checkpoint.min_interval_seconds}s required).")
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

        # 2. Registered NFC UID verification
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

        # 3. Server-validated GPS proximity
        acc_val = request.data.get("accuracy") or request.data.get("gps_accuracy")
        acc_float = None
        if acc_val is not None:
            try:
                acc_float = float(acc_val)
            except (ValueError, TypeError):
                pass

        if not verified and (lat_val is not None or lon_val is not None or gps_coords):
            if checkpoint.latitude == 0.0 and checkpoint.longitude == 0.0:
                if not rejection_reason:
                    rejection_reason = "Checkpoint GPS coordinates are not configured (LOCATION_NOT_CONFIGURED). Physical verification requires registered NFC or QR token."
            elif parsed_lat is None or parsed_lon is None or (parsed_lat == 0.0 and parsed_lon == 0.0):
                if not rejection_reason:
                    rejection_reason = "Invalid GPS coordinates: Accurate device coordinates are required for GPS verification."
            else:
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
            patrol.record_anomaly("FAILED_VERIFICATION", error_detail)
            return Response({
                "detail": error_detail,
                "verified": False,
            }, status=status.HTTP_400_BAD_REQUEST)

        gps_str = f"{parsed_lat},{parsed_lon}" if (parsed_lat is not None and parsed_lon is not None) else (gps_coords or "")
        scan_note = f"[{verification_method}] {notes}"

        client_event_id = request.data.get("client_event_id")
        parsed_event_id = None
        if client_event_id:
            try:
                import uuid as uuid_lib
                parsed_event_id = uuid_lib.UUID(str(client_event_id))
            except Exception:
                pass

        scan = CheckpointScan.objects.create(
            patrol_log=patrol,
            checkpoint=checkpoint,
            client_event_id=parsed_event_id,
            gps_coords=gps_str,
            accuracy=acc_float,
            verification_method=verification_method,
            notes=scan_note,
        )
        return Response(CheckpointScanSerializer(scan).data, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=["post"], url_path="sync_events")
    def sync_events(self, request, pk=None):
        """
        POST /patrols/logs/{id}/sync_events/
        Batch idempotent offline scan event synchronization.
        """
        patrol = self.get_object()
        if request.user.role != UserRole.GUARD:
            raise PermissionDenied("Only the assigned guard can synchronize patrol events.")
        if patrol.guard != request.user:
            raise PermissionDenied("Proxy action rejected: You can only synchronize events for your own active patrol.")
        if patrol.status in [PatrolStatus.COMPLETED, PatrolStatus.APPROVED, PatrolStatus.FAILED, PatrolStatus.EXPIRED, PatrolStatus.CANCELLED]:
            return Response(
                {"detail": f"Cannot sync events: Patrol is already marked as {patrol.status}."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        events_data = request.data.get("events")
        if not isinstance(events_data, list):
            return Response({"detail": "Expected a list of 'events' in request body."}, status=status.HTTP_400_BAD_REQUEST)

        synced_count = 0
        duplicate_count = 0
        rejected_count = 0
        results = []

        active_checkpoints = list(
            Checkpoint.objects.filter(station=patrol.station, is_active=True).order_by("order", "name", "id")
        )

        for event in events_data:
            event_id = event.get("client_event_id")
            if not event_id:
                rejected_count += 1
                results.append({
                    "status": "REJECTED",
                    "reason": "Missing client_event_id for offline event.",
                    "verified": False,
                })
                continue

            # Idempotency check: Duplicate client_event_id
            if CheckpointScan.objects.filter(client_event_id=event_id).exists():
                duplicate_count += 1
                results.append({
                    "client_event_id": str(event_id),
                    "status": "DUPLICATE_IGNORED",
                    "verified": True,
                })
                continue

            checkpoint_id = event.get("checkpoint")
            try:
                checkpoint = Checkpoint.objects.get(id=checkpoint_id)
            except (Checkpoint.DoesNotExist, ValueError):
                rejected_count += 1
                patrol.record_anomaly("INVALID_CHECKPOINT", f"Offline event referenced non-existent checkpoint {checkpoint_id}.", {"client_event_id": str(event_id)})
                results.append({
                    "client_event_id": str(event_id),
                    "status": "REJECTED",
                    "reason": "Checkpoint not found.",
                    "verified": False,
                })
                continue

            # Station isolation
            if checkpoint.station_id != patrol.station_id:
                rejected_count += 1
                patrol.record_anomaly("STATION_ISOLATION_VIOLATION", f"Checkpoint {checkpoint.name} belongs to foreign station.", {"client_event_id": str(event_id)})
                results.append({
                    "client_event_id": str(event_id),
                    "status": "REJECTED",
                    "reason": "Station isolation violation.",
                    "verified": False,
                })
                continue

            # Duplicate scan within patrol
            existing_scans = patrol.scans.order_by("scanned_at", "id")
            scanned_cp_ids = list(existing_scans.values_list("checkpoint_id", flat=True))
            if checkpoint.id in scanned_cp_ids:
                rejected_count += 1
                patrol.record_anomaly("DUPLICATE_SCAN", f"Checkpoint '{checkpoint.name}' scanned more than once.", {"client_event_id": str(event_id)})
                results.append({
                    "client_event_id": str(event_id),
                    "status": "REJECTED",
                    "reason": f"Duplicate scan rejected: Checkpoint '{checkpoint.name}' already scanned.",
                    "verified": False,
                })
                continue

            # Sequence check
            if active_checkpoints:
                distinct_count = len(set(scanned_cp_ids))
                if distinct_count < len(active_checkpoints):
                    next_expected = active_checkpoints[distinct_count]
                    if checkpoint.id != next_expected.id:
                        rejected_count += 1
                        patrol.record_anomaly("OUT_OF_SEQUENCE", f"Scanned checkpoint {checkpoint.name} (order {checkpoint.order}), expected {next_expected.name} (order {next_expected.order}).", {"client_event_id": str(event_id)})
                        results.append({
                            "client_event_id": str(event_id),
                            "status": "REJECTED",
                            "reason": f"Out-of-sequence scan rejected. Expected order {next_expected.order}.",
                            "verified": False,
                        })
                        continue

            # Verification proof
            qr_token = event.get("qr_token") or event.get("qr_code", "")
            nfc_uid = event.get("nfc_uid", "")
            gps_coords = event.get("gps_coords", "") or event.get("gps", "")
            lat_val = event.get("latitude")
            lon_val = event.get("longitude")
            acc_val = event.get("accuracy")
            notes = event.get("notes", "Offline checkpoint verified secure.")
            client_ts_str = event.get("client_timestamp")
            parsed_client_ts = None
            if client_ts_str:
                try:
                    from django.utils.dateparse import parse_datetime
                    parsed_client_ts = parse_datetime(client_ts_str)
                except Exception:
                    pass

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
                    parsed_lat = float(parts[0].strip())
                    parsed_lon = float(parts[1].strip())
                except (ValueError, TypeError):
                    pass

            verified = False
            verification_method = "UNVERIFIED"
            rejection_reason = None

            if qr_token and checkpoint.qr_code and str(qr_token).strip() == checkpoint.qr_code.strip():
                verified = True
                verification_method = "QR_TOKEN"

            if not verified and nfc_uid:
                clean_nfc = str(nfc_uid).strip()
                if len(clean_nfc) < 4:
                    rejection_reason = "Invalid NFC UID format."
                elif not checkpoint.nfc_uid:
                    rejection_reason = "NFC verification is not configured for this checkpoint."
                elif clean_nfc.upper() != checkpoint.nfc_uid.strip().upper():
                    rejection_reason = "NFC verification failed: Tag UID does not match."
                else:
                    verified = True
                    verification_method = "NFC_UID"

            if not verified and (parsed_lat is not None and parsed_lon is not None):
                if checkpoint.latitude == 0.0 and checkpoint.longitude == 0.0:
                    rejection_reason = "Checkpoint GPS coordinates not configured."
                elif parsed_lat == 0.0 and parsed_lon == 0.0:
                    rejection_reason = "Invalid GPS coordinates."
                else:
                    acc_float = None
                    if acc_val is not None:
                        try:
                            acc_float = float(acc_val)
                        except (ValueError, TypeError):
                            pass
                    if acc_float is not None and acc_float > 100.0:
                        rejection_reason = f"GPS accuracy too low ({acc_float:.1f}m)."
                    else:
                        if is_within_geofence(parsed_lat, parsed_lon, checkpoint.latitude, checkpoint.longitude, radius_meters=100.0, buffer_meters=50.0):
                            verified = True
                            verification_method = "GPS_PROXIMITY"
                        else:
                            rejection_reason = "Geofence violation: Out of range."

            if not verified:
                rejected_count += 1
                patrol.record_anomaly("FAILED_VERIFICATION", rejection_reason or "Proof verification failed.", {"client_event_id": str(event_id)})
                results.append({
                    "client_event_id": str(event_id),
                    "status": "REJECTED",
                    "reason": rejection_reason or "Verification proof failed.",
                    "verified": False,
                })
                continue

            gps_str = f"{parsed_lat},{parsed_lon}" if (parsed_lat is not None and parsed_lon is not None) else (gps_coords or "")
            acc_float = None
            if acc_val is not None:
                try:
                    acc_float = float(acc_val)
                except (ValueError, TypeError):
                    pass

            scan = CheckpointScan.objects.create(
                patrol_log=patrol,
                checkpoint=checkpoint,
                client_event_id=event_id,
                client_timestamp=parsed_client_ts,
                gps_coords=gps_str,
                accuracy=acc_float,
                verification_method=verification_method,
                notes=f"[{verification_method}] {notes}",
            )
            synced_count += 1
            results.append({
                "client_event_id": str(event_id),
                "scan_id": str(scan.id),
                "status": "ACCEPTED",
                "verified": True,
                "verification_method": verification_method,
            })

        return Response({
            "synced_count": synced_count,
            "duplicate_count": duplicate_count,
            "rejected_count": rejected_count,
            "results": results,
            "patrol": self.get_serializer(patrol).data,
        }, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="finish")
    def finish_patrol(self, request, pk=None):
        patrol = self.get_object()
        if patrol.status in [PatrolStatus.COMPLETED, PatrolStatus.APPROVED]:
            return Response({"detail": "Patrol is already completed."}, status=status.HTTP_400_BAD_REQUEST)

        if patrol.status == PatrolStatus.EXPIRED:
            return Response({"detail": "Cannot complete patrol: Patrol has expired."}, status=status.HTTP_400_BAD_REQUEST)

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

        now = timezone.now()
        # Expired patrol check
        if patrol.deadline and now > patrol.deadline:
            patrol.status = PatrolStatus.EXPIRED
            patrol.record_anomaly("EXPIRED_COMPLETION", f"Patrol finished after deadline ({patrol.deadline.isoformat()}).")
            patrol.save(update_fields=["status"])
            return Response(
                {"detail": "Cannot complete patrol: Patrol deadline has passed and the patrol is expired."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Minimum elapsed patrol duration enforcement (physical inspection cannot be instantaneous)
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
        if patrol.status not in [PatrolStatus.COMPLETED, PatrolStatus.APPROVED]:
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
        patrol.status = PatrolStatus.APPROVED
        patrol.save(update_fields=["is_approved", "approved_by", "approved_at", "status"])

        return Response({
            "message": f"Patrol {patrol.id} approved by supervisor {request.user.username}.",
            "patrol": self.get_serializer(patrol).data
        }, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="reject", permission_classes=[IsSupervisorOrAdmin])
    def reject_patrol(self, request, pk=None):
        """
        Supervisors/Admins reject completed patrols.
        Conflict of interest: Users cannot reject their own patrols.
        """
        patrol = self.get_object()
        if request.user.role == UserRole.GUARD:
            return Response(
                {"detail": "Guards are not authorized to review patrols."},
                status=status.HTTP_403_FORBIDDEN,
            )
        if patrol.guard == request.user:
            return Response(
                {"detail": "Conflict of interest: Supervisors cannot reject their own patrols."},
                status=status.HTTP_403_FORBIDDEN,
            )
        if patrol.status != PatrolStatus.COMPLETED:
            return Response(
                {"detail": f"Cannot reject patrol: Patrol must be completed before review (current status: {patrol.status})."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        reason = request.data.get("reason", "Supervisor rejected patrol verification.")
        patrol.status = PatrolStatus.FAILED
        patrol.is_approved = False
        patrol.record_anomaly("SUPERVISOR_REJECTION", reason, {"rejected_by": request.user.username})
        patrol.save(update_fields=["status", "is_approved"])
        return Response(
            {"detail": f"Patrol rejected: {reason}", "patrol": self.get_serializer(patrol).data},
            status=status.HTTP_200_OK,
        )
