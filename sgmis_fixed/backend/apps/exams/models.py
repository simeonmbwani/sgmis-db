import uuid
from django.db import models
from django.conf import settings

class ExamStatus(models.TextChoices):
    ASSIGNED = "ASSIGNED", "Assigned"
    ACKNOWLEDGED = "ACKNOWLEDGED", "Acknowledged"
    IN_PROGRESS = "IN_PROGRESS", "In Progress"
    COMPLETED = "COMPLETED", "Completed"
    CANCELLED = "CANCELLED", "Cancelled"

class ExamDuty(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    reference = models.CharField(max_length=64, blank=True, db_index=True)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="exam_duties")
    supervisor = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="supervised_exams")
    station = models.ForeignKey("stations.Station", on_delete=models.SET_NULL, null=True, blank=True, related_name="exam_duties")
    institution = models.CharField(max_length=200)
    exam_title = models.CharField(max_length=200)
    hall_post = models.CharField(max_length=200, blank=True, default="")
    supervisor_contact = models.CharField(max_length=100, blank=True, default="")
    instructions = models.TextField(blank=True, default="")
    date = models.DateField()
    reporting_time = models.TimeField(null=True, blank=True)
    start_time = models.TimeField()
    end_time = models.TimeField()
    acknowledged_at = models.DateTimeField(null=True, blank=True)
    status = models.CharField(max_length=20, choices=ExamStatus.choices, default=ExamStatus.ASSIGNED, db_index=True)
    remarks = models.TextField(blank=True, default="")
    notes = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-date", "start_time"]

    def save(self, *args, **kwargs):
        if not self.reference:
            self.reference = f"EXAM-{uuid.uuid4().hex[:8].upper()}"
        super().save(*args, **kwargs)

    def __str__(self):
        return f"Exam: {self.reference} - {self.exam_title} @ {self.institution} ({self.date}) - {self.guard.username}"
