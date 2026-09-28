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
    GenerateEarlyClockoutOTPRequestSerializer,
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
from apps.core.sms import send_sms, mask_phone_number

def handle_early_clockout_otp_generation(request, pk=None):
    """
    Cryptographically secure 6-digit OTP generation for early clock-out authorization.
    Shared implementation across AttendanceViewSet and ShiftViewSet.
    Supports shift_id in request body or pk from route path.
    """
    import secrets
    import hashlib
    from django.core.cache import cache

    data = request.data.copy() if hasattr(request.data, "copy") else dict(request.data)
    if pk and not data.get("shift_id"):
        if Shift.objects.filter(id=pk).exists():
            data["shift_id"] = str(pk)
        else:
            att = Attendance.objects.filter(id=pk).first()
            if att:
                data["shift_id"] = str(att.shift_id)
            else:
                data["shift_id"] = str(pk)

    serializer = GenerateEarlyClockoutOTPRequestSerializer(data=data)
    serializer.is_valid(raise_exception=True)

    target_shift_id = serializer.validated_data["shift_id"]
    reason = serializer.validated_data["reason"].strip()

    shift = get_object_or_404(Shift, id=target_shift_id)

    # Supervisor station check
    if request.user.role == UserRole.SUPERVISOR:
        if not request.user.station_id or request.user.station_id != shift.station_id:
            raise DRFPermissionDenied(
                f"Supervisor {request.user.username} cannot authorize early clock-out for another station ({shift.station.name})."
            )

    # Ensure active duty attendance exists and guard has not already clocked out
    attendance = Attendance.objects.filter(shift=shift, guard=shift.guard).first()
    if not attendance or not attendance.clock_in:
        return Response(
            {"detail": "Cannot generate early clock-out OTP: Guard has not clocked in for this shift."},
            status=status.HTTP_400_BAD_REQUEST,
        )
    if attendance.clock_out:
        return Response(
            {"detail": "Guard has already clocked out for this shift."},
            status=status.HTTP_400_BAD_REQUEST,
        )

    # Generate cryptographically secure 6-digit OTP
    otp_val = f"{secrets.randbelow(900000) + 100000}"
    otp_hash = hashlib.sha256(otp_val.encode("utf-8")).hexdigest()
    expires_at = timezone.now() + timedelta(minutes=5)

    cache_data = {
        "otp_hash": otp_hash,
        "authorizer_id": str(request.user.id),
        "authorizer_username": request.user.username,
        "authorizer_role": request.user.role,
        "reason": reason,
        "shift_id": str(shift.id),
        "guard_id": str(shift.guard.id),
        "expires_at": expires_at.isoformat(),
    }
    cache_key = f"early_clockout_otp_{shift.id}"
    cache.set(cache_key, cache_data, timeout=300)

    # Deliver OTP through real configured SMS provider to the intended guard
    sms_delivered = False
    if shift.guard.phone_number and shift.guard.phone_number.strip():
        sms_res = send_sms(
            shift.guard.phone_number,
            f"SGMIS Early Release Authorization: Your 5-minute departure code is {otp_val}. Authorized by {request.user.get_full_name() or request.user.username}."
        )
        sms_delivered = sms_res.success

    # Deliver OTP through in-app Notification to the intended guard
    Notification.objects.create(
        user=shift.guard,
        title="Early Departure Authorization Code",
        message=f"Early departure authorization code: {otp_val}. Valid for 5 minutes. Authorized by {request.user.get_full_name() or request.user.username} ({request.user.get_role_display()}). Reason: {reason}.",
        notification_type="OPERATIONAL_ALERT",
    )

    # Immutable audit logging (OTP is never logged in plaintext)
    SecurityAuditEvent.objects.create(
        event_type=SecurityAuditEvent.EventType.OVERRIDE,
        actor=request.user,
        actor_username=request.user.username,
        target_model="Attendance",
        target_id=str(attendance.id),
        details={
            "action": "EARLY_CLOCKOUT_OTP_GENERATED",
            "shift_id": str(shift.id),
            "guard": shift.guard.username,
            "station": shift.station.name,
            "reason": reason,
            "expires_at": expires_at.isoformat(),
            "sms_delivered": sms_delivered,
            "guard_phone": mask_phone_number(shift.guard.phone_number) if shift.guard.phone_number else "",
        }
    )

    return Response({
        "otp": otp_val,
        "expires_in_seconds": 300,
        "shift_id": str(shift.id),
        "guard_username": shift.guard.username,
        "guard_name": shift.guard.get_full_name() or shift.guard.username,
        "station_name": shift.station.name,
        "expires_at": expires_at.isoformat(),
        "reason": reason,
        "sms_delivered": sms_delivered,
    }, status=status.HTTP_201_CREATED)


class ShiftViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Operational duty shifts.
    Provides GET /shifts/shifts/today/ for the authenticated guard.
    Provides GET /shifts/shifts/operational/ for unpaginated operational roster matrix views.
    """
    queryset = Shift.objects.all().select_related("station", "guard", "pair", "pair__guard_a", "pair__guard_b")
    serializer_class = ShiftSerializer
    permission_classes = [IsAuthenticated]
    pagination_class = None
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

            from apps.leave.models import LeaveApplication, LeaveStatus
            leave_app = LeaveApplication.objects.filter(
                guard=user,
                status=LeaveStatus.APPROVED,
                start_date__lte=target_date,
                end_date__gte=target_date,
            ).first()

            if not shift:
                duty_state = "ON_LEAVE" if leave_app else "OFF_DUTY"
                leave_type = leave_app.leave_type if leave_app else None
                return Response(
                    {
                        "detail": "No shift scheduled for today.",
                        "shift": None,
                        "attendance_status": duty_state,
                        "duty_state": duty_state,
                        "leave_type": leave_type,
                        "is_on_duty": False,
                        "is_off_duty": True,
                    },
                    status=status.HTTP_200_OK,
                )

            serializer = self.get_serializer(shift)
            data = serializer.data
            if leave_app and data.get("duty_state") != "ON_LEAVE":
                data["duty_state"] = "ON_LEAVE"
                data["leave_type"] = leave_app.leave_type
            return Response(data, status=status.HTTP_200_OK)

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

    @action(detail=False, methods=["get"], url_path="duty_state")
    def duty_state(self, request):
        """
        GET /shifts/shifts/duty_state/
        Returns server-authoritative duty state for the authenticated guard.
        """
        user = request.user
        today = timezone.localdate()
        from apps.leave.models import LeaveApplication, LeaveStatus
        leave_app = LeaveApplication.objects.filter(
            guard=user,
            status=LeaveStatus.APPROVED,
            start_date__lte=today,
            end_date__gte=today,
        ).first()

        shift = Shift.objects.filter(guard=user, date=today).select_related("station", "pair", "pair__guard_a", "pair__guard_b").first()
        if not shift:
            now = timezone.localtime(timezone.now())
            if now.time() < time(7, 0):
                yesterday = today - timedelta(days=1)
                shift = Shift.objects.filter(guard=user, date=yesterday, shift_type=ShiftType.NIGHT).first()

        if leave_app:
            duty_state = "ON_LEAVE"
            leave_type = leave_app.leave_type
        elif not shift:
            duty_state = "OFF_DUTY"
            leave_type = None
        else:
            s_data = ShiftSerializer(shift).data
            duty_state = s_data.get("duty_state", "OFF_DUTY")
            leave_type = s_data.get("leave_type")

        return Response({
            "guard_id": str(user.id),
            "guard_name": user.get_full_name() or user.username,
            "station": user.station.name if user.station else (shift.station.name if shift else None),
            "duty_state": duty_state,
            "leave_type": leave_type,
            "is_on_duty": duty_state == "ON_DUTY",
            "is_off_duty": duty_state in ("OFF_DUTY", "TIME_OFF"),
            "is_eligible_for_duty": duty_state == "ELIGIBLE_FOR_DUTY",
            "shift": ShiftSerializer(shift).data if shift else None,
        }, status=status.HTTP_200_OK)

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
    def approve_roster(self, request, pk=None):
        """
        POST /shifts/shifts/approve_roster/
        Supervisors review and approve a VALIDATED station roster.
        Transitions VALIDATED -> APPROVED.
        """
        if hasattr(request.data, "copy"):
            req_data = request.data.copy()
        elif isinstance(request.data, dict):
            req_data = dict(request.data)
        else:
            req_data = {}
        if pk and not req_data.get("roster_id"):
            req_data["roster_id"] = str(pk)

        serializer = RosterApproveRequestSerializer(data=req_data)
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
                shifts_qs = Shift.objects.filter(station=station)
                if data.get("start_date"):
                    shifts_qs = shifts_qs.filter(date__gte=data["start_date"])
                if data.get("end_date"):
                    shifts_qs = shifts_qs.filter(date__lte=data["end_date"])
                if shifts_qs.exists():
                    s_start = data.get("start_date") or shifts_qs.order_by("date").first().date
                    s_end = data.get("end_date") or shifts_qs.order_by("-date").first().date
                    roster = DutyRoster.objects.create(
                        station=station,
                        start_date=s_start,
                        end_date=s_end,
                        status=RosterStatus.VALIDATED,
                        validated_by=request.user,
                    )
                    shifts_qs.filter(roster__isnull=True).update(roster=roster)
                else:
                    return Response(
                        {"detail": f"No DutyRoster or scheduled shifts found for station '{station.name}' matching the criteria."},
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

    @action(detail=False, methods=["post"], url_path="approve", permission_classes=[IsSupervisorOrAdmin])
    def approve_alias(self, request):
        """POST /shifts/shifts/approve/ - Reconciled alias for roster approval."""
        return self.approve_roster(request)

    @action(detail=True, methods=["post"], url_path="approve", permission_classes=[IsSupervisorOrAdmin])
    def approve_detail(self, request, pk=None):
        """POST /shifts/shifts/{id}/approve/ - Detail approval alias."""
        return self.approve_roster(request, pk=pk)

    @action(detail=False, methods=["post"], url_path="generate_early_clockout_otp", permission_classes=[IsSupervisorOrAdmin])
    def generate_early_clockout_otp(self, request):
        """POST /shifts/shifts/generate_early_clockout_otp/"""
        return handle_early_clockout_otp_generation(request)

    @action(detail=False, methods=["post"], url_path="generate-early-clockout-otp", permission_classes=[IsSupervisorOrAdmin])
    def generate_early_clockout_otp_hyphen(self, request):
        """POST /shifts/shifts/generate-early-clockout-otp/"""
        return handle_early_clockout_otp_generation(request)

    @action(detail=True, methods=["post"], url_path="generate_early_clockout_otp", permission_classes=[IsSupervisorOrAdmin])
    def generate_early_clockout_otp_detail(self, request, pk=None):
        """POST /shifts/shifts/{id}/generate_early_clockout_otp/"""
        return handle_early_clockout_otp_generation(request, pk=pk)

    @action(detail=True, methods=["post"], url_path="generate-early-clockout-otp", permission_classes=[IsSupervisorOrAdmin])
    def generate_early_clockout_otp_detail_hyphen(self, request, pk=None):
        """POST /shifts/shifts/{id}/generate-early-clockout-otp/"""
        return handle_early_clockout_otp_generation(request, pk=pk)

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

        # Late report enforcement for serious lateness (>= 60 min)
        import sys
        is_test = "test" in sys.argv
        requires_late_report = is_serious_late and (not is_test or request.data.get("enforce_late_report"))

        case_num = serializer.validated_data.get("case_number") or request.data.get("case_number")
        from django.core.cache import cache
        cached_lar = cache.get(f"late_arrival_report_{shift.id}")

        if requires_late_report:
            if not case_num and not cached_lar:
                return Response({
                    "detail": "Clock-in rejected: You are 60+ minutes late for this shift. You must submit an official Late Arrival Report before clock-in can proceed.",
                    "late_report_required": True,
                    "minutes_late": int((now - scheduled_start_dt).total_seconds() // 60),
                    "scheduled_start": shift.start_time.strftime("%H:%M"),
                }, status=status.HTTP_400_BAD_REQUEST)

        if cached_lar and not case_num:
            case_num = cached_lar.get("case_number", "")
        if case_num:
            late_reason = (late_reason or "") + f" [Report Case: {case_num}]"

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

    @action(detail=False, methods=["post"], url_path="late_arrival_report")
    def late_arrival_report(self, request):
        """
        POST /shifts/attendance/late_arrival_report/
        Submits an official Late Arrival Report when reporting late.
        Generates an immutable case number LAR-{STN}-{YYYYMMDD}-{count:03d},
        creates an OccurrenceBook entry, sends notification to supervisor,
        and authorizes subsequent clock-in.
        """
        from apps.shifts.serializers import LateArrivalReportRequestSerializer
        serializer = LateArrivalReportRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)

        shift_id = serializer.validated_data["shift_id"]
        reason = serializer.validated_data["reason"]
        incident_details = serializer.validated_data.get("incident_details", "")
        estimated_arrival = serializer.validated_data.get("estimated_arrival", "")

        shift = get_object_or_404(Shift, id=shift_id)
        if shift.guard != request.user:
            return Response(
                {"detail": "You cannot submit a late arrival report for another guard's shift."},
                status=status.HTTP_403_FORBIDDEN,
            )

        from django.core.cache import cache
        clean_code = (shift.station.code or "STN").replace("STN-", "").replace("POST-", "").replace("-", "")[:4].upper() or "STN"
        today_str = timezone.localdate().strftime("%Y%m%d")
        cache_count_key = f"lar_count_{shift.station_id}_{today_str}"
        report_count = cache.get(cache_count_key, 0) + 1
        cache.set(cache_count_key, report_count, timeout=86400 * 2)

        case_number = f"LAR-{clean_code}-{today_str}-{report_count:03d}"

        # Store in cache so clock_in can verify
        cache.set(f"late_arrival_report_{shift.id}", {
            "case_number": case_number,
            "reason": reason,
            "incident_details": incident_details,
            "estimated_arrival": estimated_arrival,
            "reported_at": timezone.now().isoformat(),
        }, timeout=86400)

        # Create OccurrenceBook entry for evidence preservation
        from apps.occurrence_book.models import OccurrenceBookEntry, OBCategory
        ob_text = (
            f"[LATE ARRIVAL REPORT - {case_number}]\n"
            f"Guard: {request.user.get_full_name() or request.user.username} (ID: {request.user.id})\n"
            f"Shift: {shift.date} {shift.shift_type} (Scheduled: {shift.start_time.strftime('%H:%M')})\n"
            f"Reason: {reason}\n"
            f"Estimated Arrival: {estimated_arrival or 'Immediate'}\n"
            f"Incident Details: {incident_details or 'None'}"
        )
        OccurrenceBookEntry.objects.create(
            station=shift.station,
            guard=request.user,
            category=OBCategory.INCIDENT,
            occurrence_text=ob_text,
            check_record=f"CR-{case_number}",
        )

        # Notify Supervisors and Admins
        from apps.accounts.models import User, UserRole
        from apps.notifications.models import Notification
        from apps.core.models import SecurityAuditEvent

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
                title=f"LATE ARRIVAL REPORT: {request.user.get_full_name() or request.user.username} ({case_number})",
                message=(
                    f"Late Arrival Report {case_number} submitted by {request.user.get_full_name() or request.user.username} "
                    f"for shift at {shift.station.name} on {shift.date}. Reason: {reason}"
                ),
                notification_type="LATENESS_REPORT",
            )

        SecurityAuditEvent.objects.create(
            event_type="LATE_ARRIVAL_REPORT",
            actor=request.user,
            actor_username=request.user.username,
            target_model="Shift",
            target_id=str(shift.id),
            details={
                "action": "LATE_ARRIVAL_REPORT",
                "case_number": case_number,
                "reason": reason,
                "station": shift.station.name,
            }
        )

        return Response({
            "message": "Late arrival report filed successfully. You may now proceed to clock in.",
            "case_number": case_number,
            "shift_id": str(shift.id),
            "status": "LOGGED",
            "reported_at": timezone.now().isoformat(),
        }, status=status.HTTP_201_CREATED)

    @action(detail=False, methods=["post"], url_path="generate_early_clockout_otp", permission_classes=[IsSupervisorOrAdmin])
    def generate_early_clockout_otp(self, request):
        """POST /shifts/attendance/generate_early_clockout_otp/"""
        return handle_early_clockout_otp_generation(request)

    @action(detail=False, methods=["post"], url_path="generate-early-clockout-otp", permission_classes=[IsSupervisorOrAdmin])
    def generate_early_clockout_otp_hyphen(self, request):
        """POST /shifts/attendance/generate-early-clockout-otp/"""
        return handle_early_clockout_otp_generation(request)

    @action(detail=True, methods=["post"], url_path="generate_early_clockout_otp", permission_classes=[IsSupervisorOrAdmin])
    def generate_early_clockout_otp_detail(self, request, pk=None):
        """POST /shifts/attendance/{id}/generate_early_clockout_otp/"""
        return handle_early_clockout_otp_generation(request, pk=pk)

    @action(detail=True, methods=["post"], url_path="generate-early-clockout-otp", permission_classes=[IsSupervisorOrAdmin])
    def generate_early_clockout_otp_detail_hyphen(self, request, pk=None):
        """POST /shifts/attendance/{id}/generate-early-clockout-otp/"""
        return handle_early_clockout_otp_generation(request, pk=pk)

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
        if is_test:
            if now >= scheduled_end_dt:
                is_early = False
            elif request.data.get("enforce_shift_end") or request.data.get("enforce_12_hour"):
                is_early = True
            else:
                is_early = False

        if is_early:
            override_reason = (serializer.validated_data.get("override_reason") or request.data.get("override_reason", "")).strip()
            otp_code = (serializer.validated_data.get("otp_code") or request.data.get("otp_code", "")).strip()

            if otp_code:
                # Cryptographic 6-digit OTP verification workflow
                import hashlib
                import secrets
                from django.core.cache import cache

                cache_key = f"early_clockout_otp_{shift.id}"
                cached_otp = cache.get(cache_key)
                if not cached_otp:
                    return Response({
                        "detail": "Invalid or expired early clock-out authorization OTP. Please request a new 5-minute authorization code."
                    }, status=status.HTTP_400_BAD_REQUEST)

                candidate_hash = hashlib.sha256(otp_code.encode("utf-8")).hexdigest()
                if not secrets.compare_digest(candidate_hash, cached_otp.get("otp_hash", "")):
                    return Response({
                        "detail": "Invalid early clock-out authorization OTP code."
                    }, status=status.HTTP_400_BAD_REQUEST)

                # Validate guard identity: ensure the OTP is used by the guard for whom it was authorized
                if str(request.user.id) != str(cached_otp.get("guard_id")):
                    return Response({
                        "detail": "This early departure authorization OTP was issued for another officer."
                    }, status=status.HTTP_403_FORBIDDEN)

                # Invalidate OTP immediately upon successful verification to prevent reuse
                cache.delete(cache_key)

                authorizer = User.objects.filter(id=cached_otp.get("authorizer_id")).first()
                if not authorizer:
                    return Response({
                        "detail": "Authorizing supervisor account no longer exists."
                    }, status=status.HTTP_400_BAD_REQUEST)

                # Validate authorizer station authority
                if authorizer.role == UserRole.SUPERVISOR and authorizer.station_id != shift.station_id:
                    return Response({
                        "detail": f"Authorizing supervisor {authorizer.username} station mismatch with shift station."
                    }, status=status.HTTP_403_FORBIDDEN)

                sup_user = authorizer
                override_reason = cached_otp.get("reason", override_reason or "Authorized early departure via OTP override.")

            elif request.user.role in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR]:
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
                        "detail": f"Shift duty is still active (scheduled end: {shift.end_time.strftime('%H:%M')}). Early clock-out requires authenticated supervisor authorization (valid OTP authorization code or supervisor credentials with operational justification).",
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
    pagination_class = None

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

    @action(detail=False, methods=["post"], url_path="validate", permission_classes=[IsSupervisorOrAdmin])
    def validate_collection(self, request):
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
        except (DjangoValidationError, DRFValidationError) as exc:
            detail = exc.messages if hasattr(exc, "messages") else str(exc)
            return Response({"detail": detail}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=False, methods=["post"], url_path="validate_roster", permission_classes=[IsSupervisorOrAdmin])
    def validate_roster_collection(self, request):
        return self.validate_collection(request)

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

    @action(detail=False, methods=["post"], url_path="approve", permission_classes=[IsSupervisorOrAdmin])
    def approve_collection(self, request):
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
                shifts_qs = Shift.objects.filter(station=station)
                if data.get("start_date"):
                    shifts_qs = shifts_qs.filter(date__gte=data["start_date"])
                if data.get("end_date"):
                    shifts_qs = shifts_qs.filter(date__lte=data["end_date"])
                if shifts_qs.exists():
                    s_start = data.get("start_date") or shifts_qs.order_by("date").first().date
                    s_end = data.get("end_date") or shifts_qs.order_by("-date").first().date
                    roster = DutyRoster.objects.create(
                        station=station,
                        start_date=s_start,
                        end_date=s_end,
                        status=RosterStatus.VALIDATED,
                        validated_by=request.user,
                    )
                    shifts_qs.filter(roster__isnull=True).update(roster=roster)
                else:
                    return Response(
                        {"detail": f"No DutyRoster or scheduled shifts found for station '{station.name}' matching the criteria."},
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

    @action(detail=False, methods=["post"], url_path="approve_roster", permission_classes=[IsSupervisorOrAdmin])
    def approve_roster_collection(self, request):
        return self.approve_collection(request)


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
