package com.example.ui.screens

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import com.example.services.KavyaAccessibilityService
import com.example.ui.components.GlassCard
import com.example.ui.components.KavyaVoiceOrb
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import com.example.utils.PermissionsManager
import com.example.viewmodel.KavyaViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    navController: NavController,
    viewModel: KavyaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentStep by remember { mutableIntStateOf(1) } // 1: Welcome, 2: API Key, 3: Permissions, 4: Dashboard, 5: Finish

    // Permission tracking states
    var micGranted by remember { mutableStateOf(AppPreferences.hasMicrophonePermission(context)) }
    var notifGranted by remember { mutableStateOf(AppPreferences.hasNotificationPermission(context)) }
    var overlayGranted by remember { mutableStateOf(AppPreferences.hasOverlayPermission(context)) }
    var accessGranted by remember { mutableStateOf(AppPreferences.hasAccessibilityPermission(context)) }

    fun refreshPermissions() {
        micGranted = AppPreferences.hasMicrophonePermission(context)
        notifGranted = AppPreferences.hasNotificationPermission(context)
        overlayGranted = AppPreferences.hasOverlayPermission(context)
        accessGranted = AppPreferences.hasAccessibilityPermission(context)
    }

    // Refresh permissions whenever user returns to the app from system settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshPermissions()
    }

    val runtimePermissions = remember {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list
    }
    val multiplePermissionsState = rememberMultiplePermissionsState(runtimePermissions) {
        refreshPermissions()
    }

    // API Key states
    var apiKeyInput by remember { mutableStateOf(AppPreferences.getCustomApiKey(context)) }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var apiKeyVerificationState by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isVerifyingApiKey by remember { mutableStateOf(false) }
    val isBuiltInKeyAvailable = remember { AppPreferences.isBuiltInApiKeyAvailable() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Background,
                        BackgroundGradientEnd,
                        Color(0xFF0D0A1A)
                    )
                )
            )
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Top Step Indicator Bar
            OnboardingTopBar(
                currentStep = currentStep,
                onBack = {
                    if (currentStep > 1) currentStep -= 1
                },
                canGoBack = currentStep in 2..4
            )

            // Animated Step Content Transition
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut()
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                label = "OnboardingStepAnimation"
            ) { step ->
                when (step) {
                    1 -> Step1Welcome(
                        onGetStarted = { currentStep = 2 }
                    )
                    2 -> Step2ApiKeySetup(
                        apiKeyInput = apiKeyInput,
                        onApiKeyChange = {
                            apiKeyInput = it
                            apiKeyVerificationState = null
                        },
                        isApiKeyVisible = isApiKeyVisible,
                        onToggleVisibility = { isApiKeyVisible = !isApiKeyVisible },
                        isBuiltInKeyAvailable = isBuiltInKeyAvailable,
                        isVerifying = isVerifyingApiKey,
                        verificationResult = apiKeyVerificationState,
                        onVerifyAndSave = { keyToSave ->
                            coroutineScope.launch {
                                isVerifyingApiKey = true
                                val result = AppPreferences.validateGeminiApiKey(keyToSave)
                                apiKeyVerificationState = result
                                isVerifyingApiKey = false
                                if (result.first) {
                                    AppPreferences.setCustomApiKey(context, keyToSave)
                                }
                            }
                        },
                        onUseBuiltInKey = {
                            AppPreferences.clearCustomApiKey(context)
                            apiKeyInput = ""
                            apiKeyVerificationState = Pair(true, "AI Studio Pre-configured Key selected!")
                        },
                        onContinue = {
                            if (apiKeyInput.isNotBlank()) {
                                AppPreferences.setCustomApiKey(context, apiKeyInput)
                            }
                            currentStep = 3
                        }
                    )
                    3 -> Step3Permissions(
                        micGranted = micGranted,
                        notifGranted = notifGranted,
                        overlayGranted = overlayGranted,
                        accessGranted = accessGranted,
                        onRequestRuntimePermissions = {
                            multiplePermissionsState.launchMultiplePermissionRequest()
                        },
                        onRequestOverlay = {
                            PermissionsManager.requestOverlayPermission(context)
                        },
                        onRequestAccessibility = {
                            PermissionsManager.requestAccessibilityPermission(context)
                        },
                        onRefresh = { refreshPermissions() },
                        onContinue = { currentStep = 4 }
                    )
                    4 -> Step4Dashboard(
                        hasApiKey = AppPreferences.hasAnyApiKey(context) || apiKeyInput.isNotBlank(),
                        micGranted = micGranted,
                        notifGranted = notifGranted,
                        overlayGranted = overlayGranted,
                        accessGranted = accessGranted,
                        onContinue = { currentStep = 5 }
                    )
                    5 -> Step5Finish(
                        onFinish = {
                            AppPreferences.setOnboardingCompleted(context, true)
                            // Greet user with Kavya's sweet friendly tone
                            viewModel.speakMessage("Hi, I am Kavya. I'm ready to make things effortless for you. What are we starting with? ✨")
                            navController.navigate("home") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TOP BAR WITH STEP DOTS
// -------------------------------------------------------------
@Composable
private fun OnboardingTopBar(
    currentStep: Int,
    onBack: () -> Unit,
    canGoBack: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (canGoBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SurfaceGlass)
                    .border(1.dp, GlassBorder, CircleShape)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(38.dp))
        }

        // Step Dots Indicator
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 1..5) {
                val isCurrent = i == currentStep
                val isPassed = i < currentStep
                val dotWidth by animateDpAsState(
                    targetValue = if (isCurrent) 24.dp else 8.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                    label = "DotWidth"
                )
                val dotColor by animateColorAsState(
                    targetValue = when {
                        isCurrent -> Primary
                        isPassed -> SuccessGreen
                        else -> GlassBorder
                    },
                    label = "DotColor"
                )

                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .width(dotWidth)
                        .clip(RoundedCornerShape(4.dp))
                        .background(dotColor)
                )
            }
        }

        Text(
            text = "Step $currentStep/5",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = TextSecondary,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End
        )
    }
}

