from rest_framework import viewsets
from rest_framework.permissions import IsAuthenticated
from .models import OccurrenceBookEntry
from .serializers import OccurrenceBookEntrySerializer
from apps.accounts.models import UserRole

class OccurrenceBookEntryViewSet(viewsets.ModelViewSet):
    queryset = OccurrenceBookEntry.objects.all().select_related("station", "guard")
    serializer_class = OccurrenceBookEntrySerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        station_id = self.request.query_params.get("station")
        category = self.request.query_params.get("category")

        if station_id:
            qs = qs.filter(station_id=station_id)
        if category:
            qs = qs.filter(category=category)

        if user.role == UserRole.GUARD:
            # Guard sees entries for their assigned station or own entries
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station:
            qs = qs.filter(station=user.station)

        return qs

    def perform_create(self, serializer):
        user = self.request.user
        if user.role == UserRole.GUARD and not user.station:
            from rest_framework.exceptions import PermissionDenied
            raise PermissionDenied("Your account has no station assigned. Contact your supervisor or administrator.")

        station = serializer.validated_data.get("station") or user.station
        if not station:
            from rest_framework.exceptions import ValidationError
            raise ValidationError({"station": "Your account has no station assigned. Contact your supervisor or administrator."})

        serializer.save(guard=user, station=station)
