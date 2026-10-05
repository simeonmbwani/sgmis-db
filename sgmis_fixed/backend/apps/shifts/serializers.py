from rest_framework import serializers
from .models import (
    Shift,
    ShiftHandover,
    Attendance,
    DutyRosterCycle,
    ExaminationPeriod,
    TemporaryAssignmentAudit,
    ShiftType,
    AssignmentType,
    DutyRoster,
    RosterStatus,
    PublicHoliday,
    PublicHolidayDutyRecord,
    HolidayCompensationStatus,
)

class ShiftSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)
    guard_name = serializers.SerializerMethodField()
    employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    partner = serializers.SerializerMethodField()
    partner_name = serializers.SerializerMethodField()
    partner_employee_number = serializers.SerializerMethodField()
    attendance_status = serializers.SerializerMethodField()
    duty_state = serializers.SerializerMethodField()
    leave_type = serializers.SerializerMethodField()
    late_report_required = serializers.SerializerMethodField()
    is_serious_late = serializers.SerializerMethodField()
    is_late = serializers.SerializerMethodField()
    clock_in_enabled = serializers.SerializerMethodField()
    clock_out_enabled = serializers.SerializerMethodField()

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
            "duty_state",
            "leave_type",
            "late_report_required",
            "is_serious_late",
            "is_late",
            "clock_in_enabled",
            "clock_out_enabled",
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
        if obj.assignment_type == AssignmentType.TIME_OFF or obj.shift_type == ShiftType.OFF:
            return "OFF_DUTY"
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

    def get_duty_state(self, obj):
        from apps.leave.models import LeaveApplication, LeaveStatus
        leave_app = getattr(obj, "_leave_app", None)
        if leave_app is None:
            leave_app = LeaveApplication.objects.filter(
                guard=obj.guard,
                status=LeaveStatus.APPROVED,
                start_date__lte=obj.date,
                end_date__gte=obj.date,
            ).first()
        if leave_app:
            return "ON_LEAVE"

        if obj.assignment_type == AssignmentType.TIME_OFF or obj.shift_type == ShiftType.OFF:
            return "TIME_OFF"

        att = getattr(obj, "_attendance_record", None)
        if att is None:
            att = Attendance.objects.filter(shift=obj, guard=obj.guard).first()

        if att and att.clock_in and not att.clock_out:
            from django.core.cache import cache
            if cache.get(f"early_clockout_otp_{obj.id}"):
                return "EARLY_EXIT_PENDING"
            return "ON_DUTY"

        if att and att.clock_out:
            return "OFF_DUTY"

        from django.utils import timezone
        from datetime import datetime, timedelta
        now = timezone.localtime(timezone.now())
        if obj.date != now.date():
            return "OFF_DUTY"

        sched_start_dt = timezone.make_aware(datetime.combine(obj.date, obj.start_time), timezone.get_current_timezone())
        if obj.end_time <= obj.start_time:
            sched_end_dt = timezone.make_aware(datetime.combine(obj.date + timedelta(days=1), obj.end_time), timezone.get_current_timezone())
        else:
            sched_end_dt = timezone.make_aware(datetime.combine(obj.date, obj.end_time), timezone.get_current_timezone())

        if now > sched_end_dt:
            return "OFF_DUTY"

        return "ELIGIBLE_FOR_DUTY"

    def get_leave_type(self, obj):
        from apps.leave.models import LeaveApplication, LeaveStatus
        leave_app = getattr(obj, "_leave_app", None)
        if leave_app is None:
            leave_app = LeaveApplication.objects.filter(
                guard=obj.guard,
                status=LeaveStatus.APPROVED,
                start_date__lte=obj.date,
                end_date__gte=obj.date,
            ).first()
        return leave_app.leave_type if leave_app else None

    def get_late_report_required(self, obj):
        att = getattr(obj, "_attendance_record", None)
        if att is None:
            att = Attendance.objects.filter(shift=obj, guard=obj.guard).first()
        if att and att.clock_in:
            return False
        from django.utils import timezone
        from datetime import datetime, timedelta
        now = timezone.localtime(timezone.now())
        if obj.date != now.date():
            return False
        sched_start_dt = timezone.make_aware(datetime.combine(obj.date, obj.start_time), timezone.get_current_timezone())
        return now >= (sched_start_dt + timedelta(minutes=60))

    def get_is_serious_late(self, obj):
        att = getattr(obj, "_attendance_record", None)
        if att is None:
            att = Attendance.objects.filter(shift=obj, guard=obj.guard).first()
        if att and att.is_serious_late:
            return True
        return self.get_late_report_required(obj)

    def get_is_late(self, obj):
        att = getattr(obj, "_attendance_record", None)
        if att is None:
            att = Attendance.objects.filter(shift=obj, guard=obj.guard).first()
        if att:
            return att.is_late
        from django.utils import timezone
        from datetime import datetime, timedelta
        now = timezone.localtime(timezone.now())
        if obj.date != now.date():
            return False
        sched_start_dt = timezone.make_aware(datetime.combine(obj.date, obj.start_time), timezone.get_current_timezone())
        return now > (sched_start_dt + timedelta(minutes=15))

    def get_clock_in_enabled(self, obj):
        if obj.assignment_type == AssignmentType.TIME_OFF or obj.shift_type == ShiftType.OFF:
            return False
        att = getattr(obj, "_attendance_record", None)
        if att is None:
            att = Attendance.objects.filter(shift=obj, guard=obj.guard).first()
        if att and att.clock_in:
            return False
        from django.utils import timezone
        from datetime import datetime, timedelta
        now = timezone.localtime(timezone.now())
        if obj.date != now.date():
            return False
        sched_start_dt = timezone.make_aware(datetime.combine(obj.date, obj.start_time), timezone.get_current_timezone())
        if obj.end_time <= obj.start_time:
            sched_end_dt = timezone.make_aware(datetime.combine(obj.date + timedelta(days=1), obj.end_time), timezone.get_current_timezone())
        else:
            sched_end_dt = timezone.make_aware(datetime.combine(obj.date, obj.end_time), timezone.get_current_timezone())
        reporting_open = sched_start_dt - timedelta(minutes=30)
        return reporting_open <= now <= sched_end_dt

    def get_clock_out_enabled(self, obj):
        if obj.assignment_type == AssignmentType.TIME_OFF or obj.shift_type == ShiftType.OFF:
            return False
        att = getattr(obj, "_attendance_record", None)
        if att is None:
            att = Attendance.objects.filter(shift=obj, guard=obj.guard).first()
        return bool(att and att.clock_in and not att.clock_out)

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
            "is_serious_late",
            "late_reason",
            "escalation_notified",
            "created_at",
        ]
        read_only_fields = ["id", "guard", "is_late", "is_serious_late", "escalation_notified", "created_at"]

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username

