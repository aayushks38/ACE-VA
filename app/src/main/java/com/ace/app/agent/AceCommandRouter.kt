package com.ace.app.agent

import android.util.Log

enum class RouteType {
    INSTANT_INTELLIGENCE,
    FAST_ACTION,
    DETERMINISTIC_WORKFLOW,
    STRUCTURED_WORKFLOW,
    DEEP_BRAIN
}

data class RoutingResult(
    val route: RouteType,
    val confidence: Float,
    val workflow: AgentPlan?,
    val reason: String,
    val executionMode: ExecutionMode = ExecutionMode.DETERMINISTIC,
    val brainRequired: Boolean = false,
    val brainAvailable: Boolean = false
)

sealed class CommandRoute {
    data class Fast(val result: RoutingResult, val plan: AgentPlan) : CommandRoute()
    data class Workflow(val result: RoutingResult, val plan: AgentPlan) : CommandRoute()
    data class DeepBrain(val result: RoutingResult) : CommandRoute()
}

class AceCommandRouter {

    fun route(goal: String, brainAvailable: Boolean = false): CommandRoute {
        com.ace.app.utils.AceLatencyTracker.recordStage("T1")
        val clean = goal.replace("_", " ").trim()

        if (clean.isBlank()) {
            val result = RoutingResult(
                route = RouteType.DEEP_BRAIN,
                confidence = 0.0f,
                workflow = null,
                reason = "blank_command",
                executionMode = ExecutionMode.BRAIN,
                brainRequired = true,
                brainAvailable = brainAvailable
            )
            logRouting(clean, 0, false, RouteType.DEEP_BRAIN, false, "blank_command", ExecutionMode.BRAIN, false, brainAvailable)
            return CommandRoute.DeepBrain(result)
        }

        // Normalize goal by stripping leading conversational filler terms
        val normalized = clean
            .replace(Regex("""^(stop|forget that|no|actually|instead|please|can you|could you|hey ace|ace|now)\s*[\.,!]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(instead|please)$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { clean }

        // LEVEL 0: INSTANT INTELLIGENCE ENGINE (System/Device API direct bypass)
        val instantCap = com.ace.app.instant.InstantIntelligenceEngine.findBestCapability(normalized)
            ?: com.ace.app.instant.InstantIntelligenceEngine.findBestCapability(clean)
        if (instantCap != null) {
            val targetCmd = if (com.ace.app.instant.InstantIntelligenceEngine.canHandle(normalized)) normalized else clean
            val plan = AgentPlan(
                userGoal = clean,
                intent = instantCap.id,
                channel = CommunicationChannel.PHONE,
                targetEntity = instantCap.source,
                steps = listOf(
                    TaskStep(
                        id = "step_1",
                        label = instantCap.name,
                        capabilityId = "instant_intelligence",
                        inputParams = mapOf("command" to targetCmd, "capabilityId" to instantCap.id)
                    )
                )
            )
            val result = RoutingResult(
                route = RouteType.INSTANT_INTELLIGENCE,
                confidence = instantCap.confidence(targetCmd),
                workflow = plan,
                reason = "instant_intelligence_${instantCap.id}",
                executionMode = ExecutionMode.DETERMINISTIC,
                brainRequired = false,
                brainAvailable = brainAvailable
            )
            Log.i("ACE_ROUTER", "ACE_ROUTER: command=\"$clean\"")
            Log.i("ACE_ROUTER", "ACE_ROUTER: route=INSTANT_INTELLIGENCE")
            Log.i("ACE_ROUTER", "ACE_ROUTER: execution_mode=DETERMINISTIC")
            Log.i("ACE_ROUTER", "ACE_ROUTER: brain_required=false")
            Log.i("ACE_ROUTER", "ACE_ROUTER: brain_available=$brainAvailable")
            Log.i("ACE_INSTANT", "ACE_INSTANT: capability=${instantCap.id}")
            Log.i("ACE_DEVICE", "ACE_DEVICE: source=${instantCap.source}")
            Log.i("ACE_ROUTER", "ACE_ROUTER: GEMMA_BYPASSED=true")
            com.ace.app.utils.AceLatencyTracker.recordStage("T2")
            return CommandRoute.Fast(result, plan)
        }

        // LEVEL 1: EXPLICIT DIRECT ACTIONS (Priority 2: Direct bypass for open app, flashlight, volume, settings)
        val subCommands = clean.split(Regex("""\s*,\s*|\b(and then|after that|followed by|then|next|and)\b""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val complexity = subCommands.size

        if (complexity == 1) {
            val singleSteps = resolveSubCommand(subCommands[0], 1).ifEmpty { resolveSubCommand(normalized, 1) }
            if (singleSteps.isNotEmpty()) {
                val firstCap = singleSteps.first().capabilityId
                val isDirectAction = firstCap == "ui_open_app" || firstCap == "flashlight" || firstCap == "system_volume" || firstCap == "system_settings" || firstCap == "web_open_url" || firstCap == "universal_search" || firstCap == "media_playback" || firstCap == "play_media" || firstCap == "set_alarm"
                if (isDirectAction) {
                    val plan = AgentPlan(
                        userGoal = clean,
                        intent = firstCap,
                        channel = CommunicationChannel.PHONE,
                        targetEntity = singleSteps.first().inputParams["appName"] ?: singleSteps.first().inputParams["url"],
                        steps = singleSteps
                    )
                    val result = RoutingResult(
                        route = RouteType.FAST_ACTION,
                        confidence = 1.0f,
                        workflow = plan,
                        reason = "explicit_direct_action",
                        executionMode = ExecutionMode.DETERMINISTIC,
                        brainRequired = false,
                        brainAvailable = brainAvailable
                    )
                    logRouting(clean, 1, true, RouteType.FAST_ACTION, true, "explicit_direct_action", ExecutionMode.DETERMINISTIC, false, brainAvailable)
                    com.ace.app.utils.AceLatencyTracker.recordStage("T2")
                    return CommandRoute.Fast(result, plan)
                }
            }
        }

        // LEVEL 2: GENERAL-PURPOSE NLU BRAIN ROUTING (Priority 3: Gemma General Agent)
        // When Gemma brain is loaded and available, route complex/natural language goals to Gemma
        if (brainAvailable) {
            val result = RoutingResult(
                route = RouteType.DEEP_BRAIN,
                confidence = 0.95f,
                workflow = null,
                reason = "nlu_brain_available",
                executionMode = ExecutionMode.BRAIN,
                brainRequired = true,
                brainAvailable = true
            )
            logRouting(clean, complexity, false, RouteType.DEEP_BRAIN, false, "nlu_brain_available", ExecutionMode.BRAIN, true, true)
            return CommandRoute.DeepBrain(result)
        }

        // Level 2: STRUCTURED CAPABILITY WORKFLOWS (Pattern Extraction)
        val structuredSteps = extractStructuredWorkflow(clean)
        if (structuredSteps.isNotEmpty()) {
            val plan = AgentPlan(
                userGoal = clean,
                intent = "structured_workflow",
                channel = CommunicationChannel.PHONE,
                targetEntity = null,
                steps = structuredSteps
            )
            val result = RoutingResult(
                route = RouteType.STRUCTURED_WORKFLOW,
                confidence = 0.95f,
                workflow = plan,
                reason = "structured_capability_workflow",
                executionMode = ExecutionMode.DETERMINISTIC,
                brainRequired = false,
                brainAvailable = brainAvailable
            )
            logRouting(clean, complexity, true, RouteType.STRUCTURED_WORKFLOW, true, "structured_capability_workflow", ExecutionMode.DETERMINISTIC, false, brainAvailable)
            logWorkflow(structuredSteps)
            return CommandRoute.Workflow(result, plan)
        }

        // Level 3: DETERMINISTIC MULTI-STEP WORKFLOW
        if (complexity > 1) {
            val allSteps = mutableListOf<TaskStep>()
            var stepCounter = 1
            var allResolved = true

            var activeApp: String? = null
            for (subCmd in subCommands) {
                val steps = resolveSubCommand(subCmd, stepCounter)
                if (steps.isEmpty()) {
                    allResolved = false
                    break
                }
                val processedSteps = steps.map { step ->
                    val appInStep = step.inputParams["app"] ?: step.inputParams["appName"]
                    if (!appInStep.isNullOrBlank()) activeApp = appInStep
                    val currentApp = activeApp
                    if ((step.capabilityId == "web_search" || step.capabilityId == "media_playback") && !currentApp.isNullOrBlank() && !step.inputParams.containsKey("app")) {
                        val newParams = step.inputParams.toMutableMap()
                        newParams["app"] = currentApp
                        newParams["appName"] = currentApp
                        step.copy(inputParams = newParams)
                    } else {
                        step
                    }
                }
                allSteps.addAll(processedSteps)
                stepCounter += processedSteps.size
            }

            if (allResolved && allSteps.isNotEmpty()) {
                val plan = AgentPlan(
                    userGoal = clean,
                    intent = "deterministic_workflow",
                    channel = CommunicationChannel.PHONE,
                    targetEntity = null,
                    steps = allSteps
                )
                val result = RoutingResult(
                    route = RouteType.DETERMINISTIC_WORKFLOW,
                    confidence = 0.95f,
                    workflow = plan,
                    reason = "deterministic_multi_step",
                    executionMode = ExecutionMode.DETERMINISTIC,
                    brainRequired = false,
                    brainAvailable = brainAvailable
                )
                logRouting(clean, complexity, true, RouteType.DETERMINISTIC_WORKFLOW, true, "deterministic_multi_step", ExecutionMode.DETERMINISTIC, false, brainAvailable)
                logWorkflow(allSteps)
                return CommandRoute.Workflow(result, plan)
            }
        }

        // Level 4: DEEP BRAIN (Gemma LLM Fallback)
        val result = RoutingResult(
            route = RouteType.DEEP_BRAIN,
            confidence = 0.0f,
            workflow = null,
            reason = "reasoning_required",
            executionMode = if (brainAvailable) ExecutionMode.BRAIN else ExecutionMode.BRAIN_UNAVAILABLE,
            brainRequired = true,
            brainAvailable = brainAvailable
        )
        logRouting(clean, complexity, false, RouteType.DEEP_BRAIN, false, "reasoning_required", if (brainAvailable) ExecutionMode.BRAIN else ExecutionMode.BRAIN_UNAVAILABLE, true, brainAvailable)
        return CommandRoute.DeepBrain(result)
    }

    private fun logRouting(
        command: String,
        complexity: Int,
        deterministicMatch: Boolean,
        route: RouteType,
        gemmaBypassed: Boolean,
        reason: String,
        executionMode: ExecutionMode,
        brainRequired: Boolean,
        brainAvailable: Boolean
    ) {
        Log.i("ACE_ROUTER", "ACE_ROUTER: command=$command")
        Log.i("ACE_ROUTER", "ACE_ROUTER: complexity=$complexity")
        Log.i("ACE_ROUTER", "ACE_ROUTER: deterministic_match=$deterministicMatch")
        Log.i("ACE_ROUTER", "ACE_ROUTER: route=$route")
        Log.i("ACE_ROUTER", "ACE_ROUTER: GEMMA_BYPASSED=$gemmaBypassed")
        Log.i("ACE_ROUTER", "ACE_ROUTER: execution_mode=$executionMode")
        Log.i("ACE_ROUTER", "ACE_ROUTER: brain_required=$brainRequired")
        Log.i("ACE_ROUTER", "ACE_ROUTER: brain_available=$brainAvailable")
        if (route == RouteType.DEEP_BRAIN) {
            Log.i("ACE_ROUTER", "ACE_ROUTER: reason=$reason")
        }
    }

    private fun logWorkflow(steps: List<TaskStep>) {
        Log.i("ACE_WORKFLOW", "ACE_WORKFLOW: steps=${steps.size}")
        for ((idx, step) in steps.withIndex()) {
            Log.i("ACE_WORKFLOW", "ACE_WORKFLOW: step=${idx + 1} capability=${step.capabilityId}")
        }
    }

    private fun resolveSubCommand(rawSubCmd: String, startStepIdx: Int): List<TaskStep> {
        val subCmd = rawSubCmd.replace("_", " ").trim()
        val lower = subCmd.lowercase()

        // A. ROUTE DIRECTIONS / NAVIGATION INTENT (e.g. "Find a route to the nearest major railway station")
        if (lower.startsWith("find a route to ") || lower.startsWith("find route to ") || lower.startsWith("get directions to ") || lower.startsWith("directions to ") || lower.startsWith("navigate to ")) {
            val destination = extractTarget(subCmd, listOf("find a route to ", "find route to ", "get directions to ", "directions to ", "navigate to "))
            if (destination.isNotBlank() && !isWebUrl(destination)) {
                return listOf(
                    TaskStep(
                        id = "step_$startStepIdx",
                        label = "Directions to $destination",
                        capabilityId = "route_directions",
                        inputParams = mapOf("destination" to destination, "target" to destination, "place" to destination)
                    )
                )
            }
        }

        // B. OPEN WEBSITE / URL (e.g. "open gitam.edu", "open https://example.com")
        if (lower.startsWith("open ") || lower.startsWith("go to ") || lower.startsWith("navigate to ")) {
            val target = extractTarget(subCmd, listOf("open ", "go to ", "navigate to "))
            if (isWebUrl(target)) {
                val formattedUrl = if (!target.startsWith("http://") && !target.startsWith("https://")) "https://$target" else target
                return listOf(
                    TaskStep(
                        id = "step_$startStepIdx",
                        label = "Open $target",
                        capabilityId = "web_open_url",
                        inputParams = mapOf("url" to formattedUrl, "query" to formattedUrl)
                    )
                )
            }
        }

        // C. WEB SEARCH & MEDIA PLAYBACK
        if (lower.startsWith("play ") || lower.startsWith("listen to ")) {
            val trackFull = extractTarget(subCmd, listOf("play song ", "play music ", "play ", "listen to "))
            if (trackFull.isNotBlank()) {
                val qualified = extractAppQualifier(trackFull)
                val targetApp = qualified?.canonicalAppName ?: "Spotify"
                val trackQuery = qualified?.cleanQuery ?: trackFull
                return listOf(
                    TaskStep(
                        id = "step_$startStepIdx",
                        label = "Play '$trackQuery' on $targetApp",
                        capabilityId = "media_playback",
                        inputParams = mapOf(
                            "query" to trackQuery,
                            "song" to trackQuery,
                            "track" to trackQuery,
                            "appName" to targetApp,
                            "targetApp" to targetApp,
                            "action" to "PLAY_MEDIA"
                        )
                    )
                )
            }
        }

        if (lower.startsWith("search ") || lower.startsWith("google ") || lower.startsWith("find ") || lower.startsWith("look for ") || lower.startsWith("look up ")) {
            var query = extractTarget(subCmd, listOf("search for ", "search on google for ", "search google for ", "search ", "google ", "find for ", "find ", "look for ", "look up for ", "look up "))
            if (query.lowercase().startsWith("for ")) {
                query = query.substring(4).trim()
            }
            if (query.isNotBlank()) {
                val qualified = extractAppQualifier(query)
                if (qualified != null) {
                    return listOf(
                        TaskStep(
                            id = "step_$startStepIdx",
                            label = "Search '${qualified.cleanQuery}' in ${qualified.canonicalAppName}",
                            capabilityId = "universal_search",
                            inputParams = mapOf(
                                "query" to qualified.cleanQuery,
                                "targetApp" to qualified.canonicalAppName,
                                "appName" to qualified.canonicalAppName,
                                "action" to "SEARCH"
                            )
                        )
                    )
                }

                return listOf(
                    TaskStep(
                        id = "step_$startStepIdx",
                        label = "Search '$query'",
                        capabilityId = "web_search",
                        inputParams = mapOf("query" to query)
                    )
                )
            }
        }

        // D. OPEN / LAUNCH APP OR SETTINGS
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ") || lower.startsWith("run ") || lower.startsWith("show me ")) {
            val target = extractTarget(subCmd, listOf("open ", "launch ", "start ", "run ", "show me "))
            if (target.isNotBlank()) {
                // Check for "open app and search" pattern
                if ((lower.contains(" and search ") || lower.contains(" and find ")) && !target.lowercase().contains("settings")) {
                    val appName = when {
                        target.lowercase().contains("youtube") -> "YouTube"
                        target.lowercase().contains("facebook") -> "Facebook"
                        target.lowercase().contains("instagram") -> "Instagram"
                        target.lowercase().contains("twitter") -> "Twitter"
                        target.lowercase().contains("reddit") -> "Reddit"
                        target.lowercase().contains("chrome") -> "Chrome"
                        target.lowercase().contains("browser") -> "Chrome"
                        else -> target.split(" ").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "app"
                    }
                    val searchTerm = if (lower.contains(" and search ")) {
                        lower.substringAfter(" and search ").trim().trim('"', '\'')
                    } else {
                        lower.substringAfter(" and find ").trim().trim('"', '\'')
                    }
                    
                    return listOf(
                        TaskStep(
                            id = "step_$startStepIdx",
                            label = "Search '$searchTerm' in $appName",
                            capabilityId = "universal_search",
                            inputParams = mapOf(
                                "query" to searchTerm,
                                "targetApp" to appName,
                                "appName" to appName,
                                "action" to "SEARCH"
                            )
                        )
                    )
                }
                
                if (target.lowercase().contains("battery") || lower.contains("battery")) {
                    return listOf(
                        TaskStep(
                            id = "step_$startStepIdx",
                            label = "Open Battery Settings",
                            capabilityId = "system_settings",
                            inputParams = mapOf("setting" to "battery", "target" to "battery")
                        )
                    )
                }
                if (target.lowercase() == "settings" || target.lowercase() == "system settings" || target.lowercase().endsWith("settings")) {
                    val settingType = if (target.lowercase().contains("wifi")) "wifi"
                    else if (target.lowercase().contains("bluetooth")) "bluetooth"
                    else if (target.lowercase().contains("display")) "display"
                    else if (target.lowercase().contains("sound")) "sound"
                    else "settings"
                    return listOf(
                        TaskStep(
                            id = "step_$startStepIdx",
                            label = "Open Settings",
                            capabilityId = "system_settings",
                            inputParams = mapOf("setting" to settingType)
                        )
                    )
                }
                if (target.lowercase().contains("browser") || target.lowercase() == "google" || target.lowercase() == "chrome") {
                    return listOf(
                        TaskStep(
                            id = "step_$startStepIdx",
                            label = "Open Browser",
                            capabilityId = "ui_open_app",
                            inputParams = mapOf("app" to "chrome", "appName" to "chrome")
                        )
                    )
                }
                if (target.lowercase().contains("chat") || (target.lowercase().contains("whatsapp") && (target.contains("with") || target.contains("for") || target.contains("to")))) {
                    val recipient = when {
                        target.contains(" with ") -> target.substringAfter(" with ")
                        target.contains(" for ") -> target.substringAfter(" for ")
                        target.contains(" to ") -> target.substringAfter(" to ")
                        else -> target.lowercase().replace("whatsapp", "").replace("chat", "").trim()
                    }.trim()
                    return listOf(
                        TaskStep(
                            id = "step_$startStepIdx",
                            label = "Open WhatsApp chat for $recipient",
                            capabilityId = "smart_delivery",
                            inputParams = mapOf(
                                "userGoal" to subCmd,
                                "contact" to recipient,
                                "targetApp" to "WhatsApp"
                            )
                        )
                    )
                }
                return listOf(
                    TaskStep(
                        id = "step_$startStepIdx",
                        label = "Open $target",
                        capabilityId = "ui_open_app",
                        inputParams = mapOf("app" to target, "appName" to target)
                    )
                )
            }
        }

        // E. CALL / DIAL CONTACT OR PHONE NUMBER
        if (lower.startsWith("call ") || lower.startsWith("dial ") || lower.startsWith("phone ")) {
            val isCall = lower.startsWith("call ")
            val target = extractTarget(subCmd, listOf("call ", "dial ", "phone "))
            if (target.isNotBlank()) {
                if (looksLikePhoneNumber(target)) {
                    val capId = if (isCall) "phone_call" else "phone_dialer"
                    val labelStr = if (isCall) "Call $target" else "Dial $target"
                    return listOf(
                        TaskStep(
                            id = "step_$startStepIdx",
                            label = labelStr,
                            capabilityId = capId,
                            inputParams = mapOf("phoneNumber" to target, "contactName" to target)
                        )
                    )
                } else if (isCall) {
                    val lookupId = "step_${startStepIdx}_lookup"
                    val callId = "step_${startStepIdx}_call"
                    return listOf(
                        TaskStep(
                            id = lookupId,
                            label = "Lookup contact $target",
                            capabilityId = "contact_lookup",
                            inputParams = mapOf("query" to target, "contact" to target, "name" to target)
                        ),
                        TaskStep(
                            id = callId,
                            label = "Call $target",
                            capabilityId = "phone_call",
                            dependsOnStepIds = listOf(lookupId),
                            inputParams = mapOf("contactName" to target)
                        )
                    )
                } else {
                    val lookupId = "step_${startStepIdx}_lookup"
                    val dialId = "step_${startStepIdx}_dial"
                    return listOf(
                        TaskStep(
                            id = lookupId,
                            label = "Lookup contact $target",
                            capabilityId = "contact_lookup",
                            inputParams = mapOf("query" to target, "contact" to target, "name" to target)
                        ),
                        TaskStep(
                            id = dialId,
                            label = "Open dialer for $target",
                            capabilityId = "phone_dialer",
                            dependsOnStepIds = listOf(lookupId),
                            inputParams = mapOf("contactName" to target)
                        )
                    )
                }
            }
        }

        // F. SHOW RESULTS / VERIFY STEP
        if (lower == "show the results" || lower == "show results" || lower == "display results") {
            return listOf(
                TaskStep(
                    id = "step_$startStepIdx",
                    label = "Show Results",
                    capabilityId = "text_reasoning",
                    inputParams = mapOf("goal" to subCmd)
                )
            )
        }

        // G. FLASHLIGHT / TORCH
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val isOff = lower.contains("off") || lower.contains("disable")
            val actionText = if (isOff) "off" else "on"
            return listOf(
                TaskStep(
                    id = "step_$startStepIdx",
                    label = "Turn $actionText flashlight",
                    capabilityId = "flashlight",
                    inputParams = mapOf("state" to actionText, "action" to actionText)
                )
            )
        }

        // H. SYSTEM MOBILE DATA
        if (lower.contains("mobile data") || lower.contains("cellular data") || lower.contains("data connection")) {
            val isOff = lower.contains("off") || lower.contains("disable") || lower.contains("turn off") || lower.contains("switch off")
            val stateStr = if (isOff) "off" else "on"
            return listOf(
                TaskStep(
                    id = "step_$startStepIdx",
                    label = "Turn $stateStr mobile data",
                    capabilityId = "system_mobile_data",
                    inputParams = mapOf("state" to stateStr, "action" to stateStr)
                )
            )
        }

        // I. SYSTEM WI-FI
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            val isQuery = lower.contains("is ") || lower.contains("status") || lower.contains("check") || lower.contains("state")
            val isOff = lower.contains("off") || lower.contains("disable") || lower.contains("turn off") || lower.contains("switch off")
            val stateStr = if (isQuery) "query" else if (isOff) "off" else "on"
            val labelStr = if (isQuery) "Check Wi-Fi status" else "Turn $stateStr Wi-Fi"
            return listOf(
                TaskStep(
                    id = "step_$startStepIdx",
                    label = labelStr,
                    capabilityId = "system_wifi",
                    inputParams = mapOf("state" to stateStr, "action" to stateStr)
                )
            )
        }

