from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    ShiftViewSet,
    AttendanceViewSet,
    ShiftHandoverViewSet,
    ExaminationPeriodViewSet,
    TemporaryAssignmentAuditViewSet,
    DutyRosterViewSet,
    PublicHolidayViewSet,
    PublicHolidayDutyRecordViewSet,
    DutyOverrideViewSet,
)

router = DefaultRouter()
router.register(r"shifts", ShiftViewSet, basename="shift")
router.register(r"duty-rosters", DutyRosterViewSet, basename="duty-roster")
router.register(r"duty-overrides", DutyOverrideViewSet, basename="duty-override")
router.register(r"public-holidays", PublicHolidayViewSet, basename="public-holiday")
router.register(r"holiday-duties", PublicHolidayDutyRecordViewSet, basename="holiday-duty")
router.register(r"attendance", AttendanceViewSet, basename="attendance")
router.register(r"handovers", ShiftHandoverViewSet, basename="handover")
router.register(r"examination-periods", ExaminationPeriodViewSet, basename="examination-period")
router.register(r"temporary-assignments", TemporaryAssignmentAuditViewSet, basename="temporary-assignment")

urlpatterns = [
    path("", include(router.urls)),
]

