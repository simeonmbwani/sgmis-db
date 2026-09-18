from decimal import Decimal
from django.shortcuts import get_object_or_404
from django.contrib.auth import get_user_model
from rest_framework import status, viewsets
from rest_framework.views import APIView
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.exceptions import PermissionDenied, ValidationError as DRFValidationError
from .models import (
    LeaveBalance,
    LeaveApplication,
    LeaveStatus,
    LeaveType,
    PublicHolidayCompensationLedger,
    CompensationLedgerEntryType,
)
from .serializers import (
    LeaveBalanceSerializer,
    LeaveApplicationSerializer,
    GuardLeaveSummarySerializer,
)
from apps.accounts.models import UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin

UserModel = get_user_model()


def build_guard_leave_summary(guard, year=2026):
    """
    Authoritative calculation of the 3 independent leave and compensation streams:
    1. Vacation Leave: Accrued (2.5/mo), Used, Remaining (capped at 90.0).
    2. Casual Leave: Accrued (1.0/mo), Used, Remaining (12-month cycle).
    3. Public Holiday Compensation: Earned, Used, Remaining (2 days per worked holiday, NO 90d cap).
    """
    balance, _ = LeaveBalance.objects.get_or_create(
        guard=guard,
        year=year,
        defaults={"annual_days": 21, "sick_days": 14},
    )
    balance.accrue_to_date()

    vacation_accrued = float(balance.vacation_days)
    vacation_used = float(balance.used_vacation)
    vacation_remaining = float(balance.remaining_vacation)

    casual_accrued = float(balance.casual_days)
    casual_used = float(balance.used_casual)
    casual_remaining = float(balance.remaining_casual)

    comp_earned = float(PublicHolidayCompensationLedger.get_total_earned_for_guard(guard))
    comp_used = float(PublicHolidayCompensationLedger.get_total_used_for_guard(guard))
    comp_remaining = float(PublicHolidayCompensationLedger.get_remaining_for_guard(guard))

    categories = [
        {
            "category": "Vacation Leave",
            "metric_label": "Accrued",
            "accrued_or_earned": vacation_accrued,
            "used": vacation_used,
            "remaining": vacation_remaining,
            "policy_note": "2.5 days/month • Max 90 days cap",
        },
        {
            "category": "Casual Leave",
            "metric_label": "Accrued",
            "accrued_or_earned": casual_accrued,
            "used": casual_used,
            "remaining": casual_remaining,
            "policy_note": "1.0 day/month • 12-month cycle",
        },
        {
            "category": "Public Holiday Compensation",
            "metric_label": "Earned",
            "accrued_or_earned": comp_earned,
            "used": comp_used,
            "remaining": comp_remaining,
            "policy_note": "2 days per worked public holiday • No cap",
        },
    ]

    return {
        "guard_id": guard.id,
        "guard_name": guard.get_full_name().strip() or guard.username,
        "guard_employee_number": getattr(guard, "employee_number", "") or "",
        "year": year,
        "categories": categories,
        "vacation": {
            "accrued": vacation_accrued,
            "used": vacation_used,
            "remaining": vacation_remaining,
            "cap": float(balance.vacation_cap),
            "accrual_rate": float(balance.vacation_accrual_rate),
        },
        "casual": {
            "accrued": casual_accrued,
            "used": casual_used,
            "remaining": casual_remaining,
            "cap": 12.0,
            "accrual_rate": float(balance.casual_accrual_rate),
        },
        "compensation": {
            "earned": comp_earned,
            "used": comp_used,
            "remaining": comp_remaining,
        },
    }


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

    @action(detail=False, methods=["get"], url_path="my-summary")
    def my_summary(self, request):
        """
        GET /leave/balances/my-summary/
        Returns the authenticated guard's own 3-stream summary table.
        """
        year = int(request.query_params.get("year", 2026))
        summary_data = build_guard_leave_summary(request.user, year=year)
        serializer = GuardLeaveSummarySerializer(summary_data)
        return Response(serializer.data, status=status.HTTP_200_OK)

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
                    balance.save(update_fields=["used_casual"])
                elif application.leave_type == "VACATION":
                    balance.used_vacation += days
                    balance.save(update_fields=["used_vacation"])
                elif application.leave_type == "ANNUAL":
                    balance.used_annual += days
                    balance.save(update_fields=["used_annual"])
                elif application.leave_type == "SICK":
                    balance.used_sick += days
                    balance.save(update_fields=["used_sick"])
                elif application.leave_type == "COMPENSATION":
                    remaining_comp = PublicHolidayCompensationLedger.get_remaining_for_guard(application.guard)
                    if Decimal(str(days)) > remaining_comp:
                        return Response(
                            {"detail": f"Insufficient public holiday compensation balance. Requested: {days} days, Remaining: {remaining_comp} days."},
                            status=status.HTTP_400_BAD_REQUEST,
                        )
                    PublicHolidayCompensationLedger.objects.get_or_create(
                        leave_application=application,
                        entry_type=CompensationLedgerEntryType.USED,
                        defaults={
                            "guard": application.guard,
                            "days": Decimal(str(days)),
                            "created_by": request.user,
                            "notes": f"Used {days} days compensation for approved leave from {application.start_date} to {application.end_date}.",
                        },
                    )

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


class GuardLeaveSummaryView(APIView):
    """
    Authoritative read-only Leave & Compensation Summary endpoint.
    GET /leave/my-summary/
    Rules:
    - Authenticated users only.
    - Uses authenticated JWT identity: request.user.
    - Guards cannot supply another guard's ID to override identity (403 Forbidden).
    - Supervisors/Admins can view guards within station scope.
    - Client cannot submit or modify balances (strictly read-only).
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        user = request.user
        guard_param = request.query_params.get("guard") or request.query_params.get("guard_id")

        if guard_param:
            if user.role == UserRole.GUARD:
                if str(user.id) != str(guard_param):
                    raise PermissionDenied("Guards cannot request another guard's summary.")
                target_guard = user
            elif user.role == UserRole.SUPERVISOR:
                target_guard = get_object_or_404(UserModel, id=guard_param)
                if not user.station_id or user.station_id != target_guard.station_id:
                    raise PermissionDenied("Supervisor cannot view leave summary for a guard outside assigned station.")
            else:
                target_guard = get_object_or_404(UserModel, id=guard_param)
        else:
            target_guard = user

        year = int(request.query_params.get("year", 2026))
        summary_data = build_guard_leave_summary(target_guard, year=year)
        serializer = GuardLeaveSummarySerializer(summary_data)
        return Response(serializer.data, status=status.HTTP_200_OK)


class PublicHolidayCompensationLedgerViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Read-only viewset for auditing the public holiday compensation ledger.
    Guards see only their own ledger entries.
    Supervisors see station guard entries.
    Administrators have global visibility.
    """
    queryset = PublicHolidayCompensationLedger.objects.all().select_related(
        "guard", "duty_record", "leave_application", "created_by"
    )
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station_id:
            return qs.filter(guard__station_id=user.station_id)
        return qs
