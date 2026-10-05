import uuid
from django.db import models
from django.conf import settings

class Checkpoint(models.Model):
    """
    Physical inspection point tied to a specific station.
    Never fake; associated with real station coordinates.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="checkpoints")
    name = models.CharField(max_length=150)
    code = models.CharField(max_length=50)
    qr_code = models.CharField(max_length=100, blank=True, default="")
    latitude = models.FloatField(default=0.0)
    longitude = models.FloatField(default=0.0)
    order = models.PositiveIntegerField(default=1)
    nfc_uid = models.CharField(max_length=64, blank=True, default="", help_text="Registered hardware NFC UID for physical checkpoint verification.")
    min_interval_seconds = models.PositiveIntegerField(default=0, help_text="Minimum transit/travel time in seconds required from previous checkpoint.")
    is_active = models.BooleanField(default=True)

    class Meta:
        ordering = ["station", "order"]
        unique_together = ("station", "code")

    def __str__(self):
        return f"{self.station.name} - #{self.order} {self.name} ({self.code})"

class PatrolStatus(models.TextChoices):
    IN_PROGRESS = "IN_PROGRESS", "In Progress"
    COMPLETED = "COMPLETED", "Completed"

class PatrolLog(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="patrol_logs")
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="patrol_logs")
    start_time = models.DateTimeField(auto_now_add=True)
    end_time = models.DateTimeField(null=True, blank=True)
    status = models.CharField(max_length=20, choices=PatrolStatus.choices, default=PatrolStatus.IN_PROGRESS)
    is_approved = models.BooleanField(default=False)
    approved_by = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="approved_patrols")
    approved_at = models.DateTimeField(null=True, blank=True)
    notes = models.TextField(blank=True, default="")

    class Meta:
        ordering = ["-start_time"]

    def __str__(self):
        return f"Patrol {self.station.name} by {self.guard.username} [{self.status}]"

class CheckpointScan(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    patrol_log = models.ForeignKey(PatrolLog, on_delete=models.CASCADE, related_name="scans")
    checkpoint = models.ForeignKey(Checkpoint, on_delete=models.CASCADE, related_name="scans")
    scanned_at = models.DateTimeField(auto_now_add=True)
    gps_coords = models.CharField(max_length=100, blank=True, default="")
    notes = models.CharField(max_length=255, blank=True, default="Checkpoint secure.")

    class Meta:
        ordering = ["scanned_at"]

    def __str__(self):
        return f"Scan: {self.checkpoint.name} @ {self.scanned_at.strftime('%H:%M:%S')}"
