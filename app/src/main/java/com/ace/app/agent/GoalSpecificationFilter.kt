package com.ace.app.agent

import android.util.Log

data class SpecificationCheck(
    val isSpecified: Boolean,
    val clarificationQuestion: String? = null,
    val reason: String = ""
)

/**
 * Conversational Intelligence Gateway.
 * Validates goal presence without enforcing brittle hardcoded string match lists.
 * Semantic clarification is delegated to the ReasoningBrain.
 */
object GoalSpecificationFilter {

    private const val TAG = "ACE_SPECIFICATION"

    fun evaluate(goal: String, hasActiveContext: Boolean = false): SpecificationCheck {
        val lower = goal.lowercase().trim()

        if (lower.isBlank()) {
            Log.w(TAG, "ACE_SPECIFICATION: BLANK_GOAL")
            return SpecificationCheck(
                isSpecified = false,
                clarificationQuestion = "Could you tell me what you'd like me to do?",
                reason = "blank_goal"
            )
        }

        // Semantic goal specification is delegated to ReasoningBrain during the autonomous agent loop.
        Log.i(TAG, "ACE_SPECIFICATION: GOAL_SPECIFIED goal=\"$goal\"")
        return SpecificationCheck(isSpecified = true)
    }
}
