import random
from datetime import datetime
from django.utils import timezone
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import EscortDuty
from .serializers import EscortDutySerializer
from apps.accounts.models import User, UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin
from apps.shifts.models import Shift
from apps.notifications.models import Notification

class EscortDutyViewSet(viewsets.ModelViewSet):
    queryset = EscortDuty.objects.all().select_related("guard")
    serializer_class = EscortDutySerializer
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

        # Non-duty guards: exclude guards with scheduled shifts on target_date
        scheduled_guards = Shift.objects.filter(date=target_date).values_list("guard_id", flat=True)
        already_assigned = EscortDuty.objects.filter(start_time__date=target_date).values_list("guard_id", flat=True)

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
