from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import ShiftViewSet, AttendanceViewSet, ShiftHandoverViewSet

router = DefaultRouter()
router.register(r"shifts", ShiftViewSet, basename="shift")
router.register(r"attendance", AttendanceViewSet, basename="attendance")
router.register(r"handovers", ShiftHandoverViewSet, basename="handover")

urlpatterns = [
    path("", include(router.urls)),
]
