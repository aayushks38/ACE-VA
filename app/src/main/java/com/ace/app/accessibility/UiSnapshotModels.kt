package com.ace.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.ace.app.agent.ActionResultStatus

data class UiNode(
    val text: String = "",
    val contentDescription: String = "",
    val hintText: String = "",
    val resourceId: String = "",
    val className: String = "",
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEnabled: Boolean = true,
    val boundsInScreen: String = "",
    val nodeRef: AccessibilityNodeInfo? = null
)

data class UiSnapshot(
    val packageName: String = "",
    val windowTitle: String = "",
    val nodeCount: Int = 0,
    val nodes: List<UiNode> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class CandidateMatch(
    val node: UiNode,
    val candidateText: String,
    val confidence: Float,
    val strategyUsed: String = "Semantic Match",
    val centerX: Float? = null,
    val centerY: Float? = null
)

fun UiNode.getRect(): android.graphics.Rect? {
    if (boundsInScreen.isBlank()) return null
    return try {
        val clean = boundsInScreen.replace("[", "").replace("]", ",")
        val parts = clean.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size == 4) {
            android.graphics.Rect(parts[0], parts[1], parts[2], parts[3])
        } else null
    } catch (_: Exception) {
        null
    }
}

fun UiNode.getCenterCoordinates(): Pair<Float, Float>? {
    val rect = getRect() ?: return null
    if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) return null
    return Pair(rect.left + (rect.width() / 2.0f), rect.top + (rect.height() / 2.0f))
}

enum class InterruptionType {
    NONE,
    SAFE_INTERRUPTION,
    SENSITIVE_INTERRUPTION
}

data class DialogCheckResult(
    val type: InterruptionType,
    val actionButtonText: String = "",
    val actionButtonNode: UiNode? = null
)

data class VerificationResult(
    val isVerified: Boolean,
    val status: ActionResultStatus,
    val summary: String,
    val evidenceText: String? = null
)
