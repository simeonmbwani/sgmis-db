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
            "nfc_uid",
            "latitude",
            "longitude",
            "order",
            "min_interval_seconds",
            "is_active",
        ]

class CheckpointScanSerializer(serializers.ModelSerializer):
    checkpoint_name = serializers.CharField(source="checkpoint.name", read_only=True)
    checkpoint_code = serializers.CharField(source="checkpoint.code", read_only=True)

    class Meta:
        model = CheckpointScan
        fields = [
            "id",
            "client_event_id",
            "patrol_log",
            "checkpoint",
            "checkpoint_name",
            "checkpoint_code",
            "scanned_at",
            "client_timestamp",
            "gps_coords",
            "accuracy",
            "verification_method",
            "notes",
        ]
        read_only_fields = ["id", "scanned_at"]

class PatrolLogSerializer(serializers.ModelSerializer):
    guard_name = serializers.SerializerMethodField()
    station_name = serializers.CharField(source="station.name", read_only=True)
    assigned_by_name = serializers.SerializerMethodField()
    approved_by_name = serializers.SerializerMethodField()
    scans = CheckpointScanSerializer(many=True, read_only=True)
    scans_count = serializers.IntegerField(source="scans.count", read_only=True)
    anomalies_count = serializers.SerializerMethodField()

    class Meta:
        model = PatrolLog
        fields = [
            "id",
            "name",
            "guard",
            "guard_name",
            "station",
            "station_name",
            "assigned_by",
            "assigned_by_name",
            "start_window",
            "deadline",
            "start_time",
            "end_time",
            "status",
            "is_approved",
            "approved_by",
            "approved_by_name",
            "approved_at",
            "anomalies",
            "anomalies_count",
            "notes",
            "scans_count",
            "scans",
        ]
        read_only_fields = ["id", "start_time", "is_approved", "approved_by", "approved_at", "assigned_by"]
        extra_kwargs = {
            "guard": {"required": False},
            "station": {"required": False, "allow_null": True},
        }

    def get_anomalies_count(self, obj):
        if isinstance(obj.anomalies, list):
            return len(obj.anomalies)
        return 0

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
            attrs["guard"] = user
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
        if obj.guard:
            name = obj.guard.get_full_name().strip()
            return name if name else obj.guard.username
        return ""

    def get_assigned_by_name(self, obj):
        if obj.assigned_by:
            name = obj.assigned_by.get_full_name().strip()
            return name if name else obj.assigned_by.username
        return None

    def get_approved_by_name(self, obj):
        if obj.approved_by:
            name = obj.approved_by.get_full_name().strip()
            return name if name else obj.approved_by.username
        return None
