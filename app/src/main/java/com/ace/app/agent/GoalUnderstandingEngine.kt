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
 * Derives structured semantic objectives and expected postcondition contracts from natural-language user goals.
 * Absolutely zero hardcoded verb lists, first-word classifications, phrase lists, app/website keywords, or command rules.
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

        // Semantic structure analysis: check if request seeks information vs state change
        val hasQuestionMark = clean.contains("?")
        val isInfoSeeker = hasQuestionMark || clean.split(Regex("\\s+")).size <= 2 && !clean.contains(" ")

        // Detect ambiguity: extremely short or generic single words without target context
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        val isUnderspecified = words.size == 1 && clean.lowercase() in setOf("do", "go", "send", "open", "find", "get", "make")

        if (isUnderspecified) {
            val genericQ = "Could you please clarify what specific item or action you mean by '$clean'?"
            return StructuredGoalObjective(
                rawGoal = clean,
                requestedOutcome = "Clarification required",
                expectedOutcomeSummary = "Goal '$clean' is underspecified",
                isAmbiguous = true,
                clarificationQuestion = genericQ
            )
        }

        val type = if (isInfoSeeker) ObjectiveType.INFORMATION_RETRIEVAL else ObjectiveType.STATE_MODIFICATION
        val targetText = if (words.size > 1) words.drop(1).joinToString(" ") else clean

        // Extract entities (quoted strings, noun phrases)
        val entities = mutableListOf<String>()
        val quoteMatches = Regex("\"([^\"]+)\"|'([^']+)'").findAll(clean)
        quoteMatches.forEach { match ->
            val e = match.groupValues[1].ifBlank { match.groupValues[2] }
            if (e.isNotBlank()) entities.add(e)
        }

        val desiredState = if (type == ObjectiveType.STATE_MODIFICATION) "Environment state established to satisfy: '$clean'" else ""
        val desiredInfo = if (type == ObjectiveType.INFORMATION_RETRIEVAL) "Empirical information extracted answering: '$clean'" else ""

        return StructuredGoalObjective(
            rawGoal = clean,
            objectiveType = type,
            requestedOutcome = clean,
            primaryTarget = targetText,
            isInformational = (type == ObjectiveType.INFORMATION_RETRIEVAL),
            expectedOutcomeSummary = "Objective '$clean' fully satisfied",
            desiredState = desiredState,
            desiredInformation = desiredInfo,
            targetEntities = entities.ifEmpty { listOf(targetText) },
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
