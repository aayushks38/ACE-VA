package com.ace.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ace.app.R
import com.ace.app.agent.AgentTask
import com.ace.app.agent.ApprovalDetails
import com.ace.app.agent.TaskCategory
import com.ace.app.agent.TaskStatus
import com.ace.app.agent.TaskViewModel
import com.ace.app.agent.isTerminalForAce
import com.ace.app.ui.components.AceBackground
import com.ace.app.ui.components.AstraVoiceOrb
import com.ace.app.voice.VoiceManager
import com.ace.app.voice.VoiceProvider
import com.ace.app.voice.VoiceState
import com.ace.app.voice.AceProgressSpeaker

private val Ink = Color(0xFFF8F7FF)
private val Muted = Color(0xFFB6B0C9)
private val Purple = Color(0xFF9D6BFF)
private val Surface = Color(0xFF1C1530)
private val WarningAmber = Color(0xFFFFB800)
private val SuccessGreen = Color(0xFF4EEB99)
private val DangerRed = Color(0xFFFF4D4D)

@Composable
fun HomeScreen(
    onProfileClick: () -> Unit,
    viewModel: TaskViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val voiceManager = remember {
        VoiceManager(
            context = context.applicationContext,
            onSpeechRecognized = { text -> viewModel.handleSpokenInput(text) },
            onStateChanged = { voiceState -> viewModel.setVoiceState(voiceState) },
            onError = { errMsg ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
                }
            },
            onProviderChanged = { provider -> viewModel.setVoiceProvider(provider) }
        )
    }

    LaunchedEffect(voiceManager) {
        viewModel.initializeVoiceManager(voiceManager)
    }

    DisposableEffect(voiceManager) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                android.util.Log.i("ACE_BROADCAST_RX", "ACE_BROADCAST_RX: onReceive called action=${intent?.action}")
                val goal = intent?.getStringExtra("goal")
                android.util.Log.i("ACE_BROADCAST_RX", "ACE_BROADCAST_RX: goal='$goal' isBlank=${goal.isNullOrBlank()}")
                if (!goal.isNullOrBlank()) {
                    com.ace.app.utils.AceLatencyTracker.startTask()
                    android.util.Log.i("ACE_BROADCAST_RX", "ACE_BROADCAST_RX: calling handleSpokenInput with goal='$goal'")
                    viewModel.handleSpokenInput(goal)
                } else {
                    android.util.Log.w("ACE_BROADCAST_RX", "ACE_BROADCAST_RX: goal is null or blank, not calling handleSpokenInput")
                }
            }
        }
        val filter = android.content.IntentFilter("com.ace.app.SUBMIT_GOAL")
        android.util.Log.i("ACE_BROADCAST_RX", "ACE_BROADCAST_RX: registering receiver for com.ace.app.SUBMIT_GOAL")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        android.util.Log.i("ACE_BROADCAST_RX", "ACE_BROADCAST_RX: receiver registered successfully")
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (audioGranted) {
            voiceManager.startListening()
        } else {
            Toast.makeText(context, "Microphone permission required for ACE voice agent.", Toast.LENGTH_LONG).show()
        }
    }

    val taskPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val anyGranted = permissions.values.any { it }
        if (anyGranted) {
            val task = state.activeTask
            if (task != null && task.status == TaskStatus.WAITING_FOR_USER) {
                viewModel.resumeTask(task)
            }
        }
    }

    LaunchedEffect(state.activeTask?.status, state.activeTask?.summary) {
        val task = state.activeTask
        if (task != null && task.status == TaskStatus.WAITING_FOR_USER) {
            val summary = task.summary.lowercase()
            val neededPermissions = mutableListOf<String>()
            if (summary.contains("read_contacts") || summary.contains("contact")) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    neededPermissions.add(Manifest.permission.READ_CONTACTS)
                }
            }
            if (summary.contains("call_phone") || summary.contains("phone call") || summary.contains("call")) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    neededPermissions.add(Manifest.permission.CALL_PHONE)
                }
            }
            if (summary.contains("read_phone_state")) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    neededPermissions.add(Manifest.permission.READ_PHONE_STATE)
                }
            }
            if (neededPermissions.isNotEmpty()) {
                taskPermissionLauncher.launch(neededPermissions.toTypedArray())
            }
        }
    }

    fun triggerVoiceInput() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceManager.startListening()
        } else {
            permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    AceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "ACE",
                        color = Ink,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Autonomous Voice Agent",
                        color = Muted,
                        fontSize = 13.sp
                    )
                }

                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "Settings",
                    tint = Ink,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onProfileClick)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Status badges row ─────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val brainColor = when {
                    state.isBrainReady || state.brainStatusText.contains("Ready", ignoreCase = true) -> SuccessGreen
                    state.brainStatusText.contains("Preparing", ignoreCase = true) -> WarningAmber
                    state.brainStatusText.contains("unavailable", ignoreCase = true) -> DangerRed
                    else -> Muted
                }
                Text(
                    state.brainStatusText,
                    color = brainColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(brainColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )

                val providerText = when (state.voiceProvider) {
                    VoiceProvider.ONLINE_RIME -> "Voice: Rime"
                    VoiceProvider.OFFLINE_LOCAL -> "Voice: Offline"
                    VoiceProvider.DEVICE_FALLBACK -> "Voice: Device"
                    VoiceProvider.UNAVAILABLE -> "Voice: None"
                }
                Text(
                    providerText,
                    color = if (state.voiceProvider == VoiceProvider.ONLINE_RIME) Purple else Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Purple.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // ── Central Voice Orb Area ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AstraVoiceOrb(
                        voiceState = state.voiceState,
                        onClick = { triggerVoiceInput() }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Dynamic status label
                    val statusLabel = when (state.voiceState) {
                        VoiceState.IDLE -> "Tap to speak"
                        VoiceState.LISTENING -> "Listening..."
                        VoiceState.THINKING -> "Thinking..."
                        VoiceState.EXECUTING -> if (state.currentActionLabel.isNotBlank()) state.currentActionLabel else "Working..."
                        VoiceState.SPEAKING -> "ACE speaks..."
                    }
                    val statusColor = when (state.voiceState) {
                        VoiceState.IDLE -> Muted
                        VoiceState.LISTENING -> Ink
                        VoiceState.THINKING -> Purple
                        VoiceState.EXECUTING -> WarningAmber
                        VoiceState.SPEAKING -> SuccessGreen
                    }

                    AnimatedContent(
                        targetState = statusLabel,
                        transitionSpec = {
                            (fadeIn() + slideInVertically { it / 4 }) togetherWith
                                (fadeOut() + slideOutVertically { -it / 4 })
                        },
                        label = "StatusLabel"
                    ) { label ->
                        Text(
                            text = label,
                            color = statusColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Secondary action label — shows current step (e.g. "Opening WhatsApp...")
                    AnimatedVisibility(
                        visible = state.currentActionLabel.isNotBlank(),
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { -it / 2 }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.currentActionLabel,
                                color = Muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // ── Task / Response Area ──────────────────────────────────────────
            AnimatedVisibility(
                visible = state.activeTask != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val task = state.activeTask
                if (task != null) {
                    TaskWorkspaceCard(
                        task = task,
                        lastHeard = state.lastHeard,
                        onApprove = { viewModel.approve() },
                        onCancel = { viewModel.cancel() },
                        onNewTask = { viewModel.clearTask() }
                    )
                }
            }

            // ── Announcement / Response banner (only when non-empty & no active task) ──
            AnimatedVisibility(
                visible = state.announcement.isNotBlank() && state.activeTask == null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = state.announcement,
                        color = Ink,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Surface.copy(alpha = 0.8f))
                            .border(1.dp, Purple.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TaskWorkspaceCard(
    task: AgentTask,
    lastHeard: String?,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
    onNewTask: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface.copy(alpha = 0.6f))
            .border(1.dp, Purple.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadge(task.category)
            Spacer(modifier = Modifier.weight(1f))
            StatusBadge(task.status)
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(task.goal, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        if (!lastHeard.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Heard: \"$lastHeard\"", color = Muted, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Consequential Approval Card
        if (task.status == TaskStatus.WAITING_FOR_APPROVAL && task.approvalDetails != null) {
            ApprovalCard(
                details = task.approvalDetails,
                onApprove = onApprove,
                onCancel = onCancel
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Execution Steps Checklist
        Text("Execution Graph", color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        task.steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        step.isComplete -> Text("✓", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        step.isRunning && !task.status.isTerminalForAce() -> CircularProgressIndicator(color = Purple, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        task.status == TaskStatus.AWAITING_USER_ACTION -> Text("⏳", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        else -> Text("${index + 1}", color = Muted, fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.label,
                        color = if (step.isComplete || task.status.isTerminalForAce()) Ink else Muted,
                        fontSize = 14.sp,
                        fontWeight = if (step.isRunning) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (!step.output.isNullOrBlank() && !step.output.startsWith("PARTIAL:")) {
                        Text(step.output, color = SuccessGreen, fontSize = 11.sp)
                    }
                }
                if (step.isParallel) {
                    Text(
                        "Parallel",
                        color = Purple,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Purple.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Verification & Terminal Summary Banner
        val isTerminal = task.status.isTerminalForAce()
        if (isTerminal || task.verificationResult != null) {
            Spacer(modifier = Modifier.height(14.dp))
            val (badgeText, borderColor, bgColor) = when (task.status) {
                TaskStatus.COMPLETED -> Triple("VERIFIED SUCCESS ✓", SuccessGreen, SuccessGreen.copy(alpha = 0.15f))
                TaskStatus.HANDOFF_COMPLETED -> Triple("HANDOFF COMPLETE ✓", SuccessGreen, SuccessGreen.copy(alpha = 0.15f))
                TaskStatus.AWAITING_USER_ACTION -> Triple("YOUR ACTION NEEDED ⏳", WarningAmber, WarningAmber.copy(alpha = 0.15f))
                TaskStatus.CANCELLED -> Triple("CANCELLED", Muted, Muted.copy(alpha = 0.15f))
                else -> Triple("VERIFICATION FAILED ✗", DangerRed, DangerRed.copy(alpha = 0.15f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(badgeText, color = borderColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.verificationResult ?: task.summary,
                    color = Ink,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    "Start New Task",
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Purple)
                        .clickable(onClick = onNewTask)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    details: ApprovalDetails,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WarningAmber.copy(alpha = 0.12f))
            .border(1.dp, WarningAmber, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(details.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(details.description, color = Ink.copy(alpha = 0.9f), fontSize = 13.sp)

        if (details.monetaryCost != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Financial Commitment: ${details.monetaryCost}", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        if (details.warningMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(details.warningMessage, color = Muted, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Approve (or say \"Yes\")",
                color = Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Purple)
                    .clickable(onClick = onApprove)
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Cancel",
                color = Ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(0.6f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface)
                    .border(1.dp, Muted.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable(onClick = onCancel)
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CategoryBadge(category: TaskCategory) {
    val (label, color) = when (category) {
        TaskCategory.COMMUNICATION -> "Communication" to Color(0xFFFFB800)
        TaskCategory.DOCUMENT -> "Document / File" to Color(0xFFFF007A)
        TaskCategory.RESEARCH -> "Web Research" to Color(0xFF9D6BFF)
        TaskCategory.SYSTEM -> "System Action" to Color(0xFF4EEB99)
        TaskCategory.TRAVEL -> "Travel / Booking" to Color(0xFF00F0FF)
        TaskCategory.GENERAL -> "Dynamic Agent Goal" to Purple
    }

    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun StatusBadge(status: TaskStatus) {
    val (label, color) = when (status) {
        TaskStatus.PLANNING -> "Planning" to Muted
        TaskStatus.RUNNING -> "Executing" to Purple
        TaskStatus.WAITING_FOR_APPROVAL -> "Approval Needed" to WarningAmber
        TaskStatus.VERIFYING -> "Verifying" to Color(0xFF00F0FF)
        TaskStatus.COMPLETED -> "Completed" to SuccessGreen
        TaskStatus.HANDOFF_COMPLETED -> "Handoff Complete" to SuccessGreen
        TaskStatus.AWAITING_USER_ACTION -> "Action Needed" to WarningAmber
        TaskStatus.FAILED -> "Failed" to DangerRed
        TaskStatus.CANCELLED -> "Cancelled" to Muted
        else -> status.name to Muted
    }

    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
