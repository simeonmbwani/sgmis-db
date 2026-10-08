package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: SgmisViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val user = uiState.currentUser
    val scrollState = rememberScrollState()

    var firstName by remember(user?.firstName) { mutableStateOf(user?.firstName ?: "") }
    var lastName by remember(user?.lastName) { mutableStateOf(user?.lastName ?: "") }
    var phone by remember(user?.phoneNumber) { mutableStateOf(user?.phoneNumber ?: "") }
    var email by remember(user?.email) { mutableStateOf(user?.email ?: "") }
    var address by remember(user?.address) { mutableStateOf(user?.address ?: "") }
    var photoUrl by remember(user?.profilePhoto) { mutableStateOf(user?.profilePhoto ?: "") }

    val fullPhotoUrl = remember(user?.profilePhoto, uiState.serverUrl) {
        val photo = user?.profilePhoto
        when {
            photo.isNullOrBlank() -> null
            photo.startsWith("http://") || photo.startsWith("https://") -> photo
            photo.startsWith("/") -> "${uiState.serverUrl.removeSuffix("/")}$photo"
            else -> "${uiState.serverUrl.removeSuffix("/")}/$photo"
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                val inputStream = context.contentResolver.openInputStream(selectedUri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null && bytes.isNotEmpty()) {
                    val mimeType = context.contentResolver.getType(selectedUri) ?: "image/jpeg"
                    val ext = if (mimeType.contains("png")) "png" else "jpg"
                    val filename = "profile_${System.currentTimeMillis()}.$ext"
                    viewModel.uploadProfilePhoto(bytes, filename, mimeType)
                }
            } catch (e: Exception) {
                // Handled in viewModel error state
            }
        }
    }

    Scaffold(
        topBar = {
            SgmisTopAppBar(
                title = "My Profile",
                subtitle = "Personnel Credentials & Details",
                onNavigationClick = onBack,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("profile_back_button")
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
            // Notification banners
            if (uiState.successMessage != null) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.successMessage!!,
                            color = StatusSuccess,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (uiState.errorMessage != null) {
                Surface(
                    color = StatusError.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = StatusError, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage!!,
                            color = StatusError,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Header Profile Card with Photo & Quick Identity
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_header_card")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(NavyDark.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!fullPhotoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = fullPhotoUrl,
                                    contentDescription = "Profile Photo",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                val initials = "${user?.firstName?.take(1) ?: ""}${user?.lastName?.take(1) ?: ""}".ifEmpty {
                                    user?.username?.take(2)?.uppercase() ?: "SG"
                                }
                                Text(
                                    text = initials,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    color = NavyDark
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.fullName ?: user?.username ?: "Officer",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryLight
                            )
                            Text(
                                text = "Employee ID: ${user?.employeeNumber ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryLight
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            SgmisBadge(
                                text = user?.role ?: "GUARD",
                                variant = BadgeVariant.Info
                            )
                        }
                    }

                    // Gallery photo upload action
                    OutlinedButton(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("profile_photo_upload_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(18.dp), tint = NavyDark)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select & Upload Photo from Gallery", color = NavyDark, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Read-Only Authoritative Organization Info Card
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("protected_info_card")
            ) {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = NavyDark,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Authoritative Security Assignment",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }

                    Text(
                        text = "Organizational credentials and station postings are managed exclusively by Station Administration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryLight
                    )

                    HorizontalDivider(color = BorderSubtleLight, thickness = 0.5.dp)

                    ProfileDetailRow("Employee ID", user?.employeeNumber ?: "—")
                    ProfileDetailRow("Operational Role", user?.role ?: "—")
                    ProfileDetailRow("Assigned Station", user?.stationName ?: "Unassigned")
                    ProfileDetailRow("Current Duty Status", uiState.guardDutyState.label)
                    ProfileDetailRow("Rank / Title", user?.rank ?: "Security Officer")
                    ProfileDetailRow("Account Status", if (user?.isActive == true) "Active Duty" else "Suspended")
                }
            }

            // Editable Personal Details Card
            SgmisCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editable_profile_card")
            ) {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = NavyDark,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Contact & Profile Details",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )
                    }

                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("First Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("profile_first_name_input")
                    )

                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Last Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("profile_last_name_input")
                    )

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("profile_phone_input")
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email Address") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("profile_email_input")
                    )

                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Residential Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("profile_address_input")
                    )

                    OutlinedTextField(
                        value = photoUrl,
                        onValueChange = { photoUrl = it },
                        label = { Text("Profile Photo URL (or use Gallery above)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("profile_photo_url_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                firstName = user?.firstName ?: ""
                                lastName = user?.lastName ?: ""
                                phone = user?.phoneNumber ?: ""
                                email = user?.email ?: ""
                                address = user?.address ?: ""
                                photoUrl = user?.profilePhoto ?: ""
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Reset", color = TextSecondaryLight)
                        }

                        Button(
                            onClick = {
                                viewModel.updateProfile(firstName, lastName, phone, email, address, photoUrl)
                            },
                            enabled = !uiState.isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = NavyDark),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("save_profile_button")
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = SurfaceCardLight
                                )
                            } else {
                                Text("Save Changes")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondaryLight)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = TextPrimaryLight)
    }
}
