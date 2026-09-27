import uuid
from datetime import date
from decimal import Decimal
from django.db import models
from django.conf import settings

class LeaveType(models.TextChoices):
    CASUAL = "CASUAL", "Casual Leave"
    VACATION = "VACATION", "Vacation Leave"
    COMPENSATION = "COMPENSATION", "Public Holiday Compensation"
    SPECIAL = "SPECIAL", "Special Leave"
    SICK = "SICK", "Sick Leave"
    # ANNUAL is retained for backward compatibility with existing records/clients.
    ANNUAL = "ANNUAL", "Vacation Leave (Legacy Annual)"
    EMERGENCY = "EMERGENCY", "Emergency Leave"
    COMPASSIONATE = "COMPASSIONATE", "Compassionate Leave"

class LeaveStatus(models.TextChoices):
    PENDING = "PENDING", "Pending Review"
    APPROVED = "APPROVED", "Approved"
    REJECTED = "REJECTED", "Rejected"
    CHANGES_REQUESTED = "CHANGES_REQUESTED", "Changes Requested"

class LeaveBalance(models.Model):
    """Leave ledger using the organisation's authoritative monthly accrual rules."""
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="leave_balances")
    year = models.PositiveIntegerField(default=2026)

    # Legacy fields retained so old clients/data continue to work.
    annual_days = models.PositiveIntegerField(default=21)
    sick_days = models.PositiveIntegerField(default=14)
    used_annual = models.PositiveIntegerField(default=0)
    used_sick = models.PositiveIntegerField(default=0)

    # Official SGMIS policy: casual 1 day/month, vacation 2.5 days/month, vacation cap 90 days.
    casual_days = models.DecimalField(max_digits=6, decimal_places=1, default=0)
    vacation_days = models.DecimalField(max_digits=6, decimal_places=1, default=0)
    used_casual = models.DecimalField(max_digits=6, decimal_places=1, default=0)
    used_vacation = models.DecimalField(max_digits=6, decimal_places=1, default=0)
    casual_accrual_rate = models.DecimalField(max_digits=4, decimal_places=1, default=1.0)
    vacation_accrual_rate = models.DecimalField(max_digits=4, decimal_places=1, default=2.5)
    vacation_cap = models.DecimalField(max_digits=6, decimal_places=1, default=90.0)
    last_accrual_date = models.DateField(default=date(2026, 1, 1))
    casual_cycle_start = models.DateField(default=date(2026, 1, 1))

    class Meta:
        unique_together = ("guard", "year")

    @staticmethod
    def _completed_months_between(start, end):
        """
        Determines the list of (year, month) tuples that have formally completed
        between start date and end date. A month is only completed once its last day
        has passed (i.e. end >= first day of the subsequent month).
        """
        completed = []
        if end <= start:
            return completed

        cur_year = start.year
        cur_month = start.month

        while True:
            # First day of the month after cur_month
            if cur_month == 12:
                next_month_start = date(cur_year + 1, 1, 1)
            else:
                next_month_start = date(cur_year, cur_month + 1, 1)

            # If end date is on or after next_month_start, cur_month is completed
            if end >= next_month_start:
                completed.append((cur_year, cur_month))
                if cur_month == 12:
                    cur_year += 1
                    cur_month = 1
                else:
                    cur_month += 1
            else:
                break

        return completed

    def accrue_to_date(self, as_of=None, save=True, created_by=None):
        """
        Accrues completed months strictly after month-end has passed.
        Never accrues incomplete or future months.
        Guarantees idempotency via LeaveAccrualRecord.
        """
        as_of = as_of or date.today()
        if as_of <= self.casual_cycle_start:
            return self

        # Determine all completed months since casual_cycle_start up to as_of
        completed_months = self._completed_months_between(self.casual_cycle_start, as_of)
        if not completed_months:
            return self

        # Query existing accrual records for this guard to guarantee idempotency
        existing_records = set(
            LeaveAccrualRecord.objects.filter(guard=self.guard).values_list("year", "month")
        )

        mutated = False
        for y, m in completed_months:
            if (y, m) in existing_records:
                continue

            # Check 12-month casual forfeiture cycle
            # Count how many casual accruals in current cycle
            cycle_accruals = LeaveAccrualRecord.objects.filter(
                guard=self.guard,
                created_at__date__gte=self.casual_cycle_start
            ).count()

            if cycle_accruals >= 12:
                # 12-month forfeiture: unused casual entitlement resets
                self.casual_days = Decimal("0.0")
                self.used_casual = Decimal("0.0")
                self.casual_cycle_start = date(y, m, 1)

            current_casual = Decimal(str(self.casual_days or 0))
            current_vacation = Decimal(str(self.vacation_days or 0))

            # Casual leave: 1.0 day per completed month, capped at 12 per cycle
            new_casual = min(Decimal("12.0"), current_casual + Decimal(str(self.casual_accrual_rate)))
            casual_credited = new_casual - current_casual
            self.casual_days = new_casual

            # Vacation leave: 2.5 days per completed month, capped at 90.0
            new_vacation = min(Decimal(str(self.vacation_cap)), current_vacation + Decimal(str(self.vacation_accrual_rate)))
            vacation_credited = new_vacation - current_vacation
            self.vacation_days = new_vacation

            # Record accrual ledger
            LeaveAccrualRecord.objects.create(
                guard=self.guard,
                year=y,
                month=m,
                casual_credited=casual_credited,
                vacation_credited=vacation_credited,
                casual_balance_after=self.casual_days,
                vacation_balance_after=self.vacation_days,
                created_by=created_by,
                notes=f"Completed month {y}-{m:02d} accrual.",
            )
            existing_records.add((y, m))
            self.last_accrual_date = as_of
            mutated = True

        if mutated and save:
            self.save(update_fields=["casual_days", "vacation_days", "used_casual", "last_accrual_date", "casual_cycle_start"])

        return self

    @property
    def remaining_annual(self):
        return max(0, self.annual_days - self.used_annual)

    @property
    def remaining_sick(self):
        return max(0, self.sick_days - self.used_sick)

    @property
    def remaining_casual(self):
        return max(0.0, float(self.casual_days) - float(self.used_casual))

    @property
    def remaining_vacation(self):
        return max(0.0, float(self.vacation_days) - float(self.used_vacation))

    @property
    def compensation_earned(self):
        return float(PublicHolidayCompensationLedger.get_total_earned_for_guard(self.guard))

    @property
    def compensation_used(self):
        return float(PublicHolidayCompensationLedger.get_total_used_for_guard(self.guard))

    @property
    def remaining_compensation(self):
        return float(PublicHolidayCompensationLedger.get_remaining_for_guard(self.guard))

    def credit_public_holiday_duty(self, days=2.0, save=True, created_by=None, notes=None):
        """
        Maintains separation of concern: Public holiday compensation is tracked
        in PublicHolidayCompensationLedger, NOT by modifying vacation balance.
        """
        PublicHolidayCompensationLedger.objects.create(
            guard=self.guard,
            entry_type=CompensationLedgerEntryType.EARNED,
            days=Decimal(str(days)),
            created_by=created_by,
            notes=notes or "Credit for worked public holiday duty.",
        )
        return self

    def __str__(self):
        return f"{self.guard.username} ({self.year}) Leave Balance"


