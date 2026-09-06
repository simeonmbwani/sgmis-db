import uuid
from django.contrib.auth.models import AbstractUser
from django.db import models

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
