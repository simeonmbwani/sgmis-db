from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import ExamDutyViewSet

router = DefaultRouter()
router.register(r"duties", ExamDutyViewSet, basename="exam_duty")

urlpatterns = [
    path("", include(router.urls)),
]
