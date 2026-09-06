from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import LeaveBalance, LeaveApplication, LeaveStatus
from .serializers import LeaveBalanceSerializer, LeaveApplicationSerializer
from apps.accounts.models import UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin

class LeaveBalanceViewSet(viewsets.ReadOnlyModelViewSet):
    queryset = LeaveBalance.objects.all().select_related("guard")
    serializer_class = LeaveBalanceSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        return qs

    @action(detail=False, methods=["get"], url_path="my_balance")
    def my_balance(self, request):
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=request.user,
            year=2026,
            defaults={"annual_days": 21, "sick_days": 14}
        )
        return Response(self.get_serializer(balance).data)

class LeaveApplicationViewSet(viewsets.ModelViewSet):
    queryset = LeaveApplication.objects.all().select_related("guard", "reviewer")
    serializer_class = LeaveApplicationSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station:
            return qs.filter(guard__station=user.station)
        return qs

    def perform_create(self, serializer):
        serializer.save(guard=self.request.user)

    @action(detail=True, methods=["post"], url_path="review", permission_classes=[IsSupervisorOrAdmin])
    def review(self, request, pk=None):
        application = self.get_object()
        new_status = request.data.get("status")
        notes = request.data.get("reviewer_notes", "")

        if new_status not in [LeaveStatus.APPROVED, LeaveStatus.REJECTED, LeaveStatus.CHANGES_REQUESTED]:
            return Response(
                {"detail": f"Invalid status '{new_status}'. Allowed: APPROVED, REJECTED, CHANGES_REQUESTED."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        application.status = new_status
        application.reviewer = request.user
        application.reviewer_notes = notes
        application.save()

        # If approved, deduct from balance
        if new_status == LeaveStatus.APPROVED:
            days = max(1, (application.end_date - application.start_date).days + 1)
            balance, _ = LeaveBalance.objects.get_or_create(guard=application.guard, year=application.start_date.year)
            if application.leave_type == "ANNUAL":
                balance.used_annual += days
            elif application.leave_type == "SICK":
                balance.used_sick += days
            balance.save()

        return Response(self.get_serializer(application).data, status=status.HTTP_200_OK)
