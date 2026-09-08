from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import EscortDutyViewSet

router = DefaultRouter()
router.register(r"duties", EscortDutyViewSet, basename="escort_duty")

urlpatterns = [
    path("", include(router.urls)),
]