        // J. SYSTEM BLUETOOTH
        if (lower.contains("bluetooth")) {
            val isOff = lower.contains("off") || lower.contains("disable") || lower.contains("turn off") || lower.contains("switch off")
            val stateStr = if (isOff) "off" else "on"
            return listOf(
                TaskStep(
                    id = "step_$startStepIdx",
                    label = "Turn $stateStr Bluetooth",
                    capabilityId = "system_bluetooth",
                    inputParams = mapOf("state" to stateStr, "action" to stateStr)
                )
            )
        }

        // K. SYSTEM VOLUME
        if (lower.contains("volume")) {
            val isUp = lower.contains("up") || lower.contains("increase") || lower.contains("raise")
            val isDown = lower.contains("down") || lower.contains("lower") || lower.contains("decrease")
            val dirStr = if (isUp) "up" else if (isDown) "down" else "mute"
            return listOf(
                TaskStep(
                    id = "step_$startStepIdx",
                    label = "Adjust volume ($dirStr)",
                    capabilityId = "system_volume",
                    inputParams = mapOf("direction" to dirStr)
                )
            )
        }

        // L. SYSTEM BRIGHTNESS
        if (lower.contains("brightness") || lower.contains("screen brighter") || lower.contains("dim screen")) {
            val isUp = lower.contains("up") || lower.contains("increase") || lower.contains("brighter")
            val dirStr = if (isUp) "up" else "down"
            return listOf(
                TaskStep(
                    id = "step_$startStepIdx",
                    label = "Adjust brightness ($dirStr)",
                    capabilityId = "system_brightness",
                    inputParams = mapOf("direction" to dirStr)
                )
            )
        }

