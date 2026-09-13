package com.ace.app.agent

import android.content.Context
import android.util.Log
import com.ace.app.accessibility.AceAccessibilityService
import com.ace.app.accessibility.UniversalAppInteractionEngine
import com.ace.app.agent.CapabilityRegistry
import com.ace.app.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ActionResult(
    val status: ActionResultStatus,
    val message: String,
    val backendUsed: String = "Intent",
    val outputData: Map<String, String> = emptyMap(),
    val error: String? = null
)

class ActionExecutionEngine(private val context: Context?) {
    private val universalInteractionEngine = UniversalAppInteractionEngine()

    suspend fun executeAction(
        step: TaskStep,
        task: AgentTask
    ): ActionResult = withContext(Dispatchers.Main) {
        val rawCapId = step.capabilityId
        val universalAction = GoalRequirementExtractor.mapToUniversalName(rawCapId)
        val params = step.inputParams.toMutableMap()
        if (!params.containsKey("goal")) params["goal"] = task.goal

        Log.i("ACE_ACTION", "ACE_ACTION: original_goal='${task.goal}'")
        Log.i("ACE_ACTION", "ACE_ACTION: step='${step.id}' action='$rawCapId' universal_action='$universalAction' params=$params")

        Log.i("ACE_CAPABILITY", "ACE_CAPABILITY: capability=$rawCapId universal_action=$universalAction step=${step.id}")
        val result = executeMultiBackend(rawCapId, universalAction, params, context)

        Log.i("ACE_ACTION", "ACE_ACTION: step='${step.id}' action='$rawCapId' backend='${result.backendUsed}' status=${result.status}")
        if (result.status == ActionResultStatus.SUCCESS) {
            Log.i("ACE_CAPABILITY", "ACE_CAPABILITY: capability=$rawCapId status=SUCCESS backend='${result.backendUsed}'")
        } else {
            Log.e("ACE_ERROR", "ACE_ERROR: capability=$rawCapId status=${result.status} error='${result.error ?: result.message}'")
        }

        return@withContext result
    }

