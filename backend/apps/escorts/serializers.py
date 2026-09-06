from rest_framework import serializers
from .models import EscortDuty

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
