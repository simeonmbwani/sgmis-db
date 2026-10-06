from decimal import Decimal
from django.shortcuts import get_object_or_404
from django.contrib.auth import get_user_model
from django.core.exceptions import ValidationError as DjangoValidationError
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
    LeaveAccrualRecord,
    PublicHolidayCompensationLedger,
    CompensationLedgerEntryType,
    AdjustmentType,
    LeaveAdjustmentRecord,
)
from .serializers import (
    LeaveBalanceSerializer,
    LeaveApplicationSerializer,
    LeaveAccrualRecordSerializer,
    GuardLeaveSummarySerializer,
    PublicHolidayCompensationLedgerSerializer,
    LeaveAdjustmentRecordSerializer,
)
from .services import (
    process_guard_accruals,
    process_all_guards_accruals,
    approve_leave_application,
    reject_leave_application,
)
from apps.accounts.models import UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin, IsAdministrator

UserModel = get_user_model()


def build_guard_leave_summary(guard, year=2026):
    """
    Authoritative calculation of the 3 independent leave and compensation streams:
    1. Vacation Leave: Accrued (2.5/mo), Used, Remaining (capped at 90.0).
    2. Casual Leave: Accrued (1.0/mo), Used, Remaining (12-month cycle).
    3. Public Holiday Compensation: Earned, Used, Remaining (2 days per worked holiday, NO 90d cap).

    STRICT READ-ONLY: Does NOT mutate or trigger accruals.
    """
    balance = LeaveBalance.objects.filter(guard=guard, year=year).first()
    if not balance:
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=guard,
            year=year,
            defaults={"annual_days": 21, "sick_days": 14},
        )

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
        station_id = self.request.query_params.get("station")

        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station:
                return qs.filter(guard__station=user.station)
            return qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            if station_id:
                return qs.filter(guard__station_id=station_id)
            return qs
        return qs

    @action(detail=False, methods=["get"], url_path="my_balance")
    def my_balance(self, request):
        balance, _ = LeaveBalance.objects.get_or_create(
            guard=request.user,
            year=2026,
            defaults={"annual_days": 21, "sick_days": 14}
        )
        return Response(self.get_serializer(balance).data)

    @action(detail=False, methods=["get"], url_path="my-summary")
    def my_summary_hyphen(self, request):
        """GET /leave/balances/my-summary/"""
        year = int(request.query_params.get("year", 2026))
        summary_data = build_guard_leave_summary(request.user, year=year)
        serializer = GuardLeaveSummarySerializer(summary_data)
        return Response(serializer.data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["get"], url_path="my_summary")
    def my_summary_underscore(self, request):
        """GET /leave/balances/my_summary/"""
        return self.my_summary_hyphen(request)

    @action(detail=False, methods=["post"], url_path="process-accruals", permission_classes=[IsSupervisorOrAdmin])
    def process_accruals_hyphen(self, request):
        """
        POST /leave/balances/process-accruals/
        Explicit, permission-controlled, idempotent accrual execution.
        """
        from datetime import datetime
        guard_id = request.data.get("guard_id") or request.data.get("guard")
        as_of_str = request.data.get("as_of_date")
        as_of = datetime.strptime(as_of_str, "%Y-%m-%d").date() if as_of_str else None

        if guard_id:
            guard = get_object_or_404(UserModel, id=guard_id)
            if request.user.role == UserRole.SUPERVISOR:
                if not request.user.station_id or request.user.station_id != guard.station_id:
                    raise PermissionDenied("Supervisor can only process accruals for assigned station guards.")
            records = process_guard_accruals(guard, as_of_date=as_of, actor=request.user)
        else:
            if request.user.role != UserRole.ADMINISTRATOR:
                raise PermissionDenied("Only administrators can trigger mass accrual processing.")
            records = process_all_guards_accruals(as_of_date=as_of, actor=request.user)

        return Response({
            "message": f"Successfully processed accruals. {len(records)} month(s) credited.",
            "records_count": len(records),
            "records": LeaveAccrualRecordSerializer(records, many=True).data,
        }, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="process_accruals", permission_classes=[IsSupervisorOrAdmin])
    def process_accruals_underscore(self, request):
        """POST /leave/balances/process_accruals/"""
        return self.process_accruals_hyphen(request)

    @action(detail=True, methods=["post"], url_path="credit_holiday", permission_classes=[IsSupervisorOrAdmin])
    def credit_holiday(self, request, pk=None):
        balance = self.get_object()
        days = float(request.data.get("days", 2.0))
        balance.credit_public_holiday_duty(days=days)
        return Response(self.get_serializer(balance).data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="set_opening_balance", permission_classes=[IsAdministrator])
    def set_opening_balance(self, request):
        """
        POST /leave/balances/set_opening_balance/
        Administrative endpoint for Superusers to establish verified opening/current leave balances
        from physical organisational ledgers.
        Does NOT alter historical accruals or attendance prior to effective_date.
        """
        from datetime import datetime
        guard_id = request.data.get("guard_id") or request.data.get("guard")
        if not guard_id:
            return Response({"detail": "guard_id is required."}, status=status.HTTP_400_BAD_REQUEST)

        guard = get_object_or_404(UserModel, id=guard_id)
        effective_date_str = request.data.get("effective_date")
        if not effective_date_str:
            return Response({"detail": "effective_date is required (YYYY-MM-DD)."}, status=status.HTTP_400_BAD_REQUEST)
        try:
            effective_date = datetime.strptime(effective_date_str, "%Y-%m-%d").date()
        except ValueError:
            return Response({"detail": "Invalid effective_date format. Use YYYY-MM-DD."}, status=status.HTTP_400_BAD_REQUEST)

        source = request.data.get("source", "Verified physical organisation ledger").strip()
        reason = request.data.get("reason", "").strip()
        if not reason:
            return Response({"detail": "Mandatory operational reason / verification citation is required."}, status=status.HTTP_400_BAD_REQUEST)

        balance, _ = LeaveBalance.objects.get_or_create(guard=guard, year=effective_date.year)
        records_created = []

        if "vacation_balance" in request.data:
            vac_val = Decimal(str(request.data["vacation_balance"]))
            prev = balance.vacation_days
            balance.vacation_days = vac_val
            balance.opening_vacation_balance = vac_val
            balance.opening_balance_date = effective_date
            balance.opening_balance_source = source
            balance.opening_balance_verified_by = request.user
            rec = LeaveAdjustmentRecord.objects.create(
                guard=guard,
                adjustment_type=AdjustmentType.OPENING_BALANCE,
                leave_type="VACATION",
                previous_balance=prev,
                new_balance=vac_val,
                effective_date=effective_date,
                source=source,
                reason=reason,
                authorized_by=request.user,
            )
            records_created.append(rec)

        if "casual_balance" in request.data:
            cas_val = Decimal(str(request.data["casual_balance"]))
            prev = balance.casual_days
            balance.casual_days = cas_val
            balance.opening_casual_balance = cas_val
            balance.opening_balance_date = effective_date
            balance.opening_balance_source = source
            balance.opening_balance_verified_by = request.user
            rec = LeaveAdjustmentRecord.objects.create(
                guard=guard,
                adjustment_type=AdjustmentType.OPENING_BALANCE,
                leave_type="CASUAL",
                previous_balance=prev,
                new_balance=cas_val,
                effective_date=effective_date,
                source=source,
                reason=reason,
                authorized_by=request.user,
            )
            records_created.append(rec)

        if "compensation_balance" in request.data:
            from apps.leave.models import PublicHolidayCompensationLedger, CompensationLedgerEntryType
            comp_val = Decimal(str(request.data["compensation_balance"]))
            rem = PublicHolidayCompensationLedger.get_remaining_for_guard(guard)
            diff = comp_val - rem
            if diff != Decimal("0.0"):
                PublicHolidayCompensationLedger.objects.create(
                    guard=guard,
                    entry_type=CompensationLedgerEntryType.EARNED if diff > 0 else CompensationLedgerEntryType.USED,
                    days=abs(diff),
                    notes=f"Opening compensation balance adjustment: {reason}",
                    created_by=request.user,
                )

        balance.save()

        return Response({
            "message": f"Successfully set verified opening balance for {guard.username} effective {effective_date}.",
            "balance": LeaveBalanceSerializer(balance).data,
            "adjustments": LeaveAdjustmentRecordSerializer(records_created, many=True).data,
        }, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="set-opening-balance", permission_classes=[IsAdministrator])
    def set_opening_balance_hyphen(self, request):
        return self.set_opening_balance(request)


