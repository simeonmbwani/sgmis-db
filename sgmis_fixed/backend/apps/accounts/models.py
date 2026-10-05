import uuid
from django.contrib.auth.models import AbstractUser
from django.db import models
from django.utils import timezone

class UserRole(models.TextChoices):
    ADMINISTRATOR = "ADMINISTRATOR", "Administrator"
    SUPERVISOR = "SUPERVISOR", "Supervisor"
    GUARD = "GUARD", "Security Guard"

class User(AbstractUser):
    """
    Custom user model for SGMIS.
    Supports login by username or employee number.
    Role-based permissions for Administrator, Supervisor, Guard.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    role = models.CharField(
        max_length=20,
        choices=UserRole.choices,
        default=UserRole.GUARD,
        db_index=True,
    )
    employee_number = models.CharField(
        max_length=30,
        unique=True,
        null=True,
        blank=True,
        db_index=True,
        help_text="Unique organizational employee ID (e.g., SEC-1001)",
    )
    station = models.ForeignKey(
        "stations.Station",
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="assigned_guards",
    )
    rank = models.CharField(max_length=64, default="Security Officer", blank=True)
    phone_number = models.CharField(max_length=32, blank=True, default="")
    address = models.CharField(max_length=255, blank=True, default="")
    profile_photo = models.URLField(max_length=500, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["last_name", "first_name", "username"]
        indexes = [
            models.Index(fields=["employee_number"]),
            models.Index(fields=["role"]),
            models.Index(fields=["station"]),
        ]

    def __str__(self):
        full = self.get_full_name().strip()
        name = full if full else self.username
        emp = f" ({self.employee_number})" if self.employee_number else ""
        return f"{name}{emp} - {self.get_role_display()}"

    @property
    def is_admin(self):
        return self.role == UserRole.ADMINISTRATOR or self.is_superuser

    @property
    def is_supervisor_user(self):
        return self.role in (UserRole.SUPERVISOR, UserRole.ADMINISTRATOR) or self.is_superuser

    @property
    def is_guard_user(self):
        return self.role == UserRole.GUARD


class LoginAttempt(models.Model):
    """
    Tracks failed login attempts for brute-force rate limiting and lockout.
    Max 5 failed attempts within window -> 15 minute lockout.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    identifier = models.CharField(max_length=150, db_index=True)
    ip_address = models.GenericIPAddressField(null=True, blank=True)
    failed_attempts = models.PositiveIntegerField(default=0)
    locked_until = models.DateTimeField(null=True, blank=True)
    last_attempt = models.DateTimeField(auto_now=True)

    class Meta:
        indexes = [
            models.Index(fields=["identifier"]),
            models.Index(fields=["ip_address"]),
        ]

    def __str__(self):
        return f"{self.identifier} ({self.failed_attempts} failed attempts, locked until {self.locked_until})"


class PasswordResetOTP(models.Model):
    """
    Time-sensitive OTP for secure account password recovery.
    Valid for 10 minutes from creation.
    Stores cryptographic SHA-256 hash of the recovery code to prevent plaintext exposure.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="password_reset_otps")
    otp_code = models.CharField(max_length=64, blank=True, default="")
    otp_code_hash = models.CharField(max_length=64, db_index=True, blank=True, default="")
    attempts_count = models.PositiveIntegerField(default=0)
    max_attempts = models.PositiveIntegerField(default=5)
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()
    is_used = models.BooleanField(default=False)

    class Meta:
        ordering = ["-created_at"]

    def set_otp(self, raw_otp: str):
        import hashlib
        clean_otp = raw_otp.strip()
        self.otp_code_hash = hashlib.sha256(clean_otp.encode("utf-8")).hexdigest()
        self.otp_code = ""  # Never retain plaintext in storage

    def verify_otp(self, candidate_otp: str) -> bool:
        import hashlib
        import secrets
        if self.is_used:
            return False
        if timezone.now() > self.expires_at:
            return False
        if self.attempts_count >= self.max_attempts:
            self.is_used = True
            self.save(update_fields=["is_used"])
            return False

        candidate_hash = hashlib.sha256(candidate_otp.strip().encode("utf-8")).hexdigest()
        # Verify against otp_code_hash (or fallback to legacy otp_code if present)
        target_hash = self.otp_code_hash
        if not target_hash and self.otp_code:
            target_hash = hashlib.sha256(self.otp_code.strip().encode("utf-8")).hexdigest()

        matched = secrets.compare_digest(candidate_hash, target_hash) if target_hash else False
        if not matched:
            self.attempts_count += 1
            if self.attempts_count >= self.max_attempts:
                self.is_used = True
            self.save(update_fields=["attempts_count", "is_used"])
            return False

        return True

    def __str__(self):
        return f"OTP for {self.user.username} (used={self.is_used}, attempts={self.attempts_count})"



class UserDeactivationAudit(models.Model):
    """
    Immutable audit log for account deactivations.
    Deactivating a guard requires explicit Admin approval plus a mandatory audit reason.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    target_user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="deactivation_records")
    performed_by = models.ForeignKey(User, on_delete=models.CASCADE, related_name="deactivations_performed")
    reason = models.TextField(help_text="Mandatory audit justification for account deactivation")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"Deactivation of {self.target_user.username} by {self.performed_by.username} on {self.created_at}"
