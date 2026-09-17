from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import Notification
from .serializers import NotificationSerializer
from apps.accounts.models import User, UserRole

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

    @action(detail=False, methods=["get", "post"], url_path="broadcast")
    def broadcast(self, request):
        """
        Broadcast a notification notice to active users.
        Supervisors and administrators can broadcast notices.
        """
        if request.method == "GET":
            return Response({"status": "ready", "message": "Broadcast service ready."}, status=status.HTTP_200_OK)

        if request.user.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
            return Response(
                {"detail": "Only supervisors and administrators can broadcast notices."},
                status=status.HTTP_403_FORBIDDEN
            )
        title = request.data.get("title", "").strip()
        message = request.data.get("message", "").strip()
        target_role = request.data.get("target_role")
        station_id = request.data.get("station_id")

        if not title or not message:
            return Response(
                {"detail": "Title and message are required for broadcast."},
                status=status.HTTP_400_BAD_REQUEST
            )

        users_qs = User.objects.filter(is_active=True)
        if target_role:
            users_qs = users_qs.filter(role=target_role)
        if station_id:
            users_qs = users_qs.filter(station_id=station_id)

        notifications = [
            Notification(
                user=u,
                title=title,
                message=message,
                notification_type="BROADCAST",
            )
            for u in users_qs
        ]
        Notification.objects.bulk_create(notifications)
        return Response({
            "message": f"Broadcast delivered to {len(notifications)} personnel.",
            "recipients_count": len(notifications)
        }, status=status.HTTP_201_CREATED)

