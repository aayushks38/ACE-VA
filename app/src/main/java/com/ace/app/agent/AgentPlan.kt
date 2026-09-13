package com.ace.app.agent

data class AgentPlan(
    val userGoal: String,
    val intent: String,
    val channel: CommunicationChannel = CommunicationChannel.PHONE,
    val targetEntity: String? = null,
    val steps: List<TaskStep>,
    val requiresApproval: Boolean = false,
    val clarificationNeeded: Boolean = false,
    val clarificationQuestion: String? = null,
    val taskGenerationId: Long = 0L
)
