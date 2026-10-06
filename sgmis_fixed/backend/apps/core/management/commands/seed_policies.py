from django.core.management.base import BaseCommand
from apps.core.models import OrganizationPolicy

DEFAULT_POLICIES = [
    {
        "category": OrganizationPolicy.Category.LEAVE,
        "title": "Annual, Vacation & Casual Leave Policy",
        "summary": "Monthly accrual rates, caps, forfeiture rules and application guidelines.",
        "content": (
            "1. Casual Leave: Accrues at 1.0 day per completed calendar month up to a maximum of 12 days per annual cycle. "
            "Unused casual leave cannot be carried over and is forfeited at the end of the 12-month cycle.\n"
            "2. Vacation Leave: Accrues at 2.5 days per completed month. Cumulative balance is capped at a strict maximum of 90 days. "
            "Leave accrued in excess of 90 days is subject to mandatory scheduling or forfeiture.\n"
            "3. Sick Leave: Standard entitlement up to 14 days per year. Any sick leave request exceeding 2 consecutive days requires a certified medical practitioner report.\n"
            "4. Special & Compassionate Leave: Requires documentary proof and operational approval by the Station Supervisor and Administrator."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.COMPENSATION,
        "title": "Public Holiday & Duty Interruption Compensation",
        "summary": "Compensation entitlements for worked holidays and emergency duty call-ins.",
        "content": (
            "1. Public Holiday Duty: Security officers who work an active, verified shift on a gazetted public holiday earn 2.0 compensatory leave days in the compensation ledger.\n"
            "2. Leave Interruption / Duty Override: When operational exigencies require a guard on approved leave or scheduled off-day to report for duty, a formal Duty Override is recorded. "
            "Guards earn 1:1 compensatory entitlement for each interrupted day worked.\n"
            "3. Redemption: Compensatory days can be redeemed as Compensatory Leave via standard leave applications without reducing vacation balances or triggering casual cycle caps."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.ROSTER,
        "title": "Standard Roster Rotation & Pair Governance",
        "summary": "4-day rotation blocks, standard pairings, and coverage rules.",
        "content": (
            "1. Standard Cycle: Deployment operates on a structured 4-day block rotation cycle (Days 1–4, 5–8, 9–12).\n"
            "2. Station Pairs: Each deployment post consists of paired security officers who rotate alternating Day (06:00–18:00) and Night (18:00–06:00) shifts.\n"
            "3. Maximum Deployment: Exactly two active guards (1 Day, 1 Night) may be scheduled per pair per date. Duplicate active shifts for the same post are strictly prohibited by server validation.\n"
            "4. Station Isolation: Security officers are bound to their assigned post. Cross-station deployments require formal relief or temporary reassignment authorization."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.PATROL,
        "title": "Station Patrol Execution & Geofence Verification",
        "summary": "Checkpoint sequence, hardware NFC, QR tokens, and GPS proximity criteria.",
        "content": (
            "1. Supervisor Assignment: Operational patrol rounds must be assigned by the Station Supervisor before guard execution.\n"
            "2. Checkpoint Sequence: Checkpoints must be inspected in strict numerical sequence. Out-of-order scans are flagged as sequence anomalies.\n"
            "3. Physical Verification: Each checkpoint requires registered hardware NFC tag UID match, cryptographic QR token, or verified high-accuracy GPS proximity (<100m).\n"
            "4. Duration & Intervals: Patrol rounds have a minimum duration of 60 seconds. Minimum transit intervals between consecutive checkpoints are strictly enforced.\n"
            "5. GPS Tracking: Active device GPS coordinates must be recorded during patrol rounds. Finishing patrols without GPS verification is disallowed."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.ATTENDANCE,
        "title": "Duty Reporting, Geofencing & Clock-In Protocols",
        "summary": "Reporting windows, geofence radius, lateness thresholds, and early exit authorization.",
        "content": (
            "1. Reporting Window: Clock-in opens 30 minutes prior to scheduled shift start. Off-duty guards are locked out from clocking in outside this window.\n"
            "2. Geofence Boundary: Clock-in requires device GPS location verified within the authorized station boundary (typically 200m radius).\n"
            "3. Lateness: Arrival after 15 minutes is marked late. Arrival 60+ minutes late constitutes serious lateness requiring a formal Late Arrival Incident case report before clock-in is permitted.\n"
            "4. Early Clock-Out: Departure prior to shift completion requires a cryptographically generated 6-digit OTP authorized by the Station Supervisor or Administrator."
        ),
        "version": "1.0",
    },
    {
        "category": OrganizationPolicy.Category.DUTY,
        "title": "Operational Duty Overrides & Relief Deployment",
        "summary": "Mandatory justification, audit logging, and reconciliation criteria.",
        "content": (
            "1. Zero Silent Edits: Operational records (shifts, attendance, patrols, occurrence book) cannot be deleted or silently modified.\n"
            "2. Record Adjustments: Reconciliations to employee details, balances, or shift records require documented physical record citations and administrative approval.\n"
            "3. Historical Preservation: Past shifts and completed attendance records remain immutable in administrative history."
        ),
        "version": "1.0",
    },
]

class Command(BaseCommand):
    help = "Seed initial authoritative organization policies if not present"

    def handle(self, *args, **options):
        created_count = 0
        for item in DEFAULT_POLICIES:
            obj, created = OrganizationPolicy.objects.get_or_create(
                category=item["category"],
                title=item["title"],
                defaults={
                    "summary": item["summary"],
                    "content": item["content"],
                    "version": item["version"],
                    "is_active": True,
                },
            )
            if created:
                created_count += 1
        self.stdout.write(self.style.SUCCESS(f"Seeded {created_count} organization policies (total {OrganizationPolicy.objects.count()})."))
