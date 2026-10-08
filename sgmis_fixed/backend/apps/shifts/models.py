import uuid
from django.db import models
from django.conf import settings
from django.core.exceptions import ValidationError, ObjectDoesNotExist

class ShiftType(models.TextChoices):
    DAY = "DAY", "Day Shift"
    NIGHT = "NIGHT", "Night Shift"
    OFF = "OFF", "Time Off"

class AssignmentType(models.TextChoices):
    NORMAL = "NORMAL", "Normal Duty"
    EXAM = "EXAM", "Exam Duty"
    ESCORT = "ESCORT", "Escort Duty"
    TIME_OFF = "TIME_OFF", "Time Off"
    RELIEF = "RELIEF", "Relief Duty"

class RosterStatus(models.TextChoices):
    DRAFT = "DRAFT", "Draft"
    VALIDATED = "VALIDATED", "Validated"
    APPROVED = "APPROVED", "Approved"
    ACTIVE = "ACTIVE", "Active"
    ARCHIVED = "ARCHIVED", "Archived"

class HolidayCompensationStatus(models.TextChoices):
    PENDING = "PENDING", "Pending Review"
    APPROVED = "APPROVED", "Approved"
    REJECTED = "REJECTED", "Rejected"
    CANCELLED = "CANCELLED", "Cancelled"

class DutyOverrideType(models.TextChoices):
    LEAVE_INTERRUPTION = "LEAVE_INTERRUPTION", "Leave Interruption for Duty"
    OFF_DAY_CALL_IN = "OFF_DAY_CALL_IN", "Scheduled Off-Day Call-In"
    EMERGENCY_REINFORCEMENT = "EMERGENCY_REINFORCEMENT", "Emergency Station Reinforcement"

class DutyOverrideStatus(models.TextChoices):
    ACTIVE = "ACTIVE", "Active"
    COMPLETED = "COMPLETED", "Completed"
    CANCELLED = "CANCELLED", "Cancelled"

class ExaminationPeriod(models.Model):
    """
    Authorized examination period (typically ~2 weeks) during which
    examination venue duties and collection escorts are active.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="examination_periods")
    name = models.CharField(max_length=150, default="University Examinations")
    venue_name = models.CharField(max_length=150, default="Examination Center")
    start_date = models.DateField(db_index=True)
    end_date = models.DateField(db_index=True)
    is_active = models.BooleanField(default=True)
    authorized_by = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="authorized_exam_periods")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-start_date"]

    def __str__(self):
        return f"{self.name} ({self.start_date} to {self.end_date}) @ {self.station.name}"

class TemporaryAssignmentAudit(models.Model):
    """
    Audit log of temporary operational reassignments (e.g. during examination periods).
    Preserves original pair relationships and normal assignment history.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="temporary_reassignments")
    original_pair = models.ForeignKey("stations.GuardPair", on_delete=models.SET_NULL, null=True, blank=True, related_name="temp_reassignments")
    original_assignment = models.CharField(max_length=50, default="NORMAL")
    temporary_assignment = models.CharField(max_length=50, default="EXAM")
    location = models.CharField(max_length=150, default="Exam Venue")
    start_date = models.DateField()
    end_date = models.DateField()
    start_time = models.TimeField(null=True, blank=True)
    end_time = models.TimeField(null=True, blank=True)
    reason = models.TextField(help_text="Mandatory justification / operational directive")
    authorized_by = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.SET_NULL, null=True, blank=True, related_name="approved_reassignments")
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"Reassignment: {self.guard.username} -> {self.temporary_assignment} ({self.start_date} to {self.end_date})"

