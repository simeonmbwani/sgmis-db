import uuid
from datetime import date
from django.db import models
from django.conf import settings

class LeaveType(models.TextChoices):
    CASUAL = "CASUAL", "Casual Leave"
    VACATION = "VACATION", "Vacation Leave"
    # ANNUAL is retained for backward compatibility with existing records/clients.
    ANNUAL = "ANNUAL", "Vacation Leave (Legacy Annual)"
    SICK = "SICK", "Sick Leave"
    EMERGENCY = "EMERGENCY", "Emergency Leave"
    COMPASSIONATE = "COMPASSIONATE", "Compassionate Leave"

class LeaveStatus(models.TextChoices):
    PENDING = "PENDING", "Pending Review"
    APPROVED = "APPROVED", "Approved"
    REJECTED = "REJECTED", "Rejected"
    CHANGES_REQUESTED = "CHANGES_REQUESTED", "Changes Requested"

class LeaveBalance(models.Model):
    """Leave ledger using the organisation's stated monthly accrual rules."""
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
    def _whole_months(start, end):
        months = (end.year - start.year) * 12 + end.month - start.month
        if end.day < start.day:
            months -= 1
        return max(0, months)

    def accrue_to_date(self, as_of=None, save=True):
        """Accrue completed months since the last accrual and enforce the 12-month casual cycle."""
        as_of = as_of or date.today()
        if as_of < self.last_accrual_date:
            return self

        # Casual leave expires at the end of each 12-month cycle if unused.
        cycle_months = self._whole_months(self.casual_cycle_start, as_of)
        if cycle_months >= 12:
            self.casual_days = 0
            self.used_casual = 0
            cycles = cycle_months // 12
            start_month = self.casual_cycle_start.month - 1 + cycles * 12
            self.casual_cycle_start = date(
                self.casual_cycle_start.year + start_month // 12,
                start_month % 12 + 1,
                min(self.casual_cycle_start.day, 28),
            )
            self.last_accrual_date = self.casual_cycle_start

        months = self._whole_months(self.last_accrual_date, as_of)
        if months:
            self.casual_days = min(12.0, float(self.casual_days) + months * float(self.casual_accrual_rate))
            self.vacation_days = min(float(self.vacation_cap), float(self.vacation_days) + months * float(self.vacation_accrual_rate))
            self.last_accrual_date = as_of
            if save:
                self.save(update_fields=["casual_days", "vacation_days", "last_accrual_date", "casual_cycle_start", "used_casual"])
        elif save:
            self.save(update_fields=["casual_cycle_start", "used_casual"])
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

    def __str__(self):
        return f"{self.guard.username} ({self.year}) Leave Balance"

class LeaveApplication(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="leave_applications")
    leave_type = models.CharField(max_length=20, choices=LeaveType.choices, default=LeaveType.VACATION)
    start_date = models.DateField()
    end_date = models.DateField()
    reason = models.TextField()
    status = models.CharField(max_length=20, choices=LeaveStatus.choices, default=LeaveStatus.PENDING, db_index=True)
    reviewer = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="reviewed_leaves")
    reviewer_notes = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.guard.username} - {self.get_leave_type_display()} ({self.start_date} to {self.end_date}): {self.status}"