// -------------------------------------------------------------
// STEP 1: WELCOME SCREEN
// -------------------------------------------------------------
@Composable
private fun Step1Welcome(
    onGetStarted: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Center Visual Element: Kavya Voice Orb
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(160.dp),
                contentAlignment = Alignment.Center
            ) {
                KavyaVoiceOrb(
                    state = com.example.ui.components.VoiceState.SPEAKING,
                    onClick = {}
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to Kavya",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your warm, natural & grounded companion.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        // Feature Highlights in Glass Cards
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FeatureHighlightRow(
                icon = Icons.Default.AutoAwesome,
                iconTint = PrimaryLight,
                title = "Grounded & Intelligent AI",
                desc = "Natural, expressive alto voice with emotional intelligence and deep warmth."
            )
            FeatureHighlightRow(
                icon = Icons.Default.Mic,
                iconTint = AccentCyan,
                title = "Hands-Free Device Control",
                desc = "Open apps, search media, and control settings directly via voice."
            )
            FeatureHighlightRow(
                icon = Icons.Default.ScreenShare,
                iconTint = AccentPurpleLight,
                title = "Live Screen Perception",
                desc = "Understands on-screen content and assists step-by-step."
            )
        }

        // Action Button
        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("onboarding_get_started_btn"),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text(
                text = "Get Started",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun FeatureHighlightRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    desc: String
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = SurfaceGlass.copy(alpha = 0.6f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                Spacer(modifier = Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp), color = TextSecondary)
            }
        }
    }
}

