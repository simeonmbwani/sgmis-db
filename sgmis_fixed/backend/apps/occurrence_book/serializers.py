from rest_framework import serializers
from .models import OccurrenceBookEntry

class OccurrenceBookEntrySerializer(serializers.ModelSerializer):
    guard_name = serializers.SerializerMethodField()
    guard_employee_number = serializers.CharField(source="guard.employee_number", read_only=True)
    station_name = serializers.CharField(source="station.name", read_only=True)
    category_display = serializers.CharField(source="get_category_display", read_only=True)

    class Meta:
        extra_kwargs = {"station": {"required": False}}
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
            "created_at",
        ]
        read_only_fields = ["id", "entry_number", "guard", "created_at"]

    def get_guard_name(self, obj):
        name = obj.guard.get_full_name().strip()
        return name if name else obj.guard.username
