import random
from datetime import datetime
from django.utils import timezone
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.exceptions import PermissionDenied
from .models import EscortDuty, EscortStatus
from .serializers import EscortDutySerializer
from apps.accounts.models import User, UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin
from apps.shifts.models import Shift
from apps.notifications.models import Notification
from apps.core.sms import send_sms
from apps.core.models import SecurityAuditEvent

class EscortDutyViewSet(viewsets.ModelViewSet):
    queryset = EscortDuty.objects.all().select_related("guard", "supervisor", "station")
    serializer_class = EscortDutySerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station_id:
                return qs.filter(station_id=user.station_id)
            return qs.filter(supervisor=user)
        return qs

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "auto_allocate"]:
            return [IsSupervisorOrAdmin()]
        return [IsAuthenticated()]

    def destroy(self, request, *args, **kwargs):
        raise PermissionDenied("Escort duty history is retained; cancel the duty to preserve its audit trail.")

    def perform_create(self, serializer):
        user = self.request.user
        target_guard = serializer.validated_data.get("guard")
        start_time = serializer.validated_data.get("start_time")
        if target_guard and start_time:
            target_date = start_time.date()
            from apps.leave.models import LeaveApplication, LeaveStatus
            if LeaveApplication.objects.filter(guard=target_guard, status=LeaveStatus.APPROVED, start_date__lte=target_date, end_date__gte=target_date).exists():
                raise ValidationError({"guard": "The selected guard is on approved leave on this date."})
        supervisor = serializer.validated_data.get("supervisor") or (user if user.role in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR] else None)
        station = serializer.validated_data.get("station") or (user.station if user.station else None)
        duty = serializer.save(supervisor=supervisor, station=station)

        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=user,
            actor_username=user.username,
            target_model="EscortDuty",
            target_id=str(duty.id),
            details={"action": "ESCORT_DUTY_CREATED", "reference": duty.reference,
                     "guard_id": str(duty.guard_id), "station_id": str(duty.station_id)},
        )

        # Notify assigned guard in-app
        Notification.objects.create(
            user=duty.guard,
            title="Escort Mission Assigned",
            message=f"You have been assigned to escort mission {duty.mission_name} (Ref: {duty.reference}). Origin: {duty.origin} -> Destination: {duty.destination}.",
            notification_type="DUTY_ASSIGNMENT",
        )

        # Deliver SMS if phone number configured
        if duty.guard.phone_number:
            send_sms(
                duty.guard.phone_number,
                f"SGMIS Escort Duty: Ref {duty.reference}. Mission: {duty.mission_name}. From {duty.origin} to {duty.destination}. Report at {duty.start_time.strftime('%Y-%m-%d %H:%M')}."
            )

    def perform_update(self, serializer):
        old = serializer.instance
        old_guard_id = old.guard_id
        changed = {key: str(value) for key, value in serializer.validated_data.items()}
        duty = serializer.save()
        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=self.request.user,
            actor_username=self.request.user.username,
            target_model="EscortDuty",
            target_id=str(duty.id),
            details={"action": "ESCORT_DUTY_UPDATED", "reference": duty.reference,
                     "old_guard_id": str(old_guard_id), "new_guard_id": str(duty.guard_id),
                     "changed_fields": changed},
        )
        if old_guard_id != duty.guard_id:
            Notification.objects.create(
                user=duty.guard, title="Escort Mission Reassigned",
                message=f"Escort mission {duty.reference} has been assigned to you.",
                notification_type="DUTY_ASSIGNMENT",
            )

    @action(detail=True, methods=["post"], url_path="acknowledge")
    def acknowledge(self, request, pk=None):
        """
        POST /escorts/duties/{id}/acknowledge/
        Guard acknowledges receipt of assigned escort duty.
        """
        duty = self.get_object()
        if request.user.role == UserRole.GUARD and duty.guard_id != request.user.id:
            return Response({"detail": "You are not assigned to this escort duty."}, status=status.HTTP_403_FORBIDDEN)

        duty.status = EscortStatus.ACKNOWLEDGED
        duty.acknowledged_at = timezone.now()
        remarks = request.data.get("remarks", "").strip()
        if remarks:
            duty.remarks = (duty.remarks + f"\n[{timezone.now().strftime('%Y-%m-%d %H:%M')}] {request.user.username}: {remarks}").strip()
        duty.save()

        if duty.supervisor:
            Notification.objects.create(
                user=duty.supervisor,
                title="Escort Duty Acknowledged",
                message=f"Guard {request.user.get_full_name() or request.user.username} acknowledged escort mission {duty.reference} ({duty.mission_name}).",
                notification_type="OPERATIONAL_ALERT",
            )

        return Response(self.get_serializer(duty).data, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="update_status")
    def update_status(self, request, pk=None):
        """
        POST /escorts/duties/{id}/update_status/
        Guard or supervisor updates operational status (ACKNOWLEDGED, EN_ROUTE, COMPLETED, CANCELLED).
        """
        duty = self.get_object()
        if request.user.role == UserRole.GUARD and duty.guard_id != request.user.id:
            return Response({"detail": "You are not assigned to this escort duty."}, status=status.HTTP_403_FORBIDDEN)

        new_status = request.data.get("status")
        if not new_status or new_status not in EscortStatus.values:
            return Response({"detail": f"Invalid status '{new_status}'. Allowed: {', '.join(EscortStatus.values)}"}, status=status.HTTP_400_BAD_REQUEST)
        if request.user.role == UserRole.ADMINISTRATOR and new_status != EscortStatus.CANCELLED:
            return Response({"detail": "Administrators may cancel escort duties; operational progress is recorded by guards."}, status=status.HTTP_403_FORBIDDEN)

        if new_status == EscortStatus.CANCELLED and request.user.role == UserRole.GUARD:
            return Response({"detail": "Guards cannot cancel escort duties."}, status=status.HTTP_403_FORBIDDEN)

        now = timezone.now()
        duty.status = new_status
        if new_status == EscortStatus.ACKNOWLEDGED and not duty.acknowledged_at:
            duty.acknowledged_at = now
        elif new_status == EscortStatus.EN_ROUTE and not duty.departure_time:
            duty.departure_time = now
        elif new_status == EscortStatus.COMPLETED and not duty.completion_time:
            duty.completion_time = now

        remarks = request.data.get("remarks", "").strip()
        if remarks:
            duty.remarks = (duty.remarks + f"\n[{now.strftime('%Y-%m-%d %H:%M')}] {request.user.username}: {remarks}").strip()

        duty.save()

        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.OVERRIDE if new_status == EscortStatus.CANCELLED else SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=request.user,
            actor_username=request.user.username,
            target_model="EscortDuty",
            target_id=str(duty.id),
            details={
                "action": "ESCORT_STATUS_UPDATE",
                "reference": duty.reference,
                "status": new_status,
                "remarks": remarks,
            }
        )

        return Response(self.get_serializer(duty).data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="auto_allocate")
    def auto_allocate(self, request):
        """
        POST /escorts/duties/auto_allocate/
        Assign non-duty guards to escort duties based on paired lists or random selection.
        """
        start_time_str = request.data.get("start_time")
        end_time_str = request.data.get("end_time")
        mission_name = request.data.get("mission_name", "High-Value Asset Escort")
        origin = request.data.get("origin", "Main Headquarters")
        destination = request.data.get("destination", "Designated Vault")
        strategy = request.data.get("strategy", "RANDOM").upper()
        count = int(request.data.get("count", 1))

        if not start_time_str or not end_time_str:
            return Response({"detail": "start_time and end_time are required (ISO format)."}, status=status.HTTP_400_BAD_REQUEST)

        try:
            start_dt = datetime.fromisoformat(start_time_str.replace("Z", "+00:00"))
            end_dt = datetime.fromisoformat(end_time_str.replace("Z", "+00:00"))
        except (ValueError, TypeError):
            return Response({"detail": "Invalid start_time or end_time format. Use ISO format (YYYY-MM-DDTHH:MM:SS)."}, status=status.HTTP_400_BAD_REQUEST)

        target_date = start_dt.date()

        # Non-duty guards: exclude guards with scheduled shifts, approved leave, or conflicting duties
        from apps.shifts.models import Shift, ShiftType, AssignmentType
        from apps.exams.models import ExamDuty, ExamStatus
        from apps.leave.models import LeaveApplication, LeaveStatus

        scheduled_guards = Shift.objects.filter(date=target_date).exclude(
            assignment_type=AssignmentType.TIME_OFF
        ).exclude(shift_type=ShiftType.OFF).values_list("guard_id", flat=True)

        already_assigned = EscortDuty.objects.filter(
            start_time__date__lte=target_date, end_time__date__gte=target_date
        ).exclude(status=EscortStatus.CANCELLED).values_list("guard_id", flat=True)

        exam_assigned = ExamDuty.objects.filter(
            date=target_date
        ).exclude(status=ExamStatus.CANCELLED).values_list("guard_id", flat=True)

        on_leave_guards = LeaveApplication.objects.filter(
            status=LeaveStatus.APPROVED, start_date__lte=target_date, end_date__gte=target_date
        ).values_list("guard_id", flat=True)

        excluded_ids = set(scheduled_guards) | set(already_assigned) | set(exam_assigned) | set(on_leave_guards)

        candidate_guards = list(
            User.objects.filter(role=UserRole.GUARD, is_active=True).exclude(id__in=excluded_ids)
        )

        if not candidate_guards:
            return Response(
                {"detail": "No non-duty guards are available on this date."},
                status=status.HTTP_404_NOT_FOUND,
            )

        if strategy == "RANDOM":
            selected = random.sample(candidate_guards, min(count, len(candidate_guards)))
        else:
            selected = candidate_guards[:count]

        created_duties = []
        for g in selected:
            duty = EscortDuty.objects.create(
                guard=g,
                mission_name=mission_name,
                origin=origin,
                destination=destination,
                start_time=start_dt,
                end_time=end_dt,
                notes=f"[AUTO-ALLOCATED: Strategy={strategy}]",
            )
            created_duties.append(duty)

            Notification.objects.create(
                user=g,
                title="Escort Mission Assigned",
                message=f"You have been assigned to escort duty: {mission_name} ({origin} -> {destination}).",
                notification_type="DUTY_ASSIGNMENT",
            )

        return Response({
            "message": f"Successfully allocated {len(created_duties)} non-duty guard(s) to escort mission.",
            "strategy": strategy,
            "duties": EscortDutySerializer(created_duties, many=True).data,
        }, status=status.HTTP_201_CREATED)
