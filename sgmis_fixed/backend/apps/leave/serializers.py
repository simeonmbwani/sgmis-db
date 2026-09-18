from decimal import Decimal
from rest_framework import serializers
from .models import LeaveBalance, LeaveApplication, LeaveType, PublicHolidayCompensationLedger


class LeaveBalanceSerializer(serializers.ModelSerializer):
    remaining_annual = serializers.ReadOnlyField()
    remaining_sick = serializers.ReadOnlyField()
    remaining_casual = serializers.ReadOnlyField()
    remaining_vacation = serializers.ReadOnlyField()
    compensation_earned = serializers.ReadOnlyField()
    compensation_used = serializers.ReadOnlyField()
    remaining_compensation = serializers.ReadOnlyField()
    guard_name = serializers.CharField(source="guard.get_full_name", read_only=True)

    class Meta:
        model = LeaveBalance
        fields = [
            "id", "guard", "guard_name", "year",
            "annual_days", "sick_days", "used_annual", "used_sick", "remaining_annual", "remaining_sick",
            "casual_days", "vacation_days", "used_casual", "used_vacation",
            "casual_accrual_rate", "vacation_accrual_rate", "vacation_cap",
            "remaining_casual", "remaining_vacation",
            "compensation_earned", "compensation_used", "remaining_compensation",
            "last_accrual_date", "casual_cycle_start",
        ]
        read_only_fields = [
            "id", "guard", "remaining_casual", "remaining_vacation",
            "remaining_annual", "remaining_sick",
            "compensation_earned", "compensation_used", "remaining_compensation",
        ]

    def to_representation(self, instance):
        instance.accrue_to_date()
        return super().to_representation(instance)


class LeaveApplicationSerializer(serializers.ModelSerializer):
    guard_name = serializers.SerializerMethodField()
    guard_employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    reviewer_name = serializers.SerializerMethodField()
    leave_type_display = serializers.CharField(source="get_leave_type_display", read_only=True)
    status_display = serializers.CharField(source="get_status_display", read_only=True)
    rejection_reason_display = serializers.CharField(source="get_rejection_reason_display", read_only=True)

    class Meta:
        model = LeaveApplication
        fields = [
            "id", "guard", "guard_name", "guard_employee_number",
            "leave_type", "leave_type_display", "start_date", "end_date",
            "reason", "emergency_phone", "emergency_address",
            "status", "status_display", "reviewer", "reviewer_name",
            "reviewer_notes", "rejection_reason", "rejection_reason_display",
            "created_at", "updated_at"
        ]
        read_only_fields = ["id", "guard", "reviewer", "created_at", "updated_at"]

    def validate(self, attrs):
        if attrs["end_date"] < attrs["start_date"]:
            raise serializers.ValidationError({"end_date": "End date cannot be before start date."})

        # Validate requested leave days against authoritative server balance
        days = (attrs["end_date"] - attrs["start_date"]).days + 1
        request = self.context.get("request")
        user = request.user if request and request.user.is_authenticated else None
        leave_type = attrs.get("leave_type")

        if user and leave_type == LeaveType.COMPENSATION:
            remaining = PublicHolidayCompensationLedger.get_remaining_for_guard(user)
            if Decimal(str(days)) > remaining:
                raise serializers.ValidationError({
                    "detail": f"Insufficient public holiday compensation balance. Requested: {days} days, Remaining: {remaining} days."
                })
        elif user and leave_type == LeaveType.VACATION:
            balance, _ = LeaveBalance.objects.get_or_create(guard=user, year=attrs["start_date"].year)
            balance.accrue_to_date(attrs["start_date"])
            if balance.remaining_vacation < days:
                raise serializers.ValidationError({
                    "detail": f"Insufficient vacation leave balance. Requested: {days} days, Remaining: {balance.remaining_vacation} days."
                })
        elif user and leave_type == LeaveType.CASUAL:
            balance, _ = LeaveBalance.objects.get_or_create(guard=user, year=attrs["start_date"].year)
            balance.accrue_to_date(attrs["start_date"])
            if balance.remaining_casual < days:
                raise serializers.ValidationError({
                    "detail": f"Insufficient casual leave balance. Requested: {days} days, Remaining: {balance.remaining_casual} days."
                })

        return attrs

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username

    def get_reviewer_name(self, obj):
        if not obj.reviewer:
            return None
        name = obj.reviewer.get_full_name().strip()
        return name if name else obj.reviewer.username


class PublicHolidayCompensationLedgerSerializer(serializers.ModelSerializer):
    guard_name = serializers.CharField(source="guard.get_full_name", read_only=True)
    created_by_name = serializers.CharField(source="created_by.get_full_name", read_only=True)

    class Meta:
        model = PublicHolidayCompensationLedger
        fields = [
            "id", "guard", "guard_name", "entry_type", "days",
            "duty_record", "leave_application", "notes",
            "created_by", "created_by_name", "created_at",
        ]
        read_only_fields = fields


class LeaveCategorySummarySerializer(serializers.Serializer):
    category = serializers.CharField()
    metric_label = serializers.CharField()
    accrued_or_earned = serializers.FloatField()
    used = serializers.FloatField()
    remaining = serializers.FloatField()
    policy_note = serializers.CharField(required=False, allow_blank=True, default="")


class GuardLeaveSummarySerializer(serializers.Serializer):
    guard_id = serializers.UUIDField()
    guard_name = serializers.CharField()
    guard_employee_number = serializers.CharField(allow_blank=True, default="")
    year = serializers.IntegerField()
    categories = LeaveCategorySummarySerializer(many=True)
    vacation = serializers.DictField()
    casual = serializers.DictField()
    compensation = serializers.DictField()
