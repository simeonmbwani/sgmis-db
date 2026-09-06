import uuid
from django.db import models
from django.conf import settings

class IncidentPriority(models.TextChoices):
    LOW = "LOW", "Low Priority"
    MEDIUM = "MEDIUM", "Medium Priority"
    HIGH = "HIGH", "High Priority"
    CRITICAL = "CRITICAL", "Critical Emergency"

class IncidentStatus(models.TextChoices):
    REPORTED = "REPORTED", "Reported"
    ACKNOWLEDGED = "ACKNOWLEDGED", "Acknowledged"
    INVESTIGATING = "INVESTIGATING", "Under Investigation"
    RESOLVED = "RESOLVED", "Resolved"

class IncidentReport(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="incidents")
    reporting_guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="reported_incidents")
    priority = models.CharField(max_length=20, choices=IncidentPriority.choices, default=IncidentPriority.MEDIUM, db_index=True)
    title = models.CharField(max_length=200)
    description = models.TextField()
    location = models.CharField(max_length=200, help_text="Specific zone or coordinates within station")
    status = models.CharField(max_length=20, choices=IncidentStatus.choices, default=IncidentStatus.REPORTED, db_index=True)
    acknowledged_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="acknowledged_incidents",
    )
    resolution_notes = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"[{self.priority}] {self.title} @ {self.station.name} ({self.status})"
