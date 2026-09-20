package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.ui.theme.*
import com.example.utils.AppPreferences
import com.example.viewmodel.KavyaViewModel
import com.example.visual.VisualAutomationMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualActionControlScreen(
    navController: NavController,
    viewModel: KavyaViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engineStatus by viewModel.visualEngineStatus.collectAsState()
    val globalState by com.example.state.KavyaStateManager.state.collectAsState()

    var isEnabled by remember { mutableStateOf(AppPreferences.isVisualAutomationEnabled(context)) }
    var currentMode by remember { mutableStateOf(AppPreferences.getVisualAutomationMode(context)) }
    var confidenceThreshold by remember { mutableStateOf(AppPreferences.getConfidenceThreshold(context)) }
    var testGoalText by remember { mutableStateOf("") }
    var inspectionResult by remember { mutableStateOf<String?>(null) }
    var isInspecting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Visual Action Engine", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Human-Like Screen Automation", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Emergency Stop Action in AppBar
                    Button(
                        onClick = {
                            viewModel.emergencyStop("User tapped Emergency Stop")
                            Toast.makeText(context, "Automation Stopped", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("STOP / रुको", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceGlass)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(if (engineStatus.isActive) AccentCyan else TextSecondary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (engineStatus.isActive) "ACTIVE AUTOMATION" else "ENGINE READY",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = if (engineStatus.isActive) AccentCyan else TextSecondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Text(
                                text = "Confidence: ${(engineStatus.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "State: ${globalState.taskState.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryLight
                        )
                        Text(
                            text = engineStatus.detailMessage.ifBlank { "Awaiting voice command (e.g. 'Free Fire खोलो और BR Start करो')" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Automation Mode Configuration
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceGlass)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Automation Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enable Visual Screen Control", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = {
                                    isEnabled = it
                                    AppPreferences.setVisualAutomationEnabled(context, it)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Modes
                        listOf(
                            VisualAutomationMode.AUTOMATIC_SAFE.name to "Automatic Safe (High confidence actions execute automatically)",
                            VisualAutomationMode.ASK_BEFORE_ACTION.name to "Ask Before Action (Prompts before clicking)",
                            VisualAutomationMode.MANUAL_CONFIRMATION.name to "Manual Confirmation (Requires approval for all taps)"
                        ).forEach { (mode, desc) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected = currentMode == mode,
                                    onClick = {
                                        currentMode = mode
                                        AppPreferences.setVisualAutomationMode(context, mode)
                                    }
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(mode, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }

            // Confidence Threshold
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceGlass)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Confidence Threshold", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("${(confidenceThreshold * 100).toInt()}%", fontWeight = FontWeight.Bold, color = AccentCyan)
                        }
                        Text(
                            "Actions with confidence below this threshold will not execute automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Slider(
                            value = confidenceThreshold,
                            onValueChange = {
                                confidenceThreshold = it
                                AppPreferences.setConfidenceThreshold(context, it)
                            },
                            valueRange = 0.60f..0.95f,
                            steps = 6
                        )
                    }
                }
            }

            // Voice Stop & Safety Info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceGlass)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = "Safety", tint = SuccessGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Safety & Interruptions", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "• Voice Stop Keywords: Say 'Stop', 'रुको', 'Cancel', 'बस', or 'रुक जाओ' at any moment to halt automation immediately.\n" +
                            "• Maximum Retries: Max 3 attempts before safely stopping.\n" +
                            "• Loop & Permission Guards: Never clicks permission prompts, lock screens, or payments without explicit confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            // Test & Perception Inspector
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceGlass)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Live Perception Test", fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = testGoalText,
                            onValueChange = { testGoalText = it },
                            label = { Text("Command (e.g. Free Fire खोलो और BR Start करो)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    if (testGoalText.isNotBlank()) {
                                        viewModel.sendMessage(testGoalText)
                                        Toast.makeText(context, "Executing goal...", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Text("Run Goal")
                            }

                            OutlinedButton(
                                onClick = {
                                    isInspecting = true
                                    scope.launch {
                                        val snapshot = viewModel.visualActionEngine.screenObserver.observeScreen(captureVisual = false)
                                        inspectionResult = "Active App: ${snapshot.foregroundPackage}\nDetected Elements:\n${snapshot.visibleTextSummary.take(400)}"
                                        isInspecting = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isInspecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Inspect Screen")
                                }
                            }
                        }

                        if (inspectionResult != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = Background,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = inspectionResult ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentCyan,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
