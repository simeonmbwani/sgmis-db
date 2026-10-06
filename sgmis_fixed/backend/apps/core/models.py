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


class AdjustmentStatus(models.TextChoices):
    PENDING = "PENDING", "Pending Review"
    APPROVED = "APPROVED", "Approved"
    REJECTED = "REJECTED", "Rejected"


class RecordAdjustmentRequest(models.Model):
    """
    Auditable administrative correction and reconciliation request.
    Allows Supervisors to submit proposed record adjustments for Superuser approval,
    or Superusers to directly record verified current/opening state adjustments.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="adjustment_requests",
    )
    field_name = models.CharField(max_length=64, db_index=True)
    old_value = models.TextField(blank=True, default="")
    requested_value = models.TextField()
    approved_value = models.TextField(blank=True, default="")
    effective_date = models.DateField(db_index=True)
    reason = models.TextField(help_text="Mandatory operational justification or physical record citation")
    notes = models.TextField(blank=True, default="")
    status = models.CharField(
        max_length=20,
        choices=AdjustmentStatus.choices,
        default=AdjustmentStatus.PENDING,
        db_index=True,
    )
    requested_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="submitted_adjustments",
    )
    reviewed_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="reviewed_adjustments",
    )
    reviewed_at = models.DateTimeField(null=True, blank=True)
    rejection_reason = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"Adjustment [{self.field_name}] for {self.guard.username} ({self.status})"


class OrganizationPolicy(models.Model):
    class Category(models.TextChoices):
        LEAVE = "LEAVE", "Leave Management Policy"
        ROSTER = "ROSTER", "Roster & Shift Rotation Policy"
        DUTY = "DUTY", "Operational Duty & Relief Policy"
        PATROL = "PATROL", "Patrol & Geofence Verification Policy"
        ATTENDANCE = "ATTENDANCE", "Attendance & Clock-In Policy"
        COMPENSATION = "COMPENSATION", "Public Holiday & Duty Compensation Policy"
        GEOFENCE = "GEOFENCE", "Station Perimeter & Geofence Policy"
        SECURITY = "SECURITY", "Access Control & Information Security Policy"
        GENERAL = "GENERAL", "General Security Operations Policy"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    category = models.CharField(max_length=64, choices=Category.choices, db_index=True)
    policy_key = models.CharField(max_length=64, blank=True, default="", db_index=True)
    setting_value = models.CharField(max_length=128, blank=True, default="")
    title = models.CharField(max_length=150)
    summary = models.CharField(max_length=255, blank=True, default="")
    content = models.TextField()
    version = models.CharField(max_length=30, default="1.0")
    is_active = models.BooleanField(default=True, db_index=True)
    updated_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="updated_policies",
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["category", "title"]

    def __str__(self):
        return f"[{self.category}] {self.title} (v{self.version})"

    @classmethod
    def get_operational_value(cls, key: str, default=None):
        """
        Dynamically extracts authoritative operational parameters from active policies.
        Supported keys:
          - 'PATROL_SCAN_INTERVAL': minimum patrol duration / scan interval (seconds, default 60)
          - 'STATION_GEOFENCE_RADIUS': station geofence radius (meters, default 200.0)
          - 'PATROL_PROXIMITY_RADIUS': checkpoint proximity radius (meters, default 100.0)
          - 'LEAVE_INTERRUPTION_COMPENSATION': days compensation per interrupted leave day (default 1.0)
        """
        try:
            policy = cls.objects.filter(policy_key=key, is_active=True).first()
            if policy and policy.setting_value:
                if isinstance(default, int):
                    return int(float(policy.setting_value))
                elif isinstance(default, float):
                    return float(policy.setting_value)
                return policy.setting_value

            cat_map = {
                "PATROL_SCAN_INTERVAL": cls.Category.PATROL,
                "PATROL_PROXIMITY_RADIUS": cls.Category.PATROL,
                "STATION_GEOFENCE_RADIUS": cls.Category.GEOFENCE,
                "LEAVE_INTERRUPTION_COMPENSATION": cls.Category.COMPENSATION,
            }
            if key in cat_map:
                cat_policy = cls.objects.filter(category=cat_map[key], is_active=True).first()
                if cat_policy and cat_policy.content:
                    import re
                    match = re.search(rf"{key}\s*=\s*([0-9.]+)", cat_policy.content)
                    if match:
                        val_str = match.group(1)
                        if isinstance(default, int):
                            return int(float(val_str))
                        elif isinstance(default, float):
                            return float(val_str)
                        return val_str
        except Exception:
            pass
        return default


