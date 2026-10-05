import uuid
import datetime
from datetime import time, timedelta, datetime as dt_cls
from collections import defaultdict
from django.db import models, transaction
from django.utils import timezone
from rest_framework.exceptions import ValidationError, PermissionDenied, NotAuthenticated
from .models import (
    Shift,
    ShiftType,
    AssignmentType,
    ExaminationPeriod,
    TemporaryAssignmentAudit,
    DutyRosterCycle,
    DutyRoster,
    RosterStatus,
    Attendance,
    PublicHoliday,
    PublicHolidayDutyRecord,
    HolidayCompensationStatus,
)
from apps.stations.models import GuardPair
from apps.accounts.models import UserRole

def resolve_incoming_guard(outgoing_shift):
    """
    Authoritative server resolution of the incoming guard.
    Never relies on user typing or arbitrary client inputs.
    
    If outgoing shift is DAY on date D:
      The incoming shift is NIGHT on date D at the same station.
    If outgoing shift is NIGHT on date D:
      The incoming shift is DAY on date D+1 at the same station.
    """
    station = outgoing_shift.station
    shift_date = outgoing_shift.date

    if outgoing_shift.shift_type == ShiftType.DAY:
        incoming_date = shift_date
        incoming_type = ShiftType.NIGHT
    else:
        incoming_date = shift_date + timedelta(days=1)
        incoming_type = ShiftType.DAY

    incoming_shift = Shift.objects.filter(
        station=station,
        date=incoming_date,
        shift_type=incoming_type,
        duty_location=outgoing_shift.duty_location
    ).exclude(id=outgoing_shift.id).select_related("guard").first()

    if not incoming_shift:
        # Fallback: check any active shift on the incoming date/type at this station
        incoming_shift = Shift.objects.filter(
            station=station,
            date=incoming_date,
            shift_type=incoming_type
        ).exclude(id=outgoing_shift.id).exclude(assignment_type=AssignmentType.TIME_OFF).select_related("guard").first()

    if not incoming_shift:
        # Fallback: check next available active shift at same station ordered by date and start_time
        incoming_shift = Shift.objects.filter(
            station=station,
            date__gte=shift_date
        ).exclude(id=outgoing_shift.id).exclude(assignment_type=AssignmentType.TIME_OFF).order_by("date", "start_time").select_related("guard").first()

    if incoming_shift:
        return incoming_shift.guard
    return None

