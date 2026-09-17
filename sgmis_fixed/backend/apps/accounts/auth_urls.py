from django.urls import path
from .views import LoginView, CustomTokenRefreshView, PasswordResetRequestView, PasswordResetConfirmView

urlpatterns = [
    path("login/", LoginView.as_view(), name="auth_login"),
    path("login", LoginView.as_view(), name="auth_login_noslash"),
    path("refresh/", CustomTokenRefreshView.as_view(), name="auth_refresh"),
    path("refresh", CustomTokenRefreshView.as_view(), name="auth_refresh_noslash"),
    path("password_reset/request/", PasswordResetRequestView.as_view(), name="auth_password_reset_request"),
    path("password_reset/request", PasswordResetRequestView.as_view(), name="auth_password_reset_request_noslash"),
    path("password_reset/confirm/", PasswordResetConfirmView.as_view(), name="auth_password_reset_confirm"),
    path("password_reset/confirm", PasswordResetConfirmView.as_view(), name="auth_password_reset_confirm_noslash"),
]