class DutyRosterCycle(models.Model):
    """
    Roster rotation cycle definition for a station.
    Default rotation block is 4 days.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="roster_cycles")
    cycle_start_date = models.DateField()
    cycle_length_days = models.PositiveIntegerField(default=4)
    rotation_order = models.PositiveIntegerField(default=1)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    def __str__(self):
        return f"Cycle {self.station.name} starting {self.cycle_start_date} ({self.cycle_length_days} days)"

class DutyRoster(models.Model):
    """
    Authoritative duty roster governing shift deployments for a station over a defined period.
    Enforces strict lifecycle states and approval metadata validation.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="duty_rosters")
    start_date = models.DateField(db_index=True)
    end_date = models.DateField(db_index=True)
    status = models.CharField(
        max_length=20,
        choices=RosterStatus.choices,
        default=RosterStatus.DRAFT,
        db_index=True,
    )
    approved_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="approved_duty_rosters",
    )
    approved_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-start_date", "station"]
        indexes = [
            models.Index(fields=["station", "start_date"]),
            models.Index(fields=["station", "end_date"]),
            models.Index(fields=["status"]),
        ]

    def __str__(self):
        return f"Roster {self.station.name} [{self.start_date} to {self.end_date}] ({self.get_status_display()})"

    def clean(self):
        super().clean()
        errors = {}

        # 1. Station validation
        try:
            station = self.station if self.station_id else None
        except ObjectDoesNotExist:
            station = None
            errors.setdefault("station", []).append("Selected station does not exist.")

        if not station and "station" not in errors:
            errors.setdefault("station", []).append("Station is required.")

        # 2. Date validation
        if self.start_date and self.end_date:
            if self.end_date < self.start_date:
                errors.setdefault("end_date", []).append(
                    "End date cannot be earlier than start date."
                )
        elif not self.start_date:
            errors.setdefault("start_date", []).append("Start date is required.")
        elif not self.end_date:
            errors.setdefault("end_date", []).append("End date is required.")

        # 3. Approval metadata rules based on status
        has_approved_by = bool(self.approved_by_id)
        has_approved_at = bool(self.approved_at)

        if self.status in (RosterStatus.DRAFT, RosterStatus.VALIDATED):
            if has_approved_by:
                errors.setdefault("approved_by", []).append(
                    f"Roster in {self.get_status_display()} status must not have approval metadata (approved_by must be empty)."
                )
            if has_approved_at:
                errors.setdefault("approved_at", []).append(
                    f"Roster in {self.get_status_display()} status must not have approval metadata (approved_at must be empty)."
                )
        elif self.status in (RosterStatus.APPROVED, RosterStatus.ACTIVE, RosterStatus.ARCHIVED):
            if not has_approved_by:
                errors.setdefault("approved_by", []).append(
                    f"Roster in {self.get_status_display()} status requires an approving user (approved_by)."
                )
            if not has_approved_at:
                errors.setdefault("approved_at", []).append(
                    f"Roster in {self.get_status_display()} status requires an approval timestamp (approved_at)."
                )

        # 4. Immutability protection for APPROVED / ACTIVE / ARCHIVED rosters
        if self.pk and not getattr(self, "_admin_reopening", False):
            try:
                original = DutyRoster.objects.get(pk=self.pk)
                if original.status in (RosterStatus.APPROVED, RosterStatus.ACTIVE, RosterStatus.ARCHIVED):
                    if self.station_id != original.station_id:
                        errors.setdefault("station", []).append(
                            f"Station cannot be modified on an {original.get_status_display().lower()} roster."
                        )
                    if self.start_date != original.start_date:
                        errors.setdefault("start_date", []).append(
                            f"Start date cannot be modified on an {original.get_status_display().lower()} roster."
                        )
                    if self.end_date != original.end_date:
                        errors.setdefault("end_date", []).append(
                            f"End date cannot be modified on an {original.get_status_display().lower()} roster."
                        )
                    if self.status in (RosterStatus.DRAFT, RosterStatus.VALIDATED):
                        errors.setdefault("status", []).append(
                            f"An {original.get_status_display().lower()} roster cannot be reverted to {self.get_status_display().lower()}."
                        )
            except DutyRoster.DoesNotExist:
                pass

        if errors:
            raise ValidationError(errors)

    def delete(self, *args, **kwargs):
        if self.status in (RosterStatus.APPROVED, RosterStatus.ACTIVE, RosterStatus.ARCHIVED):
            raise ValidationError(
                f"An {self.get_status_display().lower()} duty roster is protected and cannot be deleted."
            )
        return super().delete(*args, **kwargs)

