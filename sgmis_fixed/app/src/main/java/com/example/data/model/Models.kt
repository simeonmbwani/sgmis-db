package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class PaginatedResponse<T>(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<T> = emptyList()
)

enum class AppRole(val serverKey: String) {
    GUARD("GUARD"),
    SUPERVISOR("SUPERVISOR"),
    ADMINISTRATOR("ADMINISTRATOR");

    companion object {
        fun fromString(role: String?): AppRole {
            return when (role?.trim()?.uppercase()) {
                "ADMIN", "ADMINISTRATOR", "SUPERUSER" -> ADMINISTRATOR
                "SUPERVISOR", "STATION_SUPERVISOR", "STATION SUPERVISOR" -> SUPERVISOR
                else -> GUARD
            }
        }
    }
}

enum class GuardDutyState {
    OFF_DUTY,          // No shift today, or shift already completed/clocked out
    ELIGIBLE_FOR_DUTY, // Scheduled shift today, within report window (not yet clocked in)
    ON_DUTY,           // Formally clocked in on authoritative shift, actively on post
    TIME_OFF,          // Scheduled off-duty day
    ON_LEAVE,          // Approved leave
    EARLY_EXIT_PENDING; // Early departure requested/pending

    val isOnDuty: Boolean get() = this == ON_DUTY
    val isOffDuty: Boolean get() = this == OFF_DUTY || this == TIME_OFF || this == ON_LEAVE
    val isEligibleForDuty: Boolean get() = this == ELIGIBLE_FOR_DUTY
    val isOnLeave: Boolean get() = this == ON_LEAVE

    companion object {
        fun fromShift(shift: Shift?): GuardDutyState {
            if (shift == null) return OFF_DUTY
            val raw = shift.rawDutyState?.trim()?.uppercase()
            if (raw == "ON_LEAVE") return ON_LEAVE
            if (raw == "TIME_OFF") return TIME_OFF
            if (raw == "ON_DUTY") return ON_DUTY
            if (raw == "OFF_DUTY") return OFF_DUTY
            if (raw == "ELIGIBLE_FOR_DUTY") return ELIGIBLE_FOR_DUTY
            if (raw == "EARLY_EXIT_PENDING") return EARLY_EXIT_PENDING

            val shiftTypeUpper = shift.shiftType.uppercase()
            val assignmentTypeUpper = shift.assignmentType.uppercase()
            if (shiftTypeUpper == "OFF" || assignmentTypeUpper == "TIME_OFF") {
                return OFF_DUTY
            }
            return when (shift.attendanceStatus.trim().uppercase()) {
                "CLOCKED_IN" -> ON_DUTY
                "NOT_CLOCKED_IN" -> ELIGIBLE_FOR_DUTY
                "CLOCKED_OUT", "OFF_DUTY" -> OFF_DUTY
                "ON_LEAVE" -> ON_LEAVE
                "TIME_OFF" -> TIME_OFF
                else -> OFF_DUTY
            }
        }
    }
}

@JsonClass(generateAdapter = true)
data class User(
    val id: String,
    val username: String,
    val email: String? = null,
    @Json(name = "employee_number") val employeeNumber: String? = null,
    @Json(name = "first_name") val firstName: String? = null,
    @Json(name = "last_name") val lastName: String? = null,
    val role: String,
    @Json(name = "full_name") val fullName: String? = null,
    val rank: String? = null,
    val station: String? = null,
    @Json(name = "station_name") val stationName: String? = null,
    @Json(name = "phone_number") val phoneNumber: String? = null,
    @Json(name = "profile_photo") val profilePhoto: String? = null,
    @Json(name = "is_active") val isActive: Boolean = true
) {
    val appRole: AppRole get() = AppRole.fromString(role)
    val isGuard: Boolean get() = appRole == AppRole.GUARD
    val isSupervisor: Boolean get() = appRole == AppRole.SUPERVISOR
    val isAdmin: Boolean get() = appRole == AppRole.ADMINISTRATOR
    val isSupervisorOrAdmin: Boolean get() = appRole != AppRole.GUARD
}

