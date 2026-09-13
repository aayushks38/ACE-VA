package com.ace.app.agent

/**
 * Generic semantic postcondition contract describing what must be true when a goal is achieved.
 * Decoupled from specific applications, websites, or workflows.
 */
data class ExpectedPostcondition(
    val summary: String = "",
    val desiredState: String = "",
    val desiredInformation: String = "",
    val desiredEnvironmentCondition: String = "",
    val targetEntities: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val successConditions: List<String> = emptyList()
)

/**
 * Transient structured task context maintained during autonomous computer use.
 * Authoritative state store for user goal, expected postconditions, action history,
 * environment observations, evidence, and verification tracking.
 * Decoupled from specific applications, websites, or workflows.
 */
data class AgentTaskContext(
    var userGoal: String,
    var expectedPostcondition: ExpectedPostcondition = ExpectedPostcondition(),
    val generationId: Long = 0L,
    val discoveredEnvironment: String = "UNKNOWN",
    val actionHistory: MutableList<String> = mutableListOf(),
    val capturedEvidence: MutableMap<String, String> = mutableMapOf(),
    val blockers: MutableList<String> = mutableListOf(),
    val userClarifications: MutableMap<String, String> = mutableMapOf(),
    var unverifiedHypothesisAttempts: Int = 0,
    val startTimeMs: Long = System.currentTimeMillis()
) {
    fun formatCompactTaskMemory(): String = buildString {
        if (actionHistory.isNotEmpty()) {
            val historySnippet = if (actionHistory.size <= 8) {
                actionHistory.joinToString(" -> ")
            } else {
                "(${actionHistory.size - 6} earlier actions) ... " + actionHistory.takeLast(6).joinToString(" -> ")
            }
            append("Action History: ").append(historySnippet).append("\n")
        }
        if (blockers.isNotEmpty()) {
            append("Blockers/Failures: ").append(blockers.takeLast(4).joinToString("; ")).append("\n")
        }
        if (userClarifications.isNotEmpty()) {
            val clarifs = userClarifications.entries.joinToString("; ") { "${it.key}='${it.value}'" }
            append("User Clarifications: ").append(clarifs).append("\n")
        }
        val discoveries = capturedEvidence.filterKeys { it != "brain_completion_hypothesis" }
        if (discoveries.isNotEmpty()) {
            val discStr = discoveries.entries.take(5).joinToString("; ") { "${it.key}: ${it.value}" }
            append("Discoveries: ").append(discStr).append("\n")
        }
    }
}

