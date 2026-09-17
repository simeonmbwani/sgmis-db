from rest_framework import serializers
from .models import Shift, ShiftHandover, Attendance, DutyRosterCycle, ExaminationPeriod, TemporaryAssignmentAudit

class ShiftSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)
    guard_name = serializers.SerializerMethodField()
    employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    partner = serializers.SerializerMethodField()
    partner_name = serializers.SerializerMethodField()
    partner_employee_number = serializers.SerializerMethodField()
    attendance_status = serializers.SerializerMethodField()

    class Meta:
        model = Shift
        fields = [
            "id",
            "station",
            "station_name",
            "guard",
            "guard_name",
            "employee_number",
            "date",
            "start_time",
            "end_time",
            "shift_type",
            "assignment_type",
            "duty_location",
            "examination_period",
            "pair",
            "partner",
            "partner_name",
            "partner_employee_number",
            "is_override",
            "override_reason",
            "attendance_status",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username

    def _get_partner_obj(self, obj):
        if not hasattr(obj, "_cached_partner"):
            obj._cached_partner = obj.get_partner()
        return obj._cached_partner

    def get_partner(self, obj):
        partner = self._get_partner_obj(obj)
        return str(partner.id) if partner else None

    def get_partner_name(self, obj):
        partner = self._get_partner_obj(obj)
        if partner:
            name = partner.get_full_name().strip()
            return name if name else partner.username
        return None

    def get_partner_employee_number(self, obj):
        partner = self._get_partner_obj(obj)
        return partner.employee_number if partner else None

    def get_attendance_status(self, obj):
        # Check if an attendance record exists for this shift
        att = getattr(obj, "_attendance_record", None)
        if att is None:
            att = Attendance.objects.filter(shift=obj, guard=obj.guard).first()
        if not att:
            return "NOT_CLOCKED_IN"
        if att.clock_out:
            return "CLOCKED_OUT"
        if att.clock_in:
            return "CLOCKED_IN"
        return "NOT_CLOCKED_IN"

class ShiftHandoverSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)
    outgoing_guard_name = serializers.SerializerMethodField()
    incoming_guard_name = serializers.SerializerMethodField()
    outgoing_shift_details = serializers.SerializerMethodField()
    is_rejected = serializers.SerializerMethodField()

    class Meta:
        model = ShiftHandover
        fields = [
            "id",
            "outgoing_shift",
            "outgoing_shift_details",
            "outgoing_guard",
            "outgoing_guard_name",
            "incoming_guard",
            "incoming_guard_name",
            "station",
            "station_name",
            "occurrence_summary",
            "equipment_issued",
            "keys_handed_over",
            "pending_issues",
            "outgoing_signed",
            "incoming_accepted",
            "incoming_accepted_at",
            "is_rejected",
            "created_at",
        ]
        read_only_fields = [
            "id",
            "outgoing_guard",
            "incoming_guard",
            "station",
            "incoming_accepted",
            "incoming_accepted_at",
            "is_rejected",
            "created_at",
        ]

    def get_outgoing_guard_name(self, obj):
        name = obj.outgoing_guard.get_full_name().strip()
        return name if name else obj.outgoing_guard.username

    def get_incoming_guard_name(self, obj):
        name = obj.incoming_guard.get_full_name().strip()
        return name if name else obj.incoming_guard.username

    def get_outgoing_shift_details(self, obj):
        shift = obj.outgoing_shift
        return f"{shift.date} {shift.shift_type} ({shift.start_time.strftime('%H:%M')}-{shift.end_time.strftime('%H:%M')})"

    def get_is_rejected(self, obj):
        return "[REJECTED" in (obj.pending_issues or "")


class AttendanceSerializer(serializers.ModelSerializer):
    guard_name = serializers.SerializerMethodField()
    guard_employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    station_name = serializers.CharField(source="shift.station.name", read_only=True)
    shift_type = serializers.CharField(source="shift.shift_type", read_only=True)
    shift_date = serializers.DateField(source="shift.date", read_only=True)

    class Meta:
        model = Attendance
        fields = [
            "id",
            "shift",
            "shift_date",
            "shift_type",
            "guard",
            "guard_name",
            "guard_employee_number",
            "station_name",
            "clock_in",
            "clock_out",
            "clock_in_gps",
            "clock_out_gps",
            "is_late",
            "late_reason",
            "created_at",
        ]
        read_only_fields = ["id", "guard", "is_late", "created_at"]

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username

