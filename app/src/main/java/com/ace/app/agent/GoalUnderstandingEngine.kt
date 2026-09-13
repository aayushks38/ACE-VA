package com.ace.app.agent

import android.util.Log

data class GoalInterpretation(
    val rawGoal: String,
    val objectiveType: String = "GENERAL",
    val requestedOutcome: String = "",
    val targetEntities: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val desiredFinalState: String = "",
    val desiredInformation: String = "",
    val successConditions: List<String> = emptyList(),
    val isAmbiguous: Boolean = false,
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
 * Pure Model-Facing Goal Understanding & Semantic Postcondition Engine.
 * Absolutely zero hardcoded verb lists, first-word classifications, phrase lists,
 * question-word string matches (contains("what"), etc.), word-length heuristics, or regex intent classifiers.
 * Semantics and postcondition contracts are derived from model reasoning.
 */
object GoalUnderstandingEngine {

    fun createInitialInterpretation(goal: String): GoalInterpretation {
        val clean = goal.trim()
        try { Log.i("ACE_GOAL_UNDERSTANDING", "ACE_GOAL_UNDERSTANDING: Creating initial neutral GoalInterpretation for goal='$clean'") } catch (_: Throwable) {}

        if (clean.isBlank()) {
            return GoalInterpretation(
                rawGoal = clean,
                requestedOutcome = "Clarification required",
                isAmbiguous = true,
                clarificationQuestion = "Could you please specify what task or action you would like me to perform?"
            )
        }

        // Semantic entity extraction (quoted strings or targets) without natural language intent classification
        val entities = mutableListOf<String>()
        val quoteMatches = Regex("\"([^\"]+)\"|'([^']+)'").findAll(clean)
        quoteMatches.forEach { match ->
            val e = match.groupValues[1].ifBlank { match.groupValues[2] }
            if (e.isNotBlank()) entities.add(e)
        }

        return GoalInterpretation(
            rawGoal = clean,
            objectiveType = "GENERAL",
            requestedOutcome = clean,
            targetEntities = entities.ifEmpty { listOf(clean) },
            constraints = emptyList(),
            desiredFinalState = "Observable environment state established to fulfill: $clean",
            desiredInformation = "Empirical evidence extracted answering goal: $clean",
            successConditions = listOf("Goal '$clean' verified by empirical environment evidence"),
            isAmbiguous = false,
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
            expectedOutcomeSummary = if (isInfo) "Verify information retrieved for '${interp.rawGoal}'" else "Verify observable state satisfied for '${interp.rawGoal}'",
            desiredState = interp.desiredFinalState,
            desiredInformation = interp.desiredInformation,
            targetEntities = interp.targetEntities.ifEmpty { listOf(interp.rawGoal) },
            constraints = interp.constraints,
            isAmbiguous = interp.isAmbiguous,
            clarificationQuestion = interp.clarificationQuestion
        )
    }

    fun derivePostcondition(goal: String): ExpectedPostcondition {
        val interp = createInitialInterpretation(goal)
        return derivePostconditionFromInterpretation(interp)
    }

    fun derivePostconditionFromInterpretation(interp: GoalInterpretation): ExpectedPostcondition {
        val isInfo = interp.objectiveType.equals("INFORMATION_RETRIEVAL", ignoreCase = true)
        val summaryStr = if (isInfo) "Verify information retrieved for '${interp.rawGoal}'" else "Verify observable state satisfied for '${interp.rawGoal}'"
        return ExpectedPostcondition(
            summary = summaryStr,
            desiredState = interp.desiredFinalState,
            desiredInformation = interp.desiredInformation,
            desiredEnvironmentCondition = if (isInfo) interp.desiredInformation else interp.desiredFinalState,
            targetEntities = interp.targetEntities.ifEmpty { listOf(interp.rawGoal) },
            constraints = interp.constraints,
            successConditions = interp.successConditions
        )
    }
}
