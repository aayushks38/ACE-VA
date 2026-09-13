package com.ace.app.agent

/**
 * Generic semantic postcondition contract describing what must be true when a goal is achieved.
 * Decoupled from specific applications, websites, or workflows.
 */
data class ExpectedPostcondition(
    val summary: String = "",
    val desiredState: String = "",
    val desiredInformation: String = "",
    val desiredEnvironmentCondition: String = ""
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
)
