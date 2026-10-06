package com.example.data.repository

import com.example.data.api.ApiClient
import com.example.data.api.SessionManager
import com.example.data.local.*

import com.example.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

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
        Log.e("SgmisRepository", "Request failed", e)
        if (msg.contains("setLenient", ignoreCase = true) || msg.contains("malformed JSON", ignoreCase = true) || msg.contains("Expected BEGIN_", ignoreCase = true)) {
            return Exception("Unable to process the server response. Please try again.")
        }
        if (e is java.net.SocketTimeoutException) {
            return Exception("The request timed out. Please try again.")
        }
        if (e is java.io.IOException) {
            return Exception("Unable to connect. Please check your connection and try again.")
        }
        return Exception(fallback)
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
                return userSafeMessage(jsonObj.getString("detail"), fallback)
            }
            if (jsonObj.has("message")) {
                return userSafeMessage(jsonObj.getString("message"), fallback)
            }
            if (jsonObj.has("non_field_errors")) {
                val arr = jsonObj.getJSONArray("non_field_errors")
                if (arr.length() > 0) return userSafeMessage(arr.getString(0), fallback)
            }
            val keys = jsonObj.keys()
            if (keys.hasNext()) {
                val firstKey = keys.next()
                val firstVal = jsonObj.get(firstKey)
                if (firstVal is org.json.JSONArray && firstVal.length() > 0) {
                    userSafeMessage("${firstKey.replace('_', ' ').capitalize()}: ${firstVal.getString(0)}", fallback)
                } else {
                    userSafeMessage("${firstKey.replace('_', ' ').capitalize()}: $firstVal", fallback)
                }
            } else {
                fallback
            }
        } catch (e: Exception) {
            fallback
        }
    }

    private fun userSafeMessage(message: String, fallback: String): String {
        val technical = Regex("(?i)(exception|traceback|stack trace|retrofit|java\\.|kotlin\\.|sqlite|operationalerror|expected begin_|http\\s+5\\d\\d|/api/[a-z0-9_/-]+)")
        return if (technical.containsMatchIn(message)) fallback else message
    }

    suspend fun fetchRecordAdjustments(status: String? = null): Result<List<RecordAdjustmentRequest>> = try {
        val response = api.getRecordAdjustments(status)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to load record adjustments")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun createRecordAdjustment(request: CreateRecordAdjustmentRequest): Result<RecordAdjustmentRequest> = try {
        val response = api.createRecordAdjustment(request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to submit record adjustment")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun approveRecordAdjustment(id: String, value: String? = null): Result<RecordAdjustmentResponse> = try {
        val response = api.approveRecordAdjustment(id, ApproveRecordAdjustmentRequest(value))
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to approve adjustment")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun rejectRecordAdjustment(id: String, reason: String): Result<RecordAdjustmentResponse> = try {
        val response = api.rejectRecordAdjustment(id, RejectRecordAdjustmentRequest(reason))
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to reject adjustment")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun fetchAdministrativeHistory(): Result<List<AdministrativeHistoryEntry>> = try {
        val response = api.getAdministrativeHistory()
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to load administrative history")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun fetchLeaveAdjustments(): Result<List<LeaveAdjustmentRecord>> = try {
        val response = api.getLeaveAdjustments()
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to load leave adjustment history")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun setOpeningBalance(request: SetOpeningBalanceRequest): Result<SetOpeningBalanceResponse> = try {
        val response = api.setOpeningLeaveBalance(request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to set opening leave balance")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun reassignDuty(request: ReassignDutyRequest): Result<ReassignDutyResponse> = try {
        val response = api.reassignDuty(request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to reassign duty")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun reassignSingleShift(id: String, request: ReassignSingleShiftRequest): Result<ReassignSingleShiftResponse> = try {
        val response = api.reassignSingleShift(id, request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to reassign shift")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun getStationCoverage(stationId: String? = null, date: String? = null): Result<StationCoverageResponse> = try {
        val response = api.getStationCoverage(stationId, date)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to load station coverage")))
    } catch (e: Exception) { Result.failure(sanitizeException(e, "Failed to load station coverage")) }

    suspend fun swapPairDuties(request: SwapPairDutiesRequest): Result<NotificationActionResponse> = try {
        val response = api.swapPairDuties(request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to swap pair duties")))
    } catch (e: Exception) { Result.failure(sanitizeException(e, "Failed to swap pair duties")) }

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
            Result.failure(sanitizeException(e))
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
    suspend fun clockIn(shiftId: String, lat: Double?, lon: Double?, lateReason: String?, caseNumber: String? = null): Result<Attendance> {
        return try {
            val response = api.clockIn(ClockInRequest(shiftId, lat, lon, lateReason, caseNumber))
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
        overrideReason: String? = null,
        otpCode: String? = null
    ): Result<Attendance> {
        return try {
            val request = ClockOutRequest(
                shiftId = shiftId,
                latitude = lat,
                longitude = lon,
                supervisorUsername = supervisorUsername?.takeIf { it.isNotBlank() },
                supervisorPassword = supervisorPassword?.takeIf { it.isNotBlank() },
                overrideReason = overrideReason?.takeIf { it.isNotBlank() },
                otpCode = otpCode?.takeIf { it.isNotBlank() }
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
                        nfcUid = it.nfcUid,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        order = it.order,
                        minIntervalSeconds = it.minIntervalSeconds,
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
                        nfcUid = it.nfcUid,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        order = it.order,
                        minIntervalSeconds = it.minIntervalSeconds,
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
                        nfcUid = it.nfcUid,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        order = it.order,
                        minIntervalSeconds = it.minIntervalSeconds,
                        isActive = it.isActive
                    )
                })
            } else {
                Result.failure(sanitizeException(e))
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
                    nfcUid = it.nfcUid,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    order = it.order,
                    minIntervalSeconds = it.minIntervalSeconds,
                    isActive = it.isActive
                )
            }
        }
    }

    suspend fun fetchPatrolLogs(): Result<List<PatrolLog>> {
        return try {
            val response = api.getPatrolLogs()
            if (response.isSuccessful && response.body() != null) {
                val logs = response.body()!!
                database.patrolDao().insertPatrolLogs(logs.map {
                    CachedPatrolLogEntity(
                        id = it.id,
                        name = it.name,
                        guard = it.guard,
                        guardName = it.guardName,
                        station = it.station,
                        stationName = it.stationName,
                        assignedBy = it.assignedBy,
                        assignedByName = it.assignedByName,
                        startWindow = it.startWindow,
                        deadline = it.deadline,
                        startTime = it.startTime,
                        endTime = it.endTime,
                        status = it.status,
                        isApproved = it.isApproved,
                        approvedBy = it.approvedBy,
                        approvedByName = it.approvedByName,
                        approvedAt = it.approvedAt,
                        anomaliesCount = it.anomaliesCount,
                        notes = it.notes,
                        scansCount = it.scansCount
                    )
                })
                Result.success(logs)
            } else {
                val cached = database.patrolDao().getAllPatrolLogs()
                if (cached.isNotEmpty()) {
                    Result.success(cached.map {
                        PatrolLog(
                            id = it.id,
                            name = it.name,
                            guard = it.guard,
                            guardName = it.guardName,
                            station = it.station,
                            stationName = it.stationName,
                            assignedBy = it.assignedBy,
                            assignedByName = it.assignedByName,
                            startWindow = it.startWindow,
                            deadline = it.deadline,
                            startTime = it.startTime,
                            endTime = it.endTime,
                            status = it.status,
                            isApproved = it.isApproved,
                            approvedBy = it.approvedBy,
                            approvedByName = it.approvedByName,
                            approvedAt = it.approvedAt,
                            anomaliesCount = it.anomaliesCount,
                            notes = it.notes,
                            scansCount = it.scansCount
                        )
                    })
                } else {
                    val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch patrol history")
                    Result.failure(Exception(err))
                }
            }
        } catch (e: Exception) {
            val cached = database.patrolDao().getAllPatrolLogs()
            if (cached.isNotEmpty()) {
                Result.success(cached.map {
                    PatrolLog(
                        id = it.id,
                        name = it.name,
                        guard = it.guard,
                        guardName = it.guardName,
                        station = it.station,
                        stationName = it.stationName,
                        assignedBy = it.assignedBy,
                        assignedByName = it.assignedByName,
                        startWindow = it.startWindow,
                        deadline = it.deadline,
                        startTime = it.startTime,
                        endTime = it.endTime,
                        status = it.status,
                        isApproved = it.isApproved,
                        approvedBy = it.approvedBy,
                        approvedByName = it.approvedByName,
                        approvedAt = it.approvedAt,
                        anomaliesCount = it.anomaliesCount,
                        notes = it.notes,
                        scansCount = it.scansCount
                    )
                })
            } else {
                Result.failure(sanitizeException(e))
            }
        }
    }

    suspend fun assignPatrol(
        guardId: String,
        stationId: String? = null,
        name: String = "Routine Station Patrol",
        startWindow: String? = null,
        deadline: String? = null,
        notes: String? = ""
    ): Result<PatrolLog> {
        return try {
            val response = api.assignPatrol(
                AssignPatrolRequest(
                    guard = guardId,
                    station = stationId ?: currentUser?.station,
                    name = name,
                    startWindow = startWindow,
                    deadline = deadline,
                    notes = notes ?: ""
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val patrol = response.body()!!
                database.patrolDao().insertPatrolLog(
                    CachedPatrolLogEntity(
                        id = patrol.id,
                        name = patrol.name,
                        guard = patrol.guard,
                        guardName = patrol.guardName,
                        station = patrol.station,
                        stationName = patrol.stationName,
                        assignedBy = patrol.assignedBy,
                        assignedByName = patrol.assignedByName,
                        startWindow = patrol.startWindow,
                        deadline = patrol.deadline,
                        startTime = patrol.startTime,
                        endTime = patrol.endTime,
                        status = patrol.status,
                        isApproved = patrol.isApproved,
                        approvedBy = patrol.approvedBy,
                        approvedByName = patrol.approvedByName,
                        approvedAt = patrol.approvedAt,
                        anomaliesCount = patrol.anomaliesCount,
                        notes = patrol.notes,
                        scansCount = patrol.scansCount
                    )
                )
                Result.success(patrol)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to assign patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun startAssignedPatrol(patrolId: String, notes: String? = "Patrol started by guard"): Result<PatrolLog> {
        return try {
            val response = api.startAssignedPatrol(patrolId, StartAssignedPatrolRequest(notes = notes))
            if (response.isSuccessful && response.body() != null) {
                val patrol = response.body()!!
                database.patrolDao().insertPatrolLog(
                    CachedPatrolLogEntity(
                        id = patrol.id,
                        name = patrol.name,
                        guard = patrol.guard,
                        guardName = patrol.guardName,
                        station = patrol.station,
                        stationName = patrol.stationName,
                        assignedBy = patrol.assignedBy,
                        assignedByName = patrol.assignedByName,
                        startWindow = patrol.startWindow,
                        deadline = patrol.deadline,
                        startTime = patrol.startTime,
                        endTime = patrol.endTime,
                        status = patrol.status,
                        isApproved = patrol.isApproved,
                        approvedBy = patrol.approvedBy,
                        approvedByName = patrol.approvedByName,
                        approvedAt = patrol.approvedAt,
                        anomaliesCount = patrol.anomaliesCount,
                        notes = patrol.notes,
                        scansCount = patrol.scansCount
                    )
                )
                Result.success(patrol)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to start assigned patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun cancelPatrol(patrolId: String, reason: String = "Patrol cancelled by supervisor"): Result<PatrolLog> {
        return try {
            val response = api.cancelPatrol(patrolId, CancelPatrolRequest(reason = reason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to cancel patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun reassignPatrol(patrolId: String, newGuardId: String, reason: String = "Patrol reassigned by supervisor"): Result<PatrolLog> {
        return try {
            val response = api.reassignPatrol(patrolId, ReassignPatrolRequest(guard = newGuardId, reason = reason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to reassign patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun approvePatrol(patrolId: String, notes: String? = "Patrol verified and approved"): Result<PatrolLog> {
        return try {
            val response = api.approvePatrol(patrolId, ApprovePatrolRequest(notes = notes))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to approve patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun rejectPatrol(patrolId: String, reason: String): Result<PatrolLog> {
        return try {
            val response = api.rejectPatrol(patrolId, RejectPatrolRequest(reason = reason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to reject patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun scanCheckpoint(
        patrolId: String,
        checkpointId: String,
        gps: String,
        notes: String,
        checkpointCode: String = "",
        checkpointOrder: Int = 1,
        verificationMethod: String = "NFC",
        accuracy: Double? = null
    ): Result<Unit> {
        val clientEventId = java.util.UUID.randomUUID().toString()
        val clientTimestamp = java.time.Instant.now().toString()
        return try {
            val response = api.scanCheckpoint(
                patrolId,
                CheckpointScanRequest(
                    checkpoint = checkpointId,
                    gpsCoords = gps,
                    notes = notes,
                    accuracy = accuracy,
                    verificationMethod = verificationMethod,
                    clientEventId = clientEventId,
                    clientTimestamp = clientTimestamp
                )
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                database.patrolDao().insertPatrolEvent(
                    CachedPatrolEventEntity(
                        clientEventId = clientEventId,
                        patrolLogId = patrolId,
                        checkpointId = checkpointId,
                        checkpointCode = checkpointCode,
                        checkpointOrder = checkpointOrder,
                        scannedAt = clientTimestamp,
                        clientTimestamp = clientTimestamp,
                        gpsCoords = gps,
                        accuracy = accuracy,
                        verificationMethod = verificationMethod,
                        notes = notes,
                        isSynced = false
                    )
                )
                val err = parseDrfError(response.errorBody()?.string(), "Checkpoint scan verification queued offline")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            database.patrolDao().insertPatrolEvent(
                CachedPatrolEventEntity(
                    clientEventId = clientEventId,
                    patrolLogId = patrolId,
                    checkpointId = checkpointId,
                    checkpointCode = checkpointCode,
                    checkpointOrder = checkpointOrder,
                    scannedAt = clientTimestamp,
                    clientTimestamp = clientTimestamp,
                    gpsCoords = gps,
                    accuracy = accuracy,
                    verificationMethod = verificationMethod,
                    notes = notes,
                    isSynced = false
                )
            )
            Result.success(Unit)
        }
    }

    suspend fun syncOfflinePatrolEvents(patrolId: String): Result<SyncPatrolEventsResponse> {
        return try {
            val unsynced = database.patrolDao().getUnsyncedEventsForPatrol(patrolId)
            if (unsynced.isEmpty()) {
                return Result.success(SyncPatrolEventsResponse(syncedCount = 0, anomaliesDetected = 0))
            }
            val requestEvents = unsynced.map {
                OfflinePatrolEvent(
                    clientEventId = it.clientEventId,
                    checkpoint = it.checkpointId,
                    clientTimestamp = it.clientTimestamp,
                    verificationMethod = it.verificationMethod,
                    gpsCoords = it.gpsCoords,
                    accuracy = it.accuracy,
                    notes = it.notes
                )
            }
            val response = api.syncPatrolEvents(patrolId, SyncPatrolEventsRequest(events = requestEvents))
            if (response.isSuccessful && response.body() != null) {
                val res = response.body()!!
                database.patrolDao().markEventsSynced(unsynced.map { it.clientEventId })
                Result.success(res)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Offline patrol synchronization failed")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun getUnsyncedPatrolEventsCount(): Int {
        return try {
            database.patrolDao().getAllUnsyncedEvents().size
        } catch (e: Exception) {
            0
        }
    }

    suspend fun finishPatrol(patrolId: String, notes: String): Result<PatrolLog> {
        return try {
            val response = api.finishPatrol(patrolId, FinishPatrolRequest(notes = notes))
            if (response.isSuccessful && response.body() != null) {
                val patrol = response.body()!!
                database.patrolDao().insertPatrolLog(
                    CachedPatrolLogEntity(
                        id = patrol.id,
                        name = patrol.name,
                        guard = patrol.guard,
                        guardName = patrol.guardName,
                        station = patrol.station,
                        stationName = patrol.stationName,
                        assignedBy = patrol.assignedBy,
                        assignedByName = patrol.assignedByName,
                        startWindow = patrol.startWindow,
                        deadline = patrol.deadline,
                        startTime = patrol.startTime,
                        endTime = patrol.endTime,
                        status = patrol.status,
                        isApproved = patrol.isApproved,
                        approvedBy = patrol.approvedBy,
                        approvedByName = patrol.approvedByName,
                        approvedAt = patrol.approvedAt,
                        anomaliesCount = patrol.anomaliesCount,
                        notes = patrol.notes,
                        scansCount = patrol.scansCount
                    )
                )
                Result.success(patrol)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to complete patrol")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun applyForLeave(
        type: String,
        start: String,
        end: String,
        reason: String,
        emergencyPhone: String? = null,
        emergencyAddress: String? = null,
        doctorReport: String? = null,
        eventDetails: String? = null
    ): Result<LeaveApplication> {
        return try {
            val response = api.applyForLeave(
                CreateLeaveRequest(
                    leaveType = type,
                    startDate = start,
                    endDate = end,
                    reason = reason,
                    emergencyPhone = emergencyPhone,
                    emergencyAddress = emergencyAddress,
                    doctorReport = doctorReport,
                    eventDetails = eventDetails
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to submit leave application")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun creditHoliday(balanceId: String, days: Double = 2.0): Result<LeaveBalance> {
        return try {
            val response = api.creditHoliday(balanceId, CreditHolidayRequest(days = days))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to credit holiday leave")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun fetchAllLeaveBalances(stationId: String? = null): Result<List<LeaveBalance>> {
        return try {
            val response = api.getAllLeaveBalances(stationId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch station leave balances")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun fetchLeaveAccrualRecords(): Result<List<LeaveAccrualRecord>> {
        return try {
            val response = api.getLeaveAccrualRecords()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch leave accrual records")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun processMonthlyAccruals(): Result<ProcessAccrualResponse> {
        return try {
            val response = api.processMonthlyAccruals()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to execute monthly leave accrual")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun validateRoster(
        stationId: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        rosterId: String? = null
    ): Result<ValidateRosterResponse> {
        return try {
            val req = ValidateRosterRequest(
                rosterId = rosterId,
                stationId = stationId,
                startDate = startDate,
                endDate = endDate
            )
            var response = api.validateRoster(req)
            if (response.code() in listOf(404, 405)) {
                response = api.validateDutyRoster(req)
            }
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to validate roster")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun approveRoster(
        stationId: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        rosterId: String? = null
    ): Result<String> {
        return try {
            val req = ApproveRosterRequest(
                rosterId = rosterId,
                stationId = stationId,
                startDate = startDate,
                endDate = endDate
            )
            var response = api.approveRoster(req)
            if (response.code() in listOf(404, 405)) {
                response = api.approveDutyRoster(req)
            }
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()?.message ?: "Roster approved.")
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to approve roster")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun updateManagedUser(userId: String, request: UpdateUserRequest): Result<User> = try {
        val response = api.updateUser(userId, request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to update personnel record")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

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
                Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun updateStation(id: String, request: UpdateStationRequest): Result<Station> = try {
        val response = api.updateStation(id, request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to update station")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun fetchGuardPairs(station: String? = null): Result<List<GuardPair>> {
        return try {
            val response = api.getGuardPairs(station)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch guard pairings"))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun updateGuardPair(id: String, request: UpdateGuardPairRequest): Result<GuardPair> = try {
        val response = api.updateGuardPair(id, request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to update guard pair")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun updateEscortStatus(id: String, status: String): Result<EscortDuty> {
        return try {
            val response = api.setEscortDutyStatus(id, UpdateDutyStatusRequest(status = status))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to update escort status")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun updateEscortDuty(id: String, request: UpdateEscortDutyRequest): Result<EscortDuty> = try {
        val response = api.updateEscortDuty(id, request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to update escort duty")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun deleteEscortDuty(id: String): Result<Unit> = try {
        val response = api.deleteEscortDuty(id)
        if (response.isSuccessful) Result.success(Unit)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to cancel escort duty")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

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
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun updateExamStatus(id: String, status: String): Result<ExamDuty> {
        return try {
            val response = api.setExamDutyStatus(id, UpdateDutyStatusRequest(status = status))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to update exam status")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun updateExamDuty(id: String, request: UpdateExamDutyRequest): Result<ExamDuty> = try {
        val response = api.updateExamDuty(id, request)
        if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to update exam duty")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

    suspend fun deleteExamDuty(id: String): Result<Unit> = try {
        val response = api.deleteExamDuty(id)
        if (response.isSuccessful) Result.success(Unit)
        else Result.failure(Exception(parseDrfError(response.errorBody()?.string(), "Failed to cancel exam duty")))
    } catch (e: Exception) { Result.failure(sanitizeException(e)) }

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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun markNotificationRead(id: String): Result<Unit> {
        return try {
            val response = api.markNotificationRead(id)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to mark alert as read"))
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun markAllNotificationsRead(): Result<Unit> {
        return try {
            val response = api.markAllNotificationsRead()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to mark all alerts as read"))
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun updateUserProfile(
        firstName: String?,
        lastName: String?,
        phoneNumber: String?,
        email: String? = null,
        address: String? = null,
        profilePhoto: String? = null
    ): Result<User> {
        return try {
            val request = UpdateProfileRequest(
                firstName = firstName,
                lastName = lastName,
                phoneNumber = phoneNumber,
                email = email,
                address = address,
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
            Result.failure(sanitizeException(e))
        }
    }

    suspend fun uploadProfilePhoto(
        bytes: ByteArray,
        filename: String = "profile.jpg",
        mimeType: String = "image/jpeg"
    ): Result<User> {
        return try {
            val mediaType = mimeType.toMediaTypeOrNull()
            val reqBody = bytes.toRequestBody(mediaType)
            val part = okhttp3.MultipartBody.Part.createFormData("photo", filename, reqBody)
            val response = api.uploadProfilePhoto(part)
            if (response.isSuccessful && response.body() != null) {
                val updatedUser = response.body()!!
                sessionManager.saveUser(updatedUser)
                Result.success(updatedUser)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to upload photo")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e))
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

    // --- Public Holidays & National Holiday Duties ---
    suspend fun fetchPublicHolidays(): Result<List<PublicHoliday>> {
        return try {
            val response = api.getPublicHolidays()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch public holidays (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to fetch public holidays"))
        }
    }

    suspend fun fetchHolidayDuties(status: String? = null, station: String? = null): Result<List<PublicHolidayDutyRecord>> {
        return try {
            val response = api.getHolidayDuties(status = status, station = station)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch holiday duties (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to fetch holiday duties"))
        }
    }

    suspend fun approveHolidayDuty(id: String, reason: String): Result<PublicHolidayDutyRecord> {
        return try {
            val response = api.approveHolidayDuty(id, ReviewHolidayDutyRequest(reason = reason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to approve holiday duty (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to approve holiday duty"))
        }
    }

    suspend fun rejectHolidayDuty(id: String, reason: String): Result<PublicHolidayDutyRecord> {
        return try {
            val response = api.rejectHolidayDuty(id, ReviewHolidayDutyRequest(reason = reason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to reject holiday duty (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to reject holiday duty"))
        }
    }

    // --- Early Clock-Out OTP Generation (Administrator & Supervisor) ---
    suspend fun generateEarlyClockoutOtp(shiftId: String, reason: String): Result<GenerateEarlyClockoutOtpResponse> {
        return try {
            val req = GenerateEarlyClockoutOtpRequest(shiftId = shiftId, reason = reason)
            var response = api.generateEarlyClockoutOtp(req)
            if (response.code() in listOf(404, 405)) {
                response = api.generateEarlyClockoutOtpShift(req)
            }
            if (response.code() in listOf(404, 405)) {
                response = api.generateEarlyClockoutOtpHyphen(req)
            }
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to generate authorization OTP (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to generate authorization OTP"))
        }
    }

    // --- Phase 13 Guard Operations & Communications ---
    suspend fun fetchDutyState(): Result<DutyStateResponse> {
        return try {
            val response = api.getDutyState()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch duty state (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to fetch duty state"))
        }
    }

    suspend fun submitLateArrivalReport(
        shiftId: String,
        reason: String,
        incidentDetails: String = "",
        estimatedArrival: String = ""
    ): Result<LateArrivalReportResponse> {
        return try {
            val req = LateArrivalReportRequest(
                shiftId = shiftId,
                reason = reason,
                incidentDetails = incidentDetails,
                estimatedArrival = estimatedArrival
            )
            val response = api.submitLateArrivalReport(req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to submit late report (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to submit late report"))
        }
    }

    suspend fun triggerSos(
        latitude: Double?,
        longitude: Double?,
        category: String = "General Officer Distress",
        emergencyDetails: String = ""
    ): Result<SosDistressResponse> {
        return try {
            val req = SosDistressRequest(
                latitude = latitude,
                longitude = longitude,
                category = category,
                emergencyDetails = emergencyDetails
            )
            val response = api.triggerSos(req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to dispatch SOS (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to dispatch SOS"))
        }
    }

    suspend fun fetchDirectMessages(withUser: String? = null): Result<List<DirectMessage>> {
        return try {
            val response = api.getDirectMessages(withUser)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to fetch messages (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to fetch messages"))
        }
    }

    suspend fun sendDirectMessage(recipientId: String, content: String): Result<DirectMessage> {
        return try {
            val req = DirectMessageCreateRequest(recipient = recipientId, content = content)
            val response = api.sendDirectMessage(req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to send message (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to send message"))
        }
    }

    suspend fun fetchUnreadMessageCount(): Result<Int> {
        return try {
            val response = api.getUnreadMessageCount()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.unreadCount)
            } else {
                Result.success(0)
            }
        } catch (e: Exception) {
            Result.success(0)
        }
    }

    suspend fun markDirectMessageRead(id: String): Result<DirectMessage> {
        return try {
            val response = api.markMessageRead(id)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = parseDrfError(response.errorBody()?.string(), "Failed to mark message read (${response.code()})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(sanitizeException(e, "Failed to mark message read"))
        }
    }
}
