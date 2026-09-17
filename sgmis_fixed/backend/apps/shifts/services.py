import datetime
from datetime import time, timedelta, datetime as dt_cls
from django.utils import timezone
from rest_framework.exceptions import ValidationError
from .models import Shift, ShiftType, AssignmentType, ExaminationPeriod, TemporaryAssignmentAudit, DutyRosterCycle
from apps.stations.models import GuardPair

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
    Generates shifts for pairs assigned to a station over a cycle according to authoritative rules:
    
    NORMAL SECURITY ARRANGEMENT:
      - Premise has 3 guard pairs (6 guards total).
      - Each pair provides continuous day/night coverage (one DAY guard 07:00-18:00, one NIGHT guard 18:00-07:00).
      - Continuous 24/7 coverage: Exactly 1 Day guard and 1 Night guard on duty each day for Main Campus.
      - The active pair works for 4 consecutive working days, then enters Time Off.
      - On the NEXT working cycle for that pair, the guards SWAP shifts:
          Cycle 1: Guard A = DAY, Guard B = NIGHT
          Cycle 2: Guard A = NIGHT, Guard B = DAY
          Cycle 3: Guard A = DAY, Guard B = NIGHT, etc.
      - Off-duty pairs receive scheduled TIME_OFF.
    
    EXAMINATION MODE:
      - Main Campus: Continues 1 DAY + 1 NIGHT guard coverage uninterrupted.
      - Examination Venue: Exactly 2 guards during DAY ONLY (07:00-18:00).
      - Exam venue guards work 5 consecutive days (authorized 5-day exception).
      - Relief guards drawn from off-duty pool; original pair remains intact.
      - Temporary assignments logged to TemporaryAssignmentAudit.
    """
    pairs = list(GuardPair.objects.filter(station=station, is_active=True).order_by("rotation_order"))
    if not pairs:
        raise ValueError(f"No active guard pairs found for station {station.name}. Please configure guard pairs first.")

    created_shifts = []
    num_pairs = len(pairs)
    block_length = 4  # 4 days per normal duty block

    # Prepare relief guards for Exam Mode if active
    exam_guards = []
    if mode == "EXAM" or examination_period is not None:
        if exam_guard_ids and len(exam_guard_ids) == 2:
            from apps.accounts.models import User
            exam_guards = list(User.objects.filter(id__in=exam_guard_ids))
        elif num_pairs >= 2:
            # Default relief: select pair 2 (or second active pair) from off-duty pool
            relief_pair = pairs[1]
            exam_guards = [relief_pair.guard_a, relief_pair.guard_b]

    for day_offset in range(cycle_days):
        current_date = start_date + timedelta(days=day_offset)
        block_index = day_offset // block_length
        active_pair_idx = block_index % num_pairs
        active_pair = pairs[active_pair_idx]

        is_exam_day = (mode == "EXAM" or examination_period is not None) and (day_offset < 5)

        # Relief Substitution: If active pair is serving on Exam Venue duty today,
        # substitute with the available off-duty pair so Main Campus is never uncovered or conflicted
        campus_pair = active_pair
        if is_exam_day and exam_guards:
            exam_guard_ids_set = {g.id for g in exam_guards}
            if active_pair.guard_a_id in exam_guard_ids_set or active_pair.guard_b_id in exam_guard_ids_set:
                # Find available off-duty pair not assigned to exams
                available_pairs = [
                    p for p in pairs 
                    if p.guard_a_id not in exam_guard_ids_set and p.guard_b_id not in exam_guard_ids_set
                ]
                if available_pairs:
                    # Choose the pair that was in Time Off (e.g. Pair 3)
                    campus_pair = available_pairs[-1]

        cycle_number = block_index // num_pairs  # working cycle iteration for this pair

        # CRITICAL SWAP RULE: Cycle 0 -> A=DAY, B=NIGHT; Cycle 1 -> A=NIGHT, B=DAY; Cycle 2 -> A=DAY, etc.
        if cycle_number % 2 == 0:
            campus_day_guard = campus_pair.guard_a
            campus_night_guard = campus_pair.guard_b
        else:
            campus_day_guard = campus_pair.guard_b
            campus_night_guard = campus_pair.guard_a

        # 1. Main Campus: 1 Day guard (07:00 - 18:00)
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
                "examination_period": None,
            }
        )
        created_shifts.append(shift_day)

        # 2. Main Campus: 1 Night guard (18:00 - 07:00)
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
                "examination_period": None,
            }
        )
        created_shifts.append(shift_night)

        # 3. Off-duty pairs: record scheduled TIME_OFF (unless assigned to EXAM relief)
        for pair_idx, pair in enumerate(pairs):
            if pair_idx != active_pair_idx:
                for guard in [pair.guard_a, pair.guard_b]:
                    # If this guard is on exam duty today, exam shift handled below
                    is_on_exam = (mode == "EXAM" or examination_period is not None) and (guard in exam_guards) and (day_offset < 5)
                    if not is_on_exam:
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
                                "examination_period": None,
                            }
                        )
                        created_shifts.append(shift_off)

        # 4. Examination Venue Duty (if EXAM mode is active, for 5 consecutive days)
        if (mode == "EXAM" or examination_period is not None) and exam_guards and day_offset < 5:
            venue = examination_period.venue_name if examination_period else exam_venue_name
            for exam_guard in exam_guards:
                # Remove any TIME_OFF record for this guard on this exam date
                Shift.objects.filter(station=station, guard=exam_guard, date=current_date, shift_type=ShiftType.OFF).delete()

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
                    }
                )
                created_shifts.append(exam_shift)

                # Record Temporary Assignment Audit on first day of exam block
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

