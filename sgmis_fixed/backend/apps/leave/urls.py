from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    LeaveBalanceViewSet,
    LeaveApplicationViewSet,
    PublicHolidayCompensationLedgerViewSet,
    GuardLeaveSummaryView,
)

router = DefaultRouter()
router.register(r"balances", LeaveBalanceViewSet, basename="leave_balance")
router.register(r"applications", LeaveApplicationViewSet, basename="leave_application")
router.register(r"compensation-ledger", PublicHolidayCompensationLedgerViewSet, basename="compensation_ledger")

urlpatterns = [
    path("my-summary/", GuardLeaveSummaryView.as_view(), name="my_leave_summary"),
    path("", include(router.urls)),
]
