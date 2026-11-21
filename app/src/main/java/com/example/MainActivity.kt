package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.KavyaSideMenu
import com.example.ui.navigation.AppNavGraph
import com.example.ui.navigation.LocalDrawerState
import com.example.ui.theme.*
import com.example.viewmodel.KavyaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: KavyaViewModel by viewModels()

  companion object {
    private const val TAG = "MainActivity"
    const val EXTRA_OPEN_ROUTE = "open_route"
  }

  private val pendingDeepLinkRoute = mutableStateOf<String?>(null)

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    Log.d(TAG, "Permissions result: $permissions")
    if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
      Log.i(TAG, "RECORD_AUDIO granted.")
      if (com.example.utils.AppPreferences.isMicEnabled(this)) {
        com.example.voice.KavyaMicrophoneEngine.startService(this)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.example.orchestrator.AiAuthorityGateway.invalidateAllOnStartup()
    enableEdgeToEdge()
    pendingDeepLinkRoute.value = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
    handleAssistantIntentIfNeeded(intent)
    handleOAuthRedirectIfNeeded(intent)

    requestAppPermissions()

    setContent {
      MyApplicationTheme {
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val deepLinkTarget by pendingDeepLinkRoute
        val isAssistantPopupOpen by com.example.voice.KavyaMicrophoneEngine.isAssistantPopupVisible.collectAsState()
        val assistantState by com.example.voice.KavyaMicrophoneEngine.assistantState.collectAsState()
        val micAmplitude by com.example.voice.KavyaMicrophoneEngine.amplitude.collectAsState()
        val partialTranscript by com.example.voice.KavyaMicrophoneEngine.partialText.collectAsState()

        LaunchedEffect(deepLinkTarget) {
            val target = deepLinkTarget
            if (!target.isNullOrBlank()) {
                navController.navigate(target) { launchSingleTop = true }
                pendingDeepLinkRoute.value = null
            }
        }
        
        CompositionLocalProvider(LocalDrawerState provides drawerState) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = androidx.compose.ui.graphics.Color(0x5C000000),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        drawerTonalElevation = 0.dp,
                        windowInsets = WindowInsets(0),
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.82f)
                            .widthIn(max = 336.dp)
                    ) {
                        KavyaSideMenu(
                            navController = navController,
                            drawerState = drawerState,
                            scope = scope
                        )
                    }
                }
            ) {
                val isDrawerOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
                val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isDrawerOpen) {
                    Modifier.graphicsLayer {
                        renderEffect = android.graphics.RenderEffect.createBlurEffect(
                            18f, 18f, android.graphics.Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    }
                } else Modifier

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = Background,
                        contentWindowInsets = WindowInsets(0),
                        modifier = Modifier.fillMaxSize().then(blurModifier)
                    ) { innerPadding ->
                        AppNavGraph(
                            navController = navController,
                            viewModel = viewModel,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }

                    val latestCaption by viewModel.latestKavyaCaption.collectAsState()
                    val micEngineState by com.example.voice.KavyaMicrophoneEngine.engineState.collectAsState()
                    val lastVoiceError by com.example.voice.KavyaMicrophoneEngine.lastVoiceError.collectAsState()
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isAssistantPopupOpen,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 3 },
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 3 }
                    ) {
                        KavyaAssistantActivationPopup(
                            assistantState = assistantState,
                            micEngineState = micEngineState,
                            amplitude = micAmplitude,
                            partialTranscript = partialTranscript,
                            aiResponseText = latestCaption.orEmpty(),
                            errorText = lastVoiceError?.userFacingEnglish,
                            isDefaultAssistant = com.example.services.KavyaAssistantInteractionService.isDefaultDigitalAssistant(this@MainActivity),
                            onExplainScreen = {
                                viewModel.explainCurrentScreen("Explain this screen")
                                com.example.voice.KavyaMicrophoneEngine.dismissAssistantPopup(this@MainActivity)
                            },
                            onStopListening = {
                                com.example.voice.KavyaMicrophoneEngine.stopService(this@MainActivity)
                            },
                            onRetry = {
                                com.example.voice.KavyaMicrophoneEngine.startService(this@MainActivity)
                            },
                            onOpenDefaultAssistantSettings = {
                                val intent = com.example.services.KavyaAssistantInteractionService.createDefaultAssistantSetupIntent(this@MainActivity)
                                runCatching { startActivity(intent) }.onFailure {
                                    com.example.voice.KavyaMicrophoneEngine.openDefaultAssistantSettings(this@MainActivity)
                                }
                            },
                            onCancel = {
                                com.example.voice.KavyaMicrophoneEngine.stopService(this@MainActivity)
                                com.example.voice.KavyaMicrophoneEngine.dismissAssistantPopup(this@MainActivity)
                            }
                        )
                    }
                }
            }
        }
      }
    }
  }

  private fun handleAssistantIntentIfNeeded(intent: android.content.Intent?) {
    val action = intent?.action ?: return
    if (action == android.content.Intent.ACTION_ASSIST || action == android.content.Intent.ACTION_VOICE_COMMAND) {
      com.example.voice.KavyaMicrophoneEngine.init(this)
      com.example.agent.MicrophoneEngine.getInstance(this).activateAssistantPopup()
      if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        com.example.voice.KavyaMicrophoneEngine.startService(this)
      }
    }
  }

  private fun handleOAuthRedirectIfNeeded(intent: android.content.Intent?) {
    val data = intent?.data ?: return
    if (data.scheme == "kavya" && data.host == "oauth") {
      pendingDeepLinkRoute.value = "kucp_dashboard"
      lifecycleScope.launch(Dispatchers.IO) {
        try {
          com.example.kucp.KucpGateway.getInstance(this@MainActivity).completeOAuthCallback(data)
        } catch (e: Exception) {
          Log.w(TAG, "KUCP OAuth callback error: ${e.javaClass.simpleName}")
        }
      }
    }
  }

  private fun requestAppPermissions() {
    val permissions = mutableListOf<String>()
    permissions.add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val missing = permissions.filter {
      androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    if (missing.isNotEmpty()) {
      permissionLauncher.launch(missing.toTypedArray())
    } else {
      // Permission already granted, start microphone architecture if enabled by user
      if (com.example.utils.AppPreferences.isMicEnabled(this)) {
        com.example.voice.KavyaMicrophoneEngine.startService(this)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    com.example.state.KavyaStateManager.isActivityVisible = true
    com.example.visual.JarvisOverlayController.syncFloatingOrbFromPreferences(this)
    // Recover microphone cleanly if Android silenced or preempted it while switching apps
    com.example.agent.MicrophoneEngine.getExistingInstance()?.ensureHealthyOnForeground()
  }

  override fun onPause() {
    super.onPause()
    com.example.state.KavyaStateManager.isActivityVisible = false
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pendingDeepLinkRoute.value = intent.getStringExtra(EXTRA_OPEN_ROUTE)
    handleAssistantIntentIfNeeded(intent)
    handleOAuthRedirectIfNeeded(intent)
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    com.example.ai.offline.Gemma4E4BEngine.getInstance(this).onTrimMemory(level)
    com.example.ai.offline.OnDeviceVisionOrchestrator.getInstance(this).onTrimMemory(level)
    com.example.ai.tts.VoiceEngineManager.getInstance(this).onTrimMemory(level)
    com.example.vision.VisualizationOrchestrator.onTrimMemory(level)
  }
}

@Composable
private fun KavyaAssistantActivationPopup(
    assistantState: com.example.agent.MicrophoneEngine.WakeWordAssistantState,
    micEngineState: com.example.voice.KavyaMicrophoneEngine.EngineState,
    amplitude: Float,
    partialTranscript: String,
    aiResponseText: String,
    errorText: String?,
    isDefaultAssistant: Boolean,
    onExplainScreen: () -> Unit,
    onStopListening: () -> Unit,
    onRetry: () -> Unit,
    onOpenDefaultAssistantSettings: () -> Unit,
    onCancel: () -> Unit
) {
    val isListeningActive = micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ACTIVE ||
        micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_STARTING
    val statusLabel = when {
        !errorText.isNullOrBlank() -> errorText
        micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_OFF -> "Microphone stopped"
        micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_MUTED -> "Microphone muted"
        assistantState == com.example.agent.MicrophoneEngine.WakeWordAssistantState.PROCESSING -> "Understanding your request..."
        assistantState == com.example.agent.MicrophoneEngine.WakeWordAssistantState.SPEAKING -> "Kavya is responding"
        assistantState == com.example.agent.MicrophoneEngine.WakeWordAssistantState.COMMAND_LISTENING -> "Listening • Speak now (Hindi, Hinglish, or English)"
        else -> "Listening • Say \"Hey Kavya\" or speak your command"
    }
    val statusDotColor = when {
        !errorText.isNullOrBlank() || micEngineState == com.example.voice.KavyaMicrophoneEngine.EngineState.MIC_ERROR -> Error
        isListeningActive -> SuccessGreen
        else -> AccentCyan
    }

    val pulseTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "voice_popup_wave")
    val pulsePhase by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 1100, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "wave_phase"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = androidx.compose.ui.Alignment.BottomCenter
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            color = androidx.compose.ui.graphics.Color(0xF0121722),
            tonalElevation = 0.dp,
            shadowElevation = 24.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                com.example.ui.components.AppleGlassBorderBrush
            ),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp)
                .testTag("voice_assistant_popup")
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Kavya Voice Assistant",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = statusLabel,
                                color = if (!errorText.isNullOrBlank()) Error else PrimaryLight,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                        }
                    }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("voice_popup_cancel_btn")
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                }

                // Animated 9-bar Listening Waveform synced with live microphone amplitude
                val normalizedAmp = (amplitude / 1800f).coerceIn(0.12f, 1f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.CenterHorizontally),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    for (barIdx in 0 until 9) {
                        val wave = if (isListeningActive) {
                            (0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(pulsePhase + barIdx * 0.55f)).toFloat()) * normalizedAmp
                        } else {
                            0.18f
                        }
                        Box(
                            modifier = Modifier
                                .width(5.dp)
                                .height((6f + 22f * wave).dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .background(if (isListeningActive) PrimaryLight else SurfaceVariant)
                        )
                    }
                }

                if (partialTranscript.isNotBlank()) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = androidx.compose.ui.graphics.Color(0x991B2232),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("You said", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = "\"$partialTranscript\"",
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                if (aiResponseText.isNotBlank()) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = Primary.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Kavya", color = PrimaryLight, style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = aiResponseText.take(260),
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4
                            )
                        }
                    }
                }

                // Primary Row: EXPLAIN SCREEN + Stop Listening / Retry
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onExplainScreen,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .heightIn(min = 48.dp)
                            .testTag("voice_popup_explain_screen_btn")
                    ) {
                        Text("EXPLAIN SCREEN", maxLines = 1, color = androidx.compose.ui.graphics.Color.White)
                    }
                    if (isListeningActive) {
                        OutlinedButton(
                            onClick = onStopListening,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Error.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("voice_popup_stop_btn")
                        ) {
                            Text("Stop Listening", maxLines = 1, color = Error)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onRetry,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryLight.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("voice_popup_retry_btn")
                        ) {
                            Text("Retry Mic", maxLines = 1, color = PrimaryLight)
                        }
                    }
                }

                // Secondary Row: Default Digital Assistant status / setup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isDefaultAssistant) "Default Digital Assistant active" else "Set Kavya as Default Assistant for OS trigger",
                        color = if (isDefaultAssistant) SuccessGreen else TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isDefaultAssistant) {
                        TextButton(
                            onClick = onOpenDefaultAssistantSettings,
                            modifier = Modifier.testTag("voice_popup_default_assistant_btn")
                        ) {
                            Text("Set Default", color = PrimaryLight)
                        }
                    }
                }
            }
        }
    }
}

