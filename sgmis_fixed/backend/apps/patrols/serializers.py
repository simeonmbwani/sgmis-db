from rest_framework import serializers
from .models import Checkpoint, PatrolLog, CheckpointScan

class CheckpointSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)

    class Meta:
        model = Checkpoint
        fields = [
            "id",
            "station",
            "station_name",
            "name",
            "code",
            "qr_code",
            "latitude",
            "longitude",
            "order",
            "is_active",
        ]

class CheckpointScanSerializer(serializers.ModelSerializer):
    checkpoint_name = serializers.CharField(source="checkpoint.name", read_only=True)
    checkpoint_code = serializers.CharField(source="checkpoint.code", read_only=True)

    class Meta:
        model = CheckpointScan
        fields = [
            "id",
            "patrol_log",
            "checkpoint",
            "checkpoint_name",
            "checkpoint_code",
            "scanned_at",
            "gps_coords",
            "notes",
        ]
        read_only_fields = ["id", "scanned_at"]

class PatrolLogSerializer(serializers.ModelSerializer):
    guard_name = serializers.SerializerMethodField()
    station_name = serializers.CharField(source="station.name", read_only=True)
    scans = CheckpointScanSerializer(many=True, read_only=True)
    scans_count = serializers.IntegerField(source="scans.count", read_only=True)

    class Meta:
        model = PatrolLog
        fields = [
            "id",
            "guard",
            "guard_name",
            "station",
            "station_name",
            "start_time",
            "end_time",
            "status",
            "notes",
            "scans_count",
            "scans",
        ]
        read_only_fields = ["id", "guard", "start_time"]
        extra_kwargs = {
            "station": {"required": False, "allow_null": True},
        }

    def validate(self, attrs):
        request = self.context.get("request")
        user = getattr(request, "user", None)

        station = attrs.get("station")
        if user and getattr(user, "role", None) == "GUARD":
            target_station = getattr(user, "station", None)
            if not target_station:
                from apps.shifts.models import Shift
                from django.utils import timezone
                today_shift = Shift.objects.filter(guard=user, date=timezone.now().date()).select_related("station").first()
                if today_shift and today_shift.station:
                    target_station = today_shift.station
            if not target_station:
                raise serializers.ValidationError({
                    "station": "Your account has no station assigned. Contact your supervisor or administrator."
                })
            attrs["station"] = target_station
        elif not station:
            if user and getattr(user, "station", None):
                attrs["station"] = user.station
            else:
                raise serializers.ValidationError({
                    "station": "A valid station is required. Your account has no station assigned."
                })
        elif station and not getattr(station, "is_active", True):
            raise serializers.ValidationError({
                "station": f"Station '{station.name}' is inactive."
            })
        return attrs

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username
