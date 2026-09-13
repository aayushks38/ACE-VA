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
            try { Log.i(TAG, "ACE_BRAIN_ROUTER: Escalating task to CLOUD_PROVIDER (failedAttempts=$failedAttempts, blockers=${context.blockers.size})") } catch (_: Throwable) {}
            return cloudBrain
        }

        // 2. Local Brain Primary Choice
        if (localBrain != null && localBrain.isReady()) {
            try { Log.i(TAG, "ACE_BRAIN_ROUTER: Routing goal='$goal' to LOCAL_GEMMA") } catch (_: Throwable) {}
            return localBrain as ReasoningBrain
        }

        // 3. Fallback to Cloud if configured
        if (cloudBrain != null && cloudBrain.isReady()) {
            try { Log.i(TAG, "ACE_BRAIN_ROUTER: Local brain unavailable; routing goal='$goal' to CLOUD_PROVIDER") } catch (_: Throwable) {}
            return cloudBrain
        }

        // 4. Fallback if no valid reasoning backend is ready
        try { Log.w(TAG, "ACE_BRAIN_ROUTER: Neither local nor cloud reasoning backend is ready.") } catch (_: Throwable) {}
        return object : ReasoningBrain {
            override val backendType = ReasoningBackend.LOCAL_GEMMA
            override suspend fun reasonNextDecision(goal: String, observation: ScreenObservation, context: AgentTaskContext, generationId: Long): AgentDecision {
                return AgentDecision.Blocked("No reasoning backend is ready or available to process goal.")
            }
            override fun isReady(): Boolean = false
        }
    }
}
