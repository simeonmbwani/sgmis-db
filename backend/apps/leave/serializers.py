from rest_framework import serializers
from .models import LeaveBalance, LeaveApplication

class LeaveBalanceSerializer(serializers.ModelSerializer):
    remaining_annual = serializers.ReadOnlyField()
    remaining_sick = serializers.ReadOnlyField()
    guard_name = serializers.CharField(source="guard.get_full_name", read_only=True)

    class Meta:
        model = LeaveBalance
        fields = [
            "id",
            "guard",
            "guard_name",
            "year",
            "annual_days",
            "sick_days",
            "used_annual",
            "used_sick",
            "remaining_annual",
            "remaining_sick",
        ]
        read_only_fields = ["id", "guard"]

class LeaveApplicationSerializer(serializers.ModelSerializer):
    guard_name = serializers.SerializerMethodField()
    guard_employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    reviewer_name = serializers.SerializerMethodField()
    leave_type_display = serializers.CharField(source="get_leave_type_display", read_only=True)
    status_display = serializers.CharField(source="get_status_display", read_only=True)

    class Meta:
        model = LeaveApplication
        fields = [
            "id",
            "guard",
            "guard_name",
            "guard_employee_number",
            "leave_type",
            "leave_type_display",
            "start_date",
            "end_date",
            "reason",
            "status",
            "status_display",
            "reviewer",
            "reviewer_name",
            "reviewer_notes",
            "created_at",
            "updated_at",
        ]
        read_only_fields = ["id", "guard", "reviewer", "created_at", "updated_at"]

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username

    def get_reviewer_name(self, obj):
        if not obj.reviewer:
            return None
        name = obj.reviewer.get_full_name().strip()
        return name if name else obj.reviewer.username
