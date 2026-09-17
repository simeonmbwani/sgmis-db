import uuid
from django.db import models
from django.conf import settings
from django.core.exceptions import ValidationError

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
    """
    Evidence-grade Incident Report.
    Deletion is strictly prohibited; archival flag used instead.
    """
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
    assigned_to = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="assigned_incidents",
    )
    escalated_to_admin = models.BooleanField(default=False)
    resolution_notes = models.TextField(blank=True, default="")
    is_archived = models.BooleanField(default=False, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]

    def delete(self, *args, **kwargs):
        raise ValidationError(
            "Deletion of evidence-grade incident reports is strictly prohibited by security audit policy. Records must be preserved or archived."
        )

    def __str__(self):
        return f"[{self.priority}] {self.title} @ {self.station.name} ({self.status})"


class IncidentAmendment(models.Model):
    """
    Immutable audit amendment record for an Incident report.
    Ensures evidence integrity: original description is preserved,
    corrections/additions are append-only.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    incident = models.ForeignKey(
        IncidentReport,
        on_delete=models.CASCADE,
        related_name="amendments",
    )
    amended_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="incident_amendments",
    )
    reason = models.TextField(help_text="Mandatory justification for amending an incident record")
    original_description_snapshot = models.TextField(help_text="Snapshot of the description before this amendment")
    amended_description = models.TextField(help_text="New description content appended as an official correction")
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ["created_at"]

    def delete(self, *args, **kwargs):
        raise ValidationError("Deletion of audit amendments is strictly prohibited.")

    def __str__(self):
        return f"Amendment to incident {self.incident.id} by {self.amended_by.username} at {self.created_at}"
