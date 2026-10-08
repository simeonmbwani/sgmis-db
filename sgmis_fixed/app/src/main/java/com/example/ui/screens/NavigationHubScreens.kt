package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GuardDutyState
import com.example.ui.components.*
import com.example.ui.navigation.NavRoutes
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel

/**
 * Hub screen for Guard operational logging (Patrol, OB, Visitors, Incidents, Handover, SOS).
 * Clearly communicates operational duty lock if the guard is off duty.
 */
@Composable
fun GuardOperationsHubScreen(
    viewModel: SgmisViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isOnDuty = uiState.guardDutyState == GuardDutyState.ON_DUTY || uiState.guardDutyState.isSpecialDuty

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .verticalScroll(rememberScrollState())
    ) {
        SgmisTopAppBar(
            title = "Operations Hub",
            subtitle = uiState.currentStationName
        )

        Column(modifier = Modifier.padding(16.dp)) {
            // Tactical Duty State Announcement Card
            if (isOnDuty) {
                SgmisStatusCard(
                    statusColor = StatusSuccess,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "ON DUTY • LIVE LOGGING ACTIVE",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = StatusSuccess
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Post: ${uiState.currentStationName}. All entries are cryptographically timestamped and recorded into the authoritative station ledger.",
                        fontSize = 13.sp,
                        color = TextSecondaryLight
                    )
                }
            } else {
                SgmisStatusCard(
                    statusColor = StatusWarning,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "OPERATIONAL LOGGING LOCKED",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = StatusWarning
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "You are currently ${uiState.guardDutyState.label.uppercase()}. Standing security regulations require guards to be CLOCKED IN (On Duty) to submit official occurrence, patrol, or gate visitor logs.",
                        fontSize = 13.sp,
                        color = TextSecondaryLight
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SgmisPrimaryButton(
                        text = "Report to Post & Clock In",
                        onClick = { onNavigate(NavRoutes.TODAY_SHIFT) },
                        leadingIcon = Icons.Default.Login
                    )
                }
            }

            SgmisSectionHeader(
                title = "Operational Registers",
                subtitle = "Official post records and verification"
            )

            SgmisCard(modifier = Modifier.padding(vertical = 8.dp)) {
                SgmisListItem(
                    title = "Occurrence Book (OB)",
                    subtitle = "Record official daily entries, incidents, and post handovers",
                    leadingIcon = Icons.AutoMirrored.Filled.MenuBook,
                    leadingIconTint = NavyDark,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.OCCURRENCE_BOOK) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Security Patrols",
                    subtitle = "Execute QR/NFC checkpoint scans and verify station perimeter",
                    leadingIcon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    leadingIconTint = StatusSuccess,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.PATROL) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Visitor & Gate Register",
                    subtitle = "Log visitors, contractors, passes, and vehicle registrations",
                    leadingIcon = Icons.Default.Badge,
                    leadingIconTint = ShiftDayColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.VISITORS) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Report Incident",
                    subtitle = "Log priority security breaches, thefts, hazards, or intrusions",
                    leadingIcon = Icons.Default.Warning,
                    leadingIconTint = StatusError,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.INCIDENTS) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Shift Handover",
                    subtitle = "Formal post inventory handover briefing to incoming guard",
                    leadingIcon = Icons.Default.SyncAlt,
                    leadingIconTint = ShiftNightColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.HANDOVER) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Additional Duties",
                    subtitle = "Special event security and ad-hoc station deployment orders",
                    leadingIcon = Icons.Default.Assignment,
                    leadingIconTint = ShiftExamColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ADDITIONAL_DUTIES) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Urgent Duress Action
            SgmisCard(
                modifier = Modifier.padding(vertical = 4.dp),
                backgroundColor = StatusError.copy(alpha = 0.05f),
                borderColor = StatusError.copy(alpha = 0.3f)
            ) {
                SgmisListItem(
                    title = "Emergency Duress SOS",
                    subtitle = "Instantly broadcast distress beacon with GPS coordinates to supervisor",
                    leadingIcon = Icons.Default.Emergency,
                    leadingIconTint = StatusError,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.EMERGENCY_SOS) }
                )
            }
        }
    }
}

/**
 * Hub screen for Guard schedule, roster rotation, and leave management.
 */
