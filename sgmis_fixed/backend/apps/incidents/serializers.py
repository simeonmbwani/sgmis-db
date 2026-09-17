from rest_framework import serializers
from .models import IncidentReport, IncidentAmendment

class IncidentAmendmentSerializer(serializers.ModelSerializer):
    amended_by_name = serializers.SerializerMethodField()

    class Meta:
        model = IncidentAmendment
        fields = [
            "id",
            "incident",
            "amended_by",
            "amended_by_name",
            "reason",
            "original_description_snapshot",
            "amended_description",
            "created_at",
        ]
        read_only_fields = ["id", "amended_by", "original_description_snapshot", "created_at"]

    def get_amended_by_name(self, obj):
        name = obj.amended_by.get_full_name().strip()
        return name if name else obj.amended_by.username

class IncidentReportSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)
    reporting_guard_name = serializers.SerializerMethodField()
    reporting_guard_employee_number = serializers.CharField(source="reporting_guard.employee_number", read_only=True)
    acknowledged_by_name = serializers.SerializerMethodField()
    assigned_to_name = serializers.SerializerMethodField()
    priority_display = serializers.CharField(source="get_priority_display", read_only=True)
    status_display = serializers.CharField(source="get_status_display", read_only=True)
    amendments = IncidentAmendmentSerializer(many=True, read_only=True)

    class Meta:
        extra_kwargs = {"station": {"required": False}}
        model = IncidentReport
        fields = [
            "id",
            "station",
            "station_name",
            "reporting_guard",
            "reporting_guard_name",
            "reporting_guard_employee_number",
            "priority",
            "priority_display",
            "title",
            "description",
            "location",
            "status",
            "status_display",
            "acknowledged_by",
            "acknowledged_by_name",
            "assigned_to",
            "assigned_to_name",
            "escalated_to_admin",
            "resolution_notes",
            "is_archived",
            "amendments",
            "created_at",
            "updated_at",
        ]
        read_only_fields = ["id", "reporting_guard", "acknowledged_by", "created_at", "updated_at"]

    def get_reporting_guard_name(self, obj):
        name = obj.reporting_guard.get_full_name().strip()
        return name if name else obj.reporting_guard.username

    def get_acknowledged_by_name(self, obj):
        if not obj.acknowledged_by:
            return None
        name = obj.acknowledged_by.get_full_name().strip()
        return name if name else obj.acknowledged_by.username

    def get_assigned_to_name(self, obj):
        if not obj.assigned_to:
            return None
        name = obj.assigned_to.get_full_name().strip()
        return name if name else obj.assigned_to.username
