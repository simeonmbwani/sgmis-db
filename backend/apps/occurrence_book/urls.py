from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import OccurrenceBookEntryViewSet

router = DefaultRouter()
router.register(r"entries", OccurrenceBookEntryViewSet, basename="ob_entry")

urlpatterns = [
    path("", include(router.urls)),
]