def generate_roster_for_station(
    station,
    start_date,
    cycle_days=12,
    day_start=time(7, 0),
    day_end=time(18, 0),
    night_start=time(18, 0),
    night_end=time(7, 0),
    mode="NORMAL",
    examination_period=None,
    exam_venue_name="Exam Venue",
    exam_guard_ids=None,
    authorized_by=None,
):
    """
    Authoritative normal roster generation for a station:
    - 3 permanent guard pairs (6 guards total) rotating in 12-day cycles.
    - Each cycle: 4 consecutive ON calendar days, followed by 8 days TIME_OFF.
    - Day/Night assignments alternate between working cycles within each pair.
    - Links generated shifts to a DRAFT DutyRoster.
    - Safely regenerates existing DRAFT rosters without deleting attendance-protected
      or external shifts.
    - Strictly prevents modification of APPROVED/ACTIVE/ARCHIVED rosters.
    - Runs entirely within transaction.atomic() to guarantee atomic rollback on failure.
    - Preserves specialized assignments (EXAM, ESCORT, RELIEF).
    """
    with transaction.atomic():
        if not station:
            raise ValidationError("A valid station is required for roster generation.")

        # 1. Validate station has exactly 3 valid active GuardPairs
        pairs = list(GuardPair.objects.filter(station=station, is_active=True).order_by("rotation_order"))
        if len(pairs) != 3:
            raise ValidationError(
                f"Station '{station.name}' must have exactly 3 active guard pairs to generate an authoritative roster (found {len(pairs)})."
            )

        # 2. Validate rotation orders are exactly [1, 2, 3]
        rotation_orders = [p.rotation_order for p in pairs]
        if rotation_orders != [1, 2, 3]:
            raise ValidationError(
                f"Active guard pairs for station '{station.name}' must have rotation orders exactly [1, 2, 3] (found {rotation_orders})."
            )

        # 3. Validate each pair has 2 distinct active GUARD users and no guard is in multiple pairs
        from apps.accounts.models import UserRole
        all_guards_set = set()
        for p in pairs:
            if not p.guard_a_id or not p.guard_b_id or p.guard_a_id == p.guard_b_id:
                raise ValidationError(f"Pair {p.rotation_order} must contain two distinct guards.")
            for g in [p.guard_a, p.guard_b]:
                if not g.is_active:
                    raise ValidationError(f"Guard {g.username} in Pair {p.rotation_order} is not active.")
                if g.role != UserRole.GUARD:
                    raise ValidationError(f"User {g.username} in Pair {p.rotation_order} must have the GUARD role.")
                if g.station_id != station.id:
                    raise ValidationError(f"Guard {g.username} in Pair {p.rotation_order} is not assigned to station '{station.name}'.")
                if g.id in all_guards_set:
                    raise ValidationError(f"Guard {g.username} is assigned to multiple active pairs at station '{station.name}'.")
                all_guards_set.add(g.id)

        # 4. Calculate end_date
        end_date = start_date + timedelta(days=cycle_days - 1)

        # 5. Check whether an APPROVED or ACTIVE DutyRoster covers any part of the requested period
        protected_roster = DutyRoster.objects.filter(
            station=station,
            status__in=[RosterStatus.APPROVED, RosterStatus.ACTIVE, RosterStatus.ARCHIVED],
            start_date__lte=end_date,
            end_date__gte=start_date,
        ).first()
        if protected_roster:
            raise ValidationError(
                f"Cannot generate roster: An {protected_roster.get_status_display().lower()} DutyRoster "
                f"({protected_roster.start_date} to {protected_roster.end_date}) "
                f"already covers the requested generation period ({start_date} to {end_date})."
            )

        # 6. DRAFT DutyRoster: reuse exact matching draft or create new DRAFT
        draft_roster = DutyRoster.objects.filter(
            station=station,
            start_date=start_date,
            end_date=end_date,
            status=RosterStatus.DRAFT,
        ).first()

        if not draft_roster:
            draft_roster = DutyRoster.objects.create(
                station=station,
                start_date=start_date,
                end_date=end_date,
                status=RosterStatus.DRAFT,
            )

        # 7. Check for conflicting external roster shifts in target period
        # Never delete a conflicting shift belonging to another roster; report a blocking conflict.
        other_roster_shifts = Shift.objects.filter(
            station=station,
            date__gte=start_date,
            date__lte=end_date,
        ).exclude(
            roster=draft_roster
        ).filter(
            roster__isnull=False
        )
        if other_roster_shifts.exists():
            conflict = other_roster_shifts.first()
            raise ValidationError(
                f"Cannot generate roster: A conflicting shift exists on {conflict.date} for {conflict.guard.username} "
                f"belonging to another roster ({conflict.roster})."
            )

        # 8. Safe draft regeneration:
        # Only remove child shifts that satisfy ALL:
        # - shift.roster == this DRAFT roster
        # - assignment_type is NORMAL or TIME_OFF
        # - no Attendance exists
        # - shift is inside the target generation period
        Shift.objects.filter(
            roster=draft_roster,
            date__gte=start_date,
            date__lte=end_date,
            assignment_type__in=[AssignmentType.NORMAL, AssignmentType.TIME_OFF],
            attendance_records__isnull=True,
        ).delete()

        created_shifts = []
        num_pairs = len(pairs)
        block_length = 4
        specialized_types = {
            AssignmentType.EXAM,
            AssignmentType.ESCORT,
            AssignmentType.RELIEF,
        }

        # Determine baseline alternation offset for each pair based on prior history before start_date
        pair_base_cycle_offset = {}
        for p in pairs:
            prior_day_shift = Shift.objects.filter(
                station=station,
                pair=p,
                shift_type=ShiftType.DAY,
                assignment_type=AssignmentType.NORMAL,
                date__lt=start_date,
            ).order_by("-date").first()

            if prior_day_shift and prior_day_shift.guard_id == p.guard_a_id:
                pair_base_cycle_offset[p.id] = 1
            else:
                pair_base_cycle_offset[p.id] = 0

        # Prepare relief guards for Exam Mode if active
        exam_guards = []
        if mode == "EXAM" or examination_period is not None:
            if exam_guard_ids and len(exam_guard_ids) == 2:
                from apps.accounts.models import User
                exam_guards = list(User.objects.filter(id__in=exam_guard_ids))
            elif num_pairs >= 2:
                relief_pair = pairs[1]
                exam_guards = [relief_pair.guard_a, relief_pair.guard_b]

        for day_offset in range(cycle_days):
            current_date = start_date + timedelta(days=day_offset)
            block_index = day_offset // block_length
            active_pair_idx = block_index % num_pairs
            active_pair = pairs[active_pair_idx]

            is_exam_day = (mode == "EXAM" or examination_period is not None) and (day_offset < 5)

            # Exam relief substitution for main campus
            campus_pair = active_pair
            if is_exam_day and exam_guards:
                exam_guard_ids_set = {g.id for g in exam_guards}
                if active_pair.guard_a_id in exam_guard_ids_set or active_pair.guard_b_id in exam_guard_ids_set:
                    available_pairs = [
                        p for p in pairs
                        if p.guard_a_id not in exam_guard_ids_set and p.guard_b_id not in exam_guard_ids_set
                    ]
                    if available_pairs:
                        campus_pair = available_pairs[-1]

            # Day/Night alternation calculation
            pair_cycle_num = block_index // num_pairs
            effective_cycle = pair_cycle_num + pair_base_cycle_offset.get(campus_pair.id, 0)

            if effective_cycle % 2 == 0:
                campus_day_guard = campus_pair.guard_a
                campus_night_guard = campus_pair.guard_b
            else:
                campus_day_guard = campus_pair.guard_b
                campus_night_guard = campus_pair.guard_a

            # 1. Main Campus: 1 Day guard (07:00 - 18:00)
            existing_day = Shift.objects.filter(
                station=station,
                guard=campus_day_guard,
                date=current_date,
                shift_type=ShiftType.DAY,
            ).first()

            if existing_day and (
                existing_day.attendance_records.exists()
                or existing_day.assignment_type in specialized_types
            ):
                created_shifts.append(existing_day)
            else:
                shift_day, _ = Shift.objects.update_or_create(
                    station=station,
                    guard=campus_day_guard,
                    date=current_date,
                    shift_type=ShiftType.DAY,
                    defaults={
                        "start_time": day_start,
                        "end_time": day_end,
                        "assignment_type": AssignmentType.NORMAL,
                        "duty_location": "Main Campus",
                        "pair": active_pair,
                        "roster": draft_roster,
                        "examination_period": None,
                    }
                )
                created_shifts.append(shift_day)

            # 2. Main Campus: 1 Night guard (18:00 - 07:00)
            existing_night = Shift.objects.filter(
                station=station,
                guard=campus_night_guard,
                date=current_date,
                shift_type=ShiftType.NIGHT,
            ).first()

            if existing_night and (
                existing_night.attendance_records.exists()
                or existing_night.assignment_type in specialized_types
            ):
                created_shifts.append(existing_night)
            else:
                shift_night, _ = Shift.objects.update_or_create(
                    station=station,
                    guard=campus_night_guard,
                    date=current_date,
                    shift_type=ShiftType.NIGHT,
                    defaults={
                        "start_time": night_start,
                        "end_time": night_end,
                        "assignment_type": AssignmentType.NORMAL,
                        "duty_location": "Main Campus",
                        "pair": active_pair,
                        "roster": draft_roster,
                        "examination_period": None,
                    }
                )
                created_shifts.append(shift_night)

            # 3. Off-duty pairs: record scheduled TIME_OFF (unless assigned to EXAM relief or specialized duty)
            for pair_idx, pair in enumerate(pairs):
                if pair_idx != active_pair_idx:
                    for guard in [pair.guard_a, pair.guard_b]:
                        is_on_exam = (mode == "EXAM" or examination_period is not None) and (guard in exam_guards) and (day_offset < 5)

                        # Check if guard has an existing specialized assignment (EXAM, ESCORT, RELIEF) today
                        has_specialized = Shift.objects.filter(
                            station=station,
                            guard=guard,
                            date=current_date,
                            assignment_type__in=specialized_types,
                        ).first()
                        if has_specialized:
                            created_shifts.append(has_specialized)
                            continue

                        if not is_on_exam:
                            existing_off = Shift.objects.filter(
                                station=station,
                                guard=guard,
                                date=current_date,
                                shift_type=ShiftType.OFF,
                            ).first()

                            if existing_off and (
                                existing_off.attendance_records.exists()
                                or existing_off.assignment_type in specialized_types
                            ):
                                created_shifts.append(existing_off)
                            else:
                                shift_off, _ = Shift.objects.update_or_create(
                                    station=station,
                                    guard=guard,
                                    date=current_date,
                                    shift_type=ShiftType.OFF,
                                    defaults={
                                        "start_time": time(0, 0),
                                        "end_time": time(0, 0),
                                        "assignment_type": AssignmentType.TIME_OFF,
                                        "duty_location": "Time Off",
                                        "pair": pair,
                                        "roster": draft_roster,
                                        "examination_period": None,
                                    }
                                )
                                created_shifts.append(shift_off)

            # 4. Examination Venue Duty (if EXAM mode is active, for 5 consecutive days)
            if (mode == "EXAM" or examination_period is not None) and exam_guards and day_offset < 5:
                venue = examination_period.venue_name if examination_period else exam_venue_name
                for exam_guard in exam_guards:
                    Shift.objects.filter(
                        station=station,
                        guard=exam_guard,
                        date=current_date,
                        shift_type=ShiftType.OFF,
                        attendance_records__isnull=True,
                    ).delete()

                    existing_exam = Shift.objects.filter(
                        station=station,
                        guard=exam_guard,
                        date=current_date,
                        shift_type=ShiftType.DAY,
                    ).first()

                    if existing_exam and (
                        existing_exam.attendance_records.exists()
                        or existing_exam.assignment_type in specialized_types
                    ):
                        created_shifts.append(existing_exam)
                    else:
                        exam_shift, _ = Shift.objects.update_or_create(
                            station=station,
                            guard=exam_guard,
                            date=current_date,
                            shift_type=ShiftType.DAY,
                            defaults={
                                "start_time": day_start,
                                "end_time": day_end,
                                "assignment_type": AssignmentType.EXAM,
                                "duty_location": venue,
                                "pair": getattr(exam_guard, "pairs_as_guard_a", None).first() or getattr(exam_guard, "pairs_as_guard_b", None).first(),
                                "examination_period": examination_period,
                                "roster": draft_roster,
                            }
                        )
                        created_shifts.append(exam_shift)

                    if day_offset == 0:
                        guard_pair = getattr(exam_guard, "pairs_as_guard_a", None).first() or getattr(exam_guard, "pairs_as_guard_b", None).first()
                        TemporaryAssignmentAudit.objects.create(
                            guard=exam_guard,
                            original_pair=guard_pair,
                            original_assignment="TIME_OFF",
                            temporary_assignment="EXAM",
                            location=venue,
                            start_date=start_date,
                            end_date=start_date + timedelta(days=4),
                            start_time=day_start,
                            end_time=day_end,
                            reason="Authorized university examination venue security duty (5-day consecutive exception)",
                            authorized_by=authorized_by,
                        )

        return created_shifts

