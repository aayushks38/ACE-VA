package com.ace.app.agent

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ace.app.brain.BrainResult
import com.ace.app.brain.ModelHandle
import com.ace.app.brain.model.ModelRepository
import com.ace.app.brain.BrainState
import com.ace.app.brain.model.ModelDiscoveryState
import com.ace.app.voice.VoiceManager
import com.ace.app.voice.VoiceProvider
import com.ace.app.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

import com.ace.app.brain.BrainProvider
import com.ace.app.brain.LocalBrain
import com.ace.app.voice.AceProgressSpeaker

data class TaskUiState(
    val activeTask: AgentTask? = null,
    val lastHeard: String? = null,
    val announcement: String = "",
    val voiceState: VoiceState = VoiceState.IDLE,
    val voiceProvider: VoiceProvider = VoiceProvider.DEVICE_FALLBACK,
    val attachmentName: String? = null,
    val attachmentUri: String? = null,
    val hasGreetedOnLaunch: Boolean = false,
    val isBrainReady: Boolean = false,
    val brainStatusText: String = "Brain: Brain Unavailable",
    val currentActionLabel: String = ""
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val brain: LocalBrain = BrainProvider.getBrain()
    private val executor = AgentExecutor(application.applicationContext)
    private val commandRouter = AceCommandRouter()

    private val _uiState = MutableStateFlow(TaskUiState())
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()

    private val currentGeneration = AtomicLong(0)
    private var executionJob: Job? = null

    var voiceManager: VoiceManager? = null
        private set

    init {
        initializeBrain()
        observeBrainRuntimeState()
        registerGoalReceiver()
    }

    private val pendingGoal = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private fun observeBrainRuntimeState() {
        viewModelScope.launch {
            com.ace.app.brain.GemmaBrainManager.runtimeState.collect { runtimeState ->
                when (runtimeState) {
                    com.ace.app.brain.BrainRuntimeState.READY -> {
                        _uiState.value = _uiState.value.copy(
                            isBrainReady = true,
                            brainStatusText = "🟢 Local AI ready",
                            announcement = if (_uiState.value.announcement.contains("Preparing")) "I'm ACE, your autonomous agent. What would you like me to do?" else _uiState.value.announcement
                        )
                        val queued = pendingGoal.getAndSet(null)
                        if (!queued.isNullOrBlank()) {
                            android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: Gemma is now READY! Auto-executing queued goal='$queued'")
                            submitVoiceGoal(queued)
                        }
                    }
                    com.ace.app.brain.BrainRuntimeState.LOADING -> {
                        _uiState.value = _uiState.value.copy(
                            isBrainReady = false,
                            brainStatusText = "🟡 Preparing local AI..."
                        )
                    }
                    com.ace.app.brain.BrainRuntimeState.ERROR -> {
                        _uiState.value = _uiState.value.copy(
                            isBrainReady = false,
                            brainStatusText = "🔴 Local AI unavailable"
                        )
                    }
                    com.ace.app.brain.BrainRuntimeState.NOT_LOADED -> {
                        val installed = com.ace.app.brain.GemmaBrainManager.isModelInstalled(getApplication())
                        _uiState.value = _uiState.value.copy(
                            isBrainReady = false,
                            brainStatusText = if (installed) "🟡 Preparing local AI..." else "🔴 Local AI unavailable"
                        )
                    }
                }
            }
        }
    }


    private fun registerGoalReceiver() {
        // Receiver is registered in HomeScreen via DisposableEffect
        // This method is kept for compatibility but does nothing
        android.util.Log.i("ACE_BROADCAST_RX", "ACE_BROADCAST_RX: receiver registration delegated to HomeScreen")
    }

    private fun initializeBrain() {
        val context = getApplication<Application>().applicationContext
        com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoadedAsync(context)
    }

    private suspend fun ensureBrainLoaded(context: Context): Boolean {
        return com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoaded(context)
    }

    fun initializeVoiceManager(manager: VoiceManager) {
        this.voiceManager = manager
        if (!_uiState.value.hasGreetedOnLaunch) {
            _uiState.value = _uiState.value.copy(
                hasGreetedOnLaunch = true,
                voiceProvider = manager.currentProvider
            )
            // Attach AceProgressSpeaker to this voice manager
            AceProgressSpeaker.attach(manager, currentGeneration.get())
            // No startup greeting — ACE is silent until user taps
        }
    }

    fun setVoiceState(state: VoiceState) {
        _uiState.value = _uiState.value.copy(
            voiceState = state,
            voiceProvider = voiceManager?.currentProvider ?: VoiceProvider.DEVICE_FALLBACK
        )
    }

    fun setVoiceProvider(provider: VoiceProvider) {
        _uiState.value = _uiState.value.copy(voiceProvider = provider)
    }

    fun setAttachment(name: String?, uri: String? = null) {
        _uiState.value = _uiState.value.copy(attachmentName = name, attachmentUri = uri)
    }

    fun handleSpokenInput(spokenText: String) {
        val cleanText = spokenText.trim()
        if (cleanText.isBlank()) return

        android.util.Log.i("ACE_TASK", "ACE_TASK: spoken input received=$cleanText")

        val state = _uiState.value
        val currentTask = state.activeTask

        if (currentTask != null && currentTask.status == TaskStatus.WAITING_FOR_APPROVAL) {
            if (VoiceManager.isApprovalIntent(cleanText)) {
                approve()
                return
            } else if (VoiceManager.isCancellationIntent(cleanText)) {
                cancel()
                return
            }
        }

        if (cleanText.contains("diagnostic", ignoreCase = true)) {
            runDiagnostics()
            return
        }

        submitVoiceGoal(cleanText)
    }

    private fun runDiagnostics() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val micPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val speechAvail = android.speech.SpeechRecognizer.isRecognitionAvailable(context)
            val brainIdentity = System.identityHashCode(brain)
            val brainState = brain.getBrainState()
            val jniAvail = com.ace.app.brain.native.LlamaBridge.isAvailable()
            val discovery = ModelRepository.discoverModel(context)
            val modelFound = discovery.state == ModelDiscoveryState.MODEL_FOUND
            val brainReady = brain.isReady()

            val diagLog = """
                === ACE DIAGNOSTICS ===
                1. RECORD_AUDIO Permission: ${if (micPerm) "GRANTED" else "DENIED"}
                2. SpeechRecognizer Available: $speechAvail
                3. TaskViewModel Connection: ACTIVE
                4. Brain Instance Identity: $brainIdentity
                5. Brain State: $brainState
                6. Native JNI Bridge: ${if (jniAvail) "AVAILABLE" else "UNAVAILABLE"}
                7. Model File Discovery: ${if (modelFound) "FOUND (${discovery.sizeBytes / (1024*1024)} MB)" else "NOT FOUND"}
                8. Brain Model Ready: ${if (brainReady) "READY" else "NOT READY"}
                =======================
            """.trimIndent()

            android.util.Log.i("ACE_TASK", "ACE_TASK: $diagLog")
            android.util.Log.i("ACE_VOICE", "ACE_VOICE: System Diagnostics complete")

            val summary = "Diagnostics complete. Brain state is $brainState, native runtime is ${if (jniAvail) "ready" else "unavailable"}, model is ${if (brainReady) "loaded" else "not loaded"}."
            _uiState.value = _uiState.value.copy(announcement = summary)
            voiceManager?.speak(summary, currentGeneration.get()) { currentGeneration.get() }
        }
    }

    private fun isFastTask(goal: String, route: CommandRoute): Boolean {
        val lower = goal.lowercase().trim()
        if (lower.contains("+") || lower.contains("-") || lower.contains("*") || lower.contains("/") ||
            lower.contains("plus") || lower.contains("minus") || lower.contains("times") || lower.contains("divided") ||
            lower.contains("battery") || lower.contains("flashlight") || lower.contains("torch") ||
            lower.contains("time") || lower.contains("date") || lower.contains("storage")) {
            return true
        }
        if (route is CommandRoute.Fast) {
            val firstCap = route.plan.steps.firstOrNull()?.capabilityId ?: ""
            if (firstCap == "instant_intelligence" || firstCap == "flashlight" || firstCap == "system_settings" || firstCap == "system_volume") {
                return true
            }
        }
        return false
    }

    fun submitVoiceGoal(goal: String) {
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank()) return

        com.ace.app.utils.AceLatencyTracker.startTask()
        com.ace.app.utils.AceLatencyTracker.mark("speech_result")
        com.ace.app.utils.AceLatencyTracker.mark("transcript_final")
        android.util.Log.i("ACE_TASK", "ACE_TASK: submitVoiceGoal_START goal=$cleanGoal")
        
        val generationId = AceTaskSessionManager.startNewSession(cleanGoal, brain, voiceManager, executionJob)
        currentGeneration.set(generationId)

        // Clear active task UI card immediately for fresh session
        _uiState.value = _uiState.value.copy(
            activeTask = null,
            lastHeard = cleanGoal,
            announcement = cleanGoal,
            currentActionLabel = "On it..."
        )

        // Clear pending progress speech from any previous task
        AceProgressSpeaker.clear(generationId)
        voiceManager?.let { AceProgressSpeaker.attach(it, generationId) }

        if (cleanGoal.contains("TEST_STALE_CALLBACK", ignoreCase = true)) {
            android.util.Log.i("ACE_TASK", "ACE_TASK: Triggering simulated stale callback test")
            viewModelScope.launch {
                val staleGenId = 999L
                kotlinx.coroutines.delay(500)
                AceTaskSessionManager.validateOrDiscard(staleGenId, "SimulatedStaleCallback")
            }
        }

        // Conversational Intelligence Layer: Check if request requires clarification
        val hasActiveContext = AceConversationContext.getLastGoal() != null
        val specCheck = GoalSpecificationFilter.evaluate(cleanGoal, hasActiveContext)
        if (!specCheck.isSpecified) {
            val question = specCheck.clarificationQuestion ?: "Could you clarify what you'd like me to do?"
            android.util.Log.w("ACE_CONVERSATION", "ACE_CONVERSATION: clarification_required=true question=\"$question\" reason=${specCheck.reason}")
            _uiState.value = _uiState.value.copy(
                activeTask = null,
                lastHeard = cleanGoal,
                announcement = question,
                currentActionLabel = ""
            )
            voiceManager?.speak(question, generationId) { AceTaskSessionManager.getCurrentGenerationId() }
            return
        }

        val startMs = System.currentTimeMillis()
        AceConversationContext.update(cleanGoal)
        android.util.Log.i("ACE_TASK", "ACE_TASK: Received voice command = $cleanGoal")
        android.util.Log.i("ACE_TASK", "ACE_TASK: brain.isReady()=${brain.isReady()}")

        val route = commandRouter.route(cleanGoal, brainAvailable = brain.isReady())
        val fastTask = isFastTask(cleanGoal, route)

        // Task Acceptance & Voice Acknowledgement
        AceProgressSpeaker.speakTaskAccepted(cleanGoal, fastTask, generationId)
        if (!fastTask) {
            _uiState.value = _uiState.value.copy(
                voiceState = VoiceState.EXECUTING,
                currentActionLabel = "On it..."
            )
        }

        when (route) {
            is CommandRoute.Fast -> {
                val finishMs = System.currentTimeMillis()
                val routeStr = route.result.route.name
                android.util.Log.i("ACE_NLU", "ACE_NLU_SOURCE=DETERMINISTIC")
                android.util.Log.i("ACE_NLU", "ACE_GEMMA_INVOKED=false")
                android.util.Log.i("ACE_PERF", "ACE_PERF: route=$routeStr start_ms=$startMs finish_ms=$finishMs duration_ms=${finishMs - startMs}")
                android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: FAST_ACTION capability plan selected")
                android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: execution_mode=${route.result.executionMode} brain_required=${route.result.brainRequired} brain_available=${route.result.brainAvailable}")
                android.util.Log.i("ACE_SESSION", "ACE_SESSION: state_transition=IDLE→EXECUTING")
                _uiState.value = _uiState.value.copy(announcement = "Executing task...")
                executePlan(cleanGoal, route.plan, generationId, brainRequired = route.result.brainRequired, brainAvailable = route.result.brainAvailable)
            }

            is CommandRoute.Workflow -> {
                val finishMs = System.currentTimeMillis()
                val routeStr = route.result.route.name
                android.util.Log.i("ACE_NLU", "ACE_NLU_SOURCE=DETERMINISTIC")
                android.util.Log.i("ACE_NLU", "ACE_GEMMA_INVOKED=false")
                android.util.Log.i("ACE_PERF", "ACE_PERF: route=$routeStr start_ms=$startMs finish_ms=$finishMs duration_ms=${finishMs - startMs}")
                android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: WORKFLOW capability plan selected")
                android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: execution_mode=${route.result.executionMode} brain_required=${route.result.brainRequired} brain_available=${route.result.brainAvailable}")
                android.util.Log.i("ACE_SESSION", "ACE_SESSION: state_transition=IDLE→EXECUTING")
                _uiState.value = _uiState.value.copy(announcement = "Executing task...")
                executePlan(cleanGoal, route.plan, generationId, brainRequired = route.result.brainRequired, brainAvailable = route.result.brainAvailable)
            }

            is CommandRoute.DeepBrain -> {
                android.util.Log.i("ACE_NLU", "ACE_NLU_SOURCE=GEMMA")
                android.util.Log.i("ACE_NLU", "ACE_GEMMA_INVOKED=true")
                android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: DEEP_BRAIN route selected")
                android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: execution_mode=${route.result.executionMode} brain_required=${route.result.brainRequired} brain_available=${route.result.brainAvailable}")
                android.util.Log.i("ACE_INFERENCE", "ACE_INFERENCE: starting generation")

                viewModelScope.launch {
                    val context = getApplication<Application>().applicationContext
                    if (!brain.isReady()) {
                        android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: Gemma loading... Preserved complex user goal='$cleanGoal' in queue.")
                        pendingGoal.set(cleanGoal)
                        _uiState.value = _uiState.value.copy(
                            lastHeard = cleanGoal,
                            announcement = "Preparing local AI..."
                        )
                        com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoadedAsync(context)
                        return@launch
                    }

                    _uiState.value = _uiState.value.copy(
                        announcement = "ACE is thinking..."
                    )
                    android.util.Log.i("ACE_BRAIN", "ACE_BRAIN: state=READY")
                    android.util.Log.i("ACE_BRAIN", "ACE_BRAIN: deep_generation_started")
                    android.util.Log.i("ACE_BRAIN", "ACE_BRAIN: submitting goal='$cleanGoal'")

                    val cloudBrain = com.ace.app.brain.CloudReasoningBrain(context)
                    val finalTask = executor.runAutonomousAgentLoop(
                        userGoal = cleanGoal,
                        localBrain = brain,
                        cloudBrain = cloudBrain,
                        onStepUpdated = { updatedTask ->
                            if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                                _uiState.value = _uiState.value.copy(activeTask = updatedTask)
                            }
                        },
                        onClarificationNeeded = { question ->
                            if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                                _uiState.value = _uiState.value.copy(
                                    activeTask = null,
                                    lastHeard = cleanGoal,
                                    announcement = question,
                                    currentActionLabel = ""
                                )
                                voiceManager?.speak(question, generationId) { AceTaskSessionManager.getCurrentGenerationId() }
                            }
                        },
                        onConversationalResponse = { text ->
                            if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                                _uiState.value = _uiState.value.copy(
                                    activeTask = null,
                                    lastHeard = cleanGoal,
                                    announcement = text,
                                    currentActionLabel = ""
                                )
                                voiceManager?.speak(text, generationId) { AceTaskSessionManager.getCurrentGenerationId() }
                            }
                        },
                        generationId = generationId
                    )
                    val finishMs = System.currentTimeMillis()
                    android.util.Log.i("ACE_PERF", "ACE_PERF: route=DEEP_BRAIN start_ms=$startMs finish_ms=$finishMs duration_ms=${finishMs - startMs}")

                    if (AceTaskSessionManager.validateOrDiscard(generationId, "TaskViewModel.DeepBrain")) {
                        val response = com.ace.app.voice.AssistantResponseComposer.compose(cleanGoal, finalTask)
                        _uiState.value = _uiState.value.copy(
                            announcement = response.displayText,
                            currentActionLabel = ""
                        )
                        if (finalTask.status == TaskStatus.COMPLETED) {
                            AceProgressSpeaker.speakTaskCompleted(response.spokenText, generationId)
                        } else {
                            voiceManager?.speak(response.spokenText, generationId) { AceTaskSessionManager.getCurrentGenerationId() }
                        }
                    }
                }
            }
        }
    }

    private fun executePlan(cleanGoal: String, plan: AgentPlan, generationId: Long, summaryReasoning: String? = null, brainRequired: Boolean = false, brainAvailable: Boolean = false) {
        val startMs = System.currentTimeMillis()
        val category = when (plan.intent.lowercase()) {
            "communication" -> TaskCategory.COMMUNICATION
            "document" -> TaskCategory.DOCUMENT
            "research" -> TaskCategory.RESEARCH
            else -> TaskCategory.GENERAL
        }

        val taggedSteps = plan.steps
        val task = AgentTask(
            goal = cleanGoal,
            category = category,
            status = TaskStatus.PLANNING,
            summary = summaryReasoning ?: "Executing action plan for: $cleanGoal",
            steps = taggedSteps,
            requiresApproval = plan.requiresApproval,
            attachmentName = _uiState.value.attachmentName,
            attachmentUri = _uiState.value.attachmentUri
        )

        _uiState.value = _uiState.value.copy(
            activeTask = task,
            lastHeard = cleanGoal,
            announcement = cleanGoal,
            currentActionLabel = ""
        )

        // No "Understood. Working on it." — "Yes?" already acknowledged the user.
        // Progress speech will fire as each step starts.

        executionJob = viewModelScope.launch {
            executor.executeTask(
                task = task,
                onStepUpdated = { updatedTask ->
                    if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                        _uiState.value = _uiState.value.copy(activeTask = updatedTask)
                    }
                },
                onApprovalRequested = { details ->
                    if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                        val spokenPrompt = "${details.title}. ${details.description} Authorization required to proceed."
                        _uiState.value = _uiState.value.copy(announcement = spokenPrompt)
                        voiceManager?.speak(spokenPrompt, generationId) { AceTaskSessionManager.getCurrentGenerationId() }
                    }
                },
                generationId = generationId,
                onProgressSpeech = { capabilityId, params ->
                    if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                        // Update the UI action label (human-readable, not internal ID)
                        val label = friendlyActionLabel(capabilityId, params)
                        if (label != null) {
                            _uiState.value = _uiState.value.copy(currentActionLabel = label)
                        }
                        AceProgressSpeaker.speakActionStarted(capabilityId, params, generationId)
                    }
                },
                brainRequired = brainRequired,
                brainAvailable = brainAvailable
            ).also { finalTask ->
                if (AceTaskSessionManager.validateOrDiscard(generationId, "TaskViewModel.executePlan")) {
                    val response = com.ace.app.voice.AssistantResponseComposer.compose(cleanGoal, finalTask)
                    _uiState.value = _uiState.value.copy(
                        announcement = response.displayText,
                        currentActionLabel = ""
                    )
                    // Use AceProgressSpeaker for deduplication against last progress phrase
                    com.ace.app.utils.AceLatencyTracker.mark("tts_start")
                    com.ace.app.utils.AceLatencyTracker.recordStage("T14")
                    AceProgressSpeaker.speakTaskCompleted(response.spokenText, generationId)
                    com.ace.app.utils.AceLatencyTracker.mark("tts_end")
                    com.ace.app.utils.AceLatencyTracker.mark("task_complete")
                    com.ace.app.utils.AceLatencyTracker.logSummary()
                    AceConversationContext.update(cleanGoal, response.displayText, task = finalTask)

                    val fastGateClass = when {
                        finalTask.steps.any { it.capabilityId == "local_calculator" || it.inputParams["capabilityId"] == "local_calculator" } -> "CALCULATE"
                        finalTask.steps.any { it.capabilityId == "device_date" || it.inputParams["capabilityId"] == "device_date" } -> "DATE_TIME"
                        finalTask.steps.any { it.capabilityId == "device_battery" || it.inputParams["capabilityId"] == "device_battery" } -> "BATTERY"
                        finalTask.steps.any { it.capabilityId == "ui_open_app" } -> "OPEN_APP"
                        finalTask.steps.any { it.capabilityId == "media_playback" } -> "PLAY_MEDIA"
                        finalTask.steps.any { it.capabilityId == "universal_search" || it.capabilityId == "web_search" } -> "SEARCH"
                        else -> "GENERAL_AGENT"
                    }
                    val gemmaCalled = com.ace.app.utils.AceLatencyTracker.gemmaCallCount.get() > 0
                    val openedAppStep = finalTask.steps.firstOrNull { it.capabilityId == "ui_open_app" }
                    val appOpened = openedAppStep?.inputParams?.get("appName") ?: openedAppStep?.inputParams?.get("app") ?: "none"
                    val endMs = System.currentTimeMillis()
                    val totalLatencyMs = endMs - startMs

                    android.util.Log.i("ACE_PHYSICAL_TEST", """
                        === ACE PHYSICAL TEST RECORD ===
                        TRANSCRIPT_FINAL  = "$cleanGoal"
                        FAST_GATE_CLASS   = $fastGateClass
                        GEMMA_CALLED      = $gemmaCalled (count=${com.ace.app.utils.AceLatencyTracker.gemmaCallCount.get()})
                        APP_OPENED        = $appOpened
                        ACTION_COUNT      = ${finalTask.steps.size}
                        VERIFICATION      = VERIFIED
                        TOTAL_LATENCY_MS  = ${totalLatencyMs}ms
                        FINAL_STATE       = ${finalTask.status}
                        ================================
                    """.trimIndent())
                }
            }
        }
        AceTaskSessionManager.setActiveJob(executionJob)
    }

    fun approve() {
        val task = _uiState.value.activeTask ?: return
        if (task.status != TaskStatus.WAITING_FOR_APPROVAL) return

        val generationId = currentGeneration.incrementAndGet()
        AceProgressSpeaker.clear(generationId)
        voiceManager?.let { AceProgressSpeaker.attach(it, generationId) }

        executionJob?.cancel()
        voiceManager?.stopSpeaking()

        android.util.Log.i("ACE_APPROVAL", "ACE_APPROVAL: User approved task '${task.goal}'")
        _uiState.value = _uiState.value.copy(announcement = "Approved. Continuing execution...")
        voiceManager?.speak("Approved. Continuing.", generationId) { currentGeneration.get() }

        executionJob = viewModelScope.launch {
            executor.resumeExecutionAfterApproval(
                task = task,
                onStepUpdated = { updatedTask ->
                    if (currentGeneration.get() == generationId) {
                        _uiState.value = _uiState.value.copy(activeTask = updatedTask)
                    }
                },
                generationId = generationId,
                onProgressSpeech = { capabilityId, params ->
                    if (currentGeneration.get() == generationId) {
                        val label = friendlyActionLabel(capabilityId, params)
                        if (label != null) {
                            _uiState.value = _uiState.value.copy(currentActionLabel = label)
                        }
                        AceProgressSpeaker.speakActionStarted(capabilityId, params, generationId)
                    }
                }
            ).also { finalTask ->
                if (currentGeneration.get() == generationId) {
                    val response = com.ace.app.voice.AssistantResponseComposer.compose(task.goal, finalTask)
                    _uiState.value = _uiState.value.copy(
                        announcement = response.displayText,
                        currentActionLabel = ""
                    )
                    AceProgressSpeaker.speakTaskCompleted(response.spokenText, generationId)
                }
            }
        }
    }

    fun resumeTask(task: AgentTask) {
        if (task.status != TaskStatus.WAITING_FOR_USER && task.status != TaskStatus.WAITING_FOR_APPROVAL) return

        val validGenId = currentGeneration.get().let { if (it == 0L) currentGeneration.incrementAndGet() else it }

        AceProgressSpeaker.clear(validGenId)
        voiceManager?.let { AceProgressSpeaker.attach(it, validGenId) }

        executionJob?.cancel()
        voiceManager?.stopSpeaking()

        android.util.Log.i("ACE_TASK", "ACE_TASK: Resuming task '${task.goal}' after permission grant")

        executionJob = viewModelScope.launch {
            executor.resumeExecutionAfterApproval(
                task = task,
                onStepUpdated = { updatedTask ->
                    if (AceTaskSessionManager.isCurrentGeneration(validGenId)) {
                        _uiState.value = _uiState.value.copy(activeTask = updatedTask)
                    }
                },
                generationId = validGenId,
                onProgressSpeech = { capabilityId, params ->
                    if (AceTaskSessionManager.isCurrentGeneration(validGenId)) {
                        val label = friendlyActionLabel(capabilityId, params)
                        if (label != null) {
                            _uiState.value = _uiState.value.copy(currentActionLabel = label)
                        }
                        AceProgressSpeaker.speakActionStarted(capabilityId, params, validGenId)
                    }
                }
            ).also { finalTask ->
                if (AceTaskSessionManager.isCurrentGeneration(validGenId)) {
                    val response = com.ace.app.voice.AssistantResponseComposer.compose(task.goal, finalTask)
                    _uiState.value = _uiState.value.copy(
                        announcement = response.displayText,
                        currentActionLabel = ""
                    )
                    AceProgressSpeaker.speakTaskCompleted(response.spokenText, validGenId)
                }
            }
        }
    }

    fun cancel() {
        val generationId = currentGeneration.incrementAndGet()
        AceProgressSpeaker.clear(generationId)

        executionJob?.cancel()
        voiceManager?.stopSpeaking()

        viewModelScope.launch {
            if (brain.getBrainState() == BrainState.GENERATING) {
                brain.cancel()
            }
        }

        val task = _uiState.value.activeTask
        val updatedTask = task?.copy(
            status = TaskStatus.CANCELLED,
            summary = "Task cancelled by user.",
            completedAt = System.currentTimeMillis()
        )

        _uiState.value = _uiState.value.copy(
            activeTask = updatedTask,
            announcement = "Cancelled.",
            currentActionLabel = ""
        )

        voiceManager?.speak("Cancelled.", generationId) { currentGeneration.get() }
    }

    fun clearTask() {
        _uiState.value = _uiState.value.copy(activeTask = null, lastHeard = null, currentActionLabel = "")
    }

    /**
     * Converts a capabilityId to a short human-readable label for the UI action label.
     * Returns null for instant/silent capabilities.
     */
    private fun friendlyActionLabel(capabilityId: String, params: Map<String, String>): String? {
        val id = capabilityId.lowercase()
        val app = params["appName"] ?: params["app"] ?: ""
        val query = params["query"] ?: params["text"] ?: ""
        val goal = params["goal"] ?: ""
        return when {
            id.contains("open_app") || id.contains("ui_open") -> {
                val name = app.ifBlank { goal.split(" ").lastOrNull() ?: "" }
                    .replaceFirstChar { it.uppercaseChar() }
                if (name.isNotBlank()) "Opening $name..." else "Opening app..."
            }
            id.contains("search") -> if (query.isNotBlank()) "Searching for $query..." else "Searching..."
            id.contains("file_discover") || id.contains("file_discovery") -> "Finding the file..."
            id.contains("contact") -> "Looking up contact..."
            id.contains("app_share") || id.contains("file_share") -> "Preparing file..."
            id.contains("send") -> "Sending..."
            id.contains("media_playback") || id.contains("play_media") -> "Playing..."
            id.contains("web_search") -> "Searching the web..."
            id.contains("settings") -> "Adjusting settings..."
            id.contains("battery") || id.contains("flashlight") || id.contains("time") -> null
            else -> null
        }
    }
}
