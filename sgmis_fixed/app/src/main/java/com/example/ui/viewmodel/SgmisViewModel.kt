package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.SgmisRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SgmisUiState(
    val currentUser: User? = null,
    val isLoggedIn: Boolean = false,
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,

    // Operations Data
    val todayShift: Shift? = null,
    val shiftLoading: Boolean = false,
    val clockLoading: Boolean = false,

    // Handovers
    val handovers: List<ShiftHandover> = emptyList(),
    val handoversLoading: Boolean = false,

    // Occurrence Book
    val obEntries: List<OccurrenceBookEntry> = emptyList(),
    val obLoading: Boolean = false,

    // Incidents
    val incidents: List<IncidentReport> = emptyList(),
    val incidentsLoading: Boolean = false,

    // Patrols
    val checkpoints: List<Checkpoint> = emptyList(),
    val activePatrol: PatrolLog? = null,
    val patrolLogs: List<PatrolLog> = emptyList(),
    val patrolsLoading: Boolean = false,

    // Leave
    val leaveBalance: LeaveBalance? = null,
    val leaveApplications: List<LeaveApplication> = emptyList(),
    val leaveLoading: Boolean = false,

    // Escorts & Exams
    val escortDuties: List<EscortDuty> = emptyList(),
    val escortsLoading: Boolean = false,
    val examDuties: List<ExamDuty> = emptyList(),
    val examsLoading: Boolean = false,

    // Notifications
    val notifications: List<NotificationAlert> = emptyList(),
    val notificationsLoading: Boolean = false,

    // Supervisory & Administrative
    val users: List<User> = emptyList(),
    val stations: List<Station> = emptyList(),
    val guardPairs: List<GuardPair> = emptyList(),
    val rosterShifts: List<Shift> = emptyList(),
    val attendanceRecords: List<Attendance> = emptyList(),
    val adminLoading: Boolean = false,

    // Telemetry & Settings
    val telemetry: TelemetryOverview = TelemetryOverview(),
    val themeMode: String = "SYSTEM" // "SYSTEM", "LIGHT", "DARK"
)

