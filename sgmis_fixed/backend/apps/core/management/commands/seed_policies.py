from django.core.management.base import BaseCommand
from apps.core.models import OrganizationPolicy

DEFAULT_POLICIES = [
    {
        "category": OrganizationPolicy.Category.LEAVE,
        "policy_key": "LEAVE_MANAGEMENT",
        "setting_value": "90",
        "title": "Annual, Vacation, Casual & Sick Leave Governance",
        "summary": "Monthly accrual rates, 90-day vacation ceiling, sick leave certification and forfeiture rules.",
        "content": (
            "1. Casual Leave: Accrues at 1.0 day per completed calendar month up to a maximum of 12 days per annual cycle. "
            "Unused casual leave cannot be carried over and is forfeited at the end of the 12-month cycle.\n"
            "2. Vacation Leave: Accrues at 2.5 days per completed month. Cumulative balance is capped at a strict maximum of 90 days. "
            "Leave accrued in excess of 90 days is subject to mandatory scheduling or administrative forfeiture.\n"
            "3. Sick Leave: Standard entitlement up to 14 days per calendar year. Any sick leave request exceeding 2 consecutive days "
            "requires a certified medical practitioner report.\n"
            "4. Special & Compassionate Leave: Requires physical documentary verification and approval by the Station Supervisor and Administrator."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.DUTY,
        "policy_key": "DUTY_OVERRIDE_GOVERNANCE",
        "setting_value": "1.0",
        "title": "Operational Duty Reassignment & Relief Deployment Policy",
        "summary": "Zero silent edits, authoritative overrides, relief coverage, and audit trails.",
        "content": (
            "1. Zero Silent Edits: Operational shift, attendance, patrol, and incident records cannot be deleted or silently modified.\n"
            "2. Authorized Duty Overrides: Supervisors and Administrators may override scheduled off-days or interrupt approved leave "
            "when operational emergencies demand immediate relief coverage.\n"
            "3. Authoritative Reassignment: Any reassignment instantly transitions the officer to REASSIGNED / ON DUTY and authorizes "
            "clock-in within station geofence.\n"
            "4. Historical Preservation: Past shifts and completed attendance records remain immutable in administrative history."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.ROSTER,
        "policy_key": "ROSTER_ROTATION",
        "setting_value": "4",
        "title": "Station Roster Rotation, Pairing & Shift Limits",
        "summary": "4-day rotation blocks, standard pairings, 2-guard station limits, alternation.",
        "content": (
            "1. Standard Cycle: Deployment operates on a structured 4-day block rotation cycle (Days 1–4, 5–8, 9–12).\n"
            "2. Station Pairs: Each deployment post consists of paired security officers who rotate alternating Day (07:00–18:00) "
            "and Night (18:00–07:00) shifts.\n"
            "3. Maximum Deployment: Exactly two active guards (1 Day, 1 Night) may be scheduled per pair per date. Duplicate active shifts "
            "for the same post are strictly prohibited by server validation.\n"
            "4. Station Isolation: Security officers are bound to their assigned post. Cross-station deployments require formal relief authorization."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.PATROL,
        "policy_key": "PATROL_SCAN_INTERVAL",
        "setting_value": "60",
        "title": "Station Perimeter Patrol & Checkpoint Verification SOP",
        "summary": "Strict sequence order, hardware NFC UID, QR tokens, GPS proximity (<100m), 60s minimum duration.",
        "content": (
            "1. Supervisor Assignment: Operational patrol rounds must be assigned by the Station Supervisor before guard execution.\n"
            "2. Checkpoint Sequence: Checkpoints must be inspected in strict numerical route sequence. Out-of-order scans are flagged as sequence anomalies.\n"
            "3. Physical Verification: Each checkpoint requires registered hardware NFC tag UID match, cryptographic QR token, or verified high-accuracy GPS proximity (<100m).\n"
            "4. Minimum Duration & Intervals: Patrol rounds have a minimum duration of 60 seconds (PATROL_SCAN_INTERVAL=60). Minimum transit intervals between consecutive checkpoints are strictly enforced.\n"
            "5. GPS Proximity: Checkpoint inspection requires GPS proximity within 100 meters (PATROL_PROXIMITY_RADIUS=100.0m).\n"
            "6. GPS Tracking: Active device GPS coordinates must be recorded at completion. Finishing patrols without GPS verification is disallowed."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.ATTENDANCE,
        "policy_key": "ATTENDANCE_REPORTING",
        "setting_value": "30",
        "title": "Duty Reporting, Geofencing & Clock-In Protocols",
        "summary": "30-minute reporting window, station geofence radius (200m), lateness thresholds, OTP early exit.",
        "content": (
            "1. Reporting Window: Clock-in opens 30 minutes prior to scheduled shift start. Off-duty guards are locked out from clocking in outside this window.\n"
            "2. Geofence Boundary: Clock-in requires device GPS location verified within the authorized station boundary (STATION_GEOFENCE_RADIUS=200.0m).\n"
            "3. Lateness: Arrival after 15 minutes is marked late. Arrival 60+ minutes late constitutes serious lateness requiring a formal Late Arrival Incident case report before clock-in is permitted.\n"
            "4. Early Clock-Out: Departure prior to shift completion requires a cryptographically generated 6-digit OTP authorized by the Station Supervisor or Administrator."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.COMPENSATION,
        "policy_key": "LEAVE_INTERRUPTION_COMPENSATION",
        "setting_value": "1.0",
        "title": "Public Holiday & Duty Interruption Compensation Policy",
        "summary": "2.0 days public holiday credit, 1.0 day leave interruption credit, ledger accounting.",
        "content": (
            "1. Public Holiday Duty: Security officers who work an active, verified shift on a gazetted public holiday earn 2.0 compensatory leave days in the compensation ledger.\n"
            "2. Leave Interruption / Duty Override: When operational exigencies require a guard on approved leave or scheduled off-day to report for duty, a formal Duty Override is recorded. "
            "Guards earn 1.0 compensatory day per interrupted date worked (LEAVE_INTERRUPTION_COMPENSATION=1.0).\n"
            "3. Redemption: Compensatory days can be redeemed as Compensatory Leave via standard leave applications without reducing vacation balances or triggering casual cycle caps."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.GEOFENCE,
        "policy_key": "STATION_GEOFENCE_RADIUS",
        "setting_value": "200.0",
        "title": "Station Perimeter & Geofence Boundary Standards",
        "summary": "200m station perimeter, high-accuracy GPS fix, anti-spoofing criteria.",
        "content": (
            "1. Perimeter Boundary: The authoritative station operating perimeter is set to 200 meters from registered station GPS coordinates (STATION_GEOFENCE_RADIUS=200.0m).\n"
            "2. Accuracy Tolerance: Location accuracy readings exceeding 100m error are rejected for geofence verification.\n"
            "3. Anti-Spoofing: Mock location providers and GPS simulation tools trigger automated security alerts and prevent attendance / patrol verification."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.SECURITY,
        "policy_key": "INACTIVITY_AUTO_LOCK",
        "setting_value": "180",
        "title": "Access Control, Session Inactivity & Credential Governance",
        "summary": "3-minute inactivity auto-lock (180s), multi-factor authentication, role privilege isolation.",
        "content": (
            "1. Device Inactivity Lock: Security terminal sessions automatically lock after 3 minutes (180 seconds, INACTIVITY_AUTO_LOCK=180) of inactivity to prevent unauthorized terminal tampering.\n"
            "2. Credential Security: Passwords must be hashed using PBKDF2/Argon2. Shared credentials or proxy attendance clock-ins are severe disciplinary violations.\n"
            "3. Station Isolation & Least Privilege: Supervisors are restricted to their assigned station. Superusers exercise oversight with mandatory audit logging for all record adjustments."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.GENERAL,
        "policy_key": "GENERAL_OPERATIONS",
        "setting_value": "STANDARD",
        "title": "General Security Operations & Emergency SOS Protocols",
        "summary": "Immediate panic broadcasts, chain of custody, incident escalation, occurrence book.",
        "content": (
            "1. Emergency SOS: Activating the panic trigger initiates an immediate high-priority alert broadcast across the station command network.\n"
            "2. Occurrence Book: All operational handovers, visitor entries, weapon inspections, and incidents must be logged contemporaneously.\n"
            "3. Chain of Custody: Handover inspections require physical signature and mutual acknowledgement between incoming and outgoing guards."
        ),
        "version": "1.0",
    },
]

class Command(BaseCommand):
    help = "Seed initial authoritative organization policies if not present"

    def handle(self, *args, **options):
        count = 0
        for item in DEFAULT_POLICIES:
            obj, created = OrganizationPolicy.objects.update_or_create(
                category=item["category"],
                defaults={
                    "policy_key": item.get("policy_key", ""),
                    "setting_value": item.get("setting_value", ""),
                    "title": item["title"],
                    "summary": item["summary"],
                    "content": item["content"],
                    "version": item["version"],
                    "is_active": True,
                },
            )
            count += 1
        self.stdout.write(self.style.SUCCESS(f"Synchronized {count} organization policies across all categories (total {OrganizationPolicy.objects.count()})."))
