package com.example.data.api

import com.example.data.model.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // --- Authentication ---
    @POST("auth/login/")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("auth/refresh/")
    suspend fun refreshToken(@Body request: TokenRefreshRequest): Response<TokenRefreshResponse>

    @POST("auth/password_reset/request/")
    suspend fun requestPasswordReset(@Body request: PasswordResetRequest): Response<NotificationActionResponse>

    @POST("auth/password_reset/confirm/")
    suspend fun confirmPasswordReset(@Body request: PasswordResetConfirmRequest): Response<NotificationActionResponse>

    @GET("accounts/users/me/")
    suspend fun getCurrentUser(): Response<User>

    @PATCH("accounts/users/me/")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<User>

    @Multipart
    @POST("accounts/users/me/photo/")
    suspend fun uploadProfilePhoto(@Part photo: MultipartBody.Part): Response<User>

    @GET("core/telemetry/")
    suspend fun getTelemetry(): Response<TelemetryOverview>

    @GET("core/adjustments/")
    suspend fun getRecordAdjustments(@Query("status") status: String? = null): Response<List<RecordAdjustmentRequest>>

    @GET("core/adjustments/{id}/")
    suspend fun getRecordAdjustment(@Path("id") id: String): Response<RecordAdjustmentRequest>

    @POST("core/adjustments/")
    suspend fun createRecordAdjustment(@Body request: CreateRecordAdjustmentRequest): Response<RecordAdjustmentRequest>

    @POST("core/adjustments/{id}/approve/")
    suspend fun approveRecordAdjustment(@Path("id") id: String, @Body request: ApproveRecordAdjustmentRequest): Response<RecordAdjustmentResponse>

    @POST("core/adjustments/{id}/reject/")
    suspend fun rejectRecordAdjustment(@Path("id") id: String, @Body request: RejectRecordAdjustmentRequest): Response<RecordAdjustmentResponse>

    @GET("core/admin-history/")
    suspend fun getAdministrativeHistory(): Response<List<AdministrativeHistoryEntry>>

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
        @Body request: UpdateUserRequest
    ): Response<User>

    @DELETE("accounts/users/{id}/")
    suspend fun deleteUser(@Path("id") id: String): Response<Unit>

    // --- Stations & Guard Pairs ---
    @GET("stations/stations/")
    suspend fun getStations(): Response<List<Station>>

    @POST("stations/stations/")
    suspend fun createStation(@Body request: CreateStationRequest): Response<Station>

    @PATCH("stations/stations/{id}/")
    suspend fun updateStation(@Path("id") id: String, @Body request: UpdateStationRequest): Response<Station>

    @GET("stations/pairs/")
    suspend fun getGuardPairs(@Query("station") station: String? = null): Response<List<GuardPair>>

    @POST("stations/pairs/")
    suspend fun createGuardPair(@Body request: CreateGuardPairRequest): Response<GuardPair>

    @PATCH("stations/pairs/{id}/")
    suspend fun updateGuardPair(@Path("id") id: String, @Body request: UpdateGuardPairRequest): Response<GuardPair>

    // --- Shifts & Today's Shift ---
    @GET("shifts/shifts/today/")
    suspend fun getTodayShift(
        @Query("station") station: String? = null,
        @Query("guard") guard: String? = null
    ): Response<Shift>

    @GET("shifts/shifts/")
    suspend fun getShifts(
        @Query("date") date: String? = null,
        @Query("station") station: String? = null
    ): Response<List<Shift>>

    @GET("shifts/shifts/operational/")
    suspend fun getOperationalRoster(
        @Query("station") station: String? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("guard") guard: String? = null,
        @Query("pair") pair: String? = null,
        @Query("assignment_type") assignmentType: String? = null,
        @Query("shift_type") shiftType: String? = null
    ): Response<List<Shift>>

    @POST("shifts/shifts/generate/")
    suspend fun generateRoster(@Body request: GenerateRosterRequest): Response<GenerateRosterResponse>

    @POST("shifts/shifts/validate_roster/")
    suspend fun validateRoster(@Body request: ValidateRosterRequest): Response<ValidateRosterResponse>

    @POST("shifts/shifts/approve_roster/")
    suspend fun approveRoster(@Body request: ApproveRosterRequest): Response<RosterApproveResponse>

    @POST("shifts/duty-rosters/approve/")
    suspend fun approveDutyRoster(@Body request: ApproveRosterRequest): Response<RosterApproveResponse>

    @POST("shifts/duty-rosters/validate/")
    suspend fun validateDutyRoster(@Body request: ValidateRosterRequest): Response<ValidateRosterResponse>

    @POST("shifts/shifts/detect_conflicts/")
    suspend fun detectConflicts(@Body request: DetectConflictsRequest): Response<ConflictReport>

    @POST("shifts/shifts/reassign_duty/")
    suspend fun reassignDuty(@Body request: ReassignDutyRequest): Response<ReassignDutyResponse>

    @POST("shifts/shifts/{id}/reassign/")
    suspend fun reassignSingleShift(@Path("id") id: String, @Body request: ReassignSingleShiftRequest): Response<ReassignSingleShiftResponse>

    @GET("shifts/shifts/station_coverage/")
    suspend fun getStationCoverage(
        @Query("station") station: String? = null,
        @Query("date") date: String? = null
    ): Response<StationCoverageResponse>

    @POST("shifts/shifts/swap_pair_duties/")
    suspend fun swapPairDuties(
        @Body request: SwapPairDutiesRequest
    ): Response<NotificationActionResponse>

    @POST("shifts/shifts/schedule_escort/")
    suspend fun scheduleEscort(@Body request: ScheduleExamEscortRequest): Response<ScheduleExamEscortResponse>

    @POST("shifts/shifts/resume_normal/")
    suspend fun resumeNormalRoster(@Body request: ResumeNormalRosterRequest): Response<NotificationActionResponse>

    @GET("shifts/shifts/temporary_assignments/")
    suspend fun getTemporaryAssignments(@Query("station") station: String? = null): Response<List<TemporaryAssignmentAudit>>

    @GET("shifts/examination-periods/")
    suspend fun getExaminationPeriods(@Query("station") station: String? = null): Response<List<ExaminationPeriod>>

    @POST("shifts/examination-periods/")
    suspend fun createExaminationPeriod(@Body request: CreateExaminationPeriodRequest): Response<ExaminationPeriod>

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

    @POST("shifts/handovers/{id}/reject/")
    suspend fun rejectHandover(
        @Path("id") id: String,
        @Body request: RejectHandoverRequest = RejectHandoverRequest()
    ): Response<ShiftHandover>

    // --- Occurrence Book (OB) ---
    @GET("occurrence_book/entries/")
    suspend fun getOBEntries(
        @Query("category") category: String? = null,
        @Query("station") station: String? = null
    ): Response<List<OccurrenceBookEntry>>

    @POST("occurrence_book/entries/")
    suspend fun createOBEntry(@Body request: CreateOBEntryRequest): Response<OccurrenceBookEntry>

    @POST("occurrence_book/entries/{id}/amend/")
    suspend fun amendOBEntry(
        @Path("id") id: String,
        @Body request: AmendOBRequest
    ): Response<AmendOBResponse>

    // --- Incident Reporting ---
    @GET("incidents/reports/")
    suspend fun getIncidents(
        @Query("priority") priority: String? = null,
        @Query("status") status: String? = null,
        @Query("station") station: String? = null
    ): Response<List<IncidentReport>>

    @POST("incidents/reports/")
    suspend fun reportIncident(@Body request: CreateIncidentRequest): Response<IncidentReport>

    @POST("incidents/reports/{id}/amend/")
    suspend fun amendIncident(
        @Path("id") id: String,
        @Body request: AmendIncidentRequest
    ): Response<AmendIncidentResponse>

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
    suspend fun createCheckpoint(@Body request: CreateCheckpointRequest): Response<Checkpoint>

    @GET("patrols/logs/")
    suspend fun getPatrolLogs(): Response<List<PatrolLog>>

    @POST("patrols/logs/")
    suspend fun startPatrol(@Body request: StartPatrolRequest = StartPatrolRequest()): Response<PatrolLog>

    @POST("patrols/logs/{id}/scan/")
    suspend fun scanCheckpoint(
        @Path("id") patrolId: String,
        @Body request: CheckpointScanRequest
    ): Response<CheckpointScanResponse>

    @POST("patrols/logs/{id}/finish/")
    suspend fun finishPatrol(
        @Path("id") patrolId: String,
        @Body request: FinishPatrolRequest = FinishPatrolRequest()
    ): Response<PatrolLog>

    // --- Leave Applications ---
    @GET("leave/balances/my_balance/")
    suspend fun getLeaveBalance(): Response<LeaveBalance>

    @GET("leave/balances/my-summary/")
    suspend fun getLeaveSummary(): Response<LeaveSummary>

    @GET("leave/applications/")
    suspend fun getLeaveApplications(): Response<List<LeaveApplication>>

    @POST("leave/applications/")
    suspend fun applyForLeave(@Body request: CreateLeaveRequest): Response<LeaveApplication>

    @POST("leave/applications/{id}/review/")
    suspend fun reviewLeaveApplication(
        @Path("id") id: String,
        @Body request: LeaveReviewRequest
    ): Response<LeaveApplication>

    @POST("leave/balances/{id}/credit_holiday/")
    suspend fun creditHoliday(
        @Path("id") id: String,
        @Body request: CreditHolidayRequest
    ): Response<LeaveBalance>

    @GET("leave/balances/")
    suspend fun getAllLeaveBalances(@Query("station") stationId: String? = null): Response<List<LeaveBalance>>

    @POST("leave/balances/set_opening_balance/")
    suspend fun setOpeningLeaveBalance(@Body request: SetOpeningBalanceRequest): Response<SetOpeningBalanceResponse>

    @GET("leave/adjustments/")
    suspend fun getLeaveAdjustments(): Response<List<LeaveAdjustmentRecord>>

    @GET("leave/accrual-records/")
    suspend fun getLeaveAccrualRecords(): Response<List<LeaveAccrualRecord>>

    @POST("leave/balances/process-accruals/")
    suspend fun processMonthlyAccruals(@Body body: Map<String, String> = emptyMap()): Response<ProcessAccrualResponse>

    // --- Escorts (Vehicle & Security) ---
    @GET("escorts/duties/")
    suspend fun getEscortDuties(): Response<List<EscortDuty>>

    @POST("escorts/duties/")
    suspend fun createEscortDuty(@Body request: CreateEscortDutyRequest): Response<EscortDuty>

    @POST("escorts/duties/auto_allocate/")
    suspend fun autoAllocateEscortDuties(@Body request: AutoAllocateDutyRequest): Response<AutoAllocateResponse>

    @PATCH("escorts/duties/{id}/")
    suspend fun updateEscortDuty(
        @Path("id") id: String,
        @Body request: UpdateEscortDutyRequest
    ): Response<EscortDuty>

    @DELETE("escorts/duties/{id}/")
    suspend fun deleteEscortDuty(@Path("id") id: String): Response<Unit>

    @POST("escorts/duties/{id}/update_status/")
    suspend fun setEscortDutyStatus(@Path("id") id: String, @Body request: UpdateDutyStatusRequest): Response<EscortDuty>

    // --- Exams ---
    @GET("exams/duties/")
    suspend fun getExamDuties(): Response<List<ExamDuty>>

    @POST("exams/duties/")
    suspend fun createExamDuty(@Body request: CreateExamDutyRequest): Response<ExamDuty>

    @POST("exams/duties/auto_allocate/")
    suspend fun autoAllocateExamDuties(@Body request: AutoAllocateDutyRequest): Response<AutoAllocateResponse>

    @PATCH("exams/duties/{id}/")
    suspend fun updateExamDuty(
        @Path("id") id: String,
        @Body request: UpdateExamDutyRequest
    ): Response<ExamDuty>

    @DELETE("exams/duties/{id}/")
    suspend fun deleteExamDuty(@Path("id") id: String): Response<Unit>

    @POST("exams/duties/{id}/update_status/")
    suspend fun setExamDutyStatus(@Path("id") id: String, @Body request: UpdateDutyStatusRequest): Response<ExamDuty>

    // --- Notifications ---
    @GET("notifications/alerts/")
    suspend fun getNotifications(): Response<List<NotificationAlert>>

    @POST("notifications/alerts/{id}/read/")
    suspend fun markNotificationRead(@Path("id") id: String): Response<NotificationActionResponse>

    @POST("notifications/alerts/read_all/")
    suspend fun markAllNotificationsRead(): Response<NotificationActionResponse>

    @GET("notifications/alerts/unread_count/")
    suspend fun getUnreadNotificationCount(): Response<UnreadCountResponse>

    @POST("notifications/alerts/broadcast/")
    suspend fun broadcastNotice(@Body request: BroadcastNoticeRequest): Response<BroadcastNoticeResponse>

    // --- Public Holidays & Holiday Duties (National Engine) ---
    @GET("shifts/public-holidays/")
    suspend fun getPublicHolidays(): Response<List<PublicHoliday>>

    @GET("shifts/holiday-duties/")
    suspend fun getHolidayDuties(
        @Query("status") status: String? = null,
        @Query("station") station: String? = null
    ): Response<List<PublicHolidayDutyRecord>>

    @POST("shifts/holiday-duties/{id}/approve/")
    suspend fun approveHolidayDuty(
        @Path("id") id: String,
        @Body request: ReviewHolidayDutyRequest = ReviewHolidayDutyRequest()
    ): Response<PublicHolidayDutyRecord>

    @POST("shifts/holiday-duties/{id}/reject/")
    suspend fun rejectHolidayDuty(
        @Path("id") id: String,
        @Body request: ReviewHolidayDutyRequest = ReviewHolidayDutyRequest()
    ): Response<PublicHolidayDutyRecord>

    // --- Early Clock-Out OTP Generation (Administrator & Supervisor) ---
    @POST("shifts/attendance/generate_early_clockout_otp/")
    suspend fun generateEarlyClockoutOtp(
        @Body request: GenerateEarlyClockoutOtpRequest
    ): Response<GenerateEarlyClockoutOtpResponse>

    @POST("shifts/shifts/generate_early_clockout_otp/")
    suspend fun generateEarlyClockoutOtpShift(
        @Body request: GenerateEarlyClockoutOtpRequest
    ): Response<GenerateEarlyClockoutOtpResponse>

    @POST("shifts/attendance/generate-early-clockout-otp/")
    suspend fun generateEarlyClockoutOtpHyphen(
        @Body request: GenerateEarlyClockoutOtpRequest
    ): Response<GenerateEarlyClockoutOtpResponse>

    // --- Phase 13 Operational Guard & Communications ---
    @GET("shifts/shifts/duty_state/")
    suspend fun getDutyState(): Response<DutyStateResponse>

    @POST("shifts/attendance/late_arrival_report/")
    suspend fun submitLateArrivalReport(@Body request: LateArrivalReportRequest): Response<LateArrivalReportResponse>

    @POST("incidents/sos/")
    suspend fun triggerSos(@Body request: SosDistressRequest): Response<SosDistressResponse>

    @GET("notifications/messages/")
    suspend fun getDirectMessages(@Query("with_user") withUser: String? = null): Response<List<DirectMessage>>

    @POST("notifications/messages/")
    suspend fun sendDirectMessage(@Body request: DirectMessageCreateRequest): Response<DirectMessage>

    @GET("notifications/messages/unread_count/")
    suspend fun getUnreadMessageCount(): Response<UnreadCountResponse>

    @POST("notifications/messages/{id}/mark_read/")
    suspend fun markMessageRead(@Path("id") id: String): Response<DirectMessage>
}
