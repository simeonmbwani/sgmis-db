import uuid
from django.db import models
from django.conf import settings

class EscortStatus(models.TextChoices):
    SCHEDULED = "SCHEDULED", "Scheduled"
    ASSIGNED = "ASSIGNED", "Assigned"
    ACKNOWLEDGED = "ACKNOWLEDGED", "Acknowledged"
    EN_ROUTE = "EN_ROUTE", "En Route"
    COMPLETED = "COMPLETED", "Completed"
    CANCELLED = "CANCELLED", "Cancelled"

class EscortDuty(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    reference = models.CharField(max_length=64, blank=True, db_index=True)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="escort_duties")
    supervisor = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="supervised_escorts")
    station = models.ForeignKey("stations.Station", on_delete=models.SET_NULL, null=True, blank=True, related_name="escort_duties")
    mission_name = models.CharField(max_length=200)
    origin = models.CharField(max_length=200)
    destination = models.CharField(max_length=200)
    purpose = models.CharField(max_length=255, blank=True, default="")
    instructions = models.TextField(blank=True, default="")
    contact_numbers = models.CharField(max_length=255, blank=True, default="")
    start_time = models.DateTimeField()
    end_time = models.DateTimeField()
    departure_time = models.DateTimeField(null=True, blank=True)
    completion_time = models.DateTimeField(null=True, blank=True)
    acknowledged_at = models.DateTimeField(null=True, blank=True)
    status = models.CharField(max_length=20, choices=EscortStatus.choices, default=EscortStatus.SCHEDULED, db_index=True)
    remarks = models.TextField(blank=True, default="")
    notes = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-start_time"]

    def save(self, *args, **kwargs):
        if not self.reference:
            self.reference = f"ESC-{uuid.uuid4().hex[:8].upper()}"
        super().save(*args, **kwargs)

    def __str__(self):
        return f"Escort: {self.reference} - {self.mission_name} ({self.origin} -> {self.destination}) - {self.guard.username}"
