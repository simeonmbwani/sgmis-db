from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    ShiftViewSet,
    AttendanceViewSet,
    ShiftHandoverViewSet,
    ExaminationPeriodViewSet,
    TemporaryAssignmentAuditViewSet,
)

router = DefaultRouter()
router.register(r"shifts", ShiftViewSet, basename="shift")
router.register(r"attendance", AttendanceViewSet, basename="attendance")
router.register(r"handovers", ShiftHandoverViewSet, basename="handover")
router.register(r"examination-periods", ExaminationPeriodViewSet, basename="examination-period")
router.register(r"temporary-assignments", TemporaryAssignmentAuditViewSet, basename="temporary-assignment")

urlpatterns = [
    path("", include(router.urls)),
]
