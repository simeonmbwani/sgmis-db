import uuid
from django.db import models
from django.conf import settings

class ExamDuty(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="exam_duties")
    institution = models.CharField(max_length=200)
    exam_title = models.CharField(max_length=200)
    date = models.DateField()
    start_time = models.TimeField()
    end_time = models.TimeField()
    status = models.CharField(max_length=20, default="ASSIGNED")
    notes = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-date", "start_time"]

    def __str__(self):
        return f"Exam: {self.exam_title} @ {self.institution} ({self.date}) - {self.guard.username}"
