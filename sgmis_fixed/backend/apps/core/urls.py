from django.urls import path
from apps.core.views import health_check, telemetry_overview

urlpatterns = [
    path("health/", health_check, name="health_check"),
    path("telemetry/", telemetry_overview, name="telemetry_overview"),
    path("telemetry/overview/", telemetry_overview, name="telemetry_overview_alias"),
]
