import datetime
from django.core.management.base import BaseCommand
from django.contrib.auth import get_user_model
from django.utils import timezone
from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift, ShiftType, DutyRosterCycle
from apps.shifts.services import generate_roster_for_station
from apps.patrols.models import Checkpoint
from apps.leave.models import LeaveBalance

class Command(BaseCommand):
    help = "DEVELOPMENT ONLY: Seeds a clean operational testing environment with 1 station, 6 guards, 3 pairs, roster shifts, checkpoints, and a supervisor."

    def handle(self, *args, **options):
        UserModel = get_user_model()
        self.stdout.write(self.style.WARNING("=== SEEDING DEVELOPMENT ENVIRONMENT (NOT FOR PRODUCTION) ==="))

        dev_password = "DevPassword123!"

        # 1. Create Station
        station, _ = Station.objects.get_or_create(
            code="STN-ALPHA",
            defaults={
                "name": "Alpha Central Post",
                "address": "100 Security Boulevard, Sector 4",
                "latitude": 51.5074,
                "longitude": -0.1278,
                "geofence_radius_meters": 250.0,
                "is_active": True,
            }
        )
        self.stdout.write(f"Station: {station.name} [{station.code}]")

        # 2. Create Supervisor
        supervisor, _ = UserModel.objects.get_or_create(
            username="supervisor_alpha",
            defaults={
                "email": "supervisor.alpha@sgmis.local",
                "first_name": "Marcus",
                "last_name": "Vance",
                "employee_number": "SUP-2001",
                "role": UserRole.SUPERVISOR,
                "rank": "Station Field Supervisor",
                "station": station,
                "is_active": True,
            }
        )
        supervisor.set_password(dev_password)
        supervisor.station = station
        supervisor.role = UserRole.SUPERVISOR
        supervisor.save()

        # 3. Create 6 Guards
        guard_data = [
            ("guard_a", "SEC-1001", "Aaron", "Kiprono", "Senior Patrol Officer"),
            ("guard_b", "SEC-1002", "Brian", "Ochieng", "Security Officer II"),
            ("guard_c", "SEC-1003", "Caleb", "Mutua", "Security Officer I"),
            ("guard_d", "SEC-1004", "David", "Kariuki", "Security Officer I"),
            ("guard_e", "SEC-1005", "Evans", "Wekesa", "Security Officer II"),
            ("guard_f", "SEC-1006", "Felix", "Omondi", "Patrol Officer"),
        ]

        guards = []
        for uname, emp_no, fname, lname, rank in guard_data:
            guard, _ = UserModel.objects.get_or_create(
                username=uname,
                defaults={
                    "email": f"{uname}@sgmis.local",
                    "employee_number": emp_no,
                    "first_name": fname,
                    "last_name": lname,
                    "role": UserRole.GUARD,
                    "rank": rank,
                    "station": station,
                    "is_active": True,
                }
            )
            guard.set_password(dev_password)
            guard.station = station
            guard.role = UserRole.GUARD
            guard.save()
            guards.append(guard)

            # Initialize leave balance
            LeaveBalance.objects.get_or_create(
                guard=guard,
                year=timezone.now().year,
                defaults={"annual_days": 21, "sick_days": 14}
            )

        # 4. Create Guard Pairs
        pair1, _ = GuardPair.objects.get_or_create(
            station=station,
            rotation_order=1,
            defaults={"guard_a": guards[0], "guard_b": guards[1], "is_active": True}
        )
        pair2, _ = GuardPair.objects.get_or_create(
            station=station,
            rotation_order=2,
            defaults={"guard_a": guards[2], "guard_b": guards[3], "is_active": True}
        )
        pair3, _ = GuardPair.objects.get_or_create(
            station=station,
            rotation_order=3,
            defaults={"guard_a": guards[4], "guard_b": guards[5], "is_active": True}
        )

        # 5. Create Checkpoints
        checkpoints_data = [
            ("CP-01", "Main Gate & Barrier Control", 1),
            ("CP-02", "Perimeter Fence - North Sector", 2),
            ("CP-03", "Server Room & Power Vault", 3),
            ("CP-04", "Loading Dock & Emergency Exit", 4),
        ]
        for cp_code, cp_name, order in checkpoints_data:
            Checkpoint.objects.get_or_create(
                station=station,
                code=cp_code,
                defaults={
                    "name": cp_name,
                    "qr_code": f"SGMIS-{station.code}-{cp_code}",
                    "order": order,
                    "latitude": station.latitude,
                    "longitude": station.longitude,
                }
            )

        # 6. Generate Duty Roster Starting Today
        today = timezone.now().date()
        created_shifts = generate_roster_for_station(station, today, cycle_days=12)

        self.stdout.write(self.style.SUCCESS(
            f"Successfully seeded development environment:\n"
            f"- Station: {station.name}\n"
            f"- Supervisor: {supervisor.username} ({supervisor.employee_number})\n"
            f"- Guards: {len(guards)} created\n"
            f"- Pairs: 3 active rotation pairs\n"
            f"- Checkpoints: 4 station checkpoints\n"
            f"- Shifts: {len(created_shifts)} real roster shifts generated starting today ({today})\n"
            f"- Development Password for all test accounts: {dev_password}"
        ))
