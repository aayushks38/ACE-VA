package com.ace.app.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.ace.app.accessibility.AceAccessibilityService
import com.ace.app.accessibility.UniversalAppInteractionEngine
import com.ace.app.brain.AgentDecision

data class UniversalActionResult(
    val status: ActionResultStatus,
    val message: String,
    val evidence: String? = null,
    val outputData: Map<String, String> = emptyMap()
)

/**
 * Universal Action Execution Layer.
 * Direct resolution engine for semantic AgentDecision.Action primitives to Android platform APIs
 * and low-level UI interaction services without requiring CapabilityRegistry, TaskStep, or legacy wrappers.
 */
object UniversalActionExecutor {
    private val universalInteractionEngine = UniversalAppInteractionEngine()

    suspend fun execute(context: Context?, action: AgentDecision.Action): UniversalActionResult {
        if (context == null) {
            return UniversalActionResult(
                status = ActionResultStatus.FAILED,
                message = "Device context unavailable."
            )
        }

        val primitive = action.primitive.lowercase().trim()
        val target = action.target.trim()
        val inputText = action.inputText.orEmpty().trim()
        val params = action.params

        Log.i("ACE_UNIVERSAL_ACTION", "ACE_UNIVERSAL_ACTION: primitive=$primitive target='$target' inputText='$inputText' params=$params")

        return when {
            // 1. OPEN_APP: Direct Android PackageManager Launch Intent
            primitive.contains("open_app") || primitive == "app.launch" || primitive == "open" -> {
                val appName = target.ifBlank { params["app"] ?: params["appName"] ?: "" }
                val appInfo = AppDiscoveryEngine.findApp(context, appName)
                val intent = appInfo?.launchIntent
                if (intent != null) {
                    val launchIntent = Intent(intent).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(launchIntent)
                    UniversalActionResult(
                        status = ActionResultStatus.SUCCESS,
                        message = "Launched $appName via PackageManager Intent",
                        evidence = "Launched package ${appInfo.packageName}",
                        outputData = mapOf("target_package" to appInfo.packageName, "verified_outcome" to "true")
                    )
                } else {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Could not find installed application '$appName'"
                    )
                }
            }

            // 2. OPEN_URL: Direct Android ACTION_VIEW Browser Intent
            primitive.contains("open_url") || primitive == "web.open" || primitive == "url" -> {
                val url = if (target.startsWith("http")) target else "https://$target"
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    UniversalActionResult(
                        status = ActionResultStatus.SUCCESS,
                        message = "Opened web URL: $url",
                        evidence = "ACTION_VIEW intent launched for $url",
                        outputData = mapOf("verified_outcome" to "true", "target_url" to url)
                    )
                } catch (e: Exception) {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Failed to open URL $url: ${e.message}"
                    )
                }
            }

            // 3. SEARCH: Direct Android ACTION_WEB_SEARCH Intent
            primitive.contains("search") -> {
                val query = target.ifBlank { inputText }.ifBlank { params["query"] ?: "" }
                try {
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra("query", query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    UniversalActionResult(
                        status = ActionResultStatus.SUCCESS,
                        message = "Executed web search for '$query'",
                        evidence = "ACTION_WEB_SEARCH intent sent for '$query'",
                        outputData = mapOf("verified_outcome" to "true", "query" to query)
                    )
                } catch (e: Exception) {
                    val searchUrl = "https://www.google.com/search?q=" + Uri.encode(query)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    UniversalActionResult(
                        status = ActionResultStatus.SUCCESS,
                        message = "Executed web search via browser URL for '$query'",
                        evidence = "Browser URL launched for '$query'",
                        outputData = mapOf("verified_outcome" to "true", "query" to query)
                    )
                }
            }

            // 4. PLAY_MEDIA: Direct Media Route Resolver / Universal Accessibility Interaction Engine
            primitive.contains("play") || primitive.contains("media") -> {
                val targetApp = params["targetApp"] ?: params["app"] ?: target
                val query = inputText.ifBlank { params["query"] ?: target }
                val directResult = GenericMediaRouteResolver.executeDirectMediaRoute(context, targetApp, query)
                if (directResult != null && directResult.isVerified) {
                    UniversalActionResult(
                        status = directResult.status,
                        message = directResult.summary,
                        evidence = directResult.evidenceText,
                        outputData = mapOf("verified_outcome" to "true", "query" to query)
                    )
                } else {
                    if (!AceAccessibilityService.isServiceEnabled(context)) {
                        UniversalActionResult(
                            status = ActionResultStatus.NEEDS_USER_ACTION,
                            message = "Accessibility service required for in-app media interaction inside $targetApp."
                        )
                    } else {
                        val uiResult = universalInteractionEngine.executeInAppPlayMedia(context, targetApp, query)
                        UniversalActionResult(
                            status = uiResult.status,
                            message = uiResult.summary,
                            evidence = uiResult.evidenceText,
                            outputData = if (uiResult.isVerified) mapOf("verified_outcome" to "true") else emptyMap()
                        )
                    }
                }
            }

            // 5. CLICK: Direct Accessibility UI Node Click
            primitive.contains("click") -> {
                val service = AceAccessibilityService.getInstance()
                if (service == null || !AceAccessibilityService.isServiceEnabled(context)) {
                    UniversalActionResult(
                        status = ActionResultStatus.NEEDS_USER_ACTION,
                        message = "ACE Accessibility Service is required to perform UI click actions."
                    )
                } else {
                    val clicked = service.clickText(target)
                    UniversalActionResult(
                        status = if (clicked) ActionResultStatus.SUCCESS else ActionResultStatus.FAILED,
                        message = if (clicked) "Clicked '$target' on screen" else "Could not click UI element '$target'",
                        evidence = "Accessibility clickText('$target') result=$clicked",
                        outputData = if (clicked) mapOf("action_type" to "click", "target" to target) else emptyMap()
                    )
                }
            }

            // 6. TYPE / ENTER: Direct Accessibility UI Text Input
            primitive.contains("type") || primitive.contains("enter") || primitive.contains("fill") -> {
                val service = AceAccessibilityService.getInstance()
                if (service == null || !AceAccessibilityService.isServiceEnabled(context)) {
                    UniversalActionResult(
                        status = ActionResultStatus.NEEDS_USER_ACTION,
                        message = "ACE Accessibility Service is required for text typing."
                    )
                } else {
                    val typed = service.typeText(inputText.ifBlank { target }, target)
                    UniversalActionResult(
                        status = if (typed) ActionResultStatus.SUCCESS else ActionResultStatus.FAILED,
                        message = if (typed) "Typed text into '$target'" else "Could not type text into '$target'",
                        evidence = "Accessibility typeText result=$typed",
                        outputData = if (typed) mapOf("action_type" to "type", "inputText" to inputText) else emptyMap()
                    )
                }
            }

            // 7. SCROLL: Direct Accessibility UI Scroll Gesture
            primitive.contains("scroll") -> {
                val service = AceAccessibilityService.getInstance()
                if (service == null || !AceAccessibilityService.isServiceEnabled(context)) {
                    UniversalActionResult(
                        status = ActionResultStatus.NEEDS_USER_ACTION,
                        message = "ACE Accessibility Service is required for scrolling."
                    )
                } else {
                    val isUp = target.contains("up") || primitive.contains("up")
                    val scrolled = service.scroll(forward = !isUp)
                    UniversalActionResult(
                        status = if (scrolled) ActionResultStatus.SUCCESS else ActionResultStatus.FAILED,
                        message = if (scrolled) "Scrolled UI ${if (isUp) "up" else "down"}" else "Could not scroll UI",
                        evidence = "Accessibility scroll result=$scrolled",
                        outputData = if (scrolled) mapOf("action_type" to "scroll") else emptyMap()
                    )
                }
            }

            // 8. NAVIGATE_BACK: Direct Accessibility Global Back Action
            primitive.contains("back") || primitive.contains("navigate_back") -> {
                val service = AceAccessibilityService.getInstance()
                if (service != null && AceAccessibilityService.isServiceEnabled(context)) {
                    val back = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    UniversalActionResult(
                        status = if (back) ActionResultStatus.SUCCESS else ActionResultStatus.FAILED,
                        message = if (back) "Performed back gesture" else "Failed to perform back gesture",
                        evidence = "Accessibility global action back result=$back",
                        outputData = if (back) mapOf("action_type" to "back") else emptyMap()
                    )
                } else {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Accessibility service unavailable for back gesture."
                    )
                }
            }

            // 9. FLASHLIGHT: Direct CameraManager API
            primitive.contains("flashlight") || primitive.contains("torch") -> {
                try {
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                    if (cameraManager != null && cameraId != null) {
                        val turnOn = !target.lowercase().contains("off") && !primitive.lowercase().contains("off")
                        cameraManager.setTorchMode(cameraId, turnOn)
                        UniversalActionResult(
                            status = ActionResultStatus.SUCCESS,
                            message = if (turnOn) "Flashlight turned on" else "Flashlight turned off",
                            evidence = "CameraManager setTorchMode($turnOn)",
                            outputData = mapOf("verified_hardware_state" to "true", "hardware_detail" to "Flashlight toggled $turnOn")
                        )
                    } else {
                        UniversalActionResult(
                            status = ActionResultStatus.FAILED,
                            message = "Camera flashlight hardware unavailable."
                        )
                    }
                } catch (e: Exception) {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Flashlight control error: ${e.message}"
                    )
                }
            }

            // 10. SYSTEM_VOLUME: Direct AudioManager API
            primitive.contains("volume") -> {
                try {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (audioManager != null) {
                        val isLower = target.contains("down") || target.contains("lower") || primitive.contains("lower")
                        val direction = if (isLower) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                        UniversalActionResult(
                            status = ActionResultStatus.SUCCESS,
                            message = "System volume adjusted",
                            evidence = "AudioManager adjustStreamVolume direction=$direction",
                            outputData = mapOf("verified_hardware_state" to "true", "hardware_detail" to "Volume adjusted")
                        )
                    } else {
                        UniversalActionResult(
                            status = ActionResultStatus.FAILED,
                            message = "AudioManager service unavailable."
                        )
                    }
                } catch (e: Exception) {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Volume control error: ${e.message}"
                    )
                }
            }

            // 11. SYSTEM_SETTINGS: Direct Settings Intent
            primitive.contains("setting") -> {
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    UniversalActionResult(
                        status = ActionResultStatus.SUCCESS,
                        message = "Opened System Settings",
                        evidence = "ACTION_SETTINGS intent launched",
                        outputData = mapOf("verified_outcome" to "true")
                    )
                } catch (e: Exception) {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Failed to launch settings: ${e.message}"
                    )
                }
            }

            // 12. FILE_SHARE: Direct Android System Share Sheet (ACTION_SEND)
            primitive.contains("share") || primitive.contains("send") -> {
                val textPayload = inputText.ifBlank { target }
                try {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, textPayload)
                    }
                    val shareIntent = Intent.createChooser(sendIntent, "Share").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(shareIntent)
                    UniversalActionResult(
                        status = ActionResultStatus.SUCCESS,
                        message = "Launched Android Share Sheet for '$textPayload'",
                        evidence = "ACTION_SEND chooser launched",
                        outputData = mapOf("verified_outcome" to "true", "share_payload" to textPayload)
                    )
                } catch (e: Exception) {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Failed to launch Share Sheet: ${e.message}"
                    )
                }
            }

            // Generic Fallback
            else -> {
                val service = AceAccessibilityService.getInstance()
                if (service != null && AceAccessibilityService.isServiceEnabled(context) && target.isNotBlank()) {
                    val clicked = service.clickText(target)
                    UniversalActionResult(
                        status = if (clicked) ActionResultStatus.SUCCESS else ActionResultStatus.FAILED,
                        message = if (clicked) "Executed generic UI click on '$target'" else "Generic action '$primitive $target' unhandled",
                        evidence = "Generic Accessibility fallback click result=$clicked",
                        outputData = if (clicked) mapOf("action_type" to "click", "target" to target) else emptyMap()
                    )
                } else {
                    UniversalActionResult(
                        status = ActionResultStatus.UNSUPPORTED,
                        message = "Action '$primitive $target' is unhandled on this system state."
                    )
                }
            }
        }
    }
}
