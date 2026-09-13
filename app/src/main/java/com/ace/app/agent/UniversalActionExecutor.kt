package com.ace.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
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
            // 1. OPEN_APP: Android PackageManager Intent
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
                        evidence = "Intent launched for package ${appInfo.packageName}"
                    )
                } else {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Could not find installed package for '$appName'"
                    )
                }
            }

            // 2. OPEN_URL: Android ACTION_VIEW Browser Intent
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
                        evidence = "ACTION_VIEW launched for $url"
                    )
                } catch (e: Exception) {
                    UniversalActionResult(
                        status = ActionResultStatus.FAILED,
                        message = "Failed to open URL $url: ${e.message}"
                    )
                }
            }

            // 3. SEARCH: Android ACTION_WEB_SEARCH Intent
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
                        evidence = "ACTION_WEB_SEARCH intent sent for '$query'"
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
                        evidence = "Browser search URL launched for '$query'"
                    )
                }
            }

            // 4. PLAY_MEDIA: Android Direct Media Resolver / Universal Interaction Engine
            primitive.contains("play") || primitive.contains("media") -> {
                val targetApp = params["targetApp"] ?: params["app"] ?: target
                val query = inputText.ifBlank { params["query"] ?: target }
                val directResult = GenericMediaRouteResolver.executeDirectMediaRoute(context, targetApp, query)
                if (directResult != null && directResult.isVerified) {
                    UniversalActionResult(
                        status = directResult.status,
                        message = directResult.summary,
                        evidence = directResult.evidenceText
                    )
                } else {
                    if (!AceAccessibilityService.isServiceEnabled(context)) {
                        UniversalActionResult(
                            status = ActionResultStatus.NEEDS_USER_ACTION,
                            message = "Accessibility service required for in-app media interaction."
                        )
                    } else {
                        val uiResult = universalInteractionEngine.executeInAppPlayMedia(context, targetApp, query)
                        UniversalActionResult(
                            status = uiResult.status,
                            message = uiResult.summary,
                            evidence = uiResult.evidenceText
                        )
                    }
                }
            }

            // 5. CLICK / TYPE / SCROLL: Universal Accessibility UI Engine
            primitive.contains("click") || primitive.contains("type") || primitive.contains("scroll") || primitive.contains("press") -> {
                if (!AceAccessibilityService.isServiceEnabled(context)) {
                    UniversalActionResult(
                        status = ActionResultStatus.NEEDS_USER_ACTION,
                        message = "ACE Accessibility Service is required to perform UI interactions. Please enable ACE in Accessibility Settings."
                    )
                } else {
                    val result = ActionExecutionEngine(context).executeAction(
                        TaskStep(
                            id = "universal_ui",
                            label = "$primitive $target",
                            capabilityId = primitive,
                            inputParams = mapOf("target" to target, "text" to inputText)
                        ),
                        AgentTask(goal = target, category = TaskCategory.GENERAL, status = TaskStatus.RUNNING, summary = "", steps = emptyList())
                    )
                    UniversalActionResult(
                        status = result.status,
                        message = result.message,
                        evidence = result.outputData["evidence"] ?: result.message
                    )
                }
            }

            // 6. Direct Android Engine Fallback
            else -> {
                val engine = ActionExecutionEngine(context)
                val step = TaskStep(
                    id = "universal_fallback",
                    label = "$primitive $target",
                    capabilityId = primitive,
                    inputParams = mapOf("target" to target, "text" to inputText) + params
                )
                val task = AgentTask(goal = target, category = TaskCategory.GENERAL, status = TaskStatus.RUNNING, summary = "", steps = emptyList())
                val result = engine.executeAction(step, task)
                UniversalActionResult(
                    status = result.status,
                    message = result.message,
                    evidence = result.outputData["evidence"] ?: result.message,
                    outputData = result.outputData
                )
            }
        }
    }
}
