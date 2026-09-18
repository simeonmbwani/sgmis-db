"""
Smart Security — Authoritative Demo Seeding Script
Populates the local database with the exact agreed operational model:
- 1 Station: Main Campus Security Post (MAIN_CAMPUS)
- 1 Administrator: admin / AdminPass123!
- 1 Supervisor: supervisor1 / SuperPass123!
- Exactly 6 Guards across 3 permanent pairs:
    * Pair 1 (order 1): guard1a (John Kamau) + guard1b (Mary Wanjiku)
    * Pair 2 (order 2): guard2a (Peter Omondi) + guard2b (Grace Achieng)
    * Pair 3 (order 3): guard3a (James Mwangi) + guard3b (Faith Chebet)
- 4 Checkpoints with QR codes for patrol verification
- Generates 1 full rotational roster cycle (12 days, 72 shifts) starting today
  using the authoritative Roster Engine (4 days ON, 8 days REST, Day/Night swap).
"""

import os
import sys
import django

# Set up Django environment
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "sgmis_backend.settings")
django.setup()

from django.contrib.auth import get_user_model
from django.utils import timezone
from apps.accounts.models import UserRole
from apps.stations.models import Station, GuardPair
from apps.shifts.models import Shift
from apps.shifts.services import generate_roster_for_station
from apps.patrols.models import Checkpoint
from apps.leave.models import LeaveBalance

UserModel = get_user_model()

