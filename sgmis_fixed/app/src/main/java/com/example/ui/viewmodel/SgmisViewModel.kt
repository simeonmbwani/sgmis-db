package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.SgmisRepository
import com.example.util.LocationHelper
import com.example.util.NotificationHelper
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
    val assignedPatrols: List<PatrolLog> = emptyList(),
    val unsyncedPatrolEventsCount: Int = 0,
    val isSyncingPatrolEvents: Boolean = false,
    val isReviewingPatrol: Boolean = false,
    val patrolsLoading: Boolean = false,

    // Leave
    val leaveBalance: LeaveBalance? = null,
    val leaveSummary: LeaveSummary? = null,
    val leaveApplications: List<LeaveApplication> = emptyList(),
    val stationLeaveBalances: List<LeaveBalance> = emptyList(),
    val leaveAccrualRecords: List<LeaveAccrualRecord> = emptyList(),
    val leaveLoading: Boolean = false,
    val accrualProcessing: Boolean = false,

    // Escorts & Exams
    val escortDuties: List<EscortDuty> = emptyList(),
    val escortsLoading: Boolean = false,
    val examDuties: List<ExamDuty> = emptyList(),
    val examsLoading: Boolean = false,

    // Notifications
    val notifications: List<NotificationAlert> = emptyList(),
    val notificationsLoading: Boolean = false,
    val unreadNotificationCount: Int = 0,

    // Direct Messages & Communications
    val directMessages: List<DirectMessage> = emptyList(),
    val messagesLoading: Boolean = false,
    val unreadMessageCount: Int = 0,

    // Phase 13 Guard Operations
    val serverDutyState: DutyStateResponse? = null,
    val isFilingLateReport: Boolean = false,
    val lastLateReportCaseNumber: String? = null,
    val isDispatchingSos: Boolean = false,
    val sosAlertMessage: String? = null,

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
    val recordAdjustments: List<RecordAdjustmentRequest> = emptyList(),
    val selectedRecordAdjustment: RecordAdjustmentRequest? = null,
    val adjustmentsLoading: Boolean = false,
    val administrativeHistory: List<AdministrativeHistoryEntry> = emptyList(),
    val administrativeHistoryLoading: Boolean = false,
    val leaveAdjustmentHistory: List<LeaveAdjustmentRecord> = emptyList(),
    val openingBalanceSaving: Boolean = false,
    val dutyReassignmentSaving: Boolean = false,

    // Station Coverage & Duty Swapping
    val stationCoverage: StationCoverageResponse? = null,
    val stationCoverageLoading: Boolean = false,
    val dutySwapping: Boolean = false,

    // Public Holidays & Early Clockout OTP
    val publicHolidays: List<PublicHoliday> = emptyList(),
    val holidayDutyRecords: List<PublicHolidayDutyRecord> = emptyList(),
    val activeEarlyClockoutOtp: GenerateEarlyClockoutOtpResponse? = null,
    val isGeneratingOtp: Boolean = false,
    val isReviewingHolidayDuty: Boolean = false,

    // Redesign: Supervisor & Admin Dashboards
    val supervisorDashboard: SupervisorDashboardResponse? = null,
    val supervisorDashboardLoading: Boolean = false,
    val adminDashboard: AdminDashboardResponse? = null,
    val adminDashboardLoading: Boolean = false,

    // Organization Policies
    val organizationPolicies: List<OrganizationPolicy> = emptyList(),
    val policiesLoading: Boolean = false,

    // Duty Overrides
    val dutyOverrides: List<DutyOverride> = emptyList(),
    val dutyOverridesLoading: Boolean = false,
    val isCreatingDutyOverride: Boolean = false,

    // Pair Reassignments
    val pairReassignments: List<GuardPairReassignmentAudit> = emptyList(),
    val pairReassignmentsLoading: Boolean = false,
    val isReassigningPair: Boolean = false,

    // Telemetry & Settings
    val telemetry: TelemetryOverview = TelemetryOverview()
) {

    val appRole: AppRole get() = currentUser?.appRole ?: AppRole.GUARD
    val isGuard: Boolean get() = appRole == AppRole.GUARD
    val isSupervisor: Boolean get() = appRole == AppRole.SUPERVISOR
    val isAdmin: Boolean get() = appRole == AppRole.ADMINISTRATOR
    val isSupervisorOrAdmin: Boolean get() = appRole != AppRole.GUARD

    // Server-Authoritative Duty State for Guard
    val guardDutyState: GuardDutyState get() = serverDutyState?.let {
        when (it.dutyState.trim().uppercase()) {
            "ON_DUTY" -> GuardDutyState.ON_DUTY
            "ON_LEAVE" -> GuardDutyState.ON_LEAVE
            "TIME_OFF" -> GuardDutyState.TIME_OFF
            "ELIGIBLE_FOR_DUTY" -> GuardDutyState.ELIGIBLE_FOR_DUTY
            "EARLY_EXIT_PENDING" -> GuardDutyState.EARLY_EXIT_PENDING
            "EXAM" -> GuardDutyState.EXAM
            "ESCORT" -> GuardDutyState.ESCORT
            else -> GuardDutyState.OFF_DUTY
        }
    } ?: GuardDutyState.fromShift(todayShift)

    val isOnDuty: Boolean get() = guardDutyState == GuardDutyState.ON_DUTY
    val isOffDuty: Boolean get() = guardDutyState.isOffDuty
    val isEligibleForDuty: Boolean get() = guardDutyState == GuardDutyState.ELIGIBLE_FOR_DUTY
    val isOnLeave: Boolean get() = guardDutyState == GuardDutyState.ON_LEAVE
    val isExamDuty: Boolean get() = guardDutyState == GuardDutyState.EXAM
    val isEscortDuty: Boolean get() = guardDutyState == GuardDutyState.ESCORT

    // Guard operational capability check (enforces that guards must be ON_DUTY or on special duty to execute operational events)
    val canPerformGuardOperations: Boolean get() = !isGuard || isOnDuty || guardDutyState.isSpecialDuty

    // Authoritative station context
    val currentStationId: String? get() = currentUser?.station ?: todayShift?.station
    val currentStationName: String
        get() = currentUser?.stationName
            ?: todayShift?.stationName
            ?: stations.find { it.id == currentStationId }?.name
            ?: "Station Unassigned"
    val hasAssignedStation: Boolean
        get() = !currentStationId.isNullOrBlank() && currentStationName != "Station Unassigned"

    val currentStation: Station?
        get() = stations.find { it.id == currentStationId || it.name.equals(currentStationName, ignoreCase = true) }

    val stationGeofenceRadius: Double?
        get() = currentStation?.geofenceRadiusMeters ?: currentStation?.geofenceRadius

    // Authoritative partner context
    val assignedPartnerName: String get() = todayShift?.partnerName ?: "Solo / Unassigned"
    val assignedPartnerEmployeeNumber: String? get() = todayShift?.partnerEmployeeNumber
}

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

    private fun adminOnly(): Boolean = _uiState.value.currentUser?.appRole == AppRole.ADMINISTRATOR

    fun fetchRecordAdjustments() {
        if (_uiState.value.currentUser?.appRole == AppRole.GUARD) return
        viewModelScope.launch {
            _uiState.update { it.copy(adjustmentsLoading = true) }
            repository.fetchRecordAdjustments().onSuccess { rows ->
                _uiState.update { it.copy(recordAdjustments = rows, adjustmentsLoading = false, errorMessage = null) }
            }.onFailure { e -> _uiState.update { it.copy(adjustmentsLoading = false, errorMessage = e.message ?: "Unable to load record adjustments. Please try again.") } }
        }
    }

    fun submitRecordAdjustment(request: CreateRecordAdjustmentRequest, onSuccess: () -> Unit = {}) {
        if (_uiState.value.currentUser?.appRole !in listOf(AppRole.SUPERVISOR, AppRole.ADMINISTRATOR)) return
        viewModelScope.launch {
            _uiState.update { it.copy(adjustmentsLoading = true, errorMessage = null) }
            repository.createRecordAdjustment(request).onSuccess { row ->
                _uiState.update { it.copy(adjustmentsLoading = false, successMessage = "Adjustment request submitted.", recordAdjustments = listOf(row) + it.recordAdjustments) }
                fetchRecordAdjustments()
                onSuccess()
            }.onFailure { e -> _uiState.update { it.copy(adjustmentsLoading = false, errorMessage = e.message) } }
        }
    }

    fun reviewRecordAdjustment(id: String, approve: Boolean, valueOrReason: String = "") {
        if (!adminOnly()) return
        viewModelScope.launch {
            _uiState.update { it.copy(adjustmentsLoading = true, errorMessage = null) }
            val result = if (approve) repository.approveRecordAdjustment(id, valueOrReason.ifBlank { null })
                else repository.rejectRecordAdjustment(id, valueOrReason)
            result.onSuccess {
                _uiState.update { it.copy(adjustmentsLoading = false, selectedRecordAdjustment = null, successMessage = if (approve) "Adjustment approved." else "Adjustment rejected.") }
                fetchRecordAdjustments()
                fetchAdministrativeHistory()
            }.onFailure { e -> _uiState.update { it.copy(adjustmentsLoading = false, errorMessage = e.message) } }
        }
    }

    fun selectRecordAdjustment(row: RecordAdjustmentRequest?) { _uiState.update { it.copy(selectedRecordAdjustment = row) } }

    fun fetchAdministrativeHistory(
        search: String? = null,
        kind: String? = null,
        station: String? = null
    ) {
        if (!adminOnly()) return
        viewModelScope.launch {
            _uiState.update { it.copy(administrativeHistoryLoading = true) }
            repository.fetchAdministrativeHistory(search, kind, station).onSuccess { rows ->
                _uiState.update { it.copy(administrativeHistory = rows, administrativeHistoryLoading = false, errorMessage = null) }
            }.onFailure { e -> _uiState.update { it.copy(administrativeHistoryLoading = false, errorMessage = e.message ?: "Unable to load administrative history. Please try again.") } }
            repository.fetchLeaveAdjustments().onSuccess { rows -> _uiState.update { it.copy(leaveAdjustmentHistory = rows) } }
        }
    }


    fun setOpeningBalance(request: SetOpeningBalanceRequest, onSuccess: () -> Unit = {}) {
        if (!adminOnly()) return
        viewModelScope.launch {
            _uiState.update { it.copy(openingBalanceSaving = true, errorMessage = null) }
            repository.setOpeningBalance(request).onSuccess { response ->
                _uiState.update { it.copy(openingBalanceSaving = false, successMessage = response.message, leaveAdjustmentHistory = response.adjustments + it.leaveAdjustmentHistory) }
                fetchStationLeaveBalances()
                fetchAdministrativeHistory()
                onSuccess()
            }.onFailure { e -> _uiState.update { it.copy(openingBalanceSaving = false, errorMessage = e.message) } }
        }
    }

    fun reassignDuty(request: ReassignDutyRequest, onSuccess: () -> Unit = {}) {
        if (!adminOnly()) return
        viewModelScope.launch {
            _uiState.update { it.copy(dutyReassignmentSaving = true, errorMessage = null) }
            repository.reassignDuty(request).onSuccess { response ->
                _uiState.update { it.copy(dutyReassignmentSaving = false, successMessage = response.message) }
                fetchRosterShifts()
                fetchAdministrativeHistory()
                onSuccess()
            }.onFailure { e -> _uiState.update { it.copy(dutyReassignmentSaving = false, errorMessage = e.message) } }
        }
    }

    fun reassignSingleShift(shiftId: String, request: ReassignSingleShiftRequest, onSuccess: () -> Unit = {}) {
        if (_uiState.value.currentUser?.appRole == AppRole.GUARD) return
        viewModelScope.launch {
            _uiState.update { it.copy(dutyReassignmentSaving = true, errorMessage = null) }
            repository.reassignSingleShift(shiftId, request).onSuccess { response ->
                _uiState.update { it.copy(dutyReassignmentSaving = false, successMessage = response.message) }
                fetchRosterShifts()
                fetchStationCoverage()
                fetchAdministrativeHistory()
                onSuccess()
            }.onFailure { e -> _uiState.update { it.copy(dutyReassignmentSaving = false, errorMessage = e.message) } }
        }
    }

    fun fetchStationCoverage(stationId: String? = null, date: String? = null) {
        if (_uiState.value.currentUser?.appRole == AppRole.GUARD) return
        val effectiveStation = stationId ?: _uiState.value.currentUser?.station
        viewModelScope.launch {
            _uiState.update { it.copy(stationCoverageLoading = true) }
            repository.getStationCoverage(effectiveStation, date).onSuccess { coverage ->
                _uiState.update { it.copy(stationCoverage = coverage, stationCoverageLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(stationCoverageLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun swapPairDuties(request: SwapPairDutiesRequest, onSuccess: () -> Unit = {}) {
        if (_uiState.value.currentUser?.appRole == AppRole.GUARD) return
        viewModelScope.launch {
            _uiState.update { it.copy(dutySwapping = true, errorMessage = null) }
            repository.swapPairDuties(request).onSuccess { res ->
                _uiState.update { it.copy(dutySwapping = false, successMessage = res.message) }
                fetchRosterShifts()
                fetchStationCoverage()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(dutySwapping = false, errorMessage = err.message) }
            }
        }
    }

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
        fetchDutyState()
        fetchUnreadNotificationCount()
        fetchUnreadMessageCount()
        fetchDirectMessages()
        val role = _uiState.value.currentUser?.role?.uppercase()
        if (role == "SUPERVISOR" || role == "ADMIN" || role == "ADMINISTRATOR") {
            fetchTelemetry()
            fetchStationCoverage()
            fetchUsers(role = "GUARD")
            fetchRosterShifts()
        }
    }

    fun refreshAllData() {
        resetInactivityTimer()
        fetchCurrentUser()
        fetchTodayShift()
        fetchDutyState()
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
        fetchDirectMessages()
        fetchUnreadMessageCount()
        fetchTelemetry()

        val role = _uiState.value.currentUser?.role?.uppercase()
        if (role == "SUPERVISOR" || role == "ADMIN" || role == "ADMINISTRATOR") {
            fetchUsers()
            fetchStations()
            fetchGuardPairs()
            fetchRosterShifts()
            fetchStationCoverage()
            fetchAttendanceRecords()
            fetchPublicHolidays()
            fetchHolidayDutyRecords()
        }
    }

    fun refreshAuthoritativeState() {
        if (_uiState.value.isLoggedIn) {
            resetInactivityTimer()
            fetchCurrentUser()
            fetchTodayShift()
            fetchDutyState()
            fetchRosterShifts()
            fetchOBEntries()
            fetchVisitors()
            fetchLeave()
            fetchUnreadNotificationCount()
            fetchUnreadMessageCount()
            fetchDirectMessages()
            if (_uiState.value.isSupervisorOrAdmin) {
                fetchTelemetry()
                fetchStationCoverage()
                fetchIncidents()
                fetchPatrolLogs()
                fetchHandovers()
                fetchAttendanceRecords()
                fetchUsers()
                fetchStations()
                fetchPublicHolidays()
                fetchHolidayDutyRecords()
            }
        }
    }

    fun postSecurityAlert(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
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

    fun clockIn(
        shiftId: String,
        lat: Double?,
        lon: Double?,
        lateReason: String? = null,
        caseNumber: String? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(clockLoading = true, errorMessage = null) }
            val res = repository.clockIn(shiftId, lat, lon, lateReason, caseNumber)
            res.onSuccess { att ->
                _uiState.update {
                    it.copy(
                        clockLoading = false,
                        successMessage = "Clock-in recorded successfully at ${att.clockIn ?: "now"}"
                    )
                }
                fetchTodayShift()
                fetchDutyState()
                onSuccess?.invoke()
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
        otpCode: String? = null,
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
                overrideReason = overrideReason,
                otpCode = otpCode
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

    fun acceptHandover(handoverId: String, onSuccess: (() -> Unit)? = null) {
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
                onSuccess?.invoke()
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
            res.onSuccess { cps ->
                _uiState.update { it.copy(checkpoints = cps, errorMessage = null) }
            }.onFailure { err ->
                if (_uiState.value.checkpoints.isEmpty()) {
                    _uiState.update { it.copy(errorMessage = err.message ?: "Unable to load station checkpoints. Please try again.") }
                }
            }
        }
    }

    fun fetchPatrolLogs(
        search: String? = null,
        status: String? = null,
        guard: String? = null,
        date: String? = null,
        archived: Boolean? = null
    ) {
        viewModelScope.launch {
            val res = repository.fetchPatrolLogs(search, status, guard, date, archived)
            res.onSuccess { logs ->
                val active = logs.firstOrNull { it.status == "IN_PROGRESS" || it.status == "ACTIVE" }
                val assigned = logs.filter { it.status == "ASSIGNED" }
                val unsyncedCount = repository.getUnsyncedPatrolEventsCount()
                _uiState.update {
                    it.copy(
                        patrolLogs = logs,
                        activePatrol = active,
                        assignedPatrols = assigned,
                        unsyncedPatrolEventsCount = unsyncedCount,
                        errorMessage = null
                    )
                }

            }.onFailure { err ->
                if (_uiState.value.patrolLogs.isEmpty()) {
                    _uiState.update { it.copy(errorMessage = err.message ?: "Unable to load station patrols. Please try again.") }
                }
            }
        }
    }

    fun startAssignedPatrol(patrolId: String, notes: String? = "Patrol started by guard") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.startAssignedPatrol(patrolId, notes)
            res.onSuccess { patrol ->
                _uiState.update {
                    it.copy(
                        activePatrol = patrol,
                        isLoading = false,
                        successMessage = "Patrol '${patrol.name}' started successfully."
                    )
                }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun assignPatrol(
        guardId: String,
        stationId: String? = null,
        name: String = "Routine Station Patrol",
        startWindow: String? = null,
        deadline: String? = null,
        notes: String? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.assignPatrol(guardId, stationId, name, startWindow, deadline, notes)
            res.onSuccess {
                _uiState.update { it.copy(isLoading = false, successMessage = "Patrol assigned to guard successfully.") }
                fetchPatrolLogs()
                onSuccess?.invoke()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun cancelPatrol(patrolId: String, reason: String = "Cancelled by supervisor") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.cancelPatrol(patrolId, reason)
            res.onSuccess {
                _uiState.update { it.copy(isLoading = false, successMessage = "Patrol cancelled.") }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun reassignPatrol(patrolId: String, newGuardId: String, reason: String = "Reassigned by supervisor") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.reassignPatrol(patrolId, newGuardId, reason)
            res.onSuccess {
                _uiState.update { it.copy(isLoading = false, successMessage = "Patrol reassigned.") }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun approvePatrol(patrolId: String, notes: String? = "Patrol verified and approved") {
        viewModelScope.launch {
            _uiState.update { it.copy(isReviewingPatrol = true, errorMessage = null) }
            val res = repository.approvePatrol(patrolId, notes)
            res.onSuccess {
                _uiState.update { it.copy(isReviewingPatrol = false, successMessage = "Patrol log approved.") }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isReviewingPatrol = false, errorMessage = err.message) }
            }
        }
    }

    fun rejectPatrol(patrolId: String, reason: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isReviewingPatrol = true, errorMessage = null) }
            val res = repository.rejectPatrol(patrolId, reason)
            res.onSuccess {
                _uiState.update { it.copy(isReviewingPatrol = false, successMessage = "Patrol marked rejected.") }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isReviewingPatrol = false, errorMessage = err.message) }
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

    fun scanCheckpoint(
        patrolId: String,
        checkpointId: String,
        gps: String,
        notes: String,
        checkpointCode: String = "",
        checkpointOrder: Int = 1,
        verificationMethod: String = "NFC",
        accuracy: Double? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.scanCheckpoint(
                patrolId = patrolId,
                checkpointId = checkpointId,
                gps = gps,
                notes = notes,
                checkpointCode = checkpointCode,
                checkpointOrder = checkpointOrder,
                verificationMethod = verificationMethod,
                accuracy = accuracy
            )
            res.onSuccess {
                val unsyncedCount = repository.getUnsyncedPatrolEventsCount()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Checkpoint verification logged ($verificationMethod).",
                        unsyncedPatrolEventsCount = unsyncedCount
                    )
                }
                fetchPatrolLogs()
            }.onFailure { err ->
                val unsyncedCount = repository.getUnsyncedPatrolEventsCount()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message,
                        unsyncedPatrolEventsCount = unsyncedCount
                    )
                }
            }
        }
    }

    fun syncOfflinePatrolEvents(patrolId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingPatrolEvents = true, errorMessage = null) }
            val res = repository.syncOfflinePatrolEvents(patrolId)
            res.onSuccess { syncRes ->
                val unsyncedCount = repository.getUnsyncedPatrolEventsCount()
                _uiState.update {
                    it.copy(
                        isSyncingPatrolEvents = false,
                        unsyncedPatrolEventsCount = unsyncedCount,
                        successMessage = "Synced ${syncRes.syncedCount} patrol events successfully."
                    )
                }
                fetchPatrolLogs()
            }.onFailure { err ->
                _uiState.update { it.copy(isSyncingPatrolEvents = false, errorMessage = err.message) }
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
        doctorReport: String? = null,
        eventDetails: String? = null,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.applyForLeave(type, start, end, reason, emergencyPhone, emergencyAddress, doctorReport, eventDetails)
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

    fun fetchStationLeaveBalances(stationId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(leaveLoading = true) }
            val res = repository.fetchAllLeaveBalances(stationId)
            res.onSuccess { list ->
                _uiState.update { it.copy(stationLeaveBalances = list, leaveLoading = false, errorMessage = null) }
            }.onFailure { err ->
                _uiState.update { it.copy(leaveLoading = false, errorMessage = err.message ?: "Unable to load station leave balances. Please try again.") }
            }
        }
    }

    fun fetchLeaveAccrualRecords() {
        viewModelScope.launch {
            _uiState.update { it.copy(leaveLoading = true) }
            val res = repository.fetchLeaveAccrualRecords()
            res.onSuccess { list ->
                _uiState.update { it.copy(leaveAccrualRecords = list, leaveLoading = false, errorMessage = null) }
            }.onFailure { err ->
                _uiState.update { it.copy(leaveLoading = false, errorMessage = err.message ?: "Unable to load leave accrual records. Please try again.") }
            }
        }
    }

    fun processMonthlyAccruals(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(accrualProcessing = true, errorMessage = null) }
            val res = repository.processMonthlyAccruals()
            res.onSuccess { resp ->
                _uiState.update {
                    it.copy(
                        accrualProcessing = false,
                        successMessage = "${resp.message} (${resp.recordsCreated} records created for ${resp.guardsEvaluated} guards as of ${resp.asOfDate})"
                    )
                }
                fetchLeave()
                fetchStationLeaveBalances()
                fetchLeaveAccrualRecords()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(accrualProcessing = false, errorMessage = err.message) }
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
        approveRoster(stationId, null, null, onSuccess)
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
            }.onFailure { error ->
                _uiState.update { it.copy(escortsLoading = false, errorMessage = error.message ?: "Unable to load escort duties. Please try again.") }
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

    fun editEscortDuty(id: String, request: UpdateEscortDutyRequest, onSuccess: () -> Unit = {}) {
        if (_uiState.value.currentUser?.appRole !in listOf(AppRole.SUPERVISOR, AppRole.ADMINISTRATOR)) return
        viewModelScope.launch {
            repository.updateEscortDuty(id, request).onSuccess { _uiState.update { it.copy(successMessage = "Escort duty updated.") }; fetchEscortDuties(); onSuccess() }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
        }
    }

    fun cancelEscortDuty(id: String) {
        if (_uiState.value.currentUser?.appRole != AppRole.ADMINISTRATOR) return
        updateEscortStatus(id, "CANCELLED")
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
            }.onFailure { error ->
                _uiState.update { it.copy(examsLoading = false, errorMessage = error.message ?: "Unable to load exam duties. Please try again.") }
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

    fun editExamDuty(id: String, request: UpdateExamDutyRequest, onSuccess: () -> Unit = {}) {
        if (_uiState.value.currentUser?.appRole !in listOf(AppRole.SUPERVISOR, AppRole.ADMINISTRATOR)) return
        viewModelScope.launch {
            repository.updateExamDuty(id, request).onSuccess { _uiState.update { it.copy(successMessage = "Exam duty updated.") }; fetchExamDuties(); onSuccess() }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
        }
    }

    fun cancelExamDuty(id: String) {
        if (_uiState.value.currentUser?.appRole != AppRole.ADMINISTRATOR) return
        updateExamStatus(id, "CANCELLED")
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

    fun updateManagedUser(userId: String, request: UpdateUserRequest, onSuccess: () -> Unit = {}) {
        if (!adminOnly()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.updateManagedUser(userId, request).onSuccess { user ->
                _uiState.update { state -> state.copy(isLoading = false, successMessage = "Personnel record updated.", users = state.users.map { if (it.id == user.id) user else it }) }
                fetchUsers()
                onSuccess()
            }.onFailure { e -> _uiState.update { it.copy(isLoading = false, errorMessage = e.message) } }
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

    fun updateStation(id: String, request: UpdateStationRequest, onSuccess: () -> Unit = {}) {
        if (!adminOnly()) return
        viewModelScope.launch {
            repository.updateStation(id, request).onSuccess { station ->
                _uiState.update { s -> s.copy(stations = s.stations.map { if (it.id == id) station else it }, successMessage = "Station updated.") }
                fetchStations(); onSuccess()
            }.onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
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

    fun updateGuardPair(id: String, request: UpdateGuardPairRequest, onSuccess: () -> Unit = {}) {
        if (!adminOnly()) return
        viewModelScope.launch {
            repository.updateGuardPair(id, request).onSuccess {
                _uiState.update { it.copy(successMessage = "Guard pair updated.") }
                fetchGuardPairs(); onSuccess()
            }.onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
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
            if (res.isFailure && res.exceptionOrNull()?.message?.contains("VALIDATED", ignoreCase = true) == true) {
                // Roster requires validation first: run validation and retry approval
                val valRes = repository.validateRoster(stationId = stationId, startDate = startDate, endDate = endDate)
                if (valRes.isSuccess && valRes.getOrNull()?.valid == true) {
                    val retryRes = repository.approveRoster(stationId, startDate, endDate)
                    retryRes.onSuccess { msg ->
                        _uiState.update { it.copy(isLoading = false, successMessage = msg) }
                        fetchRosterShifts(station = stationId)
                        onSuccess()
                        return@launch
                    }.onFailure { err ->
                        _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                        return@launch
                    }
                }
            }
            res.onSuccess { msg ->
                _uiState.update { it.copy(isLoading = false, successMessage = msg) }
                fetchRosterShifts(station = stationId)
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun validateRoster(stationId: String, startDate: String? = null, endDate: String? = null, onSuccess: (ValidateRosterResponse) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.validateRoster(stationId, startDate, endDate)
            res.onSuccess { valResp ->
                _uiState.update { it.copy(isLoading = false, successMessage = valResp.message ?: "Roster validated successfully.") }
                onSuccess(valResp)
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
        email: String? = null,
        address: String? = null,
        profilePhoto: String? = null,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.updateUserProfile(
                firstName = firstName,
                lastName = lastName,
                phoneNumber = phoneNumber,
                email = email,
                address = address,
                profilePhoto = profilePhoto
            )
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

    fun uploadProfilePhoto(
        bytes: ByteArray,
        filename: String = "profile.jpg",
        mimeType: String = "image/jpeg",
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.uploadProfilePhoto(bytes, filename, mimeType)
            res.onSuccess { updatedUser ->
                _uiState.update {
                    it.copy(
                        currentUser = updatedUser,
                        isLoading = false,
                        successMessage = "Profile photo updated successfully."
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

    // --- Zimbabwe Public Holidays & National Holiday Duties ---
    fun fetchPublicHolidays() {
        viewModelScope.launch {
            val res = repository.fetchPublicHolidays()
            res.onSuccess { holidays ->
                _uiState.update { it.copy(publicHolidays = holidays) }
            }
        }
    }

    fun fetchHolidayDutyRecords(status: String? = null, station: String? = null) {
        viewModelScope.launch {
            val res = repository.fetchHolidayDuties(status = status, station = station)
            res.onSuccess { records ->
                _uiState.update { it.copy(holidayDutyRecords = records) }
            }
        }
    }

    fun approveHolidayDuty(id: String, reason: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isReviewingHolidayDuty = true, errorMessage = null) }
            val res = repository.approveHolidayDuty(id, reason)
            res.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        isReviewingHolidayDuty = false,
                        successMessage = "Holiday duty compensation approved (2 days credited)."
                    )
                }
                fetchHolidayDutyRecords()
                fetchLeave()
                fetchTelemetry()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isReviewingHolidayDuty = false, errorMessage = err.message) }
            }
        }
    }

    fun rejectHolidayDuty(id: String, reason: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isReviewingHolidayDuty = true, errorMessage = null) }
            val res = repository.rejectHolidayDuty(id, reason)
            res.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        isReviewingHolidayDuty = false,
                        successMessage = "Holiday duty compensation rejected."
                    )
                }
                fetchHolidayDutyRecords()
                fetchLeave()
                fetchTelemetry()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isReviewingHolidayDuty = false, errorMessage = err.message) }
            }
        }
    }

    // --- Early Clock-Out OTP Generation (Administrator & Supervisor) ---
    fun generateEarlyClockoutOtp(shiftId: String, reason: String, onSuccess: (GenerateEarlyClockoutOtpResponse) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingOtp = true, errorMessage = null) }
            val res = repository.generateEarlyClockoutOtp(shiftId, reason)
            res.onSuccess { response ->
                _uiState.update { state ->
                    state.copy(
                        isGeneratingOtp = false,
                        activeEarlyClockoutOtp = response,
                        successMessage = "Early Clock-Out OTP generated (Code: ${response.otp} - Valid 5 min)."
                    )
                }
                onSuccess(response)
            }.onFailure { err ->
                _uiState.update { it.copy(isGeneratingOtp = false, errorMessage = err.message) }
            }
        }
    }

    fun clearActiveOtp() {
        _uiState.update { it.copy(activeEarlyClockoutOtp = null) }
    }

    // --- Phase 13 Guard Operations & Communications ---
    fun fetchDutyState() {
        viewModelScope.launch {
            val res = repository.fetchDutyState()
            res.onSuccess { dutyResp ->
                _uiState.update { it.copy(serverDutyState = dutyResp) }
            }
        }
    }

    fun submitLateArrivalReport(
        shiftId: String,
        reason: String,
        incidentDetails: String = "",
        estimatedArrival: String = "",
        onSuccess: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isFilingLateReport = true, errorMessage = null) }
            val res = repository.submitLateArrivalReport(shiftId, reason, incidentDetails, estimatedArrival)
            res.onSuccess { resp ->
                _uiState.update {
                    it.copy(
                        isFilingLateReport = false,
                        lastLateReportCaseNumber = resp.caseNumber,
                        successMessage = "Late Arrival Report logged (Case #${resp.caseNumber}). You may now clock in."
                    )
                }
                fetchOBEntries()
                onSuccess(resp.caseNumber)
            }.onFailure { err ->
                _uiState.update { it.copy(isFilingLateReport = false, errorMessage = err.message) }
            }
        }
    }

    fun triggerEmergencySos(
        context: Context,
        category: String = "General Officer Distress",
        emergencyDetails: String = "",
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDispatchingSos = true, errorMessage = null) }
            val loc = LocationHelper.getDeviceLocation(context)
            val res = repository.triggerSos(
                latitude = loc?.first,
                longitude = loc?.second,
                category = category,
                emergencyDetails = emergencyDetails
            )
            res.onSuccess { resp ->
                NotificationHelper.triggerEmergencyNotification(
                    context,
                    "DISTRESS BEACON DISPATCHED",
                    "Emergency SOS sent to station supervisor & central control."
                )
                _uiState.update {
                    it.copy(
                        isDispatchingSos = false,
                        sosAlertMessage = "DISTRESS BEACON BROADCAST: All station supervisors alerted.",
                        successMessage = "EMERGENCY SOS DISPATCHED: Central Control & Supervisor notified."
                    )
                }
                fetchIncidents()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isDispatchingSos = false, errorMessage = err.message) }
            }
        }
    }

    fun fetchDirectMessages(withUser: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(messagesLoading = true) }
            val res = repository.fetchDirectMessages(withUser)
            res.onSuccess { msgs ->
                _uiState.update { it.copy(directMessages = msgs, messagesLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(messagesLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun sendDirectMessage(recipientId: String, content: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.sendDirectMessage(recipientId, content)
            res.onSuccess { msg ->
                _uiState.update { state ->
                    state.copy(
                        directMessages = state.directMessages + msg,
                        successMessage = "Message dispatched."
                    )
                }
                fetchDirectMessages()
                fetchUnreadMessageCount()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun fetchUnreadMessageCount() {
        viewModelScope.launch {
            val res = repository.fetchUnreadMessageCount()
            res.onSuccess { count ->
                _uiState.update { it.copy(unreadMessageCount = count) }
            }
        }
    }

    fun markDirectMessageRead(id: String) {
        viewModelScope.launch {
            repository.markDirectMessageRead(id)
            fetchUnreadMessageCount()
        }
    }

    // --- Redesign: Supervisor & Admin Dashboards ---
    fun fetchSupervisorDashboard(stationId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(supervisorDashboardLoading = true) }
            val res = repository.fetchSupervisorDashboard(stationId)
            res.onSuccess { data ->
                _uiState.update { it.copy(supervisorDashboard = data, supervisorDashboardLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(supervisorDashboardLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun fetchAdminDashboard() {
        viewModelScope.launch {
            _uiState.update { it.copy(adminDashboardLoading = true) }
            val res = repository.fetchAdminDashboard()
            res.onSuccess { data ->
                _uiState.update { it.copy(adminDashboard = data, adminDashboardLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(adminDashboardLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun fetchOrganizationPolicies(category: String? = null, search: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(policiesLoading = true) }
            val res = repository.fetchOrganizationPolicies(category, search)
            res.onSuccess { list ->
                _uiState.update { it.copy(organizationPolicies = list, policiesLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(policiesLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun updateOrganizationPolicy(id: String, updates: Map<String, Any>, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.updateOrganizationPolicy(id, updates)
            res.onSuccess {
                _uiState.update { it.copy(isLoading = false, successMessage = "Policy updated successfully.") }
                fetchOrganizationPolicies()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun fetchDutyOverrides(guardId: String? = null, date: String? = null, status: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(dutyOverridesLoading = true) }
            val res = repository.fetchDutyOverrides(guardId, date, status)
            res.onSuccess { list ->
                _uiState.update { it.copy(dutyOverrides = list, dutyOverridesLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(dutyOverridesLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun createDutyOverride(request: CreateDutyOverrideRequest, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingDutyOverride = true, errorMessage = null) }
            val res = repository.createDutyOverride(request)
            res.onSuccess { override ->
                _uiState.update {
                    it.copy(
                        isCreatingDutyOverride = false,
                        dutyOverrides = listOf(override) + it.dutyOverrides,
                        successMessage = "Duty override recorded. Relief shift created and compensation logged."
                    )
                }
                fetchDutyOverrides()
                fetchSupervisorDashboard()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isCreatingDutyOverride = false, errorMessage = err.message) }
            }
        }
    }

    fun settleDutyOverrideCompensation(id: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repository.settleDutyOverrideCompensation(id)
            res.onSuccess {
                _uiState.update { it.copy(isLoading = false, successMessage = "Compensation settled.") }
                fetchDutyOverrides()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    fun reassignGuardPair(request: ReassignGuardPairRequest, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isReassigningPair = true, errorMessage = null) }
            val res = repository.reassignGuardPair(request)
            res.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        isReassigningPair = false,
                        successMessage = response.message.ifBlank { "Guard pair reassigned successfully." }
                    )
                }
                fetchGuardPairs()
                fetchPairReassignments()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isReassigningPair = false, errorMessage = err.message) }
            }
        }
    }

    fun fetchPairReassignments(stationId: String? = null, guardId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(pairReassignmentsLoading = true) }
            val res = repository.fetchPairReassignments(stationId, guardId)
            res.onSuccess { list ->
                _uiState.update { it.copy(pairReassignments = list, pairReassignmentsLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(pairReassignmentsLoading = false, errorMessage = err.message) }
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
