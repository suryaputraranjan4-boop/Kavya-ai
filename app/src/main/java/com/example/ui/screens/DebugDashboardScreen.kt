package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.QuotaManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugDashboardScreen(onBack: (() -> Unit)? = null) {
    var tick by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    
    val quota = QuotaManager.instance
    
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = Color(0xFF4FC3F7), fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Internal Debug Dashboard", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        
        val globalState by com.example.state.KavyaStateManager.state.collectAsState()
        val context = androidx.compose.ui.platform.LocalContext.current
        val gemmaStatus by com.example.ai.offline.GemmaModelManager.status.collectAsState()
        val gemmaDiagnostics = com.example.ai.offline.Gemma4E4BEngine.getInstance().diagnostics
        val gemmaFile = com.example.ai.offline.GemmaModelManager.getModelFile(context)
        val gemmaFormat = com.example.ai.offline.GemmaModelManager.cachedInfo?.format ?: gemmaDiagnostics.detectedFormat
        val selectedProvider = com.example.utils.AppPreferences.getAiProvider(context)
        val isOfflineMode = com.example.utils.AppPreferences.isOfflineFallbackEnabled(context) || selectedProvider == "GEMMA_OFFLINE"
        val availRamMb = com.example.ai.offline.GemmaDiagnostics.getAvailableSystemMemoryMB(context)

        DashboardCard("Offline LLM & Runtime Diagnostics (Gemma / LiteRT-LM / GGUF)") {
            StatRow("Selected Provider", if (isOfflineMode) "GEMMA_OFFLINE (Strict Local)" else selectedProvider)
            StatRow("Selected Model", gemmaFile?.name ?: "None (No model imported)")
            StatRow("Detected Format", gemmaFormat.name)
            StatRow("Runtime", gemmaDiagnostics.runtimeSelected.ifBlank { "Uninitialized" })
            StatRow("Model File Status", if (gemmaFile != null) "${gemmaStatus.name} (${gemmaFile.length() / (1024 * 1024)} MB)" else gemmaStatus.name)
            StatRow("Health-Check Status", gemmaDiagnostics.healthCheckState)
            StatRow("Actual Inference Status", if (gemmaDiagnostics.inferenceSucceeded) "VERIFIED (Real Tokens)" else "NOT YET VERIFIED")
            StatRow("Network Usage Status", if (isOfflineMode && !gemmaDiagnostics.networkUsed) "0 Calls (100% Offline)" else if (gemmaDiagnostics.networkUsed) "VIOLATION DETECTED" else "Cloud Enabled")
            StatRow("Memory Usage / Free RAM", "$availRamMb MB Free")
            StatRow("Last Error", gemmaDiagnostics.lastError ?: com.example.ai.offline.GemmaModelManager.lastError ?: "None")
        }
        
        DashboardCard("Global State Manager (KAVYA_STATE_MANAGER)") {
            StatRow("Conversation", globalState.conversationState)
            StatRow("Voice", globalState.voiceState.name)
            StatRow("CGI", globalState.cgiState)
            StatRow("Active Tool", globalState.activeTool ?: "None")
            StatRow("App Context", globalState.currentApp ?: "None")
        }
        
        DashboardCard("Gemini API Requests") {
            StatRow("Requests Today", "${quota.currentRequestsToday}")
            StatRow("Requests This Hour", "${quota.currentRequestsInHour}")
            StatRow("Requests This Minute", "${quota.currentRequestsInMinute}")
            StatRow("Active Foreground Requests", "${quota.currentActiveRequests}")
            StatRow("Total Voice Generation Req", "${quota.totalVoiceRequests}")
            StatRow("Proactive Requests", "${quota.proactiveRequestsCount}")
        }
        
        DashboardCard("Resource & Abuse Protection") {
            StatRow("Duplicate Requests Prevented", "${quota.duplicateRequestsPrevented}")
            StatRow("Rate Limit (429) Errors", "${quota.rateLimitErrorsCount}")
        }
        
        DashboardCard("System Health & Errors") {
            StatRow("Last Error Type", quota.lastErrorType)
            val timeStr = if (quota.lastErrorTime > 0) timeFormatter.format(Date(quota.lastErrorTime)) else "N/A"
            StatRow("Last Error Time", timeStr)
            StatRow("Last Error Source", quota.lastErrorRequestType.ifBlank { "None" })
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun DashboardCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, color = Color(0xFF4FC3F7), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.LightGray, fontSize = 14.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