class ClockInRequestSerializer(serializers.Serializer):
    shift_id = serializers.UUIDField(required=True)
    latitude = serializers.FloatField(required=False, default=None)
    longitude = serializers.FloatField(required=False, default=None)
    late_reason = serializers.CharField(required=False, allow_blank=True, default="")

class ClockOutRequestSerializer(serializers.Serializer):
    shift_id = serializers.UUIDField(required=True)
    latitude = serializers.FloatField(required=False, default=None)
    longitude = serializers.FloatField(required=False, default=None)

class ExaminationPeriodSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)
    authorized_by_name = serializers.SerializerMethodField()

    class Meta:
        model = ExaminationPeriod
        fields = [
            "id",
            "station",
            "station_name",
            "name",
            "venue_name",
            "start_date",
            "end_date",
            "is_active",
            "authorized_by",
            "authorized_by_name",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]

    def get_authorized_by_name(self, obj):
        if obj.authorized_by:
            name = obj.authorized_by.get_full_name().strip()
            return name if name else obj.authorized_by.username
        return None

class TemporaryAssignmentAuditSerializer(serializers.ModelSerializer):
    guard_name = serializers.SerializerMethodField()
    guard_employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    authorized_by_name = serializers.SerializerMethodField()
    original_pair_name = serializers.SerializerMethodField()

    class Meta:
        model = TemporaryAssignmentAudit
        fields = [
            "id",
            "guard",
            "guard_name",
            "guard_employee_number",
            "original_pair",
            "original_pair_name",
            "original_assignment",
            "temporary_assignment",
            "location",
            "start_date",
            "end_date",
            "start_time",
            "end_time",
            "reason",
            "authorized_by",
            "authorized_by_name",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username

    def get_authorized_by_name(self, obj):
        if obj.authorized_by:
            name = obj.authorized_by.get_full_name().strip()
            return name if name else obj.authorized_by.username
        return None

    def get_original_pair_name(self, obj):
        if obj.original_pair:
            return f"Pair {obj.original_pair.rotation_order} ({obj.original_pair.station.name})"
        return "Independent"

class RosterGenerateRequestSerializer(serializers.Serializer):
    station_id = serializers.UUIDField(required=True)
    start_date = serializers.DateField(required=True)
    cycle_days = serializers.IntegerField(default=12, min_value=1, max_value=60)
    mode = serializers.ChoiceField(choices=["NORMAL", "EXAM"], default="NORMAL")
    examination_period_id = serializers.UUIDField(required=False, allow_null=True, default=None)
    exam_venue_name = serializers.CharField(required=False, default="Exam Venue")
    exam_guard_ids = serializers.ListField(
        child=serializers.UUIDField(), required=False, allow_empty=True, default=list
    )

class ScheduleExamEscortSerializer(serializers.Serializer):
    station_id = serializers.UUIDField(required=True)
    date = serializers.DateField(required=True)
    guard_ids = serializers.ListField(
        child=serializers.UUIDField(), min_length=2, max_length=2, required=True
    )
    start_time = serializers.TimeField(required=False, default="06:00:00")
    end_time = serializers.TimeField(required=False, default="17:00:00")
    reason = serializers.CharField(
        required=False, default="Examination paper collection escort to University National Centre"
    )

class ResumeNormalRosterSerializer(serializers.Serializer):
    station_id = serializers.UUIDField(required=True)
    after_date = serializers.DateField(required=True)
    cycle_days = serializers.IntegerField(default=12, min_value=1, max_value=60)

class DetectConflictsRequestSerializer(serializers.Serializer):
    station_id = serializers.UUIDField(required=True)
    start_date = serializers.DateField(required=False, allow_null=True, default=None)
    end_date = serializers.DateField(required=False, allow_null=True, default=None)