class Shift(models.Model):
    """
    Individual guard shift assignment on a given date.
    All times and stations are authoritative database values.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="shifts")
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="assigned_shifts")
    date = models.DateField(db_index=True)
    start_time = models.TimeField()
    end_time = models.TimeField()
    shift_type = models.CharField(max_length=10, choices=ShiftType.choices, db_index=True)
    assignment_type = models.CharField(max_length=20, choices=AssignmentType.choices, default=AssignmentType.NORMAL, db_index=True)
    duty_location = models.CharField(max_length=150, default="Main Campus")
    examination_period = models.ForeignKey(ExaminationPeriod, on_delete=models.SET_NULL, null=True, blank=True, related_name="shifts")
    pair = models.ForeignKey("stations.GuardPair", on_delete=models.SET_NULL, null=True, blank=True, related_name="shifts")
    roster = models.ForeignKey(
        DutyRoster,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="shifts",
    )
    is_override = models.BooleanField(default=False)
    override_reason = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-date", "start_time"]
        unique_together = ("station", "guard", "date", "shift_type")
        indexes = [
            models.Index(fields=["guard", "date"]),
            models.Index(fields=["station", "date"]),
        ]

    def __str__(self):
        return f"{self.date} {self.shift_type} ({self.start_time}-{self.end_time}) - {self.guard.get_full_name() or self.guard.username} @ {self.station.name}"

    def clean(self):
        super().clean()

        # Rule 0: Duty Conflict Check (Leave, Exam, Escort)
        # Normal shifts are mutually exclusive with approved leave, exam duty, and escort missions on the same date.
        if self.guard_id and self.date and self.shift_type != ShiftType.OFF and self.assignment_type != AssignmentType.TIME_OFF:
            from apps.leave.models import LeaveApplication, LeaveStatus
            has_active_override = DutyOverride.objects.filter(
                guard_id=self.guard_id,
                date=self.date,
                status=DutyOverrideStatus.ACTIVE,
            ).exists()
            if not has_active_override and LeaveApplication.objects.filter(guard_id=self.guard_id, status=LeaveStatus.APPROVED, start_date__lte=self.date, end_date__gte=self.date).exists():
                raise ValidationError(f"The selected guard is on approved leave on {self.date} and cannot be assigned to a shift.")

            from apps.exams.models import ExamDuty, ExamStatus
            if ExamDuty.objects.filter(guard_id=self.guard_id, date=self.date).exclude(status=ExamStatus.CANCELLED).exists():
                raise ValidationError(f"The selected guard is assigned to examination duty on {self.date} and cannot be assigned to a normal shift.")

            from apps.escorts.models import EscortDuty, EscortStatus
            if EscortDuty.objects.filter(guard_id=self.guard_id, start_time__date__lte=self.date, end_time__date__gte=self.date).exclude(status=EscortStatus.CANCELLED).exists():
                raise ValidationError(f"The selected guard is assigned to escort duty on {self.date} and cannot be assigned to a normal shift.")

        # Rule 1: ONE GUARD = ONE ACTIVE STATION AT A TIME
        # A guard cannot have active working duties at multiple stations on the same date.
        # Historical, expired, archived, and cancelled records do NOT count as conflicts.
        if self.guard_id and self.station_id and self.shift_type != ShiftType.OFF and self.assignment_type != AssignmentType.TIME_OFF:

            conflict = Shift.objects.filter(
                guard_id=self.guard_id,
                date=self.date,
            ).exclude(id=self.id).exclude(
                roster__status__in=[RosterStatus.ARCHIVED]
            ).exclude(
                shift_type=ShiftType.OFF
            ).exclude(
                assignment_type=AssignmentType.TIME_OFF
            ).exclude(
                station_id=self.station_id
            ).select_related("station").first()
            if conflict:
                raise ValidationError(
                    f"Guard already has an active assignment at another station ({conflict.station.name}) on {self.date}."
                )

        # Rule 2: NORMAL STATION PAIR / MAXIMUM TWO ACTIVE GUARDS (1 DAY, 1 NIGHT)
        if self.assignment_type in [AssignmentType.NORMAL, AssignmentType.RELIEF] and self.shift_type in [ShiftType.DAY, ShiftType.NIGHT]:
            target_filter = {"pair": self.pair} if self.pair else {"station_id": self.station_id}

            # Enforce pair/station invariant: No duplicate shift_type on same date
            dup = Shift.objects.filter(
                date=self.date,
                shift_type=self.shift_type,
                assignment_type__in=[AssignmentType.NORMAL, AssignmentType.RELIEF],
                **target_filter,
            ).exclude(id=self.id).exclude(roster__status__in=[RosterStatus.ARCHIVED])
            if dup.exists():
                entity_label = "Pair" if self.pair else "Station"
                raise ValidationError(
                    f"{entity_label} already has an active {self.shift_type} shift on {self.date}."
                )

            # Maximum 2 distinct active guards scheduled per pair/station per date (1 DAY, 1 NIGHT)
            active_shifts = Shift.objects.filter(
                date=self.date,
                assignment_type__in=[AssignmentType.NORMAL, AssignmentType.RELIEF],
                **target_filter,
            ).exclude(id=self.id).exclude(shift_type=ShiftType.OFF).exclude(
                assignment_type=AssignmentType.TIME_OFF
            ).exclude(roster__status__in=[RosterStatus.ARCHIVED])
            if active_shifts.count() >= 2:
                entity_label = "pair" if self.pair else "station"
                raise ValidationError(
                    f"Maximum 2 guards (1 DAY, 1 NIGHT) can be scheduled for a {entity_label} on {self.date}."
                )

    def get_partner(self):
        """
        Determines the assigned partner guard for this shift.
        First checks the assigned GuardPair, or queries for other guards
        assigned to the same station on the same date and shift type.
        """
        if self.pair:
            return self.pair.get_partner_for(self.guard)
        # Fallback: check other shifts at same station, date, and shift_type
        other_shift = Shift.objects.filter(
            station=self.station,
            date=self.date,
            shift_type=self.shift_type
        ).exclude(guard=self.guard).select_related("guard").first()
        return other_shift.guard if other_shift else None

class ShiftHandover(models.Model):
    """
    Formal operational handover between outgoing guard and incoming guard.
    Incoming guard is strictly determined by backend roster.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    outgoing_shift = models.ForeignKey(Shift, on_delete=models.CASCADE, related_name="handovers")
    outgoing_guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="outgoing_handovers")
    incoming_guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="incoming_handovers")
    station = models.ForeignKey("stations.Station", on_delete=models.CASCADE, related_name="handovers")
    occurrence_summary = models.TextField()
    equipment_issued = models.TextField(blank=True, default="All inventory accounted for.")
    keys_handed_over = models.TextField(blank=True, default="Master keys & padlock sets transferred.")
    pending_issues = models.TextField(blank=True, default="None.")
    outgoing_signed = models.BooleanField(default=True)
    incoming_accepted = models.BooleanField(default=False, db_index=True)
    incoming_accepted_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"Handover {self.station.name}: {self.outgoing_guard.username} -> {self.incoming_guard.username} ({self.created_at.strftime('%Y-%m-%d %H:%M')})"

