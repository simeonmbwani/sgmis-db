package com.example.data.api

import com.example.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // --- Authentication ---
    @POST("auth/login/")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("auth/refresh/")
    suspend fun refreshToken(@Body request: TokenRefreshRequest): Response<TokenRefreshResponse>

    @GET("accounts/users/me/")
    suspend fun getCurrentUser(): Response<User>

    @PATCH("accounts/users/me/")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<User>

    @GET("core/telemetry/")
    suspend fun getTelemetry(): Response<TelemetryOverview>

    // --- User Management (Supervisor / Administrator) ---
    @GET("accounts/users/")
    suspend fun getUsers(
        @Query("role") role: String? = null,
        @Query("station") station: String? = null
    ): Response<List<User>>

    @POST("accounts/users/")
    suspend fun createUser(@Body request: CreateUserRequest): Response<User>

    @PATCH("accounts/users/{id}/")
    suspend fun updateUser(
        @Path("id") id: String,
        @Body updates: Map<String, @JvmSuppressWildcards Any?>
    ): Response<User>

    @DELETE("accounts/users/{id}/")
    suspend fun deleteUser(@Path("id") id: String): Response<Unit>

    // --- Stations & Guard Pairs ---
    @GET("stations/stations/")
    suspend fun getStations(): Response<List<Station>>

    @POST("stations/stations/")
    suspend fun createStation(@Body request: CreateStationRequest): Response<Station>

    @GET("stations/pairs/")
    suspend fun getGuardPairs(@Query("station") station: String? = null): Response<List<GuardPair>>

    @POST("stations/pairs/")
    suspend fun createGuardPair(@Body request: CreateGuardPairRequest): Response<GuardPair>

    // --- Shifts & Today's Shift ---
    @GET("shifts/shifts/today/")
    suspend fun getTodayShift(): Response<Shift>

    @GET("shifts/shifts/")
    suspend fun getShifts(
        @Query("date") date: String? = null,
        @Query("station") station: String? = null
    ): Response<List<Shift>>

    @POST("shifts/shifts/generate/")
    suspend fun generateRoster(@Body request: RosterGenerateRequest): Response<Map<String, Any>>

    // --- Attendance Clock-In & Clock-Out ---
    @POST("shifts/attendance/clock_in/")
    suspend fun clockIn(@Body request: ClockInRequest): Response<Attendance>

    @POST("shifts/attendance/clock_out/")
    suspend fun clockOut(@Body request: ClockOutRequest): Response<Attendance>

    @GET("shifts/attendance/")
    suspend fun getAttendanceRecords(
        @Query("date") date: String? = null,
        @Query("shift") shift: String? = null
    ): Response<List<Attendance>>

    // --- Shift Handovers ---
    @GET("shifts/handovers/")
    suspend fun getHandovers(): Response<List<ShiftHandover>>

    @POST("shifts/handovers/")
    suspend fun createHandover(@Body request: CreateHandoverRequest): Response<ShiftHandover>

    @POST("shifts/handovers/{id}/accept/")
    suspend fun acceptHandover(@Path("id") id: String): Response<ShiftHandover>

    // --- Occurrence Book (OB) ---
    @GET("occurrence_book/entries/")
    suspend fun getOBEntries(
        @Query("category") category: String? = null,
        @Query("station") station: String? = null
    ): Response<List<OccurrenceBookEntry>>

    @POST("occurrence_book/entries/")
    suspend fun createOBEntry(@Body request: CreateOBEntryRequest): Response<OccurrenceBookEntry>

    // --- Incident Reporting ---
    @GET("incidents/reports/")
    suspend fun getIncidents(
        @Query("priority") priority: String? = null,
        @Query("status") status: String? = null,
        @Query("station") station: String? = null
    ): Response<List<IncidentReport>>

    @POST("incidents/reports/")
    suspend fun reportIncident(@Body request: CreateIncidentRequest): Response<IncidentReport>

    @POST("incidents/reports/{id}/acknowledge/")
    suspend fun acknowledgeIncident(@Path("id") id: String): Response<IncidentReport>

    @POST("incidents/reports/{id}/resolve/")
    suspend fun resolveIncident(
        @Path("id") id: String,
        @Body request: IncidentResolveRequest
    ): Response<IncidentReport>

    // --- Patrols & Checkpoints ---
    @GET("patrols/checkpoints/")
    suspend fun getCheckpoints(@Query("station") station: String? = null): Response<List<Checkpoint>>

    @POST("patrols/checkpoints/")
    suspend fun createCheckpoint(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<Checkpoint>

    @GET("patrols/logs/")
    suspend fun getPatrolLogs(): Response<List<PatrolLog>>

    @POST("patrols/logs/")
    suspend fun startPatrol(@Body body: Map<String, String>): Response<PatrolLog>

    @POST("patrols/logs/{id}/scan/")
    suspend fun scanCheckpoint(
        @Path("id") patrolId: String,
        @Body request: CheckpointScanRequest
    ): Response<Map<String, Any>>

    @POST("patrols/logs/{id}/finish/")
    suspend fun finishPatrol(
        @Path("id") patrolId: String,
        @Body body: Map<String, String> = emptyMap()
    ): Response<PatrolLog>

    // --- Leave Applications ---
    @GET("leave/balances/my_balance/")
    suspend fun getLeaveBalance(): Response<LeaveBalance>

    @GET("leave/applications/")
    suspend fun getLeaveApplications(): Response<List<LeaveApplication>>

    @POST("leave/applications/")
    suspend fun applyForLeave(@Body request: CreateLeaveRequest): Response<LeaveApplication>

    @POST("leave/applications/{id}/review/")
    suspend fun reviewLeaveApplication(
        @Path("id") id: String,
        @Body request: LeaveReviewRequest
    ): Response<LeaveApplication>

    // --- Escorts (Vehicle & Security) ---
    @GET("escorts/duties/")
    suspend fun getEscortDuties(): Response<List<EscortDuty>>

    @POST("escorts/duties/")
    suspend fun createEscortDuty(@Body request: CreateEscortDutyRequest): Response<EscortDuty>

    @PATCH("escorts/duties/{id}/")
    suspend fun updateEscortDuty(
        @Path("id") id: String,
        @Body updates: Map<String, String>
    ): Response<EscortDuty>

    // --- Exams ---
    @GET("exams/duties/")
    suspend fun getExamDuties(): Response<List<ExamDuty>>

    @POST("exams/duties/")
    suspend fun createExamDuty(@Body request: CreateExamDutyRequest): Response<ExamDuty>

    @PATCH("exams/duties/{id}/")
    suspend fun updateExamDuty(
        @Path("id") id: String,
        @Body updates: Map<String, String>
    ): Response<ExamDuty>

    // --- Notifications ---
    @GET("notifications/alerts/")
    suspend fun getNotifications(): Response<List<NotificationAlert>>

    @POST("notifications/alerts/{id}/read/")
    suspend fun markNotificationRead(@Path("id") id: String): Response<Map<String, Any>>

    @POST("notifications/alerts/read_all/")
    suspend fun markAllNotificationsRead(): Response<Map<String, Any>>
}
