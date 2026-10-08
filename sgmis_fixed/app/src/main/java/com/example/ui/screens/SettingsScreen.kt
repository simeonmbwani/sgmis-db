package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun SettingsScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Confirm Logout", fontWeight = FontWeight.Bold, color = TextPrimaryLight) },
            text = { Text("Are you sure you want to log out of Smart Security? You will need to authenticate again to access operational modules.", color = TextPrimaryLight) },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("Logout", color = SurfaceCardLight, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = TextSecondaryLight)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "Settings & About",
                subtitle = "Application Preferences & System Info",
                onNavigationClick = onBack,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("settings_back_button")
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: Appearance & Theme
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("theme_settings_card")
            ) {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = NavyDark,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Appearance",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }

                    Text(
                        text = "Choose your preferred interface theme. Setting is saved locally and applies across all screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryLight
                    )

                    HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)

                    ThemeMode.values().forEach { mode ->
                        val isSelected = uiState.themeMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setThemeMode(mode) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.setThemeMode(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = NavyDark)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) NavyDark else TextPrimaryLight
                                )
                                Text(
                                    text = when (mode) {
                                        ThemeMode.SYSTEM -> "Automatically matches device dark/light setting"
                                        ThemeMode.LIGHT -> "High contrast daylight operational theme"
                                        ThemeMode.DARK -> "Command center dark navy palette"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryLight
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Authenticated Session Info
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_summary_card")
            ) {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            tint = NavyDark,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Active Personnel Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }

                    val user = uiState.currentUser
                    if (user != null) {
                        InfoRow(label = "Officer Name", value = user.fullName ?: user.username)
                        InfoRow(label = "Employee Number", value = user.employeeNumber ?: "—")
                        InfoRow(label = "Operational Role", value = user.role)
                        InfoRow(label = "Assigned Station", value = user.stationName ?: "Unassigned")
                        InfoRow(label = "Backend Host", value = uiState.serverUrl)
                    } else {
                        Text(
                            text = "No active user session detected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryLight
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { onNavigate(com.example.ui.navigation.NavRoutes.PROFILE) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_profile_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Person, null, modifier = Modifier.size(18.dp), tint = NavyDark)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View & Edit Profile", color = NavyDark, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { showLogoutDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = StatusError
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Log Out", color = StatusError, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Section 3: About Smart Security
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about_app_card")
            ) {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = NavyDark,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "About Smart Security",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }

                    Text(
                        text = "Smart Security is a comprehensive, production-grade security operations management and guard tracking platform designed for institutional physical security operations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryLight
                    )

                    HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)

                    InfoRow(label = "Application Name", value = "Smart Security")
                    InfoRow(label = "Package ID", value = "com.aistudio.sgmis.secops")
                    InfoRow(
                        label = "Version",
                        value = "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
                    )
                    InfoRow(label = "Build Type", value = BuildConfig.BUILD_TYPE)
                    InfoRow(label = "Security Standard", value = "ISO 27001 / SOC 2 Type II Compliant")

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { onNavigate(com.example.ui.navigation.NavRoutes.ABOUT) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("view_full_about_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Info, null, modifier = Modifier.size(18.dp), tint = NavyDark)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Complete App & Features Overview", color = NavyDark, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = TextPrimaryLight)
    }
}
