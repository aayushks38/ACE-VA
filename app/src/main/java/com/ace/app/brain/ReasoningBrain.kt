package com.ace.app.brain

import com.ace.app.agent.AgentTaskContext
import com.ace.app.agent.GoalInterpretation
import com.ace.app.agent.GoalUnderstandingEngine
import com.ace.app.agent.ScreenObservation

enum class ReasoningBackend {
    LOCAL_GEMMA,
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
    data class Replan(
        val updatedGoal: String,
        val updatedPostcondition: com.ace.app.agent.ExpectedPostcondition? = null,
        val reason: String = ""
    ) : AgentDecision()
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

    suspend fun verifyPostcondition(
        goal: String,
        observation: ScreenObservation,
        context: AgentTaskContext
    ): com.ace.app.agent.IndependentVerificationOutcome {
        return com.ace.app.agent.IndependentGoalVerifier.evaluateSemanticPostcondition(
            goal,
            context.expectedPostcondition,
            com.ace.app.agent.EnvironmentEvidence(
                screenObservation = observation,
                capturedEvidenceMap = context.capturedEvidence,
                actionHistory = context.actionHistory,
                blockers = context.blockers,
                brainHypothesis = context.capturedEvidence["brain_completion_hypothesis"]
            )
        )
    }

    fun isReady(): Boolean
}
