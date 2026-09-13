package com.ace.app.agent

/**
 * Transient structured task context maintained during autonomous computer use.
 * Stores environment state, intermediate findings, action history, and verification evidence.
 * Decoupled from specific applications or websites.
 */
data class AgentTaskContext(
    var userGoal: String,
    val generationId: Long = 0L,
    val discoveredEnvironment: String = "UNKNOWN",
    val actionHistory: MutableList<String> = mutableListOf(),
    val capturedEvidence: MutableMap<String, String> = mutableMapOf(),
    val blockers: MutableList<String> = mutableListOf(),
    val userClarifications: MutableMap<String, String> = mutableMapOf(),
    val startTimeMs: Long = System.currentTimeMillis()
)
