import uuid
from django.db import models
from django.conf import settings

class Station(models.Model):
    """
    Physical security post or deployment station.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=150)
    code = models.CharField(max_length=30, unique=True, db_index=True)
    address = models.TextField(blank=True, default="")
    latitude = models.FloatField(default=0.0)
    longitude = models.FloatField(default=0.0)
    geofence_radius_meters = models.FloatField(default=200.0)
    is_active = models.BooleanField(default=True, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["name"]

    def __str__(self):
        return f"{self.name} [{self.code}]"

class GuardPair(models.Model):
    """
    Official pairing of two security guards assigned to a station
    and rotating in shifts.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    station = models.ForeignKey(Station, on_delete=models.CASCADE, related_name="pairs")
    guard_a = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="pairs_as_guard_a",
    )
    guard_b = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="pairs_as_guard_b",
    )
    rotation_order = models.PositiveIntegerField(default=1, db_index=True)
    is_active = models.BooleanField(default=True, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["station", "rotation_order"]
        unique_together = ("station", "rotation_order")

    def __str__(self):
        return f"Pair {self.rotation_order} ({self.station.name}): {self.guard_a.get_full_name() or self.guard_a.username} & {self.guard_b.get_full_name() or self.guard_b.username}"

    def get_partner_for(self, user):
        """Returns the assigned partner for the given user, or None."""
        if self.guard_a_id == user.id:
            return self.guard_b
        elif self.guard_b_id == user.id:
            return self.guard_a
        return None
