package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutAppScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "About Smart Security",
                subtitle = "System Overview & Compliance",
                onNavigationClick = onBack,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("about_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = NavyDark
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(LightBackground)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Hero Banner Card
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about_hero_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(NavyDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = SurfaceCardLight,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Text(
                        text = "Smart Security",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )

                    Text(
                        text = "Security Guard Management Information System",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryLight
                    )

                    Surface(
                        color = NavyDark.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = NavyDark,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Platform Description Card
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about_description_card")
            ) {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Platform Overview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )
                    Text(
                        text = "Smart Security is a mission-critical, enterprise physical security operations platform engineered for universities, corporate campuses, and critical infrastructure. It provides authoritative single-source-of-truth duty tracking, immutable audit trails, and supervisor command controls.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryLight,
                        lineHeight = 22.sp
                    )
                }
            }

            // Key System Features
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about_features_card")
            ) {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Key Operational Capabilities",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )

                    FeatureItem(
                        icon = Icons.Outlined.CalendarMonth,
                        title = "Authoritative Duty Rostering",
                        description = "12-day cyclical roster engine with 4-day on / 8-day off rotation, pair day/night alternation, and supervisor-authorized duty assignments."
                    )
                    FeatureItem(
                        icon = Icons.Outlined.LocationOn,
                        title = "Geofenced Biometric Attendance",
                        description = "GPS-verified station reporting windows, early clock-out OTP authorization, and automatic late arrival incident escalation."
                    )
                    FeatureItem(
                        icon = Icons.Outlined.MenuBook,
                        title = "Digital Occurrence Book (OB)",
                        description = "Immutable digital occurrence recording with tamper-evident amendment logging and supervisor security oversight."
                    )
                    FeatureItem(
                        icon = Icons.Outlined.Warning,
                        title = "Emergency SOS & Incident Management",
                        description = "Instant distress signaling with real-time GPS coordinates, priority notifications, and dispatch logging."
                    )
                    FeatureItem(
                        icon = Icons.Outlined.QrCodeScanner,
                        title = "Guard Patrol & QR Route Tracking",
                        description = "Checkpoint verification, scheduled patrol intervals, and live perimeter monitoring."
                    )
                    FeatureItem(
                        icon = Icons.Outlined.SwapHoriz,
                        title = "Shift Handover & Keys Custody",
                        description = "Mandatory sign-off between incoming and outgoing guards, equipment ledger, and dispute management."
                    )
                    FeatureItem(
                        icon = Icons.Outlined.Shield,
                        title = "Specialized Duties (Exam & Escort)",
                        description = "University exam venue security and transit escort tracking with dedicated acknowledgement workflows."
                    )
                    FeatureItem(
                        icon = Icons.Outlined.EventAvailable,
                        title = "Leave & Holiday Accounting",
                        description = "Monthly vacation accruals (2.5 days/mo, 90-day cap), casual leave, and worked public holiday double compensation."
                    )
                }
            }

            // Technical Specifications Card
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about_tech_card")
            ) {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "System Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )
                    HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)

                    TechRow("Application ID", BuildConfig.APPLICATION_ID)
                    TechRow("Version Name", BuildConfig.VERSION_NAME)
                    TechRow("Version Code", BuildConfig.VERSION_CODE.toString())
                    TechRow("Build Configuration", BuildConfig.BUILD_TYPE.uppercase())
                    TechRow("Backend Host", uiState.serverUrl)
                    TechRow("Compliance Standard", "ISO 27001 / SOC 2 Type II")
                }
            }

            // Footer
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "© 2026 Smart Security Systems. All rights reserved.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryLight
                )
            }
        }
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(NavyDark.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NavyDark,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimaryLight
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryLight,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun TechRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = TextPrimaryLight)
    }
}
