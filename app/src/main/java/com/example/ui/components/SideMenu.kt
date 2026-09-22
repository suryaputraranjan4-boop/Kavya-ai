package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun KavyaSideMenu(
    navController: NavController,
    drawerState: DrawerState,
    scope: CoroutineScope
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceGlass.copy(alpha = 0.95f))
            .padding(top = 48.dp, bottom = 24.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "Kavya",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Light,
                color = TextPrimary
            ),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
        
        HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(modifier = Modifier.height(16.dp))

        DrawerItem(Icons.Default.Home, "Home") { 
            navController.navigate("home") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.AutoMirrored.Filled.Chat, "Chats") { 
            navController.navigate("chat") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.Timeline, "Activity") { 
            navController.navigate("activity") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.Favorite, "Favourites") { 
            navController.navigate("favourites") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.TaskAlt, "Routines") { 
            navController.navigate("routines") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.Memory, "Memory") { 
            navController.navigate("memory") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.MusicNote, "Music") { 
            navController.navigate("music") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.AutoGraph, "Kavya Evolution") { 
            navController.navigate("evolution") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Settings & APIs",
            style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        DrawerItem(Icons.Default.VpnKey, "API Dashboard") { 
            navController.navigate("ai_api_hub") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.Settings, "Settings") { 
            navController.navigate("settings") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.RecordVoiceOver, "Voice") { 
            navController.navigate("voice_settings") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.Language, "Language") { 
            navController.navigate("language") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.Palette, "Appearance") { 
            navController.navigate("appearance") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.Security, "Permissions") { 
            navController.navigate("permissions") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.PrivacyTip, "Privacy") { 
            navController.navigate("privacy") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.Build, "Advanced Settings") { 
            navController.navigate("advanced") { launchSingleTop = true }
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.AutoMirrored.Filled.Help, "Help & Support") { 
            // no-op for now, could add help screen later or map to advanced
            scope.launch { drawerState.close() } 
        }
        DrawerItem(Icons.Default.BugReport, "Debug Dashboard") {
            navController.navigate("debug_dashboard") { launchSingleTop = true }
            scope.launch { drawerState.close() }
        }
    }
}

@Composable
fun DrawerItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = PrimaryLight, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary))
    }
}
