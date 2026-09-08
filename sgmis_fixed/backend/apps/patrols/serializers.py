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

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username
