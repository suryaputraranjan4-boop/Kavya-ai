package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orchestrator.OrchestrationLifecycleState
import com.example.orchestrator.OrchestrationSnapshot
import com.example.orchestrator.TaskOrchestrationStateMachine
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.Error
import com.example.ui.theme.PrimaryLight
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SurfaceGlass
import com.example.viewmodel.PendingConfirmation

@Composable
fun CeoOrchestrationHud(
    snapshot: OrchestrationSnapshot,
    fallbackStatus: String? = null,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onEmergencyStop: () -> Unit,
    onDismissTerminal: () -> Unit = { TaskOrchestrationStateMachine.resetToIdle() },
    modifier: Modifier = Modifier
) {
    val shouldShow = snapshot.isRunning ||
        snapshot.isPaused ||
        snapshot.state == OrchestrationLifecycleState.TASK_FAILED ||
        snapshot.state == OrchestrationLifecycleState.TASK_CANCELLED ||
        !fallbackStatus.isNullOrBlank()

    AnimatedVisibility(visible = shouldShow, modifier = modifier) {
        val stateLabel = when (snapshot.state) {
            OrchestrationLifecycleState.USER_COMMAND_RECEIVED -> "Command Received"
            OrchestrationLifecycleState.API_AUTHENTICATION -> "AI Auth Preflight"
            OrchestrationLifecycleState.AI_REASONING -> "AI Reasoning"
            OrchestrationLifecycleState.PLAN_GENERATION,
            OrchestrationLifecycleState.PLAN_VALIDATION -> "AI Planning"
            OrchestrationLifecycleState.WAITING_FOR_PERMISSION -> "Permission Needed"
            OrchestrationLifecycleState.WAITING_FOR_CONFIRMATION -> "Awaiting Confirmation"
            OrchestrationLifecycleState.ACTION_AUTHORIZATION -> "Authorizing Permit"
            OrchestrationLifecycleState.ACTION_EXECUTION -> "Executing Action"
            OrchestrationLifecycleState.SCREEN_OBSERVATION -> "Screen Observation"
            OrchestrationLifecycleState.AI_VERIFICATION -> "AI Verifying"
            OrchestrationLifecycleState.REPLANNING -> "AI Replanning"
            OrchestrationLifecycleState.TASK_PAUSED -> if (snapshot.suspendedDueToNetwork) "Paused • Offline" else "Paused"
            OrchestrationLifecycleState.TASK_COMPLETED -> "Verified Complete"
            OrchestrationLifecycleState.TASK_FAILED -> "Halted"
            OrchestrationLifecycleState.TASK_CANCELLED -> "Stopped"
            OrchestrationLifecycleState.IDLE -> "Processing"
        }

        val accent = when (snapshot.state) {
            OrchestrationLifecycleState.TASK_FAILED,
            OrchestrationLifecycleState.TASK_CANCELLED -> Error
            OrchestrationLifecycleState.TASK_PAUSED,
            OrchestrationLifecycleState.WAITING_FOR_CONFIRMATION,
            OrchestrationLifecycleState.WAITING_FOR_PERMISSION -> Color(0xFFFFB020)
            OrchestrationLifecycleState.TASK_COMPLETED -> SuccessGreen
            else -> AccentCyan
        }

        Surface(
            color = SurfaceGlass.copy(alpha = 0.96f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ceo_orchestration_hud")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (snapshot.isRunning && !snapshot.isPaused) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = accent
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stateLabel,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("hud_state_label")
                                )
                                if (snapshot.totalSteps > 0) {
                                    Text(
                                        text = "Step ${snapshot.currentStep}/${snapshot.totalSteps}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            color = accent
                                        ),
                                        modifier = Modifier.testTag("hud_step_progress")
                                    )
                                }
                            }
                            if (snapshot.providerId.isNotBlank()) {
                                Text(
                                    text = "${snapshot.providerId} • ${snapshot.modelId.ifBlank { "default" }}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        color = PrimaryLight.copy(alpha = 0.9f)
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("hud_provider_badge")
                                )
                            }
                        }
                    }

                    if (!snapshot.isRunning && !snapshot.isPaused) {
                        IconButton(
                            onClick = onDismissTerminal,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("hud_dismiss_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss status",
                                tint = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                val detailText = (fallbackStatus?.takeIf { it.isNotBlank() }
                    ?: snapshot.lastError?.takeIf { it.isNotBlank() }
                    ?: snapshot.stepDescription).trim()
                if (detailText.isNotBlank()) {
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.88f)
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("hud_detail_text")
                    )
                }

                if (snapshot.isRunning || snapshot.isPaused) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { if (snapshot.isPaused) onResume() else onPause() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("hud_pause_resume_button")
                        ) {
                            Icon(
                                imageVector = if (snapshot.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (snapshot.isPaused) "Resume automation" else "Pause automation",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (snapshot.isPaused) "Resume" else "Pause",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("hud_cancel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel task",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel", fontSize = 12.sp, color = Color.White)
                        }

                        Button(
                            onClick = onEmergencyStop,
                            colors = ButtonDefaults.buttonColors(containerColor = Error),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("hud_emergency_stop_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Emergency stop automation",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("STOP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CeoConfirmationDialog(
    pending: PendingConfirmation?,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = onDeny,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Action confirmation",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = pending.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.testTag("confirm_action_title")
            )
        },
        text = {
            Text(
                text = pending.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("confirm_action_description")
            )
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                    .testTag("confirm_action_allow_button")
            ) {
                Text("Allow")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier
                    .defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                    .testTag("confirm_action_deny_button")
            ) {
                Text("Deny")
            }
        }
    )
}
