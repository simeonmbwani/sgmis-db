package com.example.data.repository

import com.example.data.api.ApiClient
import com.example.data.api.SessionManager
import com.example.data.local.CachedIncidentEntity
import com.example.data.local.CachedOBEntity
import com.example.data.local.CachedShiftEntity
import com.example.data.local.SgmisDatabase
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SgmisRepository(
    private val apiClient: ApiClient,
    private val sessionManager: SessionManager,
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

    // --- Error Parser Helper ---
    private fun parseDrfError(errorBody: String?, fallback: String): String {
        if (errorBody.isNullOrBlank()) return fallback
        return try {
            val jsonObj = org.json.JSONObject(errorBody)
            if (jsonObj.has("detail")) {
                return jsonObj.getString("detail")
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
            errorBody.take(160)
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
            Result.failure(e)
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
            Result.failure(e)
        }
    }

    fun logout() {
        sessionManager.clearSession()
        apiClient.invalidateClient()
    }

    // --- Today's Shift ---
    suspend fun fetchTodayShift(): Result<Shift?> {
        return try {
            val response = api.getTodayShift()
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
                val err = response.errorBody()?.string() ?: "Clock-in failed"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clockOut(shiftId: String, lat: Double?, lon: Double?): Result<Attendance> {
        return try {
            val response = api.clockOut(ClockOutRequest(shiftId, lat, lon))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = response.errorBody()?.string() ?: "Clock-out failed"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
        pendingIssues: String
    ): Result<ShiftHandover> {
        return try {
            val response = api.createHandover(
                CreateHandoverRequest(
                    outgoingShift = outgoingShiftId,
                    occurrenceSummary = occurrenceSummary,
                    equipmentIssued = equipment,
                    keysHandedOver = keys,
                    pendingIssues = pendingIssues
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = response.errorBody()?.string() ?: "Failed to submit handover"
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
                val err = response.errorBody()?.string() ?: "Failed to accept handover"
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

    suspend fun submitOBEntry(category: String, text: String, checkRecord: String): Result<OccurrenceBookEntry> {
        return try {
            val response = api.createOBEntry(
                CreateOBEntryRequest(category = category, occurrenceText = text, checkRecord = checkRecord)
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
                val err = response.errorBody()?.string() ?: "Failed to file incident report"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Patrols ---
    suspend fun fetchCheckpoints(): Result<List<Checkpoint>> {
        return try {
            val response = api.getCheckpoints()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch station checkpoints"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchPatrolLogs(): Result<List<PatrolLog>> {
        return try {
            val response = api.getPatrolLogs()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch patrol history"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startPatrol(): Result<PatrolLog> {
        return try {
            val response = api.startPatrol(emptyMap())
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = response.errorBody()?.string() ?: "Failed to initiate patrol"
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
                val err = response.errorBody()?.string() ?: "Checkpoint scan verification failed"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun finishPatrol(patrolId: String, notes: String): Result<PatrolLog> {
        return try {
            val response = api.finishPatrol(patrolId, mapOf("notes" to notes))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = response.errorBody()?.string() ?: "Failed to complete patrol"
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

    suspend fun applyForLeave(type: String, start: String, end: String, reason: String): Result<LeaveApplication> {
        return try {
            val response = api.applyForLeave(CreateLeaveRequest(type, start, end, reason))
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

    suspend fun reviewLeaveApplication(id: String, status: String, reviewerNotes: String): Result<LeaveApplication> {
        return try {
            val response = api.reviewLeaveApplication(id, LeaveReviewRequest(status, reviewerNotes))
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
            val response = api.updateUser(userId, mapOf("is_active" to !currentActive))
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
            val response = api.updateUser(userId, mapOf("station" to stationId))
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
            val response = api.getStations()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch stations"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createStation(name: String, code: String, address: String, lat: Double, lon: Double, geofence: Int): Result<Station> {
        return try {
            val response = api.createStation(CreateStationRequest(name, code, address, lat, lon, geofence))
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
                Result.failure(Exception("Failed to fetch roster shifts"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateRoster(stationId: String, startDate: String, cycleDays: Int): Result<Map<String, Any>> {
        return try {
            val response = api.generateRoster(RosterGenerateRequest(stationId, startDate, cycleDays))
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
            val response = api.updateEscortDuty(id, mapOf("status" to status))
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
            val response = api.updateExamDuty(id, mapOf("status" to status))
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
}
