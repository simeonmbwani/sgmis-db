from django.urls import path, include
from rest_framework.routers import DefaultRouter
from apps.core.views import (
    health_check,
    telemetry_overview,
    RecordAdjustmentRequestViewSet,
    administrative_history,
    OrganizationPolicyViewSet,
    supervisor_dashboard,
    admin_dashboard,
)

router = DefaultRouter()
router.register(r"adjustments", RecordAdjustmentRequestViewSet, basename="record_adjustment")
router.register(r"policies", OrganizationPolicyViewSet, basename="organization_policy")

urlpatterns = [
    path("health/", health_check, name="health_check"),
    path("telemetry/", telemetry_overview, name="telemetry_overview"),
    path("telemetry/overview/", telemetry_overview, name="telemetry_overview_alias"),
    path("dashboard/supervisor/", supervisor_dashboard, name="supervisor_dashboard"),
    path("dashboard/admin/", admin_dashboard, name="admin_dashboard"),
    path("admin-history/", administrative_history, name="administrative_history"),
    path("", include(router.urls)),
]

