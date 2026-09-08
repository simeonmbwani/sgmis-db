from rest_framework import status, viewsets
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework_simplejwt.views import TokenRefreshView
from .models import User, UserRole
from .serializers import UserSerializer, UserCreateSerializer, UserProfileUpdateSerializer, LoginSerializer
from .permissions import IsAdministrator, IsSupervisorOrAdmin

class LoginView(APIView):
    """
    POST /auth/login/
    Accepts username OR employee_number + password.
    Returns JWT tokens and user profile.
    """
    permission_classes = [AllowAny]

    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        user = serializer.validated_data["user"]
        refresh = RefreshToken.for_user(user)
        return Response({
            "access": str(refresh.access_token),
            "refresh": str(refresh),
            "user": UserSerializer(user).data,
        }, status=status.HTTP_200_OK)

class CustomTokenRefreshView(TokenRefreshView):
    """
    POST /auth/refresh/
    Refreshes access token using valid refresh token.
    """
    permission_classes = [AllowAny]

class CurrentUserView(APIView):
    """
    GET /accounts/users/me/
    PATCH /accounts/users/me/
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        serializer = UserSerializer(request.user)
        return Response(serializer.data)

    def patch(self, request):
        serializer = UserProfileUpdateSerializer(request.user, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return Response(UserSerializer(request.user).data)

class UserViewSet(viewsets.ModelViewSet):
    """
    CRUD for User accounts.
    Administrators have full management.
    Supervisors can view guards.
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

        # Supervisors only see guards within their scope or all guards
        if user.role == UserRole.SUPERVISOR:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.filter(role=UserRole.GUARD)
        return qs

    def get_permissions(self):
        if self.action in ["create", "destroy"]:
            return [IsAdministrator()]
        return [IsSupervisorOrAdmin()]
