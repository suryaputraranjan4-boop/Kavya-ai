package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.*
import com.example.viewmodel.KavyaViewModel

@Composable
fun AppNavGraph(
    navController: NavHostController,
    viewModel: KavyaViewModel,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val startDestination = androidx.compose.runtime.remember {
        if (!com.example.utils.AppPreferences.isApiSetupCompleted(context) && !com.example.utils.AppPreferences.hasConfiguredApiKey(context)) {
            "setup_kavya"
        } else if (com.example.utils.AppPreferences.isOnboardingCompleted(context)) {
            "home"
        } else {
            "onboarding"
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable("setup_kavya") { SetupKavyaScreen(navController) }
        composable("onboarding") { OnboardingScreen(navController, viewModel) }
        composable("home") { HomeScreen(navController, viewModel) }
        composable("chat") { ChatScreen(navController, viewModel) }
        composable("activity") { ActivityScreen(navController, viewModel) }
        composable("favourites") { FavouritesScreen(navController, viewModel) }
        composable("settings") { SettingsScreen(navController) }
        
        // Sub-screens
        composable("routines") { RoutinesScreen(navController, viewModel) }
        composable("music") { MusicScreen(navController, viewModel) }
        composable("memory") { MemoryScreen(navController, viewModel) }
        
        // Settings Sub-screens
        composable("permissions") { PermissionsScreen(navController) }
        composable("appearance") { AppearanceScreen(navController) }
        composable("voice_settings") { VoiceSettingsScreen(navController, viewModel) }
        composable("screenshare") { ScreenShareScreen(navController, viewModel) }
        composable("language") { LanguageScreen(navController) }
        composable("privacy") { AutomationDebugPanelScreen(navController, viewModel) }
        composable("automation_debug") { AutomationDebugPanelScreen(navController, viewModel) }
        composable("advanced") { AdvancedScreen(navController) }
        composable("ai_provider_settings") { AiApiHubScreen(navController) }
        composable("ai_api_hub") { AiApiHubScreen(navController) }
        composable("proactive_settings") { ProactiveSettingsScreen(navController, viewModel) }
        composable("debug_dashboard") { DebugDashboardScreen() }
    }
}
