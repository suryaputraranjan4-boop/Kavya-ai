package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.navigation.NavController
import com.example.ui.components.GlassCard
import com.example.ui.components.KavyaVoiceOrb
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupKavyaScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    var openRouterKeyInput by remember { mutableStateOf(AppPreferences.getOpenRouterApiKey(context)) }
    var openRouterModelInput by remember { mutableStateOf(AppPreferences.getOpenRouterModel(context).ifBlank { "openrouter/free" }) }
    var isOpenRouterKeyVisible by remember { mutableStateOf(false) }
    var isOpenRouterTesting by remember { mutableStateOf(false) }
    var openRouterTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var huggingFaceKeyInput by remember { mutableStateOf(AppPreferences.getHuggingFaceApiKey(context)) }
    var huggingFaceModelInput by remember { mutableStateOf(AppPreferences.getHuggingFaceModel(context).ifBlank { "distilbert-base-uncased" }) }
    var isHfTesting by remember { mutableStateOf(false) }
    var hfTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var geminiKeyInput by remember { mutableStateOf(AppPreferences.getCustomApiKey(context)) }
    var isGeminiTesting by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val presetModels = listOf(
        Triple("openrouter/free", "OpenRouter Free Router", true),
        Triple("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash (Free)", true),
        Triple("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (Free)", true),
        Triple("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", false),
        Triple("openai/gpt-4o-mini", "GPT-4o Mini", false)
    )

    fun navigateNext() {
        AppPreferences.setApiSetupCompleted(context, true)
        if (AppPreferences.isOnboardingCompleted(context)) {
            navController.navigate("home") {
                popUpTo("setup_kavya") { inclusive = true }
            }
        } else {
            navController.navigate("onboarding") {
                popUpTo("setup_kavya") { inclusive = true }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, BackgroundGradientEnd, Color(0xFF0F111A))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hero Orb & Header
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(modifier = Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                        KavyaVoiceOrb(
                            state = com.example.ui.components.VoiceState.IDLE,
                            onClick = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Text(
                        text = "Set up Kavya",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = TextPrimary
                    )

                    Text(
                        text = "Kavya's Architecture: Gemini is the Main Brain & Orchestrator. OpenRouter, Hugging Face, and Public APIs act as optional supporting resources.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        ),
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // SECTION 1: OpenRouter (Gemini's AI Partner) Card
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = OutlineVariant
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "OpenRouter (Optional AI Partner)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Gemini's secondary reasoning partner for complex tasks",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextTertiary
                                )
                            }
                        }

                        // Field 1: OpenRouter API Key
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "OpenRouter API Key",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary
                                )

                                val clipText = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clipText.isNullOrBlank() && clipText.startsWith("sk-or-")) {
                                    Text(
                                        text = "Paste from Clipboard",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = AccentCyan,
                                        modifier = Modifier.clickable {
                                            openRouterKeyInput = clipText.trim()
                                            openRouterTestResult = null
                                            errorMessage = null
                                        }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = openRouterKeyInput,
                                onValueChange = {
                                    openRouterKeyInput = it
                                    openRouterTestResult = null
                                    errorMessage = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("openrouter_api_key_input"),
                                placeholder = {
                                    Text("sk-or-v1-...", color = TextTertiary, fontSize = 14.sp)
                                },
                                visualTransformation = if (isOpenRouterKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = OutlineVariant,
                                    focusedContainerColor = SurfaceVariant.copy(alpha = 0.6f),
                                    unfocusedContainerColor = SurfaceVariant.copy(alpha = 0.4f),
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                trailingIcon = {
                                    IconButton(onClick = { isOpenRouterKeyVisible = !isOpenRouterKeyVisible }) {
                                        Icon(
                                            imageVector = if (isOpenRouterKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (isOpenRouterKeyVisible) "Hide key" else "Show key",
                                            tint = TextTertiary
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.clearFocus() })
                            )
                        }

                        // Field 2: Model
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Model",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )

                            OutlinedTextField(
                                value = openRouterModelInput,
                                onValueChange = {
                                    openRouterModelInput = it
                                    openRouterTestResult = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("openrouter_model_input"),
                                placeholder = {
                                    Text("openrouter/free", color = TextTertiary, fontSize = 14.sp)
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = OutlineVariant,
                                    focusedContainerColor = SurfaceVariant.copy(alpha = 0.6f),
                                    unfocusedContainerColor = SurfaceVariant.copy(alpha = 0.4f),
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )

                            // Preset chips
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                presetModels.forEach { (presetId, label, isFree) ->
                                    val isSelected = openRouterModelInput.trim() == presetId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) Primary.copy(alpha = 0.15f) else Color.Transparent)
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) Primary else OutlineVariant.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                openRouterModelInput = presetId
                                                openRouterTestResult = null
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                openRouterModelInput = presetId
                                                openRouterTestResult = null
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = Primary)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = presetId,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                                                color = TextTertiary
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isFree) SuccessGreen.copy(alpha = 0.18f) else AccentPurpleLight.copy(alpha = 0.18f))
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (isFree) "FREE" else "PAID",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = if (isFree) SuccessGreenGlow else AccentPurpleLight
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Test OpenRouter Status Banner
                        AnimatedVisibility(visible = openRouterTestResult != null) {
                            val isSuccess = openRouterTestResult?.first == true
                            val msg = openRouterTestResult?.second ?: ""
                            val bgColor = if (isSuccess) SuccessGreen.copy(alpha = 0.15f) else Error.copy(alpha = 0.15f)
                            val borderColor = if (isSuccess) SuccessGreen else Error
                            val textColor = if (isSuccess) SuccessGreenGlow else Error

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bgColor)
                                    .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                                    color = textColor
                                )
                            }
                        }

                        // Test Connection Button
                        OutlinedButton(
                            onClick = {
                                if (openRouterKeyInput.isBlank()) {
                                    openRouterTestResult = Pair(false, "OpenRouter connection failed. Please check your API key and internet connection.")
                                    return@OutlinedButton
                                }
                                coroutineScope.launch {
                                    isOpenRouterTesting = true
                                    openRouterTestResult = null
                                    errorMessage = null
                                    focusManager.clearFocus()
                                    val result = AppPreferences.validateOpenRouterConnection(
                                        apiKey = openRouterKeyInput.trim(),
                                        model = openRouterModelInput.trim().ifBlank { "openrouter/free" }
                                    )
                                    openRouterTestResult = result
                                    isOpenRouterTesting = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("test_connection_btn"),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.6f)),
                            enabled = !isOpenRouterTesting
                        ) {
                            if (isOpenRouterTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = AccentCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing connection...", color = AccentCyan, fontSize = 13.sp)
                            } else {
                                Icon(
                                    Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Test Connection", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // SECTION 2: Hugging Face (Optional Specialized AI) Card
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = OutlineVariant
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = AccentPurpleLight, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Hugging Face (Optional Specialized AI)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Specialized AI models for niche inferences",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextTertiary
                                )
                            }
                        }

                        OutlinedTextField(
                            value = huggingFaceKeyInput,
                            onValueChange = {
                                huggingFaceKeyInput = it
                                hfTestResult = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("hf_... (optional)", color = TextTertiary, fontSize = 14.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )

                        AnimatedVisibility(visible = hfTestResult != null) {
                            val isPass = hfTestResult?.first == true
                            Text(
                                text = hfTestResult?.second ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isPass) SuccessGreenGlow else Error
                            )
                        }

                        if (huggingFaceKeyInput.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isHfTesting = true
                                        hfTestResult = AppPreferences.validateHuggingFaceConnection(
                                            apiKey = huggingFaceKeyInput.trim(),
                                            model = huggingFaceModelInput.trim()
                                        )
                                        isHfTesting = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(if (isHfTesting) "Testing Hugging Face..." else "Test Hugging Face", color = AccentPurpleLight)
                            }
                        }
                    }
                }
            }

            // Error Banner
            if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Error,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // SECTION 3: Action Buttons
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    // Save & Continue Button
                    Button(
                        onClick = {
                            val key = openRouterKeyInput.trim()
                            if (key.isBlank()) {
                                errorMessage = "OpenRouter API Key is empty. Please enter your key or choose 'Continue with Gemini only' below."
                                return@Button
                            }
                            val model = openRouterModelInput.trim().ifBlank { "openrouter/free" }

                            // Persist securely
                            AppPreferences.setOpenRouterApiKey(context, key)
                            AppPreferences.setOpenRouterModel(context, model)
                            if (huggingFaceKeyInput.isNotBlank()) {
                                AppPreferences.setHuggingFaceApiKey(context, huggingFaceKeyInput.trim())
                                AppPreferences.setHuggingFaceModel(context, huggingFaceModelInput.trim())
                            }
                            if (geminiKeyInput.isNotBlank()) {
                                AppPreferences.setCustomApiKey(context, geminiKeyInput.trim())
                            }
                            navigateNext()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_continue_btn"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text(
                            text = "Save & Continue",
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

                    // Continue with Gemini only button (OpenRouter is strictly optional)
                    OutlinedButton(
                        onClick = {
                            if (geminiKeyInput.isNotBlank()) {
                                AppPreferences.setCustomApiKey(context, geminiKeyInput.trim())
                            }
                            if (huggingFaceKeyInput.isNotBlank()) {
                                AppPreferences.setHuggingFaceApiKey(context, huggingFaceKeyInput.trim())
                            }
                            navigateNext()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("skip_setup_btn"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Continue with Gemini only (Boss)",
                            color = PrimaryLight,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