def seed_authoritative_demo():
    print("\n" + "=" * 70)
    print("SMART SECURITY — SEEDING AUTHORITATIVE DEMO DATA")
    print("=" * 70)

    # 1. Create or get Station
    station, created = Station.objects.get_or_create(
        code="MAIN_CAMPUS",
        defaults={
            "name": "Main Campus Security Post",
            "address": "100 University Way, Main Campus",
            "latitude": -1.2921,
            "longitude": 36.8219,
            "geofence_radius_meters": 250.0,
            "is_active": True,
        }
    )
    action = "Created" if created else "Found existing"
    print(f"[+] {action} Station: {station.name} [{station.code}] (Radius: {station.geofence_radius_meters}m)")

    # 2. Create Administrator
    admin_user, _ = UserModel.objects.get_or_create(
        username="admin",
        defaults={
            "email": "admin@sgmis.local",
            "first_name": "System",
            "last_name": "Administrator",
            "employee_number": "ADM-001",
            "role": UserRole.ADMINISTRATOR,
            "rank": "Chief of Security Operations",
            "is_staff": True,
            "is_superuser": True,
            "is_active": True,
        }
    )
    admin_user.set_password("AdminPass123!")
    admin_user.role = UserRole.ADMINISTRATOR
    admin_user.is_staff = True
    admin_user.is_superuser = True
    admin_user.save()
    print("[+] Administrator: admin / AdminPass123! (ADM-001)")

    # 3. Create Supervisor
    supervisor_user, _ = UserModel.objects.get_or_create(
        username="supervisor1",
        defaults={
            "email": "supervisor1@sgmis.local",
            "first_name": "Marcus",
            "last_name": "Kiptoo",
            "employee_number": "SUP-101",
            "role": UserRole.SUPERVISOR,
            "rank": "Senior Field Operations Supervisor",
            "station": station,
            "is_active": True,
        }
    )
    supervisor_user.set_password("SuperPass123!")
    supervisor_user.station = station
    supervisor_user.role = UserRole.SUPERVISOR
    supervisor_user.save()
    print(f"[+] Supervisor: supervisor1 / SuperPass123! (Station: {station.name})")

    # 4. Create 6 Guards
    guards_spec = [
        ("guard1a", "SEC-001", "John", "Kamau", "Security Officer I", "GuardPass123!"),
        ("guard1b", "SEC-002", "Mary", "Wanjiku", "Security Officer I", "GuardPass123!"),
        ("guard2a", "SEC-003", "Peter", "Omondi", "Senior Patrol Officer", "GuardPass123!"),
        ("guard2b", "SEC-004", "Grace", "Achieng", "Security Officer II", "GuardPass123!"),
        ("guard3a", "SEC-005", "James", "Mwangi", "Security Officer I", "GuardPass123!"),
        ("guard3b", "SEC-006", "Faith", "Chebet", "Security Officer II", "GuardPass123!"),
    ]

    guards = []
    current_year = timezone.now().year
    for uname, emp_no, fname, lname, rank, pw in guards_spec:
        g, _ = UserModel.objects.get_or_create(
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
        g.set_password(pw)
        g.station = station
        g.role = UserRole.GUARD
        g.save()
        guards.append(g)

        # Initialize leave balance
        lb, _ = LeaveBalance.objects.get_or_create(
            guard=g,
            year=current_year,
            defaults={"annual_days": 21, "sick_days": 14}
        )
        print(f"    - Guard: {uname} ({fname} {lname}) [{emp_no}] - Password: {pw}")

    # 5. Create 3 Guard Pairs
    pair1, _ = GuardPair.objects.get_or_create(
        station=station,
        rotation_order=1,
        defaults={"guard_a": guards[0], "guard_b": guards[1], "is_active": True}
    )
    pair1.guard_a = guards[0]
    pair1.guard_b = guards[1]
    pair1.is_active = True
    pair1.save()

    pair2, _ = GuardPair.objects.get_or_create(
        station=station,
        rotation_order=2,
        defaults={"guard_a": guards[2], "guard_b": guards[3], "is_active": True}
    )
    pair2.guard_a = guards[2]
    pair2.guard_b = guards[3]
    pair2.is_active = True
    pair2.save()

    pair3, _ = GuardPair.objects.get_or_create(
        station=station,
        rotation_order=3,
        defaults={"guard_a": guards[4], "guard_b": guards[5], "is_active": True}
    )
    pair3.guard_a = guards[4]
    pair3.guard_b = guards[5]
    pair3.is_active = True
    pair3.save()

    print(f"[+] Pair 1: {guards[0].get_full_name()} & {guards[1].get_full_name()} (Order 1)")
    print(f"[+] Pair 2: {guards[2].get_full_name()} & {guards[3].get_full_name()} (Order 2)")
    print(f"[+] Pair 3: {guards[4].get_full_name()} & {guards[5].get_full_name()} (Order 3)")

    # 6. Checkpoints
    checkpoints_spec = [
        ("CP-01", "Main Gate Vehicle Access & Barrier Control", 1, station.latitude, station.longitude),
        ("CP-02", "Admin Block & Executive Reception", 2, station.latitude + 0.0005, station.longitude + 0.0005),
        ("CP-03", "IT Server Room & Backup Generator Vault", 3, station.latitude - 0.0005, station.longitude - 0.0005),
        ("CP-04", "Perimeter Fence - East Gate Pedestrian Post", 4, station.latitude + 0.0008, station.longitude - 0.0006),
    ]

    for code, name, order, lat, lon in checkpoints_spec:
        cp, _ = Checkpoint.objects.get_or_create(
            station=station,
            code=code,
            defaults={
                "name": name,
                "qr_code": f"SGMIS-{station.code}-{code}",
                "order": order,
                "latitude": lat,
                "longitude": lon,
            }
        )
        print(f"[+] Checkpoint: [{code}] {name} (QR: SGMIS-{station.code}-{code})")

    # 7. Generate 12-Day Authoritative Rotational Roster
    today = timezone.localdate()
    # Remove existing shifts for station from today to avoid duplicates
    Shift.objects.filter(station=station, date__gte=today).delete()
    shifts = generate_roster_for_station(station, today, cycle_days=12)

    day_count = sum(1 for s in shifts if s.shift_type == "DAY" and s.assignment_type != "TIME_OFF")
    night_count = sum(1 for s in shifts if s.shift_type == "NIGHT" and s.assignment_type != "TIME_OFF")
    off_count = sum(1 for s in shifts if s.assignment_type == "TIME_OFF" or s.shift_type in ["OFF", "REST"])

    print(f"[+] Generated {len(shifts)} Shifts over 12 days starting {today}:")
    print(f"    - Day Duty Shifts: {day_count}")
    print(f"    - Night Duty Shifts: {night_count}")
    print(f"    - Scheduled Rest (TIME_OFF) Shifts: {off_count}")
    print("=" * 70)
    print("AUTHORITATIVE DEMO READY FOR PHYSICAL ANDROID APP TESTING!")
    print("=" * 70 + "\n")

if __name__ == "__main__":
    seed_authoritative_demo()