class LeaveApplicationViewSet(viewsets.ModelViewSet):
    queryset = LeaveApplication.objects.all().select_related("guard", "reviewer")
    serializer_class = LeaveApplicationSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        station_id = self.request.query_params.get("station")

        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station:
                return qs.filter(guard__station=user.station)
            return qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            if station_id:
                return qs.filter(guard__station_id=station_id)
            return qs
        return qs

    def perform_create(self, serializer):
        application = serializer.save(guard=self.request.user)
        try:
            from apps.accounts.models import User, UserRole
            from apps.notifications.models import Notification

            # Notify station supervisors if assigned
            guard_station = self.request.user.station
            if guard_station:
                supervisors = User.objects.filter(
                    role=UserRole.SUPERVISOR,
                    station=guard_station,
                    is_active=True,
                )
                for sup in supervisors:
                    Notification.objects.create(
                        user=sup,
                        title=f"Leave Request: {self.request.user.get_full_name() or self.request.user.username}",
                        message=f"New leave application for {application.get_leave_type_display()} ({application.start_date} to {application.end_date}) submitted for review.",
                        notification_type="LEAVE_REQUEST",
                    )

            # Alert administrators for national leave oversight
            admins = User.objects.filter(role=UserRole.ADMINISTRATOR, is_active=True)
            for admin in admins:
                Notification.objects.create(
                    user=admin,
                    title=f"Leave Request: {self.request.user.get_full_name() or self.request.user.username}",
                    message=f"New leave application for {application.get_leave_type_display()} ({application.start_date} to {application.end_date}) submitted.",
                    notification_type="LEAVE_REQUEST",
                )
        except Exception:
            pass

    @action(detail=True, methods=["post"], url_path="review", permission_classes=[IsSupervisorOrAdmin])
    def review(self, request, pk=None):
        application = self.get_object()
        new_status = request.data.get("status")
        notes = request.data.get("reviewer_notes", "")
        allowed = [LeaveStatus.APPROVED, LeaveStatus.REJECTED, LeaveStatus.CHANGES_REQUESTED]
        if new_status not in allowed:
            return Response(
                {"detail": f"Invalid status '{new_status}'. Allowed: APPROVED, REJECTED, CHANGES_REQUESTED."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        rejection_reason = request.data.get("rejection_reason", "").strip()

        try:
            if new_status == LeaveStatus.APPROVED:
                updated = approve_leave_application(
                    application=application,
                    reviewer=request.user,
                    reviewer_notes=notes,
                )
            elif new_status == LeaveStatus.REJECTED:
                updated = reject_leave_application(
                    application=application,
                    reviewer=request.user,
                    rejection_reason=rejection_reason,
                    reviewer_notes=notes,
                )
            else:
                application.status = LeaveStatus.CHANGES_REQUESTED
                application.reviewer = request.user
                application.reviewer_notes = notes
                application.save()
                updated = application

            return Response(self.get_serializer(updated).data, status=status.HTTP_200_OK)
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)
        except PermissionDenied as exc:
            return Response({"detail": str(exc)}, status=status.HTTP_403_FORBIDDEN)


class LeaveAccrualRecordViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Authoritative read-only audit log of monthly leave accruals.
    """
    queryset = LeaveAccrualRecord.objects.all().select_related("guard", "created_by")
    serializer_class = LeaveAccrualRecordSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        guard_id = self.request.query_params.get("guard")
        station_id = self.request.query_params.get("station")

        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station_id:
                return qs.filter(guard__station_id=user.station_id)
            return qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            if guard_id:
                qs = qs.filter(guard_id=guard_id)
            if station_id:
                qs = qs.filter(guard__station_id=station_id)
            return qs
        return qs


class GuardLeaveSummaryView(APIView):
    """
    Authoritative read-only Leave & Compensation Summary endpoint.
    GET /leave/my-summary/
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
    """
    queryset = PublicHolidayCompensationLedger.objects.all().select_related(
        "guard", "duty_record", "leave_application", "created_by"
    )
    serializer_class = PublicHolidayCompensationLedgerSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station_id:
            return qs.filter(guard__station_id=user.station_id)
        return qs


class LeaveAdjustmentRecordViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Authoritative read-only audit log of verified opening balance adjustments and reconciliations.
    """
    queryset = LeaveAdjustmentRecord.objects.all().select_related("guard", "authorized_by")
    serializer_class = LeaveAdjustmentRecordSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        guard_id = self.request.query_params.get("guard")
        station_id = self.request.query_params.get("station")

        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station_id:
                return qs.filter(guard__station_id=user.station_id)
            return qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            if guard_id:
                qs = qs.filter(guard_id=guard_id)
            if station_id:
                qs = qs.filter(guard__station_id=station_id)
            return qs
        return qs