@Composable
fun GuardScheduleHubScreen(
    viewModel: SgmisViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .verticalScroll(rememberScrollState())
    ) {
        SgmisTopAppBar(
            title = "Schedule & Leave",
            subtitle = "Rotations, Rosters & Leave Balances"
        )

        Column(modifier = Modifier.padding(16.dp)) {
            // Guard Schedule Summary
            SgmisCard(modifier = Modifier.padding(bottom = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "CURRENT DUTY STATUS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondaryLight
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.currentStationName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }
                    SgmisDutyBadge(dutyState = uiState.guardDutyState)
                }
            }

            SgmisSectionHeader(
                title = "Duty Management",
                subtitle = "Active shifts and rotation schedules"
            )

            SgmisCard(modifier = Modifier.padding(vertical = 8.dp)) {
                SgmisListItem(
                    title = "Today's Shift",
                    subtitle = "Verify attendance window, station geofence, and clock in/out",
                    leadingIcon = Icons.Default.AccessTime,
                    leadingIconTint = ShiftDayColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.TODAY_SHIFT) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "My Duty Roster",
                    subtitle = "12-day rotational duty calendar, assigned pairs, and rest days",
                    leadingIcon = Icons.Default.CalendarMonth,
                    leadingIconTint = NavyDark,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.GUARD_DUTY_PLAN) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Leave Applications & Quota",
                    subtitle = "Submit annual/sick leave requests and inspect balance ledgers",
                    leadingIcon = Icons.Default.EventBusy,
                    leadingIconTint = ShiftLeaveColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.LEAVE) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Examination Protective Details",
                    subtitle = "National examination escort assignments and institution posts",
                    leadingIcon = Icons.Default.School,
                    leadingIconTint = ShiftExamColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.EXAM_DUTIES) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Armed & Transit Escorts",
                    subtitle = "Cash-in-transit and high-value asset protective escorts",
                    leadingIcon = Icons.Default.LocalShipping,
                    leadingIconTint = ShiftEscortColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ESCORT_DUTIES) }
                )
            }
        }
    }
}

/**
 * Hub screen for Supervisor station operational oversight.
 */
@Composable
fun SupervisorOperationsHubScreen(
    viewModel: SgmisViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .verticalScroll(rememberScrollState())
    ) {
        SgmisTopAppBar(
            title = "Station Operations",
            subtitle = "Command Oversight: ${uiState.currentStationName}"
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SgmisSectionHeader(
                title = "Operational Verification",
                subtitle = "Inspect and endorse station activity records"
            )

            SgmisCard(modifier = Modifier.padding(vertical = 8.dp)) {
                SgmisListItem(
                    title = "Occurrence Book Endorsement",
                    subtitle = "Review and verify guard shift logs and incidents",
                    leadingIcon = Icons.AutoMirrored.Filled.MenuBook,
                    leadingIconTint = NavyDark,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.OCCURRENCE_BOOK) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Patrol Inspection & Oversight",
                    subtitle = "Monitor real-time checkpoint completion and missed routes",
                    leadingIcon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    leadingIconTint = StatusSuccess,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.PATROL) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Station Visitor Register",
                    subtitle = "Review gate access registers, visitor logs, and vehicles",
                    leadingIcon = Icons.Default.Badge,
                    leadingIconTint = ShiftDayColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.VISITORS) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Incident Escalations",
                    subtitle = "Review reported security breaches and trigger escalations",
                    leadingIcon = Icons.Default.Warning,
                    leadingIconTint = StatusError,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.INCIDENTS) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Shift Handover Review",
                    subtitle = "Inspect completed post checklists and dual signatures",
                    leadingIcon = Icons.Default.SyncAlt,
                    leadingIconTint = ShiftNightColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.HANDOVER) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Special Deployments",
                    subtitle = "Coordinate ad-hoc assignments and additional duties",
                    leadingIcon = Icons.Default.Assignment,
                    leadingIconTint = ShiftExamColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ADDITIONAL_DUTIES) }
                )
            }
        }
    }
}

/**
 * Hub screen for Supervisor personnel administration (Attendance, Leave, Adjustments).
 */
@Composable
fun SupervisorPersonnelHubScreen(
    viewModel: SgmisViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .verticalScroll(rememberScrollState())
    ) {
        SgmisTopAppBar(
            title = "Personnel & Leave",
            subtitle = "Station Roster & Personnel Administration"
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SgmisSectionHeader(
                title = "Station Personnel Controls",
                subtitle = "Manage guard roll-call, leave reviews, and adjustments"
            )

            SgmisCard(modifier = Modifier.padding(vertical = 8.dp)) {
                SgmisListItem(
                    title = "Attendance & Roll-Call",
                    subtitle = "Monitor live clock-ins, late arrival reports, and overrides",
                    leadingIcon = Icons.Default.FactCheck,
                    leadingIconTint = StatusSuccess,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ATTENDANCE_MANAGEMENT) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Leave Review Queue",
                    subtitle = "Review and approve/reject guard leave applications",
                    leadingIcon = Icons.Default.EventBusy,
                    leadingIconTint = ShiftLeaveColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.LEAVE) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Record Adjustments",
                    subtitle = "Request formal corrections for contested attendance or shift logs",
                    leadingIcon = Icons.Default.EditNote,
                    leadingIconTint = ShiftDayColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.RECORD_ADJUSTMENTS) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Station Perimeter & Geofence",
                    subtitle = "Inspect station coordinates, boundary radius, and profile",
                    leadingIcon = Icons.Default.LocationOn,
                    leadingIconTint = NavyDark,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.PROFILE) }
                )
            }
        }
    }
}

