from django.utils import timezone
from django.db.models import Q
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.exceptions import PermissionDenied, ValidationError
from .models import Notification, DirectMessage
from .serializers import NotificationSerializer, DirectMessageSerializer
from apps.accounts.models import User, UserRole
from apps.stations.models import GuardPair
from apps.shifts.models import Shift
from apps.core.audit import log_security_event

class NotificationViewSet(viewsets.ModelViewSet):
    """
    Authoritative notification alerts.
    - Guards: View their own notifications, mark read, get unread count. Cannot inject arbitrary notifications.
    - Supervisors: Send notifications strictly scoped to their assigned station.
    - Administrators: Broadcast across system or target specific roles/stations/users.
    """
    queryset = Notification.objects.all()
    serializer_class = NotificationSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        return self.queryset.filter(user=self.request.user)

    def create(self, request, *args, **kwargs):
        if request.user.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
            raise PermissionDenied("Guards are not authorized to create arbitrary notifications.")
        return super().create(request, *args, **kwargs)

    def destroy(self, request, *args, **kwargs):
        if request.user.role != UserRole.ADMINISTRATOR:
            raise PermissionDenied("Only administrators can delete notification records.")
        return super().destroy(request, *args, **kwargs)

    @action(detail=True, methods=["post"], url_path="read")
    def mark_as_read(self, request, pk=None):
        notif = self.get_object()
        notif.read = True
        notif.save()
        data = self.get_serializer(notif).data
        data["message"] = "Notification marked as read."
        data["status"] = "success"
        return Response(data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="read_all")
    def mark_all_read(self, request):
        updated_count = self.get_queryset().filter(read=False).update(read=True)
        return Response({
            "message": f"All notifications ({updated_count}) marked as read.",
            "status": "success",
            "updated_count": updated_count,
        }, status=status.HTTP_200_OK)

    @action(detail=False, methods=["get"], url_path="unread_count")
    def unread_count(self, request):
        count = self.get_queryset().filter(read=False).count()
        return Response({
            "unread_count": count,
            "status": "success",
        }, status=status.HTTP_200_OK)

    @action(detail=False, methods=["get", "post"], url_path="broadcast")
    def broadcast(self, request):
        """
        Broadcast or targeted notification dispatch.
        Supervisors: Strictly restricted to their assigned station.
        Administrators: Can broadcast system-wide or filter by station/role/users.
        Guards: Forbidden.
        """
        if request.method == "GET":
            return Response({"status": "ready", "message": "Broadcast service ready."}, status=status.HTTP_200_OK)

        if request.user.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
            return Response(
                {"detail": "Only supervisors and administrators can broadcast notices."},
                status=status.HTTP_403_FORBIDDEN,
            )

        title = request.data.get("title", "").strip()
        message = request.data.get("message", "").strip()
        target_role = request.data.get("target_role")
        client_station_id = request.data.get("station_id")
        user_ids = request.data.get("user_ids")

        if not title or not message:
            return Response(
                {"detail": "Title and message are required for broadcast."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Enforce server-side station scoping
        if request.user.role == UserRole.SUPERVISOR:
            if not request.user.station_id:
                return Response(
                    {"detail": "Supervisor is not assigned to an active station."},
                    status=status.HTTP_400_BAD_REQUEST,
                )
            effective_station_id = request.user.station_id
        else:
            effective_station_id = client_station_id

        user_ids = request.data.get("user_ids")
        if not user_ids and hasattr(request.data, "getlist"):
            user_ids = request.data.getlist("user_ids") or request.data.getlist("user_ids[]")
        if isinstance(user_ids, str):
            import json
            try:
                user_ids = json.loads(user_ids)
            except Exception:
                user_ids = [uid.strip() for uid in user_ids.split(",") if uid.strip()]

        users_qs = User.objects.filter(is_active=True)
        if effective_station_id:
            users_qs = users_qs.filter(station_id=effective_station_id)
        if target_role:
            users_qs = users_qs.filter(role=target_role)
        if user_ids and isinstance(user_ids, list):
            users_qs = users_qs.filter(id__in=user_ids)

        recipients = list(users_qs)
        if not recipients:
            return Response(
                {"detail": "No eligible active recipients match the dispatch criteria."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        notifications = [
            Notification(
                user=u,
                title=title,
                message=message,
                notification_type="BROADCAST",
            )
            for u in recipients
        ]
        Notification.objects.bulk_create(notifications)

        log_security_event(
            event_type="NOTIFICATION_BROADCAST",
            actor=request.user,
            target_model="Notification",
            details={
                "title": title,
                "recipients_count": len(notifications),
                "station_id": str(effective_station_id) if effective_station_id else None,
                "target_role": target_role,
            }
        )

        return Response({
            "message": f"Broadcast delivered to {len(notifications)} personnel.",
            "recipients_count": len(notifications),
            "status": "success",
        }, status=status.HTTP_201_CREATED)


class DirectMessageViewSet(viewsets.ModelViewSet):
    """
    Direct operational communications strictly enforcing authorization boundaries:
    - Guard can ONLY message their assigned partner or their station supervisor.
    - Supervisor can ONLY message guards assigned to their station.
    - Administrators can message any active personnel.
    """
    queryset = DirectMessage.objects.all().select_related("sender", "recipient")
    serializer_class = DirectMessageSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = DirectMessage.objects.filter(Q(sender=user) | Q(recipient=user))
        with_user = self.request.query_params.get("with_user")
        if with_user:
            qs = qs.filter(Q(sender_id=with_user, recipient=user) | Q(sender=user, recipient_id=with_user))
        return qs.order_by("created_at")

    def perform_create(self, serializer):
        sender = self.request.user
        recipient = serializer.validated_data.get("recipient")
        if not recipient:
            recipient_id = self.request.data.get("recipient") or self.request.data.get("recipient_id")
            if not recipient_id:
                raise ValidationError({"recipient": "Recipient ID is required."})
            recipient = User.objects.filter(id=recipient_id, is_active=True).first()
            if not recipient:
                raise ValidationError({"recipient": "Active recipient personnel not found."})

        if sender == recipient:
            raise ValidationError({"recipient": "Cannot send messages to yourself."})

        # Boundary enforcement
        if sender.role == UserRole.GUARD:
            allowed_partner_ids = set()
            if sender.station:
                pairs = GuardPair.objects.filter(station=sender.station, is_active=True)
                for p in pairs:
                    partner = p.get_partner_for(sender)
                    if partner:
                        allowed_partner_ids.add(str(partner.id))

            today_shift = Shift.objects.filter(guard=sender, date=timezone.localdate()).first()
            if today_shift:
                partner = today_shift.get_partner()
                if partner:
                    allowed_partner_ids.add(str(partner.id))

            allowed_supervisor_ids = set()
            if sender.station:
                supervisors = User.objects.filter(role=UserRole.SUPERVISOR, station=sender.station, is_active=True)
                allowed_supervisor_ids.update(str(s.id) for s in supervisors)

            target_id_str = str(recipient.id)
            if target_id_str not in allowed_partner_ids and target_id_str not in allowed_supervisor_ids:
                raise PermissionDenied(
                    "Communication boundary violation: Security guards can only exchange messages with their assigned partner or station supervisor."
                )

        elif sender.role == UserRole.SUPERVISOR:
            if not sender.station or recipient.station != sender.station:
                raise PermissionDenied(
                    "Supervisors can only message security personnel stationed at their assigned post."
                )

        msg = serializer.save(sender=sender, recipient=recipient)

        Notification.objects.create(
            user=recipient,
            title=f"Message from {sender.get_full_name() or sender.username}",
            message=msg.content[:150],
            notification_type="DIRECT_MESSAGE",
        )

    @action(detail=False, methods=["get"], url_path="unread_count")
    def unread_count(self, request):
        count = DirectMessage.objects.filter(recipient=request.user, read=False).count()
        return Response({"unread_count": count}, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="mark_read")
    def mark_read(self, request):
        with_user = request.data.get("with_user")
        if with_user:
            updated = DirectMessage.objects.filter(recipient=request.user, sender_id=with_user, read=False).update(read=True)
        else:
            updated = DirectMessage.objects.filter(recipient=request.user, read=False).update(read=True)
        return Response({"message": f"{updated} messages marked as read.", "updated_count": updated}, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="read")
    def mark_individual_read(self, request, pk=None):
        msg = self.get_object()
        if msg.recipient != request.user:
            raise PermissionDenied("You can only mark your own received messages as read.")
        msg.read = True
        msg.save(update_fields=["read"])
        return Response(self.get_serializer(msg).data, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="mark_read")
    def mark_individual_read_alias(self, request, pk=None):
        return self.mark_individual_read(request, pk)
