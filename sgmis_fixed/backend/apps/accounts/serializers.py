from rest_framework import serializers
from django.contrib.auth import authenticate
from rest_framework_simplejwt.tokens import RefreshToken
from .models import User, UserRole

class UserSerializer(serializers.ModelSerializer):
    station_name = serializers.CharField(source="station.name", read_only=True)
    full_name = serializers.SerializerMethodField()

    class Meta:
        model = User
        fields = [
            "id",
            "username",
            "email",
            "employee_number",
            "first_name",
            "last_name",
            "full_name",
            "role",
            "rank",
            "phone_number",
            "station",
            "station_name",
            "profile_photo",
            "is_active",
            "created_at",
        ]
        read_only_fields = ["id", "created_at"]

    def get_full_name(self, obj):
        name = obj.get_full_name().strip()
        return name if name else obj.username

class UserCreateSerializer(serializers.ModelSerializer):
    password = serializers.CharField(write_only=True, required=True, min_length=8)

    class Meta:
        model = User
        fields = [
            "id",
            "username",
            "email",
            "employee_number",
            "first_name",
            "last_name",
            "role",
            "rank",
            "phone_number",
            "station",
            "profile_photo",
            "password",
        ]

    def create(self, validated_data):
        password = validated_data.pop("password")
        user = User(**validated_data)
        user.set_password(password)
        if user.role == UserRole.ADMINISTRATOR:
            user.is_staff = True
        user.save()
        return user

class UserProfileUpdateSerializer(serializers.ModelSerializer):
    class Meta:
        model = User
        fields = ["first_name", "last_name", "phone_number", "profile_photo"]

class LoginSerializer(serializers.Serializer):
    identifier = serializers.CharField(required=True, help_text="Username or Employee Number")
    password = serializers.CharField(required=True, write_only=True)

    def validate(self, attrs):
        identifier = attrs.get("identifier")
        password = attrs.get("password")
        user = authenticate(username=identifier, password=password)
        if not user:
            raise serializers.ValidationError("Invalid credentials. Please verify your username/employee number and password.")
        if not user.is_active:
            raise serializers.ValidationError("Account is inactive. Please contact an administrator.")
        attrs["user"] = user
        return attrs


class PasswordResetRequestSerializer(serializers.Serializer):
    identifier = serializers.CharField(required=True, help_text="Username, employee number, or email")


class PasswordResetConfirmSerializer(serializers.Serializer):
    identifier = serializers.CharField(required=True)
    otp_code = serializers.CharField(required=True, min_length=6, max_length=6)
    new_password = serializers.CharField(required=True, min_length=8, write_only=True)


class UserDeactivateSerializer(serializers.Serializer):
    reason = serializers.CharField(required=True, min_length=5, help_text="Mandatory audit justification for account deactivation")