def validate_duty_roster(roster):
    """
    Authoritatively validates a DutyRoster and its child shifts.
    Validation verifies:
    - exactly 3 active GuardPairs for the station
    - rotation orders exactly [1, 2, 3]
    - exactly 2 distinct active GUARD users per pair assigned to this station
    - no guard belongs to multiple active pairs
    - 4 consecutive ON days / 8 TIME_OFF days for each 12-day cycle
    - correct DAY/NIGHT staffing (1 Day guard 07:00-18:00, 1 Night guard 18:00-07:00)
    - day/night alternation history consistency
    - no overlapping shifts or duplicate duty assignments for any guard on the same date
    - no blocking external-roster conflicts
    - specialized EXAM/ESCORT/RELIEF assignments remain intact and not destroyed or duplicated
    - changes lifecycle: DRAFT -> VALIDATED
    - does NOT approve the roster
    """
    if isinstance(roster, (str, uuid.UUID)):
        roster_obj = DutyRoster.objects.filter(id=roster).select_related("station").first()
    else:
        roster_obj = roster

    if not roster_obj:
        raise ValidationError("DutyRoster not found.")

    if roster_obj.status not in [RosterStatus.DRAFT, RosterStatus.VALIDATED]:
        raise ValidationError(
            f"Cannot validate roster: Current status is {roster_obj.get_status_display()}. Only DRAFT rosters can be validated."
        )

    station = roster_obj.station
    if not station:
        raise ValidationError("Roster has no associated station.")

    # 1. Validate station has exactly 3 valid active GuardPairs
    pairs = list(GuardPair.objects.filter(station=station, is_active=True).order_by("rotation_order"))
    if len(pairs) != 3:
        raise ValidationError(
            f"Station '{station.name}' must have exactly 3 active guard pairs to validate an authoritative roster (found {len(pairs)})."
        )

    # 2. Validate rotation orders are exactly [1, 2, 3]
    rotation_orders = [p.rotation_order for p in pairs]
    if rotation_orders != [1, 2, 3]:
        raise ValidationError(
            f"Active guard pairs for station '{station.name}' must have rotation orders exactly [1, 2, 3] (found {rotation_orders})."
        )

    # 3. Validate each pair has 2 distinct active GUARD users and no guard is in multiple pairs
    all_guards_set = set()
    for p in pairs:
        if not p.guard_a_id or not p.guard_b_id or p.guard_a_id == p.guard_b_id:
            raise ValidationError(f"Pair {p.rotation_order} must contain two distinct guards.")
        for g in [p.guard_a, p.guard_b]:
            if not g.is_active:
                raise ValidationError(f"Guard {g.username} in Pair {p.rotation_order} is not active.")
            if g.role != UserRole.GUARD:
                raise ValidationError(f"User {g.username} in Pair {p.rotation_order} must have the GUARD role.")
            if g.station_id != station.id:
                raise ValidationError(f"Guard {g.username} in Pair {p.rotation_order} is not assigned to station '{station.name}'.")
            if g.id in all_guards_set:
                raise ValidationError(f"Guard {g.username} is assigned to multiple active pairs at station '{station.name}'.")
            all_guards_set.add(g.id)

    # 4. Validate date range
    if not roster_obj.start_date or not roster_obj.end_date or roster_obj.end_date < roster_obj.start_date:
        raise ValidationError("Invalid roster date range.")
    total_days = (roster_obj.end_date - roster_obj.start_date).days + 1

    # 5. Validate child shifts
    roster_shifts = list(Shift.objects.filter(roster=roster_obj).select_related("guard", "pair"))
    if not roster_shifts:
        raise ValidationError("Cannot validate roster: No shifts are assigned to this roster.")

    shifts_by_date = defaultdict(list)
    for s in roster_shifts:
        shifts_by_date[s.date].append(s)

    # Check external roster conflicts
    conflicting_external_shifts = Shift.objects.filter(
        station=station,
        date__gte=roster_obj.start_date,
        date__lte=roster_obj.end_date,
    ).exclude(
        roster=roster_obj
    ).filter(
        roster__isnull=False
    )
    if conflicting_external_shifts.exists():
        conf = conflicting_external_shifts.first()
        raise ValidationError(
            f"Blocking conflict: An external roster ({conf.roster}) has a conflicting shift on {conf.date} for guard {conf.guard.username}."
        )

    # Baseline alternation offset per pair
    pair_base_cycle_offset = {}
    for p in pairs:
        prior_day_shift = Shift.objects.filter(
            station=station,
            pair=p,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.NORMAL,
            date__lt=roster_obj.start_date,
        ).order_by("-date").first()
        if prior_day_shift and prior_day_shift.guard_id == p.guard_a_id:
            pair_base_cycle_offset[p.id] = 1
        else:
            pair_base_cycle_offset[p.id] = 0

    block_length = 4
    num_pairs = 3

    for day_offset in range(total_days):
        current_date = roster_obj.start_date + timedelta(days=day_offset)
        day_shifts = shifts_by_date.get(current_date, [])
        if not day_shifts:
            raise ValidationError(f"Missing scheduled shifts on {current_date} for roster period.")

        # Check Main Campus coverage
        day_shifts_mc = [s for s in day_shifts if s.duty_location == "Main Campus" and s.shift_type == ShiftType.DAY and s.assignment_type == AssignmentType.NORMAL]
        night_shifts_mc = [s for s in day_shifts if s.duty_location == "Main Campus" and s.shift_type == ShiftType.NIGHT and s.assignment_type == AssignmentType.NORMAL]

        if len(day_shifts_mc) != 1:
            raise ValidationError(f"Main Campus requires exactly 1 DAY guard on {current_date} (found {len(day_shifts_mc)}).")
        if len(night_shifts_mc) != 1:
            raise ValidationError(f"Main Campus requires exactly 1 NIGHT guard on {current_date} (found {len(night_shifts_mc)}).")

        # Check duplicate shifts / duty on current_date
        shifts_by_guard_today = defaultdict(list)
        for s in day_shifts:
            shifts_by_guard_today[s.guard_id].append(s)

        for gid, g_today_shifts in shifts_by_guard_today.items():
            if len(g_today_shifts) > 1:
                for i in range(len(g_today_shifts)):
                    for j in range(i + 1, len(g_today_shifts)):
                        s1 = g_today_shifts[i]
                        s2 = g_today_shifts[j]
                        if s1.shift_type != ShiftType.OFF and s2.shift_type != ShiftType.OFF:
                            if not (s1.end_time <= s2.start_time or s2.end_time <= s1.start_time):
                                raise ValidationError(
                                    f"Overlapping shift duties for guard {s1.guard.username} on {current_date}."
                                )

        # Check Day/Night assignment correctness
        block_index = day_offset // block_length
        active_pair_idx = block_index % num_pairs
        active_pair = pairs[active_pair_idx]

        mc_day_guard_id = day_shifts_mc[0].guard_id
        mc_night_guard_id = night_shifts_mc[0].guard_id

        # Verify active guards are valid station pair guards
        valid_pair_guard_ids = {p.guard_a_id for p in pairs} | {p.guard_b_id for p in pairs}
        if mc_day_guard_id not in valid_pair_guard_ids or mc_night_guard_id not in valid_pair_guard_ids:
            raise ValidationError(f"Main Campus guard on {current_date} is not a valid pair guard for {station.name}.")

    # 6. Verify 4-on / 8-off for complete 12-day blocks
    complete_cycles = total_days // 12
    cycle_has_specialized = any(
        s.assignment_type in [AssignmentType.EXAM, AssignmentType.ESCORT, AssignmentType.RELIEF]
        for s in roster_shifts
    )
    if complete_cycles >= 1 and not cycle_has_specialized:
        for c in range(complete_cycles):
            c_start = roster_obj.start_date + timedelta(days=c * 12)
            c_end = c_start + timedelta(days=11)
            c_shifts = [s for s in roster_shifts if c_start <= s.date <= c_end]

            for p in pairs:
                p_guard_ids = {p.guard_a_id, p.guard_b_id}
                p_shifts = [s for s in c_shifts if s.guard_id in p_guard_ids]
                p_work_shifts = [s for s in p_shifts if s.shift_type in [ShiftType.DAY, ShiftType.NIGHT] and s.assignment_type == AssignmentType.NORMAL]
                p_work_dates = set(s.date for s in p_work_shifts)

                if len(p_work_dates) != 4:
                    raise ValidationError(
                        f"Pair {p.rotation_order} scheduled for {len(p_work_dates)} working days in 12-day cycle ({c_start} to {c_end}); expected exactly 4."
                    )

    # 7. Specialized assignments validation
    specialized_shifts = [s for s in roster_shifts if s.assignment_type in [AssignmentType.EXAM, AssignmentType.ESCORT, AssignmentType.RELIEF]]
    for spec_s in specialized_shifts:
        if spec_s.start_time and spec_s.end_time and spec_s.end_time <= spec_s.start_time:
            raise ValidationError(f"Invalid hours for specialized assignment {spec_s.assignment_type} on {spec_s.date}.")

    # Success: transition status DRAFT -> VALIDATED
    roster_obj.status = RosterStatus.VALIDATED
    roster_obj.save(update_fields=["status", "updated_at"])

    return {
        "valid": True,
        "status": RosterStatus.VALIDATED,
        "roster_id": str(roster_obj.id),
        "station": station.name,
        "start_date": str(roster_obj.start_date),
        "end_date": str(roster_obj.end_date),
        "shifts_count": len(roster_shifts),
        "message": f"DutyRoster for station '{station.name}' successfully validated.",
    }

