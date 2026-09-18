import zoneinfo
from datetime import datetime, time, timedelta
from django.core.management.base import BaseCommand
from django.utils import timezone
from django.db import transaction

from apps.shifts.models import Shift, ShiftType, AssignmentType, RosterStatus
from apps.notifications.models import Notification

HARARE_TZ = zoneinfo.ZoneInfo("Africa/Harare")


class Command(BaseCommand):
    help = (
        "Sends in-app notifications for upcoming authoritative scheduled duties "
        "approximately 2-3 days before shift start (48h to 84h execution window) using Africa/Harare timezone."
    )

    def add_arguments(self, parser):
        parser.add_argument(
            "--now",
            type=str,
            default=None,
            help="Simulated ISO timestamp for current execution (e.g., '2026-09-21T06:00:00'). Defaults to current time in Africa/Harare.",
        )
        parser.add_argument(
            "--min-hours",
            type=float,
            default=48.0,
            help="Minimum hours before shift start to send reminder (default: 48.0).",
        )
        parser.add_argument(
            "--max-hours",
            type=float,
            default=84.0,
            help="Maximum hours before shift start to send reminder (default: 84.0).",
        )

    def handle(self, *args, **options):
        min_hours = options["min_hours"]
        max_hours = options["max_hours"]
        now_override = options.get("now")

        if now_override:
            parsed = datetime.fromisoformat(now_override)
            if timezone.is_naive(parsed):
                now_harare = timezone.make_aware(parsed, HARARE_TZ)
            else:
                now_harare = parsed.astimezone(HARARE_TZ)
        else:
            now_harare = timezone.now().astimezone(HARARE_TZ)

        # Date boundaries for pre-filtering candidate shifts
        min_start = now_harare + timedelta(hours=min_hours)
        max_start = now_harare + timedelta(hours=max_hours)

        min_date = min_start.date()
        max_date = max_start.date()

        # Authoritative Queryset:
        # - Roster status strictly APPROVED or ACTIVE
        # - Shift type strictly DAY or NIGHT
        # - Assignment type strictly NORMAL, EXAM, ESCORT, or RELIEF
        # - Guard assigned and active
        # - Date within potential reminder window
        candidate_shifts = (
            Shift.objects.filter(
                roster__status__in=[RosterStatus.APPROVED, RosterStatus.ACTIVE],
                shift_type__in=[ShiftType.DAY, ShiftType.NIGHT],
                assignment_type__in=[
                    AssignmentType.NORMAL,
                    AssignmentType.EXAM,
                    AssignmentType.ESCORT,
                    AssignmentType.RELIEF,
                ],
                guard__isnull=False,
                guard__is_active=True,
                date__gte=min_date,
                date__lte=max_date,
            )
            .select_related("station", "guard", "roster")
            .order_by("date", "start_time")
        )

        examined_count = 0
        created_count = 0
        already_notified_count = 0
        failed_count = 0

        for shift in candidate_shifts:
            examined_count += 1
            try:
                shift_time = shift.start_time or (
                    time(7, 0) if shift.shift_type == ShiftType.DAY else time(18, 0)
                )

                shift_start_dt = timezone.make_aware(
                    datetime.combine(shift.date, shift_time),
                    HARARE_TZ,
                )

                # Never notify shifts that have already started or are underway
                if shift_start_dt <= now_harare:
                    continue

                hours_until = (shift_start_dt - now_harare).total_seconds() / 3600.0

                # Strict reminder window enforcement
                if not (min_hours <= hours_until <= max_hours):
                    continue

                start_str = shift_time.strftime("%H:%M")
                if shift.end_time:
                    end_str = shift.end_time.strftime("%H:%M")
                else:
                    end_str = "18:00" if shift.shift_type == ShiftType.DAY else "07:00"

                # Overnight determination: NIGHT shift or start_time > end_time
                is_overnight = (
                    shift.shift_type == ShiftType.NIGHT
                    or (shift.start_time and shift.end_time and shift.start_time > shift.end_time)
                )

                if is_overnight:
                    duty_window_str = f"{start_str} to {end_str} next day"
                else:
                    duty_window_str = f"{start_str} to {end_str}"

                date_formatted = shift.date.strftime("%d %B %Y")
                location = shift.duty_location or shift.station.name

                duty_label = shift.get_shift_type_display()
                if shift.assignment_type != AssignmentType.NORMAL:
                    duty_label = f"{shift.get_assignment_type_display()} ({shift.get_shift_type_display()})"

                title = f"Upcoming Duty: {shift.station.name}"
                message = (
                    f"You are scheduled for {duty_label} at {location} "
                    f"on {date_formatted}, from {duty_window_str}."
                )

                dedup_key = f"UPCOMING_DUTY:{shift.id}:{shift.guard_id}"

                with transaction.atomic():
                    _, created = Notification.objects.get_or_create(
                        dedup_key=dedup_key,
                        defaults={
                            "user": shift.guard,
                            "title": title,
                            "message": message,
                            "notification_type": "UPCOMING_DUTY",
                        },
                    )

                if created:
                    created_count += 1
                else:
                    already_notified_count += 1

            except Exception as exc:
                failed_count += 1
                self.stderr.write(f"Error processing shift {shift.id}: {exc}")

        self.stdout.write(f"Upcoming duties examined: {examined_count}")
        self.stdout.write(f"Notifications created: {created_count}")
        self.stdout.write(f"Already notified: {already_notified_count}")
        if failed_count > 0:
            self.stdout.write(f"Failures: {failed_count}")