/**
 * Hub screen for Administrator national governance (Rosters, Holidays, Reports).
 */
@Composable
fun AdminGovernanceHubScreen(
    viewModel: SgmisViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .verticalScroll(rememberScrollState())
    ) {
        SgmisTopAppBar(
            title = "National Governance",
            subtitle = "Master Roster & Policy Authority"
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SgmisSectionHeader(
                title = "Governance Functions",
                subtitle = "Multi-station approval, policies, and national reporting"
            )

            SgmisCard(modifier = Modifier.padding(vertical = 8.dp)) {
                SgmisListItem(
                    title = "Duty Roster Governance",
                    subtitle = "Validate and authorize submitted station rotational rosters",
                    leadingIcon = Icons.Default.CalendarMonth,
                    leadingIconTint = NavyDark,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ROSTER_MANAGEMENT) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Public Holiday Management",
                    subtitle = "Configure Zimbabwe official public holidays and special duty multipliers",
                    leadingIcon = Icons.Default.HolidayVillage,
                    leadingIconTint = GoldMuted,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ADMIN_MASTER_TOOLS) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "National Reports & Analytics",
                    subtitle = "Generate compliance summaries and export attendance archives",
                    leadingIcon = Icons.Default.Analytics,
                    leadingIconTint = StatusSuccess,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.REPORTS) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Organization Standing Orders",
                    subtitle = "National security guidelines and operational policies",
                    leadingIcon = Icons.Default.Policy,
                    leadingIconTint = ShiftExamColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ORGANIZATION_POLICY) }
                )
            }
        }
    }
}

/**
 * Hub screen for Administrator infrastructure management (Stations, Users).
 */
@Composable
fun AdminInfrastructureHubScreen(
    viewModel: SgmisViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .verticalScroll(rememberScrollState())
    ) {
        SgmisTopAppBar(
            title = "Stations & Users",
            subtitle = "Infrastructure & Personnel Administration"
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SgmisSectionHeader(
                title = "Infrastructure Controls",
                subtitle = "Manage security posts, geofences, and personnel accounts"
            )

            SgmisCard(modifier = Modifier.padding(vertical = 8.dp)) {
                SgmisListItem(
                    title = "Station Management",
                    subtitle = "Configure stations, GPS center points, and geofence radii",
                    leadingIcon = Icons.Default.Business,
                    leadingIconTint = NavyDark,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.STATION_MANAGEMENT) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "User & Personnel Provisioning",
                    subtitle = "Provision guard accounts, assign roles, and activate personnel",
                    leadingIcon = Icons.Default.ManageAccounts,
                    leadingIconTint = StatusSuccess,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.USER_MANAGEMENT) }
                )
            }
        }
    }
}

/**
 * Hub screen for Administrator compliance and audit tools.
 */
@Composable
fun AdminAuditHubScreen(
    viewModel: SgmisViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .verticalScroll(rememberScrollState())
    ) {
        SgmisTopAppBar(
            title = "Audit & Compliance",
            subtitle = "Security Ledger & Diagnostic Tools"
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SgmisSectionHeader(
                title = "Audit Trail & Controls",
                subtitle = "Inspect security events and system diagnostic tools"
            )

            SgmisCard(modifier = Modifier.padding(vertical = 8.dp)) {
                SgmisListItem(
                    title = "Administrative Audit History",
                    subtitle = "Immutable ledger of roster approvals, overrides, and security events",
                    leadingIcon = Icons.Default.HistoryEdu,
                    leadingIconTint = NavyDark,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ADMIN_HISTORY) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Record Adjustment Approvals",
                    subtitle = "Review and authorize contested attendance and shift revisions",
                    leadingIcon = Icons.Default.Rule,
                    leadingIconTint = ShiftDayColor,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.RECORD_ADJUSTMENTS) }
                )
                HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)
                SgmisListItem(
                    title = "Master Diagnostics & Tools",
                    subtitle = "National control center diagnostics and database synchronization",
                    leadingIcon = Icons.Default.Build,
                    leadingIconTint = StatusError,
                    showChevron = true,
                    onClick = { onNavigate(NavRoutes.ADMIN_MASTER_TOOLS) }
                )
            }
        }
    }
}
