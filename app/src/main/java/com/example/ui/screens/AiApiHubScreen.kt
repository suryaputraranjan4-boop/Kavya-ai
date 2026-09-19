package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.ai.providers.ConnectionStatus
import com.example.ai.providers.RuntimeActivityEvent
import com.example.ai.providers.RuntimeActivityTracker
import com.example.ui.components.GlassCard
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import com.example.utils.CustomApiConfig
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiApiHubScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Activity tracker events
    val recentEvents by RuntimeActivityTracker.eventsFlow.collectAsState()
    val metrics = remember(recentEvents) { RuntimeActivityTracker.getMetrics(context) }

    // OpenRouter State
    var orKeyInput by remember { mutableStateOf(AppPreferences.getOpenRouterApiKey(context)) }
    var orModel by remember { mutableStateOf(AppPreferences.getOpenRouterModel(context).ifBlank { "openrouter/free" }) }
    var orEnabled by remember { mutableStateOf(AppPreferences.isOpenRouterEnabled(context)) }
    var isOrKeyVisible by remember { mutableStateOf(false) }
    var isOrEditing by remember { mutableStateOf(false) }
    var isOrTesting by remember { mutableStateOf(false) }
    var orTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var availableOrModels by remember { mutableStateOf<List<String>>(com.example.ai.providers.OpenRouterProvider.DEFAULT_VERIFIED_MODELS.map { it.id }) }

    LaunchedEffect(orKeyInput) {
        if (orKeyInput.isNotBlank()) {
            try {
                val models = com.example.ai.providers.OpenRouterProvider().getModels(context)
                if (models.isNotEmpty()) {
                    availableOrModels = models.map { it.id }
                }
            } catch (_: Exception) {}
        }
    }

    // Hugging Face State
    var hfKeyInput by remember { mutableStateOf(AppPreferences.getHuggingFaceApiKey(context)) }
    var hfModel by remember { mutableStateOf(AppPreferences.getHuggingFaceModel(context).ifBlank { "distilbert-base-uncased" }) }
    var hfEnabled by remember { mutableStateOf(AppPreferences.isHuggingFaceEnabled(context)) }
    var isHfKeyVisible by remember { mutableStateOf(false) }
    var isHfEditing by remember { mutableStateOf(false) }
    var isHfTesting by remember { mutableStateOf(false) }
    var hfTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var availableHfModels by remember { mutableStateOf<List<String>>(com.example.ai.providers.HuggingFaceProvider.DEFAULT_VERIFIED_MODELS.map { it.id }) }

    LaunchedEffect(hfKeyInput) {
        if (hfKeyInput.isNotBlank()) {
            try {
                val models = com.example.ai.providers.HuggingFaceProvider().getModels(context)
                if (models.isNotEmpty()) {
                    availableHfModels = models.map { it.id }
                }
            } catch (_: Exception) {}
        }
    }

    // Gemini State
    var geminiKeyInput by remember { mutableStateOf(AppPreferences.getCustomApiKey(context)) }
    var geminiModel by remember { mutableStateOf(AppPreferences.getGeminiModel(context)) }
    var isGeminiKeyVisible by remember { mutableStateOf(false) }
    var isGeminiEditing by remember { mutableStateOf(false) }
    var isGeminiTesting by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val effectiveGeminiKey = AppPreferences.getEffectiveApiKey(context)
    val geminiPresetModels = remember {
        com.example.ai.providers.GeminiProvider.VERIFIED_MODELS.map { it.id }
    }

    // Custom & Future APIs State
    var customApis by remember { mutableStateOf(AppPreferences.getCustomApis(context)) }
    var showAddApiDialog by remember { mutableStateOf(false) }
    var customApiTestResults by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }
    var testingApiId by remember { mutableStateOf<String?>(null) }

    // Accessibility Service State
    val isAccessibilityEnabled = com.example.services.KavyaAccessibilityService.instance != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, BackgroundGradientEnd, Color(0xFF0D0F17))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "API Dashboard",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = TextPrimary
                        )
                        Text(
                            text = "Manage OpenRouter, Hugging Face, Gemini & Custom APIs",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Info Banner
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.75f),
                    borderColor = AccentPurpleLight.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = AccentPurpleLight,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Multi-Provider AI & API Hub",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = AccentPurpleLight
                            )
                            Text(
                                "Configure your real API keys below. Kavya connects directly to real official endpoints. You can also add more custom APIs anytime.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 1. OPENROUTER API
            // =========================================================================
            item {
                Text(
                    text = "OpenRouter API",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                ApiCard(
                    title = "OpenRouter",
                    badge = "AI PARTNER / MODELS",
                    badgeColor = AccentPurpleLight,
                    icon = Icons.Default.Hub,
                    isEnabled = orEnabled,
                    onToggleEnabled = {
                        orEnabled = it
                        AppPreferences.setOpenRouterEnabled(context, it)
                    },
                    status = if (!orEnabled) {
                        ConnectionStatus.DISABLED
                    } else if (orKeyInput.isBlank()) {
                        ConnectionStatus.NOT_CONFIGURED
                    } else if (isOrTesting) {
                        ConnectionStatus.TESTING
                    } else if (orTestResult?.first == true) {
                        ConnectionStatus.CONNECTED
                    } else if (orTestResult?.first == false) {
                        ConnectionStatus.ERROR
                    } else {
                        ConnectionStatus.CONFIGURED
                    },
                    apiKey = orKeyInput,
                    onApiKeyChange = { orKeyInput = it },
                    isKeyVisible = isOrKeyVisible,
                    onToggleKeyVisibility = { isOrKeyVisible = !isOrKeyVisible },
                    isEditing = isOrEditing,
                    onStartEdit = { isOrEditing = true },
                    onSaveKey = {
                        AppPreferences.setOpenRouterApiKey(context, orKeyInput.trim())
                        isOrEditing = false
                        coroutineScope.launch {
                            isOrTesting = true
                            orTestResult = AppPreferences.validateOpenRouterConnection(orKeyInput, orModel)
                            isOrTesting = false
                        }
                    },
                    onRemoveKey = {
                        orKeyInput = ""
                        AppPreferences.clearOpenRouterApiKey(context)
                        isOrEditing = false
                        orTestResult = null
                    },
                    selectedModel = orModel,
                    onModelChange = {
                        orModel = it
                        AppPreferences.setOpenRouterModel(context, it)
                    },
                    presetModels = availableOrModels,
                    modelPlaceholder = "e.g. openrouter/free or meta-llama/llama-3.3-70b-instruct:free",
                    isTesting = isOrTesting,
                    testResult = orTestResult,
                    onTestConnection = {
                        coroutineScope.launch {
                            isOrTesting = true
                            orTestResult = AppPreferences.validateOpenRouterConnection(orKeyInput, orModel)
                            isOrTesting = false
                        }
                    }
                )
            }

            // =========================================================================
            // 2. HUGGING FACE API
            // =========================================================================
            item {
                Text(
                    text = "Hugging Face API",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                ApiCard(
                    title = "Hugging Face",
                    badge = "SPECIALIZED INFERENCE",
                    badgeColor = AccentYellow,
                    icon = Icons.Default.SmartToy,
                    isEnabled = hfEnabled,
                    onToggleEnabled = {
                        hfEnabled = it
                        AppPreferences.setHuggingFaceEnabled(context, it)
                    },
                    status = if (!hfEnabled) {
                        ConnectionStatus.DISABLED
                    } else if (hfKeyInput.isBlank()) {
                        ConnectionStatus.NOT_CONFIGURED
                    } else if (isHfTesting) {
                        ConnectionStatus.TESTING
                    } else if (hfTestResult?.first == true) {
                        ConnectionStatus.CONNECTED
                    } else if (hfTestResult?.first == false) {
                        ConnectionStatus.ERROR
                    } else {
                        ConnectionStatus.CONFIGURED
                    },
                    apiKey = hfKeyInput,
                    onApiKeyChange = { hfKeyInput = it },
                    isKeyVisible = isHfKeyVisible,
                    onToggleKeyVisibility = { isHfKeyVisible = !isHfKeyVisible },
                    isEditing = isHfEditing,
                    onStartEdit = { isHfEditing = true },
                    onSaveKey = {
                        AppPreferences.setHuggingFaceApiKey(context, hfKeyInput.trim())
                        isHfEditing = false
                        coroutineScope.launch {
                            isHfTesting = true
                            hfTestResult = AppPreferences.validateHuggingFaceConnection(hfKeyInput, hfModel)
                            isHfTesting = false
                        }
                    },
                    onRemoveKey = {
                        hfKeyInput = ""
                        AppPreferences.clearHuggingFaceApiKey(context)
                        isHfEditing = false
                        hfTestResult = null
                    },
                    selectedModel = hfModel,
                    onModelChange = {
                        hfModel = it
                        AppPreferences.setHuggingFaceModel(context, it)
                    },
                    presetModels = availableHfModels,
                    modelPlaceholder = "e.g. distilbert-base-uncased or cardiffnlp/twitter-roberta...",
                    isTesting = isHfTesting,
                    testResult = hfTestResult,
                    onTestConnection = {
                        coroutineScope.launch {
                            isHfTesting = true
                            hfTestResult = AppPreferences.validateHuggingFaceConnection(hfKeyInput, hfModel)
                            isHfTesting = false
                        }
                    }
                )
            }

            // =========================================================================
            // 3. GOOGLE GEMINI API (Main Brain)
            // =========================================================================
            item {
                Text(
                    text = "Google Gemini API (Main Brain)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                ApiCard(
                    title = "Google Gemini",
                    badge = "MAIN BRAIN / BOSS",
                    badgeColor = AccentCyan,
                    icon = Icons.Default.Psychology,
                    isEnabled = true,
                    onToggleEnabled = null, // Main orchestrator is always on
                    status = if (effectiveGeminiKey.isBlank()) {
                        ConnectionStatus.NOT_CONFIGURED
                    } else if (isGeminiTesting) {
                        ConnectionStatus.TESTING
                    } else if (geminiTestResult?.first == true) {
                        ConnectionStatus.CONNECTED
                    } else if (geminiTestResult?.first == false) {
                        ConnectionStatus.ERROR
                    } else {
                        ConnectionStatus.CONFIGURED
                    },
                    apiKey = geminiKeyInput,
                    onApiKeyChange = { geminiKeyInput = it },
                    isKeyVisible = isGeminiKeyVisible,
                    onToggleKeyVisibility = { isGeminiKeyVisible = !isGeminiKeyVisible },
                    isEditing = isGeminiEditing,
                    onStartEdit = { isGeminiEditing = true },
                    onSaveKey = {
                        AppPreferences.setCustomApiKey(context, geminiKeyInput.trim())
                        isGeminiEditing = false
                        coroutineScope.launch {
                            isGeminiTesting = true
                            geminiTestResult = AppPreferences.validateGeminiApiKey(
                                if (geminiKeyInput.isNotBlank()) geminiKeyInput else effectiveGeminiKey
                            )
                            isGeminiTesting = false
                        }
                    },
                    onRemoveKey = {
                        geminiKeyInput = ""
                        AppPreferences.clearCustomApiKey(context)
                        isGeminiEditing = false
                        geminiTestResult = null
                    },
                    selectedModel = geminiModel,
                    onModelChange = {
                        geminiModel = it
                        AppPreferences.setGeminiModel(context, it)
                    },
                    presetModels = geminiPresetModels,
                    modelPlaceholder = "gemini-2.5-flash",
                    isTesting = isGeminiTesting,
                    testResult = geminiTestResult,
                    onTestConnection = {
                        coroutineScope.launch {
                            isGeminiTesting = true
                            geminiTestResult = AppPreferences.validateGeminiApiKey(
                                if (geminiKeyInput.isNotBlank()) geminiKeyInput else effectiveGeminiKey
                            )
                            isGeminiTesting = false
                        }
                    }
                )
            }

            // =========================================================================
            // 4. CUSTOM & FUTURE APIS (In Future add more APIs)
            // =========================================================================
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Custom & Future APIs",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Add Groq, Perplexity, Together AI, Ollama or any REST API",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { showAddApiDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ Add API", fontSize = 12.sp)
                    }
                }
            }

            if (customApis.isEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        backgroundColor = SurfaceGlass.copy(alpha = 0.6f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.AddCircleOutline,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                "No Custom APIs Added Yet",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = TextSecondary
                            )
                            Text(
                                "You can add any other API provider (Groq, Perplexity, DeepSeek, Together AI, Local Ollama, etc.) anytime by clicking '+ Add API'.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = TextTertiary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(customApis, key = { it.id }) { api ->
                    CustomApiCard(
                        api = api,
                        isTesting = testingApiId == api.id,
                        testResult = customApiTestResults[api.id],
                        onToggleEnabled = { isEnabled ->
                            AppPreferences.setCustomApiEnabled(context, api.id, isEnabled)
                            customApis = AppPreferences.getCustomApis(context)
                        },
                        onDelete = {
                            AppPreferences.deleteCustomApi(context, api.id)
                            customApis = AppPreferences.getCustomApis(context)
                        },
                        onTest = {
                            coroutineScope.launch {
                                testingApiId = api.id
                                val res = AppPreferences.validateCustomApiConnection(api)
                                customApiTestResults = customApiTestResults + (api.id to res)
                                testingApiId = null
                            }
                        }
                    )
                }
            }

            // =========================================================================
            // 5. SYSTEM STATUS & RECENT ACTIVITY
            // =========================================================================
            item {
                Text(
                    text = "System Status & API Activity",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
                    borderColor = OutlineVariant
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Metric Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusMetricChip(
                                label = "TOTAL PROVIDERS",
                                value = "${(metrics["total_providers"] ?: 4) + customApis.size}",
                                color = AccentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            StatusMetricChip(
                                label = "CONNECTED",
                                value = "${metrics["connected_providers"] ?: 0}",
                                color = SuccessGreenGlow,
                                modifier = Modifier.weight(1f)
                            )
                            StatusMetricChip(
                                label = "ACTIVE MODELS",
                                value = "${(metrics["active_ai_models"] ?: 0) + customApis.count { it.isEnabled }}",
                                color = AccentPurpleLight,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Live API Events",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextSecondary
                            )
                            Text(
                                if (recentEvents.isEmpty()) "No requests yet" else "${recentEvents.size} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }

                        if (recentEvents.isEmpty()) {
                            Text(
                                "Real events will appear here as Gemini processes requests or consults OpenRouter, Hugging Face, or Custom APIs.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = TextTertiary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                recentEvents.take(5).forEach { event ->
                                    RuntimeEventRow(event)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // DIALOG: ADD NEW CUSTOM API
    // =========================================================================
    if (showAddApiDialog) {
        var newApiName by remember { mutableStateOf("") }
        var newApiEndpoint by remember { mutableStateOf("") }
        var newApiKey by remember { mutableStateOf("") }
        var newApiModel by remember { mutableStateOf("") }
        var isNewKeyVisible by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddApiDialog = false },
            containerColor = Surface,
            title = {
                Text("Add New API Provider", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Add any future API (Groq, Perplexity, DeepSeek, Together AI, Mistral, Ollama, or custom endpoint).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = newApiName,
                        onValueChange = { newApiName = it },
                        label = { Text("Provider Name (e.g. Groq)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newApiEndpoint,
                        onValueChange = { newApiEndpoint = it },
                        label = { Text("Endpoint / Base URL") },
                        placeholder = { Text("https://api.groq.com/openai/v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newApiKey,
                        onValueChange = { newApiKey = it },
                        label = { Text("API Key") },
                        visualTransformation = if (isNewKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isNewKeyVisible = !isNewKeyVisible }) {
                                Icon(
                                    imageVector = if (isNewKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newApiModel,
                        onValueChange = { newApiModel = it },
                        label = { Text("Model Name (optional)") },
                        placeholder = { Text("e.g. llama-3.3-70b-versatile") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMsg != null) {
                        Text(errorMsg!!, color = Error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newApiName.isBlank()) {
                            errorMsg = "Please provide a name for this API."
                            return@Button
                        }
                        if (newApiEndpoint.isBlank()) {
                            errorMsg = "Please provide the API endpoint URL."
                            return@Button
                        }
                        val config = CustomApiConfig(
                            name = newApiName.trim(),
                            endpointUrl = newApiEndpoint.trim(),
                            apiKey = newApiKey.trim(),
                            model = newApiModel.trim()
                        )
                        AppPreferences.saveCustomApi(context, config)
                        customApis = AppPreferences.getCustomApis(context)
                        showAddApiDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Save API")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddApiDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

// -----------------------------------------------------------------------------
// REUSABLE API CARD COMPONENT
// -----------------------------------------------------------------------------

@Composable
fun ApiCard(
    title: String,
    badge: String,
    badgeColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isEnabled: Boolean,
    onToggleEnabled: ((Boolean) -> Unit)?,
    status: ConnectionStatus,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    isKeyVisible: Boolean,
    onToggleKeyVisibility: () -> Unit,
    isEditing: Boolean,
    onStartEdit: () -> Unit,
    onSaveKey: () -> Unit,
    onRemoveKey: () -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    presetModels: List<String>,
    modelPlaceholder: String,
    isTesting: Boolean,
    testResult: Pair<Boolean, String>?,
    onTestConnection: () -> Unit
) {
    var isCustomModelEditing by remember { mutableStateOf(false) }
    var customModelInput by remember(selectedModel) { mutableStateOf(selectedModel) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        backgroundColor = SurfaceGlass.copy(alpha = 0.85f),
        borderColor = OutlineVariant
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(badgeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            RoleBadge(label = badge, color = badgeColor)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        StatusIndicatorRow(status = status)
                    }
                }

                if (onToggleEnabled != null) {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Primary
                        )
                    )
                }
            }

            // Model Selection
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Model: $selectedModel",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { isCustomModelEditing = !isCustomModelEditing }) {
                        Text(
                            if (isCustomModelEditing) "Presets" else "Custom Model",
                            color = AccentCyan,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                if (isCustomModelEditing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customModelInput,
                            onValueChange = { customModelInput = it },
                            placeholder = { Text(modelPlaceholder, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Button(
                            onClick = {
                                onModelChange(customModelInput.trim())
                                isCustomModelEditing = false
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("Set", fontSize = 12.sp)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetModels.take(3).forEach { option ->
                            val isSelected = option == selectedModel
                            val shortName = option.substringAfterLast("/").substringBefore(":")
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (isSelected) Primary else OutlineVariant, RoundedCornerShape(8.dp))
                                    .clickable { onModelChange(option) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = shortName,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = if (isSelected) AccentCyan else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // API Key Section
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("API Key:", style = MaterialTheme.typography.labelSmall, color = TextTertiary)

                if (!isEditing && apiKey.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = SuccessGreenGlow, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "••••••••••••••••••••",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onStartEdit) {
                                Text("Change", color = AccentCyan, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = onRemoveKey) {
                                Text("Remove", color = Error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        placeholder = { Text("Enter your API key...", color = TextTertiary) },
                        visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = onToggleKeyVisibility) {
                                Icon(
                                    imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = TextTertiary
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onSaveKey,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("Save Key")
                        }
                    }
                }
            }

            // Test Result Alert
            if (testResult != null) {
                val isPass = testResult.first
                Text(
                    text = testResult.second,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPass) SuccessGreenGlow else Error
                )
            }

            // Action Bar
            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = !isTesting && isEnabled,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Test Connection", color = AccentCyan)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// CUSTOM API CARD COMPONENT
// -----------------------------------------------------------------------------

@Composable
fun CustomApiCard(
    api: CustomApiConfig,
    isTesting: Boolean,
    testResult: Pair<Boolean, String>?,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        backgroundColor = SurfaceGlass.copy(alpha = 0.8f),
        borderColor = OutlineVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AccentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            api.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            api.endpointUrl,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = api.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Primary
                    )
                )
            }

            val status = if (!api.isEnabled) {
                ConnectionStatus.DISABLED
            } else if (api.apiKey.isBlank()) {
                ConnectionStatus.NOT_CONFIGURED
            } else if (testResult?.first == true) {
                ConnectionStatus.CONNECTED
            } else if (testResult?.first == false) {
                ConnectionStatus.ERROR
            } else {
                ConnectionStatus.CONFIGURED
            }

            StatusIndicatorRow(status = status)

            if (api.model.isNotBlank()) {
                Text(
                    "Model: ${api.model}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = TextSecondary
                )
            }

            if (testResult != null) {
                val isPass = testResult.first
                Text(
                    text = testResult.second,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = if (isPass) SuccessGreenGlow else Error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Error)
                }

                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting && api.isEnabled,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Test Endpoint", fontSize = 11.sp, color = AccentCyan)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// HELPER COMPOSABLES
// -----------------------------------------------------------------------------

@Composable
fun StatusMetricChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RoleBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
            color = color
        )
    }
}

@Composable
fun StatusIndicatorRow(status: ConnectionStatus, note: String? = null) {
    val (dotColor, label) = when (status) {
        ConnectionStatus.CONNECTED -> Pair(SuccessGreenGlow, "Connected")
        ConnectionStatus.CONFIGURED -> Pair(AccentCyan, "Configured")
        ConnectionStatus.NOT_CONFIGURED -> Pair(TextTertiary, "Not Configured")
        ConnectionStatus.TESTING -> Pair(AccentYellow, "Testing...")
        ConnectionStatus.ERROR -> Pair(Error, "Error")
        ConnectionStatus.RATE_LIMITED -> Pair(Error, "Rate Limited")
        ConnectionStatus.DISABLED -> Pair(TextTertiary, "Disabled")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = dotColor
        )
        if (!note.isNullOrBlank()) {
            Text(
                text = " • $note",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RuntimeEventRow(event: RuntimeActivityEvent) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(event.timestamp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextTertiary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = event.providerId.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = AccentCyan
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = event.task,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${event.latencyMs}ms",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextTertiary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (event.success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (event.success) SuccessGreenGlow else Error,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

