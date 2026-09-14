package com.ace.app.agent

import android.util.Log

object AceConversationContext {
    private var lastGoal: String? = null
    private var lastResponse: String? = null
    private var activeApp: String? = null
    private var activeTask: AgentTask? = null

    private var pendingClarificationGoal: String? = null
    private var pendingClarificationQuestion: String? = null

    fun update(goal: String, response: String? = null, app: String? = null, task: AgentTask? = null) {
        lastGoal = goal
        if (!response.isNullOrBlank()) lastResponse = response
        if (!app.isNullOrBlank()) activeApp = app
        if (task != null) activeTask = task
        try { Log.i("ACE_CONTEXT", "ACE_CONTEXT: context updated goal=\"$goal\" app=\"${activeApp ?: "none"}\"") } catch (_: Throwable) {}
    }

    fun setPendingClarification(goal: String, question: String) {
        pendingClarificationGoal = goal
        pendingClarificationQuestion = question
        try { Log.i("ACE_CONTEXT", "ACE_CONTEXT: pending clarification set goal=\"$goal\" question=\"$question\"") } catch (_: Throwable) {}
    }

    fun consumePendingClarification(): Pair<String, String>? {
        val g = pendingClarificationGoal
        val q = pendingClarificationQuestion
        pendingClarificationGoal = null
        pendingClarificationQuestion = null
        return if (!g.isNullOrBlank() && !q.isNullOrBlank()) Pair(g, q) else null
    }

    fun clearPendingClarification() {
        pendingClarificationGoal = null
        pendingClarificationQuestion = null
    }

    fun clearSession() {
        lastGoal = null
        lastResponse = null
        activeApp = null
        activeTask = null
        clearPendingClarification()
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
