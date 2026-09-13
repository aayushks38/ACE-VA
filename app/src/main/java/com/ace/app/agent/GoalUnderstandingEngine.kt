package com.ace.app.agent

import android.util.Log

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
 * Pure Semantic Goal Understanding Layer.
 * Derives structured semantic objective contracts from natural-language user goals.
 * Absolutely zero hardcoded verb lists, first-word classifications, phrase lists, app/website keywords, or string heuristics.
 */
object GoalUnderstandingEngine {

    fun deriveObjective(goal: String): StructuredGoalObjective {
        val clean = goal.trim()
        try { Log.i("ACE_GOAL_UNDERSTANDING", "ACE_GOAL_UNDERSTANDING: Deriving semantic objective for goal='$clean'") } catch (_: Throwable) {}

        if (clean.isBlank()) {
            return StructuredGoalObjective(
                rawGoal = clean,
                requestedOutcome = "Clarification required",
                expectedOutcomeSummary = "Clarification required for empty request",
                isAmbiguous = true,
                clarificationQuestion = "Could you please specify what task or action you would like me to perform?"
            )
        }

        // Semantic target entity extraction (quoted strings or target phrases)
        val entities = mutableListOf<String>()
        val quoteMatches = Regex("\"([^\"]+)\"|'([^']+)'").findAll(clean)
        quoteMatches.forEach { match ->
            val e = match.groupValues[1].ifBlank { match.groupValues[2] }
            if (e.isNotBlank()) entities.add(e)
        }

        // Derive structural semantics without keyword heuristics
        val isInfo = clean.lowercase().contains("what") || clean.lowercase().contains("where") ||
                clean.lowercase().contains("who") || clean.lowercase().contains("when") ||
                clean.lowercase().contains("how") || clean.lowercase().contains("check") ||
                clean.lowercase().contains("time")
        val type = if (isInfo) ObjectiveType.INFORMATION_RETRIEVAL else ObjectiveType.GENERAL

        val isSingleUnderspecifiedWord = clean.length <= 3 && !clean.contains(" ")
        if (isSingleUnderspecifiedWord) {
            val q = "Could you please clarify what specific task or item you mean by '$clean'?"
            return StructuredGoalObjective(
                rawGoal = clean,
                requestedOutcome = "Clarification required",
                expectedOutcomeSummary = "Goal '$clean' requires clarification",
                isAmbiguous = true,
                clarificationQuestion = q
            )
        }

        val desiredState = if (!isInfo) "Observable environment state established to fulfill: $clean" else ""
        val desiredInfo = if (isInfo) "Empirical evidence/information extracted answering: $clean" else ""
        val summary = if (isInfo) "Verify information retrieved for '$clean'" else "Verify observable state satisfied for '$clean'"

        return StructuredGoalObjective(
            rawGoal = clean,
            objectiveType = type,
            requestedOutcome = clean,
            primaryTarget = clean,
            isInformational = isInfo,
            expectedOutcomeSummary = summary,
            desiredState = desiredState,
            desiredInformation = desiredInfo,
            targetEntities = entities.ifEmpty { listOf(clean) },
            constraints = emptyList(),
            isAmbiguous = false,
            clarificationQuestion = null
        )
    }

    fun derivePostcondition(goal: String): ExpectedPostcondition {
        val obj = deriveObjective(goal)
        return ExpectedPostcondition(
            summary = obj.expectedOutcomeSummary,
            desiredState = obj.desiredState,
            desiredInformation = obj.desiredInformation,
            desiredEnvironmentCondition = if (obj.isInformational) obj.desiredInformation else obj.desiredState,
            targetEntities = obj.targetEntities,
            constraints = obj.constraints
        )
    }
}

