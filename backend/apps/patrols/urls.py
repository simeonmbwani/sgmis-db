from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import CheckpointViewSet, PatrolLogViewSet

router = DefaultRouter()
router.register(r"checkpoints", CheckpointViewSet, basename="checkpoint")
router.register(r"logs", PatrolLogViewSet, basename="patrol_log")

urlpatterns = [
    path("", include(router.urls)),
]