class Attendance(models.Model):
    """
    Clock-in and clock-out operational attendance.
    Server timestamps and GPS coordinates recorded.
    Late status calculated strictly on backend.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    shift = models.ForeignKey(Shift, on_delete=models.CASCADE, related_name="attendance_records")
    guard = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="attendance_records")
    clock_in = models.DateTimeField(null=True, blank=True)
    clock_out = models.DateTimeField(null=True, blank=True)
    clock_in_gps = models.CharField(max_length=100, blank=True, default="")
    clock_out_gps = models.CharField(max_length=100, blank=True, default="")
    is_late = models.BooleanField(default=False)
    is_serious_late = models.BooleanField(default=False)
    late_reason = models.TextField(blank=True, default="")
    escalation_notified = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]
        unique_together = ("shift", "guard")

    def __str__(self):
        status_str = "Clocked Out" if self.clock_out else ("Clocked In" if self.clock_in else "Pending")
        return f"{self.guard.username} Attendance - {self.shift.date} {self.shift.shift_type} ({status_str})"


class PublicHoliday(models.Model):
    """
    Authoritative calendar public holiday definition.
    Configurable and data-driven; not hardcoded.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=150)
    date = models.DateField(db_index=True)
    country_code = models.CharField(max_length=10, default="ZW", db_index=True)
    description = models.TextField(blank=True, default="")
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["date"]
        unique_together = ("date", "country_code")

    def __str__(self):
        return f"{self.name} ({self.date}) [{self.country_code}]"


