from rest_framework import serializers
from .models import ExamDuty

class ExamDutySerializer(serializers.ModelSerializer):
    guard_name = serializers.CharField(source="guard.get_full_name", read_only=True)
    guard_employee_id = serializers.CharField(source="guard.employee_id", read_only=True)
    supervisor_name = serializers.CharField(source="supervisor.get_full_name", read_only=True)
    station_name = serializers.CharField(source="station.name", read_only=True)

    class Meta:
        model = ExamDuty
        fields = [
            "id",
            "reference",
            "guard",
            "guard_name",
            "guard_employee_id",
            "supervisor",
            "supervisor_name",
            "station",
            "station_name",
            "institution",
            "exam_title",
            "hall_post",
            "supervisor_contact",
            "instructions",
            "date",
            "reporting_time",
            "start_time",
            "end_time",
            "acknowledged_at",
            "status",
            "remarks",
            "notes",
            "created_at",
        ]
        read_only_fields = ["id", "reference", "created_at"]
