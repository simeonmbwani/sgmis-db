import random
from datetime import datetime
from django.utils import timezone
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import ExamDuty
from .serializers import ExamDutySerializer
from apps.accounts.models import User, UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin
from apps.shifts.models import Shift
from apps.notifications.models import Notification

class ExamDutyViewSet(viewsets.ModelViewSet):
    queryset = ExamDuty.objects.all().select_related("guard")
    serializer_class = ExamDutySerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        return qs

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "destroy", "auto_allocate"]:
            return [IsSupervisorOrAdmin()]
        return [IsAuthenticated()]

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