class LeaveAccrualRecord(models.Model):
    """
    Authoritative, immutable transaction log of monthly accruals.
    Enforces:
    - 1.0 day casual leave per completed working month.
    - 2.5 days vacation leave per completed working month (capped at 90 days).
    - Absolute idempotency: (guard, year, month) is unique.
    - Never accrues incomplete or future months.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="accrual_records",
    )
    year = models.PositiveIntegerField(db_index=True)
    month = models.PositiveIntegerField(db_index=True)
    casual_credited = models.DecimalField(max_digits=4, decimal_places=1, default=1.0)
    vacation_credited = models.DecimalField(max_digits=4, decimal_places=1, default=2.5)
    casual_balance_after = models.DecimalField(max_digits=6, decimal_places=1)
    vacation_balance_after = models.DecimalField(max_digits=6, decimal_places=1)
    created_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="processed_accruals",
    )
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)
    notes = models.TextField(blank=True, default="")

    class Meta:
        ordering = ["-year", "-month"]
        constraints = [
            models.UniqueConstraint(
                fields=["guard", "year", "month"],
                name="unique_accrual_record_per_guard_month",
            )
        ]

    def __str__(self):
        return f"{self.guard.username} - {self.year}-{self.month:02d} Accrual (+{self.casual_credited} C, +{self.vacation_credited} V)"


class LeaveRejectionReason(models.TextChoices):
    MANPOWER_SHORTAGE = "MANPOWER_SHORTAGE", "Manpower shortage"
    CRITICAL_SCHEDULE = "CRITICAL_SCHEDULE", "Critical Schedule"
    INSUFFICIENT_DAYS = "INSUFFICIENT_DAYS", "Insufficient days"
    SPECIAL_FUNCTIONS = "SPECIAL_FUNCTIONS", "Special Upcoming functions"
    OTHER = "OTHER", "Other operational grounds"


class LeaveApplication(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="leave_applications")
    leave_type = models.CharField(max_length=20, choices=LeaveType.choices, default=LeaveType.VACATION)
    start_date = models.DateField()
    end_date = models.DateField()
    reason = models.TextField()
    emergency_phone = models.CharField(max_length=50, blank=True, default="")
    emergency_address = models.TextField(blank=True, default="")
    doctor_report = models.TextField(blank=True, default="", help_text="Medical certificate or doctor report details for Sick Leave")
    doctor_report_verified = models.BooleanField(default=False, help_text="Verified by reviewing supervisor")
    event_details = models.TextField(blank=True, default="", help_text="Event justification for Special Leave")
    status = models.CharField(max_length=20, choices=LeaveStatus.choices, default=LeaveStatus.PENDING, db_index=True)
    reviewer = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="reviewed_leaves")
    reviewer_notes = models.TextField(blank=True, default="")
    rejection_reason = models.CharField(max_length=50, choices=LeaveRejectionReason.choices, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.guard.username} - {self.get_leave_type_display()} ({self.start_date} to {self.end_date}): {self.status}"


class CompensationLedgerEntryType(models.TextChoices):
    EARNED = "EARNED", "Earned (Public Holiday Duty)"
    USED = "USED", "Used (Compensatory Leave Taken)"


class PublicHolidayCompensationLedger(models.Model):
    """
    Authoritative auditable transaction ledger for public-holiday duty compensation.
    Guarantees:
    - 2 days earned per verified/approved worked public holiday duty.
    - Tracks EARNED and USED transactions with full audit trail.
    - Prevents duplicate EARNED credits for the same duty record.
    - Prevents duplicate USED deductions for the same leave application.
    - Independent from vacation_days (no 90-day cap) and casual_days (no 12-day cap).
    - Authoritative remaining balance = SUM(EARNED) - SUM(USED).
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="compensation_ledger_entries",
    )
    entry_type = models.CharField(
        max_length=20,
        choices=CompensationLedgerEntryType.choices,
        db_index=True,
    )
    days = models.DecimalField(max_digits=6, decimal_places=1)
    duty_record = models.ForeignKey(
        "shifts.PublicHolidayDutyRecord",
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name="compensation_ledger_entries",
    )
    leave_application = models.ForeignKey(
        "leave.LeaveApplication",
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name="compensation_ledger_entries",
    )
    notes = models.TextField(blank=True, default="")
    created_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="created_compensation_ledger_entries",
    )
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]
        constraints = [
            models.UniqueConstraint(
                fields=["duty_record", "entry_type"],
                condition=models.Q(entry_type="EARNED"),
                name="unique_earned_entry_per_duty_record",
            ),
            models.UniqueConstraint(
                fields=["leave_application", "entry_type"],
                condition=models.Q(entry_type="USED"),
                name="unique_used_entry_per_leave_application",
            ),
        ]

    def __str__(self):
        return f"{self.guard.username} - {self.entry_type} {self.days} days ({self.created_at.strftime('%Y-%m-%d')})"

    @classmethod
    def get_total_earned_for_guard(cls, guard):
        res = cls.objects.filter(guard=guard, entry_type=CompensationLedgerEntryType.EARNED).aggregate(
            total=models.Sum("days")
        )["total"]
        return Decimal(str(res)) if res is not None else Decimal("0.0")

    @classmethod
    def get_total_used_for_guard(cls, guard):
        res = cls.objects.filter(guard=guard, entry_type=CompensationLedgerEntryType.USED).aggregate(
            total=models.Sum("days")
        )["total"]
        return Decimal(str(res)) if res is not None else Decimal("0.0")

    @classmethod
    def get_remaining_for_guard(cls, guard):
        earned = cls.get_total_earned_for_guard(guard)
        used = cls.get_total_used_for_guard(guard)
        return max(Decimal("0.0"), earned - used)
