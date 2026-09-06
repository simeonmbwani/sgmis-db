package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class User(
    val id: String,
    val username: String,
    val email: String? = null,
    @Json(name = "employee_number") val employeeNumber: String? = null,
    val role: String,
    @Json(name = "full_name") val fullName: String? = null,
    val rank: String? = null,
    @Json(name = "station_name") val stationName: String? = null,
    @Json(name = "is_active") val isActive: Boolean = true
)

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
    val pair: String? = null,
    val partner: String? = null,
    @Json(name = "partner_name") val partnerName: String? = null,
    @Json(name = "partner_employee_number") val partnerEmployeeNumber: String? = null,
    @Json(name = "is_override") val isOverride: Boolean = false,
    @Json(name = "override_reason") val overrideReason: String? = null,
    @Json(name = "attendance_status") val attendanceStatus: String = "NOT_CLOCKED_IN"
)

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
    @Json(name = "late_reason") val lateReason: String? = null
)

@JsonClass(generateAdapter = true)
data class ClockInRequest(
    @Json(name = "shift_id") val shiftId: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "late_reason") val lateReason: String? = null
)

@JsonClass(generateAdapter = true)
data class ClockOutRequest(
    @Json(name = "shift_id") val shiftId: String,
    val latitude: Double? = null,
    val longitude: Double? = null
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
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class CreateHandoverRequest(
    @Json(name = "outgoing_shift") val outgoingShift: String,
    @Json(name = "occurrence_summary") val occurrenceSummary: String,
    @Json(name = "equipment_issued") val equipmentIssued: String = "All equipment accounted for.",
    @Json(name = "keys_handed_over") val keysHandedOver: String = "Station keys transferred.",
    @Json(name = "pending_issues") val pendingIssues: String = "None."
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
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class CreateOBEntryRequest(
    val category: String,
    @Json(name = "occurrence_text") val occurrenceText: String,
    @Json(name = "check_record") val checkRecord: String = "Verified & Logged",
    val station: String? = null
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
    val year: Int,
    @Json(name = "annual_days") val annualDays: Int,
    @Json(name = "sick_days") val sickDays: Int,
    @Json(name = "used_annual") val usedAnnual: Int,
    @Json(name = "used_sick") val usedSick: Int,
    @Json(name = "remaining_annual") val remainingAnnual: Int,
    @Json(name = "remaining_sick") val remainingSick: Int
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
    val status: String,
    @Json(name = "status_display") val statusDisplay: String? = null,
    @Json(name = "reviewer_name") val reviewerName: String? = null,
    @Json(name = "reviewer_notes") val reviewerNotes: String? = null,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class CreateLeaveRequest(
    @Json(name = "leave_type") val leaveType: String,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    val reason: String
)
