package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.SgmisInputField
import com.example.ui.components.SgmisPrimaryButton
import com.example.ui.components.SgmisStatusCard
import com.example.ui.theme.*
import com.example.ui.viewmodel.SgmisViewModel
import com.example.util.ApiErrorTranslator

@Composable
fun LoginScreen(
    viewModel: SgmisViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Server Config Icon on Top Right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showServerDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("server_config_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.server_settings),
                            tint = TextSecondaryLight
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Brand Emblem
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(NavyDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        tint = GoldAccent,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // System Brand Typography
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = NavyDark,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.app_full_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondaryLight,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Official operational badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = NavyDark.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, NavyDark.copy(alpha = 0.15f))
                ) {
                    Text(
                        text = "OFFICIAL OPERATIONAL PORTAL",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = NavyDark,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Main Login Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, BorderSubtleLight),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.login_title),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryLight
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.login_subtitle),
                                fontSize = 13.sp,
                                color = TextSecondaryLight
                            )
                        }

                        // Lockout Warning Banner
                        if (uiState.isLockedOut) {
                            SgmisStatusCard(
                                statusColor = StatusError,
                                backgroundColor = StatusError.copy(alpha = 0.06f)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = StatusError,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Account Locked",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusError
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Security lockout active (5 failed attempts). Try again in ${uiState.lockoutRemainingMinutes.coerceAtLeast(1)} minutes or reset your password.",
                                            fontSize = 12.sp,
                                            color = TextPrimaryLight,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Success Banner
                        if (uiState.successMessage != null) {
                            SgmisStatusCard(
                                statusColor = StatusSuccess,
                                backgroundColor = StatusSuccess.copy(alpha = 0.06f)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusSuccess,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = uiState.successMessage ?: "",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimaryLight
                                    )
                                }
                            }
                        }

                        // Error Banner (answering: What happened? Why? What can I do now?)
                        if (uiState.errorMessage != null && !uiState.isLockedOut) {
                            val guidance = ApiErrorTranslator.translate(uiState.errorMessage)
                            SgmisStatusCard(
                                statusColor = StatusError,
                                backgroundColor = StatusError.copy(alpha = 0.06f)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = StatusError,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = guidance.whatHappened,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusError
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = guidance.whyItHappened,
                                            fontSize = 12.sp,
                                            color = TextPrimaryLight,
                                            lineHeight = 16.sp
                                        )
                                        if (guidance.whatCanIDoNow.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = guidance.whatCanIDoNow,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = TextSecondaryLight,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Identifier Field
                        SgmisInputField(
                            value = identifier,
                            onValueChange = {
                                identifier = it
                                viewModel.clearMessages()
                            },
                            label = stringResource(R.string.username_or_id_label),
                            placeholder = "Username or Employee ID",
                            leadingIcon = Icons.Default.Badge,
                            fieldModifier = Modifier.testTag("identifier_input"),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            )
                        )

                        // Password Field
                        SgmisInputField(
                            value = password,
                            onValueChange = {
                                password = it
                                viewModel.clearMessages()
                            },
                            label = stringResource(R.string.password_label),
                            placeholder = "Enter your password",
                            leadingIcon = Icons.Default.Lock,
                            fieldModifier = Modifier.testTag("password_input"),
                            trailingIcon = {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                        tint = TextSecondaryLight
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (!uiState.isLockedOut && !uiState.isLoading) {
                                        viewModel.login(identifier, password)
                                    }
                                }
                            )
                        )

                        // Forgot Password Action Link
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    viewModel.clearMessages()
                                    showForgotPasswordDialog = true
                                },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("forgot_password_button")
                            ) {
                                Text(
                                    text = "Forgot Password?",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NavyDark
                                )
                            }
                        }

                        // Submit Button
                        SgmisPrimaryButton(
                            text = stringResource(R.string.sign_in_button),
                            loadingText = stringResource(R.string.logging_in),
                            onClick = {
                                viewModel.login(identifier, password)
                            },
                            enabled = !uiState.isLoading && !uiState.isLockedOut,
                            isLoading = uiState.isLoading,
                            leadingIcon = Icons.Default.Login,
                            minHeight = 52.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_button")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Server Endpoint Indicator
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BorderSubtleLight.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(StatusSuccess)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Server: ${uiState.serverUrl}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondaryLight
                        )
                    }
                }
            }
        }
    }

    if (showServerDialog) {
        ServerConfigDialog(
            currentUrl = uiState.serverUrl,
            onDismiss = { showServerDialog = false },
            onSave = { newUrl ->
                viewModel.updateServerUrl(newUrl)
                showServerDialog = false
            }
        )
    }

    if (showForgotPasswordDialog) {
        ForgotPasswordDialog(
            initialIdentifier = identifier,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onRequestOtp = { id, onSent ->
                viewModel.requestPasswordReset(id) {
                    onSent(it)
                }
            },
            onConfirmReset = { id, otp, newPass, onComplete ->
                viewModel.confirmPasswordReset(id, otp, newPass) {
                    onComplete()
                }
            },
            onDismiss = {
                showForgotPasswordDialog = false
                viewModel.clearMessages()
            }
        )
    }
}

