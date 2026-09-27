from datetime import date
from decimal import Decimal
from django.db import transaction
from django.utils import timezone
from django.core.exceptions import ValidationError, PermissionDenied
from django.contrib.auth import get_user_model
from apps.accounts.models import UserRole
from apps.shifts.models import Shift, AssignmentType
from apps.notifications.models import Notification
from .models import (
    LeaveBalance,
    LeaveApplication,
    LeaveStatus,
    LeaveType,
    LeaveAccrualRecord,
    PublicHolidayCompensationLedger,
    CompensationLedgerEntryType,
)
from .constants import (
    VACATION_ACCRUAL_RATE,
    CASUAL_ACCRUAL_RATE,
    MAX_VACATION_DAYS,
    MAX_CASUAL_DAYS_PER_CYCLE,
)

UserModel = get_user_model()


def get_completed_months(start_date, as_of_date=None):
    """
    Returns a list of (year, month) tuples that have formally completed
    between start_date and as_of_date.
    A month is completed ONLY after its final day has passed.
    E.g. If as_of_date is 2026-04-15, Month 4 is incomplete; completed are (2026, 1), (2026, 2), (2026, 3).
    """
    as_of = as_of_date or timezone.localdate()
    completed = []
    if as_of <= start_date:
        return completed

    cur_year = start_date.year
    cur_month = start_date.month

    while True:
        if cur_month == 12:
            next_month_start = date(cur_year + 1, 1, 1)
        else:
            next_month_start = date(cur_year, cur_month + 1, 1)

        if as_of >= next_month_start:
            completed.append((cur_year, cur_month))
            if cur_month == 12:
                cur_year += 1
                cur_month = 1
            else:
                cur_month += 1
        else:
            break

    return completed


def process_guard_accruals(guard, as_of_date=None, actor=None):
    """
    Idempotent server-side accrual processor for a single guard.
    Rules:
    - Calculates completed working months.
    - Credits 1.0 Casual day per completed month (12-day cycle cap, unused forfeited after 12 months).
    - Credits 2.5 Vacation days per completed month (90-day ceiling cap).
    - Never credits incomplete or future months.
    - Never duplicates a month already credited (tracked via LeaveAccrualRecord).
    - Database transactions ensure atomicity and concurrency safety.
    """
    as_of = as_of_date or timezone.localdate()

    with transaction.atomic():
        balance, _ = LeaveBalance.objects.select_for_update().get_or_create(
            guard=guard,
            year=as_of.year,
            defaults={"annual_days": 21, "sick_days": 14},
        )

        completed_months = get_completed_months(balance.casual_cycle_start, as_of)
        if not completed_months:
            return []

        existing = set(
            LeaveAccrualRecord.objects.filter(guard=guard).values_list("year", "month")
        )

        created_records = []
        mutated = False

        for y, m in completed_months:
            if (y, m) in existing:
                continue

            # 12-month forfeiture check for Casual Leave
            cycle_accruals = LeaveAccrualRecord.objects.filter(
                guard=guard,
                created_at__date__gte=balance.casual_cycle_start,
            ).count()

            if cycle_accruals >= 12:
                balance.casual_days = Decimal("0.0")
                balance.used_casual = Decimal("0.0")
                balance.casual_cycle_start = date(y, m, 1)

            # Casual entitlement (+1.0, max 12.0)
            new_casual = min(MAX_CASUAL_DAYS_PER_CYCLE, balance.casual_days + CASUAL_ACCRUAL_RATE)
            casual_credited = new_casual - balance.casual_days
            balance.casual_days = new_casual

            # Vacation entitlement (+2.5, max 90.0)
            new_vacation = min(balance.vacation_cap, balance.vacation_days + VACATION_ACCRUAL_RATE)
            vacation_credited = new_vacation - balance.vacation_days
            balance.vacation_days = new_vacation

            record = LeaveAccrualRecord.objects.create(
                guard=guard,
                year=y,
                month=m,
                casual_credited=casual_credited,
                vacation_credited=vacation_credited,
                casual_balance_after=balance.casual_days,
                vacation_balance_after=balance.vacation_days,
                created_by=actor,
                notes=f"Authoritative accrual for completed month {y}-{m:02d}.",
            )
            existing.add((y, m))
            created_records.append(record)
            balance.last_accrual_date = as_of
            mutated = True

        if mutated:
            balance.save(update_fields=[
                "casual_days", "vacation_days", "used_casual",
                "last_accrual_date", "casual_cycle_start",
            ])

        return created_records


def process_all_guards_accruals(as_of_date=None, actor=None):
    """
    Executes authoritative month-end accruals across all active guards.
    """
    guards = UserModel.objects.filter(role=UserRole.GUARD, is_active=True)
    all_created = []
    for guard in guards:
        created = process_guard_accruals(guard, as_of_date=as_of_date, actor=actor)
        all_created.extend(created)
    return all_created


