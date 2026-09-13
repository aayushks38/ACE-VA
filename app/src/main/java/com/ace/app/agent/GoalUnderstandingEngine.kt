package com.ace.app.agent

import android.util.Log

/**
 * Structured Goal Interpretation produced by the Reasoning Model.
 * Kotlin does NOT decide intent, ambiguity, or postconditions; the model does.
 */
data class GoalInterpretation(
    val rawGoal: String,
    val objectiveType: String = "GENERAL",
    val requestedOutcome: String = "",
    val targetEntities: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val temporalRequirements: List<String> = emptyList(),
    val desiredFinalState: String = "",
    val desiredInformation: String = "",
    val successConditions: List<String> = emptyList(),
    val unresolvedAmbiguities: List<String> = emptyList(),
    val isAmbiguous: Boolean = false,
    val clarificationRequired: Boolean = false,
    val clarificationQuestion: String? = null
)

enum class ObjectiveType {
    STATE_MODIFICATION,
    INFORMATION_RETRIEVAL,
    NAVIGATION,
    COMMUNICATION,
    TRANSACTION,
    GENERAL
}

data class StructuredGoalObjective(
    val rawGoal: String,
    val objectiveType: ObjectiveType = ObjectiveType.GENERAL,
    val requestedOutcome: String,
    val primaryTarget: String = "",
    val isInformational: Boolean = false,
    val expectedOutcomeSummary: String,
    val desiredState: String = "",
    val desiredInformation: String = "",
    val targetEntities: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val isAmbiguous: Boolean = false,
    val clarificationQuestion: String? = null
)

/**
 * Pure Passive Data Contract Adapter.
 * Converts model-produced GoalInterpretation into ExpectedPostcondition and StructuredGoalObjective.
 * Absolutely zero hardcoded verb lists, first-word classifications, phrase lists,
 * question-word string matches, word-length heuristics, or fake fallback sentences.
 */
object GoalUnderstandingEngine {

    fun createInitialInterpretation(goal: String): GoalInterpretation {
        val clean = goal.trim()
        try { Log.i("ACE_GOAL_UNDERSTANDING", "ACE_GOAL_UNDERSTANDING: Creating uninterpreted GoalInterpretation wrapper for goal='$clean'") } catch (_: Throwable) {}

        if (clean.isBlank()) {
            return GoalInterpretation(
                rawGoal = clean,
                requestedOutcome = "Clarification required",
                clarificationRequired = true,
                isAmbiguous = true,
                clarificationQuestion = "Could you please specify what task or action you would like me to perform?"
            )
        }

        return GoalInterpretation(
            rawGoal = clean,
            objectiveType = "UNINTERPRETED",
            requestedOutcome = clean,
            targetEntities = listOf(clean),
            constraints = emptyList(),
            temporalRequirements = emptyList(),
            desiredFinalState = "",
            desiredInformation = "",
            successConditions = emptyList(),
            unresolvedAmbiguities = emptyList(),
            clarificationRequired = false,
            clarificationQuestion = null
        )
    }

    fun deriveObjective(goal: String): StructuredGoalObjective {
        val interp = createInitialInterpretation(goal)
        return convertInterpretationToObjective(interp)
    }

    fun convertInterpretationToObjective(interp: GoalInterpretation): StructuredGoalObjective {
        val isInfo = interp.objectiveType.equals("INFORMATION_RETRIEVAL", ignoreCase = true)
        val type = if (isInfo) ObjectiveType.INFORMATION_RETRIEVAL else ObjectiveType.GENERAL

        return StructuredGoalObjective(
            rawGoal = interp.rawGoal,
            objectiveType = type,
            requestedOutcome = interp.requestedOutcome.ifBlank { interp.rawGoal },
            primaryTarget = interp.rawGoal,
            isInformational = isInfo,
            expectedOutcomeSummary = interp.requestedOutcome.ifBlank { interp.rawGoal },
            desiredState = interp.desiredFinalState,
            desiredInformation = interp.desiredInformation,
            targetEntities = interp.targetEntities,
            constraints = interp.constraints,
            isAmbiguous = interp.clarificationRequired || interp.isAmbiguous,
            clarificationQuestion = interp.clarificationQuestion
        )
    }

    fun derivePostcondition(goal: String): ExpectedPostcondition {
        val interp = createInitialInterpretation(goal)
        return derivePostconditionFromInterpretation(interp)
    }

    fun derivePostconditionFromInterpretation(interp: GoalInterpretation): ExpectedPostcondition {
        val isInfo = interp.objectiveType.equals("INFORMATION_RETRIEVAL", ignoreCase = true)
        val outcomeSummary = interp.requestedOutcome.ifBlank { interp.rawGoal }
        return ExpectedPostcondition(
            summary = outcomeSummary,
            desiredState = interp.desiredFinalState,
            desiredInformation = interp.desiredInformation,
            desiredEnvironmentCondition = if (isInfo) interp.desiredInformation else interp.desiredFinalState,
            targetEntities = interp.targetEntities,
            constraints = interp.constraints,
            successConditions = interp.successConditions
        )
    }
}
