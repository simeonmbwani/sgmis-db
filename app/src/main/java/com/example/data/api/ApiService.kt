package com.example.data.api

import com.example.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // --- Authentication ---
    @POST("auth/login/")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("auth/me/")
    suspend fun getCurrentUser(): Response<User>

    // --- Shifts & Today's Shift ---
    @GET("shifts/shifts/today/")
    suspend fun getTodayShift(): Response<Shift>

    @GET("shifts/shifts/")
    suspend fun getShifts(@Query("date") date: String? = null): Response<List<Shift>>

    // --- Attendance Clock-In & Clock-Out ---
    @POST("shifts/attendance/clock_in/")
    suspend fun clockIn(@Body request: ClockInRequest): Response<Attendance>

    @POST("shifts/attendance/clock_out/")
    suspend fun clockOut(@Body request: ClockOutRequest): Response<Attendance>

    @GET("shifts/attendance/")
    suspend fun getAttendanceRecords(): Response<List<Attendance>>

    // --- Shift Handovers ---
    @GET("shifts/handovers/")
    suspend fun getHandovers(): Response<List<ShiftHandover>>

    @POST("shifts/handovers/")
    suspend fun createHandover(@Body request: CreateHandoverRequest): Response<ShiftHandover>

    @POST("shifts/handovers/{id}/accept/")
    suspend fun acceptHandover(@Path("id") id: String): Response<ShiftHandover>

    // --- Occurrence Book (OB) ---
    @GET("occurrence_book/entries/")
    suspend fun getOBEntries(): Response<List<OccurrenceBookEntry>>

    @POST("occurrence_book/entries/")
    suspend fun createOBEntry(@Body request: CreateOBEntryRequest): Response<OccurrenceBookEntry>

    // --- Incident Reporting ---
    @GET("incidents/reports/")
    suspend fun getIncidents(): Response<List<IncidentReport>>

    @POST("incidents/reports/")
    suspend fun reportIncident(@Body request: CreateIncidentRequest): Response<IncidentReport>

    // --- Patrols & Checkpoints ---
    @GET("patrols/checkpoints/")
    suspend fun getCheckpoints(): Response<List<Checkpoint>>

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
}
