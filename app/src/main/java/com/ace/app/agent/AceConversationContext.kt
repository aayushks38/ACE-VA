package com.ace.app.agent

import android.util.Log

data class ConversationalSessionState(
    val sessionGenerationId: Long = 0L,
    val pendingGoal: String? = null,
    val clarificationQuestion: String? = null,
    val userClarificationAnswer: String? = null,
    val activeTaskGenerationId: Long = 0L,
    val isAwaitingClarification: Boolean = false,
    val isCancelledOrReplaced: Boolean = false
)

object AceConversationContext {
    private var lastGoal: String? = null
    private var lastResponse: String? = null
    private var activeApp: String? = null
    private var activeTask: AgentTask? = null

    private var sessionState = ConversationalSessionState()

    @Synchronized
    fun getConversationalState(): ConversationalSessionState = sessionState

    @Synchronized
    fun startNewSession(sessionGenId: Long): ConversationalSessionState {
        val oldState = sessionState
        if (oldState.isAwaitingClarification && !oldState.pendingGoal.isNullOrBlank()) {
            sessionState = ConversationalSessionState(
                sessionGenerationId = sessionGenId,
                pendingGoal = oldState.pendingGoal,
                clarificationQuestion = oldState.clarificationQuestion,
                activeTaskGenerationId = oldState.activeTaskGenerationId,
                isAwaitingClarification = true,
                isCancelledOrReplaced = false
            )
        } else {
            sessionState = ConversationalSessionState(
                sessionGenerationId = sessionGenId,
                isAwaitingClarification = false,
                isCancelledOrReplaced = false
            )
        }
        try { Log.i("ACE_CONTEXT", "ACE_CONTEXT: startNewSession gen=$sessionGenId awaitingClarification=${sessionState.isAwaitingClarification}") } catch (_: Throwable) {}
        return sessionState
    }

    @Synchronized
    fun update(goal: String, response: String? = null, app: String? = null, task: AgentTask? = null) {
        lastGoal = goal
        if (!response.isNullOrBlank()) lastResponse = response
        if (!app.isNullOrBlank()) activeApp = app
        if (task != null) activeTask = task
        try { Log.i("ACE_CONTEXT", "ACE_CONTEXT: context updated goal=\"$goal\" app=\"${activeApp ?: "none"}\"") } catch (_: Throwable) {}
    }

    @Synchronized
    fun setPendingClarification(goal: String, question: String, taskGenId: Long = 0L) {
        sessionState = sessionState.copy(
            pendingGoal = goal,
            clarificationQuestion = question,
            activeTaskGenerationId = taskGenId,
            isAwaitingClarification = true,
            isCancelledOrReplaced = false
        )
        try { Log.i("ACE_CONTEXT", "ACE_CONTEXT: pending clarification set goal=\"$goal\" question=\"$question\"") } catch (_: Throwable) {}
    }

    @Synchronized
    fun consumePendingClarification(userAnswer: String): String? {
        val state = sessionState
        if (!state.isAwaitingClarification || state.pendingGoal.isNullOrBlank()) {
            return null
        }
        val origGoal = state.pendingGoal
        val question = state.clarificationQuestion.orEmpty()

        sessionState = state.copy(
            userClarificationAnswer = userAnswer,
            isAwaitingClarification = false
        )

        try { Log.i("ACE_CONTEXT", "ACE_CONTEXT: consumed clarification origGoal=\"$origGoal\" question=\"$question\" answer=\"$userAnswer\"") } catch (_: Throwable) {}

        return if (question.isNotBlank()) {
            "Original Goal: $origGoal. Clarification requested: '$question'. User provided answer: '$userAnswer'"
        } else {
            "Original Goal: $origGoal. User provided answer: '$userAnswer'"
        }
    }

    @Synchronized
    fun consumePendingClarification(): Pair<String, String>? {
        val state = sessionState
        if (!state.isAwaitingClarification || state.pendingGoal.isNullOrBlank()) {
            return null
        }
        val g = state.pendingGoal
        val q = state.clarificationQuestion.orEmpty()
        sessionState = state.copy(isAwaitingClarification = false)
        return Pair(g, q)
    }

    @Synchronized
    fun clearPendingClarification() {
        sessionState = sessionState.copy(
            pendingGoal = null,
            clarificationQuestion = null,
            userClarificationAnswer = null,
            isAwaitingClarification = false
        )
    }

    @Synchronized
    fun clearSession() {
        lastGoal = null
        lastResponse = null
        activeApp = null
        activeTask = null
        sessionState = ConversationalSessionState(isCancelledOrReplaced = true)
        try { Log.i("ACE_CONTEXT", "ACE_CONTEXT: task session context cleared completely") } catch (_: Throwable) {}
    }

    fun clearCancelledContext() {
        clearSession()
    }

    fun getActiveApp(): String? = activeApp
    fun getLastGoal(): String? = lastGoal
    fun getLastResponse(): String? = lastResponse
    fun getActiveTask(): AgentTask? = activeTask
}
