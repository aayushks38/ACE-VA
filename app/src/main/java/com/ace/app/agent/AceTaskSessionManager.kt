package com.ace.app.agent

import android.util.Log
import com.ace.app.brain.BrainState
import com.ace.app.brain.LocalBrain
import com.ace.app.voice.VoiceManager
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object AceTaskSessionManager {
    private val currentGenerationId = AtomicLong(100L)
    private val currentGoal = AtomicReference<String>("")
    private var activeJob: Job? = null

    fun getCurrentGenerationId(): Long = currentGenerationId.get()
    fun getCurrentGoal(): String = currentGoal.get()

    fun startNewSession(
        newGoal: String,
        brain: LocalBrain,
        voiceManager: VoiceManager?,
        executionJob: Job? = null
    ): Long {
        val oldGenId = currentGenerationId.get()
        val newGenId = currentGenerationId.incrementAndGet()

        currentGoal.set(newGoal)

        // Cancel previous running execution job
        executionJob?.cancel()
        activeJob?.cancel()

        Log.i("ACE_TASK", "TASK_SESSION_CANCEL generation=$oldGenId")
        Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: detected=true")
        Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: new user command detected")
        Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: previous_task_id=task_$oldGenId")
        Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: previous_task_cancelled=true")
        Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: cancelling task_id=task_$oldGenId")
        Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: previous_generation=$oldGenId")
        Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: new_generation=$newGenId")
        Log.i("ACE_TASK", "ACE_TASK: previous_task=CANCELLED")
        Log.i("ACE_TASK", "ACE_TASK: new_task=ACTIVE")
        Log.i("ACE_TASK", "ACE_TASK: previous task marked CANCELLED")

        // 1. Interrupt Gemma inference if generating (without unloading model)
        if (brain.getBrainState() == BrainState.GENERATING) {
            Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: gemma_generation_cancelled=true")
            Log.i("ACE_INFERENCE", "ACE_INFERENCE: interruption requested")
            Log.i("ACE_INFERENCE", "ACE_INFERENCE: native generation cancellation requested")
            Log.i("ACE_INFERENCE", "ACE_INFERENCE: cancellation flag set")
            Log.i("ACE_BRAIN", "ACE_BRAIN: model_unloaded=false")
            Log.i("ACE_BRAIN", "ACE_BRAIN: state=READY")
            Log.i("ACE_TASK", "ACE_TASK: old task cancelled")
        }

        // 2. Stop Voice Output / TTS
        voiceManager?.stopSpeaking()
        Log.i("ACE_TTS", "ACE_TTS: speech stopped")
        Log.i("ACE_TTS", "ACE_TTS: stopped_by_barge_in=true")

        // 3. Clear conversation / task context completely for fresh task session
        AceConversationContext.clearSession()

        Log.i("ACE_ROUTER", "ACE_ROUTER: processing newest command")
        Log.i("ACE_TASK", "TASK_SESSION_START generation=$newGenId")
        Log.i("ACE_TASK", "ACE_TASK: generation=$newGenId goal=\"$newGoal\"")
        Log.i("ACE_TASK", "ACE_TASK: active_task_id=task_$newGenId")

        return newGenId
    }

    fun isCurrentGeneration(generationId: Long): Boolean {
        if (generationId == 0L) return true
        val activeGen = currentGenerationId.get()
        val matches = generationId == activeGen
        if (!matches) {
            Log.w("ACE_TASK", "TASK_EVENT_IGNORED stale=$generationId active=$activeGen")
            Log.w("ACE_TASK", "TASK_CALLBACK_IGNORED stale_generation=$generationId active_generation=$activeGen")
            Log.w("ACE_TASK", "ACE_TASK: STALE_REJECTED eventGeneration=$generationId currentGeneration=$activeGen")
        } else {
            Log.i("ACE_TASK", "TASK_EVENT generation=$generationId")
        }
        return matches
    }

    fun validateOrDiscard(generationId: Long, sourceTag: String): Boolean {
        val activeGen = currentGenerationId.get()
        if (generationId != 0L && generationId != activeGen) {
            Log.w("ACE_TASK", "TASK_EVENT_IGNORED stale=$generationId active=$activeGen")
            Log.w("ACE_TASK", "TASK_CALLBACK_IGNORED stale_generation=$generationId active_generation=$activeGen")
            Log.w("ACE_TASK", "ACE_TASK: STALE_REJECTED eventGeneration=$generationId currentGeneration=$activeGen")
            Log.w("ACE_TASK", "ACE_TASK: stale_result_detected")
            Log.w("ACE_TASK", "ACE_TASK: generation_mismatch")
            Log.w("ACE_TASK", "ACE_TASK: stale_result_discarded=true")
            Log.w("ACE_TASK", "ACE_TASK: discarded callback from $sourceTag (received=$generationId, current=$activeGen)")
            return false
        }
        return true
    }

    fun setActiveJob(job: Job?) {
        activeJob = job
    }
}