class PublicHolidayDutyRecord(models.Model):
    """
    Authoritative record of a guard actually working on a configured public holiday.
    Links PublicHoliday -> Shift -> Attendance.
    Prevents duplicate records for the same worked shift.
    TIME_OFF / OFF shifts are strictly ineligible.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    public_holiday = models.ForeignKey(
        PublicHoliday,
        on_delete=models.PROTECT,
        related_name="duty_records",
    )
    shift = models.OneToOneField(
        Shift,
        on_delete=models.CASCADE,
        related_name="holiday_duty_record",
    )
    guard = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="holiday_duty_records",
    )
    attendance = models.ForeignKey(
        Attendance,
        on_delete=models.CASCADE,
        related_name="holiday_duty_records",
    )
    compensated_days = models.DecimalField(max_digits=4, decimal_places=1, default=2.0)
    status = models.CharField(
        max_length=20,
        choices=HolidayCompensationStatus.choices,
        default=HolidayCompensationStatus.PENDING,
        db_index=True,
    )
    approved_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="approved_holiday_compensations",
    )
    approved_at = models.DateTimeField(null=True, blank=True)
    decision_reason = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        indexes = [
            models.Index(fields=["guard", "status"]),
            models.Index(fields=["public_holiday", "guard"]),
        ]

    def __str__(self):
        return f"Holiday Duty: {self.guard.username} on {self.public_holiday.name} ({self.shift.date}) - {self.get_status_display()}"

    def clean(self):
        super().clean()
        errors = {}
        if self.shift_id:
            if self.shift.shift_type == ShiftType.OFF or self.shift.assignment_type == AssignmentType.TIME_OFF:
                errors.setdefault("shift", []).append("TIME_OFF/OFF shifts are not eligible for public holiday duty records.")
            if self.public_holiday_id and self.shift.date != self.public_holiday.date:
                errors.setdefault("shift", []).append(
                    f"Shift date ({self.shift.date}) does not match public holiday date ({self.public_holiday.date})."
                )
            if self.attendance_id:
                if self.attendance.shift_id != self.shift_id:
                    errors.setdefault("attendance", []).append("Attendance record does not belong to the duty shift.")
                if not self.attendance.clock_in:
                    errors.setdefault("attendance", []).append("Attendance must have a clock-in record proving duty was worked.")

        if self.status == HolidayCompensationStatus.APPROVED:
            if not self.approved_by_id:
                errors.setdefault("approved_by", []).append("Approved holiday duty record requires an approving user (approved_by).")
            if not self.approved_at:
                errors.setdefault("approved_at", []).append("Approved holiday duty record requires an approval timestamp (approved_at).")

        if errors:
            raise ValidationError(errors)


class DutyOverride(models.Model):
    """
    Formal operational duty override / leave interruption record.
    Authorizes a guard who is ON LEAVE or OFF DUTY to perform duty.
    Establishes an auditable chain:
    Leave -> Interrupted -> Worked -> Compensation Owed -> Compensation Taken.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    guard = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="duty_overrides",
    )
    station = models.ForeignKey(
        "stations.Station",
        on_delete=models.CASCADE,
        related_name="duty_overrides",
    )
    override_type = models.CharField(
        max_length=30,
        choices=DutyOverrideType.choices,
        default=DutyOverrideType.LEAVE_INTERRUPTION,
        db_index=True,
    )
    date = models.DateField(db_index=True)
    shift_type = models.CharField(
        max_length=10,
        choices=ShiftType.choices,
        default=ShiftType.DAY,
    )
    original_leave = models.ForeignKey(
        "leave.LeaveApplication",
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="duty_overrides",
    )
    original_off_shift = models.ForeignKey(
        Shift,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="off_day_overrides",
    )
    reason = models.TextField(help_text="Mandatory operational justification for duty override / interruption")
    authorized_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="authorized_duty_overrides",
    )
    shift_created = models.ForeignKey(
        Shift,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="originating_duty_override",
    )
    days_interrupted = models.DecimalField(max_digits=4, decimal_places=1, default=1.0)
    compensation_days_owed = models.DecimalField(max_digits=4, decimal_places=1, default=1.0)
    compensation_settled = models.BooleanField(default=False)
    status = models.CharField(
        max_length=20,
        choices=DutyOverrideStatus.choices,
        default=DutyOverrideStatus.ACTIVE,
        db_index=True,
    )
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-date", "-created_at"]
        indexes = [
            models.Index(fields=["guard", "date"]),
            models.Index(fields=["station", "date"]),
            models.Index(fields=["status"]),
        ]

    def __str__(self):
        return f"DutyOverride [{self.override_type}] {self.guard.username} @ {self.station.name} on {self.date} ({self.status})"

