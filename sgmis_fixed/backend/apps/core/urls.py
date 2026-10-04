from django.urls import path, include
from rest_framework.routers import DefaultRouter
from apps.core.views import health_check, telemetry_overview, RecordAdjustmentRequestViewSet, administrative_history

router = DefaultRouter()
router.register(r"adjustments", RecordAdjustmentRequestViewSet, basename="record_adjustment")

urlpatterns = [
    path("health/", health_check, name="health_check"),
    path("telemetry/", telemetry_overview, name="telemetry_overview"),
    path("telemetry/overview/", telemetry_overview, name="telemetry_overview_alias"),
    path("admin-history/", administrative_history, name="administrative_history"),
    path("", include(router.urls)),
]
