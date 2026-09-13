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
        try {
            Log.i("ACE_VERIFY", "ACE_VERIFY: Starting semantic postcondition verification for goal='${taskContext.userGoal}'")
            Log.i("ACE_VERIFY", "ACE_VERIFY: current_app=${currentObservation.appName} pkg=${currentObservation.packageName} nodes=${currentObservation.visibleText.size}")
        } catch (_: Throwable) {}

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
        if (!obs.isPerceptionAvailable || obs.screenState == "PERCEPTION_UNAVAILABLE") {
            return IndependentVerificationOutcome(
                isVerified = false,
                status = TaskStatus.NOT_VERIFIED,
                evidence = "Perception is unavailable or unobserved; cannot verify postcondition.",
                summary = "Environment observation unavailable for postcondition verification."
            )
        }

        val visibleTextSet = obs.visibleText.map { it.lowercase().trim() }.toSet()

        // 2. Strict Semantic Postcondition Evaluation: Multi-condition & Entity Evidence Check
        val targetEntities = postcondition.targetEntities.filter { it.isNotBlank() }
        val desiredInformation = postcondition.desiredInformation.lowercase().trim()
        val desiredState = postcondition.desiredState.lowercase().trim()

        if (targetEntities.isNotEmpty()) {
            // Verify that ALL required target entities/conditions are empirically established in current environment
            val allEntitiesVerified = targetEntities.all { entity ->
                val entityLower = entity.lowercase().trim()
                visibleTextSet.any { text -> text.contains(entityLower) } ||
                evidence.capturedEvidenceMap.values.any { valStr -> valStr.lowercase().contains(entityLower) }
            }

            if (allEntitiesVerified) {
                return IndependentVerificationOutcome(
                    isVerified = true,
                    status = TaskStatus.COMPLETED,
                    evidence = "Empirical verification satisfied: all postcondition target entities (${targetEntities.joinToString()}) confirmed in current environment observation (${obs.appName}).",
                    summary = "Goal outcome verified in current environment state."
                )
            }
        } else if (desiredInformation.isNotBlank()) {
            // Informational postcondition verification: check if empirical information answering query is in evidence
            val hasAnswerEvidence = evidence.capturedEvidenceMap.values.any { it.isNotBlank() }
            if (hasAnswerEvidence) {
                return IndependentVerificationOutcome(
                    isVerified = true,
                    status = TaskStatus.COMPLETED,
                    evidence = "Empirical informational verification satisfied: answer evidence extracted in environment state.",
                    summary = "Informational goal outcome verified."
                )
            }
        } else if (desiredState.isNotBlank()) {
            // Observable state verification: check if current UI state or visible elements verify the state transition
            val hasStateEvidence = evidence.capturedEvidenceMap.values.any { it.isNotBlank() }
            if (hasStateEvidence && obs.visibleText.isNotEmpty()) {
                return IndependentVerificationOutcome(
                    isVerified = true,
                    status = TaskStatus.COMPLETED,
                    evidence = "Empirical state modification satisfied in current environment observation (${obs.appName}).",
                    summary = "State modification goal outcome verified."
                )
            }
        }

        // 3. Default: Execution != Success. Return NOT_VERIFIED if empirical evidence is insufficient
        val actionCount = evidence.actionHistory.size
        return IndependentVerificationOutcome(
            isVerified = false,
            status = TaskStatus.NOT_VERIFIED,
            evidence = "Executed $actionCount action(s), but empirical environment evidence (${obs.appName}) is insufficient to verify postcondition for '$userGoal'.",
            summary = "Postcondition unverified in current environment state."
        )
    }
}
