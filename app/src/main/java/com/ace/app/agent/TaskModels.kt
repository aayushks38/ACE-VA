package com.ace.app.agent

import java.util.UUID

enum class TaskCategory {
    COMMUNICATION,
    DOCUMENT,
    RESEARCH,
    SYSTEM,
    TRAVEL,
    GENERAL
}

enum class ActionResultStatus {
    SUCCESS,
    FAILED,
    UNSUPPORTED,
    NEEDS_PERMISSION,
    NEEDS_USER_ACTION,
    PARTIAL
}

enum class ExecutionMode {
    DETERMINISTIC,  // Deterministic capability: flashlight, battery, call, etc.
    BRAIN,          // Brain-dependent: requires Gemma interpretation
    BRAIN_UNAVAILABLE
}

enum class TaskStatus {
    PLANNING,
    READY,
    WAITING_FOR_APPROVAL,
    RUNNING,
    VERIFYING,
    COMPLETED,
    HANDOFF_COMPLETED,
    AWAITING_USER_ACTION,
    PARTIAL,
    FAILED,
    WAITING_FOR_USER,
    CANCELLED
}

fun TaskStatus.isTerminalForAce(): Boolean {
    return this == TaskStatus.COMPLETED ||
           this == TaskStatus.HANDOFF_COMPLETED ||
           this == TaskStatus.AWAITING_USER_ACTION ||
           this == TaskStatus.FAILED ||
           this == TaskStatus.CANCELLED
}


data class ApprovalDetails(
    val title: String,
    val description: String,
    val monetaryCost: String? = null,
    val riskLevel: String = "Medium",
    val warningMessage: String? = null
)

data class TaskStep(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val capabilityId: String = "text_reasoning",
    val dependsOnStepIds: List<String> = emptyList(),
    val inputParams: Map<String, String> = emptyMap(),
    val isParallel: Boolean = false,
    val isComplete: Boolean = false,
    val isRunning: Boolean = false,
    val isVerified: Boolean = false,
    val requiresApproval: Boolean = false,
    val output: String? = null,
    val outputData: Map<String, String> = emptyMap()
)

data class ToolExecutionResult(
    val isSuccess: Boolean,
    val message: String,
    val outputData: Map<String, String> = emptyMap(),
    val verificationDetails: String? = null
)

data class GoalRequirement(
    val id: String,
    val description: String,
    val isVerified: Boolean = false,
    val verificationDetails: String = "Pending verification"
)

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val category: TaskCategory = TaskCategory.GENERAL,
    val status: TaskStatus = TaskStatus.PLANNING,
    val summary: String,
    val steps: List<TaskStep>,
    val requirements: List<GoalRequirement> = emptyList(),
    val requiresApproval: Boolean = false,
    val approvalDetails: ApprovalDetails? = null,
    val attachmentName: String? = null,
    val attachmentUri: String? = null,
    val verificationResult: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
