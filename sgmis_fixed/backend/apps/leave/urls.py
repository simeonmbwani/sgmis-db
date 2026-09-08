from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import LeaveBalanceViewSet, LeaveApplicationViewSet

router = DefaultRouter()
router.register(r"balances", LeaveBalanceViewSet, basename="leave_balance")
router.register(r"applications", LeaveApplicationViewSet, basename="leave_application")

urlpatterns = [
    path("", include(router.urls)),
]
