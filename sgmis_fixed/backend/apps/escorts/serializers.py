from rest_framework import serializers
from .models import EscortDuty, EscortStatus

class EscortDutySerializer(serializers.ModelSerializer):
    guard_name = serializers.CharField(source="guard.get_full_name", read_only=True)
    guard_employee_id = serializers.CharField(source="guard.employee_id", read_only=True)
    supervisor_name = serializers.CharField(source="supervisor.get_full_name", read_only=True)
    station_name = serializers.CharField(source="station.name", read_only=True)
    status_display = serializers.CharField(source="get_status_display", read_only=True)

    class Meta:
        model = EscortDuty
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
            "mission_name",
            "origin",
            "destination",
            "purpose",
            "instructions",
            "contact_numbers",
            "start_time",
            "end_time",
            "departure_time",
            "completion_time",
            "acknowledged_at",
            "status",
            "status_display",
            "remarks",
            "notes",
            "created_at",
        ]
        read_only_fields = ["id", "reference", "created_at"]

    def validate(self, attrs):
        guard = attrs.get("guard") or (self.instance.guard if self.instance else None)
        start_time = attrs.get("start_time") or (self.instance.start_time if self.instance else None)
        end_time = attrs.get("end_time") or (self.instance.end_time if self.instance else None)

        if start_time and end_time and end_time <= start_time:
            raise serializers.ValidationError({"end_time": "End time must be strictly after start time."})

        if guard and start_time and end_time:
            from apps.shifts.services import validate_guard_duty_availability
            # Validate start date
            validate_guard_duty_availability(
                guard=guard,
                date=start_time.date(),
                start_time=start_time.time(),
                end_time=end_time.time() if start_time.date() == end_time.date() else None,
                duty_type="ESCORT",
                exclude_escort_id=self.instance.id if self.instance else None,
                as_drf=True,
            )
            # If escort spans across multiple dates, validate end date as well
            if end_time.date() != start_time.date():
                validate_guard_duty_availability(
                    guard=guard,
                    date=end_time.date(),
                    start_time=None,
                    end_time=end_time.time(),
                    duty_type="ESCORT",
                    exclude_escort_id=self.instance.id if self.instance else None,
                    as_drf=True,
                )

        return attrs

