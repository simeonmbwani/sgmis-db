import uuid
from django.db import models
from django.conf import settings
from django.utils import timezone

class OBCategory(models.TextChoices):
    ROUTINE = "ROUTINE", "Routine Check"
    VISITOR = "VISITOR", "Visitor Log"
    INCIDENT = "INCIDENT", "Incident Report"
    VEHICLE = "VEHICLE", "Vehicle Entry/Exit"
    HANDOVER = "HANDOVER", "Shift Handover"
    MAINTENANCE = "MAINTENANCE", "Facility/Maintenance"
    SPECIAL = "SPECIAL", "Special Instructions"

class OccurrenceBookEntry(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    entry_number = models.CharField(max_length=30, unique=True, db_index=True, blank=True)
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="ob_entries")
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="ob_entries")
    category = models.CharField(max_length=30, choices=OBCategory.choices, default=OBCategory.ROUTINE)
    occurrence_text = models.TextField()
    check_record = models.CharField(max_length=255, blank=True, default="Verified & Logged")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]

    def save(self, *args, **kwargs):
        if not self.entry_number:
            today_str = timezone.now().strftime("%Y%m%d")
            # Count entries for today
            count_today = OccurrenceBookEntry.objects.filter(created_at__date=timezone.now().date()).count() + 1
            self.entry_number = f"OB-{today_str}-{count_today:04d}"
        super().save(*args, **kwargs)

    def __str__(self):
        return f"{self.entry_number} [{self.station.name}] - {self.category}"
