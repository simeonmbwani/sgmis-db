from django.db import models
from rest_framework import viewsets, status
from rest_framework.response import Response
from rest_framework.decorators import action
from rest_framework.permissions import IsAuthenticated
from .models import Station, GuardPair
from .serializers import StationSerializer, GuardPairSerializer
from rest_framework.exceptions import PermissionDenied
from apps.accounts.permissions import IsAdministrator, IsSupervisorOrAdmin
from apps.accounts.models import UserRole
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
    Guards and Supervisors have read-only access.
    Administrators have master cross-station pair control.
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
        if self.action == "reassign_guard":
            return [IsSupervisorOrAdmin()]
        return [IsAuthenticated()]

    def get_queryset(self):
        qs = super().get_queryset()
        station_id = self.request.query_params.get("station")
        if station_id:
            qs = qs.filter(station_id=station_id)
        return qs

    @action(detail=False, methods=["post"], url_path="reassign_guard", permission_classes=[IsSupervisorOrAdmin])
    def reassign_guard(self, request):
        """
        POST /stations/pairs/reassign_guard/
        Reassigns a guard's pair with full audit trail in GuardPairReassignmentAudit.
        Accepts: guard (UUID), new_pair (UUID), slot ('guard_a' or 'guard_b'), effective_date, reason.
        """
        user = request.user
        guard_id = request.data.get("guard") or request.data.get("guard_id")
        target_pair_id = request.data.get("new_pair") or request.data.get("new_pair_id") or request.data.get("target_pair_id")
        slot = request.data.get("slot")
        effective_date_str = request.data.get("effective_date")
        reason = (request.data.get("reason") or "").strip()

        if not guard_id or not target_pair_id:
            return Response({"detail": "guard and new_pair are required fields."}, status=status.HTTP_400_BAD_REQUEST)
        if not reason:
            return Response({"detail": "Operational justification (reason) is mandatory."}, status=status.HTTP_400_BAD_REQUEST)

        from apps.accounts.models import User, UserRole
        from .models import GuardPair, GuardPairReassignmentAudit
        from apps.core.models import SecurityAuditEvent
        from django.utils import timezone
        from datetime import datetime

        try:
            guard = User.objects.get(id=guard_id)
        except (User.DoesNotExist, ValueError):
            return Response({"detail": "Guard not found."}, status=status.HTTP_404_NOT_FOUND)

        try:
            target_pair = GuardPair.objects.get(id=target_pair_id)
        except (GuardPair.DoesNotExist, ValueError):
            return Response({"detail": "Target guard pair not found."}, status=status.HTTP_404_NOT_FOUND)

        station = target_pair.station
        if user.role == UserRole.SUPERVISOR:
            if not user.station or station.id != user.station.id:
                raise PermissionDenied("Station isolation: Cannot reassign pairs at another station.")
            if guard.station_id != user.station.id:
                raise PermissionDenied("Station isolation: Cannot reassign a guard from another station.")

        old_pair = GuardPair.objects.filter(
            station=station,
            is_active=True,
        ).filter(models.Q(guard_a=guard) | models.Q(guard_b=guard)).first()

        effective_date = timezone.localdate()
        if effective_date_str:
            try:
                effective_date = datetime.strptime(str(effective_date_str)[:10], "%Y-%m-%d").date()
            except ValueError:
                pass

        displaced_guard = None
        if slot == "guard_a":
            displaced_guard = target_pair.guard_a
            target_pair.guard_a = guard
        elif slot == "guard_b":
            displaced_guard = target_pair.guard_b
            target_pair.guard_b = guard
        else:
            if target_pair.guard_a_id == guard.id:
                displaced_guard = target_pair.guard_b
            else:
                displaced_guard = target_pair.guard_b
                target_pair.guard_b = guard

        if old_pair and old_pair.id != target_pair.id and displaced_guard and displaced_guard.id != guard.id:
            if old_pair.guard_a_id == guard.id:
                old_pair.guard_a = displaced_guard
            elif old_pair.guard_b_id == guard.id:
                old_pair.guard_b = displaced_guard
            old_pair.save()

        target_pair.save()

        audit = GuardPairReassignmentAudit.objects.create(
            station=station,
            guard=guard,
            old_pair=old_pair,
            new_pair=target_pair,
            effective_date=effective_date,
            reason=reason,
            authorized_by=user,
        )

        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=user,
            actor_username=user.username,
            target_model="GuardPair",
            target_id=str(target_pair.id),
            details={
                "action": "GUARD_PAIR_REASSIGNED",
                "guard": guard.username,
                "old_pair": str(old_pair) if old_pair else None,
                "new_pair": str(target_pair),
                "effective_date": effective_date.isoformat(),
                "reason": reason,
            },
        )

        from .serializers import GuardPairSerializer, GuardPairReassignmentAuditSerializer
        return Response({
            "message": f"Guard {guard.get_full_name() or guard.username} reassigned to Pair {target_pair.rotation_order}.",
            "pair": GuardPairSerializer(target_pair).data,
            "audit": GuardPairReassignmentAuditSerializer(audit).data,
        }, status=status.HTTP_200_OK)


class GuardPairReassignmentAuditViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Read-only view of historical pair reassignments.
    """
    from .models import GuardPairReassignmentAudit
    from .serializers import GuardPairReassignmentAuditSerializer
    queryset = GuardPairReassignmentAudit.objects.select_related("station", "guard", "old_pair", "new_pair", "authorized_by").all()
    serializer_class = GuardPairReassignmentAuditSerializer
    permission_classes = [IsSupervisorOrAdmin]

    def get_queryset(self):
        from .models import GuardPairReassignmentAudit
        from apps.accounts.models import UserRole
        user = self.request.user
        qs = GuardPairReassignmentAudit.objects.select_related("station", "guard", "old_pair", "new_pair", "authorized_by").all()
        if user.role == UserRole.SUPERVISOR and user.station:
            qs = qs.filter(station=user.station)
        station_id = self.request.query_params.get("station")
        if station_id:
            qs = qs.filter(station_id=station_id)
        guard_id = self.request.query_params.get("guard")
        if guard_id:
            qs = qs.filter(guard_id=guard_id)
        return qs

