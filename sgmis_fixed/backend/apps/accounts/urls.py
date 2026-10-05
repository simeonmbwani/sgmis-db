from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import CurrentUserView, CurrentUserPhotoUploadView, UserViewSet

router = DefaultRouter()
router.register(r"users", UserViewSet, basename="user")

urlpatterns = [
    path("auth/", include("apps.accounts.auth_urls")),
    path("users/me/photo/", CurrentUserPhotoUploadView.as_view(), name="user_me_photo"),
    path("users/me/", CurrentUserView.as_view(), name="user_me"),
    path("", include(router.urls)),
]
