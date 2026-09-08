from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import StationViewSet, GuardPairViewSet

router = DefaultRouter()
router.register(r"stations", StationViewSet, basename="station")
router.register(r"pairs", GuardPairViewSet, basename="pair")

urlpatterns = [
    path("", include(router.urls)),
]
