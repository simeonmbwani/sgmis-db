from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import Notification
from .serializers import NotificationSerializer

class NotificationViewSet(viewsets.ModelViewSet):
    queryset = Notification.objects.all()
    serializer_class = NotificationSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        return self.queryset.filter(user=self.request.user)

    @action(detail=True, methods=["post"], url_path="read")
    def mark_as_read(self, request, pk=None):
        notif = self.get_object()
        notif.read = True
        notif.save()
        return Response(self.get_serializer(notif).data)

    @action(detail=False, methods=["post"], url_path="read_all")
    def mark_all_read(self, request):
        self.get_queryset().filter(read=False).update(read=True)
        return Response({"message": "All notifications marked as read."})
