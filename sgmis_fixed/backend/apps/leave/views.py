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
        balance.accrue_to_date()
        return Response(self.get_serializer(balance).data)

    @action(detail=True, methods=["post"], url_path="credit_holiday", permission_classes=[IsSupervisorOrAdmin])
    def credit_holiday(self, request, pk=None):
        balance = self.get_object()
        days = float(request.data.get("days", 2.0))
        balance.credit_public_holiday_duty(days=days)
        return Response(self.get_serializer(balance).data, status=status.HTTP_200_OK)

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
        application = serializer.save(guard=self.request.user)
        try:
            from apps.accounts.models import User, UserRole
            from apps.notifications.models import Notification
            admins = User.objects.filter(role=UserRole.ADMINISTRATOR, is_active=True)
            for admin in admins:
                Notification.objects.create(
                    user=admin,
                    title=f"Leave Request: {self.request.user.get_full_name() or self.request.user.username}",
                    message=f"New leave application for {application.get_leave_type_display()} ({application.start_date} to {application.end_date}) routed to Administration.",
                    notification_type="LEAVE_REQUEST",
                )
        except Exception:
            pass

    @action(detail=True, methods=["post"], url_path="review", permission_classes=[IsSupervisorOrAdmin])
    def review(self, request, pk=None):
        from django.db import transaction

        application = self.get_object()
        new_status = request.data.get("status")
        notes = request.data.get("reviewer_notes", "")
        allowed = [LeaveStatus.APPROVED, LeaveStatus.REJECTED, LeaveStatus.CHANGES_REQUESTED]
        if new_status not in allowed:
            return Response({"detail": f"Invalid status '{new_status}'. Allowed: APPROVED, REJECTED, CHANGES_REQUESTED."}, status=status.HTTP_400_BAD_REQUEST)

        rejection_reason = request.data.get("rejection_reason", "").strip()
        if new_status == LeaveStatus.REJECTED and not rejection_reason:
            return Response(
                {"detail": "A structured rejection reason (e.g., Manpower shortage, Critical Schedule, Insufficient days, Special Upcoming functions) is mandatory when rejecting leave."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        with transaction.atomic():
            if new_status == LeaveStatus.APPROVED:
                days = (application.end_date - application.start_date).days + 1
                balance, _ = LeaveBalance.objects.get_or_create(
                    guard=application.guard,
                    year=application.start_date.year,
                    defaults={"annual_days": 21, "sick_days": 14},
                )
                balance.accrue_to_date(application.start_date)
                if application.leave_type == "CASUAL" and balance.remaining_casual < days:
                    return Response({"detail": "Insufficient casual leave balance."}, status=status.HTTP_400_BAD_REQUEST)
                if application.leave_type == "VACATION" and balance.remaining_vacation < days:
                    return Response({"detail": "Insufficient vacation leave balance."}, status=status.HTTP_400_BAD_REQUEST)
                if application.leave_type == "ANNUAL" and balance.remaining_annual < days:
                    return Response({"detail": "Insufficient legacy annual leave balance."}, status=status.HTTP_400_BAD_REQUEST)
                if application.leave_type == "SICK" and balance.remaining_sick < days:
                    return Response({"detail": "Insufficient sick leave balance."}, status=status.HTTP_400_BAD_REQUEST)

                if application.leave_type == "CASUAL":
                    balance.used_casual += days
                elif application.leave_type == "VACATION":
                    balance.used_vacation += days
                elif application.leave_type == "ANNUAL":
                    balance.used_annual += days
                elif application.leave_type == "SICK":
                    balance.used_sick += days
                balance.save()

            application.status = new_status
            application.reviewer = request.user
            application.reviewer_notes = notes
            if new_status == LeaveStatus.REJECTED:
                application.rejection_reason = rejection_reason
            application.save()

            try:
                from apps.notifications.models import Notification
                Notification.objects.create(
                    user=application.guard,
                    title=f"Leave Application {application.status.capitalize()}",
                    message=f"Your {application.get_leave_type_display()} request has been {application.status.lower()} by {request.user.get_full_name() or request.user.username}. Notes: {notes or 'No notes provided.'}",
                    notification_type="LEAVE_DECISION",
                )
            except Exception:
                pass

        return Response(self.get_serializer(application).data, status=status.HTTP_200_OK)
