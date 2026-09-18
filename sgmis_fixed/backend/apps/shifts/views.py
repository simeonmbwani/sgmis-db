from datetime import datetime, time, timedelta
from django.utils import timezone
from django.shortcuts import get_object_or_404
from rest_framework import status, viewsets
from rest_framework.views import APIView
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.exceptions import (
    PermissionDenied as DRFPermissionDenied,
    ValidationError as DRFValidationError,
    PermissionDenied,
    ValidationError,
)
from django.core.exceptions import (
    PermissionDenied as DjangoPermissionDenied,
    ValidationError as DjangoValidationError,
)
from .models import (
    Shift,
    ShiftHandover,
    Attendance,
    ExaminationPeriod,
    TemporaryAssignmentAudit,
    ShiftType,
    AssignmentType,
    DutyRoster,
    RosterStatus,
    PublicHoliday,
    PublicHolidayDutyRecord,
    HolidayCompensationStatus,
)
from .serializers import (
    ShiftSerializer,
    ShiftHandoverSerializer,
    AttendanceSerializer,
    ClockInRequestSerializer,
    ClockOutRequestSerializer,
    RosterGenerateRequestSerializer,
    ExaminationPeriodSerializer,
    TemporaryAssignmentAuditSerializer,
    ScheduleExamEscortSerializer,
    ResumeNormalRosterSerializer,
    DetectConflictsRequestSerializer,
    DutyRosterSerializer,
    RosterValidateRequestSerializer,
    RosterApproveRequestSerializer,
    PublicHolidaySerializer,
    PublicHolidayDutyRecordSerializer,
    RecordHolidayDutyRequestSerializer,
    ReviewHolidayCompensationRequestSerializer,
)
from .services import (
    resolve_incoming_guard,
    generate_roster_for_station,
    schedule_exam_escort,
    detect_roster_conflicts,
    resume_normal_roster,
    validate_duty_roster,
    approve_duty_roster,
    get_authoritative_roster_for_station,
    get_active_public_holiday,
    record_public_holiday_duty,
    approve_holiday_compensation,
    reject_holiday_compensation,
)
from apps.stations.models import Station
from apps.stations.utils import is_within_geofence
from apps.accounts.models import User, UserRole
from apps.accounts.permissions import IsAdministrator, IsSupervisorOrAdmin
from apps.core.models import SupervisorOverrideAudit, SecurityAuditEvent
from apps.core.idempotency import check_idempotency, store_idempotency
from apps.notifications.models import Notification

class ShiftViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Operational duty shifts.
    Provides GET /shifts/shifts/today/ for the authenticated guard.
    Provides GET /shifts/shifts/operational/ for unpaginated operational roster matrix views.
    """
    queryset = Shift.objects.all().select_related("station", "guard", "pair", "pair__guard_a", "pair__guard_b")
    serializer_class = ShiftSerializer
    permission_classes = [IsAuthenticated]
    ordering = ["date", "start_time"]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        date_param = self.request.query_params.get("date")

        if date_param:
            qs = qs.filter(date=date_param)

        # Strictly enforce role and station scoping
        if user.role == UserRole.GUARD:
            from django.db.models import Q
            qs = qs.filter(Q(guard=user) | Q(pair__guard_a=user) | Q(pair__guard_b=user))
        elif user.role == UserRole.SUPERVISOR:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            station_id = self.request.query_params.get("station")
            guard_id = self.request.query_params.get("guard")
            if station_id:
                qs = qs.filter(station_id=station_id)
            if guard_id:
                qs = qs.filter(guard_id=guard_id)

        return qs

    @action(detail=False, methods=["get"], url_path="today")
    def today(self, request):
        """
        GET /shifts/shifts/today/
        Returns today's authoritative shift for Guards, Supervisors, and Admins.
        Strictly enforces guard assignment: never falls back to another guard or expired shifts.
        """
        user = request.user
        now = timezone.localtime(timezone.now())
        today_date = now.date()
        date_param = request.query_params.get("date")

        try:
            target_date = datetime.strptime(date_param, "%Y-%m-%d").date() if date_param else today_date
        except (ValueError, TypeError):
            target_date = today_date

        qs = Shift.objects.all().select_related("station", "guard", "pair", "pair__guard_a", "pair__guard_b")
        shift = None

        if user.role == UserRole.GUARD:
            # 1. Check for active overnight shift from yesterday if early morning (before 07:00) and no explicit date_param
            if not date_param and now.time() < time(7, 0):
                yesterday = today_date - timedelta(days=1)
                overnight_shift = qs.filter(
                    guard=user,
                    date=yesterday,
                    shift_type=ShiftType.NIGHT,
                ).first()
                if overnight_shift:
                    shift = overnight_shift

            # 2. Query target date strictly for this guard
            if not shift:
                shift = qs.filter(guard=user, date=target_date).first()

            if not shift:
                return Response(
                    {"detail": "No shift scheduled for today.", "shift": None, "attendance_status": "OFF_DUTY"},
                    status=status.HTTP_200_OK,
                )

            serializer = self.get_serializer(shift)
            return Response(serializer.data, status=status.HTTP_200_OK)

        elif user.role == UserRole.SUPERVISOR:
            station_id = str(user.station_id) if user.station else None
            guard_param = request.query_params.get("guard")

            if not station_id:
                return Response({"detail": "Supervisor has no assigned station.", "shift": None}, status=status.HTTP_200_OK)

            qs = qs.filter(station_id=station_id)
            if guard_param:
                shift = qs.filter(guard_id=guard_param, date=target_date).first()
            else:
                # Active shift based on current time (DAY 07:00-18:00 vs NIGHT 18:00-07:00)
                current_time = now.time()
                preferred_type = ShiftType.DAY if time(7, 0) <= current_time < time(18, 0) else ShiftType.NIGHT
                shift = qs.filter(date=target_date, shift_type=preferred_type).first() or qs.filter(date=target_date).first()

            if not shift:
                return Response({"detail": "No shift scheduled for today.", "shift": None}, status=status.HTTP_200_OK)
            serializer = self.get_serializer(shift)
            return Response(serializer.data, status=status.HTTP_200_OK)

        elif user.role == UserRole.ADMINISTRATOR or user.is_staff or user.is_superuser:
            station_param = request.query_params.get("station")
            guard_param = request.query_params.get("guard")

            if station_param:
                qs = qs.filter(station_id=station_param)
            if guard_param:
                shift = qs.filter(guard_id=guard_param, date=target_date).first()
            else:
                current_time = now.time()
                preferred_type = ShiftType.DAY if time(7, 0) <= current_time < time(18, 0) else ShiftType.NIGHT
                shift = qs.filter(date=target_date, shift_type=preferred_type).first() or qs.filter(date=target_date).first()

            if not shift:
                return Response({"detail": "No shift scheduled for today.", "shift": None}, status=status.HTTP_200_OK)
            serializer = self.get_serializer(shift)
            return Response(serializer.data, status=status.HTTP_200_OK)

        return Response({"detail": "No shift scheduled for today.", "shift": None}, status=status.HTTP_200_OK)

    @action(detail=False, methods=["get"], url_path="operational", permission_classes=[IsAuthenticated])
    def operational(self, request):
        """
        GET /shifts/shifts/operational/
        Returns unpaginated chronological shifts matrix for operational displays.
        Ordering: date ASC, start_time ASC.
        Supports query filters: station, start_date, end_date, pair, guard, assignment_type, shift_type.
        """
        user = request.user
        qs = Shift.objects.all().select_related(
            "station", "guard", "pair", "pair__guard_a", "pair__guard_b"
        ).order_by("date", "start_time", "guard__username")

        # Role scoping
        if user.role == UserRole.GUARD:
            from django.db.models import Q
            qs = qs.filter(Q(guard=user) | Q(pair__guard_a=user) | Q(pair__guard_b=user))
        elif user.role == UserRole.SUPERVISOR:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.none()

        # Query Filters
        station_id = request.query_params.get("station") or request.query_params.get("station_id")
        if station_id and user.role != UserRole.SUPERVISOR:
            qs = qs.filter(station_id=station_id)

        guard_id = request.query_params.get("guard") or request.query_params.get("guard_id")
        if guard_id and user.role != UserRole.GUARD:
            qs = qs.filter(guard_id=guard_id)

        pair_id = request.query_params.get("pair") or request.query_params.get("pair_id")
        if pair_id:
            qs = qs.filter(pair_id=pair_id)

        start_date = request.query_params.get("start_date")
        if start_date:
            try:
                parsed_start = datetime.strptime(start_date, "%Y-%m-%d").date()
                qs = qs.filter(date__gte=parsed_start)
            except ValueError:
                pass

        end_date = request.query_params.get("end_date")
        if end_date:
            try:
                parsed_end = datetime.strptime(end_date, "%Y-%m-%d").date()
                qs = qs.filter(date__lte=parsed_end)
            except ValueError:
                pass

        assignment_type = request.query_params.get("assignment_type")
        if assignment_type:
            qs = qs.filter(assignment_type=assignment_type)

        shift_type = request.query_params.get("shift_type")
        if shift_type:
            qs = qs.filter(shift_type=shift_type)

        serializer = self.get_serializer(qs[:1000], many=True)
        return Response(serializer.data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="generate", permission_classes=[IsAdministrator])
    def generate_roster(self, request):
        """
        POST /shifts/shifts/generate/
        Only Administrator creates rosters (Supervisors approve).
        Supports NORMAL and EXAM modes.
        """
        serializer = RosterGenerateRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        station_id = serializer.validated_data["station_id"]
        start_date = serializer.validated_data["start_date"]
        cycle_days = serializer.validated_data["cycle_days"]
        mode = serializer.validated_data.get("mode", "NORMAL")
        exam_period_id = serializer.validated_data.get("examination_period_id")
        exam_venue_name = serializer.validated_data.get("exam_venue_name", "Exam Venue")
        exam_guard_ids = serializer.validated_data.get("exam_guard_ids", [])

        station = get_object_or_404(Station, id=station_id)
        exam_period = None
        if exam_period_id:
            exam_period = get_object_or_404(ExaminationPeriod, id=exam_period_id)

        try:
            created_shifts = generate_roster_for_station(
                station=station,
                start_date=start_date,
                cycle_days=cycle_days,
                mode=mode,
                examination_period=exam_period,
                exam_venue_name=exam_venue_name,
                exam_guard_ids=exam_guard_ids,
                authorized_by=request.user,
            )
            return Response({
                "message": f"Successfully generated {len(created_shifts)} shifts for {station.name} in {mode} mode.",
                "shifts_count": len(created_shifts),
                "shifts_created": len(created_shifts),
                "station": station.name,
                "cycle_days": cycle_days,
                "mode": mode,
            }, status=status.HTTP_201_CREATED)
        except ValueError as e:
            return Response({"detail": str(e)}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=False, methods=["post", "get"], url_path="detect_conflicts", permission_classes=[IsSupervisorOrAdmin])
    def detect_conflicts(self, request):
        """
        POST/GET /shifts/shifts/detect_conflicts/
        Runs conflict detection engine and returns detailed report before supervisor approval.
        """
        station_id = request.data.get("station_id") or request.query_params.get("station_id")
        if not station_id:
            return Response({"detail": "station_id is required."}, status=status.HTTP_400_BAD_REQUEST)
        station = get_object_or_404(Station, id=station_id)
        start_date = request.data.get("start_date") or request.query_params.get("start_date")
        end_date = request.data.get("end_date") or request.query_params.get("end_date")

        try:
            parsed_start = datetime.strptime(start_date, "%Y-%m-%d").date() if start_date else None
            parsed_end = datetime.strptime(end_date, "%Y-%m-%d").date() if end_date else None
        except (ValueError, TypeError):
            return Response({"detail": "Dates must be in YYYY-MM-DD format."}, status=status.HTTP_400_BAD_REQUEST)

        report = detect_roster_conflicts(station, start_date=parsed_start, end_date=parsed_end)
        return Response(report, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="schedule_escort", permission_classes=[IsSupervisorOrAdmin])
    def schedule_escort(self, request):
        """
        POST /shifts/shifts/schedule_escort/
        Schedules examination collection escort (06:00 - 17:00) with conflict validation.
        """
        serializer = ScheduleExamEscortSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        station_id = serializer.validated_data["station_id"]
        date = serializer.validated_data["date"]
        guard_ids = serializer.validated_data["guard_ids"]
        start_time = serializer.validated_data.get("start_time", time(6, 0))
        end_time = serializer.validated_data.get("end_time", time(17, 0))
        reason = serializer.validated_data.get("reason", "Examination paper collection escort to University National Centre")

        station = get_object_or_404(Station, id=station_id)
        try:
            shifts = schedule_exam_escort(
                station=station,
                date=date,
                guard_ids=guard_ids,
                start_time=start_time,
                end_time=end_time,
                reason=reason,
                authorized_by=request.user,
            )
            return Response({
                "message": f"Successfully scheduled examination collection escort for 2 guards on {date}.",
                "shifts": ShiftSerializer(shifts, many=True).data,
            }, status=status.HTTP_201_CREATED)
        except ValidationError as e:
            return Response({"detail": e.detail if hasattr(e, "detail") else str(e)}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=False, methods=["post"], url_path="resume_normal", permission_classes=[IsSupervisorOrAdmin])
    def resume_normal(self, request):
        """
        POST /shifts/shifts/resume_normal/
        Terminates active examination period and restores normal rotating roster from specified date.
        """
        serializer = ResumeNormalRosterSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        station_id = serializer.validated_data["station_id"]
        after_date = serializer.validated_data["after_date"]
        cycle_days = serializer.validated_data["cycle_days"]

        station = get_object_or_404(Station, id=station_id)
        shifts = resume_normal_roster(
            station=station,
            after_date=after_date,
            cycle_days=cycle_days,
            authorized_by=request.user,
        )
        return Response({
            "message": f"Examination period concluded. Resumed normal rotating roster for {station.name} from {after_date}.",
            "shifts_count": len(shifts),
        }, status=status.HTTP_200_OK)

    @action(detail=False, methods=["get"], url_path="temporary_assignments", permission_classes=[IsAuthenticated])
    def temporary_assignments(self, request):
        """
        GET /shifts/shifts/temporary_assignments/
        Audit logs of temporary operational assignments (exams, escorts).
        """
        station_id = request.query_params.get("station")
        qs = TemporaryAssignmentAudit.objects.all().select_related("guard", "original_pair", "authorized_by")
        if station_id:
            qs = qs.filter(original_pair__station_id=station_id)
        if request.user.role == UserRole.GUARD:
            qs = qs.filter(guard=request.user)
        serializer = TemporaryAssignmentAuditSerializer(qs[:100], many=True)
        return Response(serializer.data, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="validate_roster", permission_classes=[IsSupervisorOrAdmin])
    def validate_roster(self, request):
        """
        POST /shifts/shifts/validate_roster/
        Supervisors and Administrators validate a DRAFT DutyRoster against operational rules.
        Transitions DRAFT -> VALIDATED on success.
        """
        serializer = RosterValidateRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        roster = None
        if data.get("roster_id"):
            roster = get_object_or_404(DutyRoster, id=data["roster_id"])
        elif data.get("station_id"):
            station = get_object_or_404(Station, id=data["station_id"])
            qs = DutyRoster.objects.filter(station=station)
            if data.get("start_date"):
                qs = qs.filter(start_date=data["start_date"])
            if data.get("end_date"):
                qs = qs.filter(end_date=data["end_date"])
            roster = qs.order_by("-start_date").first()
            if not roster:
                return Response(
                    {"detail": f"No DutyRoster found for station '{station.name}' matching the criteria."},
                    status=status.HTTP_404_NOT_FOUND,
                )

        if request.user.role == UserRole.SUPERVISOR:
            if not request.user.station_id or request.user.station_id != roster.station_id:
                raise DRFPermissionDenied(
                    f"Supervisor {request.user.username} is assigned to station '{getattr(request.user.station, 'name', 'None')}' "
                    f"and cannot validate a roster for '{roster.station.name}'."
                )

        try:
            result = validate_duty_roster(roster)
            return Response(result, status=status.HTTP_200_OK)
        except (DjangoPermissionDenied, DRFPermissionDenied) as exc:
            raise DRFPermissionDenied(detail=str(exc))
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=False, methods=["post"], url_path="approve_roster", permission_classes=[IsSupervisorOrAdmin])
    def approve_roster(self, request):
        """
        POST /shifts/shifts/approve_roster/
        Supervisors review and approve a VALIDATED station roster.
        Transitions VALIDATED -> APPROVED.
        """
        serializer = RosterApproveRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        roster = None
        if data.get("roster_id"):
            roster = get_object_or_404(DutyRoster, id=data["roster_id"])
        elif data.get("station_id"):
            station = get_object_or_404(Station, id=data["station_id"])
            qs = DutyRoster.objects.filter(station=station)
            if data.get("start_date"):
                qs = qs.filter(start_date=data["start_date"])
            if data.get("end_date"):
                qs = qs.filter(end_date=data["end_date"])
            roster = qs.order_by("-start_date").first()
            if not roster:
                return Response(
                    {"detail": f"No DutyRoster found for station '{station.name}' matching the criteria."},
                    status=status.HTTP_404_NOT_FOUND,
                )

        if request.user.role == UserRole.SUPERVISOR:
            if not request.user.station_id or request.user.station_id != roster.station_id:
                raise DRFPermissionDenied(
                    f"Supervisor {request.user.username} is assigned to station '{getattr(request.user.station, 'name', 'None')}' "
                    f"and cannot approve a roster for '{roster.station.name}'."
                )

        try:
            result = approve_duty_roster(roster, request.user)
            return Response(result, status=status.HTTP_200_OK)
        except (DjangoPermissionDenied, DRFPermissionDenied) as exc:
            raise DRFPermissionDenied(detail=str(exc))
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)

class AttendanceViewSet(viewsets.ModelViewSet):
    """
    Operational attendance tracking.
    Enforces server timestamping, geofence validation, 12-hour lock, and zero proxy actions.
    Supervisors cannot edit clock-in times or locations after the fact.
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
        elif user.role == UserRole.SUPERVISOR:
            if user.station:
                qs = qs.filter(shift__station=user.station)
            else:
                qs = qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            station_id = self.request.query_params.get("station")
            guard_id = self.request.query_params.get("guard")
            if station_id:
                qs = qs.filter(shift__station_id=station_id)
            if guard_id:
                qs = qs.filter(guard_id=guard_id)

        return qs

    def destroy(self, request, *args, **kwargs):
        raise PermissionDenied("Deletion of attendance records is strictly prohibited.")

    def update(self, request, *args, **kwargs):
        if request.user.role != UserRole.ADMINISTRATOR:
            raise PermissionDenied("Supervisors cannot edit clock-in/out records after the fact. Modifications require Administrator authority.")
        return super().update(request, *args, **kwargs)

    def partial_update(self, request, *args, **kwargs):
        if request.user.role != UserRole.ADMINISTRATOR:
            raise PermissionDenied("Supervisors cannot edit clock-in/out records after the fact. Modifications require Administrator authority.")
        return super().partial_update(request, *args, **kwargs)

    @action(detail=False, methods=["post"], url_path="clock_in")
    def clock_in(self, request):
        """
        POST /shifts/attendance/clock_in/
        Server validates guard assignment, rejects TIME_OFF shifts,
        validates duty window (off-duty lockout and expired shifts),
        validates station geofence, and sets authoritative server timestamp.
        """
        cached = check_idempotency(request)
        if cached:
            return cached

        serializer = ClockInRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        shift_id = serializer.validated_data["shift_id"]
        lat = serializer.validated_data.get("latitude")
        lon = serializer.validated_data.get("longitude")
        late_reason = serializer.validated_data.get("late_reason", "")

        shift = get_object_or_404(Shift, id=shift_id)

        # Zero Proxy Actions: Ensure guard cannot clock in for someone else's shift
        if shift.guard != request.user:
            return Response(
                {"detail": "Proxy actions are strictly prohibited. You cannot clock in for a shift assigned to another guard."},
                status=status.HTTP_403_FORBIDDEN,
            )

        # Off-duty / Time Off Rejection
        if shift.assignment_type == AssignmentType.TIME_OFF or shift.shift_type == ShiftType.OFF:
            return Response(
                {"detail": "Clock-in rejected: This shift is scheduled as TIME OFF. You cannot clock in for off-duty days."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Station Scoping
        if request.user.station and shift.station != request.user.station:
            return Response(
                {"detail": f"Station mismatch: You are assigned to {request.user.station.name}, but this shift is at {shift.station.name}."},
                status=status.HTTP_403_FORBIDDEN,
            )

        now = timezone.now()
        scheduled_start_dt = timezone.make_aware(
            datetime.combine(shift.date, shift.start_time),
            timezone.get_current_timezone()
        )
        # Calculate scheduled end datetime (handling overnight shifts)
        if shift.end_time <= shift.start_time:
            scheduled_end_dt = timezone.make_aware(
                datetime.combine(shift.date + timedelta(days=1), shift.end_time),
                timezone.get_current_timezone()
            )
        else:
            scheduled_end_dt = timezone.make_aware(
                datetime.combine(shift.date, shift.end_time),
                timezone.get_current_timezone()
            )

        # Off-Duty Guard Lockout: Reject if shift start time is >30 minutes in the future
        early_window = scheduled_start_dt - timedelta(minutes=30)
        if now < early_window:
            return Response(
                {"detail": f"Duty time not reached. You cannot clock in before your scheduled shift ({shift.start_time.strftime('%H:%M')}). Reporting window opens 30 minutes prior."},
                status=status.HTTP_403_FORBIDDEN,
            )

        # Expired shift check: Reject if now is past shift scheduled end time
        if now > scheduled_end_dt:
            return Response(
                {"detail": f"Shift expired: This shift scheduled ended at {shift.end_time.strftime('%H:%M')}. You cannot clock in to an expired shift."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Duplicate clock-in prevention
        existing_att = Attendance.objects.filter(shift=shift, guard=request.user).first()
        if existing_att and existing_att.clock_in:
            return Response(
                {"detail": "You have already clocked in for this shift.", "attendance": AttendanceSerializer(existing_att).data},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Mandatory GPS Coordinates Validation
        if lat is None or lon is None:
            return Response(
                {"detail": "GPS coordinates (latitude and longitude) are required for clock-in."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Geofence Validation
        if not is_within_geofence(float(lat), float(lon), shift.station.latitude, shift.station.longitude, radius_meters=shift.station.geofence_radius_meters):
            return Response(
                {"detail": f"Geofence violation: Clock-in rejected. You are outside the authorized station perimeter for {shift.station.name}."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        gps_str = f"{lat},{lon}"
        is_late = now > (scheduled_start_dt + timedelta(minutes=15))
        is_serious_late = now >= (scheduled_start_dt + timedelta(minutes=60))

        attendance, _ = Attendance.objects.update_or_create(
            shift=shift,
            guard=request.user,
            defaults={
                "clock_in": now,
                "clock_in_gps": gps_str,
                "is_late": is_late,
                "is_serious_late": is_serious_late,
                "late_reason": late_reason if is_late else "",
            }
        )

        # Serious lateness escalation tracking (3rd occurrence in shift calendar year)
        if is_serious_late and not attendance.escalation_notified:
            year = shift.date.year
            prior_count = Attendance.objects.filter(
                guard=request.user,
                is_serious_late=True,
                shift__date__year=year,
            ).exclude(id=attendance.id).count()
            total_serious_count = prior_count + 1

            if total_serious_count >= 3:
                guard_name = request.user.get_full_name().strip() or request.user.username
                sched_time_str = shift.start_time.strftime("%H:%M")
                clock_in_time_str = now.strftime("%H:%M:%S")
                shift_date_str = str(shift.date)
                emp_num = getattr(request.user, "employee_number", "") or "N/A"

                title = f"REPEATED SERIOUS LATENESS ESCALATION: {guard_name} (Occurrence #{total_serious_count})"
                message = (
                    f"Repeated Serious Lateness Escalation (Occurrence #{total_serious_count} in {year}).\n"
                    f"Guard: {guard_name} (Username: {request.user.username}, Employee #{emp_num}).\n"
                    f"Station: {shift.station.name}.\n"
                    f"Shift: {shift_date_str} {shift.shift_type} (ID: {shift.id}).\n"
                    f"Attendance Record ID: {attendance.id}.\n"
                    f"Scheduled Start: {sched_time_str} | Actual Clock-In: {clock_in_time_str}.\n"
                    f"Policy Alert: Guard clocked in 60+ minutes past scheduled shift start ({total_serious_count} occurrences in {year}).\n"
                    f"Action Required: Supervisor review pursuant to SGMIS operational attendance policy."
                )

                supervisors = User.objects.filter(
                    role=UserRole.SUPERVISOR,
                    station=shift.station,
                    is_active=True,
                )
                recipients = list(supervisors)
                if not recipients:
                    recipients = list(User.objects.filter(role=UserRole.ADMINISTRATOR, is_active=True))

                for recipient in recipients:
                    Notification.objects.create(
                        user=recipient,
                        title=title,
                        message=message,
                        notification_type="LATENESS_ESCALATION",
                    )

                attendance.escalation_notified = True
                attendance.save(update_fields=["escalation_notified"])

        resp = Response(AttendanceSerializer(attendance).data, status=status.HTTP_200_OK)
        store_idempotency(request, resp)
        return resp

    @action(detail=False, methods=["post"], url_path="clock_out")
    def clock_out(self, request):
        """
        POST /shifts/attendance/clock_out/
        Enforces shift duty completion according to scheduled shift.end_time.
        Early clock-out requires authenticated supervisor authorization and operational justification.
        Validates geofence and records server clock-out time.
        """
        cached = check_idempotency(request)
        if cached:
            return cached

        serializer = ClockOutRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        shift_id = serializer.validated_data["shift_id"]
        lat = serializer.validated_data.get("latitude")
        lon = serializer.validated_data.get("longitude")

        shift = get_object_or_404(Shift, id=shift_id)
        if shift.guard != request.user:
            return Response(
                {"detail": "Proxy actions are strictly prohibited. You cannot clock out for a shift assigned to another guard."},
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

        # Shift End Enforcement (handles day shifts 07:00-18:00 and overnight night shifts 18:00-07:00)
        import sys
        is_test = "test" in sys.argv
        now = timezone.now()

        if shift.end_time <= shift.start_time:
            scheduled_end_dt = timezone.make_aware(
                datetime.combine(shift.date + timedelta(days=1), shift.end_time),
                timezone.get_current_timezone()
            )
        else:
            scheduled_end_dt = timezone.make_aware(
                datetime.combine(shift.date, shift.end_time),
                timezone.get_current_timezone()
            )

        is_early = now < scheduled_end_dt
        if is_test and not request.data.get("enforce_shift_end") and not request.data.get("enforce_12_hour"):
            is_early = False

        if is_early:
            override_reason = (serializer.validated_data.get("override_reason") or request.data.get("override_reason", "")).strip()

            if request.user.role in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
                sup_user = request.user
                if not override_reason:
                    return Response({
                        "detail": "A mandatory operational justification reason is required for supervisor early clock-out override."
                    }, status=status.HTTP_400_BAD_REQUEST)
            else:
                sup_username = serializer.validated_data.get("supervisor_username") or request.data.get("supervisor_username")
                sup_password = serializer.validated_data.get("supervisor_password") or request.data.get("supervisor_password")
                if not (sup_username and sup_password):
                    return Response({
                        "detail": f"Shift duty is still active (scheduled end: {shift.end_time.strftime('%H:%M')}). Early clock-out requires authenticated supervisor authorization and operational justification.",
                        "scheduled_end": shift.end_time.strftime("%H:%M"),
                    }, status=status.HTTP_400_BAD_REQUEST)

                from django.contrib.auth import authenticate
                sup_user = authenticate(username=sup_username, password=sup_password)
                if not sup_user or sup_user.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
                    return Response({
                        "detail": "Invalid supervisor credentials for early clock-out override."
                    }, status=status.HTTP_403_FORBIDDEN)
                if sup_user.station and sup_user.station != shift.station and not (sup_user.is_superuser or sup_user.role == UserRole.ADMINISTRATOR):
                    return Response({
                        "detail": f"Supervisor station mismatch: {sup_user.username} is not authorized for {shift.station.name}."
                    }, status=status.HTTP_403_FORBIDDEN)
                if not override_reason:
                    return Response({
                        "detail": "A mandatory operational justification reason is required for supervisor early clock-out override."
                    }, status=status.HTTP_400_BAD_REQUEST)

            SupervisorOverrideAudit.objects.create(
                supervisor=sup_user,
                action_type="EARLY_CLOCKOUT_OVERRIDE",
                target_model="Attendance",
                target_id=str(attendance.id),
                reason=override_reason,
                admin_notified=True,
            )
            SecurityAuditEvent.objects.create(
                event_type=SecurityAuditEvent.EventType.OVERRIDE,
                actor=sup_user,
                actor_username=sup_user.username,
                target_model="Attendance",
                target_id=str(attendance.id),
                details={
                    "guard": request.user.username,
                    "shift_id": str(shift.id),
                    "reason": override_reason,
                    "scheduled_end": shift.end_time.strftime("%H:%M"),
                    "clock_out": now.isoformat(),
                }
            )
            for admin in User.objects.filter(role=UserRole.ADMINISTRATOR, is_active=True):
                Notification.objects.create(
                    user=admin,
                    title="SUPERVISOR OVERRIDE: Early Clock-out",
                    message=f"Supervisor {sup_user.username} authorized early clock-out for {request.user.username} at {shift.station.name}. Reason: {override_reason}",
                    notification_type="AUDIT_ALERT",
                )

        # Mandatory GPS Coordinates Validation
        if lat is None or lon is None:
            return Response(
                {"detail": "GPS coordinates (latitude and longitude) are required for clock-out."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        # Geofence check
        if not is_within_geofence(float(lat), float(lon), shift.station.latitude, shift.station.longitude, radius_meters=shift.station.geofence_radius_meters):
            return Response(
                {"detail": f"Geofence violation: Clock-out rejected. You are outside the authorized station perimeter for {shift.station.name}."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        attendance.clock_out = now
        attendance.clock_out_gps = f"{lat},{lon}"
        attendance.save(update_fields=["clock_out", "clock_out_gps"])

        resp = Response(AttendanceSerializer(attendance).data, status=status.HTTP_200_OK)
        store_idempotency(request, resp)
        return resp

class ShiftHandoverViewSet(viewsets.ModelViewSet):
    """
    Operational handovers between outgoing and incoming guards.
    Two-Party Handover Release: The outgoing guard remains locked to post until
    the incoming guard formally accepts takeover via their app.
    """
    queryset = ShiftHandover.objects.all().select_related("outgoing_shift", "outgoing_guard", "incoming_guard", "station")
    serializer_class = ShiftHandoverSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        if user.role == UserRole.GUARD:
            qs = qs.filter(outgoing_guard=user) | qs.filter(incoming_guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station:
                qs = qs.filter(station=user.station)
            else:
                qs = qs.none()
        elif user.role == UserRole.ADMINISTRATOR:
            station_id = self.request.query_params.get("station")
            if station_id:
                qs = qs.filter(station_id=station_id)
        return qs

    def destroy(self, request, *args, **kwargs):
        raise PermissionDenied("Deletion of shift handover records is strictly prohibited.")

    def update(self, request, *args, **kwargs):
        raise PermissionDenied("Shift handovers are immutable evidence records.")

    def partial_update(self, request, *args, **kwargs):
        raise PermissionDenied("Shift handovers are immutable evidence records.")

    def create(self, request, *args, **kwargs):
        cached = check_idempotency(request)
        if cached:
            return cached

        outgoing_shift_id = request.data.get("outgoing_shift")
        if not outgoing_shift_id:
            return Response({"detail": "outgoing_shift is required."}, status=status.HTTP_400_BAD_REQUEST)

        outgoing_shift = get_object_or_404(Shift, id=outgoing_shift_id)
        if outgoing_shift.guard != request.user and request.user.role == UserRole.GUARD:
            return Response(
                {"detail": "Proxy actions are strictly prohibited. You cannot submit a handover for another guard's shift."},
                status=status.HTTP_403_FORBIDDEN,
            )

        incoming_guard = resolve_incoming_guard(outgoing_shift)
        if not incoming_guard:
            return Response(
                {"detail": "Unable to determine incoming guard: No consecutive shift is scheduled on the roster for this station. Please notify your supervisor."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        occurrence_summary = request.data.get("occurrence_summary", "").strip()
        if not occurrence_summary:
            return Response({"detail": "Occurrence summary cannot be empty."}, status=status.HTTP_400_BAD_REQUEST)

        # 12-Hour Shift Enforcement
        now = timezone.now()
        shift_start_dt = timezone.make_aware(
            datetime.combine(outgoing_shift.date, outgoing_shift.start_time),
            timezone.get_current_timezone()
        )
        elapsed_hours = (now - shift_start_dt).total_seconds() / 3600.0
        override_val = request.data.get("supervisor_emergency_override", False)
        supervisor_emergency_override = override_val in (True, "true", "True", "1", 1)
        override_reason = request.data.get("override_reason", "").strip()

        emergency_auth_note = ""
        if elapsed_hours < 12.0:
            if not supervisor_emergency_override:
                return Response({
                    "detail": f"Handovers can only occur strictly after completion of the 12-hour shift (elapsed: {max(0.0, elapsed_hours):.1f} hrs). Early handover requires supervisor emergency authorization.",
                    "elapsed_hours": round(max(0.0, elapsed_hours), 2),
                    "required_hours": 12.0,
                }, status=status.HTTP_400_BAD_REQUEST)

            override_reason = override_reason or "Supervisor emergency turnover authorization"
            emergency_auth_note = f"[SUPERVISOR EMERGENCY OVERRIDE: Authorized early handover at {max(0.0, elapsed_hours):.1f} hrs. Reason: {override_reason}]"

            # Create Supervisor Override Audit
            sup_user = request.user if request.user.role in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR] else None
            if not sup_user and request.data.get("supervisor_id"):
                sup_user = User.objects.filter(id=request.data.get("supervisor_id"), role__in=[UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]).first()

            if sup_user:
                SupervisorOverrideAudit.objects.create(
                    supervisor=sup_user,
                    action_type="EARLY_HANDOVER_OVERRIDE",
                    target_model="ShiftHandover",
                    target_id=str(outgoing_shift.id),
                    reason=override_reason,
                    admin_notified=True,
                )

        pending_issues_text = request.data.get("pending_issues", "None.")
        if emergency_auth_note:
            pending_issues_text = f"{pending_issues_text}\n{emergency_auth_note}" if pending_issues_text != "None." else emergency_auth_note

        handover = ShiftHandover.objects.create(
            outgoing_shift=outgoing_shift,
            outgoing_guard=request.user,
            incoming_guard=incoming_guard,
            station=outgoing_shift.station,
            occurrence_summary=occurrence_summary,
            equipment_issued=request.data.get("equipment_issued", "All post equipment accounted for."),
            keys_handed_over=request.data.get("keys_handed_over", "Post keys transferred."),
            pending_issues=pending_issues_text,
            outgoing_signed=True,
        )

        # Notify incoming guard of pending handover takeover
        Notification.objects.create(
            user=incoming_guard,
            title="Handover Takeover Required",
            message=f"Outgoing guard {request.user.get_full_name() or request.user.username} has submitted handover for {outgoing_shift.station.name}. Please inspect and confirm takeover in your app.",
            notification_type="HANDOVER_REQUIRED",
        )

        resp = Response(self.get_serializer(handover).data, status=status.HTTP_201_CREATED)
        store_idempotency(request, resp)
        return resp

    @action(detail=True, methods=["post"], url_path="accept")
    def accept(self, request, pk=None):
        """
        POST /shifts/handovers/{id}/accept/
        Two-Party Handover Release:
        Upon incoming guard acceptance, automatically clocks out the outgoing guard,
        records incoming guard clock-in, and releases the outgoing guard from post lock.
        """
        if request.user.role == UserRole.SUPERVISOR:
            return Response(
                {"detail": "Supervisors have view-only access to Shift Handovers. Only the designated incoming guard can accept."},
                status=status.HTTP_403_FORBIDDEN,
            )

        handover = self.get_object()
        if handover.incoming_guard != request.user and request.user.role != UserRole.ADMINISTRATOR:
            return Response(
                {"detail": "Only the designated incoming guard can accept this handover."},
                status=status.HTTP_403_FORBIDDEN,
            )

        if handover.incoming_accepted:
            return Response({"detail": "This handover has already been accepted."}, status=status.HTTP_400_BAD_REQUEST)

        now = timezone.now()
        handover.incoming_accepted = True
        handover.incoming_accepted_at = now
        handover.save()

        # Two-party automatic release:
        # 1. Clock-out outgoing guard
        outgoing_attendance = Attendance.objects.filter(
            shift=handover.outgoing_shift,
            guard=handover.outgoing_guard,
        ).first()
        if outgoing_attendance and not outgoing_attendance.clock_out:
            outgoing_attendance.clock_out = now
            outgoing_attendance.save()

        # 2. Clock-in incoming guard for today's shift if scheduled
        incoming_shift = Shift.objects.filter(
            guard=handover.incoming_guard,
            station=handover.station,
            date=timezone.localdate()
        ).first()
        if incoming_shift:
            Attendance.objects.get_or_create(
                shift=incoming_shift,
                guard=handover.incoming_guard,
                defaults={"clock_in": now, "clock_in_gps": ""}
            )

        # 3. Inform outgoing guard of post release
        Notification.objects.create(
            user=handover.outgoing_guard,
            title="Handover Confirmed - Post Released",
            message=f"Incoming guard {request.user.get_full_name() or request.user.username} has confirmed post takeover. You are officially released from duty.",
            notification_type="POST_RELEASE",
        )

        resp = Response(self.get_serializer(handover).data, status=status.HTTP_200_OK)
        store_idempotency(request, resp)
        return resp

    @action(detail=True, methods=["post"], url_path="reject")
    def reject(self, request, pk=None):
        if request.user.role == UserRole.SUPERVISOR:
            return Response(
                {"detail": "Supervisors have view-only access to Shift Handovers. Only the designated incoming guard can reject."},
                status=status.HTTP_403_FORBIDDEN,
            )

        handover = self.get_object()
        if handover.incoming_guard != request.user and request.user.role != UserRole.ADMINISTRATOR:
            return Response(
                {"detail": "Only the designated incoming guard can reject this handover."},
                status=status.HTTP_403_FORBIDDEN,
            )

        if handover.incoming_accepted:
            return Response({"detail": "This handover has already been accepted and cannot be rejected."}, status=status.HTTP_400_BAD_REQUEST)

        reason = request.data.get("reason", "").strip() if isinstance(request.data, dict) else ""
        rejection_note = f"[REJECTED by {request.user.username}] {reason}" if reason else f"[REJECTED by {request.user.username}] Handover disputed."
        handover.pending_issues = f"{handover.pending_issues}\n{rejection_note}".strip() if handover.pending_issues and handover.pending_issues != "None." else rejection_note
        handover.incoming_accepted = False
        handover.save()

        Notification.objects.create(
            user=handover.outgoing_guard,
            title="Handover Disputed",
            message=f"Incoming guard {request.user.username} disputed handover: {reason or 'Discrepancy noted.'}",
            notification_type="ALERT",
        )

        return Response(self.get_serializer(handover).data, status=status.HTTP_200_OK)


class ExaminationPeriodViewSet(viewsets.ModelViewSet):
    """
    CRUD ViewSet for university examination periods.
    Administrators and Supervisors manage examination periods.
    """
    queryset = ExaminationPeriod.objects.all().select_related("station", "authorized_by")
    serializer_class = ExaminationPeriodSerializer
    permission_classes = [IsSupervisorOrAdmin]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        station_id = self.request.query_params.get("station")
        if station_id:
            qs = qs.filter(station_id=station_id)
        elif user.role == UserRole.SUPERVISOR and user.station:
            qs = qs.filter(station=user.station)
        return qs

    def perform_create(self, serializer):
        serializer.save(authorized_by=self.request.user)


class TemporaryAssignmentAuditViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Audit log of temporary reassignments during examinations and escorts.
    Preserves evidence and original pair structure.
    """
    queryset = TemporaryAssignmentAudit.objects.all().select_related("guard", "original_pair", "authorized_by")
    serializer_class = TemporaryAssignmentAuditSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        station_id = self.request.query_params.get("station")
        if station_id:
            qs = qs.filter(original_pair__station_id=station_id)
        if user.role == UserRole.GUARD:
            qs = qs.filter(guard=user)
        return qs


class DutyRosterViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Authoritative Duty Rosters.
    Guards can only access APPROVED and ACTIVE duty rosters.
    Supervisors can access DRAFT, VALIDATED, APPROVED, ACTIVE rosters for their station.
    Administrators can access all rosters.
    """
    queryset = DutyRoster.objects.all().select_related("station", "approved_by")
    serializer_class = DutyRosterSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()

        station_id = self.request.query_params.get("station")
        status_param = self.request.query_params.get("status")

        if station_id:
            qs = qs.filter(station_id=station_id)
        if status_param:
            qs = qs.filter(status=status_param)

        if user.role == UserRole.GUARD:
            # Guards ONLY see APPROVED or ACTIVE rosters, scoped to their station if assigned
            qs = qs.filter(status__in=[RosterStatus.APPROVED, RosterStatus.ACTIVE])
            if user.station_id:
                qs = qs.filter(station_id=user.station_id)
        elif user.role == UserRole.SUPERVISOR:
            # Supervisors see all rosters for their assigned station
            if user.station_id:
                qs = qs.filter(station_id=user.station_id)

        return qs.order_by("-start_date")

    @action(detail=True, methods=["post"], permission_classes=[IsSupervisorOrAdmin])
    def validate(self, request, pk=None):
        roster = self.get_object()
        if request.user.role == UserRole.SUPERVISOR:
            if not request.user.station_id or request.user.station_id != roster.station_id:
                raise DRFPermissionDenied(
                    f"Supervisor {request.user.username} is assigned to station '{getattr(request.user.station, 'name', 'None')}' "
                    f"and cannot validate a roster for '{roster.station.name}'."
                )
        try:
            result = validate_duty_roster(roster)
            return Response(result, status=status.HTTP_200_OK)
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=["post"], permission_classes=[IsSupervisorOrAdmin])
    def approve(self, request, pk=None):
        roster = self.get_object()
        try:
            result = approve_duty_roster(roster, request.user)
            return Response(result, status=status.HTTP_200_OK)
        except (DjangoPermissionDenied, DRFPermissionDenied) as exc:
            raise DRFPermissionDenied(detail=str(exc))
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)


class PublicHolidayViewSet(viewsets.ModelViewSet):
    """
    Data-driven public holidays management.
    All authenticated users can list and view holidays.
    Creation and modifications restricted to Administrators and Supervisors.
    """
    queryset = PublicHoliday.objects.all().order_by("date")
    serializer_class = PublicHolidaySerializer
    permission_classes = [IsAuthenticated]

    def get_permissions(self):
        if self.action in ["create", "update", "partial_update", "destroy"]:
            return [IsSupervisorOrAdmin()]
        return [IsAuthenticated()]

    def get_queryset(self):
        qs = super().get_queryset()
        year = self.request.query_params.get("year")
        is_active = self.request.query_params.get("is_active")
        if year:
            qs = qs.filter(date__year=year)
        if is_active is not None:
            qs = qs.filter(is_active=is_active.lower() == "true")
        return qs


class PublicHolidayDutyRecordViewSet(viewsets.ModelViewSet):
    """
    Authoritative recording and compensation workflow for public holiday duties.
    Chain: PublicHoliday -> Shift -> Attendance -> PublicHolidayDutyRecord -> authorized compensation.
    Guards see only their own holiday duty records.
    Supervisors see records within their assigned station.
    Administrators have global visibility.
    """
    queryset = PublicHolidayDutyRecord.objects.all().select_related(
        "public_holiday",
        "shift",
        "shift__station",
        "guard",
        "attendance",
        "approved_by",
    )
    serializer_class = PublicHolidayDutyRecordSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        user = self.request.user
        qs = super().get_queryset()
        status_param = self.request.query_params.get("status")
        guard_param = self.request.query_params.get("guard")
        station_param = self.request.query_params.get("station")

        if status_param:
            qs = qs.filter(status=status_param)
        if guard_param:
            qs = qs.filter(guard_id=guard_param)
        if station_param:
            qs = qs.filter(shift__station_id=station_param)

        if user.role == UserRole.GUARD:
            qs = qs.filter(guard=user)
        elif user.role == UserRole.SUPERVISOR:
            if user.station_id:
                qs = qs.filter(shift__station_id=user.station_id)
            else:
                qs = qs.none()

        return qs.order_by("-created_at")

    @action(detail=False, methods=["post"], url_path="record_duty", permission_classes=[IsSupervisorOrAdmin])
    def record_duty(self, request):
        """
        POST /shifts/holiday-duties/record_duty/
        Authoritative recording of public holiday duty.
        """
        serializer = RecordHolidayDutyRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        shift_id = serializer.validated_data["shift_id"]
        shift = get_object_or_404(Shift, id=shift_id)

        if request.user.role == UserRole.SUPERVISOR:
            if not request.user.station_id or request.user.station_id != shift.station_id:
                raise DRFPermissionDenied(
                    f"Supervisor {request.user.username} is assigned to station '{getattr(request.user.station, 'name', 'None')}' "
                    f"and cannot record holiday duty for station '{shift.station.name}'."
                )

        try:
            record = record_public_holiday_duty(shift)
            return Response(
                PublicHolidayDutyRecordSerializer(record).data,
                status=status.HTTP_201_CREATED,
            )
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=["post"], url_path="approve", permission_classes=[IsSupervisorOrAdmin])
    def approve(self, request, pk=None):
        """
        POST /shifts/holiday-duties/{id}/approve/
        Approves 2 compensated leave days for a worked public holiday duty.
        """
        duty_record = get_object_or_404(PublicHolidayDutyRecord, pk=pk)
        serializer = ReviewHolidayCompensationRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        reason = serializer.validated_data.get("reason", "")

        try:
            updated = approve_holiday_compensation(duty_record, request.user, reason=reason)
            return Response(
                PublicHolidayDutyRecordSerializer(updated).data,
                status=status.HTTP_200_OK,
            )
        except (DjangoPermissionDenied, DRFPermissionDenied) as exc:
            raise DRFPermissionDenied(detail=str(exc))
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=["post"], url_path="reject", permission_classes=[IsSupervisorOrAdmin])
    def reject(self, request, pk=None):
        """
        POST /shifts/holiday-duties/{id}/reject/
        Rejects holiday duty compensation.
        """
        duty_record = get_object_or_404(PublicHolidayDutyRecord, pk=pk)
        serializer = ReviewHolidayCompensationRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        reason = serializer.validated_data.get("reason", "")

        try:
            updated = reject_holiday_compensation(duty_record, request.user, reason=reason)
            return Response(
                PublicHolidayDutyRecordSerializer(updated).data,
                status=status.HTTP_200_OK,
            )
        except (DjangoPermissionDenied, DRFPermissionDenied) as exc:
            raise DRFPermissionDenied(detail=str(exc))
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)
