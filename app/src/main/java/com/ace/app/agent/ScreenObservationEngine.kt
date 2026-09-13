package com.ace.app.agent

import android.view.accessibility.AccessibilityNodeInfo

data class ScreenElement(
    val id: String,
    val text: String,
    val contentDescription: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isSelected: Boolean,
    val boundsInScreen: String,
    val nodeRef: AccessibilityNodeInfo? = null
)

data class ScreenObservation(
    val packageName: String = "android",
    val appName: String = "System",
    val visibleText: List<String> = emptyList(),
    val clickableElements: List<ScreenElement> = emptyList(),
    val editableElements: List<ScreenElement> = emptyList(),
    val scrollableElements: List<ScreenElement> = emptyList(),
    val screenState: String = "UNKNOWN",
    val isPerceptionAvailable: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Generic Screen Observation Engine.
 * Provides application-agnostic UI state perception by analyzing Accessibility node trees.
 */
object ScreenObservationEngine {

    fun captureObservation(rootNode: AccessibilityNodeInfo?, currentPackage: String, appName: String): ScreenObservation {
        val visibleTexts = mutableListOf<String>()
        val clickables = mutableListOf<ScreenElement>()
        val editables = mutableListOf<ScreenElement>()
        val scrollables = mutableListOf<ScreenElement>()

        if (rootNode == null) {
            return ScreenObservation(
                packageName = currentPackage,
                appName = appName,
                visibleText = emptyList(),
                clickableElements = emptyList(),
                editableElements = emptyList(),
                scrollableElements = emptyList(),
                screenState = "PERCEPTION_UNAVAILABLE",
                isPerceptionAvailable = false
            )
        }

        traverseNodeTree(rootNode, visibleTexts, clickables, editables, scrollables)

        val hasSearchInput = editables.isNotEmpty() || visibleTexts.any { it.lowercase().contains("search") || it.lowercase().contains("type") }
        val screenState = when {
            hasSearchInput -> "SEARCH_ACTIVE"
            clickables.isNotEmpty() -> "INTERACTIVE_SCREEN"
            else -> "UNKNOWN"
        }

        return ScreenObservation(
            packageName = currentPackage,
            appName = appName,
            visibleText = visibleTexts,
            clickableElements = clickables,
            editableElements = editables,
            scrollableElements = scrollables,
            screenState = screenState,
            isPerceptionAvailable = true
        )
    }

    private fun traverseNodeTree(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        clickables: MutableList<ScreenElement>,
        editables: MutableList<ScreenElement>,
        scrollables: MutableList<ScreenElement>
    ) {
        val nodeText = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (nodeText.isNotBlank()) texts.add(nodeText)
        if (contentDesc.isNotBlank() && contentDesc != nodeText) texts.add(contentDesc)

        val elem = ScreenElement(
            id = viewId,
            text = nodeText,
            contentDescription = contentDesc,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isSelected = node.isSelected,
            boundsInScreen = "",
            nodeRef = node
        )

        if (node.isEditable) editables.add(elem)
        if (node.isClickable) clickables.add(elem)
        if (node.isScrollable) scrollables.add(elem)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodeTree(child, texts, clickables, editables, scrollables)
        }
    }

    /** Finds editable search element using semantic text, hint, and description heuristics across arbitrary apps. */
    fun findSearchInputNode(observation: ScreenObservation): ScreenElement? {
        val searchKeywords = listOf("search", "find", "query", "explore", "type", "ask", "look")
        return observation.editableElements.firstOrNull { elem ->
            val text = (elem.text + " " + elem.contentDescription + " " + elem.id).lowercase()
            searchKeywords.any { text.contains(it) }
        } ?: observation.editableElements.firstOrNull()
    }

    /** Finds editable message composer element using messaging semantics across arbitrary messaging apps. */
    fun findMessageInputNode(observation: ScreenObservation): ScreenElement? {
        val msgKeywords = listOf("message", "chat", "type", "write", "text", "send")
        return observation.editableElements.firstOrNull { elem ->
            val text = (elem.text + " " + elem.contentDescription + " " + elem.id).lowercase()
            msgKeywords.any { text.contains(it) }
        } ?: observation.editableElements.firstOrNull()
    }

    /** Finds send / submit button element using semantic button descriptions across arbitrary apps. */
    fun findSendButtonNode(observation: ScreenObservation): ScreenElement? {
        val sendKeywords = listOf("send", "submit", "go", "search", "post", "enter")
        return observation.clickableElements.firstOrNull { elem ->
            val text = (elem.text + " " + elem.contentDescription + " " + elem.id).lowercase()
            sendKeywords.any { text.contains(it) }
        }
    }

    /** Formats token-efficient compact UI representation string for Gemma UI reasoning fallback. */
    fun formatCompactUiRepresentation(userGoal: String, observation: ScreenObservation): String {
        val clickables = observation.clickableElements.mapNotNull {
            val label = (it.text.ifBlank { it.contentDescription }).trim()
            if (label.isNotBlank()) label else null
        }.distinct().take(12)

        val editables = observation.editableElements.mapNotNull {
            val label = (it.text.ifBlank { it.contentDescription }).trim()
            if (label.isNotBlank()) label else null
        }.distinct().take(6)

        val scrollables = observation.scrollableElements.mapNotNull {
            val label = (it.text.ifBlank { it.contentDescription }).trim()
            if (label.isNotBlank()) label else null
        }.distinct().take(4)

        val texts = observation.visibleText.filter { it.isNotBlank() }.distinct().take(15)

        return buildString {
            append("APP: ${observation.appName.ifBlank { observation.packageName }}\n")
            append("SCREEN_STATE: ${observation.screenState}\n")
            append("PERCEPTION_AVAILABLE: ${observation.isPerceptionAvailable}\n")
            if (editables.isNotEmpty()) append("EDITABLE_FIELDS: $editables\n")
            if (clickables.isNotEmpty()) append("CLICKABLE_ELEMENTS: $clickables\n")
            if (scrollables.isNotEmpty()) append("SCROLLABLE_CONTAINERS: $scrollables\n")
            if (texts.isNotEmpty()) append("VISIBLE_TEXT_NODES: $texts\n")
        }
    }

    /** Evaluates whether local perception can execute immediately (HIGH confidence >= 0.8) or requires Gemma UI reasoning. */
    fun evaluateLocalConfidence(userGoal: String, observation: ScreenObservation): Float {
        if (!observation.isPerceptionAvailable) return 0.0f
        val searchNode = findSearchInputNode(observation)
        if (searchNode != null && searchNode.text.lowercase().contains("search")) return 1.0f
        if (observation.editableElements.isNotEmpty()) return 0.85f
        if (observation.clickableElements.any { it.text.lowercase().contains("search") || it.contentDescription.lowercase().contains("search") }) return 0.8f
        return 0.3f
    }
}
