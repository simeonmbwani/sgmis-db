from django.utils import timezone
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.exceptions import PermissionDenied, ValidationError, NotAuthenticated
from .models import OccurrenceBookEntry, OBAmendment
from .serializers import OccurrenceBookEntrySerializer, OBAmendmentSerializer
from apps.accounts.models import UserRole
from apps.accounts.views import get_client_ip
from apps.accounts.permissions import IsAdministrator
from apps.shifts.models import Shift
from apps.core.models import SecurityAuditEvent
from apps.core.audit import log_security_event
from apps.core.idempotency import check_idempotency, store_idempotency

class OccurrenceBookEntryViewSet(viewsets.ModelViewSet):
    queryset = OccurrenceBookEntry.objects.all().select_related("station", "guard").prefetch_related("amendments")
    serializer_class = OccurrenceBookEntrySerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        show_archived = self.request.query_params.get("archived")
        if show_archived is None or show_archived.lower() not in ("true", "1"):
            qs = qs.filter(is_archived=False)

        category = self.request.query_params.get("category")
        if category:
            qs = qs.filter(category=category)

        # Strictly enforce role and station scoping
        if user.role == UserRole.GUARD:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.filter(guard=user)
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
                qs = qs.filter(guard_id=guard_id)

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
        if not user or not user.is_authenticated:
            raise NotAuthenticated()

        # Zero Proxy Actions: check if payload attempted to supply another guard
        target_guard = self.request.data.get("guard") or self.request.data.get("guard_id")
        if target_guard and str(target_guard) != str(user.id):
            raise PermissionDenied("Proxy actions are strictly prohibited. You cannot create Occurrence Book entries for another guard.")

        if user.role == UserRole.SUPERVISOR:
            raise PermissionDenied("Supervisors have view-only access to Occurrence Book entries.")

        if user.role == UserRole.GUARD:
            if not user.station:
                raise ValidationError({
                    "station": "Your account has no station assigned. Contact your supervisor or administrator."
                })
            # Off-Duty Guard Lockout: If roster shifts exist for this guard, verify today is an active duty shift
            has_any_shifts = Shift.objects.filter(guard=user).exists()
            if has_any_shifts:
                today_shift = Shift.objects.filter(guard=user, date=timezone.localdate()).first()
                if not today_shift:
                    raise PermissionDenied("Off-duty lockout: You have no active shift scheduled today to log Occurrence Book entries.")
            station = user.station
        else:
            station = serializer.validated_data.get("station") or user.station
            if not station:
                raise ValidationError({
                    "station": "A valid station is required."
                })

        serializer.save(guard=user, station=station)

    def update(self, request, *args, **kwargs):
        raise PermissionDenied(
            "Direct modification of evidence-grade Occurrence Book records is strictly prohibited. "
            "Submit an official amendment via /amend/ instead."
        )

    def partial_update(self, request, *args, **kwargs):
        raise PermissionDenied(
            "Direct modification of evidence-grade Occurrence Book records is strictly prohibited. "
            "Submit an official amendment via /amend/ instead."
        )

    def destroy(self, request, *args, **kwargs):
        raise PermissionDenied(
            "Deletion of evidence-grade Occurrence Book records is strictly prohibited by security policy. "
            "Archival should be used instead."
        )

    @action(detail=True, methods=["post"], url_path="amend")
    def amend(self, request, pk=None):
        entry = self.get_object()
        user = request.user

        # Boundary check: Guards & Supervisors can only amend entries belonging to their station
        if user.role in (UserRole.GUARD, UserRole.SUPERVISOR):
            if not user.station or entry.station_id != user.station_id:
                raise PermissionDenied("You can only amend records from your assigned station.")

        reason = str(request.data.get("reason", "")).strip()
        amended_text = str(request.data.get("amended_text", "")).strip()

        if not reason:
            raise ValidationError({"reason": "A mandatory reason is required to amend an evidence record."})
        if not amended_text:
            raise ValidationError({"amended_text": "Amended text content cannot be blank."})

        amendment = OBAmendment.objects.create(
            entry=entry,
            amended_by=user,
            reason=reason,
            original_text_snapshot=entry.occurrence_text,
            amended_text=amended_text,
        )

        log_security_event(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=user,
            actor_username=user.username,
            ip_address=get_client_ip(request),
            target_model="OccurrenceBookEntry",
            target_id=str(entry.id),
            details={"amendment_id": str(amendment.id), "reason": reason}
        )

        return Response({
            "message": "Occurrence Book amendment recorded successfully.",
            "amendment": OBAmendmentSerializer(amendment).data
        }, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=["post"], url_path="archive", permission_classes=[IsAdministrator])
    def archive(self, request, pk=None):
        entry = self.get_object()
        entry.is_archived = True
        entry.save(update_fields=["is_archived"])
        return Response({
            "message": f"Occurrence Book entry {entry.entry_number} has been archived.",
            "is_archived": True
        }, status=status.HTTP_200_OK)

