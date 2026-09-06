import uuid
from django.db import models
from django.conf import settings

class LeaveType(models.TextChoices):
    ANNUAL = "ANNUAL", "Annual Leave"
    SICK = "SICK", "Sick Leave"
    EMERGENCY = "EMERGENCY", "Emergency Leave"
    COMPASSIONATE = "COMPASSIONATE", "Compassionate Leave"

class LeaveStatus(models.TextChoices):
    PENDING = "PENDING", "Pending Review"
    APPROVED = "APPROVED", "Approved"
    REJECTED = "REJECTED", "Rejected"
    CHANGES_REQUESTED = "CHANGES_REQUESTED", "Changes Requested"

class LeaveBalance(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="leave_balances")
    year = models.PositiveIntegerField(default=2026)
    annual_days = models.PositiveIntegerField(default=21)
    sick_days = models.PositiveIntegerField(default=14)
    used_annual = models.PositiveIntegerField(default=0)
    used_sick = models.PositiveIntegerField(default=0)

    class Meta:
        unique_together = ("guard", "year")

    @property
    def remaining_annual(self):
        return max(0, self.annual_days - self.used_annual)

    @property
    def remaining_sick(self):
        return max(0, self.sick_days - self.used_sick)

    def __str__(self):
        return f"{self.guard.username} ({self.year}) Balance: {self.remaining_annual} Annual, {self.remaining_sick} Sick"

class LeaveApplication(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="leave_applications")
    leave_type = models.CharField(max_length=20, choices=LeaveType.choices, default=LeaveType.ANNUAL)
    start_date = models.DateField()
    end_date = models.DateField()
    reason = models.TextField()
    status = models.CharField(max_length=20, choices=LeaveStatus.choices, default=LeaveStatus.PENDING, db_index=True)
    reviewer = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="reviewed_leaves",
    )
    reviewer_notes = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.guard.username} - {self.get_leave_type_display()} ({self.start_date} to {self.end_date}): {self.status}"
