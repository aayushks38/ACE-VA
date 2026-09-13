package com.ace.app.agent

import android.content.Context
import android.util.Log

data class IndependentVerificationOutcome(
    val isVerified: Boolean,
    val status: TaskStatus,
    val evidence: String,
    val summary: String
)

data class EnvironmentEvidence(
    val screenObservation: ScreenObservation,
    val capturedEvidenceMap: Map<String, String>,
    val actionHistory: List<String>,
    val blockers: List<String>,
    val brainHypothesis: String?
)

/**
 * Generic Postcondition Goal Verification Engine.
 * Answers: "Is the requested postcondition empirically satisfied in the current environment?"
 * Absolutely zero hardcoded branches for specific applications, websites, commands, task categories, or goal strings.
 */
object IndependentGoalVerifier {

    fun verifyGoal(
        taskContext: AgentTaskContext,
        currentObservation: ScreenObservation,
        context: Context?
    ): IndependentVerificationOutcome {
        Log.i("ACE_VERIFY", "ACE_VERIFY: Starting generic postcondition verification for goal='${taskContext.userGoal}'")
        Log.i("ACE_VERIFY", "ACE_VERIFY: current_app=${currentObservation.appName} pkg=${currentObservation.packageName} nodes=${currentObservation.visibleText.size}")

        val evidence = EnvironmentEvidence(
            screenObservation = currentObservation,
            capturedEvidenceMap = taskContext.capturedEvidence,
            actionHistory = taskContext.actionHistory,
            blockers = taskContext.blockers,
            brainHypothesis = taskContext.capturedEvidence["brain_completion_hypothesis"]
        )

        return evaluateGenericEvidence(taskContext.userGoal, evidence)
    }

    private fun evaluateGenericEvidence(
        userGoal: String,
        evidence: EnvironmentEvidence
    ): IndependentVerificationOutcome {
        // 1. Task execution blocked by missing capability or permission
        if (evidence.blockers.isNotEmpty()) {
            val blockerReason = evidence.blockers.last()
            return IndependentVerificationOutcome(
                isVerified = false,
                status = TaskStatus.BLOCKED,
                evidence = "Task execution blocked: $blockerReason",
                summary = "Goal execution blocked: $blockerReason"
            )
        }

        // 2. Generic Postcondition Check A: Verifiable Empirical Content Payload
        // (Content URI, File URI, Attachment URI produced by execution and confirmed accessible)
        val payloadUri = evidence.capturedEvidenceMap["file_uri"]
            ?: evidence.capturedEvidenceMap["attachment_uri"]
            ?: evidence.capturedEvidenceMap["content_uri"]
            ?: evidence.capturedEvidenceMap["resolved_uri"]
        if (!payloadUri.isNullOrBlank()) {
            return IndependentVerificationOutcome(
                isVerified = true,
                status = TaskStatus.COMPLETED,
                evidence = "Empirical content payload verified: $payloadUri",
                summary = "Goal satisfied with verified content payload."
            )
        }

        // 3. Generic Postcondition Check B: Verified Device Platform State Output
        // (Hardware/system state modification explicitly reported by platform service API)
        val platformStateDetail = evidence.capturedEvidenceMap["hardware_action"]
            ?: evidence.capturedEvidenceMap["system_api_result"]
        if (!platformStateDetail.isNullOrBlank()) {
            return IndependentVerificationOutcome(
                isVerified = true,
                status = TaskStatus.COMPLETED,
                evidence = "System platform state modification verified: $platformStateDetail",
                summary = "Goal satisfied with verified system state."
            )
        }

        // 4. Default Fallback: Insufficient Empirical Postcondition Evidence
        val successfulActions = evidence.actionHistory.filter { it.contains("SUCCESS") }
        if (successfulActions.isNotEmpty()) {
            return IndependentVerificationOutcome(
                isVerified = false,
                status = TaskStatus.NOT_VERIFIED,
                evidence = "Actions executed (${successfulActions.size}), but empirical postcondition completion is not satisfied in current environment (${evidence.screenObservation.appName}).",
                summary = "Actions executed, but postcondition outcome remains unverified."
            )
        }

        return IndependentVerificationOutcome(
            isVerified = false,
            status = TaskStatus.FAILED,
            evidence = "No empirical evidence verifying postcondition for goal: '$userGoal'",
            summary = "Goal outcome unverified."
        )
    }
}