@JsonClass(generateAdapter = true)
data class AuthResponse(
    val access: String,
    val refresh: String,
    val user: User
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val identifier: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class Shift(
    val id: String,
    val station: String,
    @Json(name = "station_name") val stationName: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String,
    @Json(name = "employee_number") val employeeNumber: String? = null,
    val date: String,
    @Json(name = "start_time") val startTime: String,
    @Json(name = "end_time") val endTime: String,
    @Json(name = "shift_type") val shiftType: String,
    @Json(name = "assignment_type") val assignmentType: String = "NORMAL",
    @Json(name = "duty_location") val dutyLocation: String = "Main Campus",
    @Json(name = "examination_period") val examinationPeriod: String? = null,
    val pair: String? = null,
    val partner: String? = null,
    @Json(name = "partner_name") val partnerName: String? = null,
    @Json(name = "partner_employee_number") val partnerEmployeeNumber: String? = null,
    @Json(name = "is_override") val isOverride: Boolean = false,
    @Json(name = "override_reason") val overrideReason: String? = null,
    @Json(name = "attendance_status") val attendanceStatus: String = "NOT_CLOCKED_IN",
    @Json(name = "duty_state") val rawDutyState: String? = null,
    @Json(name = "leave_type") val leaveType: String? = null,
    @Json(name = "late_report_required") val lateReportRequired: Boolean = false,
    @Json(name = "is_serious_late") val isSeriousLate: Boolean = false,
    @Json(name = "is_late") val isLate: Boolean = false
) {
    val dutyState: GuardDutyState get() = GuardDutyState.fromShift(this)
    val isOnDuty: Boolean get() = dutyState == GuardDutyState.ON_DUTY
    val isOffDuty: Boolean get() = dutyState.isOffDuty
    val isEligibleForDuty: Boolean get() = dutyState == GuardDutyState.ELIGIBLE_FOR_DUTY
    val isOnLeave: Boolean get() = dutyState == GuardDutyState.ON_LEAVE
}

@JsonClass(generateAdapter = true)
data class Attendance(
    val id: String,
    val shift: String,
    @Json(name = "shift_date") val shiftDate: String,
    @Json(name = "shift_type") val shiftType: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String,
    @Json(name = "guard_employee_number") val guardEmployeeNumber: String? = null,
    @Json(name = "station_name") val stationName: String,
    @Json(name = "clock_in") val clockIn: String? = null,
    @Json(name = "clock_out") val clockOut: String? = null,
    @Json(name = "clock_in_gps") val clockInGps: String? = null,
    @Json(name = "clock_out_gps") val clockOutGps: String? = null,
    @Json(name = "is_late") val isLate: Boolean = false,
    @Json(name = "is_serious_late") val isSeriousLate: Boolean = false,
    @Json(name = "late_reason") val lateReason: String? = null,
    @Json(name = "escalation_notified") val escalationNotified: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ClockInRequest(
    @Json(name = "shift_id") val shiftId: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "late_reason") val lateReason: String? = null,
    @Json(name = "case_number") val caseNumber: String? = null
)

@JsonClass(generateAdapter = true)
data class ClockOutRequest(
    @Json(name = "shift_id") val shiftId: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "supervisor_username") val supervisorUsername: String? = null,
    @Json(name = "supervisor_password") val supervisorPassword: String? = null,
    @Json(name = "override_reason") val overrideReason: String? = null,
    @Json(name = "otp_code") val otpCode: String? = null
)

@JsonClass(generateAdapter = true)
data class ShiftHandover(
    val id: String,
    @Json(name = "outgoing_shift") val outgoingShift: String,
    @Json(name = "outgoing_shift_details") val outgoingShiftDetails: String? = null,
    @Json(name = "outgoing_guard") val outgoingGuard: String,
    @Json(name = "outgoing_guard_name") val outgoingGuardName: String,
    @Json(name = "incoming_guard") val incomingGuard: String,
    @Json(name = "incoming_guard_name") val incomingGuardName: String,
    val station: String,
    @Json(name = "station_name") val stationName: String,
    @Json(name = "occurrence_summary") val occurrenceSummary: String,
    @Json(name = "equipment_issued") val equipmentIssued: String,
    @Json(name = "keys_handed_over") val keysHandedOver: String,
    @Json(name = "pending_issues") val pendingIssues: String,
    @Json(name = "outgoing_signed") val outgoingSigned: Boolean,
    @Json(name = "incoming_accepted") val incomingAccepted: Boolean,
    @Json(name = "incoming_accepted_at") val incomingAcceptedAt: String? = null,
    @Json(name = "is_rejected") val isRejected: Boolean = false,
    @Json(name = "created_at") val createdAt: String
)

val ShiftHandover.isHandoverRejected: Boolean
    get() = isRejected || pendingIssues.contains("[REJECTED", ignoreCase = true)

