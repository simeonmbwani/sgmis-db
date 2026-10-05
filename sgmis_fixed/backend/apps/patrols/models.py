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
    ASSIGNED = "ASSIGNED", "Assigned"
    IN_PROGRESS = "IN_PROGRESS", "In Progress"
    ACTIVE = "ACTIVE", "Active"
    COMPLETED = "COMPLETED", "Completed"
    FAILED = "FAILED", "Failed"
    EXPIRED = "EXPIRED", "Expired"
    CANCELLED = "CANCELLED", "Cancelled"
    APPROVED = "APPROVED", "Approved"

class PatrolLog(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=150, default="Routine Station Patrol")
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="patrol_logs")
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="patrol_logs")
    assigned_by = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="assigned_patrols")
    start_window = models.DateTimeField(null=True, blank=True)
    deadline = models.DateTimeField(null=True, blank=True)
    start_time = models.DateTimeField(auto_now_add=True)
    end_time = models.DateTimeField(null=True, blank=True)
    status = models.CharField(max_length=20, choices=PatrolStatus.choices, default=PatrolStatus.ASSIGNED)
    is_approved = models.BooleanField(default=False)
    approved_by = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="approved_patrols")
    approved_at = models.DateTimeField(null=True, blank=True)
    anomalies = models.JSONField(default=list, blank=True)
    notes = models.TextField(blank=True, default="")

    class Meta:
        ordering = ["-start_time"]

    def __str__(self):
        return f"Patrol {self.station.name} by {self.guard.username} [{self.status}]"

    def record_anomaly(self, anomaly_type, description, details=None):
        if not isinstance(self.anomalies, list):
            self.anomalies = []
        from django.utils import timezone
        entry = {
            "type": anomaly_type,
            "description": description,
            "timestamp": timezone.now().isoformat(),
            "details": details or {},
        }
        self.anomalies.append(entry)
        self.save(update_fields=["anomalies"])

class CheckpointScan(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    client_event_id = models.UUIDField(null=True, blank=True, unique=True)
    patrol_log = models.ForeignKey(PatrolLog, on_delete=models.CASCADE, related_name="scans")
    checkpoint = models.ForeignKey(Checkpoint, on_delete=models.CASCADE, related_name="scans")
    scanned_at = models.DateTimeField(auto_now_add=True)
    client_timestamp = models.DateTimeField(null=True, blank=True)
    gps_coords = models.CharField(max_length=100, blank=True, default="")
    accuracy = models.FloatField(null=True, blank=True)
    verification_method = models.CharField(max_length=30, default="UNVERIFIED")
    notes = models.CharField(max_length=255, blank=True, default="Checkpoint secure.")

    class Meta:
        ordering = ["scanned_at"]

    def __str__(self):
        return f"Scan: {self.checkpoint.name} @ {self.scanned_at.strftime('%H:%M:%S')}"
