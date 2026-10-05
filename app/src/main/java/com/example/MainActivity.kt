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
  }

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
    enableEdgeToEdge()

    requestAppPermissions()

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
  }

  override fun onPause() {
    super.onPause()
    com.example.state.KavyaStateManager.isActivityVisible = false
  }
}

