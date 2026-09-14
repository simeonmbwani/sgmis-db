"""
URL configuration for SGMIS backend.
"""

from django.contrib import admin
from django.urls import path, include
from drf_spectacular.views import SpectacularAPIView, SpectacularSwaggerView, SpectacularRedocView
from apps.core.views import health_check, telemetry_overview

urlpatterns = [
    path("admin/", admin.site.urls),
    path("health/", health_check, name="health_check"),
    path("api/health/", health_check, name="api_health_check"),
    
    # Core & Telemetry canonical endpoints & aliases
    path("core/", include("apps.core.urls")),
    path("api/core/", include("apps.core.urls")),
    path("core/telemetry/", telemetry_overview, name="telemetry_overview"),
    path("api/core/telemetry/", telemetry_overview, name="api_telemetry_overview"),
    path("telemetry/", telemetry_overview, name="root_telemetry_overview"),
    path("api/telemetry/", telemetry_overview, name="root_api_telemetry_overview"),
    
    # Authentication & Accounts
    path("auth/", include("apps.accounts.auth_urls")),
    path("accounts/", include("apps.accounts.urls")),
    path("api/accounts/", include("apps.accounts.urls")),
    path("api/auth/", include("apps.accounts.auth_urls")),
    
    # Operational Modules
    path("stations/", include("apps.stations.urls")),
    path("shifts/", include("apps.shifts.urls")),
    path("leave/", include("apps.leave.urls")),
    path("patrols/", include("apps.patrols.urls")),
    path("occurrence_book/", include("apps.occurrence_book.urls")),
    path("incidents/", include("apps.incidents.urls")),
    path("escorts/", include("apps.escorts.urls")),
    path("exams/", include("apps.exams.urls")),
    path("notifications/", include("apps.notifications.urls")),
    
    # API Prefixed Operational Modules
    path("api/stations/", include("apps.stations.urls")),
    path("api/shifts/", include("apps.shifts.urls")),
    path("api/leave/", include("apps.leave.urls")),
    path("api/patrols/", include("apps.patrols.urls")),
    path("api/occurrence_book/", include("apps.occurrence_book.urls")),
    path("api/incidents/", include("apps.incidents.urls")),
    path("api/escorts/", include("apps.escorts.urls")),
    path("api/exams/", include("apps.exams.urls")),
    path("api/notifications/", include("apps.notifications.urls")),
    
    # OpenAPI Schema & Docs
    path("api/schema/", SpectacularAPIView.as_view(), name="schema"),
    path("api/docs/", SpectacularSwaggerView.as_view(url_name="schema"), name="swagger-ui"),
    path("api/redoc/", SpectacularRedocView.as_view(url_name="schema"), name="redoc"),
]
