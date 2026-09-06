from django.urls import path
from .views import LoginView, CustomTokenRefreshView

urlpatterns = [
    path("login/", LoginView.as_view(), name="auth_login"),
    path("refresh/", CustomTokenRefreshView.as_view(), name="auth_refresh"),
]
