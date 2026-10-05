import random
from datetime import datetime
from django.utils import timezone
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.exceptions import PermissionDenied
from .models import ExamDuty, ExamStatus
from .serializers import ExamDutySerializer
from apps.accounts.models import User, UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin
from apps.shifts.models import Shift
from apps.notifications.models import Notification
from apps.core.sms import send_sms
from apps.core.models import SecurityAuditEvent

class ExamDutyViewSet(viewsets.ModelViewSet):
    queryset = ExamDuty.objects.all().select_related("guard", "supervisor", "station")
    serializer_class = ExamDutySerializer
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
        raise PermissionDenied("Exam duty history is retained; cancel the duty to preserve its audit trail.")

    def perform_create(self, serializer):
        user = self.request.user
        target_guard = serializer.validated_data.get("guard")
        target_date = serializer.validated_data.get("date")
        target_start = serializer.validated_data.get("start_time")
        target_end = serializer.validated_data.get("end_time")
        if target_guard and target_date:
            from apps.shifts.services import validate_guard_duty_availability
            validate_guard_duty_availability(
                guard=target_guard,
                date=target_date,
                start_time=target_start,
                end_time=target_end,
                duty_type="EXAM",
                as_drf=True,
            )
        supervisor = serializer.validated_data.get("supervisor") or (user if user.role in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR] else None)
        station = serializer.validated_data.get("station") or (user.station if user.station else None)
        duty = serializer.save(supervisor=supervisor, station=station)

        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=user,
            actor_username=user.username,
            target_model="ExamDuty",
            target_id=str(duty.id),
            details={"action": "EXAM_DUTY_CREATED", "reference": duty.reference,
                     "guard_id": str(duty.guard_id), "station_id": str(duty.station_id)},
        )

        Notification.objects.create(
            user=duty.guard,
            title="Exam Period Duty Assigned",
            message=f"You have been assigned to exam supervision duty: {duty.exam_title} at {duty.institution} on {duty.date} (Ref: {duty.reference}).",
            notification_type="DUTY_ASSIGNMENT",
        )

        if duty.guard.phone_number:
            send_sms(
                duty.guard.phone_number,
                f"SGMIS Exam Duty: Ref {duty.reference}. {duty.exam_title} at {duty.institution} on {duty.date} ({duty.start_time}-{duty.end_time})."
            )

    def perform_update(self, serializer):
        old_guard_id = serializer.instance.guard_id
        target_guard = serializer.validated_data.get("guard") or serializer.instance.guard
        target_date = serializer.validated_data.get("date") or serializer.instance.date
        target_start = serializer.validated_data.get("start_time") or serializer.instance.start_time
        target_end = serializer.validated_data.get("end_time") or serializer.instance.end_time
        from apps.shifts.services import validate_guard_duty_availability
        validate_guard_duty_availability(
            guard=target_guard,
            date=target_date,
            start_time=target_start,
            end_time=target_end,
            duty_type="EXAM",
            exclude_exam_id=serializer.instance.id,
            as_drf=True,
        )
        changed = {key: str(value) for key, value in serializer.validated_data.items()}
        duty = serializer.save()
        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=self.request.user,
            actor_username=self.request.user.username,
            target_model="ExamDuty",

            target_id=str(duty.id),
            details={"action": "EXAM_DUTY_UPDATED", "reference": duty.reference,
                     "old_guard_id": str(old_guard_id), "new_guard_id": str(duty.guard_id),
                     "changed_fields": changed},
        )
        if old_guard_id != duty.guard_id:
            Notification.objects.create(
                user=duty.guard, title="Exam Period Duty Reassigned",
                message=f"Exam duty {duty.reference} has been assigned to you.",
                notification_type="DUTY_ASSIGNMENT",
            )

    @action(detail=True, methods=["post"], url_path="acknowledge")
    def acknowledge(self, request, pk=None):
        """
        POST /exams/duties/{id}/acknowledge/
        Guard acknowledges receipt of assigned exam duty.
        """
        duty = self.get_object()
        if request.user.role == UserRole.GUARD and duty.guard_id != request.user.id:
            return Response({"detail": "You are not assigned to this exam duty."}, status=status.HTTP_403_FORBIDDEN)

        duty.status = ExamStatus.ACKNOWLEDGED
        duty.acknowledged_at = timezone.now()
        remarks = request.data.get("remarks", "").strip()
        if remarks:
            duty.remarks = (duty.remarks + f"\n[{timezone.now().strftime('%Y-%m-%d %H:%M')}] {request.user.username}: {remarks}").strip()
        duty.save()

        if duty.supervisor:
            Notification.objects.create(
                user=duty.supervisor,
                title="Exam Duty Acknowledged",
                message=f"Guard {request.user.get_full_name() or request.user.username} acknowledged exam duty {duty.reference} ({duty.exam_title}).",
                notification_type="OPERATIONAL_ALERT",
            )

        return Response(self.get_serializer(duty).data, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="update_status")
    def update_status(self, request, pk=None):
        """
        POST /exams/duties/{id}/update_status/
        Guard or supervisor updates operational status (ACKNOWLEDGED, IN_PROGRESS, COMPLETED, CANCELLED).
        """
        duty = self.get_object()
        if request.user.role == UserRole.GUARD and duty.guard_id != request.user.id:
            return Response({"detail": "You are not assigned to this exam duty."}, status=status.HTTP_403_FORBIDDEN)

        new_status = request.data.get("status")
        if not new_status or new_status not in ExamStatus.values:
            return Response({"detail": f"Invalid status '{new_status}'. Allowed: {', '.join(ExamStatus.values)}"}, status=status.HTTP_400_BAD_REQUEST)

        if new_status == ExamStatus.CANCELLED and request.user.role == UserRole.GUARD:
            return Response({"detail": "Guards cannot cancel exam duties."}, status=status.HTTP_403_FORBIDDEN)
        if request.user.role == UserRole.ADMINISTRATOR and new_status != ExamStatus.CANCELLED:
            return Response({"detail": "Administrators may cancel exam duties; operational progress is recorded by guards."}, status=status.HTTP_403_FORBIDDEN)

        now = timezone.now()
        duty.status = new_status
        if new_status == ExamStatus.ACKNOWLEDGED and not duty.acknowledged_at:
            duty.acknowledged_at = now

        remarks = request.data.get("remarks", "").strip()
        if remarks:
            duty.remarks = (duty.remarks + f"\n[{now.strftime('%Y-%m-%d %H:%M')}] {request.user.username}: {remarks}").strip()

        duty.save()

        SecurityAuditEvent.objects.create(
            event_type=SecurityAuditEvent.EventType.OVERRIDE if new_status == ExamStatus.CANCELLED else SecurityAuditEvent.EventType.RECORD_AMENDMENT,
            actor=request.user,
            actor_username=request.user.username,
            target_model="ExamDuty",
            target_id=str(duty.id),
            details={
                "action": "EXAM_STATUS_UPDATE",
                "reference": duty.reference,
                "status": new_status,
                "remarks": remarks,
            }
        )

        return Response(self.get_serializer(duty).data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="auto_allocate")
    def auto_allocate(self, request):
        """
        POST /exams/duties/auto_allocate/
        Assign non-duty guards to exam supervision based on paired lists or random selection.
        """
        date_str = request.data.get("date")
        start_time_str = request.data.get("start_time", "08:00:00")
        end_time_str = request.data.get("end_time", "12:00:00")
        institution = request.data.get("institution", "Designated Exam Center")
        exam_title = request.data.get("exam_title", "National Examination Supervision")
        strategy = request.data.get("strategy", "RANDOM").upper()  # RANDOM or PAIR
        count = int(request.data.get("count", 1))

        if not date_str:
            return Response({"detail": "date is required (YYYY-MM-DD)."}, status=status.HTTP_400_BAD_REQUEST)

        try:
            target_date = datetime.strptime(date_str, "%Y-%m-%d").date()
        except ValueError:
            return Response({"detail": "Invalid date format. Use YYYY-MM-DD."}, status=status.HTTP_400_BAD_REQUEST)

        # Non-duty guards: exclude guards with scheduled shifts, approved leave, or conflicting duties
        from apps.shifts.models import Shift, ShiftType, AssignmentType
        from apps.escorts.models import EscortDuty, EscortStatus
        from apps.leave.models import LeaveApplication, LeaveStatus

        scheduled_guards = Shift.objects.filter(date=target_date).exclude(
            assignment_type=AssignmentType.TIME_OFF
        ).exclude(shift_type=ShiftType.OFF).values_list("guard_id", flat=True)

        already_assigned = ExamDuty.objects.filter(date=target_date).exclude(
            status=ExamStatus.CANCELLED
        ).values_list("guard_id", flat=True)

        escort_assigned = EscortDuty.objects.filter(
            start_time__date__lte=target_date, end_time__date__gte=target_date
        ).exclude(status=EscortStatus.CANCELLED).values_list("guard_id", flat=True)

        on_leave_guards = LeaveApplication.objects.filter(
            status=LeaveStatus.APPROVED, start_date__lte=target_date, end_date__gte=target_date
        ).values_list("guard_id", flat=True)

        excluded_ids = set(scheduled_guards) | set(already_assigned) | set(escort_assigned) | set(on_leave_guards)

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
            # Pair or sequential
            selected = candidate_guards[:count]

        created_duties = []
        for g in selected:
            duty = ExamDuty.objects.create(
                guard=g,
                institution=institution,
                exam_title=exam_title,
                date=target_date,
                start_time=start_time_str,
                end_time=end_time_str,
                notes=f"[AUTO-ALLOCATED: Strategy={strategy}]",
            )
            created_duties.append(duty)

            Notification.objects.create(
                user=g,
                title="Exam Supervision Duty Assigned",
                message=f"You have been assigned to exam supervision duty: {exam_title} at {institution} on {target_date} ({start_time_str}-{end_time_str}).",
                notification_type="DUTY_ASSIGNMENT",
            )

        return Response({
            "message": f"Successfully allocated {len(created_duties)} non-duty guard(s) to exam supervision.",
            "strategy": strategy,
            "duties": ExamDutySerializer(created_duties, many=True).data,
        }, status=status.HTTP_201_CREATED)
