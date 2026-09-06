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
                val errorMsg = response.errorBody()?.string() ?: "Authentication failed (${response.code()})"
                Result.failure(Exception(errorMsg))
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
                val err = response.errorBody()?.string() ?: "Failed to record OB entry"
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
                val err = response.errorBody()?.string() ?: "Failed to submit leave application"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
