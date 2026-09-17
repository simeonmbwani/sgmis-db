import uuid
from django.db import models
from django.conf import settings

class SupervisorOverrideAudit(models.Model):
    """
    Immutable audit record for supervisor overrides (e.g., early handover,
    missed patrol, manual attendance override).
    Mandates: Supervisor ID + Mandatory Reason + Timestamp + Admin Notification Flag.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    supervisor = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="supervisor_overrides",
    )
    action_type = models.CharField(max_length=64, db_index=True)
    target_model = models.CharField(max_length=64, blank=True, default="")
    target_id = models.CharField(max_length=64, blank=True, default="")
    reason = models.TextField(help_text="Mandatory operational justification for supervisor override")
    admin_notified = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"Override [{self.action_type}] by {self.supervisor.username} at {self.created_at}"


class SecurityAuditEvent(models.Model):
    class EventType(models.TextChoices):
        LOGIN_SUCCESS = "LOGIN_SUCCESS", "Login Success"
        LOGIN_FAILURE = "LOGIN_FAILURE", "Login Failure"
        PASSWORD_RESET_REQUEST = "PASSWORD_RESET_REQUEST", "Password Reset Request"
        PASSWORD_RESET_SUCCESS = "PASSWORD_RESET_SUCCESS", "Password Reset Success"
        PASSWORD_RESET_FAILED = "PASSWORD_RESET_FAILED", "Password Reset Failed"
        RECORD_AMENDMENT = "RECORD_AMENDMENT", "Record Amendment"
        OVERRIDE = "OVERRIDE", "Supervisor Override"
        UNAUTHORIZED_ACCESS_ATTEMPT = "UNAUTHORIZED_ACCESS_ATTEMPT", "Unauthorized Access Attempt"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    event_type = models.CharField(max_length=64, choices=EventType.choices, db_index=True)
    actor = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="security_audit_events",
    )
    actor_username = models.CharField(max_length=150, blank=True, default="", db_index=True)
    ip_address = models.CharField(max_length=45, blank=True, default="")
    target_model = models.CharField(max_length=64, blank=True, default="")
    target_id = models.CharField(max_length=64, blank=True, default="")
    details = models.JSONField(default=dict, blank=True)
    timestamp = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ["-timestamp"]

    def __str__(self):
        return f"[{self.timestamp}] {self.event_type} by {self.actor_username or (self.actor.username if self.actor else 'anonymous')}"


class IdempotencyRecord(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    key = models.CharField(max_length=128, unique=True, db_index=True)
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="idempotency_records",
    )
    endpoint = models.CharField(max_length=255, db_index=True)
    response_status = models.IntegerField()
    response_body = models.JSONField(default=dict)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"IdempotencyRecord [{self.key}] for user {self.user.username} ({self.response_status})"