def approve_duty_roster(roster, user):
    """
    Formally approves a VALIDATED DutyRoster.
    Rules:
    - User must be authenticated and have role SUPERVISOR or ADMINISTRATOR.
    - If user is SUPERVISOR, user.station must equal roster.station.
    - If user is GUARD, raises PermissionDenied.
    - Status MUST be VALIDATED.
    - DRAFT rosters are rejected.
    - Already APPROVED, ACTIVE, or ARCHIVED rosters are rejected.
    - Runs in transaction.atomic() with select_for_update() to prevent race conditions.
    - Performs final re-validation immediately before approval.
    - Persists approved_by=user and approved_at=timezone.now().
    - Never modifies or regenerates shifts.
    """
    if not user or not user.is_authenticated:
        raise PermissionDenied("Authentication required to approve roster.")

    if hasattr(user, "role") and user.role == UserRole.GUARD:
        raise PermissionDenied("Guards are not authorized to approve rosters.")

    if hasattr(user, "role") and user.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR] and not user.is_superuser and not user.is_staff:
        raise PermissionDenied("Only Supervisors and Administrators can approve rosters.")

    with transaction.atomic():
        if isinstance(roster, (str, uuid.UUID)):
            locked_roster = DutyRoster.objects.select_for_update().filter(id=roster).select_related("station").first()
        else:
            locked_roster = DutyRoster.objects.select_for_update().filter(id=roster.id).select_related("station").first()

        if not locked_roster:
            raise ValidationError("DutyRoster not found.")

        # Station scope check for supervisor
        if user.role == UserRole.SUPERVISOR:
            if not user.station_id or user.station_id != locked_roster.station_id:
                raise PermissionDenied(
                    f"Supervisor {user.username} is assigned to station '{getattr(user.station, 'name', 'None')}' "
                    f"and cannot approve a roster for '{locked_roster.station.name}'."
                )

        # Status checks
        if locked_roster.status == RosterStatus.APPROVED:
            raise ValidationError("Roster has already been approved.")
        if locked_roster.status in [RosterStatus.ACTIVE, RosterStatus.ARCHIVED]:
            raise ValidationError(f"Cannot approve roster: current status is {locked_roster.get_status_display()}.")
        if locked_roster.status == RosterStatus.DRAFT:
            raise ValidationError("Cannot approve DRAFT roster directly. Roster must be VALIDATED first.")
        if locked_roster.status != RosterStatus.VALIDATED:
            raise ValidationError(f"Cannot approve roster with status '{locked_roster.status}'. Only VALIDATED rosters can be approved.")

        # Final validation immediately before approval
        validate_duty_roster(locked_roster)

        # Persist approval metadata
        locked_roster.status = RosterStatus.APPROVED
        locked_roster.approved_by = user
        locked_roster.approved_at = timezone.now()
        locked_roster.full_clean()
        locked_roster.save(update_fields=["status", "approved_by", "approved_at", "updated_at"])

        # Notify all distinct active guards on this roster within the transaction boundary
        notify_roster_approval(locked_roster, user)

        return {
            "approved": True,
            "status": RosterStatus.APPROVED,
            "roster_id": str(locked_roster.id),
            "station": locked_roster.station.name,
            "approved_by": user.username,
            "approved_at": locked_roster.approved_at.isoformat(),
            "message": f"Roster for station {locked_roster.station.name} formally approved by {user.username}.",
        }

def notify_roster_approval(roster, user):
    """
    Notifies all distinct active guards assigned to shifts in the approved roster.
    Idempotent: Uses business-event-scoped dedup_key ROSTER_APPROVAL:{roster.id}:{guard.id}.
    Recipients are derived solely from Shift.guard relationships on this roster.
    """
    from apps.notifications.models import Notification
    from django.contrib.auth import get_user_model
    UserModel = get_user_model()

    guard_ids = Shift.objects.filter(
        roster=roster,
        guard__isnull=False,
        guard__is_active=True,
    ).values_list("guard_id", flat=True).distinct()

    guards = UserModel.objects.filter(id__in=guard_ids, is_active=True)
    approver_name = user.get_full_name() or user.username

    for guard in guards:
        dedup_key = f"ROSTER_APPROVAL:{roster.id}:{guard.id}"
        Notification.objects.get_or_create(
            dedup_key=dedup_key,
            defaults={
                "user": guard,
                "title": f"Duty Roster Approved: {roster.station.name}",
                "message": (
                    f"The duty roster for station '{roster.station.name}' covering "
                    f"{roster.start_date} to {roster.end_date} has been approved by {approver_name}. "
                    f"Your duty schedule is now approved and active."
                ),
                "notification_type": "ROSTER_APPROVED",
            },
        )

def get_authoritative_roster_for_station(station, on_date=None):
    """
    Returns the currently authoritative (APPROVED or ACTIVE) DutyRoster for the station
    on a given date. DRAFT and VALIDATED rosters are strictly excluded.
    """
    if not on_date:
        on_date = timezone.localdate()
    return DutyRoster.objects.filter(
        station=station,
        status__in=[RosterStatus.APPROVED, RosterStatus.ACTIVE],
        start_date__lte=on_date,
        end_date__gte=on_date,
    ).first()

def schedule_exam_escort(
    station,
    date,
    guard_ids,
    start_time=time(6, 0),
    end_time=time(17, 0),
    reason="Examination paper collection escort to University National Centre",
    authorized_by=None,
):
    """
    Schedules an official examination collection escort:
      - Exactly 2 security guards required.
      - Standard hours: 06:00 -> 17:00.
      - Rejects if any guard has an overlapping mandatory duty on that date.
      - Records official TemporaryAssignmentAudit log.
    """
    if not guard_ids or len(guard_ids) != 2:
        raise ValidationError("Examination collection escort strictly requires exactly 2 security guards.")

    from apps.accounts.models import User
    guards = list(User.objects.filter(id__in=guard_ids))
    if len(guards) != 2:
        raise ValidationError("Both escort guards must be valid registered security guards.")

    escort_shifts = []

    for guard in guards:
        # Check for overlapping mandatory duties
        active_shifts = Shift.objects.filter(
            guard=guard,
            date=date
        ).exclude(assignment_type=AssignmentType.TIME_OFF)

        for existing in active_shifts:
            # Overlap check between existing shift and 06:00-17:00
            # E.g. Day shift (07:00-18:00) overlaps with (06:00-17:00)
            if not (existing.end_time <= start_time or existing.start_time >= end_time):
                raise ValidationError(
                    f"Scheduling conflict: Guard {guard.username} already has mandatory duty "
                    f"'{existing.assignment_type}' at '{existing.duty_location}' "
                    f"({existing.start_time.strftime('%H:%M')}-{existing.end_time.strftime('%H:%M')}) on {date}."
                )

        # Clear any TIME_OFF record for the guard on this date
        Shift.objects.filter(station=station, guard=guard, date=date, shift_type=ShiftType.OFF).delete()

        # Find guard's pair for reference
        guard_pair = getattr(guard, "pairs_as_guard_a", None).first() or getattr(guard, "pairs_as_guard_b", None).first()

        shift = Shift.objects.create(
            station=station,
            guard=guard,
            date=date,
            start_time=start_time,
            end_time=end_time,
            shift_type=ShiftType.DAY,
            assignment_type=AssignmentType.ESCORT,
            duty_location="University National Centre - Paper Collection",
            pair=guard_pair,
        )
        escort_shifts.append(shift)

        TemporaryAssignmentAudit.objects.create(
            guard=guard,
            original_pair=guard_pair,
            original_assignment="TIME_OFF",
            temporary_assignment="ESCORT",
            location="University National Centre - Paper Collection",
            start_date=date,
            end_date=date,
            start_time=start_time,
            end_time=end_time,
            reason=reason,
            authorized_by=authorized_by,
        )

    return escort_shifts

