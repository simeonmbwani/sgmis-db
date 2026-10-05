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

    def validate(self, attrs):
        guard = attrs.get("guard") or (self.instance.guard if self.instance else None)
        date = attrs.get("date") or (self.instance.date if self.instance else None)
        start_time = attrs.get("start_time") or (self.instance.start_time if self.instance else None)
        end_time = attrs.get("end_time") or (self.instance.end_time if self.instance else None)

        if guard and date:
            from apps.shifts.services import validate_guard_duty_availability
            validate_guard_duty_availability(
                guard=guard,
                date=date,
                start_time=start_time,
                end_time=end_time,
                duty_type="EXAM",
                exclude_exam_id=self.instance.id if self.instance else None,
                as_drf=True,
            )
        return attrs

