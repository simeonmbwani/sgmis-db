from rest_framework import serializers
from .models import ExamDuty

class ExamDutySerializer(serializers.ModelSerializer):
    guard_name = serializers.CharField(source="guard.get_full_name", read_only=True)

    class Meta:
        model = ExamDuty
        fields = [
            "id",
            "guard",
            "guard_name",
            "institution",
            "exam_title",
            "date",
            "start_time",
            "end_time",
            "status",
            "notes",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]
