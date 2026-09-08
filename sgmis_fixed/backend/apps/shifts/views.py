from datetime import datetime, time, timedelta
from django.utils import timezone
from django.shortcuts import get_object_or_404
from rest_framework import status, viewsets
from rest_framework.views import APIView
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import Shift, ShiftHandover, Attendance
from .serializers import (
    ShiftSerializer,
    ShiftHandoverSerializer,
    AttendanceSerializer,
    ClockInRequestSerializer,
    ClockOutRequestSerializer,
    RosterGenerateRequestSerializer,
)
from .services import resolve_incoming_guard, generate_roster_for_station
from apps.stations.models import Station
from apps.accounts.models import UserRole
from apps.accounts.permissions import IsSupervisorOrAdmin

class ShiftViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Operational duty shifts.
    Provides GET /shifts/shifts/today/ for the authenticated guard.
    """
    queryset = Shift.objects.all().select_related("station", "guard", "pair", "pair__guard_a", "pair__guard_b")
    serializer_class = ShiftSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        date_param = self.request.query_params.get("date")
        station_id = self.request.query_params.get("station")
        guard_id = self.request.query_params.get("guard")

        if date_param:
            qs = qs.filter(date=date_param)
        if station_id:
            qs = qs.filter(station_id=station_id)
        if guard_id:
            qs = qs.filter(guard_id=guard_id)

        # Guards only see their own shifts unless asking for today's station roster
        if user.role == UserRole.GUARD and not guard_id and not station_id:
            qs = qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station:
            qs = qs.filter(station=user.station)

        return qs

    @action(detail=False, methods=["get"], url_path="today")
    def today(self, request):
        """
        GET /shifts/shifts/today/
        Returns today's authoritative shift for the authenticated guard.
        """
        today_date = timezone.now().date()
        shift = Shift.objects.filter(
            guard=request.user,
            date=today_date
        ).select_related("station", "guard", "pair", "pair__guard_a", "pair__guard_b").first()

        if not shift:
            return Response(
                {"detail": "No shift scheduled for today.", "shift": None},
                status=status.HTTP_200_OK,
            )
        serializer = self.get_serializer(shift)
        return Response(serializer.data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="generate", permission_classes=[IsSupervisorOrAdmin])
    def generate_roster(self, request):
        """
        POST /shifts/shifts/generate/
        Supervisor / Admin generates roster cycle for a station.
        """
        serializer = RosterGenerateRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        station_id = serializer.validated_data["station_id"]
        start_date = serializer.validated_data["start_date"]
        cycle_days = serializer.validated_data["cycle_days"]

        station = get_object_or_404(Station, id=station_id)
        try:
            created_shifts = generate_roster_for_station(station, start_date, cycle_days=cycle_days)
            return Response({
                "message": f"Successfully generated {len(created_shifts)} shifts for {station.name}.",
                "shifts_count": len(created_shifts),
            }, status=status.HTTP_201_CREATED)
        except ValueError as e:
            return Response({"detail": str(e)}, status=status.HTTP_400_BAD_REQUEST)

class AttendanceViewSet(viewsets.ModelViewSet):
    """
    Operational attendance tracking.
    Enforces server timestamping, guard identity verification, and GPS recording.
    """
    queryset = Attendance.objects.all().select_related("shift", "shift__station", "guard")
    serializer_class = AttendanceSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        shift_id = self.request.query_params.get("shift")
        date_param = self.request.query_params.get("date")

        if shift_id:
            qs = qs.filter(shift_id=shift_id)
        if date_param:
            qs = qs.filter(shift__date=date_param)

        if user.role == UserRole.GUARD:
            qs = qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station:
            qs = qs.filter(shift__station=user.station)

        return qs

    @action(detail=False, methods=["post"], url_path="clock_in")
    def clock_in(self, request):
        """
        POST /shifts/attendance/clock_in/
        Server validates guard assignment, sets timestamp, checks late status.
        """
        serializer = ClockInRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        shift_id = serializer.validated_data["shift_id"]
        lat = serializer.validated_data.get("latitude")
        lon = serializer.validated_data.get("longitude")
        late_reason = serializer.validated_data.get("late_reason", "")

        shift = get_object_or_404(Shift, id=shift_id)

        # Ensure guard cannot clock in for someone else's shift
        if shift.guard != request.user:
            return Response(
                {"detail": "You cannot clock in for a shift assigned to another guard."},
                status=status.HTTP_403_FORBIDDEN,
            )

        # Server authoritative timestamp
        now = timezone.now()
        gps_str = f"{lat},{lon}" if (lat is not None and lon is not None) else ""

        # Calculate late status
        scheduled_start_dt = timezone.make_aware(
            datetime.combine(shift.date, shift.start_time),
            timezone.get_current_timezone()
        )
        # 15 minutes grace period
        is_late = now > (scheduled_start_dt + timedelta(minutes=15))

        attendance, created = Attendance.objects.get_or_create(
            shift=shift,
            guard=request.user,
            defaults={
                "clock_in": now,
                "clock_in_gps": gps_str,
                "is_late": is_late,
                "late_reason": late_reason if is_late else "",
            }
        )

        if not created and attendance.clock_in:
            return Response(
                {"detail": "You have already clocked in for this shift.", "attendance": AttendanceSerializer(attendance).data},
                status=status.HTTP_400_BAD_REQUEST,
            )
        elif not created:
            attendance.clock_in = now
            attendance.clock_in_gps = gps_str
            attendance.is_late = is_late
            if late_reason:
                attendance.late_reason = late_reason
            attendance.save()

        return Response(AttendanceSerializer(attendance).data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="clock_out")
    def clock_out(self, request):
        """
        POST /shifts/attendance/clock_out/
        Server validates existing clock-in and sets server clock-out time.
        """
        serializer = ClockOutRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        shift_id = serializer.validated_data["shift_id"]
        lat = serializer.validated_data.get("latitude")
        lon = serializer.validated_data.get("longitude")

        shift = get_object_or_404(Shift, id=shift_id)
        if shift.guard != request.user:
            return Response(
                {"detail": "You cannot clock out for a shift assigned to another guard."},
                status=status.HTTP_403_FORBIDDEN,
            )

        attendance = Attendance.objects.filter(shift=shift, guard=request.user).first()
        if not attendance or not attendance.clock_in:
            return Response(
                {"detail": "Cannot clock out: No active clock-in found for this shift."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        if attendance.clock_out:
            return Response(
                {"detail": "You have already clocked out for this shift.", "attendance": AttendanceSerializer(attendance).data},
                status=status.HTTP_400_BAD_REQUEST,
            )

        attendance.clock_out = timezone.now()
        if lat is not None and lon is not None:
            attendance.clock_out_gps = f"{lat},{lon}"
        attendance.save()

        return Response(AttendanceSerializer(attendance).data, status=status.HTTP_200_OK)

class ShiftHandoverViewSet(viewsets.ModelViewSet):
    """
    Operational handovers between outgoing and incoming guards.
    Incoming guard is strictly determined from roster logic by backend.
    """
    queryset = ShiftHandover.objects.all().select_related("outgoing_shift", "outgoing_guard", "incoming_guard", "station")
    serializer_class = ShiftHandoverSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            qs = qs.filter(outgoing_guard=user) | qs.filter(incoming_guard=user)
        elif user.role == UserRole.SUPERVISOR and user.station:
            qs = qs.filter(station=user.station)
        return qs

    def create(self, request, *args, **kwargs):
        """
        POST /shifts/handovers/
        Outgoing guard submits handover.
        Backend determines the incoming guard from the official roster.
        """
        outgoing_shift_id = request.data.get("outgoing_shift")
        if not outgoing_shift_id:
            return Response(
                {"detail": "outgoing_shift is required."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        outgoing_shift = get_object_or_404(Shift, id=outgoing_shift_id)
        if outgoing_shift.guard != request.user and request.user.role == UserRole.GUARD:
            return Response(
                {"detail": "You cannot submit a handover for another guard's shift."},
                status=status.HTTP_403_FORBIDDEN,
            )

        # Authoritative backend determination of incoming guard
        incoming_guard = resolve_incoming_guard(outgoing_shift)
        if not incoming_guard:
            return Response(
                {"detail": "Unable to determine incoming guard: No consecutive shift is scheduled on the roster for this station. Please notify your supervisor."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        occurrence_summary = request.data.get("occurrence_summary", "").strip()
        if not occurrence_summary:
            return Response(
                {"detail": "Occurrence summary cannot be empty."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        handover = ShiftHandover.objects.create(
            outgoing_shift=outgoing_shift,
            outgoing_guard=request.user,
            incoming_guard=incoming_guard,
            station=outgoing_shift.station,
            occurrence_summary=occurrence_summary,
            equipment_issued=request.data.get("equipment_issued", "All post equipment accounted for."),
            keys_handed_over=request.data.get("keys_handed_over", "Post keys transferred."),
            pending_issues=request.data.get("pending_issues", "None."),
            outgoing_signed=True,
        )

        return Response(self.get_serializer(handover).data, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=["post"], url_path="accept")
    def accept(self, request, pk=None):
        """
        POST /shifts/handovers/{id}/accept/
        Incoming guard accepts handover. Sets incoming_accepted_at to server time.
        """
        handover = self.get_object()
        if handover.incoming_guard != request.user and request.user.role == UserRole.GUARD:
            return Response(
                {"detail": "Only the designated incoming guard can accept this handover."},
                status=status.HTTP_403_FORBIDDEN,
            )

        if handover.incoming_accepted:
            return Response(
                {"detail": "This handover has already been accepted."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        handover.incoming_accepted = True
        handover.incoming_accepted_at = timezone.now()
        handover.save()

        return Response(self.get_serializer(handover).data, status=status.HTTP_200_OK)