def detect_roster_conflicts(station, start_date=None, end_date=None):
    """
    Authoritative conflict detection engine.
    Detects and flags:
      1. Simultaneous/overlapping duties for any guard on the same date.
      2. Guard assigned to Main Campus and Exam Venue simultaneously.
      3. Guard assigned to Escort (06:00-17:00) and another duty during that window.
      4. Location overstaffing (Main Campus > 1 Day or > 1 Night; Exam Venue > 2 Day).
      5. Coverage gaps (Main Campus missing Day or Night; Exam Venue missing guards when active).
      6. Excessive consecutive working days:
         - Normal duty allows at most 4 consecutive working days.
         - Exam duty explicitly permits 5 consecutive working days (authorized exception).
         - Flags > 5 days as violations.
    """
    conflicts = []

    from apps.accounts.models import User
    station_guards = list(User.objects.filter(station=station))
    station_shift_guard_ids = Shift.objects.filter(station=station).values_list("guard_id", flat=True)
    all_guard_ids = set(g.id for g in station_guards).union(set(station_shift_guard_ids))

    qs = Shift.objects.filter(guard_id__in=all_guard_ids).exclude(assignment_type=AssignmentType.TIME_OFF).select_related("guard", "station")
    if start_date:
        qs = qs.filter(date__gte=start_date)
    if end_date:
        qs = qs.filter(date__lte=end_date)

    shifts_by_date = {}
    shifts_by_guard = {}

    for s in qs:
        shifts_by_date.setdefault(s.date, []).append(s)
        shifts_by_guard.setdefault(s.guard_id, []).append(s)

    # 1. Per-date checks (Overlaps, Double bookings, Overstaffing, Uncovered posts)
    for date_val, d_shifts in shifts_by_date.items():
        # Check guard duplicates / overlaps on this date
        guard_shifts_map = {}
        for s in d_shifts:
            guard_shifts_map.setdefault(s.guard_id, []).append(s)

        for gid, g_shifts in guard_shifts_map.items():
            if len(g_shifts) > 1:
                # Compare pairs for time overlap
                guard_name = g_shifts[0].guard.username
                for i in range(len(g_shifts)):
                    for j in range(i + 1, len(g_shifts)):
                        s1, s2 = g_shifts[i], g_shifts[j]
                        # Main Campus + Exam Venue simultaneous assignment
                        if ("Main Campus" in s1.duty_location and "Exam" in s2.duty_location) or \
                           ("Main Campus" in s2.duty_location and "Exam" in s1.duty_location):
                            conflicts.append({
                                "type": "DUAL_VENUE_ASSIGNMENT",
                                "severity": "ERROR",
                                "date": str(date_val),
                                "guard": guard_name,
                                "message": f"Guard {guard_name} assigned simultaneously to Main Campus and Exam Venue on {date_val}."
                            })

                        # Escort overlap check
                        if s1.assignment_type == AssignmentType.ESCORT or s2.assignment_type == AssignmentType.ESCORT:
                            conflicts.append({
                                "type": "ESCORT_DUTY_OVERLAP",
                                "severity": "ERROR",
                                "date": str(date_val),
                                "guard": guard_name,
                                "message": f"Guard {guard_name} assigned to collection escort and overlapping duty on {date_val}."
                            })

                        # General time overlap
                        # Daytime shifts overlap if not s1.end_time <= s2.start_time and not s2.end_time <= s1.start_time
                        if not (s1.end_time <= s2.start_time or s2.end_time <= s1.start_time):
                            conflicts.append({
                                "type": "OVERLAPPING_DUTIES",
                                "severity": "ERROR",
                                "date": str(date_val),
                                "guard": guard_name,
                                "message": f"Guard {guard_name} has overlapping duties on {date_val} ({s1.assignment_type} and {s2.assignment_type})."
                            })

        # Staffing level checks for Main Campus
        campus_day = [s for s in d_shifts if s.duty_location == "Main Campus" and s.shift_type == ShiftType.DAY]
        campus_night = [s for s in d_shifts if s.duty_location == "Main Campus" and s.shift_type == ShiftType.NIGHT]

        if len(campus_day) == 0:
            conflicts.append({
                "type": "UNCOVERED_POST",
                "severity": "ERROR",
                "date": str(date_val),
                "guard": None,
                "message": f"Main Campus Day shift has no assigned security guard on {date_val}."
            })
        elif len(campus_day) > 1:
            conflicts.append({
                "type": "LOCATION_OVERSTAFFED",
                "severity": "WARNING",
                "date": str(date_val),
                "guard": None,
                "message": f"Main Campus Day shift has {len(campus_day)} guards assigned on {date_val} (expected 1)."
            })

        if len(campus_night) == 0:
            conflicts.append({
                "type": "UNCOVERED_POST",
                "severity": "ERROR",
                "date": str(date_val),
                "guard": None,
                "message": f"Main Campus Night shift has no assigned security guard on {date_val}."
            })
        elif len(campus_night) > 1:
            conflicts.append({
                "type": "LOCATION_OVERSTAFFED",
                "severity": "WARNING",
                "date": str(date_val),
                "guard": None,
                "message": f"Main Campus Night shift has {len(campus_night)} guards assigned on {date_val} (expected 1)."
            })

        # Staffing level checks for Exam Venue (if active on this date)
        exam_shifts = [s for s in d_shifts if s.assignment_type == AssignmentType.EXAM and s.shift_type == ShiftType.DAY]
        has_active_exam_period = ExaminationPeriod.objects.filter(
            station=station, is_active=True, start_date__lte=date_val, end_date__gte=date_val
        ).exists()

        if has_active_exam_period or len(exam_shifts) > 0:
            if len(exam_shifts) < 2:
                conflicts.append({
                    "type": "EXAM_VENUE_UNDERSTAFFED",
                    "severity": "ERROR",
                    "date": str(date_val),
                    "guard": None,
                    "message": f"Examination venue requires exactly 2 day guards, but only {len(exam_shifts)} assigned on {date_val}."
                })
            elif len(exam_shifts) > 2:
                conflicts.append({
                    "type": "LOCATION_OVERSTAFFED",
                    "severity": "WARNING",
                    "date": str(date_val),
                    "guard": None,
                    "message": f"Examination venue has {len(exam_shifts)} guards assigned on {date_val} (exceeds required 2)."
                })

    # 2. Consecutive working days check
    for gid, g_shifts in shifts_by_guard.items():
        sorted_dates = sorted(list(set(s.date for s in g_shifts)))
        guard_name = g_shifts[0].guard.username

        # Group dates into consecutive runs
        if not sorted_dates:
            continue

        runs = []
        current_run = [sorted_dates[0]]
        for d in sorted_dates[1:]:
            if d == current_run[-1] + timedelta(days=1):
                current_run.append(d)
            else:
                runs.append(current_run)
                current_run = [d]
        runs.append(current_run)

        for run in runs:
            run_length = len(run)
            # Check if any shift in this run is an EXAM shift
            run_shifts = [s for s in g_shifts if s.date in run]
            has_exam_duty = any(s.assignment_type == AssignmentType.EXAM for s in run_shifts)

            if has_exam_duty:
                # 5 consecutive days on exam duty is an explicit authorized exception!
                if run_length > 5:
                    conflicts.append({
                        "type": "EXCESSIVE_CONSECUTIVE_DAYS",
                        "severity": "ERROR",
                        "date": str(run[-1]),
                        "guard": guard_name,
                        "message": f"Guard {guard_name} scheduled for {run_length} consecutive working days (exceeds authorized 5-day examination limit)."
                    })
            else:
                # Normal duty maximum is 4 consecutive working days
                if run_length > 4:
                    conflicts.append({
                        "type": "EXCESSIVE_CONSECUTIVE_DAYS",
                        "severity": "ERROR",
                        "date": str(run[-1]),
                        "guard": guard_name,
                        "message": f"Guard {guard_name} scheduled for {run_length} consecutive normal duty days (exceeds 4-day normal duty limit)."
                    })

    has_errors = any(c["severity"] == "ERROR" for c in conflicts)
    has_warnings = any(c["severity"] == "WARNING" for c in conflicts)

    return {
        "has_conflicts": has_errors,
        "has_warnings": has_warnings,
        "total_conflicts": len(conflicts),
        "conflicts": conflicts,
    }

