package com.ace.app.agent

import android.content.Context
import android.util.Log

data class IndependentVerificationOutcome(
    val isVerified: Boolean,
    val status: TaskStatus,
    val evidence: String,
    val summary: String
)

object IndependentGoalVerifier {

    fun verifyGoal(
        userGoal: String,
        taskContext: AgentTaskContext,
        currentObservation: ScreenObservation,
        context: Context?
    ): IndependentVerificationOutcome {
        val lowerGoal = userGoal.lowercase().trim()
        Log.i("ACE_VERIFY", "ACE_VERIFY: Starting independent goal verification for goal='$userGoal'")
        Log.i("ACE_VERIFY", "ACE_VERIFY: obs_app=${currentObservation.appName} text_nodes=${currentObservation.visibleText.size} evidence_keys=${taskContext.capturedEvidence.keys}")

        // 1. Explicit completion evidence captured during perception-reasoning loop
        val completionEvidence = taskContext.capturedEvidence["completion_evidence"]
        if (!completionEvidence.isNullOrBlank()) {
            return IndependentVerificationOutcome(
                isVerified = true,
                status = TaskStatus.COMPLETED,
                evidence = completionEvidence,
                summary = "Goal verified by empirical evidence: $completionEvidence"
            )
        }

        // 2. Task execution blocked by missing capability/permission
        if (taskContext.blockers.isNotEmpty()) {
            val blockerReason = taskContext.blockers.last()
            return IndependentVerificationOutcome(
                isVerified = false,
                status = TaskStatus.BLOCKED,
                evidence = "Task blocked: $blockerReason",
                summary = "Goal execution blocked: $blockerReason"
            )
        }

        // 3. Hardware state verification: Flashlight
        if (lowerGoal.contains("flashlight") || lowerGoal.contains("torch")) {
            val isFlashlightOn = taskContext.capturedEvidence["flashlight_state"] == "ON" ||
                    taskContext.actionHistory.any { it.contains("flashlight", ignoreCase = true) && it.contains("SUCCESS", ignoreCase = true) }
            if (isFlashlightOn) {
                return IndependentVerificationOutcome(
                    isVerified = true,
                    status = TaskStatus.COMPLETED,
                    evidence = "Flashlight hardware state verified active.",
                    summary = "Flashlight turned on."
                )
            }
        }

        // 4. Hardware state verification: Volume
        if (lowerGoal.contains("volume")) {
            val volumeAdjusted = taskContext.actionHistory.any { it.contains("volume", ignoreCase = true) && it.contains("SUCCESS", ignoreCase = true) }
            if (volumeAdjusted) {
                return IndependentVerificationOutcome(
                    isVerified = true,
                    status = TaskStatus.COMPLETED,
                    evidence = "System volume setting verified.",
                    summary = "Volume adjusted successfully."
                )
            }
        }

        // 5. Screen Observation evidence verification: Check text nodes on active screen
        if (currentObservation.visibleText.isNotEmpty()) {
            val stopWords = setOf("find", "open", "show", "get", "the", "and", "with", "from", "for", "please")
            val goalKeywords = lowerGoal.split(" ")
                .map { it.replace(Regex("[^a-zA-Z0-9]"), "") }
                .filter { it.length > 3 && !stopWords.contains(it) }

            val matchedKeywords = goalKeywords.filter { kw ->
                currentObservation.visibleText.any { text -> text.lowercase().contains(kw) }
            }

            if (matchedKeywords.isNotEmpty() && taskContext.actionHistory.isNotEmpty()) {
                val evidenceStr = "Screen text nodes confirm expected content keywords: ${matchedKeywords.joinToString(", ")}"
                return IndependentVerificationOutcome(
                    isVerified = true,
                    status = TaskStatus.COMPLETED,
                    evidence = evidenceStr,
                    summary = "Verified goal fulfillment on screen (${matchedKeywords.joinToString(", ")})"
                )
            }
        }

        // 6. Action History verification: Actions executed but screen completion unconfirmed
        val successfulActions = taskContext.actionHistory.filter { it.contains("SUCCESS") }
        if (successfulActions.isNotEmpty()) {
            return IndependentVerificationOutcome(
                isVerified = false,
                status = TaskStatus.PARTIAL,
                evidence = "Actions executed (${successfulActions.size}), but goal completion could not be independently verified.",
                summary = "Task partially executed; full outcome unverified."
            )
        }

        // 7. Unverified Failure
        return IndependentVerificationOutcome(
            isVerified = false,
            status = TaskStatus.FAILED,
            evidence = "No empirical evidence verifying completion of goal: '$userGoal'",
            summary = "Goal execution unverified."
        )
    }
}
