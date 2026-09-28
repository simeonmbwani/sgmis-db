from rest_framework import serializers
from apps.accounts.models import User, UserRole
from apps.stations.models import Station
from .models import RecordAdjustmentRequest, AdjustmentStatus

class RecordAdjustmentRequestSerializer(serializers.ModelSerializer):
    guard_name = serializers.CharField(source="guard.get_full_name", read_only=True)
    guard_employee_id = serializers.CharField(source="guard.employee_id", read_only=True)
    requested_by_name = serializers.CharField(source="requested_by.get_full_name", read_only=True)
    reviewed_by_name = serializers.CharField(source="reviewed_by.get_full_name", read_only=True)
    status_display = serializers.CharField(source="get_status_display", read_only=True)

    class Meta:
        model = RecordAdjustmentRequest
        fields = [
            "id",
            "guard",
            "guard_name",
            "guard_employee_id",
            "field_name",
            "old_value",
            "requested_value",
            "approved_value",
            "effective_date",
            "reason",
            "notes",
            "status",
            "status_display",
            "requested_by",
            "requested_by_name",
            "reviewed_by",
            "reviewed_by_name",
            "reviewed_at",
            "rejection_reason",
            "created_at",
        ]
        read_only_fields = [
            "id",
            "guard_name",
            "guard_employee_id",
            "requested_by",
            "requested_by_name",
            "reviewed_by",
            "reviewed_by_name",
            "reviewed_at",
            "status_display",
            "created_at",
        ]

    def validate_field_name(self, value):
        allowed_fields = [
            "employee_number",
            "employee_id",
            "first_name",
            "last_name",
            "station",
            "shift_type",
            "shift",
            "pair",
            "pair_guard",
            "roster_position",
            "vacation_balance",
            "casual_balance",
            "special_balance",
            "compensation_days",
        ]
        val_clean = value.strip().lower()
        if val_clean not in allowed_fields:
            raise serializers.ValidationError(
                f"Field '{value}' is not a recognized adjustable field. Allowed fields: {', '.join(allowed_fields)}."
            )
        return val_clean

    def validate(self, attrs):
        reason = attrs.get("reason", "").strip()
        if not reason:
            raise serializers.ValidationError({"reason": "Operational justification or physical record citation is mandatory."})
        return attrs
