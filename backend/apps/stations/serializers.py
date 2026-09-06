from rest_framework import serializers
from .models import Station, GuardPair
from apps.accounts.serializers import UserSerializer

class StationSerializer(serializers.ModelSerializer):
    guards_count = serializers.IntegerField(source="assigned_guards.count", read_only=True)
    pairs_count = serializers.IntegerField(source="pairs.count", read_only=True)

    class Meta:
        model = Station
        fields = [
            "id",
            "name",
            "code",
            "address",
            "latitude",
            "longitude",
            "geofence_radius_meters",
            "is_active",
            "guards_count",
            "pairs_count",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]

class GuardPairSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)
    guard_a_name = serializers.SerializerMethodField()
    guard_b_name = serializers.SerializerMethodField()
    guard_a_employee_number = serializers.CharField(source="guard_a.employee_number", read_only=True)
    guard_b_employee_number = serializers.CharField(source="guard_b.employee_number", read_only=True)

    class Meta:
        model = GuardPair
        fields = [
            "id",
            "station",
            "station_name",
            "guard_a",
            "guard_a_name",
            "guard_a_employee_number",
            "guard_b",
            "guard_b_name",
            "guard_b_employee_number",
            "rotation_order",
            "is_active",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]

    def get_guard_a_name(self, obj):
        name = obj.guard_a.get_full_name().strip()
        return name if name else obj.guard_a.username

    def get_guard_b_name(self, obj):
        name = obj.guard_b.get_full_name().strip()
        return name if name else obj.guard_b.username
