package com.ace.app.brain

import android.content.Context
import android.util.Log
import com.ace.app.agent.AgentTaskContext
import com.ace.app.agent.ScreenObservation

/**
 * General Brain Router.
 * Dynamically selects the appropriate ReasoningBrain backend based on task complexity,
 * execution evidence, recovery attempts, and model readiness.
 */
object BrainRouter {

    private const val TAG = "ACE_BRAIN_ROUTER"

    fun selectBrain(
        goal: String,
        observation: ScreenObservation,
        context: AgentTaskContext,
        localBrain: LocalBrain?,
        cloudBrain: ReasoningBrain?
    ): ReasoningBrain {
        val cleanGoal = goal.lowercase()

        // 1. Task Complexity & Escalation Check
        val failedAttempts = context.actionHistory.count { it.contains("FAILED") || it.contains("UNFULFILLED") }
        val requiresCloudEscalation = failedAttempts >= 2 || context.blockers.isNotEmpty()

        if (requiresCloudEscalation && cloudBrain != null && cloudBrain.isReady()) {
            Log.i(TAG, "ACE_BRAIN_ROUTER: Escalating task to CLOUD_PROVIDER (failedAttempts=$failedAttempts, blockers=${context.blockers.size})")
            return cloudBrain
        }

        // 2. Local Brain Primary Choice
        if (localBrain != null && localBrain.isReady()) {
            Log.i(TAG, "ACE_BRAIN_ROUTER: Routing goal='$goal' to LOCAL_GEMMA")
            return localBrain as ReasoningBrain
        }

        // 3. Fallback to Cloud if configured
        if (cloudBrain != null && cloudBrain.isReady()) {
            Log.i(TAG, "ACE_BRAIN_ROUTER: Local brain unavailable; routing goal='$goal' to CLOUD_PROVIDER")
            return cloudBrain
        }

        // 4. Fallback to Local Brain (will use heuristic perception fallback if GGUF loading)
        Log.w(TAG, "ACE_BRAIN_ROUTER: Defaulting to LOCAL_GEMMA with heuristic fallback")
        return (localBrain as? ReasoningBrain) ?: object : ReasoningBrain {
            override val backendType = ReasoningBackend.LOCAL_GEMMA
            override suspend fun reasonNextDecision(goal: String, observation: ScreenObservation, context: AgentTaskContext, generationId: Long): AgentDecision {
                return com.ace.app.agent.ScreenObservationEngine.determineNextActionHeuristic(goal, observation)
            }
            override fun isReady(): Boolean = true
        }
    }
}