class ClockInRequestSerializer(serializers.Serializer):
    shift_id = serializers.UUIDField(required=True)
    latitude = serializers.FloatField(required=False, default=None)
    longitude = serializers.FloatField(required=False, default=None)
    late_reason = serializers.CharField(required=False, allow_blank=True, default="")
    case_number = serializers.CharField(required=False, allow_blank=True, default="")

class LateArrivalReportRequestSerializer(serializers.Serializer):
    shift_id = serializers.UUIDField(required=True)
    reason = serializers.CharField(required=True, min_length=5)
    latitude = serializers.FloatField(required=False, allow_null=True, default=None)
    longitude = serializers.FloatField(required=False, allow_null=True, default=None)

class ClockOutRequestSerializer(serializers.Serializer):
    shift_id = serializers.UUIDField(required=True)
    latitude = serializers.FloatField(required=False, allow_null=True, default=None)
    longitude = serializers.FloatField(required=False, allow_null=True, default=None)
    supervisor_username = serializers.CharField(required=False, allow_blank=True, allow_null=True, default="")
    supervisor_password = serializers.CharField(required=False, allow_blank=True, allow_null=True, default="", write_only=True)
    override_reason = serializers.CharField(required=False, allow_blank=True, allow_null=True, default="")
    otp_code = serializers.CharField(required=False, allow_blank=True, allow_null=True, default="")

class GenerateEarlyClockoutOTPRequestSerializer(serializers.Serializer):
    shift_id = serializers.UUIDField(required=True)
    reason = serializers.CharField(required=True, min_length=5)

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

class DutyRosterSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)
    status_display = serializers.CharField(source="get_status_display", read_only=True)
    approved_by_name = serializers.SerializerMethodField()
    shift_count = serializers.SerializerMethodField()

    class Meta:
        model = DutyRoster
        fields = [
            "id",
            "station",
            "station_name",
            "start_date",
            "end_date",
            "status",
            "status_display",
            "approved_by",
            "approved_by_name",
            "approved_at",
            "shift_count",
            "created_at",
            "updated_at",
        ]
        read_only_fields = [
            "id",
            "station_name",
            "status_display",
            "approved_by",
            "approved_by_name",
            "approved_at",
            "shift_count",
            "created_at",
            "updated_at",
        ]

    def get_approved_by_name(self, obj):
        if obj.approved_by:
            name = obj.approved_by.get_full_name().strip()
            return name if name else obj.approved_by.username
        return None

    def get_shift_count(self, obj):
        return obj.shifts.count()

class RosterValidateRequestSerializer(serializers.Serializer):
    roster_id = serializers.UUIDField(required=False, allow_null=True, default=None)
    station_id = serializers.UUIDField(required=False, allow_null=True, default=None)
    start_date = serializers.DateField(required=False, allow_null=True, default=None)
    end_date = serializers.DateField(required=False, allow_null=True, default=None)

    def validate(self, attrs):
        if not attrs.get("roster_id") and not attrs.get("station_id"):
            raise serializers.ValidationError("Either 'roster_id' or 'station_id' must be provided.")
        return attrs

class RosterApproveRequestSerializer(serializers.Serializer):
    roster_id = serializers.UUIDField(required=False, allow_null=True, default=None)
    station_id = serializers.UUIDField(required=False, allow_null=True, default=None)
    start_date = serializers.DateField(required=False, allow_null=True, default=None)
    end_date = serializers.DateField(required=False, allow_null=True, default=None)

    def validate(self, attrs):
        if not attrs.get("roster_id") and not attrs.get("station_id"):
            raise serializers.ValidationError("Either 'roster_id' or 'station_id' must be provided.")
        return attrs


class PublicHolidaySerializer(serializers.ModelSerializer):
    class Meta:
        model = PublicHoliday
        fields = [
            "id",
            "name",
            "date",
            "country_code",
            "description",
            "is_active",
            "created_at",
            "updated_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at"]


class PublicHolidayDutyRecordSerializer(serializers.ModelSerializer):
    public_holiday_name = serializers.CharField(source="public_holiday.name", read_only=True)
    public_holiday_date = serializers.DateField(source="public_holiday.date", read_only=True)
    shift_date = serializers.DateField(source="shift.date", read_only=True)
    shift_type = serializers.CharField(source="shift.shift_type", read_only=True)
    station_id = serializers.UUIDField(source="shift.station.id", read_only=True)
    station_name = serializers.CharField(source="shift.station.name", read_only=True)
    guard_name = serializers.SerializerMethodField()
    guard_employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    approved_by_name = serializers.SerializerMethodField()
    status_display = serializers.CharField(source="get_status_display", read_only=True)

    class Meta:
        model = PublicHolidayDutyRecord
        fields = [
            "id",
            "public_holiday",
            "public_holiday_name",
            "public_holiday_date",
            "shift",
            "shift_date",
            "shift_type",
            "station_id",
            "station_name",
            "guard",
            "guard_name",
            "guard_employee_number",
            "attendance",
            "compensated_days",
            "status",
            "status_display",
            "approved_by",
            "approved_by_name",
            "approved_at",
            "decision_reason",
            "created_at",
            "updated_at",
        ]
        read_only_fields = [
            "id",
            "public_holiday_name",
            "public_holiday_date",
            "shift_date",
            "shift_type",
            "station_id",
            "station_name",
            "guard_name",
            "guard_employee_number",
            "approved_by",
            "approved_by_name",
            "approved_at",
            "created_at",
            "updated_at",
        ]

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username

    def get_approved_by_name(self, obj):
        if obj.approved_by:
            name = obj.approved_by.get_full_name().strip()
            return name if name else obj.approved_by.username
        return None


class RecordHolidayDutyRequestSerializer(serializers.Serializer):
    shift_id = serializers.UUIDField(required=True)


class ReviewHolidayCompensationRequestSerializer(serializers.Serializer):
    reason = serializers.CharField(required=False, allow_blank=True, default="")
