from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import IncidentReportViewSet

router = DefaultRouter()
router.register(r"reports", IncidentReportViewSet, basename="incident")
router.register(r"incidents", IncidentReportViewSet, basename="incident-incidents")
router.register(r"", IncidentReportViewSet, basename="incident-root")

sos_view = IncidentReportViewSet.as_view({"post": "trigger_sos"})

urlpatterns = [
    path("sos/", sos_view, name="incident-sos"),
    path("reports/sos/", sos_view, name="incident-reports-sos"),
    path("incidents/sos/", sos_view, name="incident-incidents-sos"),
    path("", include(router.urls)),
]