@Composable
fun ServerConfigDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var urlText by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = NavyDark,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.server_settings),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryLight
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Configure the operational backend API gateway endpoint for authentication and sync.",
                    fontSize = 13.sp,
                    color = TextSecondaryLight
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimaryLight,
                        unfocusedTextColor = TextPrimaryLight,
                        cursorColor = NavyDark,
                        focusedBorderColor = NavyDark,
                        unfocusedBorderColor = BorderSubtleLight,
                        focusedContainerColor = LightSurface,
                        unfocusedContainerColor = LightSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_url_input")
                )
                Text(
                    text = "Environment Presets:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondaryLight
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { urlText = "https://security-management-5u3m.onrender.com/" },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("preset_production_button")
                    ) {
                        Text("Production", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { urlText = "http://10.0.2.2:8000/" },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("preset_emulator_button")
                    ) {
                        Text("Emulator", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(urlText) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyDark,
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .testTag("save_server_url_button")
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
            ) {
                Text("Cancel", color = TextSecondaryLight)
            }
        }
    )
}

@Composable
fun ForgotPasswordDialog(
    initialIdentifier: String,
    isLoading: Boolean,
    errorMessage: String?,
    onRequestOtp: (String, (String) -> Unit) -> Unit,
    onConfirmReset: (String, String, String, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var identifier by remember { mutableStateOf(initialIdentifier) }
    var otp by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = NavyDark,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (!otpSent) "Reset Password" else "Enter OTP & New Password",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryLight
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val err = validationError ?: errorMessage
                if (err != null) {
                    SgmisStatusCard(
                        statusColor = StatusError,
                        backgroundColor = StatusError.copy(alpha = 0.08f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = StatusError,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = err,
                                fontSize = 12.sp,
                                color = StatusError
                            )
                        }
                    }
                }

                if (!otpSent) {
                    Text(
                        text = "Enter your Username or Guard ID. A 6-digit one-time passcode (OTP) valid for 10 minutes will be generated.",
                        fontSize = 13.sp,
                        color = TextSecondaryLight
                    )
                    SgmisInputField(
                        value = identifier,
                        onValueChange = {
                            identifier = it
                            validationError = null
                        },
                        label = "Username or Guard ID",
                        placeholder = "e.g. SEC-001 or admin",
                        leadingIcon = Icons.Default.Badge,
                        singleLine = true
                    )
                } else {
                    Text(
                        text = "Enter the 6-digit OTP code received, and enter a new secure password (minimum 8 characters).",
                        fontSize = 13.sp,
                        color = TextSecondaryLight
                    )
                    SgmisInputField(
                        value = otp,
                        onValueChange = {
                            otp = it.filter { ch -> ch.isDigit() }.take(6)
                            validationError = null
                        },
                        label = "6-Digit OTP Code",
                        placeholder = "123456",
                        leadingIcon = Icons.Default.Pin,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    SgmisInputField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            validationError = null
                        },
                        label = "New Password (min 8 chars)",
                        placeholder = "Enter new password",
                        leadingIcon = Icons.Default.Lock,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                    SgmisInputField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            validationError = null
                        },
                        label = "Confirm New Password",
                        placeholder = "Re-type new password",
                        leadingIcon = Icons.Default.Lock,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!otpSent) {
                        if (identifier.isBlank()) {
                            validationError = "Identifier is required."
                            return@Button
                        }
                        onRequestOtp(identifier) {
                            otpSent = true
                        }
                    } else {
                        if (otp.length < 6) {
                            validationError = "Please enter a valid 6-digit OTP."
                            return@Button
                        }
                        if (newPassword.length < 8) {
                            validationError = "Password must be at least 8 characters."
                            return@Button
                        }
                        if (newPassword != confirmPassword) {
                            validationError = "Passwords do not match."
                            return@Button
                        }
                        onConfirmReset(identifier, otp, newPassword) {
                            onDismiss()
                        }
                    }
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyDark,
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Processing...", fontSize = 13.sp)
                } else {
                    Text(if (!otpSent) "Request OTP" else "Reset Password", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
            ) {
                Text("Cancel", color = TextSecondaryLight)
            }
        }
    )
}
