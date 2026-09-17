from rest_framework import serializers
from .models import OccurrenceBookEntry, OBAmendment

class OBAmendmentSerializer(serializers.ModelSerializer):
    amended_by_name = serializers.SerializerMethodField()

    class Meta:
        model = OBAmendment
        fields = [
            "id",
            "entry",
            "amended_by",
            "amended_by_name",
            "reason",
            "original_text_snapshot",
            "amended_text",
            "created_at",
        ]
        read_only_fields = ["id", "amended_by", "original_text_snapshot", "created_at"]

    def get_amended_by_name(self, obj):
        name = obj.amended_by.get_full_name().strip()
        return name if name else obj.amended_by.username

class OccurrenceBookEntrySerializer(serializers.ModelSerializer):
    guard_name = serializers.SerializerMethodField()
    guard_employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    station_name = serializers.CharField(source="station.name", read_only=True)
    category_display = serializers.CharField(source="get_category_display", read_only=True)
    cross_reference = serializers.CharField(source="check_record", required=False, allow_blank=True)
    amendments = OBAmendmentSerializer(many=True, read_only=True)

    class Meta:
        model = OccurrenceBookEntry
        fields = [
            "id",
            "entry_number",
            "station",
            "station_name",
            "guard",
            "guard_name",
            "guard_employee_number",
            "category",
            "category_display",
            "occurrence_text",
            "check_record",
            "cross_reference",
            "amendments",
            "created_at",
        ]
        read_only_fields = ["id", "entry_number", "guard", "created_at"]
        extra_kwargs = {
            "station": {"required": False, "allow_null": True},
        }

    def validate(self, attrs):
        request = self.context.get("request")
        user = getattr(request, "user", None)

        station = attrs.get("station")
        if user and getattr(user, "role", None) == "GUARD":
            if not user.station:
                raise serializers.ValidationError({
                    "station": "Your account has no station assigned. Contact your supervisor or administrator."
                })
            attrs["station"] = user.station
        elif not station:
            if user and getattr(user, "station", None):
                attrs["station"] = user.station
            else:
                raise serializers.ValidationError({
                    "station": "A valid station is required. Your account has no station assigned."
                })
        elif station and not station.is_active:
            raise serializers.ValidationError({
                "station": f"Station '{station.name}' is inactive."
            })
        return attrs

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username
