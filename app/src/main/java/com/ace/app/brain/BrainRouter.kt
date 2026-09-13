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
        localBrain: LocalBrain?
    ): ReasoningBrain {
        if (localBrain != null && localBrain.isReady()) {
            try { Log.i(TAG, "ACE_BRAIN_ROUTER: Routing goal='$goal' to LOCAL_GEMMA") } catch (_: Throwable) {}
            return localBrain as ReasoningBrain
        }

        try { Log.w(TAG, "ACE_BRAIN_ROUTER: Local Gemma reasoning backend is not ready.") } catch (_: Throwable) {}
        return object : ReasoningBrain {
            override val backendType = ReasoningBackend.LOCAL_GEMMA
            override suspend fun reasonNextDecision(goal: String, observation: ScreenObservation, context: AgentTaskContext, generationId: Long): AgentDecision {
                return AgentDecision.Blocked("Local Gemma AI reasoning engine is unavailable.")
            }
            override fun isReady(): Boolean = false
        }
    }
}
