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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.KavyaSideMenu
import com.example.ui.navigation.AppNavGraph
import com.example.ui.navigation.LocalDrawerState
import com.example.ui.theme.*
import com.example.viewmodel.KavyaViewModel

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

    requestAppPermissions()

    setContent {
      MyApplicationTheme {
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val deepLinkTarget by pendingDeepLinkRoute
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
                scrimColor = androidx.compose.ui.graphics.Color(0x38000000),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        drawerTonalElevation = 0.dp,
                        windowInsets = WindowInsets(0),
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.80f)
                            .widthIn(max = 330.dp)
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
                            16f, 16f, android.graphics.Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    }
                } else Modifier

                Scaffold(
                    containerColor = Background,
                    modifier = Modifier.fillMaxSize().then(blurModifier)
                ) { innerPadding ->
                    AppNavGraph(
                        navController = navController,
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
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
  }

  override fun onPause() {
    super.onPause()
    com.example.state.KavyaStateManager.isActivityVisible = false
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pendingDeepLinkRoute.value = intent.getStringExtra(EXTRA_OPEN_ROUTE)
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    com.example.ai.offline.Gemma4E4BEngine.getInstance(this).onTrimMemory(level)
    com.example.ai.offline.OnDeviceVisionOrchestrator.getInstance(this).onTrimMemory(level)
    com.example.ai.tts.VoiceEngineManager.getInstance(this).onTrimMemory(level)
    com.example.vision.VisualizationOrchestrator.onTrimMemory(level)
  }
}

