import uuid
from django.db import models
from django.conf import settings

class EscortStatus(models.TextChoices):
    SCHEDULED = "SCHEDULED", "Scheduled"
    EN_ROUTE = "EN_ROUTE", "En Route"
    COMPLETED = "COMPLETED", "Completed"
    CANCELLED = "CANCELLED", "Cancelled"

class EscortDuty(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="escort_duties")
    mission_name = models.CharField(max_length=200)
    origin = models.CharField(max_length=200)
    destination = models.CharField(max_length=200)
    start_time = models.DateTimeField()
    end_time = models.DateTimeField()
    status = models.CharField(max_length=20, choices=EscortStatus.choices, default=EscortStatus.SCHEDULED, db_index=True)
    notes = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-start_time"]

    def __str__(self):
        return f"Escort: {self.mission_name} ({self.origin} -> {self.destination}) - {self.guard.username}"