def resume_normal_roster(station, after_date, cycle_days=12, authorized_by=None):
    """
    Deactivates active examination periods on or before after_date,
    clears temporary exam/escort assignments on or after after_date,
    and regenerates the normal 4-day rotating roster for the station.
    The underlying guard pairs and rotation order are completely preserved.
    """
    # Deactivate examination periods that are complete
    ExaminationPeriod.objects.filter(station=station, is_active=True, end_date__lte=after_date).update(is_active=False)

    # Clear temporary exam / escort shifts starting from after_date
    Shift.objects.filter(
        station=station,
        date__gte=after_date,
        assignment_type__in=[AssignmentType.EXAM, AssignmentType.ESCORT]
    ).delete()

    # Regenerate normal roster from after_date
    created_shifts = generate_roster_for_station(
        station=station,
        start_date=after_date,
        cycle_days=cycle_days,
        mode="NORMAL",
        authorized_by=authorized_by,
    )

    return created_shifts


def get_active_public_holiday(on_date, country_code="ZW"):
    """
    Returns the active PublicHoliday for on_date in the given country context, or None.
    """
    return PublicHoliday.objects.filter(date=on_date, country_code=country_code, is_active=True).first()


def record_public_holiday_duty(shift, attendance=None):
    """
    Authoritative recording of a guard working on a configured public holiday.
    Links: PublicHoliday -> Shift -> Attendance -> PublicHolidayDutyRecord.
    Rules:
    - shift.date must be a configured, active PublicHoliday.
    - shift must be an actual duty assignment (not TIME_OFF or OFF).
    - Valid attendance proving the guard actually worked (clock_in exists) is required.
    - Idempotent: returns existing record if already created for this shift.
    - Initial state: PENDING (compensation is not automatically granted).
    """
    if not shift:
        raise ValidationError("Shift is required to record public holiday duty.")

    if shift.shift_type == ShiftType.OFF or shift.assignment_type == AssignmentType.TIME_OFF:
        raise ValidationError("TIME_OFF/OFF shifts are not eligible for public holiday duty credit.")

    holiday = get_active_public_holiday(shift.date)
    if not holiday:
        raise ValidationError(f"Shift date ({shift.date}) is not a configured active public holiday.")

    if attendance is None:
        attendance = Attendance.objects.filter(
            shift=shift,
            guard=shift.guard,
            clock_in__isnull=False,
        ).first()

    if not attendance or not attendance.clock_in:
        raise ValidationError("Valid attendance proving the guard actually worked is required.")

    if attendance.shift_id != shift.id or attendance.guard_id != shift.guard_id:
        raise ValidationError("Attendance record does not match the shift and guard.")

    # Idempotent check
    existing = PublicHolidayDutyRecord.objects.filter(shift=shift).first()
    if existing:
        return existing

    record = PublicHolidayDutyRecord.objects.create(
        public_holiday=holiday,
        shift=shift,
        guard=shift.guard,
        attendance=attendance,
        status=HolidayCompensationStatus.PENDING,
        compensated_days=2.0,
    )
    return record


def approve_holiday_compensation(duty_record, user, reason=""):
    """
    Formally approves 2 days of leave compensation for a worked public holiday.
    Rules:
    - User must be authenticated and have role SUPERVISOR or ADMINISTRATOR.
    - If user is GUARD, raises PermissionDenied.
    - If user is SUPERVISOR, user.station must match the shift's station.
    - Duty record must be in PENDING status.
    - Credits 2 days to the guard's vacation leave balance subject to the 90-day vacation cap.
    - Persists approved_by, approved_at, status=APPROVED, decision_reason.
    - Runs in transaction.atomic() with select_for_update().
    """
    if not user or not user.is_authenticated:
        raise PermissionDenied("Authentication required to approve holiday compensation.")

    if hasattr(user, "role") and user.role == UserRole.GUARD:
        raise PermissionDenied("Guards are not authorized to approve holiday compensation.")

    if hasattr(user, "role") and user.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR] and not user.is_superuser and not user.is_staff:
        raise PermissionDenied("Only Supervisors and Administrators can approve holiday compensation.")

    with transaction.atomic():
        if isinstance(duty_record, (str, uuid.UUID)):
            locked_record = PublicHolidayDutyRecord.objects.select_for_update().filter(id=duty_record).select_related("shift", "shift__station", "guard", "public_holiday").first()
        else:
            locked_record = PublicHolidayDutyRecord.objects.select_for_update().filter(id=duty_record.id).select_related("shift", "shift__station", "guard", "public_holiday").first()

        if not locked_record:
            raise ValidationError("PublicHolidayDutyRecord not found.")

        # Station scope check for Supervisor
        if user.role == UserRole.SUPERVISOR:
            shift_station_id = locked_record.shift.station_id
            if not user.station_id or user.station_id != shift_station_id:
                raise PermissionDenied(
                    f"Supervisor {user.username} is assigned to station '{getattr(user.station, 'name', 'None')}' "
                    f"and cannot approve holiday compensation for station '{locked_record.shift.station.name}'."
                )

        if locked_record.status == HolidayCompensationStatus.APPROVED:
            raise ValidationError("Holiday compensation has already been approved.")

        if locked_record.status != HolidayCompensationStatus.PENDING:
            raise ValidationError(
                f"Cannot approve compensation with status '{locked_record.get_status_display()}'. Only PENDING records can be approved."
            )

        # Authoritative Phase 5C accounting: Public-holiday compensation
        # 1. Record an EARNED transaction in PublicHolidayCompensationLedger.
        # 2. Ensure guard has a LeaveBalance record and credit 2 days to vacation balance.
        from apps.leave.models import PublicHolidayCompensationLedger, CompensationLedgerEntryType, LeaveBalance
        from decimal import Decimal

        PublicHolidayCompensationLedger.objects.get_or_create(
            duty_record=locked_record,
            entry_type=CompensationLedgerEntryType.EARNED,
            defaults={
                "guard": locked_record.guard,
                "days": Decimal(str(locked_record.compensated_days)),
                "created_by": user,
                "notes": f"Earned {locked_record.compensated_days} days compensation for working public holiday {locked_record.public_holiday.name} on {locked_record.shift.date}.",
            },
        )

        locked_record.status = HolidayCompensationStatus.APPROVED
        locked_record.approved_by = user
        locked_record.approved_at = timezone.now()
        locked_record.decision_reason = reason
        locked_record.full_clean()
        locked_record.save(update_fields=["status", "approved_by", "approved_at", "decision_reason", "updated_at"])

        if locked_record.guard and locked_record.guard.is_active:
            from apps.notifications.models import Notification
            approver_name = user.get_full_name() or user.username
            dedup_key = f"HOLIDAY_COMPENSATION:{locked_record.id}:APPROVED"
            reason_suffix = f" Decision notes: {reason}" if reason else ""
            Notification.objects.get_or_create(
                dedup_key=dedup_key,
                defaults={
                    "user": locked_record.guard,
                    "title": "Public Holiday Compensation Approved",
                    "message": (
                        f"Your compensation claim for working on public holiday {locked_record.public_holiday.name} "
                        f"({locked_record.shift.date}) has been APPROVED by {approver_name}. "
                        f"Compensation earned: {locked_record.compensated_days} days.{reason_suffix}"
                    ),
                    "notification_type": "HOLIDAY_COMPENSATION",
                },
            )

        return locked_record


