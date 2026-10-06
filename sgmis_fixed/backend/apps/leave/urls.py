from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    LeaveBalanceViewSet,
    LeaveApplicationViewSet,
    LeaveAccrualRecordViewSet,
    PublicHolidayCompensationLedgerViewSet,
    LeaveAdjustmentRecordViewSet,
    GuardLeaveSummaryView,
)

router = DefaultRouter()
router.register(r"balances", LeaveBalanceViewSet, basename="leave_balance")
router.register(r"applications", LeaveApplicationViewSet, basename="leave_application")
router.register(r"accruals", LeaveAccrualRecordViewSet, basename="leave_accrual")
router.register(r"accrual-records", LeaveAccrualRecordViewSet, basename="leave_accrual_record")
router.register(r"compensation-ledger", PublicHolidayCompensationLedgerViewSet, basename="compensation_ledger")
router.register(r"adjustments", LeaveAdjustmentRecordViewSet, basename="leave_adjustment")

urlpatterns = [
    path("my-summary/", GuardLeaveSummaryView.as_view(), name="my_leave_summary"),
    path("my_summary/", GuardLeaveSummaryView.as_view(), name="my_leave_summary_underscore"),
    path("", include(router.urls)),
]
