import datetime
from datetime import time, timedelta
from django.utils import timezone
from .models import Shift, ShiftType, DutyRosterCycle
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
        shift_type=incoming_type
    ).select_related("guard").first()

    if not incoming_shift:
        # Fallback: check next available shift at same station ordered by date and start_time
        incoming_shift = Shift.objects.filter(
            station=station,
            date__gte=shift_date
        ).exclude(id=outgoing_shift.id).order_by("date", "start_time").select_related("guard").first()

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
):
    """
    Generates shifts for pairs assigned to a station over a cycle.
    Standard rotation:
      Pairs rotate through 4-day Day shift, 4-day Night shift, 4-day Rest/Off.
    """
    pairs = list(GuardPair.objects.filter(station=station, is_active=True).order_by("rotation_order"))
    if not pairs:
        raise ValueError(f"No active guard pairs found for station {station.name}. Please configure guard pairs first.")

    created_shifts = []
    num_pairs = len(pairs)
    block_length = 4  # 4 days per duty block

    for day_offset in range(cycle_days):
        current_date = start_date + timedelta(days=day_offset)
        cycle_day = day_offset % (num_pairs * block_length if num_pairs > 0 else block_length)
        active_block = (cycle_day // block_length) % max(num_pairs, 1)

        # Assign pair 0 to Day, pair 1 to Night (if available), etc.
        day_pair = pairs[active_block % num_pairs]
        night_pair = pairs[(active_block + 1) % num_pairs] if num_pairs > 1 else pairs[0]

        # Create Day shifts for day_pair guards
        for guard in [day_pair.guard_a, day_pair.guard_b]:
            shift, _ = Shift.objects.update_or_create(
                station=station,
                guard=guard,
                date=current_date,
                shift_type=ShiftType.DAY,
                defaults={
                    "start_time": day_start,
                    "end_time": day_end,
                    "pair": day_pair,
                }
            )
            created_shifts.append(shift)

        # Create Night shifts for night_pair guards (if not the exact same pair or distinct)
        if num_pairs > 1:
            for guard in [night_pair.guard_a, night_pair.guard_b]:
                shift, _ = Shift.objects.update_or_create(
                    station=station,
                    guard=guard,
                    date=current_date,
                    shift_type=ShiftType.NIGHT,
                    defaults={
                        "start_time": night_start,
                        "end_time": night_end,
                        "pair": night_pair,
                    }
                )
                created_shifts.append(shift)

    return created_shifts