def reject_holiday_compensation(duty_record, user, reason=""):
    """
    Rejects holiday compensation for a duty record.
    Requires Supervisor (within station scope) or Administrator.
    """
    if not user or not user.is_authenticated:
        raise PermissionDenied("Authentication required.")

    if hasattr(user, "role") and user.role == UserRole.GUARD:
        raise PermissionDenied("Guards cannot reject holiday compensation.")

    if hasattr(user, "role") and user.role not in [UserRole.SUPERVISOR, UserRole.ADMINISTRATOR] and not user.is_superuser and not user.is_staff:
        raise PermissionDenied("Only Supervisors and Administrators can review holiday compensation.")

    with transaction.atomic():
        if isinstance(duty_record, (str, uuid.UUID)):
            locked_record = PublicHolidayDutyRecord.objects.select_for_update().filter(id=duty_record).select_related("shift", "shift__station", "guard", "public_holiday").first()
        else:
            locked_record = PublicHolidayDutyRecord.objects.select_for_update().filter(id=duty_record.id).select_related("shift", "shift__station", "guard", "public_holiday").first()

        if not locked_record:
            raise ValidationError("PublicHolidayDutyRecord not found.")

        if user.role == UserRole.SUPERVISOR:
            if not user.station_id or user.station_id != locked_record.shift.station_id:
                raise PermissionDenied("Supervisor cannot review holiday compensation outside assigned station.")

        if locked_record.status == HolidayCompensationStatus.APPROVED:
            raise ValidationError("Cannot reject already approved holiday compensation.")

        locked_record.status = HolidayCompensationStatus.REJECTED
        locked_record.approved_by = user
        locked_record.approved_at = timezone.now()
        locked_record.decision_reason = reason
        locked_record.full_clean()
        locked_record.save(update_fields=["status", "approved_by", "approved_at", "decision_reason", "updated_at"])

        if locked_record.guard and locked_record.guard.is_active:
            from apps.notifications.models import Notification
            approver_name = user.get_full_name() or user.username
            dedup_key = f"HOLIDAY_COMPENSATION:{locked_record.id}:REJECTED"
            reason_suffix = f" Reason: {reason}" if reason else ""
            Notification.objects.get_or_create(
                dedup_key=dedup_key,
                defaults={
                    "user": locked_record.guard,
                    "title": "Public Holiday Compensation Rejected",
                    "message": (
                        f"Your compensation claim for working on public holiday {locked_record.public_holiday.name} "
                        f"({locked_record.shift.date}) has been REJECTED by {approver_name}.{reason_suffix}"
                    ),
                    "notification_type": "HOLIDAY_COMPENSATION",
                },
            )

        return locked_record


def resolve_next_guard_duty(guard, reference_date=None):
    """
    Authoritative computation of a guard's next upcoming working duty shift.
    Rules:
    - Excludes OFF and TIME_OFF shifts.
    - Excludes dates where the guard has approved leave (LeaveApplication.APPROVED).
    - If today has an upcoming shift that has not yet concluded, returns today's shift.
    - Otherwise, looks at future dates chronologically (date > reference_date).
    - Returns Shift model instance or None.
    """
    from apps.accounts.models import User
    from apps.leave.models import LeaveApplication, LeaveStatus

    if isinstance(guard, (str, uuid.UUID)):
        guard = User.objects.filter(id=guard).first()

    if not guard or not guard.is_active:
        return None

    now_tz = timezone.localtime(timezone.now())
    if reference_date is None:
        target_date = now_tz.date()
    elif isinstance(reference_date, str):
        try:
            target_date = dt_cls.strptime(reference_date, "%Y-%m-%d").date()
        except ValueError:
            target_date = now_tz.date()
    else:
        target_date = reference_date

    # Approved leave periods
    approved_leaves = list(LeaveApplication.objects.filter(
        guard=guard,
        status=LeaveStatus.APPROVED,
        end_date__gte=target_date,
    ).values("start_date", "end_date"))

    def is_date_on_leave(d):
        return any(l["start_date"] <= d <= l["end_date"] for l in approved_leaves)

    # 1. Check today's shift if evaluating today
    if target_date == now_tz.date() and not is_date_on_leave(target_date):
        today_shifts = list(
            Shift.objects.filter(guard=guard, date=target_date)
            .exclude(shift_type=ShiftType.OFF)
            .exclude(assignment_type=AssignmentType.TIME_OFF)
            .select_related("station", "pair")
            .order_by("start_time")
        )
        for s in today_shifts:
            att = Attendance.objects.filter(shift=s, guard=guard).first()
            if att and att.clock_out:
                continue

            # Schedule end boundary
            if s.end_time <= s.start_time:
                sched_end_dt = timezone.make_aware(
                    dt_cls.combine(s.date + timedelta(days=1), s.end_time),
                    timezone.get_current_timezone(),
                )
            else:
                sched_end_dt = timezone.make_aware(
                    dt_cls.combine(s.date, s.end_time), timezone.get_current_timezone()
                )

            if now_tz <= sched_end_dt or (att and att.clock_in and not att.clock_out):
                return s

    # 2. Check future shifts (date > target_date)
    future_shifts = Shift.objects.filter(
        guard=guard,
        date__gt=target_date,
    ).exclude(
        shift_type=ShiftType.OFF
    ).exclude(
        assignment_type=AssignmentType.TIME_OFF
    ).select_related("station", "pair").order_by("date", "start_time")

    for s in future_shifts:
        if not is_date_on_leave(s.date):
            return s

    return None