    private suspend fun executeMultiBackend(
        rawCapId: String,
        universalAction: String,
        params: Map<String, String>,
        context: Context?
    ): ActionResult {
        if (context == null) {
            Log.e("ACE_ERROR", "ACE_ERROR: Device context unavailable for action execution")
            return ActionResult(
                status = ActionResultStatus.FAILED,
                message = "Device context unavailable for action execution.",
                backendUsed = "None",
                error = "Context Unavailable"
            )
        }

        val targetApp = (params["targetApp"] ?: params["appName"] ?: params["app"] ?: "").trim()
        val query = (params["query"] ?: params["text"] ?: params["song"] ?: params["track"] ?: "").trim()
        val isBrowserApp = targetApp.lowercase().contains("chrome") || targetApp.lowercase().contains("browser")

        // 1. Accessibility Service & Universal Interaction Engine (for in-app UI automation in non-browser native apps)
        if (!isBrowserApp && targetApp.isNotBlank() && (universalAction == "SEARCH" || universalAction == "TYPE_TEXT" || universalAction == "SEARCH_IN_APP" || universalAction == "UNIVERSAL_SEARCH" || rawCapId == "universal_search" || universalAction == "PLAY_MEDIA")) {
            Log.i("ACE_ACCESSIBILITY", "ACE_ACCESSIBILITY: attempting in-app execution for app=$targetApp action=$universalAction query='$query'")

            if (universalAction == "PLAY_MEDIA") {
                // Phase 1: Try Generic Direct Media Intent Routing Layer
                val directResult = GenericMediaRouteResolver.executeDirectMediaRoute(context, targetApp, query)
                if (directResult != null && directResult.isVerified) {
                    val output = mapOf("targetApp" to targetApp, "query" to query, "evidence" to (directResult.evidenceText ?: ""))
                    return ActionResult(
                        status = directResult.status,
                        message = directResult.summary,
                        backendUsed = "Generic Direct Media Intent Backend",
                        outputData = output
                    )
                }

                // Phase 2: Direct route failed/unverified -> Fallback to Universal UI Search Engine
                com.ace.app.utils.AceLatencyTracker.recordStage("UI_SEARCH_FALLBACK")
                Log.i("ACE_MEDIA", "ACE_MEDIA: direct media route unverified — falling back to Universal Accessibility UI Engine")
                if (!AceAccessibilityService.isServiceEnabled(context)) {
                    Log.w("ACE_FALLBACK", "ACE_FALLBACK: accessibility service disabled")
                    return ActionResult(
                        status = ActionResultStatus.NEEDS_USER_ACTION,
                        message = "ACE Accessibility Service is required to perform in-app interactions inside $targetApp. Please enable ACE in Accessibility Settings.",
                        backendUsed = "Universal Accessibility UI Automation Engine",
                        error = "ACCESSIBILITY_DISABLED"
                    )
                }
                val verifyResult = universalInteractionEngine.executeInAppPlayMedia(context, targetApp, query)
                val output = mapOf("targetApp" to targetApp, "query" to query, "evidence" to (verifyResult.evidenceText ?: ""))
                return ActionResult(
                    status = verifyResult.status,
                    message = verifyResult.summary,
                    backendUsed = "Universal Accessibility UI Automation Engine",
                    outputData = output,
                    error = if (!verifyResult.isVerified) verifyResult.evidenceText ?: "In-app playback unverified" else null
                )
            }

            if (!AceAccessibilityService.isServiceEnabled(context)) {
                Log.w("ACE_FALLBACK", "ACE_FALLBACK: accessibility service disabled, falling back or reporting user action required")
                return ActionResult(
                    status = ActionResultStatus.NEEDS_USER_ACTION,
                    message = "ACE Accessibility Service is required to perform in-app interactions inside $targetApp. Please enable ACE in Accessibility Settings.",
                    backendUsed = "Universal Accessibility UI Automation Engine",
                    error = "ACCESSIBILITY_DISABLED"
                )
            }
            val verifyResult = universalInteractionEngine.executeInAppSearch(context, targetApp, query)
            val backendName = "Universal Accessibility UI Automation Engine"
            val output = mapOf("targetApp" to targetApp, "query" to query, "evidence" to (verifyResult.evidenceText ?: ""))
            return ActionResult(
                status = verifyResult.status,
                message = verifyResult.summary,
                backendUsed = backendName,
                outputData = output,
                error = if (!verifyResult.isVerified) verifyResult.evidenceText ?: "In-app search unverified" else null
            )
        }

        // 2. Standard Intent / Capability Backend Resolution
        val capability = CapabilityRegistry.getCapability(rawCapId)
            ?: CapabilityRegistry.getCapability(mapUniversalToLegacy(universalAction))
            ?: CapabilityRegistry.getCapability("text_reasoning")

        if (capability != null) {
            if (capability.networkRequirement == NetworkRequirement.REQUIRED && !NetworkUtils.isNetworkAvailable(context)) {
                Log.e("ACE_ERROR", "ACE_ERROR: Network connection required for '${capability.name}', but device is offline")
                return ActionResult(
                    status = ActionResultStatus.FAILED,
                    message = "Network connection required for '${capability.name}', but device is offline.",
                    backendUsed = "Network Check",
                    error = "Network Unavailable"
                )
            }

            val capResult = capability.execute(context, params)
            val status = when {
                capResult.isSuccess -> ActionResultStatus.SUCCESS
                capResult.error?.contains("Permission", ignoreCase = true) == true -> ActionResultStatus.NEEDS_PERMISSION
                capResult.error?.contains("Accessibility disabled", ignoreCase = true) == true -> ActionResultStatus.NEEDS_USER_ACTION
                else -> ActionResultStatus.FAILED
            }

            val backendUsed = detectBackendUsed(capability.id, universalAction)

            return ActionResult(
                status = status,
                message = capResult.message,
                backendUsed = backendUsed,
                outputData = capResult.outputData,
                error = capResult.error
            )
        }

        return ActionResult(
            status = ActionResultStatus.UNSUPPORTED,
            message = "Action '$rawCapId' ($universalAction) is unsupported on this device.",
            backendUsed = "Unsupported",
            error = "Unsupported capability"
        )
    }

    private fun detectBackendUsed(id: String, universalAction: String): String {
        return when (id.lowercase()) {
            "ui_open_app", "open_app" -> "Android Intent (PackageManager Launch)"
            "web_search", "search" -> "Android Intent (Google Search ACTION_VIEW)"
            "web_open_url", "open_url" -> "Android Intent (Browser ACTION_VIEW)"
            "media_playback", "play_media" -> "Android Intent / Deep Link (Media Search & URI Playback)"
            "file_discovery", "file_manager", "file_discover" -> "Android Storage Access Framework & MediaStore API"
            "app_share", "send_document", "file_share" -> "Android System Share Sheet (ACTION_SEND)"
            "ui_click", "click", "ui_type", "type_text", "ui_scroll", "scroll", "ui_press_button", "navigate_back" -> "Android Accessibility Service (UI Automation API)"
            "flashlight", "system_settings" -> "Android System Service API"
            else -> "Android $universalAction Capability Backend"
        }
    }

    private fun mapUniversalToLegacy(universalAction: String): String {
        return when (universalAction) {
            "OPEN_APP" -> "ui_open_app"
            "OPEN_URL" -> "web_open_url"
            "SEARCH" -> "web_search"
            "TYPE_TEXT" -> "ui_type"
            "CLICK" -> "ui_click"
            "SCROLL" -> "ui_scroll"
            "PLAY_MEDIA" -> "media_playback"
            "FILE_DISCOVER" -> "file_discovery"
            "FILE_SHARE" -> "app_share"
            "NAVIGATE_BACK" -> "ui_press_button"
            "SYSTEM_SETTINGS" -> "system_settings"
            else -> universalAction.lowercase()
        }
    }
}