def approve_leave_application(application, reviewer, reviewer_notes=""):
    """
    Formally approves a leave application under authoritative business rules.
    - Validates reviewer permissions and station scoping.
    - Enforces balance sufficiency for CASUAL, VACATION, and COMPENSATION.
    - Enforces doctor's report verification for SICK leave.
    - Enforces event documentation for SPECIAL leave.
    - Never creates negative balances.
    - Updates roster duty states and dispatches notifications.
    """
    if reviewer.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
        raise PermissionDenied("Only supervisors and administrators can approve leave.")

    if reviewer.role == UserRole.SUPERVISOR:
        if not reviewer.station_id or reviewer.station_id != application.guard.station_id:
            raise PermissionDenied("Supervisor cannot approve leave for a guard outside assigned station.")

    days = Decimal(str((application.end_date - application.start_date).days + 1))

    with transaction.atomic():
        balance, _ = LeaveBalance.objects.select_for_update().get_or_create(
            guard=application.guard,
            year=application.start_date.year,
            defaults={"annual_days": 21, "sick_days": 14},
        )

        leave_type = application.leave_type

        if leave_type == LeaveType.SICK:
            if not application.doctor_report.strip():
                raise ValidationError("A valid doctor's report / medical documentation is required to approve sick leave.")
            application.doctor_report_verified = True

        elif leave_type == LeaveType.SPECIAL:
            if not application.event_details.strip() and not application.reason.strip():
                raise ValidationError("Event documentation is mandatory for Special Leave approval.")

        elif leave_type == LeaveType.CASUAL:
            if Decimal(str(balance.remaining_casual)) < days:
                raise ValidationError(f"Insufficient casual leave balance. Requested: {days} days, Available: {balance.remaining_casual} days.")
            balance.used_casual += days
            balance.save(update_fields=["used_casual"])

        elif leave_type in [LeaveType.VACATION, LeaveType.ANNUAL]:
            if leave_type == LeaveType.ANNUAL and Decimal(str(balance.remaining_vacation)) < days:
                if Decimal(str(balance.remaining_annual)) < days:
                    raise ValidationError(f"Insufficient annual leave balance. Requested: {days} days, Available: {balance.remaining_annual} days.")
                balance.used_annual += int(days)
                balance.save(update_fields=["used_annual"])
            else:
                if Decimal(str(balance.remaining_vacation)) < days:
                    raise ValidationError(f"Insufficient vacation leave balance. Requested: {days} days, Available: {balance.remaining_vacation} days.")
                balance.used_vacation += days
                balance.save(update_fields=["used_vacation"])

        elif leave_type == LeaveType.COMPENSATION:
            remaining_comp = PublicHolidayCompensationLedger.get_remaining_for_guard(application.guard)
            if remaining_comp < days:
                raise ValidationError(f"Insufficient public holiday compensation balance. Requested: {days} days, Available: {remaining_comp} days.")
            PublicHolidayCompensationLedger.objects.get_or_create(
                leave_application=application,
                entry_type=CompensationLedgerEntryType.USED,
                defaults={
                    "guard": application.guard,
                    "days": days,
                    "created_by": reviewer,
                    "notes": f"Used {days} days compensation for approved leave ({application.start_date} to {application.end_date}).",
                },
            )

        application.status = LeaveStatus.APPROVED
        application.reviewer = reviewer
        application.reviewer_notes = reviewer_notes
        application.save()

        # Roster Synchronization: scheduled shifts during leave become TIME_OFF
        shifts = Shift.objects.filter(
            guard=application.guard,
            date__gte=application.start_date,
            date__lte=application.end_date,
        )
        for s in shifts:
            s.assignment_type = AssignmentType.TIME_OFF
            s.override_reason = f"Approved {application.get_leave_type_display()} ({application.start_date} to {application.end_date})"
            s.save(update_fields=["assignment_type", "override_reason"])

        # Notify Guard
        Notification.objects.create(
            user=application.guard,
            title="Leave Application Approved",
            message=f"Your {application.get_leave_type_display()} application ({application.start_date} to {application.end_date}) was approved by {reviewer.get_full_name() or reviewer.username}.",
            notification_type="LEAVE_DECISION",
        )

        # For Sick and Special leave, notify Administrators for national records
        if leave_type in [LeaveType.SICK, LeaveType.SPECIAL]:
            admins = UserModel.objects.filter(role=UserRole.ADMINISTRATOR, is_active=True)
            for admin in admins:
                Notification.objects.create(
                    user=admin,
                    title=f"Authoritative Record: Approved {application.get_leave_type_display()}",
                    message=(
                        f"Officer {application.guard.get_full_name() or application.guard.username} "
                        f"granted {days} days {application.get_leave_type_display()} "
                        f"({application.start_date} to {application.end_date}). "
                        f"Approved by {reviewer.get_full_name() or reviewer.username}."
                    ),
                    notification_type="LEAVE_AUDIT",
                )

        return application


def reject_leave_application(application, reviewer, rejection_reason, reviewer_notes=""):
    """
    Rejects a leave application with mandatory rejection reason.
    Does not deduct or alter balances.
    """
    if reviewer.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
        raise PermissionDenied("Only supervisors and administrators can reject leave.")

    if reviewer.role == UserRole.SUPERVISOR:
        if not reviewer.station_id or reviewer.station_id != application.guard.station_id:
            raise PermissionDenied("Supervisor cannot reject leave for a guard outside assigned station.")

    if not rejection_reason or not rejection_reason.strip():
        raise ValidationError("A structured rejection reason is mandatory when rejecting leave.")

    application.status = LeaveStatus.REJECTED
    application.reviewer = reviewer
    application.reviewer_notes = reviewer_notes
    application.rejection_reason = rejection_reason.strip()
    application.save(update_fields=["status", "reviewer", "reviewer_notes", "rejection_reason", "updated_at"])

    Notification.objects.create(
        user=application.guard,
        title="Leave Application Rejected",
        message=f"Your {application.get_leave_type_display()} application was rejected: {rejection_reason}. Notes: {reviewer_notes or 'None'}.",
        notification_type="LEAVE_DECISION",
    )

    return application
