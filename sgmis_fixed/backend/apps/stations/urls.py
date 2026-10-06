from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import StationViewSet, GuardPairViewSet, GuardPairReassignmentAuditViewSet

router = DefaultRouter()
router.register(r"stations", StationViewSet, basename="station")
router.register(r"pairs", GuardPairViewSet, basename="pair")
router.register(r"pair-reassignments", GuardPairReassignmentAuditViewSet, basename="pair-reassignment")

urlpatterns = [
    path("", include(router.urls)),
]