        // M. SYSTEM AIRPLANE MODE
        if (lower.contains("airplane mode") || lower.contains("flight mode")) {
            val isOff = lower.contains("off") || lower.contains("disable")
            val stateStr = if (isOff) "off" else "on"
            return listOf(
                TaskStep(
                    id = "step_$startStepIdx",
                    label = "Turn $stateStr airplane mode",
                    capabilityId = "system_airplane_mode",
                    inputParams = mapOf("state" to stateStr, "action" to stateStr)
                )
            )
        }

        // N. ALARM & TIMER INTENT
        if (lower.startsWith("set an alarm") || lower.startsWith("set alarm") || lower.startsWith("set timer") || lower.contains("alarm for") || lower.contains("alarm at") || lower.startsWith("alarm ")) {
            val isTimer = lower.contains("timer")
            val paramsMap = mutableMapOf<String, String>()
            if (isTimer) {
                val numMatch = Regex("""\b(\d+)\b""").find(lower)
                val mins = numMatch?.groupValues?.get(1) ?: "10"
                paramsMap["minutes"] = mins
                paramsMap["message"] = "ACE Timer"
                return listOf(
                    TaskStep(
                        id = "step_$startStepIdx",
                        label = "Set timer for $mins minutes",
                        capabilityId = "set_alarm",
                        inputParams = paramsMap
                    )
                )
            } else {
                var hour = "7"
                var minute = "0"
                val timeMatch = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE).find(lower)
                if (timeMatch != null) {
                    val h = timeMatch.groupValues[1].toIntOrNull() ?: 7
                    val m = timeMatch.groupValues[2].toIntOrNull() ?: 0
                    val ampm = timeMatch.groupValues[3].lowercase()
                    val finalH = if (ampm == "pm" && h < 12) h + 12 else if (ampm == "am" && h == 12) 0 else h
                    hour = finalH.toString()
                    minute = m.toString()
                } else {
                    val numMatch = Regex("""\b(\d{1,2})\b""").find(lower)
                    if (numMatch != null) {
                        hour = numMatch.groupValues[1]
                    }
                }
                paramsMap["hour"] = hour
                paramsMap["minute"] = minute
                paramsMap["message"] = "ACE Alarm"
                return listOf(
                    TaskStep(
                        id = "step_$startStepIdx",
                        label = "Set alarm for ${hour.padStart(2, '0')}:${minute.padStart(2, '0')}",
                        capabilityId = "set_alarm",
                        inputParams = paramsMap
                    )
                )
            }
        }

        return emptyList()
    }

    private fun extractStructuredWorkflow(goal: String): List<TaskStep> {
        val lower = goal.lowercase().trim()

        // E.g. "Find a route to the nearest major railway station"
        if (lower.contains("route") || lower.contains("directions") || lower.contains("railway station") || lower.contains("library")) {
            if (lower.contains("route") || lower.contains("directions") || lower.contains("way to")) {
                val target = lower.replace(Regex("""^(find a route to|get directions to|directions to|show route to|route to|find route to)\s+"""), "").trim()
                return listOf(
                    TaskStep(
                        id = "step_1",
                        label = "Find route to $target",
                        capabilityId = "route_directions",
                        inputParams = mapOf("destination" to target, "target" to target)
                    )
                )
            }
        }

        // E.g. "Find my PDF documents and open the most recently modified one"
        if (lower.contains("find") && (lower.contains("pdf") || lower.contains("document") || lower.contains("file"))) {
            val docType = if (lower.contains("pdf")) "PDF" else "document"
            val fileStepId = "step_1_find"
            val steps = mutableListOf(
                TaskStep(
                    id = fileStepId,
                    label = "Find latest $docType",
                    capabilityId = "file_discovery",
                    inputParams = mapOf("query" to docType, "fileType" to docType)
                )
            )
            if (lower.contains("share") || lower.contains("send")) {
                steps.add(
                    TaskStep(
                        id = "step_2_share",
                        label = "Share $docType",
                        capabilityId = "app_share",
                        dependsOnStepIds = listOf(fileStepId),
                        inputParams = mapOf("summary" to "Sharing $docType")
                    )
                )
            }
            return steps
        }

        // E.g. "Send the latest photo from gallery to Ravi on WhatsApp" or "Open WhatsApp chat with Ravi"
        if (lower.contains("send") || lower.contains("share") || (lower.contains("whatsapp") && (lower.contains("chat") || lower.contains("message") || lower.contains("open")))) {
            val parsed = com.ace.app.delivery.DeliveryRequestParser.parse(goal)
            val fileType = parsed.attachmentType?.name?.lowercase() ?: "attachment"
            val recipient = parsed.recipientName.orEmpty()
            val appName = parsed.targetApp?.name ?: "Generic Share"

            val labelStr = when {
                parsed.action == com.ace.app.delivery.DeliveryAction.OPEN_CHAT && recipient.isNotBlank() -> "Open $appName chat with $recipient"
                recipient.isNotBlank() -> "Send $fileType to $recipient on $appName"
                else -> "Share $fileType via $appName"
            }

            return listOf(
                TaskStep(
                    id = "step_1_delivery",
                    label = labelStr,
                    capabilityId = "smart_delivery",
                    inputParams = mapOf(
                        "userGoal" to goal,
                        "fileType" to fileType,
                        "contact" to recipient,
                        "targetApp" to appName
                    )
                )
            )
        }


        // E.g. "Open Clear Scanner and find my recent document"
        if (lower.contains("clear scanner") || lower.contains("cam scanner") || lower.contains("document scanner")) {
            val appName = if (lower.contains("clear scanner")) "clear scanner" else "scanner"
            return listOf(
                TaskStep(
                    id = "step_1",
                    label = "Open $appName",
                    capabilityId = "ui_open_app",
                    inputParams = mapOf("appName" to appName, "app" to appName)
                ),
                TaskStep(
                    id = "step_2",
                    label = "Find recent document",
                    capabilityId = "file_discovery",
                    inputParams = mapOf("query" to "document")
                )
            )
        }

        return emptyList()
    }

    private fun extractTarget(original: String, prefixes: List<String>): String {
        var result = original.trim()
        for (prefix in prefixes) {
            if (result.lowercase().startsWith(prefix.lowercase())) {
                result = result.substring(prefix.length).trim()
                break
            }
        }
        return result.trim().trimEnd('.', ',', '!', '?').trim()
    }

    private fun isWebUrl(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) return true
        return lower.endsWith(".edu") || lower.endsWith(".com") || lower.endsWith(".org") ||
                lower.endsWith(".net") || lower.endsWith(".in") || lower.endsWith(".gov")
    }

    private fun looksLikePhoneNumber(text: String): Boolean {
        val clean = text.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
        return clean.matches(Regex("""^\+?\d{3,15}$"""))
    }

    private data class QualifiedSearch(
        val cleanQuery: String,
        val targetApp: String,
        val canonicalAppName: String
    )

    private fun extractAppQualifier(rawQuery: String): QualifiedSearch? {
        val trimmed = rawQuery.trim()
        val match = Regex("""\s+(on|in)\s+([a-zA-Z0-9\s\.\-]+)$""", RegexOption.IGNORE_CASE).find(trimmed)
        if (match != null) {
            val appRaw = match.groupValues[2].trim()
            val cleanQuery = trimmed.substring(0, match.range.first).trim()
            if (cleanQuery.isNotBlank() && appRaw.isNotBlank() && appRaw.lowercase() != "google" && appRaw.lowercase() != "the web" && appRaw.lowercase() != "web" && appRaw.lowercase() != "internet") {
                val canonicalApp = appRaw.split(" ").joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                return QualifiedSearch(cleanQuery = cleanQuery, targetApp = appRaw.lowercase(), canonicalAppName = canonicalApp)
            }
        }
        return null
    }
}

