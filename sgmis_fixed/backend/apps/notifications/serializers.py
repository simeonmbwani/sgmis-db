from rest_framework import serializers
from .models import Notification, DirectMessage

class NotificationSerializer(serializers.ModelSerializer):
    class Meta:
        model = Notification
        fields = ["id", "user", "title", "message", "notification_type", "read", "created_at", "dedup_key"]
        read_only_fields = ["id", "user", "created_at", "dedup_key"]

class DirectMessageSerializer(serializers.ModelSerializer):
    sender_name = serializers.SerializerMethodField()
    recipient_name = serializers.SerializerMethodField()
    recipient_id = serializers.UUIDField(write_only=True, required=False)

    class Meta:
        model = DirectMessage
        fields = ["id", "sender", "sender_name", "recipient", "recipient_id", "recipient_name", "content", "read", "created_at"]
        read_only_fields = ["id", "sender", "created_at"]
        extra_kwargs = {
            "recipient": {"required": False},
        }

    def validate(self, attrs):
        if "recipient" not in attrs and "recipient_id" in attrs:
            from apps.accounts.models import User
            try:
                attrs["recipient"] = User.objects.get(id=attrs["recipient_id"])
            except User.DoesNotExist:
                raise serializers.ValidationError({"recipient": "Recipient not found."})
        elif "recipient" not in attrs and not self.instance:
            raise serializers.ValidationError({"recipient": "Recipient is required."})
        return attrs

    def get_sender_name(self, obj):
        name = obj.sender.get_full_name().strip()
        return name if name else obj.sender.username

    def get_recipient_name(self, obj):
        name = obj.recipient.get_full_name().strip()
        return name if name else obj.recipient.username
