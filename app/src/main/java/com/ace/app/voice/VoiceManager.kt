package com.ace.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    EXECUTING
}

class VoiceManager(
    private val context: Context,
    private val onSpeechRecognized: (String) -> Unit,
    private val onStateChanged: (VoiceState) -> Unit,
    private val onError: (String) -> Unit,
    private val onSpeechStart: (() -> Unit)? = null,
    private val onSpeechEnd: (() -> Unit)? = null,
    private val onProviderChanged: ((VoiceProvider) -> Unit)? = null
) {
    private var rimeOutput: RimeVoiceOutput? = null
    private var recognizer: SpeechRecognizer? = null
    private var currentState: VoiceState = VoiceState.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeSessionId: String = ""
    private val processedSessionIds = HashSet<String>()
    private val voiceSessionGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    var partialResultsCount: Int = 0
    var finalResultsCount: Int = 0

    fun getActiveGenerationId(): Long = voiceSessionGeneration.get()

    val currentProvider: VoiceProvider
        get() = rimeOutput?.currentProvider ?: VoiceProvider.DEVICE_FALLBACK

    init {
        rimeOutput = RimeVoiceOutput(
            context = context,
            onSpeechStart = { updateState(VoiceState.SPEAKING) },
            onSpeechDone = { updateState(VoiceState.IDLE) },
            onProviderChanged = { provider -> onProviderChanged?.invoke(provider) }
        )
    }

    fun speak(text: String, generationId: Long = 0, currentGenerationId: () -> Long = { 0 }) {
        val safeText = AssistantResponseComposer.enforceTtsSafetyFilter(text)
        mainHandler.post {
            stopListening()
            updateState(VoiceState.SPEAKING)
            Log.i("ACE_TTS_FINAL", "ACE_TTS_FINAL: session=$generationId source=VoiceManager text=\"$safeText\"")
            rimeOutput?.speak(safeText, generationId, currentGenerationId)
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            rimeOutput?.stop()
            Log.i("ACE_TTS", "ACE_TTS: speech stopped")
            if (currentState == VoiceState.SPEAKING) {
                updateState(VoiceState.IDLE)
            }
        }
    }

    fun startListening(sessionId: String = "voice_session_${System.currentTimeMillis()}", retryCount: Int = 0) {
        mainHandler.post {
            // Cleanly tear down any ongoing recognition session or TTS playback
            rimeOutput?.stop()
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
            } catch (_: Exception) {}
            recognizer = null

            val sessionGen = if (retryCount == 0) {
                voiceSessionGeneration.incrementAndGet()
            } else {
                voiceSessionGeneration.get()
            }

            activeSessionId = sessionId
            partialResultsCount = 0
            finalResultsCount = 0
            
            Log.i("ACE_VOICE", "VOICE_SESSION_START generation=$sessionGen")
            Log.i("ACE_SESSION", "ACE_SESSION: tap_received generation=$sessionGen")
            Log.i("ACE_SESSION", "ACE_SESSION: state_transition=IDLE→LISTENING")
            Log.i("ACE_VOICE", "ACE_VOICE: listening_start immediately")
            
            Log.i("ACE_MIC", "ACE_MIC: mic_active=true mic_owner=SpeechRecognizer session=$sessionId synthetic_event=false generation=$sessionGen")
            if (currentState == VoiceState.SPEAKING) {
                Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: user speech barge-in detected during TTS")
                Log.i("ACE_TTS", "ACE_TTS: speech stopped")
            }
            currentState = VoiceState.LISTENING

            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            val isAvail = SpeechRecognizer.isRecognitionAvailable(context)

            if (!hasPerm || !isAvail) {
                if (!hasPerm) safeOnError("Microphone permission required.")
                else safeOnError("Speech recognition unavailable on device.")
                updateState(VoiceState.IDLE)
                return@post
            }

            try {
                // Destroy old instance and allow system-side release before creating new one
                recognizer?.destroy()
                recognizer = null

                val capturedGen = sessionGen

                val listener = object : RecognitionListener {
                    private fun isCurrentGen(): Boolean {
                        val activeGen = voiceSessionGeneration.get()
                        if (capturedGen != activeGen) {
                            Log.w("ACE_VOICE", "VOICE_CALLBACK_IGNORED stale_generation=$capturedGen active_generation=$activeGen")
                            return false
                        }
                        return true
                    }

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!isCurrentGen()) return
                        Log.i("ACE_SPEECH", "ACE_SPEECH: session=$activeSessionId ready_for_speech=true generation=$capturedGen")
                        updateState(VoiceState.LISTENING)
                    }

                    override fun onBeginningOfSpeech() {
                        if (!isCurrentGen()) return
                        Log.i("ACE_SPEECH", "ACE_SPEECH: session=$activeSessionId speech_started=true generation=$capturedGen")
                        Log.i("ACE_MIC", "ACE_MIC: mic_active=true mic_source=DEVICE_HARDWARE_MIC session=$activeSessionId synthetic_event=false generation=$capturedGen")
                        updateState(VoiceState.LISTENING)
                        mainHandler.post { onSpeechStart?.invoke() }
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        if (!isCurrentGen()) return
                        Log.i("ACE_SPEECH", "ACE_SPEECH: session=$activeSessionId speech_ended=true generation=$capturedGen")
                        Log.i("ACE_MIC", "ACE_MIC: mic_active=false session=$activeSessionId synthetic_event=false generation=$capturedGen")
                        Log.i("ACE_SESSION", "ACE_SESSION: state_transition=LISTENING→THINKING")
                        updateState(VoiceState.THINKING)
                        mainHandler.post { onSpeechEnd?.invoke() }
                    }

                    override fun onError(error: Int) {
                        if (!isCurrentGen()) return
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match detected."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out."
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                            SpeechRecognizer.ERROR_NETWORK -> "Network connection issue during recognition."
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network connection timed out."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy."
                            SpeechRecognizer.ERROR_SERVER -> "Speech server error."
                            else -> "Speech recognition issue ($error)."
                        }
                        Log.w("ACE_SPEECH", "ACE_SPEECH: session=$activeSessionId error_code=$error message=\"$message\" generation=$capturedGen")
                        Log.i("ACE_SESSION", "ACE_SESSION: state_transition=THINKING→IDLE")
                        updateState(VoiceState.IDLE)

                        // Silently auto-retry on transient busy/client errors (no toast shown), max 2 retries
                        if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && retryCount < 2) {
                            Log.i("ACE_SPEECH", "ACE_SPEECH: transient error=$error — silently retrying after 500ms (attempt ${retryCount + 1}/2)")
                            try { recognizer?.destroy() } catch (_: Exception) {}
                            recognizer = null
                            mainHandler.postDelayed({
                                startListening(activeSessionId, retryCount + 1)
                            }, 500L)
                        } else {
                            safeOnError(message)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (!isCurrentGen()) return
                        finalResultsCount++
                        val currentSession = activeSessionId
                        if (processedSessionIds.contains(currentSession)) {
                            Log.w("ACE_SPEECH", "ACE_SPEECH: session=$currentSession duplicate_onResults_ignored generation=$capturedGen")
                            return
                        }

                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                        Log.i("ACE_VOICE", "VOICE_FINAL generation=$capturedGen transcript_length=${spokenText.length}")
                        Log.i("ACE_SESSION", "ACE_SESSION: state_transition=THINKING→IDLE_pending_route")
                        updateState(VoiceState.IDLE)
                        val isValid = isValidVoiceCommand(spokenText)

                        Log.i("ACE_VOICE_TEST", "ACE_VOICE_TEST: session_id=$currentSession wake_detected=true recognized_text=\"$spokenText\" partial_results_count=$partialResultsCount final_results_count=$finalResultsCount command_executed=$isValid tts_response=\"\" false_trigger=${!isValid}")

                        if (isValid) {
                            processedSessionIds.add(currentSession)
                            com.ace.app.utils.AceLatencyTracker.startTask()
                            Log.i("ACE_SPEECH", "ACE_SPEECH: session=$currentSession final_result=\"$spokenText\" execute=true synthetic_event=false generation=$capturedGen")
                            Log.i("ACE_COMMAND", "ACE_COMMAND: session=$currentSession routing_started=true goal=\"$spokenText\" generation=$capturedGen")
                            mainHandler.post { onSpeechRecognized(spokenText) }
                        } else {
                            Log.w("ACE_SPEECH", "ACE_SPEECH: session=$currentSession rejected_invalid_command=\"$spokenText\" execute=false generation=$capturedGen")
                            safeOnError("Invalid or empty speech input")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!isCurrentGen()) return
                        partialResultsCount++
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            Log.i("ACE_VOICE", "VOICE_PARTIAL generation=$capturedGen")
                            Log.i("ACE_SPEECH", "ACE_SPEECH: session=$activeSessionId partial_result=\"$partial\" execute=false generation=$capturedGen")
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }

                // Allow 100ms for the system to release the previous audio session before starting new one
                mainHandler.postDelayed({
                    try {
                        val speechRec = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }

                        recognizer = speechRec.apply {
                            setRecognitionListener(listener)
                        }

                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                        }

                        recognizer?.startListening(intent)
                    } catch (e: Exception) {
                        Log.e("ACE_SPEECH", "ACE_SPEECH: delayed start failed: ${e.message}")
                        recognizer = null
                        updateState(VoiceState.IDLE)
                    }
                }, 100L)
            } catch (e: Exception) {
                Log.e("ACE_SPEECH", "ACE_SPEECH: session=$activeSessionId error=\"${e.message}\"", e)
                try { recognizer?.destroy() } catch (_: Exception) {}
                recognizer = null
                updateState(VoiceState.IDLE)
                safeOnError("Could not start speech recognizer: ${e.message}")
            }
        }
    }

    fun isValidVoiceCommand(rawText: String): Boolean {
        val clean = rawText.trim().lowercase()
        if (clean.isBlank()) return false
        if (clean == "." || clean == "yes" || clean == "yeah" || clean == "yep" || clean == "hey ace" || clean == "listening" || clean == "speech input") return false
        if (clean.length < 2) return false

        val validShortCommands = setOf("stop", "time", "date", "help", "open", "exit", "quit", "pause", "next", "back", "call", "play", "mute")
        if (clean.length in 2..4) {
            val noiseWords = setOf("uh", "um", "ah", "oh", "er", "ha", "hm", "mm", "so")
            if (noiseWords.contains(clean)) return false
        }
        return true
    }

    fun stopListening() {
        mainHandler.post {
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
                recognizer = null
            } catch (ignored: Exception) {}
            if (currentState == VoiceState.LISTENING) {
                updateState(VoiceState.IDLE)
            }
        }
    }

    fun updateState(newState: VoiceState) {
        mainHandler.post {
            currentState = newState
            onStateChanged(newState)
        }
    }

    private fun safeOnError(msg: String) {
        mainHandler.post { onError(msg) }
    }

    fun release() {
        mainHandler.post {
            try {
                rimeOutput?.stop()
                recognizer?.stopListening()
                recognizer?.destroy()
            } catch (ignored: Exception) {}
            recognizer = null
            rimeOutput?.release()
            rimeOutput = null
        }
    }

    companion object {
        private val APPROVAL_TOKENS = setOf(
            "yes", "yeah", "yep", "approve", "approved", "proceed",
            "confirm", "confirmed", "ok", "okay", "sure"
        )
        private val APPROVAL_PHRASES = listOf("go ahead", "do it", "go for it")

        private val CANCEL_TOKENS = setOf(
            "no", "nope", "cancel", "stop", "abort", "reject", "don't", "dont"
        )
        private val CANCEL_PHRASES = listOf("never mind", "nevermind", "hold on")

        private fun tokenize(text: String): List<String> =
            text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }

        fun isApprovalIntent(text: String): Boolean {
            val lower = text.lowercase().trim()
            if (APPROVAL_PHRASES.any { lower.contains(it) }) return true
            return tokenize(lower).any { it in APPROVAL_TOKENS }
        }

        fun isCancellationIntent(text: String): Boolean {
            val lower = text.lowercase().trim()
            if (CANCEL_PHRASES.any { lower.contains(it) }) return true
            return tokenize(lower).any { it in CANCEL_TOKENS }
        }
    }
}