// -------------------------------------------------------------
// STEP 2: API KEY SETUP
// -------------------------------------------------------------
@Composable
private fun Step2ApiKeySetup(
    apiKeyInput: String,
    onApiKeyChange: (String) -> Unit,
    isApiKeyVisible: Boolean,
    onToggleVisibility: () -> Unit,
    isBuiltInKeyAvailable: Boolean,
    isVerifying: Boolean,
    verificationResult: Pair<Boolean, String>?,
    onVerifyAndSave: (String) -> Unit,
    onUseBuiltInKey: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Gemini AI Key Setup 🔑",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Kavya uses Google Gemini models for deep contextual conversations, warm personality, and screen reasoning.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = TextSecondary
            )
        }

        // If built-in key is configured in Studio
        if (isBuiltInKeyAvailable) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = SuccessGreen.copy(alpha = 0.35f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(SuccessGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Pre-configured Key Detected ✨",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                            Text(
                                "AI Studio environment credentials are ready to use.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = SuccessGreenGlow
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onUseBuiltInKey,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Use Pre-configured Studio Key", color = PrimaryLight, fontSize = 12.sp)
                    }
                }
            }
        }

        // Custom API Key Input Field
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                backgroundColor = SurfaceGlass.copy(alpha = 0.85f)
            ) {
                Text(
                    text = "Custom Gemini API Key",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "You can enter your own personal Gemini API Key from Google AI Studio.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = TextTertiary
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input_field"),
                    placeholder = { Text("AIzaSy...", color = TextTertiary) },
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = SurfaceVariant,
                        unfocusedContainerColor = SurfaceVariant
                    ),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onToggleVisibility) {
                                Icon(
                                    if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Key Visibility",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (apiKeyInput.isNotBlank()) {
                                IconButton(onClick = { onApiKeyChange("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextTertiary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Paste Button
                    OutlinedButton(
                        onClick = {
                            val clipData = clipboardManager?.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val pasted = clipData.getItemAt(0).text?.toString() ?: ""
                                if (pasted.isNotBlank()) {
                                    onApiKeyChange(pasted.trim())
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Paste Key", fontSize = 12.sp, color = TextSecondary)
                    }

                    // Verify Key Button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            val keyToTest = if (apiKeyInput.isNotBlank()) apiKeyInput else com.example.BuildConfig.GEMINI_API_KEY
                            onVerifyAndSave(keyToTest)
                        },
                        enabled = !isVerifying && (apiKeyInput.isNotBlank() || isBuiltInKeyAvailable),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing...", fontSize = 12.sp, color = Color.White)
                        } else {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test & Save", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }

                // Verification Result Message
                if (verificationResult != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val isSuccess = verificationResult.first
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSuccess) SuccessGreen.copy(alpha = 0.12f) else Error.copy(alpha = 0.12f)
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (isSuccess) SuccessGreen else Error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = verificationResult.second,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = if (isSuccess) SuccessGreenGlow else Error
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onContinue,
                enabled = apiKeyInput.isNotBlank() || isBuiltInKeyAvailable || verificationResult?.first == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("onboarding_api_continue_btn"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Continue to Permissions", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// -------------------------------------------------------------
// STEP 3: SEQUENTIAL PERMISSIONS
// -------------------------------------------------------------
@Composable
private fun Step3Permissions(
    micGranted: Boolean,
    notifGranted: Boolean,
    overlayGranted: Boolean,
    accessGranted: Boolean,
    onRequestRuntimePermissions: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRefresh: () -> Unit,
    onContinue: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Grant System Access 🛡️",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Kavya requires device permissions to hear voice commands, show companion overlay, and perceive screen UI.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = TextSecondary
            )
        }

        // 1. Microphone Permission
        item {
            PermissionSetupCard(
                icon = Icons.Default.Mic,
                title = "1. Microphone & Voice Recognition",
                description = "Required so Kavya can hear your speech and process conversational commands hands-free.",
                isGranted = micGranted,
                isRequired = true,
                actionLabel = "Grant Microphone",
                onAction = onRequestRuntimePermissions
            )
        }

        // 2. Notification Permission
        item {
            PermissionSetupCard(
                icon = Icons.Default.Notifications,
                title = "2. Background Alerts & Notifications",
                description = "Shows live assistant status, background automation updates, and companion feedback.",
                isGranted = notifGranted,
                isRequired = false,
                actionLabel = "Enable Notifications",
                onAction = onRequestRuntimePermissions
            )
        }

        // 3. Overlay Permission
        item {
            PermissionSetupCard(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                title = "3. Floating Companion Overlay",
                description = "Allows Kavya's interactive orb to float gently over any app for instant universal access.",
                isGranted = overlayGranted,
                isRequired = false,
                actionLabel = "Enable Overlay Permission",
                onAction = onRequestOverlay
            )
        }

        // 4. Accessibility Service
        item {
            PermissionSetupCard(
                icon = Icons.Default.Accessibility,
                title = "4. Accessibility & Vision Service",
                description = "Allows Kavya to inspect on-screen UI nodes, read visible text, and assist navigation for you.",
                isGranted = accessGranted,
                isRequired = false,
                actionLabel = "Enable Accessibility Service",
                onAction = onRequestAccessibility
            )
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("onboarding_permissions_continue_btn"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Continue to Summary", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PermissionSetupCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    isRequired: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        backgroundColor = SurfaceGlass.copy(alpha = 0.8f),
        borderColor = if (isGranted) SuccessGreen.copy(alpha = 0.35f) else GlassBorder
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isGranted) SuccessGreen.copy(alpha = 0.15f) else Primary.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isGranted) SuccessGreen else PrimaryLight,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(
                        text = if (isGranted) "Granted" else if (isRequired) "Required" else "Optional",
                        isActive = isGranted,
                        activeColor = SuccessGreen,
                        inactiveColor = if (isRequired) PrimaryLight else TextTertiary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = TextSecondary
                )

                if (!isGranted) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onAction,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(actionLabel, fontSize = 12.sp, color = PrimaryLight, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// STEP 4: DASHBOARD / SETUP SUMMARY
// -------------------------------------------------------------
@Composable
private fun Step4Dashboard(
    hasApiKey: Boolean,
    micGranted: Boolean,
    notifGranted: Boolean,
    overlayGranted: Boolean,
    accessGranted: Boolean,
    onContinue: () -> Unit
) {
    val totalReady = listOf(hasApiKey, micGranted, notifGranted, overlayGranted, accessGranted).count { it }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Setup Overview ✨",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Review your system configuration before starting with Kavya.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = TextSecondary
            )
        }

        // Status Card summary
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                backgroundColor = SurfaceGlass.copy(alpha = 0.9f),
                borderColor = Primary.copy(alpha = 0.3f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🌸", fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "System Readiness",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            "$totalReady of 5 capabilities configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (totalReady >= 2) SuccessGreenGlow else TextSecondary
                        )
                    }
                    StatusBadge(
                        text = if (totalReady >= 2) "Ready" else "Action Needed",
                        isActive = totalReady >= 2,
                        activeColor = SuccessGreen,
                        inactiveColor = Error
                    )
                }
            }
        }

        // Detailed Item List
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                SummaryItemRow("Gemini AI Core", "Intelligence & language model", hasApiKey, Icons.Default.VpnKey)
                HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 6.dp))
                SummaryItemRow("Microphone Audio", "Voice recognition & conversational input", micGranted, Icons.Default.Mic)
                HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 6.dp))
                SummaryItemRow("Notification Alerts", "Background companion feedback", notifGranted, Icons.Default.Notifications)
                HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 6.dp))
                SummaryItemRow("Floating Companion Overlay", "Display orb over any app", overlayGranted, Icons.AutoMirrored.Filled.OpenInNew)
                HorizontalDivider(color = OutlineVariant, modifier = Modifier.padding(vertical = 6.dp))
                SummaryItemRow("Accessibility & Vision Service", "Inspect and understand screen elements", accessGranted, Icons.Default.Accessibility)
            }
        }

        item {
            Text(
                text = "💡 Tip: You can adjust permissions and voice settings anytime from Settings in the app.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = TextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("onboarding_complete_summary_btn"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Complete Setup", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SummaryItemRow(
    title: String,
    subText: String,
    isReady: Boolean,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isReady) SuccessGreen.copy(alpha = 0.12f) else SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isReady) SuccessGreen else TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TextPrimary)
            Text(subText, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp), color = TextTertiary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            if (isReady) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isReady) SuccessGreen else TextTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// -------------------------------------------------------------
// STEP 5: CELEBRATION & FINISH
// -------------------------------------------------------------
@Composable
private fun Step5Finish(
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Glowing Kavya Voice Orb in active celebration
            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center
            ) {
                KavyaVoiceOrb(
                    state = com.example.ui.components.VoiceState.SPEAKING,
                    onClick = {}
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            StatusBadge(
                text = "Setup Completed ✨",
                isActive = true,
                activeColor = SuccessGreen
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "You're All Set!",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp
                ),
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Kavya is ready to assist, listen, and keep you company. Say \"Hey Kavya\" or type anything in chat to get started! ✨",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("onboarding_enter_kavya_btn"),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Enter Kavya ✨",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }
    }
}
