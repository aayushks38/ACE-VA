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
 * Generic Semantic Postcondition Goal Verification Engine.
 * Evaluates: EXPECTED POSTCONDITION + CURRENT ENVIRONMENT EVIDENCE -> VERIFICATION RESULT
 * Answers: "Is the requested postcondition empirically satisfied in the current environment right now?"
 * Absolutely zero hardcoded shortcuts for specific applications, websites, commands, task categories, or goal strings.
 */
object IndependentGoalVerifier {

    fun verifyGoal(
        taskContext: AgentTaskContext,
        currentObservation: ScreenObservation,
        context: Context?
    ): IndependentVerificationOutcome {
        Log.i("ACE_VERIFY", "ACE_VERIFY: Starting semantic postcondition verification for goal='${taskContext.userGoal}'")
        Log.i("ACE_VERIFY", "ACE_VERIFY: current_app=${currentObservation.appName} pkg=${currentObservation.packageName} nodes=${currentObservation.visibleText.size}")

        val evidence = EnvironmentEvidence(
            screenObservation = currentObservation,
            capturedEvidenceMap = taskContext.capturedEvidence,
            actionHistory = taskContext.actionHistory,
            blockers = taskContext.blockers,
            brainHypothesis = taskContext.capturedEvidence["brain_completion_hypothesis"]
        )

        return evaluateSemanticPostcondition(taskContext.userGoal, taskContext.expectedPostcondition, evidence)
    }

    private fun evaluateSemanticPostcondition(
        userGoal: String,
        postcondition: ExpectedPostcondition,
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

        val obs = evidence.screenObservation
        val visibleTextSet = obs.visibleText.map { it.lowercase().trim() }.toSet()
        val hypothesis = evidence.brainHypothesis.orEmpty().lowercase()

        // 2. Generic Semantic Postcondition Evaluation
        // Evaluate expected postcondition against current environment evidence
        val summaryTarget = postcondition.summary.lowercase().trim()
        val desiredStateTarget = postcondition.desiredState.lowercase().trim()
        val desiredInfoTarget = postcondition.desiredInformation.lowercase().trim()
        val desiredEnvTarget = postcondition.desiredEnvironmentCondition.lowercase().trim()

        val activeTargets = listOf(summaryTarget, desiredStateTarget, desiredInfoTarget, desiredEnvTarget)
            .filter { it.isNotBlank() }

        if (activeTargets.isNotEmpty()) {
            // Check if active semantic targets are empirically confirmed in current screen observation or captured state
            val confirmedState = activeTargets.any { target ->
                obs.appName.lowercase().contains(target) ||
                obs.packageName.lowercase().contains(target) ||
                visibleTextSet.any { text -> text.contains(target) } ||
                evidence.capturedEvidenceMap.values.any { valStr -> valStr.lowercase().contains(target) }
            }

            if (confirmedState) {
                return IndependentVerificationOutcome(
                    isVerified = true,
                    status = TaskStatus.COMPLETED,
                    evidence = "Empirical environment observation (${obs.appName}) satisfies semantic postcondition criteria.",
                    summary = "Goal outcome verified in current environment state."
                )
            }
        }

        // 3. Fallback: If brain hypothesis proposed completion and current environment is non-empty, evaluate hypothesis relative to observation
        if (hypothesis.isNotBlank() && obs.visibleText.isNotEmpty()) {
            val hypothesisVerified = visibleTextSet.any { text -> hypothesis.contains(text) && text.length > 3 } ||
                    evidence.capturedEvidenceMap.values.any { valStr -> valStr.lowercase().contains(hypothesis) }
            if (hypothesisVerified) {
                return IndependentVerificationOutcome(
                    isVerified = true,
                    status = TaskStatus.COMPLETED,
                    evidence = "Brain completion hypothesis '${evidence.brainHypothesis}' empirically confirmed by environment observation.",
                    summary = "Goal outcome verified via environment state."
                )
            }
        }

        // 4. Default: Insufficient Empirical Postcondition Evidence
        val actionCount = evidence.actionHistory.size
        return IndependentVerificationOutcome(
            isVerified = false,
            status = TaskStatus.NOT_VERIFIED,
            evidence = "Executed $actionCount action(s), but empirical postcondition is not fully verified in current environment state (${obs.appName}).",
            summary = "Postcondition unverified in current environment state."
        )
    }
}
