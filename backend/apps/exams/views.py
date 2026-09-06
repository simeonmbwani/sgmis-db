from rest_framework import viewsets
from rest_framework.permissions import IsAuthenticated
from .models import ExamDuty
from .serializers import ExamDutySerializer
from apps.accounts.models import UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin

class ExamDutyViewSet(viewsets.ModelViewSet):
    queryset = ExamDuty.objects.all().select_related("guard")
    serializer_class = ExamDutySerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            return qs.filter(guard=user)
        return qs

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "destroy"]:
            return [IsSupervisorOrAdmin()]
        return [IsAuthenticated()]
