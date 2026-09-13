package com.ace.app.agent

import android.util.Log

data class SpecificationCheck(
    val isSpecified: Boolean,
    val clarificationQuestion: String? = null,
    val reason: String = ""
)

/**
 * Conversational Intelligence Layer: Goal Specification Filter.
 * Evaluates whether a user's natural language goal is sufficiently specified before planning or executing.
 * Prevents guessing, false entity creation, or executing unrelated actions on underspecified commands.
 */
object GoalSpecificationFilter {

    private const val TAG = "ACE_SPECIFICATION"

    fun evaluate(goal: String, hasActiveContext: Boolean = false): SpecificationCheck {
        val lower = goal.lowercase().trim()
            .replace(Regex("""[\?!,\.]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (lower.isBlank()) {
            return SpecificationCheck(false, "Could you tell me what you'd like me to do?", "blank_goal")
        }

        // 1. Underspecified "find me the latest" pattern
        if (lower == "find me the latest" || lower == "find the latest" || lower == "show me the latest" || lower == "get the latest" || lower == "find latest" || lower == "the latest") {
            Log.w(TAG, "ACE_SPECIFICATION: UNDERSPECIFIED_GOAL goal=\"$goal\" reason=dangling_latest")
            return SpecificationCheck(
                isSpecified = false,
                clarificationQuestion = "Sure — latest what?",
                reason = "dangling_latest"
            )
        }

        // 2. Unresolved pronouns ("send it", "share it") without prior conversation context
        if (!hasActiveContext) {
            if (lower == "send it" || lower == "share it" || lower.startsWith("send it to ") || lower.startsWith("share it to ")) {
                val recipientMatch = Regex("""to\s+([a-z0-9\s]+)$""", RegexOption.IGNORE_CASE).find(lower)
                val rawRecipient = recipientMatch?.groupValues?.get(1)?.trim()
                val recipient = if (!rawRecipient.isNullOrBlank()) rawRecipient.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } else null
                val question = if (!recipient.isNullOrBlank()) {
                    "Which file or photo would you like me to send to $recipient?"
                } else {
                    "Which file or photo would you like me to send?"
                }
                Log.w(TAG, "ACE_SPECIFICATION: UNDERSPECIFIED_GOAL goal=\"$goal\" reason=unresolved_pronoun")
                return SpecificationCheck(
                    isSpecified = false,
                    clarificationQuestion = question,
                    reason = "unresolved_pronoun"
                )
            }

            if (lower == "open it" || lower == "show it" || lower == "play it" || lower == "delete it") {
                val actionVerb = lower.substringBefore(" ")
                Log.w(TAG, "ACE_SPECIFICATION: UNDERSPECIFIED_GOAL goal=\"$goal\" reason=unresolved_pronoun")
                return SpecificationCheck(
                    isSpecified = false,
                    clarificationQuestion = "Could you specify what you'd like me to $actionVerb?",
                    reason = "unresolved_pronoun"
                )
            }
        }

        // 3. Ambiguous booking/order without target entity
        if (lower == "book it" || lower == "order it" || lower == "cancel it") {
            val actionVerb = lower.substringBefore(" ")
            Log.w(TAG, "ACE_SPECIFICATION: UNDERSPECIFIED_GOAL goal=\"$goal\" reason=ambiguous_action")
            return SpecificationCheck(
                isSpecified = false,
                clarificationQuestion = "Could you clarify what you'd like me to $actionVerb?",
                reason = "ambiguous_action"
            )
        }

        Log.i(TAG, "ACE_SPECIFICATION: GOAL_SPECIFIED goal=\"$goal\"")
        return SpecificationCheck(isSpecified = true)
    }
}
