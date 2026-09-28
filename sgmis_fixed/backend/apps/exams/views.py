import random
from datetime import datetime
from django.utils import timezone
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import ExamDuty, ExamStatus
from .serializers import ExamDutySerializer
from apps.accounts.models import User, UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin, IsAdministrator
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
        if self.action in ["destroy"]:
            return [IsAdministrator()]
        if self.action in ["create", "update", "partial_update", "auto_allocate"]:
            return [IsSupervisorOrAdmin()]
        return [IsAuthenticated()]

    def perform_create(self, serializer):
        user = self.request.user
        supervisor = serializer.validated_data.get("supervisor") or (user if user.role in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR] else None)
        station = serializer.validated_data.get("station") or (user.station if user.station else None)
        duty = serializer.save(supervisor=supervisor, station=station)

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

        # Non-duty guards: exclude guards with scheduled shifts on target_date
        scheduled_guards = Shift.objects.filter(date=target_date).values_list("guard_id", flat=True)
        already_assigned = ExamDuty.objects.filter(date=target_date).values_list("guard_id", flat=True)

        candidate_guards = list(
            User.objects.filter(role=UserRole.GUARD, is_active=True)
            .exclude(id__in=scheduled_guards)
            .exclude(id__in=already_assigned)
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
