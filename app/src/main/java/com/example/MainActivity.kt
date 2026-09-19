package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.example.ui.navigation.AppNavGraph
import com.example.ui.navigation.LocalDrawerState
import com.example.ui.components.KavyaSideMenu
import com.example.ui.theme.*
import com.example.viewmodel.KavyaViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: KavyaViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
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
}
