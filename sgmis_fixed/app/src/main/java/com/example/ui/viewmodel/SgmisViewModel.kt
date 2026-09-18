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
    val isLockedOut: Boolean = false,
    val lockoutRemainingMinutes: Int = 0,
    val isAppLocked: Boolean = false,
    val serverUrl: String = "",
    val themeMode: com.example.ui.theme.ThemeMode = com.example.ui.theme.ThemeMode.SYSTEM,
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

    // Visitor Register
    val visitors: List<OccurrenceBookEntry> = emptyList(),
    val visitorsLoading: Boolean = false,

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
    val leaveSummary: LeaveSummary? = null,
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
    val unreadNotificationCount: Int = 0,

    // Supervisory & Administrative
    val users: List<User> = emptyList(),
    val stations: List<Station> = emptyList(),
    val guardPairs: List<GuardPair> = emptyList(),
    val rosterShifts: List<Shift> = emptyList(),
    val attendanceRecords: List<Attendance> = emptyList(),
    val examinationPeriods: List<ExaminationPeriod> = emptyList(),
    val temporaryAssignments: List<TemporaryAssignmentAudit> = emptyList(),
    val conflictReport: ConflictReport? = null,
    val rosterConflictsLoading: Boolean = false,
    val adminLoading: Boolean = false,

    // Telemetry & Settings
    val telemetry: TelemetryOverview = TelemetryOverview()
)

