import uuid
from django.db import models
from django.conf import settings
from django.utils import timezone
from django.core.exceptions import ValidationError

class OBCategory(models.TextChoices):
    ROUTINE = "ROUTINE", "Routine Check"
    VISITOR = "VISITOR", "Visitor Log"
    INCIDENT = "INCIDENT", "Incident Report"
    VEHICLE = "VEHICLE", "Vehicle Entry/Exit"
    HANDOVER = "HANDOVER", "Shift Handover"
    MAINTENANCE = "MAINTENANCE", "Facility/Maintenance"
    SPECIAL = "SPECIAL", "Special Instructions"

class OccurrenceBookEntry(models.Model):
    """
    Evidence-grade Occurrence Book entry.
    All records are strictly immutable; deletion is prohibited by security policy.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    entry_number = models.CharField(max_length=30, unique=True, db_index=True, blank=True)
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="ob_entries")
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="ob_entries")
    category = models.CharField(max_length=30, choices=OBCategory.choices, default=OBCategory.ROUTINE)
    occurrence_text = models.TextField()
    check_record = models.CharField(max_length=255, blank=True, default="Verified & Logged")
    is_archived = models.BooleanField(default=False, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]

    def delete(self, *args, **kwargs):
        raise ValidationError(
            "Deletion of evidence-grade Occurrence Book records is strictly prohibited by security audit policy. Records must be preserved or archived."
        )

    def save(self, *args, **kwargs):
        if not self.entry_number:
            prefix = "MW"
            if self.station and self.station.code:
                clean_code = self.station.code.replace("STN-", "").replace("POST-", "").replace("-", "")
                prefix = clean_code[:3].upper() if clean_code else "MW"
            count = OccurrenceBookEntry.objects.filter(station=self.station).count() + 1
            candidate = f"OB-{prefix}-{count:03d}"
            while OccurrenceBookEntry.objects.filter(entry_number=candidate).exists():
                count += 1
                candidate = f"OB-{prefix}-{count:03d}"
            self.entry_number = candidate

        if not self.check_record or self.check_record == "Verified & Logged":
            self.check_record = f"CR-{self.entry_number}"
        super().save(*args, **kwargs)

    def __str__(self):
        return f"{self.entry_number} [{self.station.name}] - {self.category} ({self.check_record})"


class OBAmendment(models.Model):
    """
    Immutable audit amendment record for an Occurrence Book entry.
    Ensures evidence integrity: original text is preserved,
    corrections are append-only.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    entry = models.ForeignKey(
        OccurrenceBookEntry,
        on_delete=models.CASCADE,
        related_name="amendments",
    )
    amended_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="ob_amendments",
    )
    reason = models.TextField(help_text="Mandatory justification for amending an evidence record")
    original_text_snapshot = models.TextField(help_text="Snapshot of the record text before this amendment")
    amended_text = models.TextField(help_text="New text content appended as an official correction")
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ["created_at"]

    def delete(self, *args, **kwargs):
        raise ValidationError("Deletion of audit amendments is strictly prohibited.")

    def __str__(self):
        return f"Amendment to {self.entry.entry_number} by {self.amended_by.username} at {self.created_at}"
