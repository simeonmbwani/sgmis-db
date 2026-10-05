import secrets
from datetime import timedelta
from django.utils import timezone
from django.db.models import Q
from rest_framework import status, viewsets
from rest_framework.views import APIView
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.exceptions import ValidationError, PermissionDenied
from rest_framework.parsers import MultiPartParser, FormParser, JSONParser
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework_simplejwt.views import TokenRefreshView
from django.conf import settings
from apps.core.models import SecurityAuditEvent
from apps.core.audit import log_security_event
from .models import User, UserRole, LoginAttempt, PasswordResetOTP, UserDeactivationAudit
from .serializers import (
    UserSerializer,
    UserCreateSerializer,
    UserProfileUpdateSerializer,
    LoginSerializer,
    PasswordResetRequestSerializer,
    PasswordResetConfirmSerializer,
    UserDeactivateSerializer,
)
from .permissions import IsAdministrator, IsSupervisorOrAdmin
from apps.core.sms import send_sms, mask_phone_number

def get_client_ip(request):
    x_forwarded = request.META.get("HTTP_X_FORWARDED_FOR")
    if x_forwarded:
        return x_forwarded.split(",")[0].strip()
    return request.META.get("REMOTE_ADDR", "")

class LoginView(APIView):
    """
    POST /auth/login/
    Accepts username OR employee_number + password.
    Enforces strict rate limiting: max 5 failed attempts followed by a 15-minute lockout.
    """
    permission_classes = [AllowAny]

    def post(self, request):
        identifier = str(request.data.get("identifier", "")).strip()
        client_ip = get_client_ip(request)
        now = timezone.now()

        # Check existing lockout record
        attempt_record = None
        if identifier:
            attempt_record = LoginAttempt.objects.filter(identifier=identifier).first()
        if not attempt_record and client_ip:
            attempt_record = LoginAttempt.objects.filter(ip_address=client_ip).first()

        if attempt_record and attempt_record.locked_until and attempt_record.locked_until > now:
            remaining_seconds = int((attempt_record.locked_until - now).total_seconds())
            remaining_minutes = max(1, (remaining_seconds + 59) // 60)
            log_security_event(
                event_type=SecurityAuditEvent.EventType.LOGIN_FAILURE,
                actor_username=identifier,
                ip_address=client_ip,
                details={"reason": "Attempt on locked account", "remaining_minutes": remaining_minutes}
            )
            return Response({
                "detail": f"Account locked due to 3 failed login attempts. Please retry after {remaining_minutes} minute(s).",
                "is_locked": True,
                "lockout_remaining_minutes": remaining_minutes,
                "locked_until": attempt_record.locked_until.isoformat(),
            }, status=status.HTTP_429_TOO_MANY_REQUESTS)

        serializer = LoginSerializer(data=request.data)
        if not serializer.is_valid():
            # Record failed login attempt
            if not attempt_record and identifier:
                attempt_record = LoginAttempt.objects.create(
                    identifier=identifier,
                    ip_address=client_ip,
                    failed_attempts=0
                )
            elif not attempt_record and client_ip:
                attempt_record = LoginAttempt.objects.create(
                    identifier=identifier or "anonymous",
                    ip_address=client_ip,
                    failed_attempts=0
                )

            if attempt_record:
                attempt_record.failed_attempts += 1
                if attempt_record.failed_attempts >= 3:
                    attempt_record.locked_until = now + timedelta(minutes=15)
                    attempt_record.save()
                    log_security_event(
                        event_type=SecurityAuditEvent.EventType.LOGIN_FAILURE,
                        actor_username=identifier,
                        ip_address=client_ip,
                        details={"reason": "Account locked after 3 failed attempts"}
                    )
                    return Response({
                        "detail": "Maximum 3 failed login attempts exceeded. Account is locked for 15 minutes.",
                        "is_locked": True,
                        "lockout_remaining_minutes": 15,
                        "locked_until": attempt_record.locked_until.isoformat(),
                    }, status=status.HTTP_429_TOO_MANY_REQUESTS)
                else:
                    attempt_record.save()
                    remaining_attempts = 3 - attempt_record.failed_attempts
                    log_security_event(
                        event_type=SecurityAuditEvent.EventType.LOGIN_FAILURE,
                        actor_username=identifier,
                        ip_address=client_ip,
                        details={"failed_attempts": attempt_record.failed_attempts, "remaining": remaining_attempts}
                    )
                    return Response({
                        "detail": f"Invalid credentials. {remaining_attempts} attempt(s) remaining before a 15-minute account lockout.",
                        "is_locked": False,
                        "remaining_attempts": remaining_attempts,
                    }, status=status.HTTP_400_BAD_REQUEST)

            log_security_event(
                event_type=SecurityAuditEvent.EventType.LOGIN_FAILURE,
                actor_username=identifier,
                ip_address=client_ip,
                details={"errors": serializer.errors}
            )
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        # Login succeeded: reset failed attempts
        user = serializer.validated_data["user"]
        if attempt_record:
            attempt_record.failed_attempts = 0
            attempt_record.locked_until = None
            attempt_record.save()

        if client_ip:
            LoginAttempt.objects.filter(ip_address=client_ip).update(failed_attempts=0, locked_until=None)

        log_security_event(
            event_type=SecurityAuditEvent.EventType.LOGIN_SUCCESS,
            actor=user,
            actor_username=user.username,
            ip_address=client_ip,
            details={"role": user.role, "station": user.station.name if user.station else None}
        )

        refresh = RefreshToken.for_user(user)
        return Response({
            "access": str(refresh.access_token),
            "refresh": str(refresh),
            "user": UserSerializer(user).data,
        }, status=status.HTTP_200_OK)

class PasswordResetRequestView(APIView):
    """
    POST /auth/password_reset/request/
    Initiates time-sensitive OTP recovery flow.
    OTP is hashed in storage (SHA-256). Responses are uniform to prevent enumeration.
    """
    permission_classes = [AllowAny]

    def post(self, request):
        serializer = PasswordResetRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        ident = serializer.validated_data["identifier"].strip()
        client_ip = get_client_ip(request)

        user = User.objects.filter(
            Q(username__iexact=ident) |
            Q(employee_number__iexact=ident) |
            Q(email__iexact=ident)
        ).first()

        if not user:
            # Prevent user enumeration by returning standard response
            log_security_event(
                event_type=SecurityAuditEvent.EventType.PASSWORD_RESET_REQUEST,
                actor_username=ident,
                ip_address=client_ip,
                details={"status": "user_not_found"}
            )
            return Response({
                "message": "If an active account matches the details provided, a 6-digit recovery OTP has been generated.",
                "expires_in_minutes": 10,
            }, status=status.HTTP_200_OK)

        # Guard recovery MUST validate that the user has a valid registered mobile number
        if not user.phone_number or not user.phone_number.strip():
            log_security_event(
                event_type=SecurityAuditEvent.EventType.PASSWORD_RESET_FAILED,
                actor=user,
                actor_username=user.username,
                ip_address=client_ip,
                details={"reason": "missing_registered_phone_number"}
            )
            return Response({
                "detail": "No registered mobile phone number found for this account. Please contact your station supervisor or administrator.",
            }, status=status.HTTP_400_BAD_REQUEST)

        # Resend cooldown: 60 seconds
        recent_otp = PasswordResetOTP.objects.filter(
            user=user,
            created_at__gte=timezone.now() - timedelta(seconds=60),
            is_used=False
        ).first()
        if recent_otp:
            return Response({
                "detail": "A recovery code was recently requested. Please wait 60 seconds before requesting a new code.",
            }, status=status.HTTP_429_TOO_MANY_REQUESTS)

        otp_val = f"{secrets.randbelow(900000) + 100000}"

        # Deliver the 6-digit reset code via real SMS provider abstraction
        sms_result = send_sms(
            user.phone_number,
            f"Your SGMIS security verification code is: {otp_val}. Valid for 10 minutes. Do not share this code."
        )

        if not sms_result.success:
            log_security_event(
                event_type=SecurityAuditEvent.EventType.PASSWORD_RESET_FAILED,
                actor=user,
                actor_username=user.username,
                ip_address=client_ip,
                details={"reason": "sms_dispatch_failed", "provider": sms_result.provider, "error": sms_result.error}
            )
            err_status = status.HTTP_400_BAD_REQUEST if "format" in sms_result.error.lower() else status.HTTP_502_BAD_GATEWAY
            return Response({
                "detail": f"Failed to dispatch recovery SMS to registered mobile number: {sms_result.error}. Please try again later or contact your supervisor.",
            }, status=err_status)

        # Invalidate old unused OTPs
        PasswordResetOTP.objects.filter(user=user, is_used=False).update(is_used=True)

        expires = timezone.now() + timedelta(minutes=10)
        otp = PasswordResetOTP(
            user=user,
            expires_at=expires,
        )
        otp.set_otp(otp_val)
        otp.save()

        from apps.notifications.models import Notification
        Notification.objects.create(
            user=user,
            title="Password Reset OTP Generated",
            message="A security recovery code has been dispatched to your registered mobile number via SMS.",
            notification_type="SECURITY_ALERT",
        )

        log_security_event(
            event_type=SecurityAuditEvent.EventType.PASSWORD_RESET_REQUEST,
            actor=user,
            actor_username=user.username,
            ip_address=client_ip,
            details={"status": "otp_generated_and_sms_dispatched", "destination": mask_phone_number(user.phone_number)}
        )

        resp_data = {
            "message": f"A 6-digit recovery OTP has been dispatched via SMS to your registered mobile number ending in {user.phone_number[-4:]}.",
            "destination": mask_phone_number(user.phone_number),
            "expires_in_minutes": 10,
        }

        return Response(resp_data, status=status.HTTP_200_OK)

class PasswordResetConfirmView(APIView):
    """
    POST /auth/password_reset/confirm/
    Verifies time-sensitive hashed OTP and resets password.
    Enforces maximum 5 attempts per OTP.
    """
    permission_classes = [AllowAny]

    def post(self, request):
        serializer = PasswordResetConfirmSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        ident = serializer.validated_data["identifier"].strip()
        otp_code = serializer.validated_data["otp_code"].strip()
        new_password = serializer.validated_data["new_password"]
        client_ip = get_client_ip(request)

        user = User.objects.filter(
            Q(username__iexact=ident) |
            Q(employee_number__iexact=ident) |
            Q(email__iexact=ident)
        ).first()

        if not user:
            log_security_event(
                event_type=SecurityAuditEvent.EventType.PASSWORD_RESET_FAILED,
                actor_username=ident,
                ip_address=client_ip,
                details={"reason": "User not found"}
            )
            return Response({"detail": "Invalid identifier or OTP code."}, status=status.HTTP_400_BAD_REQUEST)

        otp = PasswordResetOTP.objects.filter(
            user=user,
            is_used=False,
            expires_at__gt=timezone.now()
        ).order_by("-created_at").first()

        if not otp:
            log_security_event(
                event_type=SecurityAuditEvent.EventType.PASSWORD_RESET_FAILED,
                actor=user,
                actor_username=user.username,
                ip_address=client_ip,
                details={"reason": "No active unexpired OTP"}
            )
            return Response({"detail": "Invalid or expired OTP code. Please request a new code."}, status=status.HTTP_400_BAD_REQUEST)

        if not otp.verify_otp(otp_code):
            log_security_event(
                event_type=SecurityAuditEvent.EventType.PASSWORD_RESET_FAILED,
                actor=user,
                actor_username=user.username,
                ip_address=client_ip,
                details={"reason": "Failed OTP verification", "attempts": otp.attempts_count}
            )
            if otp.is_used:
                return Response({
                    "detail": "Maximum 5 attempts exceeded for this recovery code. Please request a new code.",
                }, status=status.HTTP_400_BAD_REQUEST)
            remaining = otp.max_attempts - otp.attempts_count
            return Response({
                "detail": f"Invalid OTP code. {remaining} attempt(s) remaining.",
            }, status=status.HTTP_400_BAD_REQUEST)

        user.set_password(new_password)
        user.save()

        otp.is_used = True
        otp.save(update_fields=["is_used"])

        # Invalidate all prior unused reset codes for this user
        PasswordResetOTP.objects.filter(user=user, is_used=False).update(is_used=True)

        # Clear login lockout record on password recovery
        LoginAttempt.objects.filter(identifier=user.username).update(failed_attempts=0, locked_until=None)
        if user.employee_number:
            LoginAttempt.objects.filter(identifier=user.employee_number).update(failed_attempts=0, locked_until=None)
        if user.email:
            LoginAttempt.objects.filter(identifier=user.email).update(failed_attempts=0, locked_until=None)
        if ident:
            LoginAttempt.objects.filter(identifier=ident).update(failed_attempts=0, locked_until=None)
        if client_ip:
            LoginAttempt.objects.filter(ip_address=client_ip).update(failed_attempts=0, locked_until=None)

        log_security_event(
            event_type=SecurityAuditEvent.EventType.PASSWORD_RESET_SUCCESS,
            actor=user,
            actor_username=user.username,
            ip_address=client_ip,
            details={"status": "password_reset_success"}
        )

        return Response({
            "message": "Password successfully reset. You may now log in with your new password.",
            "status": "success",
        }, status=status.HTTP_200_OK)

class CustomTokenRefreshView(TokenRefreshView):
    permission_classes = [AllowAny]

def save_user_profile_photo(user, photo_input):
    """
    Saves an uploaded photo file or base64 data to MEDIA_ROOT/profile_photos/
    and updates user.profile_photo.
    """
    import os, base64
    from django.core.files.uploadedfile import UploadedFile

    media_dir = os.path.join(settings.MEDIA_ROOT, "profile_photos")
    os.makedirs(media_dir, exist_ok=True)

    allowed_exts = {".jpg", ".jpeg", ".png", ".webp"}
    max_size = 5 * 1024 * 1024  # 5MB

    if isinstance(photo_input, UploadedFile):
        if photo_input.size > max_size:
            raise ValidationError({"profile_photo": "Profile photo must be 5MB or smaller."})
        _, ext = os.path.splitext(photo_input.name.lower())
        if ext not in allowed_exts:
            ext = ".jpg"
        filename = f"user_{user.id}_{int(timezone.now().timestamp())}{ext}"
        filepath = os.path.join(media_dir, filename)
        with open(filepath, "wb+") as destination:
            for chunk in photo_input.chunks():
                destination.write(chunk)
        user.profile_photo = f"{settings.MEDIA_URL}profile_photos/{filename}"
        user.save(update_fields=["profile_photo", "updated_at"])
        return user.profile_photo

    elif isinstance(photo_input, str) and photo_input.startswith("data:image/"):
        try:
            header, base64_data = photo_input.split(";base64,", 1)
            mime = header.split("data:image/")[1].lower()
            ext = f".{mime}" if f".{mime}" in allowed_exts else ".jpg"
            data_bytes = base64.b64decode(base64_data)
            if len(data_bytes) > max_size:
                raise ValidationError({"profile_photo": "Profile photo must be 5MB or smaller."})
            filename = f"user_{user.id}_{int(timezone.now().timestamp())}{ext}"
            filepath = os.path.join(media_dir, filename)
            with open(filepath, "wb") as destination:
                destination.write(data_bytes)
            user.profile_photo = f"{settings.MEDIA_URL}profile_photos/{filename}"
            user.save(update_fields=["profile_photo", "updated_at"])
            return user.profile_photo
        except Exception as e:
            if isinstance(e, ValidationError):
                raise
            raise ValidationError({"profile_photo": f"Failed to process image: {str(e)}"})
    elif isinstance(photo_input, str) and (photo_input.startswith("http://") or photo_input.startswith("https://") or photo_input.startswith("/media/")):
        user.profile_photo = photo_input
        user.save(update_fields=["profile_photo", "updated_at"])
        return user.profile_photo
    return None


class CurrentUserView(APIView):
    permission_classes = [IsAuthenticated]
    parser_classes = [MultiPartParser, FormParser, JSONParser]

    def get(self, request):
        serializer = UserSerializer(request.user)
        return Response(serializer.data)

    def patch(self, request):
        photo_file = request.FILES.get("photo") or request.FILES.get("profile_photo")
        if photo_file:
            save_user_profile_photo(request.user, photo_file)

        data = request.data.copy() if hasattr(request.data, "copy") else dict(request.data)
        photo_data = data.get("profile_photo")
        if photo_data and isinstance(photo_data, str) and photo_data.startswith("data:image/"):
            save_user_profile_photo(request.user, photo_data)
            data.pop("profile_photo", None)

        serializer = UserProfileUpdateSerializer(request.user, data=data, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return Response(UserSerializer(request.user).data)

    def post(self, request):
        return self.patch(request)


class CurrentUserPhotoUploadView(APIView):
    permission_classes = [IsAuthenticated]
    parser_classes = [MultiPartParser, FormParser, JSONParser]

    def post(self, request):
        photo_file = request.FILES.get("photo") or request.FILES.get("profile_photo")
        photo_str = request.data.get("photo") or request.data.get("profile_photo")
        photo_input = photo_file or photo_str
        if not photo_input:
            return Response({"detail": "No photo file or image data provided."}, status=status.HTTP_400_BAD_REQUEST)

        url = save_user_profile_photo(request.user, photo_input)
        user_data = UserSerializer(request.user).data
        user_data["message"] = "Profile photo updated successfully."
        user_data["profile_photo"] = url
        user_data["user"] = dict(user_data)
        return Response(user_data, status=status.HTTP_200_OK)


class UserViewSet(viewsets.ModelViewSet):
    """
    CRUD for User accounts.
    Administrators have full management.
    Supervisors can view guards.
    Deactivating a guard account requires explicit Admin approval plus a mandatory audit reason log.
    """
    queryset = User.objects.all().select_related("station")
    permission_classes = [IsSupervisorOrAdmin]

    def get_serializer_class(self):
        if self.action == "create":
            return UserCreateSerializer
        return UserSerializer

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        role = self.request.query_params.get("role")
        station = self.request.query_params.get("station")
        active = self.request.query_params.get("is_active")

        if role:
            qs = qs.filter(role=role)
        if station:
            qs = qs.filter(station_id=station)
        if active is not None:
            qs = qs.filter(is_active=active.lower() in ("true", "1"))

        if user.role == UserRole.SUPERVISOR:
            if user.station:
                qs = qs.filter(Q(station=user.station) | Q(role=UserRole.GUARD, station__isnull=True))
            else:
                qs = qs.filter(role=UserRole.GUARD)
        return qs

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "destroy", "deactivate"]:
            return [IsAdministrator()]
        return [IsSupervisorOrAdmin()]

    @action(detail=True, methods=["post"], url_path="deactivate", permission_classes=[IsAdministrator])
    def deactivate(self, request, pk=None):
        target_user = self.get_object()
        serializer = UserDeactivateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        reason = serializer.validated_data["reason"].strip()

        if not target_user.is_active:
            return Response({"detail": "User account is already deactivated."}, status=status.HTTP_400_BAD_REQUEST)

        target_user.is_active = False
        target_user.save()

        UserDeactivationAudit.objects.create(
            target_user=target_user,
            performed_by=request.user,
            reason=reason,
        )

        return Response({
            "message": f"Account for {target_user.username} successfully deactivated.",
            "user": UserSerializer(target_user).data,
        }, status=status.HTTP_200_OK)

    def perform_destroy(self, instance):
        # Prevent irreversible deletion of accounts; route to audit deactivation
        raise ValidationError({"detail": "Hard deletion of accounts is prohibited. Use the admin deactivation endpoint with audit justification."})
