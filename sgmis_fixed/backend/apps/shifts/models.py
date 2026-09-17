import uuid
from django.db import models
from django.conf import settings

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
    late_reason = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]
        unique_together = ("shift", "guard")

    def __str__(self):
        status_str = "Clocked Out" if self.clock_out else ("Clocked In" if self.clock_in else "Pending")
        return f"{self.guard.username} Attendance - {self.shift.date} {self.shift.shift_type} ({status_str})"