@JsonClass(generateAdapter = true)
data class CreateHandoverRequest(
    @Json(name = "outgoing_shift") val outgoingShift: String,
    @Json(name = "occurrence_summary") val occurrenceSummary: String,
    @Json(name = "equipment_issued") val equipmentIssued: String = "All equipment accounted for.",
    @Json(name = "keys_handed_over") val keysHandedOver: String = "Station keys transferred.",
    @Json(name = "pending_issues") val pendingIssues: String = "None.",
    @Json(name = "supervisor_emergency_override") val supervisorEmergencyOverride: Boolean = false
)

@JsonClass(generateAdapter = true)
data class RejectHandoverRequest(
    val reason: String = "Handover disputed."
)

@JsonClass(generateAdapter = true)
data class OBAmendment(
    val id: String,
    val entry: String,
    @Json(name = "amended_by") val amendedBy: String,
    @Json(name = "amended_by_name") val amendedByName: String? = null,
    val reason: String,
    @Json(name = "original_text_snapshot") val originalTextSnapshot: String,
    @Json(name = "amended_text") val amendedText: String,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class AmendOBRequest(
    val reason: String,
    @Json(name = "amended_text") val amendedText: String
)

@JsonClass(generateAdapter = true)
data class AmendOBResponse(
    val message: String,
    val amendment: OBAmendment
)

@JsonClass(generateAdapter = true)
data class OccurrenceBookEntry(
    val id: String,
    @Json(name = "entry_number") val entryNumber: String,
    val station: String,
    @Json(name = "station_name") val stationName: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String,
    @Json(name = "guard_employee_number") val guardEmployeeNumber: String? = null,
    val category: String,
    @Json(name = "category_display") val categoryDisplay: String? = null,
    @Json(name = "occurrence_text") val occurrenceText: String,
    @Json(name = "check_record") val checkRecord: String? = null,
    @Json(name = "cross_reference") val crossReference: String? = null,
    val amendments: List<OBAmendment> = emptyList(),
    @Json(name = "is_amendable") val isAmendable: Boolean = false,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class CreateOBEntryRequest(
    val category: String,
    @Json(name = "occurrence_text") val occurrenceText: String,
    @Json(name = "check_record") val checkRecord: String = "Verified & Logged",
    @Json(name = "cross_reference") val crossReference: String? = null,
    val station: String? = null
)

@JsonClass(generateAdapter = true)
data class IncidentAmendment(
    val id: String,
    val incident: String,
    @Json(name = "amended_by") val amendedBy: String,
    @Json(name = "amended_by_name") val amendedByName: String? = null,
    val reason: String,
    @Json(name = "original_description_snapshot") val originalDescriptionSnapshot: String,
    @Json(name = "amended_description") val amendedDescription: String,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class AmendIncidentRequest(
    val reason: String,
    @Json(name = "amended_description") val amendedDescription: String
)

@JsonClass(generateAdapter = true)
data class AmendIncidentResponse(
    val message: String,
    val amendment: IncidentAmendment
)

@JsonClass(generateAdapter = true)
data class IncidentReport(
    val id: String,
    val station: String,
    @Json(name = "station_name") val stationName: String,
    @Json(name = "reporting_guard") val reportingGuard: String,
    @Json(name = "reporting_guard_name") val reportingGuardName: String,
    @Json(name = "reporting_guard_employee_number") val reportingGuardEmployeeNumber: String? = null,
    val priority: String,
    @Json(name = "priority_display") val priorityDisplay: String? = null,
    val title: String,
    val description: String,
    val location: String,
    val status: String,
    @Json(name = "status_display") val statusDisplay: String? = null,
    @Json(name = "acknowledged_by") val acknowledgedBy: String? = null,
    @Json(name = "acknowledged_by_name") val acknowledgedByName: String? = null,
    @Json(name = "resolution_notes") val resolutionNotes: String? = null,
    val amendments: List<IncidentAmendment> = emptyList(),
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class CreateIncidentRequest(
    val priority: String,
    val title: String,
    val description: String,
    val location: String,
    val station: String? = null
)

@JsonClass(generateAdapter = true)
data class Checkpoint(
    val id: String,
    val station: String,
    @Json(name = "station_name") val stationName: String,
    val name: String,
    val code: String,
    @Json(name = "qr_code") val qrCode: String,
    val latitude: Double,
    val longitude: Double,
    val order: Int,
    @Json(name = "is_active") val isActive: Boolean = true
)

@JsonClass(generateAdapter = true)
data class PatrolLog(
    val id: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String,
    val station: String,
    @Json(name = "station_name") val stationName: String,
    @Json(name = "start_time") val startTime: String,
    @Json(name = "end_time") val endTime: String? = null,
    val status: String,
    val notes: String? = null,
    @Json(name = "scans_count") val scansCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class CheckpointScanRequest(
    val checkpoint: String,
    @Json(name = "gps_coords") val gpsCoords: String = "",
    val notes: String = "Checkpoint verified secure."
)

@JsonClass(generateAdapter = true)
data class LeaveBalance(
    val id: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String? = null,
    val year: Int = 2026,
    @Json(name = "annual_days") val annualDays: Int = 21,
    @Json(name = "sick_days") val sickDays: Int = 14,
    @Json(name = "used_annual") val usedAnnual: Int = 0,
    @Json(name = "used_sick") val usedSick: Int = 0,
    @Json(name = "remaining_annual") val remainingAnnual: Int = 21,
    @Json(name = "remaining_sick") val remainingSick: Int = 14,
    @Json(name = "casual_days") val casualDays: Double = 0.0,
    @Json(name = "vacation_days") val vacationDays: Double = 0.0,
    @Json(name = "used_casual") val usedCasual: Double = 0.0,
    @Json(name = "used_vacation") val usedVacation: Double = 0.0,
    @Json(name = "casual_accrual_rate") val casualAccrualRate: Double = 1.0,
    @Json(name = "vacation_accrual_rate") val vacationAccrualRate: Double = 2.5,
    @Json(name = "vacation_cap") val vacationCap: Double = 90.0,
    @Json(name = "remaining_casual") val remainingCasual: Double = 0.0,
    @Json(name = "remaining_vacation") val remainingVacation: Double = 0.0,
    @Json(name = "last_accrual_date") val lastAccrualDate: String? = null,
    @Json(name = "casual_cycle_start") val casualCycleStart: String? = null,
    @Json(name = "compensation_earned") val compensationEarned: Double = 0.0,
    @Json(name = "compensation_used") val compensationUsed: Double = 0.0,
    @Json(name = "remaining_compensation") val remainingCompensation: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class LeaveCategoryRow(
    val category: String,
    @Json(name = "metric_label") val metricLabel: String = "Accrued",
    @Json(name = "accrued_or_earned") val accruedOrEarned: Double = 0.0,
    val used: Double = 0.0,
    val remaining: Double = 0.0,
    @Json(name = "policy_note") val policyNote: String? = null
)

@JsonClass(generateAdapter = true)
data class LeaveSummary(
    @Json(name = "guard_id") val guardId: String,
    @Json(name = "guard_name") val guardName: String,
    @Json(name = "guard_employee_number") val guardEmployeeNumber: String = "",
    val year: Int = 2026,
    val categories: List<LeaveCategoryRow> = emptyList()
)

@JsonClass(generateAdapter = true)
data class LeaveApplication(
    val id: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String,
    @Json(name = "guard_employee_number") val guardEmployeeNumber: String? = null,
    @Json(name = "leave_type") val leaveType: String,
    @Json(name = "leave_type_display") val leaveTypeDisplay: String? = null,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    val reason: String,
    @Json(name = "emergency_phone") val emergencyPhone: String? = null,
    @Json(name = "emergency_address") val emergencyAddress: String? = null,
    @Json(name = "doctor_report") val doctorReport: String? = null,
    @Json(name = "doctor_report_verified") val doctorReportVerified: Boolean = false,
    @Json(name = "event_details") val eventDetails: String? = null,
    val status: String,
    @Json(name = "status_display") val statusDisplay: String? = null,
    @Json(name = "reviewer_name") val reviewerName: String? = null,
    @Json(name = "reviewer_notes") val reviewerNotes: String? = null,
    @Json(name = "rejection_reason") val rejectionReason: String? = null,
    @Json(name = "rejection_reason_display") val rejectionReasonDisplay: String? = null,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class CreateLeaveRequest(
    @Json(name = "leave_type") val leaveType: String,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    val reason: String,
    @Json(name = "emergency_phone") val emergencyPhone: String? = null,
    @Json(name = "emergency_address") val emergencyAddress: String? = null,
    @Json(name = "doctor_report") val doctorReport: String? = null,
    @Json(name = "event_details") val eventDetails: String? = null
)

@JsonClass(generateAdapter = true)
data class LeaveAccrualRecord(
    val id: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String? = null,
    val year: Int,
    val month: Int,
    @Json(name = "casual_credited") val casualCredited: Double = 1.0,
    @Json(name = "vacation_credited") val vacationCredited: Double = 2.5,
    @Json(name = "casual_balance_after") val casualBalanceAfter: Double = 0.0,
    @Json(name = "vacation_balance_after") val vacationBalanceAfter: Double = 0.0,
    val notes: String = "",
    @Json(name = "created_by_name") val createdByName: String? = null,
    @Json(name = "created_at") val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class ProcessAccrualResponse(
    val message: String,
    @Json(name = "records_created") val recordsCreated: Int = 0,
    @Json(name = "guards_evaluated") val guardsEvaluated: Int = 0,
    @Json(name = "as_of_date") val asOfDate: String = ""
)

@JsonClass(generateAdapter = true)
data class NotificationItem(
    val id: String,
    val title: String,
    val message: String,
    @Json(name = "notification_type") val notificationType: String,
    val read: Boolean = false,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class UpdateProfileRequest(
    @Json(name = "first_name") val firstName: String? = null,
    @Json(name = "last_name") val lastName: String? = null,
    @Json(name = "phone_number") val phoneNumber: String? = null,
    @Json(name = "profile_photo") val profilePhoto: String? = null
)

@JsonClass(generateAdapter = true)
data class EscortDuty(
    val id: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String,
    @Json(name = "mission_name") val missionName: String,
    val origin: String,
    val destination: String,
    @Json(name = "start_time") val startTime: String,
    @Json(name = "end_time") val endTime: String,
    val status: String,
    @Json(name = "status_display") val statusDisplay: String? = null,
    val notes: String? = null,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class ExamDuty(
    val id: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String,
    val institution: String,
    @Json(name = "exam_title") val examTitle: String,
    val date: String,
    @Json(name = "start_time") val startTime: String,
    @Json(name = "end_time") val endTime: String,
    val status: String,
    val notes: String? = null,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class CreateUserRequest(
    val username: String,
    val email: String,
    val role: String,
    @Json(name = "first_name") val firstName: String? = null,
    @Json(name = "last_name") val lastName: String? = null,
    val station: String? = null
)

@JsonClass(generateAdapter = true)
data class Station(
    val id: String,
    val name: String,
    val code: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "geofence_radius_meters") val geofenceRadiusMeters: Double? = null,
    @Json(name = "geofence_radius") val geofenceRadius: Double? = null,
    @Json(name = "created_at") val createdAt: String? = null
) {
    val effectiveRadius: Double
        get() = geofenceRadiusMeters ?: geofenceRadius ?: 200.0
}

@JsonClass(generateAdapter = true)
data class CreateStationRequest(
    val name: String,
    val code: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    @Json(name = "geofence_radius_meters") val geofenceRadiusMeters: Double = 200.0,
    @Json(name = "geofence_radius") val geofenceRadius: Double = 200.0
)

@JsonClass(generateAdapter = true)
data class GuardPair(
    val id: String,
    val station: String,
    @Json(name = "station_name") val stationName: String? = null,
    @Json(name = "guard_a") val guardA: String,
    @Json(name = "guard_b") val guardB: String,
    @Json(name = "guard_a_name") val guardAName: String? = null,
    @Json(name = "guard_b_name") val guardBName: String? = null,
    val order: Int = 1,
    @Json(name = "rotation_order") val rotationOrder: Int? = null,
    @Json(name = "is_active") val isActive: Boolean = true
)

@JsonClass(generateAdapter = true)
data class CreateGuardPairRequest(
    val station: String,
    @Json(name = "guard_a") val guardA: String,
    @Json(name = "guard_b") val guardB: String,
    @Json(name = "rotation_order") val rotationOrder: Int = 1,
    val order: Int = 1
)

@JsonClass(generateAdapter = true)
data class GenerateRosterRequest(
    @Json(name = "station_id") val stationId: String,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "cycle_days") val cycleDays: Int = 12,
    val mode: String = "NORMAL",
    @Json(name = "examination_period_id") val examinationPeriodId: String? = null,
    @Json(name = "exam_venue_name") val examVenueName: String? = null,
    @Json(name = "exam_guard_ids") val examGuardIds: List<String> = emptyList()
)

typealias RosterGenerateRequest = GenerateRosterRequest

@JsonClass(generateAdapter = true)
data class GenerateRosterResponse(
    val message: String = "",
    @Json(name = "shifts_count") val shiftsCount: Int? = null,
    @Json(name = "shifts_created") val shiftsCreated: Int? = null,
    val station: String? = null,
    @Json(name = "cycle_days") val cycleDays: Int? = null,
    val mode: String? = null
)

@JsonClass(generateAdapter = true)
data class ExaminationPeriod(
    val id: String,
    val station: String,
    @Json(name = "station_name") val stationName: String? = null,
    val name: String = "University Examinations",
    @Json(name = "venue_name") val venueName: String = "Examination Center",
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "authorized_by") val authorizedBy: String? = null,
    @Json(name = "authorized_by_name") val authorizedByName: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CreateExaminationPeriodRequest(
    val station: String,
    val name: String,
    @Json(name = "venue_name") val venueName: String,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String
)

@JsonClass(generateAdapter = true)
data class TemporaryAssignmentAudit(
    val id: String,
    val guard: String,
    @Json(name = "guard_name") val guardName: String,
    @Json(name = "guard_employee_number") val guardEmployeeNumber: String? = null,
    @Json(name = "original_pair") val originalPair: String? = null,
    @Json(name = "original_pair_name") val originalPairName: String? = null,
    @Json(name = "original_assignment") val originalAssignment: String,
    @Json(name = "temporary_assignment") val temporaryAssignment: String,
    val location: String,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    @Json(name = "start_time") val startTime: String? = null,
    @Json(name = "end_time") val endTime: String? = null,
    val reason: String,
    @Json(name = "authorized_by") val authorizedBy: String? = null,
    @Json(name = "authorized_by_name") val authorizedByName: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class ScheduleExamEscortRequest(
    @Json(name = "station_id") val stationId: String,
    val date: String,
    @Json(name = "guard_ids") val guardIds: List<String>,
    @Json(name = "start_time") val startTime: String = "06:00:00",
    @Json(name = "end_time") val endTime: String = "17:00:00",
    val reason: String = "Examination paper collection escort to University National Centre"
)

@JsonClass(generateAdapter = true)
data class ScheduleExamEscortResponse(
    val message: String = "",
    val shifts: List<Shift> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ResumeNormalRosterRequest(
    @Json(name = "station_id") val stationId: String,
    @Json(name = "after_date") val afterDate: String,
    @Json(name = "cycle_days") val cycleDays: Int = 12
)

@JsonClass(generateAdapter = true)
data class DetectConflictsRequest(
    @Json(name = "station_id") val stationId: String,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "end_date") val endDate: String? = null
)

@JsonClass(generateAdapter = true)
data class ConflictReport(
    @Json(name = "has_conflicts") val hasConflicts: Boolean = false,
    @Json(name = "has_warnings") val hasWarnings: Boolean = false,
    @Json(name = "total_conflicts") val totalConflicts: Int = 0,
    val conflicts: List<RosterConflictItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RosterConflictItem(
    val type: String = "",
    val severity: String = "ERROR",
    val date: String = "",
    val guard: String? = null,
    val message: String = ""
)

@JsonClass(generateAdapter = true)
data class IncidentResolveRequest(
    @Json(name = "resolution_notes") val resolutionNotes: String
)

@JsonClass(generateAdapter = true)
data class LeaveReviewRequest(
    val status: String,
    @Json(name = "reviewer_notes") val reviewerNotes: String? = null,
    @Json(name = "rejection_reason") val rejectionReason: String? = null
)

@JsonClass(generateAdapter = true)
data class PasswordResetRequest(
    val identifier: String
)

@JsonClass(generateAdapter = true)
data class PasswordResetConfirmRequest(
    val identifier: String,
    @Json(name = "otp_code") val otpCode: String,
    @Json(name = "new_password") val newPassword: String
)

@JsonClass(generateAdapter = true)
data class AutoAllocateDutyRequest(
    val date: String? = null,
    @Json(name = "start_time") val startTime: String? = null,
    @Json(name = "end_time") val endTime: String? = null,
    val institution: String? = null,
    @Json(name = "exam_title") val examTitle: String? = null,
    @Json(name = "mission_name") val missionName: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val strategy: String = "RANDOM",
    val count: Int = 1
)

@JsonClass(generateAdapter = true)
data class AutoAllocateResponse(
    val message: String? = null,
    val strategy: String? = null
)

@JsonClass(generateAdapter = true)
data class ApproveRosterRequest(
    @Json(name = "roster_id") val rosterId: String? = null,
    @Json(name = "station_id") val stationId: String? = null,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "end_date") val endDate: String? = null
)

@JsonClass(generateAdapter = true)
data class RosterApproveResponse(
    val approved: Boolean = false,
    val status: String? = null,
    @Json(name = "roster_id") val rosterId: String? = null,
    val station: String? = null,
    @Json(name = "approved_by") val approvedBy: String? = null,
    @Json(name = "approved_at") val approvedAt: String? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class ValidateRosterRequest(
    @Json(name = "roster_id") val rosterId: String? = null,
    @Json(name = "station_id") val stationId: String? = null,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "end_date") val endDate: String? = null
)

@JsonClass(generateAdapter = true)
data class ValidateRosterResponse(
    val valid: Boolean = false,
    val status: String? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class CreditHolidayRequest(
    val days: Double = 2.0
)

@JsonClass(generateAdapter = true)
data class CreateEscortDutyRequest(
    val guard: String,
    @Json(name = "mission_name") val missionName: String,
    val origin: String,
    val destination: String,
    @Json(name = "start_time") val startTime: String,
    @Json(name = "end_time") val endTime: String
)

@JsonClass(generateAdapter = true)
data class CreateExamDutyRequest(
    val guard: String,
    val institution: String,
    @Json(name = "exam_title") val examTitle: String,
    val date: String,
    @Json(name = "start_time") val startTime: String,
    @Json(name = "end_time") val endTime: String
)

@JsonClass(generateAdapter = true)
data class NotificationAlert(
    val id: String,
    val title: String,
    val message: String,
    @Json(name = "notification_type") val notificationType: String? = null,
    val read: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TelemetryOverview(
    @Json(name = "total_guards") val totalGuards: Int = 0,
    @Json(name = "on_duty") val onDuty: Int = 0,
    @Json(name = "active_incidents") val activeIncidents: Int = 0,
    @Json(name = "critical_incidents") val criticalIncidents: Int = 0,
    @Json(name = "active_patrols") val activePatrols: Int = 0,
    @Json(name = "today_attendance") val todayAttendance: Int = 0,
    @Json(name = "pending_leave") val pendingLeave: Int = 0,
    @Json(name = "total_stations") val totalStations: Int = 0,
    @Json(name = "pending_handovers") val pendingHandovers: Int = 0,
    @Json(name = "incident_breakdown") val incidentBreakdown: Map<String, Int>? = null,
    @Json(name = "patrol_breakdown") val patrolBreakdown: Map<String, Int>? = null,
    @Json(name = "attendance_breakdown") val attendanceBreakdown: Map<String, Int>? = null
)

@JsonClass(generateAdapter = true)
data class TokenRefreshRequest(
    val refresh: String
)

@JsonClass(generateAdapter = true)
data class TokenRefreshResponse(
    val access: String,
    val refresh: String? = null
)

@JsonClass(generateAdapter = true)
data class StartPatrolRequest(
    val station: String? = null,
    val notes: String? = ""
)

@JsonClass(generateAdapter = true)
data class VisitorLogEntry(
    val id: String,
    @Json(name = "visitor_name") val visitorName: String,
    @Json(name = "id_number") val idNumber: String? = null,
    @Json(name = "person_to_visit") val personToVisit: String,
    val purpose: String,
    @Json(name = "vehicle_reg_number") val vehicleRegNumber: String? = null,
    @Json(name = "time_in") val timeIn: String,
    @Json(name = "time_out") val timeOut: String? = null,
    val station: String? = null,
    @Json(name = "station_name") val stationName: String? = null,
    val guard: String? = null,
    @Json(name = "guard_name") val guardName: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CreateVisitorLogRequest(
    @Json(name = "visitor_name") val visitorName: String,
    @Json(name = "id_number") val idNumber: String? = null,
    @Json(name = "person_to_visit") val personToVisit: String,
    val purpose: String,
    @Json(name = "vehicle_reg_number") val vehicleRegNumber: String? = null,
    @Json(name = "time_in") val timeIn: String,
    @Json(name = "time_out") val timeOut: String? = null,
    val station: String? = null
)

@JsonClass(generateAdapter = true)
data class BroadcastNoticeRequest(
    val title: String,
    val message: String,
    @Json(name = "target_role") val targetRole: String? = null,
    @Json(name = "station_id") val stationId: String? = null,
    @Json(name = "user_ids") val userIds: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class BroadcastNoticeResponse(
    val message: String? = null,
    @Json(name = "recipients_count") val recipientsCount: Int? = null,
    val status: String? = null
)

@JsonClass(generateAdapter = true)
data class NotificationActionResponse(
    val message: String? = null,
    val status: String? = null
)

@JsonClass(generateAdapter = true)
data class UnreadCountResponse(
    @Json(name = "unread_count") val unreadCount: Int = 0,
    val status: String? = null
)

@JsonClass(generateAdapter = true)
data class CreateCheckpointRequest(
    val station: String,
    val name: String,
    val code: String,
    @Json(name = "qr_code") val qrCode: String,
    val latitude: Double,
    val longitude: Double,
    val order: Int = 1,
    @Json(name = "is_active") val isActive: Boolean = true
)

@JsonClass(generateAdapter = true)
data class FinishPatrolRequest(
    val notes: String? = ""
)

@JsonClass(generateAdapter = true)
data class CheckpointScanResponse(
    val status: String? = null,
    val message: String? = null,
    @Json(name = "scans_count") val scansCount: Int? = null,
    @Json(name = "checkpoint_name") val checkpointName: String? = null
)

@JsonClass(generateAdapter = true)
data class UpdateUserStationRequest(
    val station: String?
)

@JsonClass(generateAdapter = true)
data class UpdateUserRequest(
    val station: String? = null,
    @Json(name = "is_active") val isActive: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class UpdateDutyStatusRequest(
    val status: String? = null,
    val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class PublicHoliday(
    val id: String,
    val name: String,
    val date: String,
    @Json(name = "country_code") val countryCode: String = "ZW",
    val description: String? = "",
    @Json(name = "is_active") val isActive: Boolean = true
)

@JsonClass(generateAdapter = true)
data class PublicHolidayDutyRecord(
    val id: String,
    @Json(name = "public_holiday") val publicHoliday: String,
    @Json(name = "public_holiday_name") val publicHolidayName: String? = null,
    val shift: String,
    @Json(name = "shift_date") val shiftDate: String? = null,
    @Json(name = "station_name") val stationName: String? = null,
    val guard: String,
    @Json(name = "guard_name") val guardName: String? = null,
    val attendance: String,
    @Json(name = "compensated_days") val compensatedDays: Double = 2.0,
    val status: String = "PENDING",
    @Json(name = "approved_by") val approvedBy: String? = null,
    @Json(name = "approved_by_name") val approvedByName: String? = null,
    @Json(name = "approved_at") val approvedAt: String? = null,
    @Json(name = "decision_reason") val decisionReason: String? = ""
)

@JsonClass(generateAdapter = true)
data class ReviewHolidayDutyRequest(
    val reason: String = ""
)

@JsonClass(generateAdapter = true)
data class GenerateEarlyClockoutOtpRequest(
    @Json(name = "shift_id") val shiftId: String,
    val reason: String
)

@JsonClass(generateAdapter = true)
data class GenerateEarlyClockoutOtpResponse(
    val otp: String,
    @Json(name = "expires_in_seconds") val expiresInSeconds: Int = 300,
    @Json(name = "shift_id") val shiftId: String,
    @Json(name = "guard_username") val guardUsername: String? = null,
    @Json(name = "guard_name") val guardName: String? = null,
    @Json(name = "station_name") val stationName: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class DirectMessage(
    val id: String,
    val sender: String,
    @Json(name = "sender_name") val senderName: String = "",
    val recipient: String,
    @Json(name = "recipient_name") val recipientName: String = "",
    val content: String,
    val read: Boolean = false,
    @Json(name = "created_at") val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class DirectMessageCreateRequest(
    val recipient: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class LateArrivalReportRequest(
    @Json(name = "shift_id") val shiftId: String,
    val reason: String,
    @Json(name = "incident_details") val incidentDetails: String = "",
    @Json(name = "estimated_arrival") val estimatedArrival: String = ""
)

@JsonClass(generateAdapter = true)
data class LateArrivalReportResponse(
    val message: String = "",
    @Json(name = "case_number") val caseNumber: String = "",
    @Json(name = "shift_id") val shiftId: String = "",
    val status: String = "",
    @Json(name = "reported_at") val reportedAt: String = ""
)

@JsonClass(generateAdapter = true)
data class SosDistressRequest(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val category: String = "General Officer Distress",
    @Json(name = "emergency_details") val emergencyDetails: String = ""
)

@JsonClass(generateAdapter = true)
data class SosDistressResponse(
    val status: String = "",
    @Json(name = "sos_dispatched") val sosDispatched: Boolean = false,
    @Json(name = "incident_id") val incidentId: String = "",
    val title: String = "",
    val station: String = "",
    val officer: String = "",
    val message: String = ""
)

@JsonClass(generateAdapter = true)
data class DutyStateResponse(
    @Json(name = "guard_id") val guardId: String = "",
    @Json(name = "guard_name") val guardName: String = "",
    val station: String? = null,
    @Json(name = "duty_state") val dutyState: String = "OFF_DUTY",
    @Json(name = "leave_type") val leaveType: String? = null,
    @Json(name = "is_on_duty") val isOnDuty: Boolean = false,
    @Json(name = "is_off_duty") val isOffDuty: Boolean = true
)