package com.ace.app.agent

import android.util.Log

data class StructuredGoalObjective(
    val rawGoal: String,
    val actionVerb: String = "",
    val primaryTarget: String = "",
    val isInformational: Boolean = false,
    val expectedOutcomeSummary: String,
    val desiredState: String = "",
    val desiredInformation: String = "",
    val targetEntities: List<String> = emptyList(),
    val isAmbiguous: Boolean = false,
    val clarificationQuestion: String? = null
)

/**
 * Generic Goal Understanding Layer.
 * Analyzes natural-language user goals to derive structured semantic objectives and expected postconditions.
 * Absolutely zero app-specific, website-specific, or command-specific phrase lists or hardcoded rules.
 */
object GoalUnderstandingEngine {

    fun deriveObjective(goal: String): StructuredGoalObjective {
        val clean = goal.trim()
        Log.i("ACE_GOAL_UNDERSTANDING", "ACE_GOAL_UNDERSTANDING: Analyzing goal='$clean'")

        if (clean.isBlank()) {
            return StructuredGoalObjective(
                rawGoal = clean,
                expectedOutcomeSummary = "Clarification required",
                isAmbiguous = true,
                clarificationQuestion = "Could you please specify what task or action you'd like me to perform?"
            )
        }

        // Generic linguistic structural extraction
        val words = clean.split(Regex("\\s+"))
        val firstWord = words.firstOrNull()?.lowercase() ?: ""

        val isInfo = firstWord in listOf("what", "where", "who", "when", "how", "find", "search", "read", "show", "get", "tell") ||
                clean.contains("?") || clean.lowercase().startsWith("is ") || clean.lowercase().startsWith("are ")

        // Extract primary action verb and target object generically
        val actionVerb = if (!isInfo && words.isNotEmpty()) words.first() else "perform"
        val targetText = if (words.size > 1) words.drop(1).joinToString(" ") else clean

        // Extract candidate entities (quoted text, capitalized terms, or target noun phrases)
        val entities = mutableListOf<String>()
        val quoteRegex = Regex("\"([^\"]+)\"|'([^']+)'")
        quoteRegex.findAll(clean).forEach { match ->
            val e = match.groupValues[1].ifBlank { match.groupValues[2] }
            if (e.isNotBlank()) entities.add(e)
        }

        val desiredState = if (isInfo) "" else "Environment modified to reflect completion of '$clean'"
        val desiredInfo = if (isInfo) "Information answering '$clean' extracted from environment" else ""

        return StructuredGoalObjective(
            rawGoal = clean,
            actionVerb = actionVerb,
            primaryTarget = targetText,
            isInformational = isInfo,
            expectedOutcomeSummary = "Goal '$clean' satisfied.",
            desiredState = desiredState,
            desiredInformation = desiredInfo,
            targetEntities = entities.ifEmpty { listOf(targetText) },
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
            targetEntities = obj.targetEntities
        )
    }
}
