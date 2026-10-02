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
fun DebugDashboardScreen() {
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
        Text("Internal Debug Dashboard", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        
        val globalState by com.example.state.KavyaStateManager.state.collectAsState()
        val context = androidx.compose.ui.platform.LocalContext.current
        val localModelInfo by com.example.ai.local.LocalModelManager.getInstance(context).modelInfo.collectAsState()
        val isOfflineMode = com.example.utils.AppPreferences.isOfflineModeEnabled(context)

        DashboardCard("Local AI Model Status (Qwen3-4B-Q4_K_M.gguf)") {
            StatRow("Offline Mode", if (isOfflineMode) "ENABLED (Qwen3-4B Active)" else "DISABLED (Gemini Active)")
            StatRow("Model Status", localModelInfo.statusState.name)
            StatRow("Model File", localModelInfo.fileName)
            StatRow("Quantization", localModelInfo.quantization)
            StatRow("File Size", localModelInfo.fileSizeFormatted)
            StatRow("GGUF Signature Valid", if (localModelInfo.isGgufHeaderValid) "YES (0x46554747)" else "NO / NOT FOUND")
            StatRow("Storage Location", localModelInfo.filePath.ifBlank { "Not Found (/sdcard/Download/)" })
            if (localModelInfo.errorMessage.isNotBlank()) {
                StatRow("Status Message", localModelInfo.errorMessage)
            }
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
            Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
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