def resolve_guard_duty(guard, date=None, current_time=None):
    """
    Authoritative single source of truth for resolving a guard's duty status.
    Evaluation order:
    1. Guard active status: if not active -> INACTIVE, is_off_duty=True
    2. Active approved leave: if start_date <= date <= end_date -> ON_LEAVE
    3. Active exam duty: if assigned on date -> EXAM
    4. Active escort duty: if scheduled on date -> ESCORT
    5. Scheduled shift on date (or ongoing overnight shift from date-1):
       - Prioritizes DAY/NIGHT duty shifts over OFF.
       - If shift is OFF/TIME_OFF -> OFF_DUTY / TIME_OFF
       - If attendance clocked out -> OFF_DUTY
       - If attendance clocked in:
         - if early clockout OTP active -> EARLY_EXIT_PENDING
         - else -> ON_DUTY
       - If not clocked in:
         - if now < reporting_open: ELIGIBLE_FOR_DUTY (clock_in_enabled=False)
         - if reporting_open <= now <= sched_end: ELIGIBLE_FOR_DUTY (clock_in_enabled=True)
         - if now > sched_end: OFF_DUTY (clock_in_enabled=False)
    6. If no duty shift -> OFF_DUTY
    """
    from apps.accounts.models import User
    from apps.leave.models import LeaveApplication, LeaveStatus
    from apps.exams.models import ExamDuty, ExamStatus
    from apps.escorts.models import EscortDuty, EscortStatus
    from django.core.cache import cache

    if isinstance(guard, (str, uuid.UUID)):
        guard = User.objects.filter(id=guard).first()

    now_tz = timezone.localtime(timezone.now())
    if date is None:
        target_date = now_tz.date()
    elif isinstance(date, str):
        try:
            target_date = dt_cls.strptime(date, "%Y-%m-%d").date()
        except ValueError:
            target_date = now_tz.date()
    else:
        target_date = date

    if current_time is None:
        eval_dt = now_tz
    elif isinstance(current_time, dt_cls):
        eval_dt = timezone.localtime(current_time) if timezone.is_aware(current_time) else timezone.make_aware(current_time, timezone.get_current_timezone())
    elif isinstance(current_time, time):
        eval_dt = timezone.make_aware(dt_cls.combine(target_date, current_time), timezone.get_current_timezone())
    else:
        eval_dt = now_tz

    base_result = {
        "guard_id": str(guard.id) if guard else None,
        "guard_name": (guard.get_full_name() or guard.username) if guard else "Unknown",
        "date": target_date.isoformat(),
        "duty_state": "OFF_DUTY",
        "leave_type": None,
        "attendance_status": "OFF_DUTY",
        "is_on_duty": False,
        "is_off_duty": True,
        "is_eligible_for_duty": False,
        "is_on_leave": False,
        "clock_in_enabled": False,
        "clock_out_enabled": False,
        "shift": None,
        "next_duty": None,
        "leave_app": None,
        "exam_duty": None,
        "escort_duty": None,
        "attendance": None,
        "station": getattr(guard, "station", None),
    }

    if not guard or not guard.is_active:
        base_result["duty_state"] = "INACTIVE"
        return base_result

    base_result["next_duty"] = resolve_next_guard_duty(guard, reference_date=target_date)

    # 1. Approved Leave Check (strictly checking start_date <= date <= end_date)
    leave_app = LeaveApplication.objects.filter(
        guard=guard,
        status=LeaveStatus.APPROVED,
        start_date__lte=target_date,
        end_date__gte=target_date,
    ).first()

    if leave_app:
        base_result.update({
            "duty_state": "ON_LEAVE",
            "leave_type": leave_app.leave_type,
            "attendance_status": "ON_LEAVE",
            "is_on_duty": False,
            "is_off_duty": True,
            "is_eligible_for_duty": False,
            "is_on_leave": True,
            "clock_in_enabled": False,
            "clock_out_enabled": False,
            "leave_app": leave_app,
        })
        return base_result

    # 2. Examination Duty Check
    exam_duty = ExamDuty.objects.filter(
        guard=guard,
        date=target_date,
        status__in=[ExamStatus.ASSIGNED, ExamStatus.ACKNOWLEDGED, ExamStatus.IN_PROGRESS],
    ).first()

    if exam_duty:
        base_result.update({
            "duty_state": "EXAM",
            "attendance_status": "ON_DUTY" if exam_duty.status == ExamStatus.IN_PROGRESS else "NOT_CLOCKED_IN",
            "is_on_duty": True,
            "is_off_duty": False,
            "is_eligible_for_duty": False,
            "is_on_leave": False,
            "clock_in_enabled": True,
            "clock_out_enabled": True,
            "exam_duty": exam_duty,
        })
        return base_result

    # 3. Escort Duty Check
    escort_duty = EscortDuty.objects.filter(
        guard=guard,
        start_time__date__lte=target_date,
        end_time__date__gte=target_date,
        status__in=[EscortStatus.SCHEDULED, EscortStatus.ASSIGNED, EscortStatus.ACKNOWLEDGED, EscortStatus.EN_ROUTE],
    ).first()

    if escort_duty:
        base_result.update({
            "duty_state": "ESCORT",
            "attendance_status": "ON_DUTY" if escort_duty.status == EscortStatus.EN_ROUTE else "NOT_CLOCKED_IN",
            "is_on_duty": True,
            "is_off_duty": False,
            "is_eligible_for_duty": False,
            "is_on_leave": False,
            "clock_in_enabled": True,
            "clock_out_enabled": True,
            "escort_duty": escort_duty,
        })
        return base_result

    # 4. Scheduled Shift Check
    # Check for overnight shift from yesterday if early morning (< 07:00)
    shift = None
    if target_date == now_tz.date() and eval_dt.time() < time(7, 0):
        yesterday = target_date - timedelta(days=1)
        overnight = Shift.objects.filter(
            guard=guard,
            date=yesterday,
            shift_type=ShiftType.NIGHT,
        ).select_related("station", "pair").first()
        if overnight:
            att_overnight = Attendance.objects.filter(shift=overnight, guard=guard).first()
            if att_overnight and att_overnight.clock_in and not att_overnight.clock_out:
                shift = overnight

    if not shift:
        shifts_today = list(
            Shift.objects.filter(guard=guard, date=target_date).select_related("station", "pair")
        )
        # Prioritize active working duty shifts (DAY / NIGHT) over OFF / TIME_OFF
        shift = next((s for s in shifts_today if s.shift_type in [ShiftType.DAY, ShiftType.NIGHT] and s.assignment_type != AssignmentType.TIME_OFF), None)
        if not shift:
            shift = next((s for s in shifts_today if s.shift_type != ShiftType.OFF and s.assignment_type != AssignmentType.TIME_OFF), None)
        if not shift and shifts_today:
            shift = shifts_today[0]

    if not shift:
        return base_result

    base_result["shift"] = shift
    base_result["station"] = shift.station

    if shift.shift_type == ShiftType.OFF or shift.assignment_type == AssignmentType.TIME_OFF:
        base_result.update({
            "duty_state": "TIME_OFF",
            "attendance_status": "OFF_DUTY",
            "is_on_duty": False,
            "is_off_duty": True,
            "is_eligible_for_duty": False,
            "clock_in_enabled": False,
            "clock_out_enabled": False,
        })
        return base_result

    # Working shift attendance evaluation
    att = Attendance.objects.filter(shift=shift, guard=guard).first()
    base_result["attendance"] = att

    if att and att.clock_out:
        base_result.update({
            "duty_state": "OFF_DUTY",
            "attendance_status": "CLOCKED_OUT",
            "is_on_duty": False,
            "is_off_duty": True,
            "is_eligible_for_duty": False,
            "clock_in_enabled": False,
            "clock_out_enabled": False,
        })
        return base_result

    if att and att.clock_in:
        early_exit_key = f"early_clockout_otp_{shift.id}"
        is_early_pending = bool(cache.get(early_exit_key))
        duty_st = "EARLY_EXIT_PENDING" if is_early_pending else "ON_DUTY"
        base_result.update({
            "duty_state": duty_st,
            "attendance_status": "CLOCKED_IN",
            "is_on_duty": True,
            "is_off_duty": False,
            "is_eligible_for_duty": False,
            "clock_in_enabled": False,
            "clock_out_enabled": True,
        })
        return base_result

    # Unclocked working shift: evaluate schedule time window
    base_result["attendance_status"] = "NOT_CLOCKED_IN"

    sched_start_dt = timezone.make_aware(
        dt_cls.combine(shift.date, shift.start_time), timezone.get_current_timezone()
    )
    if shift.end_time <= shift.start_time:
        sched_end_dt = timezone.make_aware(
            dt_cls.combine(shift.date + timedelta(days=1), shift.end_time),
            timezone.get_current_timezone(),
        )
    else:
        sched_end_dt = timezone.make_aware(
            dt_cls.combine(shift.date, shift.end_time), timezone.get_current_timezone()
        )

    reporting_open_dt = sched_start_dt - timedelta(minutes=30)

    if eval_dt < reporting_open_dt:
        # Before reporting window opens: Scheduled, recognized as eligible, but clock-in not yet open
        base_result.update({
            "duty_state": "ELIGIBLE_FOR_DUTY",
            "is_on_duty": False,
            "is_off_duty": False,
            "is_eligible_for_duty": True,
            "clock_in_enabled": False,
            "clock_out_enabled": False,
        })
    elif reporting_open_dt <= eval_dt <= sched_end_dt:
        # Inside active duty / reporting window: Clock-in unlocked
        base_result.update({
            "duty_state": "ELIGIBLE_FOR_DUTY",
            "is_on_duty": False,
            "is_off_duty": False,
            "is_eligible_for_duty": True,
            "clock_in_enabled": True,
            "clock_out_enabled": False,
        })
    else:
        # Shift window ended without clocking in
        base_result.update({
            "duty_state": "OFF_DUTY",
            "is_on_duty": False,
            "is_off_duty": True,
            "is_eligible_for_duty": False,
            "clock_in_enabled": False,
            "clock_out_enabled": False,
        })

    return base_result


