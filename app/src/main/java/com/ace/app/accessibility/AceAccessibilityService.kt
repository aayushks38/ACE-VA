package com.ace.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ACE_ACCESSIBILITY"

        @Volatile
        private var instance: AceAccessibilityService? = null

        fun getInstance(): AceAccessibilityService? = instance

        fun isServiceEnabled(context: Context): Boolean {
            val expectedService = "${context.packageName}/${AceAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(expectedService)
        }

        fun promptEnableAccessibility(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
            }
        }
    }

    @Volatile
    private var lastEventTimeMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ACE Accessibility Service connected and ready for automated UI interaction.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            lastEventTimeMs = android.os.SystemClock.elapsedRealtime()
            com.ace.app.utils.AceLatencyTracker.mark("first_accessibility_event")
        }
    }

    suspend fun waitForCondition(maxTimeoutMs: Long = 400L, pollIntervalMs: Long = 20L, condition: () -> Boolean): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < maxTimeoutMs) {
            if (condition()) return true
            com.ace.app.utils.AceLatencyTracker.recordPoll()
            kotlinx.coroutines.delay(pollIntervalMs)
        }
        val satisfied = condition()
        val totalWait = android.os.SystemClock.elapsedRealtime() - start
        com.ace.app.utils.AceLatencyTracker.recordAccessibilityWait(totalWait)
        return satisfied
    }

    override fun onInterrupt() {
        Log.w(TAG, "ACE Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun clickText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val lowerTarget = targetText.lowercase().trim()
        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, lowerTarget, matchingNodes)

        val candidates = matchingNodes.filter { !it.isEditable }.ifEmpty { matchingNodes }

        for (node in candidates) {
            if (performClickOnNodeOrParent(node)) {
                Log.i(TAG, "Clicked UI node matching text: '$targetText'")
                return true
            }
        }
        Log.w(TAG, "Could not click node matching text: '$targetText'")
        return false
    }

    fun clickId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
        for (node in nodes) {
            if (performClickOnNodeOrParent(node)) {
                Log.i(TAG, "Clicked UI node matching ID: '$viewId'")
                return true
            }
        }
        Log.w(TAG, "Could not click node matching ID: '$viewId'")
        return false
    }

    fun typeText(textToType: String, targetFieldHint: String? = null): Boolean {
        var root = rootInActiveWindow ?: return false
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodesRecursive(root, editableNodes)

        if (editableNodes.isEmpty()) {
            // Attempt to click Search icon/bar if app requires tapping search bar first (e.g. Swiggy/Spotify)
            val startWait = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - startWait < 200L) {
                root = rootInActiveWindow ?: break
                findEditableNodesRecursive(root, editableNodes)
                if (editableNodes.isNotEmpty()) break
                try { Thread.sleep(20) } catch (_: Exception) {}
            }
        }

        var targetNode: AccessibilityNodeInfo? = null
        if (!targetFieldHint.isNullOrBlank()) {
            val lowerHint = targetFieldHint.lowercase().trim()
            targetNode = editableNodes.firstOrNull { node ->
                val hint = node.hintText?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                hint.contains(lowerHint) || text.contains(lowerHint) || desc.contains(lowerHint)
            }
        }

        if (targetNode == null) {
            targetNode = editableNodes.firstOrNull { it.isFocused } ?: editableNodes.firstOrNull()
        }

        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            var success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) {
                val freshRoot = rootInActiveWindow
                val freshEditable = mutableListOf<AccessibilityNodeInfo>()
                if (freshRoot != null) {
                    findEditableNodesRecursive(freshRoot, freshEditable)
                    val freshTarget = freshEditable.firstOrNull { it.isFocused } ?: freshEditable.firstOrNull()
                    if (freshTarget != null) {
                        freshTarget.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        success = freshTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    }
                }
            }
            if (success) {
                Log.i(TAG, "Successfully typed text '$textToType' into editable UI node.")
                return true
            }
        }

        Log.w(TAG, "Failed to type text '$textToType' into editable UI node.")
        return false
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollableNodes = mutableListOf<AccessibilityNodeInfo>()
        findScrollableNodesRecursive(root, scrollableNodes)

        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        for (node in scrollableNodes) {
            if (node.performAction(action)) {
                Log.i(TAG, "Successfully performed scroll (forward=$forward).")
                return true
            }
        }
        Log.w(TAG, "Could not perform scroll.")
        return false
    }

    fun pressGlobalButton(actionName: String): Boolean {
        val globalAction = when (actionName.uppercase().trim()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> GLOBAL_ACTION_BACK
        }
        val success = performGlobalAction(globalAction)
        Log.i(TAG, "Pressed global button '$actionName', result=$success")
        return success
    }

    fun launchApp(context: Context, packageNameOrName: String): Boolean {
        val pm = context.packageManager
        var pkg = packageNameOrName.trim().trimEnd('.', ',', '!', '?').trim()
        val raw = pkg.lowercase()

        // 1. Direct Intent Actions for Common System Apps
        try {
            if (raw == "camera" || raw == "the camera" || raw.contains("camera")) {
                val cameraIntent = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (cameraIntent.resolveActivity(pm) != null) {
                    context.startActivity(cameraIntent)
                    Log.i(TAG, "Successfully launched Camera via MediaStore intent action")
                    return true
                }
            }
            if (raw == "settings" || raw == "system settings") {
                val settingsIntent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                Log.i(TAG, "Successfully launched Settings via ACTION_SETTINGS intent")
                return true
            }
            if (raw == "clock" || raw == "alarms") {
                val clockIntent = android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (clockIntent.resolveActivity(pm) != null) {
                    context.startActivity(clockIntent)
                    Log.i(TAG, "Successfully launched Clock via AlarmClock intent")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Intent action fallback failed for '$raw': ${e.message}")
        }
        com.ace.app.utils.AceLatencyTracker.mark("app_discovery_start")
        val discoveredApp = com.ace.app.agent.AppDiscoveryEngine.findApp(context, pkg)
        com.ace.app.utils.AceLatencyTracker.mark("app_discovery_end")

        if (discoveredApp?.launchIntent != null) {
            return try {
                val intent = discoveredApp.launchIntent.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                startActivity(intent)
                Log.i(TAG, "Successfully launched app '${discoveredApp.appName}' (${discoveredApp.packageName}) via cached AppDiscoveryEngine")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error launching app '${discoveredApp.appName}': ${e.message}")
                false
            }
        }

        if (!pkg.contains(".")) {
            val appInfo = com.ace.app.agent.AppDiscoveryEngine.findApp(context, pkg)
            if (appInfo != null) pkg = appInfo.packageName
        }

        return try {
            val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (intent != null) {
                startActivity(intent)
                Log.i(TAG, "Successfully launched app package: '$pkg'")
                true
            } else {
                Log.w(TAG, "No launch intent found for package: '$pkg'")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app package '$pkg': ${e.message}")
            false
        }
    }

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        lowerTarget: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hint = node.hintText?.toString()?.lowercase() ?: ""

        if (text.contains(lowerTarget) || desc.contains(lowerTarget) || hint.contains(lowerTarget)) {
            results.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByTextRecursive(child, lowerTarget, results)
        }
    }

    private fun findEditableNodesRecursive(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isEditable) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableNodesRecursive(child, results)
        }
    }

    private fun findScrollableNodesRecursive(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isScrollable) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollableNodesRecursive(child, results)
        }
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            Log.i(TAG, "Accessibility click failed for text node, falling back to gesture tap at (${bounds.centerX()}, ${bounds.centerY()})")
            return clickCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        return false
    }

    fun clickCoordinates(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x, y)
        }
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
        val dispatched = dispatchGesture(builder.build(), null, null)
        Log.i(TAG, "Dispatched coordinate click at ($x, $y) result=$dispatched")
        return dispatched
    }

    fun getScreenBounds(): android.graphics.Rect {
        val displayMetrics = resources.displayMetrics
        return android.graphics.Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
    }
}
