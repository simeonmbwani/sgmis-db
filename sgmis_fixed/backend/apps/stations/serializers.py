from django.core.exceptions import ValidationError as DjangoValidationError
from rest_framework import serializers
from rest_framework.settings import api_settings
from .models import Station, GuardPair
from apps.accounts.serializers import UserSerializer

class StationSerializer(serializers.ModelSerializer):
    guards_count = serializers.IntegerField(source="assigned_guards.count", read_only=True)
    pairs_count = serializers.IntegerField(source="pairs.count", read_only=True)
    geofence_radius = serializers.FloatField(source="geofence_radius_meters", required=False)

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
            "geofence_radius",
            "is_active",
            "guards_count",
            "pairs_count",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]

    def validate_code(self, value):
        return value.strip().upper()

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

    def validate(self, attrs):
        if self.instance:
            instance = GuardPair(
                id=self.instance.id,
                station=attrs.get("station", self.instance.station),
                guard_a=attrs.get("guard_a", self.instance.guard_a),
                guard_b=attrs.get("guard_b", self.instance.guard_b),
                rotation_order=attrs.get("rotation_order", self.instance.rotation_order),
                is_active=attrs.get("is_active", self.instance.is_active),
            )
        else:
            instance = GuardPair(
                station=attrs.get("station"),
                guard_a=attrs.get("guard_a"),
                guard_b=attrs.get("guard_b"),
                rotation_order=attrs.get("rotation_order", 1),
                is_active=attrs.get("is_active", True),
            )

        try:
            instance.clean()
        except DjangoValidationError as e:
            serializer_error = serializers.as_serializer_error(e)
            if "__all__" in serializer_error:
                serializer_error[api_settings.NON_FIELD_ERRORS_KEY] = serializer_error.pop("__all__")
            raise serializers.ValidationError(serializer_error)

        return attrs
