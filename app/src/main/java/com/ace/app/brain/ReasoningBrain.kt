package com.ace.app.brain

import com.ace.app.agent.AgentTaskContext
import com.ace.app.agent.GoalInterpretation
import com.ace.app.agent.GoalUnderstandingEngine
import com.ace.app.agent.ScreenObservation

enum class ReasoningBackend {
    LOCAL_GEMMA,
    CLOUD_PROVIDER,
    DETERMINISTIC_FAST_PATH
}

/**
 * Robust Agent Decision Model.
 * Represents the semantic outcome of a reasoning turn.
 * Completely eliminates fragile command-string parsing errors.
 */
sealed class AgentDecision {
    data class Clarify(val question: String) : AgentDecision()
    data class ConversationalResponse(val text: String) : AgentDecision()
    data class Action(
        val primitive: String,
        val target: String = "",
        val inputText: String = "",
        val params: Map<String, String> = emptyMap()
    ) : AgentDecision()
    data class Wait(val durationMs: Long = 1000) : AgentDecision()
    data class Replan(val updatedGoal: String) : AgentDecision()
    data class Blocked(val reason: String, val userActionRequired: Boolean = true) : AgentDecision()
    data class Complete(val evidence: String) : AgentDecision()
}

/**
 * Universal Reasoning Brain Contract.
 * Implemented by both Local GGUF Gemma model and Cloud Provider backends.
 * The Agent Core interacts exclusively with this interface.
 */
interface ReasoningBrain {
    val backendType: ReasoningBackend

    suspend fun interpretGoal(
        goal: String,
        observation: ScreenObservation,
        context: AgentTaskContext
    ): GoalInterpretation {
        return GoalUnderstandingEngine.createInitialInterpretation(goal)
    }

    suspend fun reasonNextDecision(
        goal: String,
        observation: ScreenObservation,
        context: AgentTaskContext,
        generationId: Long = 0L
    ): AgentDecision

    fun isReady(): Boolean
}
