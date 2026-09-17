from rest_framework import serializers
from .models import EscortDuty, EscortStatus

class EscortDutySerializer(serializers.ModelSerializer):
    guard_name = serializers.CharField(source="guard.get_full_name", read_only=True)
    status_display = serializers.CharField(source="get_status_display", read_only=True)

    class Meta:
        model = EscortDuty
        fields = [
            "id",
            "guard",
            "guard_name",
            "mission_name",
            "origin",
            "destination",
            "start_time",
            "end_time",
            "status",
            "status_display",
            "notes",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]

    def validate(self, attrs):
        guard = attrs.get("guard") or (self.instance.guard if self.instance else None)
        start_time = attrs.get("start_time") or (self.instance.start_time if self.instance else None)
        end_time = attrs.get("end_time") or (self.instance.end_time if self.instance else None)

        if start_time and end_time and end_time <= start_time:
            raise serializers.ValidationError({"end_time": "End time must be strictly after start time."})

        if guard and start_time and end_time:
            # 1. Overlapping escort duties
            overlapping_escorts = EscortDuty.objects.filter(
                guard=guard,
                status__in=[EscortStatus.SCHEDULED, EscortStatus.EN_ROUTE],
                start_time__lt=end_time,
                end_time__gt=start_time,
            )
            if self.instance:
                overlapping_escorts = overlapping_escorts.exclude(id=self.instance.id)

            if overlapping_escorts.exists():
                raise serializers.ValidationError(
                    {"guard": "Guard already assigned to active duty on another post. Overlapping assignment rejected to prevent post abandonment."}
                )

            # 2. Overlapping static station shifts
            from apps.shifts.models import Shift
            overlapping_shifts = Shift.objects.filter(
                guard=guard,
                date__in=[start_time.date(), end_time.date()],
            )
            if overlapping_shifts.exists():
                raise serializers.ValidationError(
                    {"guard": "Guard already assigned to active duty on another post. Overlapping assignment rejected to prevent post abandonment."}
                )

        return attrs
