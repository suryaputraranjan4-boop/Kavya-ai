package com.example

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.example.services.KavyaVoiceService
import com.example.ui.components.KavyaSideMenu
import com.example.ui.navigation.AppNavGraph
import com.example.ui.navigation.LocalDrawerState
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import com.example.utils.PermissionsManager
import com.example.viewmodel.KavyaViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: KavyaViewModel by viewModels()

  companion object {
    private const val TAG = "MainActivity"
  }

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
    Log.d(TAG, "Permissions result: record_audio=$recordGranted")
    if (recordGranted) {
      startVoiceServiceIfPermitted()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    requestVoicePermissions()

    setContent {
      MyApplicationTheme {
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        
        CompositionLocalProvider(LocalDrawerState provides drawerState) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = SurfaceGlass,
                        drawerContentColor = TextPrimary,
                        modifier = Modifier.width(300.dp)
                    ) {
                        KavyaSideMenu(
                            navController = navController,
                            drawerState = drawerState,
                            scope = scope
                        )
                    }
                }
            ) {
                Scaffold(
                    containerColor = Background,
                    modifier = Modifier.fillMaxSize()
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

  private fun requestVoicePermissions() {
    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val missing = permissions.filter {
      androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    if (missing.isNotEmpty()) {
      permissionLauncher.launch(missing.toTypedArray())
    } else {
      startVoiceServiceIfPermitted()
    }
  }

  private fun startVoiceServiceIfPermitted() {
    if (PermissionsManager.hasRecordAudioPermission(this) && AppPreferences.isBackgroundVoiceEnabled(this)) {
      try {
        val serviceIntent = Intent(this, KavyaVoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        } else {
          startService(serviceIntent)
        }
        Log.i(TAG, "KavyaVoiceService started successfully from MainActivity.")
      } catch (e: Exception) {
        Log.e(TAG, "Error starting KavyaVoiceService: ${e.message}")
      }
    }
  }

  override fun onResume() {
    super.onResume()
    com.example.state.KavyaStateManager.isActivityVisible = true
    viewModel.microphoneEngine.refreshHardwareDiagnostics()
    startVoiceServiceIfPermitted()
  }

  override fun onPause() {
    super.onPause()
    com.example.state.KavyaStateManager.isActivityVisible = false
  }

  override fun onDestroy() {
    super.onDestroy()
    // Do NOT stop KavyaVoiceService if background voice is enabled.
    // Kavya is designed to remain listening across other apps until explicit "Sleep Kavya" or mic turned off.
    if (!AppPreferences.isBackgroundVoiceEnabled(this) && isFinishing) {
      stopService(Intent(this, KavyaVoiceService::class.java))
    }
  }
}

