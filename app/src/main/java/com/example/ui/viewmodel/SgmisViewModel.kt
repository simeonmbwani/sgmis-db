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
    val leaveLoading: Boolean = false
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
        fetchTodayShift()
        fetchHandovers()
        fetchOBEntries()
        fetchIncidents()
        fetchCheckpoints()
        fetchPatrolLogs()
        fetchLeave()
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
