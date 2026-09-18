package com.example.data.repository

import com.example.data.api.ApiClient
import com.example.data.api.SessionManager
import com.example.data.local.CachedCheckpointEntity
import com.example.data.local.CachedIncidentEntity
import com.example.data.local.CachedOBEntity
import com.example.data.local.CachedShiftEntity
import com.example.data.local.CachedStationEntity
import com.example.data.local.SgmisDatabase
import com.example.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.util.Log

class SgmisRepository(
    private val apiClient: ApiClient,
    val sessionManager: SessionManager,
    private val database: SgmisDatabase
) {
    private val api get() = apiClient.getApiService()

    val currentUser: User? get() = sessionManager.getUser()
    val isLoggedIn: Boolean get() = sessionManager.isLoggedIn()
    var serverUrl: String
        get() = sessionManager.serverUrl
        set(value) {
            sessionManager.serverUrl = value
            apiClient.invalidateClient()
        }

    var themeMode: com.example.ui.theme.ThemeMode
        get() = sessionManager.getThemeMode()
        set(value) {
            sessionManager.setThemeMode(value)
        }

    // --- Error Parser Helper ---
    private fun sanitizeException(e: Throwable, fallback: String = "Server communication error"): Exception {
        val msg = e.message ?: ""
        if (msg.contains("setLenient", ignoreCase = true) || msg.contains("malformed JSON", ignoreCase = true) || msg.contains("Expected BEGIN_", ignoreCase = true)) {
            return Exception("Invalid or unexpected response format from server. Please verify your connection or try again.")
        }
        if (msg.contains("End of input", ignoreCase = true)) {
            return Exception("Empty response received from server.")
        }
        return if (e is Exception) e else Exception(fallback, e)
    }

    private fun parseDrfError(errorBody: String?, fallback: String): String {
        if (errorBody.isNullOrBlank()) return fallback
        val trimmed = errorBody.trim()
        if (trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true) || trimmed.contains("<html", ignoreCase = true)) {
            return fallback
        }
        return try {
            val jsonObj = org.json.JSONObject(trimmed)
            if (jsonObj.has("detail")) {
                return jsonObj.getString("detail")
            }
            if (jsonObj.has("message")) {
                return jsonObj.getString("message")
            }
            if (jsonObj.has("non_field_errors")) {
                val arr = jsonObj.getJSONArray("non_field_errors")
                if (arr.length() > 0) return arr.getString(0)
            }
            val keys = jsonObj.keys()
            if (keys.hasNext()) {
                val firstKey = keys.next()
                val firstVal = jsonObj.get(firstKey)
                if (firstVal is org.json.JSONArray && firstVal.length() > 0) {
                    "${firstKey.replace('_', ' ').capitalize()}: ${firstVal.getString(0)}"
                } else {
                    "${firstKey.replace('_', ' ').capitalize()}: $firstVal"
                }
            } else {
                fallback
            }
        } catch (e: Exception) {
            fallback
        }
    }

    // --- Authentication ---
    suspend fun login(identifier: String, pass: String): Result<User> {
        return try {
            val response = api.login(LoginRequest(identifier.trim(), pass))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                sessionManager.accessToken = body.access
                sessionManager.refreshToken = body.refresh
                sessionManager.saveUser(body.user)
                Result.success(body.user)
            } else {
                val errorMsg = parseDrfError(response.errorBody()?.string(), "Authentication failed (${response.code()})")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Authentication failed"))
        }
    }

    suspend fun fetchCurrentUser(): Result<User> {
        return try {
            val response = api.getCurrentUser()
            if (response.isSuccessful && response.body() != null) {
                val user = response.body()!!
                sessionManager.saveUser(user)
                Result.success(user)
            } else {
                Result.failure(Exception("Failed to fetch user profile (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to fetch user profile"))
        }
    }

    fun logout() {
        sessionManager.clearSession()
        apiClient.invalidateClient()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                database.clearAllTables()
            } catch (e: Exception) {
                Log.e("SgmisRepository", "Error clearing local cache on logout", e)
            }
        }
    }

    // --- Today's Shift ---
    suspend fun fetchTodayShift(stationId: String? = null, guardId: String? = null): Result<Shift?> {
        return try {
            val response = api.getTodayShift(station = stationId, guard = guardId)
            if (response.isSuccessful) {
                val shift = response.body()
                if (shift != null) {
                    database.shiftDao().insertShift(
                        CachedShiftEntity(
                            id = shift.id,
                            stationId = shift.station,
                            stationName = shift.stationName,
                            guardId = shift.guard,
                            guardName = shift.guardName,
                            employeeNumber = shift.employeeNumber,
                            date = shift.date,
                            startTime = shift.startTime,
                            endTime = shift.endTime,
                            shiftType = shift.shiftType,
                            partnerId = shift.partner,
                            partnerName = shift.partnerName,
                            partnerEmployeeNumber = shift.partnerEmployeeNumber,
                            attendanceStatus = shift.attendanceStatus
                        )
                    )
                }
                Result.success(shift)
            } else {
                Result.failure(Exception("Failed to fetch shift: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCachedShift(date: String): Flow<Shift?> {
        return database.shiftDao().getShiftByDate(date).map { entity ->
            entity?.let {
                Shift(
                    id = it.id,
                    station = it.stationId,
                    stationName = it.stationName,
                    guard = it.guardId,
                    guardName = it.guardName,
                    employeeNumber = it.employeeNumber,
                    date = it.date,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    shiftType = it.shiftType,
                    partner = it.partnerId,
                    partnerName = it.partnerName,
                    partnerEmployeeNumber = it.partnerEmployeeNumber,
                    attendanceStatus = it.attendanceStatus
                )
            }
        }
    }

    // --- Attendance ---
    suspend fun clockIn(shiftId: String, lat: Double?, lon: Double?, lateReason: String?): Result<Attendance> {
        return try {
            val response = api.clockIn(ClockInRequest(shiftId, lat, lon, lateReason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Clock-in failed (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Clock-in failed"))
        }
    }

    suspend fun clockOut(
        shiftId: String,
        lat: Double?,
        lon: Double?,
        supervisorUsername: String? = null,
        supervisorPassword: String? = null,
        overrideReason: String? = null
    ): Result<Attendance> {
        return try {
            val request = ClockOutRequest(
                shiftId = shiftId,
                latitude = lat,
                longitude = lon,
                supervisorUsername = supervisorUsername?.takeIf { it.isNotBlank() },
                supervisorPassword = supervisorPassword?.takeIf { it.isNotBlank() },
                overrideReason = overrideReason?.takeIf { it.isNotBlank() }
            )
            val response = api.clockOut(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Clock-out failed (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Clock-out failed"))
        }
    }

    // --- Handovers ---
    suspend fun fetchHandovers(): Result<List<ShiftHandover>> {
        return try {
            val response = api.getHandovers()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch handovers"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitHandover(
        outgoingShiftId: String,
        occurrenceSummary: String,
        equipment: String,
        keys: String,
        pendingIssues: String,
        emergencyOverride: Boolean = false
    ): Result<ShiftHandover> {
        return try {
            val response = api.createHandover(
                CreateHandoverRequest(
                    outgoingShift = outgoingShiftId,
                    occurrenceSummary = occurrenceSummary,
                    equipmentIssued = equipment,
                    keysHandedOver = keys,
                    pendingIssues = pendingIssues,
                    supervisorEmergencyOverride = emergencyOverride
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to submit handover")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acceptHandover(handoverId: String): Result<ShiftHandover> {
        return try {
            val response = api.acceptHandover(handoverId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to accept handover")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rejectHandover(handoverId: String, reason: String = ""): Result<ShiftHandover> {
        return try {
            val response = api.rejectHandover(handoverId, RejectHandoverRequest(reason = reason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to reject handover")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Occurrence Book ---
    suspend fun fetchOBEntries(): Result<List<OccurrenceBookEntry>> {
        return try {
            val response = api.getOBEntries()
            if (response.isSuccessful && response.body() != null) {
                val entries = response.body()!!
                database.obDao().insertEntries(
                    entries.map {
                        CachedOBEntity(
                            id = it.id,
                            entryNumber = it.entryNumber,
                            stationName = it.stationName,
                            guardName = it.guardName,
                            category = it.category,
                            categoryDisplay = it.categoryDisplay,
                            occurrenceText = it.occurrenceText,
                            checkRecord = it.checkRecord,
                            createdAt = it.createdAt
                        )
                    }
                )
                Result.success(entries)
            } else {
                Result.failure(Exception("Failed to fetch OB records"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitOBEntry(category: String, text: String, checkRecord: String, crossReference: String? = null): Result<OccurrenceBookEntry> {
        return try {
            val response = api.createOBEntry(
                CreateOBEntryRequest(
                    category = category,
                    occurrenceText = text,
                    checkRecord = checkRecord,
                    crossReference = crossReference,
                    station = currentUser?.station
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to record OB entry")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun amendOBEntry(id: String, reason: String, amendedText: String): Result<AmendOBResponse> {
        return try {
            val response = api.amendOBEntry(
                id = id,
                request = AmendOBRequest(reason = reason, amendedText = amendedText)
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to amend Occurrence Book entry")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Visitor Register ---
    suspend fun fetchVisitors(): Result<List<OccurrenceBookEntry>> {
        return try {
            val response = api.getOBEntries(category = "VISITOR")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch visitors register"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logVisitor(
        visitorName: String,
        idNumber: String?,
        personToVisit: String,
        purpose: String,
        timeIn: String,
        timeOut: String?,
        vehicleRegNumber: String? = null
    ): Result<OccurrenceBookEntry> {
        val details = buildString {
            append("VISITOR: $visitorName")
            if (!idNumber.isNullOrBlank()) append(" | National ID/Passport: $idNumber")
            if (!vehicleRegNumber.isNullOrBlank()) append(" | Vehicle Reg: $vehicleRegNumber")
            append(" | Person Visited: $personToVisit")
            append(" | Purpose: $purpose")
            append(" | Time In: $timeIn")
            if (!timeOut.isNullOrBlank()) append(" | Time Out: $timeOut") else append(" | Status: ACTIVE ON SITE")
        }
        return submitOBEntry(
            category = "VISITOR",
            text = details,
            checkRecord = "Identity verified & visitor pass issued"
        )
    }

    // --- Incidents ---
    suspend fun fetchIncidents(): Result<List<IncidentReport>> {
        return try {
            val response = api.getIncidents()
            if (response.isSuccessful && response.body() != null) {
                val incidents = response.body()!!
                database.incidentDao().insertIncidents(
                    incidents.map {
                        CachedIncidentEntity(
                            id = it.id,
                            stationName = it.stationName,
                            reportingGuardName = it.reportingGuardName,
                            priority = it.priority,
                            priorityDisplay = it.priorityDisplay,
                            title = it.title,
                            description = it.description,
                            location = it.location,
                            status = it.status,
                            createdAt = it.createdAt
                        )
                    }
                )
                Result.success(incidents)
            } else {
                Result.failure(Exception("Failed to fetch incidents"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitIncident(priority: String, title: String, description: String, location: String): Result<IncidentReport> {
        return try {
            val response = api.reportIncident(
                CreateIncidentRequest(priority, title, description, location)
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to file incident report")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun amendIncident(id: String, reason: String, amendedDescription: String): Result<AmendIncidentResponse> {
        return try {
            val response = api.amendIncident(
                id = id,
                request = AmendIncidentRequest(reason = reason, amendedDescription = amendedDescription)
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to amend incident report")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Patrols ---
    suspend fun fetchCheckpoints(): Result<List<Checkpoint>> {
        return try {
            val cached = database.checkpointDao().getAllCheckpoints()
            val response = api.getCheckpoints()
            if (response.isSuccessful && response.body() != null) {
                val checkpoints = response.body()!!
                database.checkpointDao().clearCheckpoints()
                database.checkpointDao().insertCheckpoints(checkpoints.map {
                    CachedCheckpointEntity(
                        id = it.id,
                        stationId = it.station,
                        stationName = it.stationName,
                        name = it.name,
                        code = it.code,
                        qrCode = it.qrCode,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        order = it.order,
                        isActive = it.isActive
                    )
                })
                Result.success(checkpoints)
            } else if (cached.isNotEmpty()) {
                Result.success(cached.map {
                    Checkpoint(
                        id = it.id,
                        station = it.stationId,
                        stationName = it.stationName,
                        name = it.name,
                        code = it.code,
                        qrCode = it.qrCode,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        order = it.order,
                        isActive = it.isActive
                    )
                })
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch station checkpoints")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            val cached = database.checkpointDao().getAllCheckpoints()
            if (cached.isNotEmpty()) {
                Result.success(cached.map {
                    Checkpoint(
                        id = it.id,
                        station = it.stationId,
                        stationName = it.stationName,
                        name = it.name,
                        code = it.code,
                        qrCode = it.qrCode,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        order = it.order,
                        isActive = it.isActive
                    )
                })
            } else {
                Result.failure(e)
            }
        }
    }

    fun getCachedCheckpoints(): Flow<List<Checkpoint>> {
        return database.checkpointDao().getAllCheckpointsFlow().map { list ->
            list.map {
                Checkpoint(
                    id = it.id,
                    station = it.stationId,
                    stationName = it.stationName,
                    name = it.name,
                    code = it.code,
                    qrCode = it.qrCode,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    order = it.order,
                    isActive = it.isActive
                )
            }
        }
    }


    suspend fun fetchPatrolLogs(): Result<List<PatrolLog>> {
        return try {
            val response = api.getPatrolLogs()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch patrol history")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startPatrol(stationId: String? = null, notes: String = "Patrol round initiated"): Result<PatrolLog> {
        return try {
            val targetStation = stationId ?: currentUser?.station
            val response = api.startPatrol(StartPatrolRequest(station = targetStation, notes = notes))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to initiate patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun scanCheckpoint(patrolId: String, checkpointId: String, gps: String, notes: String): Result<Unit> {
        return try {
            val response = api.scanCheckpoint(patrolId, CheckpointScanRequest(checkpointId, gps, notes))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Checkpoint scan verification failed")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun finishPatrol(patrolId: String, notes: String): Result<PatrolLog> {
        return try {
            val response = api.finishPatrol(patrolId, FinishPatrolRequest(notes = notes))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to complete patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Leave ---
    suspend fun fetchLeaveBalance(): Result<LeaveBalance> {
        return try {
            val response = api.getLeaveBalance()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch leave balance"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchLeaveSummary(): Result<LeaveSummary> {
        return try {
            val response = api.getLeaveSummary()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch leave & compensation summary"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchLeaveApplications(): Result<List<LeaveApplication>> {
        return try {
            val response = api.getLeaveApplications()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch leave records"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun applyForLeave(
        type: String,
        start: String,
        end: String,
        reason: String,
        emergencyPhone: String? = null,
        emergencyAddress: String? = null
    ): Result<LeaveApplication> {
        return try {
            val response = api.applyForLeave(
                CreateLeaveRequest(
                    leaveType = type,
                    startDate = start,
                    endDate = end,
                    reason = reason,
                    emergencyPhone = emergencyPhone,
                    emergencyAddress = emergencyAddress
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to submit leave application")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reviewLeaveApplication(
        id: String,
        status: String,
        reviewerNotes: String,
        rejectionReason: String? = null
    ): Result<LeaveApplication> {
        return try {
            val response = api.reviewLeaveApplication(id, LeaveReviewRequest(status, reviewerNotes, rejectionReason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to review leave application")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun creditHoliday(balanceId: String, days: Double = 2.0): Result<LeaveBalance> {
        return try {
            val response = api.creditHoliday(balanceId, mapOf("days" to days))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to credit holiday leave")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestPasswordReset(identifier: String): Result<String> {
        return try {
            val response = api.requestPasswordReset(PasswordResetRequest(identifier))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()?.message ?: "OTP generated successfully.")
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to request password reset")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun confirmPasswordReset(identifier: String, otp: String, newPass: String): Result<String> {
        return try {
            val response = api.confirmPasswordReset(PasswordResetConfirmRequest(identifier, otp, newPass))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()?.message ?: "Password successfully reset.")
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to reset password")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun autoAllocateExams(date: String, strategy: String, count: Int): Result<String> {
        return try {
            val response = api.autoAllocateExamDuties(AutoAllocateDutyRequest(date = date, strategy = strategy, count = count))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()?.message ?: "Exam duties auto-allocated.")
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to auto-allocate exam duties")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun autoAllocateEscorts(startTime: String, endTime: String, strategy: String, count: Int): Result<String> {
        return try {
            val response = api.autoAllocateEscortDuties(AutoAllocateDutyRequest(startTime = startTime, endTime = endTime, strategy = strategy, count = count))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()?.message ?: "Escort duties auto-allocated.")
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to auto-allocate escort duties")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun approveRoster(stationId: String, startDate: String? = null, endDate: String? = null): Result<String> {
        return try {
            val response = api.approveRoster(ApproveRosterRequest(stationId = stationId, startDate = startDate, endDate = endDate))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()?.message ?: "Roster approved.")
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to approve roster")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- User & Guard Management ---
    suspend fun fetchUsers(role: String? = null, station: String? = null): Result<List<User>> {
        return try {
            val response = api.getUsers(role, station)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to load user directory"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createUser(request: CreateUserRequest): Result<User> {
        return try {
            val response = api.createUser(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to create user account")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleUserActive(userId: String, currentActive: Boolean): Result<User> {
        return try {
            val response = api.updateUser(userId, UpdateUserRequest(isActive = !currentActive))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to update user status")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun assignUserStation(userId: String, stationId: String?): Result<User> {
        return try {
            val response = api.updateUser(userId, UpdateUserRequest(station = stationId))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to assign station to user")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Station & Guard Pair Management ---
    suspend fun fetchStations(): Result<List<Station>> {
        return try {
            val cached = database.stationDao().getAllStations()
            val response = api.getStations()
            if (response.isSuccessful && response.body() != null) {
                val stations = response.body()!!
                database.stationDao().clearStations()
                database.stationDao().insertStations(stations.map {
                    CachedStationEntity(
                        id = it.id,
                        name = it.name,
                        code = it.code,
                        address = it.address,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        geofenceRadius = it.geofenceRadiusMeters ?: it.geofenceRadius,
                        createdAt = it.createdAt
                    )
                })
                Result.success(stations)
            } else if (cached.isNotEmpty()) {
                Result.success(cached.map {
                    Station(
                        id = it.id,
                        name = it.name,
                        code = it.code,
                        address = it.address,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        geofenceRadiusMeters = it.geofenceRadius,
                        geofenceRadius = it.geofenceRadius,
                        createdAt = it.createdAt
                    )
                })
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch stations")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            val cached = database.stationDao().getAllStations()
            if (cached.isNotEmpty()) {
                Result.success(cached.map {
                    Station(
                        id = it.id,
                        name = it.name,
                        code = it.code,
                        address = it.address,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        geofenceRadiusMeters = it.geofenceRadius,
                        geofenceRadius = it.geofenceRadius,
                        createdAt = it.createdAt
                    )
                })
            } else {
                Result.failure(e)
            }
        }
    }

    fun getCachedStations(): Flow<List<Station>> {
        return database.stationDao().getAllStationsFlow().map { list ->
            list.map {
                Station(
                    id = it.id,
                    name = it.name,
                    code = it.code,
                    address = it.address,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    geofenceRadiusMeters = it.geofenceRadius,
                    geofenceRadius = it.geofenceRadius,
                    createdAt = it.createdAt
                )
            }
        }
    }


    suspend fun createStation(name: String, code: String, address: String, lat: Double, lon: Double, geofence: Double): Result<Station> {
        return try {
            val response = api.createStation(
                CreateStationRequest(
                    name = name,
                    code = code,
                    address = address,
                    latitude = lat,
                    longitude = lon,
                    geofenceRadiusMeters = geofence,
                    geofenceRadius = geofence
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to create station")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchGuardPairs(station: String? = null): Result<List<GuardPair>> {
        return try {
            val response = api.getGuardPairs(station)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch guard pairings"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createGuardPair(stationId: String, guardAId: String, guardBId: String, order: Int): Result<GuardPair> {
        return try {
            val response = api.createGuardPair(CreateGuardPairRequest(stationId, guardAId, guardBId, order))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to create guard pairing")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Shift Roster & Management ---
    suspend fun fetchShifts(date: String? = null, station: String? = null): Result<List<Shift>> {
        return try {
            val response = api.getShifts(date, station)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch roster shifts (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to fetch roster shifts"))
        }
    }

    suspend fun fetchOperationalRoster(
        stationId: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        guardId: String? = null,
        pairId: String? = null,
        assignmentType: String? = null,
        shiftType: String? = null
    ): Result<List<Shift>> {
        return try {
            val response = api.getOperationalRoster(
                station = stationId,
                startDate = startDate,
                endDate = endDate,
                guard = guardId,
                pair = pairId,
                assignmentType = assignmentType,
                shiftType = shiftType
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = parseDrfError(response.errorBody()?.string(), "Failed to load operational roster (${response.code()})")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to load operational roster"))
        }
    }

    suspend fun generateRoster(
        stationId: String,
        startDate: String,
        cycleDays: Int = 12,
        mode: String = "NORMAL",
        examinationPeriodId: String? = null,
        examVenueName: String? = null,
        examGuardIds: List<String> = emptyList()
    ): Result<GenerateRosterResponse> {
        return try {
            val response = api.generateRoster(
                GenerateRosterRequest(
                    stationId = stationId,
                    startDate = startDate,
                    cycleDays = cycleDays,
                    mode = mode,
                    examinationPeriodId = examinationPeriodId,
                    examVenueName = examVenueName,
                    examGuardIds = examGuardIds
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Roster generation failed")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun detectConflicts(
        stationId: String,
        startDate: String? = null,
        endDate: String? = null
    ): Result<ConflictReport> {
        return try {
            val response = api.detectConflicts(
                DetectConflictsRequest(stationId = stationId, startDate = startDate, endDate = endDate)
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Conflict detection failed")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun scheduleExamEscort(
        stationId: String,
        date: String,
        guardIds: List<String>,
        startTime: String = "06:00:00",
        endTime: String = "17:00:00",
        reason: String = "Examination paper collection escort to University National Centre"
    ): Result<ScheduleExamEscortResponse> {
        return try {
            val response = api.scheduleEscort(
                ScheduleExamEscortRequest(
                    stationId = stationId,
                    date = date,
                    guardIds = guardIds,
                    startTime = startTime,
                    endTime = endTime,
                    reason = reason
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Escort scheduling failed")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resumeNormalRoster(
        stationId: String,
        afterDate: String,
        cycleDays: Int = 12
    ): Result<NotificationActionResponse> {
        return try {
            val response = api.resumeNormalRoster(
                ResumeNormalRosterRequest(stationId = stationId, afterDate = afterDate, cycleDays = cycleDays)
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Resume normal roster failed")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchTemporaryAssignments(stationId: String? = null): Result<List<TemporaryAssignmentAudit>> {
        return try {
            val response = api.getTemporaryAssignments(stationId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch temporary assignment audits"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchExaminationPeriods(stationId: String? = null): Result<List<ExaminationPeriod>> {
        return try {
            val response = api.getExaminationPeriods(stationId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch examination periods"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createExaminationPeriod(
        stationId: String,
        name: String,
        venueName: String,
        startDate: String,
        endDate: String
    ): Result<ExaminationPeriod> {
        return try {
            val response = api.createExaminationPeriod(
                CreateExaminationPeriodRequest(
                    station = stationId,
                    name = name,
                    venueName = venueName,
                    startDate = startDate,
                    endDate = endDate
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to create examination period")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Attendance Records ---
    suspend fun fetchAttendanceRecords(date: String? = null, shift: String? = null): Result<List<Attendance>> {
        return try {
            val response = api.getAttendanceRecords(date, shift)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch attendance records"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Incident Actions ---
    suspend fun acknowledgeIncident(id: String): Result<IncidentReport> {
        return try {
            val response = api.acknowledgeIncident(id)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to acknowledge incident")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resolveIncident(id: String, notes: String): Result<IncidentReport> {
        return try {
            val response = api.resolveIncident(id, IncidentResolveRequest(notes))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to resolve incident")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Escort Duties ---
    suspend fun fetchEscortDuties(): Result<List<EscortDuty>> {
        return try {
            val response = api.getEscortDuties()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch escort duties"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createEscortDuty(request: CreateEscortDutyRequest): Result<EscortDuty> {
        return try {
            val response = api.createEscortDuty(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to create escort duty")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateEscortStatus(id: String, status: String): Result<EscortDuty> {
        return try {
            val response = api.updateEscortDuty(id, UpdateDutyStatusRequest(status = status))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to update escort status")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Exam Duties ---
    suspend fun fetchExamDuties(): Result<List<ExamDuty>> {
        return try {
            val response = api.getExamDuties()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch exam duties"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createExamDuty(request: CreateExamDutyRequest): Result<ExamDuty> {
        return try {
            val response = api.createExamDuty(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to schedule exam duty")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateExamStatus(id: String, status: String): Result<ExamDuty> {
        return try {
            val response = api.updateExamDuty(id, UpdateDutyStatusRequest(status = status))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to update exam status")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Notifications ---
    suspend fun fetchNotifications(): Result<List<NotificationAlert>> {
        return try {
            val response = api.getNotifications()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch notifications"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markNotificationRead(id: String): Result<Unit> {
        return try {
            val response = api.markNotificationRead(id)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to mark alert as read"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markAllNotificationsRead(): Result<Unit> {
        return try {
            val response = api.markAllNotificationsRead()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to mark all alerts as read"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Operational Telemetry & Profile Management ---
    suspend fun fetchTelemetry(): Result<TelemetryOverview> {
        return try {
            val response = api.getTelemetry()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else if (response.code() == 404) {
                // Not deployed on remote server yet; dashboard computes metrics from domain models
                Result.success(TelemetryOverview())
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to load operational telemetry")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateUserProfile(
        firstName: String?,
        lastName: String?,
        phoneNumber: String?,
        profilePhoto: String?
    ): Result<User> {
        return try {
            val request = UpdateProfileRequest(
                firstName = firstName,
                lastName = lastName,
                phoneNumber = phoneNumber,
                profilePhoto = profilePhoto
            )
            val response = api.updateProfile(request)
            if (response.isSuccessful && response.body() != null) {
                val updatedUser = response.body()!!
                sessionManager.saveUser(updatedUser)
                Result.success(updatedUser)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to update profile")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchUnreadNotificationCount(): Result<Int> {
        return try {
            val response = api.getUnreadNotificationCount()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.unreadCount)
            } else {
                Result.success(0)
            }
        } catch (e: Exception) {
            Result.success(0)
        }
    }

    suspend fun broadcastNotice(
        title: String,
        message: String,
        targetRole: String? = null,
        stationId: String? = null,
        userIds: List<String>? = null
    ): Result<BroadcastNoticeResponse> {
        return try {
            val response = api.broadcastNotice(
                BroadcastNoticeRequest(
                    title = title,
                    message = message,
                    targetRole = targetRole,
                    stationId = stationId,
                    userIds = userIds
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to broadcast notice (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to broadcast notice"))
        }
    }
}
