import uuid
from django.db import models
from django.conf import settings
from django.core.exceptions import ValidationError, ObjectDoesNotExist

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

    def clean(self):
        super().clean()
        errors = {}

        # 1. Resolve foreign keys safely
        try:
            station = self.station if self.station_id else None
        except ObjectDoesNotExist:
            station = None
            errors.setdefault("station", []).append("Selected station does not exist.")

        try:
            guard_a = self.guard_a if self.guard_a_id else None
        except ObjectDoesNotExist:
            guard_a = None
            errors.setdefault("guard_a", []).append("Selected Guard A does not exist.")

        try:
            guard_b = self.guard_b if self.guard_b_id else None
        except ObjectDoesNotExist:
            guard_b = None
            errors.setdefault("guard_b", []).append("Selected Guard B does not exist.")

        if not station and "station" not in errors:
            errors.setdefault("station", []).append("Station is required.")

        if not guard_a and "guard_a" not in errors:
            errors.setdefault("guard_a", []).append("Guard A is required.")

        if not guard_b and "guard_b" not in errors:
            errors.setdefault("guard_b", []).append("Guard B is required.")

        # 2. Self-pairing check (guards must be distinct)
        if guard_a and guard_b and guard_a.id == guard_b.id:
            errors.setdefault("guard_b", []).append("A guard cannot be paired with himself/herself.")

        # 3. Role check: both must be GUARD users
        from apps.accounts.models import UserRole

        if guard_a and guard_a.role != UserRole.GUARD:
            errors.setdefault("guard_a", []).append(
                f"Guard A must have the GUARD role (current role: {guard_a.role})."
            )
        if guard_b and guard_b.role != UserRole.GUARD:
            errors.setdefault("guard_b", []).append(
                f"Guard B must have the GUARD role (current role: {guard_b.role})."
            )

        # 4. Active user check: both guards must be active
        if guard_a and not guard_a.is_active:
            errors.setdefault("guard_a", []).append("Guard A must be an active user.")
        if guard_b and not guard_b.is_active:
            errors.setdefault("guard_b", []).append("Guard B must be an active user.")

        # 5. Station check: both guards must belong to the pair's station
        if station:
            if guard_a and guard_a.station_id != station.id:
                errors.setdefault("guard_a", []).append(
                    "Guard A must belong to the pair's station."
                )
            if guard_b and guard_b.station_id != station.id:
                errors.setdefault("guard_b", []).append(
                    "Guard B must belong to the pair's station."
                )

        # 6. Rotation order: must be 1, 2, or 3
        if self.rotation_order not in (1, 2, 3):
            errors.setdefault("rotation_order", []).append(
                f"Rotation order must be 1, 2, or 3 (received {self.rotation_order})."
            )
        elif station:
            # Check rotation_order uniqueness / collision at this station
            rot_qs = GuardPair.objects.filter(
                station_id=station.id,
                rotation_order=self.rotation_order,
            )
            if self.pk:
                rot_qs = rot_qs.exclude(pk=self.pk)
            if self.is_active and rot_qs.filter(is_active=True).exists():
                errors.setdefault("rotation_order", []).append(
                    f"An active pair with rotation order {self.rotation_order} already exists at this station."
                )
            elif rot_qs.exists():
                errors.setdefault("rotation_order", []).append(
                    f"A pair with rotation order {self.rotation_order} already exists at this station."
                )

        # 7. Duplicate member combination (A+B or B+A) at this station
        if station and guard_a and guard_b and guard_a.id != guard_b.id:
            dup_qs = GuardPair.objects.filter(station_id=station.id).filter(
                (models.Q(guard_a_id=guard_a.id, guard_b_id=guard_b.id) |
                 models.Q(guard_a_id=guard_b.id, guard_b_id=guard_a.id))
            )
            if self.pk:
                dup_qs = dup_qs.exclude(pk=self.pk)
            if dup_qs.exists():
                errors.setdefault("guard_b", []).append(
                    "A pair with these two guards already exists at this station (forward or reverse order)."
                )

        # 8. Active pair uniqueness: guard cannot belong to more than one active pair at the station
        if station and self.is_active:
            if guard_a:
                active_a = GuardPair.objects.filter(
                    station_id=station.id,
                    is_active=True,
                ).filter(
                    models.Q(guard_a_id=guard_a.id) | models.Q(guard_b_id=guard_a.id)
                )
                if self.pk:
                    active_a = active_a.exclude(pk=self.pk)
                if active_a.exists():
                    errors.setdefault("guard_a", []).append(
                        f"Guard A ({guard_a.get_full_name() or guard_a.username}) is already assigned to an active pair at this station."
                    )

            if guard_b and guard_a != guard_b:
                active_b = GuardPair.objects.filter(
                    station_id=station.id,
                    is_active=True,
                ).filter(
                    models.Q(guard_a_id=guard_b.id) | models.Q(guard_b_id=guard_b.id)
                )
                if self.pk:
                    active_b = active_b.exclude(pk=self.pk)
                if active_b.exists():
                    errors.setdefault("guard_b", []).append(
                        f"Guard B ({guard_b.get_full_name() or guard_b.username}) is already assigned to an active pair at this station."
                    )

        if errors:
            raise ValidationError(errors)

    def get_partner_for(self, user):
        """Returns the assigned partner for the given user, or None."""
        if self.guard_a_id == user.id:
            return self.guard_b
        elif self.guard_b_id == user.id:
            return self.guard_a
        return None