class SgmisViewModel(private val repository: SgmisRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SgmisUiState(
            currentUser = repository.currentUser,
            isLoggedIn = repository.isLoggedIn,
            serverUrl = repository.serverUrl,
            themeMode = repository.themeMode
        )
    )
    val uiState: StateFlow<SgmisUiState> = _uiState.asStateFlow()

    fun setThemeMode(mode: com.example.ui.theme.ThemeMode) {
        repository.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    init {
        if (repository.isLoggedIn) {
            loadInitialDashboardData()
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

    private var inactivityJob: kotlinx.coroutines.Job? = null

    fun onUserInteraction() {
        if (_uiState.value.isLoggedIn && !_uiState.value.isAppLocked) {
            resetInactivityTimer()
        }
    }

    fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            // Non-destructive Idle Lock: 3 minutes inactivity timer (180 seconds)
            kotlinx.coroutines.delay(3 * 60 * 1000L)
            if (_uiState.value.isLoggedIn) {
                _uiState.update {
                    it.copy(isAppLocked = true)
                }
            }
        }
    }

    fun lockApp() {
        if (_uiState.value.isLoggedIn) {
            inactivityJob?.cancel()
            _uiState.update { it.copy(isAppLocked = true) }
        }
    }

    fun unlockApp(password: String, onResult: (Boolean, String?) -> Unit) {
        val user = _uiState.value.currentUser
        if (user == null) {
            onResult(false, "User session not found.")
            return
        }
        if (password.isBlank()) {
            onResult(false, "Password cannot be empty.")
            return
        }

        viewModelScope.launch {
            val result = repository.login(user.username, password)
            result.onSuccess {
                _uiState.update {
                    it.copy(isAppLocked = false)
                }
                resetInactivityTimer()
                onResult(true, null)
            }.onFailure { err ->
                val msg = err.message ?: "Incorrect password. Please try again."
                onResult(false, msg)
            }
        }
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
                        isLockedOut = false,
                        lockoutRemainingMinutes = 0,
                        successMessage = "Authenticated as ${user.fullName ?: user.username}"
                    )
                }
                resetInactivityTimer()
                loadInitialDashboardData()
                onSuccess()
            }.onFailure { err ->
                val msg = err.message ?: "Authentication failed."
                val isLocked = msg.contains("locked", ignoreCase = true) || msg.contains("lockout", ignoreCase = true)
                val remainingMin = if (isLocked) 15 else 0
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLockedOut = isLocked,
                        lockoutRemainingMinutes = remainingMin,
                        errorMessage = msg
                    )
                }
            }
        }
    }

    fun requestPasswordReset(identifier: String, onSuccess: (String) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.requestPasswordReset(identifier)
            res.onSuccess { msg ->
                _uiState.update { it.copy(isLoading = false, successMessage = msg) }
                onSuccess(msg)
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun confirmPasswordReset(identifier: String, otp: String, newPass: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.confirmPasswordReset(identifier, otp, newPass)
            res.onSuccess { msg ->
                _uiState.update {
                    it.copy(isLoading = false, isLockedOut = false, successMessage = msg)
                }
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun logout() {
        inactivityJob?.cancel()
        repository.logout()
        _uiState.update {
            SgmisUiState(
                currentUser = null,
                isLoggedIn = false,
                serverUrl = repository.serverUrl
            )
        }
    }

    fun loadInitialDashboardData() {
        resetInactivityTimer()
        fetchTodayShift()
        fetchUnreadNotificationCount()
        val role = _uiState.value.currentUser?.role?.uppercase()
        if (role == "SUPERVISOR" || role == "ADMIN" || role == "ADMINISTRATOR") {
            fetchTelemetry()
        }
    }

    fun refreshAllData() {
        resetInactivityTimer()
        fetchCurrentUser()
        fetchTodayShift()
        fetchHandovers()
        fetchOBEntries()
        fetchVisitors()
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
    fun fetchTodayShift(stationId: String? = null, guardId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(shiftLoading = true) }
            val res = repository.fetchTodayShift(stationId, guardId)
            res.onSuccess { shift ->
                _uiState.update { it.copy(todayShift = shift, shiftLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(shiftLoading = false, errorMessage = err.message) }
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

    fun clockOut(
        shiftId: String,
        lat: Double?,
        lon: Double?,
        supervisorUsername: String? = null,
        supervisorPassword: String? = null,
        overrideReason: String? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(clockLoading = true, errorMessage = null) }
            val res = repository.clockOut(
                shiftId = shiftId,
                lat = lat,
                lon = lon,
                supervisorUsername = supervisorUsername,
                supervisorPassword = supervisorPassword,
                overrideReason = overrideReason
            )
            res.onSuccess { att ->
                _uiState.update {
                    it.copy(
                        clockLoading = false,
                        successMessage = "Clock-out logged at ${att.clockOut ?: "now"}. Duty completed."
                    )
                }
                fetchTodayShift()
                val role = _uiState.value.currentUser?.role?.uppercase()
                if (role == "SUPERVISOR" || role == "ADMIN" || role == "ADMINISTRATOR") {
                    fetchAttendanceRecords()
                }
                onSuccess?.invoke()
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
            }.onFailure { err ->
                _uiState.update { it.copy(handoversLoading = false, errorMessage = it.errorMessage ?: err.message) }
            }
        }
    }

    fun submitHandover(
        outgoingShiftId: String,
        occurrence: String,
        equipment: String,
        keys: String,
        pending: String,
        emergencyOverride: Boolean = false,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.submitHandover(outgoingShiftId, occurrence, equipment, keys, pending, emergencyOverride)
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
            res.onSuccess { updatedHandover ->
                _uiState.update { state ->
                    val updatedList = state.handovers.map {
                        if (it.id == handoverId) updatedHandover else it
                    }
                    state.copy(
                        isLoading = false,
                        handovers = updatedList,
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

    fun rejectHandover(handoverId: String, reason: String = "", onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.rejectHandover(handoverId, reason)
            res.onSuccess { updatedHandover ->
                _uiState.update { state ->
                    val updatedList = state.handovers.map {
                        if (it.id == handoverId) updatedHandover else it
                    }
                    state.copy(
                        isLoading = false,
                        handovers = updatedList,
                        successMessage = "Handover rejected and flagged for review."
                    )
                }
                fetchHandovers()
                onSuccess?.invoke()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Rejection failed.")
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
            }.onFailure { err ->
                _uiState.update { it.copy(obLoading = false, errorMessage = it.errorMessage ?: err.message) }
            }
        }
    }

    fun submitOBEntry(category: String, text: String, checkRecord: String, crossReference: String? = null, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.submitOBEntry(category, text, checkRecord, crossReference)
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

    fun amendOBEntry(id: String, reason: String, amendedText: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.amendOBEntry(id, reason, amendedText)
            res.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = response.message
                    )
                }
                fetchOBEntries()
                onSuccess()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to amend OB entry.")
                }
            }
        }
    }

    // --- Visitor Register ---
    fun fetchVisitors() {
        viewModelScope.launch {
            _uiState.update { it.copy(visitorsLoading = true) }
            val res = repository.fetchVisitors()
            res.onSuccess { list ->
                _uiState.update { it.copy(visitors = list, visitorsLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(visitorsLoading = false, errorMessage = it.errorMessage ?: err.message) }
            }
        }
    }

    fun logVisitor(
        name: String,
        idNumber: String?,
        personToVisit: String,
        purpose: String,
        timeIn: String,
        timeOut: String?,
        vehicleRegNumber: String? = null,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.logVisitor(name, idNumber, personToVisit, purpose, timeIn, timeOut, vehicleRegNumber)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Visitor $name logged in official register.")
                }
                fetchVisitors()
                onSuccess()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to log visitor.")
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
            }.onFailure { err ->
                _uiState.update { it.copy(incidentsLoading = false, errorMessage = it.errorMessage ?: err.message) }
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

    fun amendIncident(id: String, reason: String, amendedDescription: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.amendIncident(id, reason, amendedDescription)
            res.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = response.message
                    )
                }
                fetchIncidents()
                onSuccess()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to amend incident.")
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

    fun startPatrol(stationId: String? = null, notes: String = "Patrol round initiated") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.startPatrol(stationId, notes)
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
                        successMessage = "Patrol debrief completed."
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

            val sumRes = repository.fetchLeaveSummary()
            sumRes.onSuccess { s -> _uiState.update { it.copy(leaveSummary = s) } }

            val appsRes = repository.fetchLeaveApplications()
            appsRes.onSuccess { apps ->
                _uiState.update { it.copy(leaveApplications = apps, leaveLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(leaveLoading = false) }
            }
        }
    }

    fun applyForLeave(
        type: String,
        start: String,
        end: String,
        reason: String,
        emergencyPhone: String? = null,
        emergencyAddress: String? = null,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.applyForLeave(type, start, end, reason, emergencyPhone, emergencyAddress)
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

    fun reviewLeaveApplication(id: String, status: String, notes: String, rejectionReason: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.reviewLeaveApplication(id, status, notes, rejectionReason)
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

    fun creditHoliday(days: Double = 2.0) {
        val balId = _uiState.value.leaveBalance?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.creditHoliday(balId, days)
            res.onSuccess { updated ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        leaveBalance = updated,
                        successMessage = "Successfully credited $days holiday compensation days."
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun autoAllocateExams(date: String, strategy: String, count: Int, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.autoAllocateExams(date, strategy, count)
            res.onSuccess { msg ->
                _uiState.update { it.copy(isLoading = false, successMessage = msg) }
                fetchExamDuties()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun autoAllocateEscorts(startTime: String, endTime: String, strategy: String, count: Int, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.autoAllocateEscorts(startTime, endTime, strategy, count)
            res.onSuccess { msg ->
                _uiState.update { it.copy(isLoading = false, successMessage = msg) }
                fetchEscortDuties()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun approveRoster(stationId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.approveRoster(stationId)
            res.onSuccess { msg ->
                _uiState.update { it.copy(isLoading = false, successMessage = msg) }
                fetchRosterShifts()
                onSuccess()
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
    fun fetchUnreadNotificationCount() {
        viewModelScope.launch {
            val res = repository.fetchUnreadNotificationCount()
            res.onSuccess { count ->
                _uiState.update { it.copy(unreadNotificationCount = count) }
            }
        }
    }

    fun fetchNotifications() {
        viewModelScope.launch {
            _uiState.update { it.copy(notificationsLoading = true) }
            val res = repository.fetchNotifications()
            res.onSuccess { alerts ->
                val unread = alerts.count { !it.read }
                _uiState.update { it.copy(notifications = alerts, unreadNotificationCount = unread, notificationsLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(notificationsLoading = false) }
            }
        }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch {
            val res = repository.markNotificationRead(id)
            res.onSuccess {
                _uiState.update { current ->
                    val updated = current.notifications.map {
                        if (it.id == id) it.copy(read = true) else it
                    }
                    val newCount = (current.unreadNotificationCount - 1).coerceAtLeast(0)
                    current.copy(notifications = updated, unreadNotificationCount = newCount)
                }
            }
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            val res = repository.markAllNotificationsRead()
            res.onSuccess {
                _uiState.update { current ->
                    val updated = current.notifications.map { it.copy(read = true) }
                    current.copy(
                        notifications = updated,
                        unreadNotificationCount = 0,
                        successMessage = "All alerts marked as read."
                    )
                }
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
            }.onFailure { err ->
                _uiState.update { it.copy(adminLoading = false, errorMessage = it.errorMessage ?: err.message) }
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

    fun assignUserStation(userId: String, stationId: String?, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.assignUserStation(userId, stationId)
            res.onSuccess { updatedUser ->
                _uiState.update { state ->
                    val updatedList = state.users.map { if (it.id == userId) updatedUser else it }
                    state.copy(
                        isLoading = false,
                        users = updatedList,
                        successMessage = "Station assigned to ${updatedUser.fullName ?: updatedUser.username}."
                    )
                }
                fetchUsers()
                onSuccess()
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
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = it.errorMessage ?: err.message) }
            }
        }
    }

    fun createStation(name: String, code: String, address: String, lat: Double, lon: Double, geofence: Double = 200.0, onSuccess: () -> Unit) {
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
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = it.errorMessage ?: err.message) }
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
            val res = if (date != null) {
                repository.fetchOperationalRoster(stationId = station, startDate = date, endDate = date)
            } else {
                repository.fetchOperationalRoster(stationId = station)
            }
            res.onSuccess { list ->
                _uiState.update { it.copy(rosterShifts = list, adminLoading = false) }
            }.onFailure { _ ->
                val fallback = repository.fetchShifts(date, station)
                fallback.onSuccess { list ->
                    _uiState.update { it.copy(rosterShifts = list, adminLoading = false) }
                }.onFailure { fallbackErr ->
                    _uiState.update { it.copy(adminLoading = false, errorMessage = it.errorMessage ?: fallbackErr.message) }
                }
            }
        }
    }

    fun generateRoster(
        stationId: String,
        startDate: String,
        cycleDays: Int = 12,
        mode: String = "NORMAL",
        examinationPeriodId: String? = null,
        examVenueName: String? = null,
        examGuardIds: List<String> = emptyList(),
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.generateRoster(
                stationId = stationId,
                startDate = startDate,
                cycleDays = cycleDays,
                mode = mode,
                examinationPeriodId = examinationPeriodId,
                examVenueName = examVenueName,
                examGuardIds = examGuardIds
            )
            res.onSuccess { resp ->
                val msg = if (resp.message.isNotBlank()) resp.message else "Shift roster generated successfully in $mode mode."
                _uiState.update {
                    it.copy(isLoading = false, successMessage = msg)
                }
                fetchRosterShifts(station = stationId)
                fetchTodayShift()
                detectConflicts(stationId, startDate)
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun detectConflicts(stationId: String, startDate: String? = null, endDate: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(rosterConflictsLoading = true) }
            val res = repository.detectConflicts(stationId, startDate, endDate)
            res.onSuccess { report ->
                _uiState.update { it.copy(conflictReport = report, rosterConflictsLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(rosterConflictsLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun approveRoster(stationId: String, startDate: String? = null, endDate: String? = null, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.approveRoster(stationId, startDate, endDate)
            res.onSuccess { msg ->
                _uiState.update { it.copy(isLoading = false, successMessage = msg) }
                fetchRosterShifts(station = stationId)
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun scheduleExamEscort(
        stationId: String,
        date: String,
        guardIds: List<String>,
        startTime: String = "06:00:00",
        endTime: String = "17:00:00",
        reason: String = "Examination paper collection escort to University National Centre",
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.scheduleExamEscort(stationId, date, guardIds, startTime, endTime, reason)
            res.onSuccess {
                _uiState.update { state ->
                    state.copy(isLoading = false, successMessage = "Examination collection escort (06:00-17:00) scheduled.")
                }
                fetchRosterShifts(station = stationId)
                fetchTemporaryAssignments(stationId)
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun resumeNormalRoster(stationId: String, afterDate: String, cycleDays: Int = 12, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.resumeNormalRoster(stationId, afterDate, cycleDays)
            res.onSuccess {
                _uiState.update { state ->
                    state.copy(isLoading = false, successMessage = "Resumed normal 4-day rotating roster from $afterDate.")
                }
                fetchRosterShifts(station = stationId)
                fetchExaminationPeriods(stationId)
                detectConflicts(stationId, afterDate)
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun fetchTemporaryAssignments(stationId: String? = null) {
        viewModelScope.launch {
            val res = repository.fetchTemporaryAssignments(stationId)
            res.onSuccess { list ->
                _uiState.update { it.copy(temporaryAssignments = list) }
            }
        }
    }

    fun fetchExaminationPeriods(stationId: String? = null) {
        viewModelScope.launch {
            val res = repository.fetchExaminationPeriods(stationId)
            res.onSuccess { list ->
                _uiState.update { it.copy(examinationPeriods = list) }
            }
        }
    }

    fun createExaminationPeriod(
        stationId: String,
        name: String,
        venueName: String,
        startDate: String,
        endDate: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.createExaminationPeriod(stationId, name, venueName, startDate, endDate)
            res.onSuccess { ep ->
                _uiState.update { state ->
                    state.copy(isLoading = false, successMessage = "Examination period '${ep.name}' created.")
                }
                fetchExaminationPeriods(stationId)
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
            }.onFailure { err ->
                _uiState.update { it.copy(adminLoading = false, errorMessage = it.errorMessage ?: err.message) }
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
        val theme = try {
            com.example.ui.theme.ThemeMode.valueOf(mode)
        } catch (e: Exception) {
            com.example.ui.theme.ThemeMode.SYSTEM
        }
        setThemeMode(theme)
    }

    fun broadcastNotice(title: String, message: String, targetRole: String? = null, stationId: String? = null, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.broadcastNotice(title, message, targetRole, stationId)
            res.onSuccess {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = "Broadcast notification dispatched.")
                }
                fetchNotifications()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
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
