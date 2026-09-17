from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import NotificationViewSet

router = DefaultRouter()
router.register(r"alerts", NotificationViewSet, basename="notification")

broadcast_view = NotificationViewSet.as_view({"get": "broadcast", "post": "broadcast"})

urlpatterns = [
    path("alerts/broadcast/", broadcast_view, name="notification-alert-broadcast"),
    path("alerts/broadcast", broadcast_view),
    path("broadcast/", broadcast_view, name="notification-broadcast"),
    path("broadcast", broadcast_view),
    path("", include(router.urls)),
]