class SgmisViewModel(private val repository: SgmisRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SgmisUiState(
            currentUser = repository.currentUser,
            isLoggedIn = repository.isLoggedIn,
            serverUrl = repository.serverUrl
        )
    )
    val uiState: StateFlow<SgmisUiState> = _uiState.asStateFlow()

    init {
        if (repository.isLoggedIn) {
            refreshAllData()
        }
    }

    fun updateServerUrl(url: String) {
        repository.serverUrl = url
        _uiState.update { it.copy(serverUrl = repository.serverUrl) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    // --- Authentication ---
    fun login(identifier: String, pass: String, onSuccess: () -> Unit = {}) {
        if (identifier.isBlank() || pass.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Username or Employee ID and password are required.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.login(identifier, pass)
            result.onSuccess { user ->
                _uiState.update {
                    it.copy(
                        currentUser = user,
                        isLoggedIn = true,
                        isLoading = false,
                        successMessage = "Authenticated as ${user.fullName ?: user.username}"
                    )
                }
                refreshAllData()
                onSuccess()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Authentication failed."
                    )
                }
            }
        }
    }

    fun logout() {
        repository.logout()
        _uiState.update {
            SgmisUiState(
                currentUser = null,
                isLoggedIn = false,
                serverUrl = repository.serverUrl
            )
        }
    }

    fun refreshAllData() {
        fetchCurrentUser()
        fetchTodayShift()
        fetchHandovers()
        fetchOBEntries()
        fetchIncidents()
        fetchCheckpoints()
        fetchPatrolLogs()
        fetchLeave()
        fetchEscortDuties()
        fetchExamDuties()
        fetchNotifications()
        fetchTelemetry()

        val role = _uiState.value.currentUser?.role?.uppercase()
        if (role == "SUPERVISOR" || role == "ADMIN" || role == "ADMINISTRATOR") {
            fetchUsers()
            fetchStations()
            fetchGuardPairs()
            fetchRosterShifts()
            fetchAttendanceRecords()
        }
    }

    fun fetchCurrentUser() {
        viewModelScope.launch {
            val res = repository.fetchCurrentUser()
            res.onSuccess { user ->
                _uiState.update { it.copy(currentUser = user) }
            }
        }
    }

    // --- Shifts & Attendance ---
    fun fetchTodayShift() {
        viewModelScope.launch {
            _uiState.update { it.copy(shiftLoading = true) }
            val res = repository.fetchTodayShift()
            res.onSuccess { shift ->
                _uiState.update { it.copy(todayShift = shift, shiftLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(shiftLoading = false) }
            }
        }
    }

    fun clockIn(shiftId: String, lat: Double?, lon: Double?, lateReason: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(clockLoading = true, errorMessage = null) }
            val res = repository.clockIn(shiftId, lat, lon, lateReason)
            res.onSuccess { att ->
                _uiState.update {
                    it.copy(
                        clockLoading = false,
                        successMessage = "Clock-in recorded successfully at ${att.clockIn ?: "now"}"
                    )
                }
                fetchTodayShift()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        clockLoading = false,
                        errorMessage = err.message ?: "Clock-in failed."
                    )
                }
            }
        }
    }

    fun clockOut(shiftId: String, lat: Double?, lon: Double?) {
        viewModelScope.launch {
            _uiState.update { it.copy(clockLoading = true, errorMessage = null) }
            val res = repository.clockOut(shiftId, lat, lon)
            res.onSuccess { att ->
                _uiState.update {
                    it.copy(
                        clockLoading = false,
                        successMessage = "Clock-out logged at ${att.clockOut ?: "now"}. Duty completed."
                    )
                }
                fetchTodayShift()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        clockLoading = false,
                        errorMessage = err.message ?: "Clock-out failed."
                    )
                }
            }
        }
    }

    // --- Handovers ---
    fun fetchHandovers() {
        viewModelScope.launch {
            _uiState.update { it.copy(handoversLoading = true) }
            val res = repository.fetchHandovers()
            res.onSuccess { list ->
                _uiState.update { it.copy(handovers = list, handoversLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(handoversLoading = false) }
            }
        }
    }

    fun submitHandover(
        outgoingShiftId: String,
        occurrence: String,
        equipment: String,
        keys: String,
        pending: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.submitHandover(outgoingShiftId, occurrence, equipment, keys, pending)
            res.onSuccess { h ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Handover transferred to incoming guard: ${h.incomingGuardName}"
                    )
                }
                fetchHandovers()
                onSuccess()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Handover submission failed."
                    )
                }
            }
        }
    }

    fun acceptHandover(handoverId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.acceptHandover(handoverId)
            res.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Handover acknowledged and accepted."
                    )
                }
                fetchHandovers()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Acceptance failed.")
                }
            }
        }
    }

    // --- Occurrence Book ---
    fun fetchOBEntries() {
        viewModelScope.launch {
            _uiState.update { it.copy(obLoading = true) }
            val res = repository.fetchOBEntries()
            res.onSuccess { entries ->
                _uiState.update { it.copy(obEntries = entries, obLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(obLoading = false) }
            }
        }
    }

    fun submitOBEntry(category: String, text: String, checkRecord: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.submitOBEntry(category, text, checkRecord)
            res.onSuccess { entry ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Entry ${entry.entryNumber} recorded in Occurrence Book."
                    )
                }
                fetchOBEntries()
                onSuccess()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to log OB entry.")
                }
            }
        }
    }

    // --- Incidents ---
    fun fetchIncidents() {
        viewModelScope.launch {
            _uiState.update { it.copy(incidentsLoading = true) }
            val res = repository.fetchIncidents()
            res.onSuccess { list ->
                _uiState.update { it.copy(incidents = list, incidentsLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(incidentsLoading = false) }
            }
        }
    }

    fun submitIncident(priority: String, title: String, desc: String, loc: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.submitIncident(priority, title, desc, loc)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Incident report filed successfully.")
                }
                fetchIncidents()
                onSuccess()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to file report.")
                }
            }
        }
    }

    // --- Patrols ---
    fun fetchCheckpoints() {
        viewModelScope.launch {
            val res = repository.fetchCheckpoints()
            res.onSuccess { cps -> _uiState.update { it.copy(checkpoints = cps) } }
        }
    }

    fun fetchPatrolLogs() {
        viewModelScope.launch {
            val res = repository.fetchPatrolLogs()
            res.onSuccess { logs ->
                val active = logs.firstOrNull { it.status == "IN_PROGRESS" }
                _uiState.update { it.copy(patrolLogs = logs, activePatrol = active) }
            }
        }
    }

    fun startPatrol() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.startPatrol()
            res.onSuccess { patrol ->
                _uiState.update {
                    it.copy(
                        activePatrol = patrol,
                        isLoading = false,
                        successMessage = "Patrol initiated for ${patrol.stationName}"
                    )
                }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun scanCheckpoint(patrolId: String, checkpointId: String, gps: String, notes: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.scanCheckpoint(patrolId, checkpointId, gps, notes)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Checkpoint verification logged.")
                }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun finishPatrol(patrolId: String, notes: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.finishPatrol(patrolId, notes)
            res.onSuccess {
                _uiState.update {
                    it.copy(
                        activePatrol = null,
                        isLoading = false,
                        successMessage = "Patrol patrol debrief completed."
                    )
                }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    // --- Leave ---
    fun fetchLeave() {
        viewModelScope.launch {
            _uiState.update { it.copy(leaveLoading = true) }
            val balRes = repository.fetchLeaveBalance()
            balRes.onSuccess { b -> _uiState.update { it.copy(leaveBalance = b) } }

            val appsRes = repository.fetchLeaveApplications()
            appsRes.onSuccess { apps ->
                _uiState.update { it.copy(leaveApplications = apps, leaveLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(leaveLoading = false) }
            }
        }
    }

    fun applyForLeave(type: String, start: String, end: String, reason: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.applyForLeave(type, start, end, reason)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Leave application submitted.")
                }
                fetchLeave()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun reviewLeaveApplication(id: String, status: String, notes: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.reviewLeaveApplication(id, status, notes)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Leave application $status successfully.")
                }
                fetchLeave()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    // --- Incident Actions ---
    fun acknowledgeIncident(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.acknowledgeIncident(id)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Incident acknowledged.")
                }
                fetchIncidents()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun resolveIncident(id: String, notes: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.resolveIncident(id, notes)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Incident marked as RESOLVED.")
                }
                fetchIncidents()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    // --- Escort Duties ---
    fun fetchEscortDuties() {
        viewModelScope.launch {
            _uiState.update { it.copy(escortsLoading = true) }
            val res = repository.fetchEscortDuties()
            res.onSuccess { list ->
                _uiState.update { it.copy(escortDuties = list, escortsLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(escortsLoading = false) }
            }
        }
    }

    fun createEscortDuty(request: CreateEscortDutyRequest, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.createEscortDuty(request)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Escort duty logged successfully.")
                }
                fetchEscortDuties()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun updateEscortStatus(id: String, status: String) {
        viewModelScope.launch {
            val res = repository.updateEscortStatus(id, status)
            res.onSuccess {
                _uiState.update {
                    it.copy(successMessage = "Escort status updated to $status.")
                }
                fetchEscortDuties()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    // --- Exam Duties ---
    fun fetchExamDuties() {
        viewModelScope.launch {
            _uiState.update { it.copy(examsLoading = true) }
            val res = repository.fetchExamDuties()
            res.onSuccess { list ->
                _uiState.update { it.copy(examDuties = list, examsLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(examsLoading = false) }
            }
        }
    }

    fun createExamDuty(request: CreateExamDutyRequest, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.createExamDuty(request)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Exam security duty scheduled.")
                }
                fetchExamDuties()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun updateExamStatus(id: String, status: String) {
        viewModelScope.launch {
            val res = repository.updateExamStatus(id, status)
            res.onSuccess {
                _uiState.update {
                    it.copy(successMessage = "Exam duty status updated to $status.")
                }
                fetchExamDuties()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    // --- Notifications ---
    fun fetchNotifications() {
        viewModelScope.launch {
            _uiState.update { it.copy(notificationsLoading = true) }
            val res = repository.fetchNotifications()
            res.onSuccess { alerts ->
                _uiState.update { it.copy(notifications = alerts, notificationsLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(notificationsLoading = false) }
            }
        }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch {
            val res = repository.markNotificationRead(id)
            res.onSuccess { fetchNotifications() }
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            val res = repository.markAllNotificationsRead()
            res.onSuccess {
                _uiState.update { it.copy(successMessage = "All alerts marked as read.") }
                fetchNotifications()
            }
        }
    }

    // --- Users & Guards Management ---
    fun fetchUsers(role: String? = null, station: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(adminLoading = true) }
            val res = repository.fetchUsers(role, station)
            res.onSuccess { userList ->
                _uiState.update { it.copy(users = userList, adminLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(adminLoading = false) }
            }
        }
    }

    fun createUser(request: CreateUserRequest, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.createUser(request)
            res.onSuccess { u ->
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Account created for ${u.username} (${u.role})")
                }
                fetchUsers()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun toggleUserActive(user: User) {
        viewModelScope.launch {
            val res = repository.toggleUserActive(user.id, user.isActive)
            res.onSuccess { updated ->
                _uiState.update {
                    it.copy(successMessage = "${updated.username} is now ${if (updated.isActive) "Active" else "Deactivated"}")
                }
                fetchUsers()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun assignUserStation(userId: String, stationId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.assignUserStation(userId, stationId)
            res.onSuccess { updated ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Assigned ${updated.username} to ${updated.stationName ?: "station"}."
                    )
                }
                fetchUsers()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    // --- Stations Management ---
    fun fetchStations() {
        viewModelScope.launch {
            val res = repository.fetchStations()
            res.onSuccess { list ->
                _uiState.update { it.copy(stations = list) }
            }
        }
    }

    fun createStation(name: String, code: String, address: String, lat: Double, lon: Double, geofence: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.createStation(name, code, address, lat, lon, geofence)
            res.onSuccess { st ->
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Station ${st.name} registered.")
                }
                fetchStations()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    // --- Guard Pairs Management ---
    fun fetchGuardPairs(stationId: String? = null) {
        viewModelScope.launch {
            val res = repository.fetchGuardPairs(stationId)
            res.onSuccess { list ->
                _uiState.update { it.copy(guardPairs = list) }
            }
        }
    }

    fun createGuardPair(stationId: String, guardA: String, guardB: String, order: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.createGuardPair(stationId, guardA, guardB, order)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Guard pair established.")
                }
                fetchGuardPairs()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    // --- Shifts & Roster Management ---
    fun fetchRosterShifts(date: String? = null, station: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(adminLoading = true) }
            val res = repository.fetchShifts(date, station)
            res.onSuccess { list ->
                _uiState.update { it.copy(rosterShifts = list, adminLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(adminLoading = false) }
            }
        }
    }

    fun generateRoster(stationId: String, startDate: String, cycleDays: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.generateRoster(stationId, startDate, cycleDays)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Automated shift roster generated successfully.")
                }
                fetchRosterShifts(station = stationId)
                fetchTodayShift()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    // --- Attendance Records ---
    fun fetchAttendanceRecords(date: String? = null, shift: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(adminLoading = true) }
            val res = repository.fetchAttendanceRecords(date, shift)
            res.onSuccess { list ->
                _uiState.update { it.copy(attendanceRecords = list, adminLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(adminLoading = false) }
            }
        }
    }

    // --- Telemetry & Profile Management ---
    fun fetchTelemetry() {
        viewModelScope.launch {
            val res = repository.fetchTelemetry()
            res.onSuccess { telemetryData ->
                _uiState.update { it.copy(telemetry = telemetryData) }
            }
        }
    }

    fun updateProfile(
        firstName: String?,
        lastName: String?,
        phoneNumber: String?,
        profilePhoto: String? = null,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.updateUserProfile(firstName, lastName, phoneNumber, profilePhoto)
            res.onSuccess { updatedUser ->
                _uiState.update {
                    it.copy(
                        currentUser = updatedUser,
                        isLoading = false,
                        successMessage = "Personnel profile updated successfully."
                    )
                }
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun setThemeMode(mode: String) {
        _uiState.update { it.copy(themeMode = mode) }
    }
}

class SgmisViewModelFactory(private val repository: SgmisRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SgmisViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SgmisViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
