package com.ace.app.agent

import android.util.Log

data class GoalCompletenessResult(
    val goal: String,
    val requiredActions: List<String>,
    val plannedActions: List<String>,
    val coveragePercentage: Int,
    val isPlanComplete: Boolean
)

object GoalRequirementExtractor {

    fun extractRequiredActions(goal: String): List<String> {
        val lower = goal.lowercase().trim()
        val actions = mutableListOf<String>()

        // 1. App opening intent
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val appTarget = extractAppTarget(lower)
            if (appTarget.isNotBlank() && appTarget != "settings" && !appTarget.contains(".edu") && !appTarget.contains(".com")) {
                actions.add("OPEN_APP")
            }
        }

        // 2. Web URL intent
        if (lower.contains("go to ") || lower.contains("navigate to ") || lower.contains(".edu") || lower.contains(".com") || lower.startsWith("http")) {
            actions.add("OPEN_URL")
        }

        // 3. Search & Route intent
        if (lower.contains("route") || lower.contains("directions") || lower.contains("way to")) {
            actions.add("ROUTE_DIRECTIONS")
        } else if (lower.contains("search") || lower.contains("find ") || lower.contains("google ") || lower.contains("look for ")) {
            if (lower.contains("pdf") || lower.contains("file") || lower.contains("document") || lower.contains("downloads")) {
                actions.add("FILE_DISCOVER")
            } else {
                actions.add("SEARCH")
            }
        }

        // 4. Media Playback intent
        if (lower.contains("play ") || lower.contains("listen to ") || lower.contains("stream ")) {
            actions.add("PLAY_MEDIA")
        }

        // 5. File sharing / dispatch intent
        if (lower.contains("share") || lower.contains("send ") || lower.contains("prepare it to share") || lower.contains("prepare to share")) {
            if (lower.contains("photo") || lower.contains("gallery") || lower.contains("picture") || lower.contains("image") || lower.contains("pdf") || lower.contains("file") || lower.contains("document") || lower.contains("latest") || lower.contains("recent")) {
                actions.add("FILE_DISCOVER")
            }
            if (lower.contains("to ") || lower.contains("contact") || lower.contains("ravi")) {
                actions.add("CONTACT_LOOKUP")
            }
            if (lower.contains("whatsapp") || lower.contains("email") || lower.contains("gmail") || lower.contains("app")) {
                actions.add("OPEN_APP")
            }
            actions.add("FILE_SHARE")
        }

        // 6. UI typing / input intent
        if (lower.contains("type ") || lower.contains("enter ") || lower.contains("fill ")) {
            actions.add("TYPE_TEXT")
        }

        // 7. Navigation back intent
        if (lower.contains("go back") || lower.contains("press back")) {
            actions.add("NAVIGATE_BACK")
        }

        // 8. Flashlight / torch intent
        if (lower.contains("flashlight") || lower.contains("torch")) {
            actions.add("FLASHLIGHT")
        }

        // 9. Phone call / dial intent
        if (lower.startsWith("call ") || lower.contains(" call ")) {
            actions.add("PHONE_CALL")
        } else if (lower.contains("dial ") || lower.contains("phone ")) {
            actions.add("PHONE_DIAL")
        }

        // 10. System settings / device control intent
        if (lower.contains("mobile data") || lower.contains("cellular data")) {
            actions.add("SYSTEM_MOBILE_DATA")
        } else if (lower.contains("wifi") || lower.contains("wi-fi")) {
            actions.add("SYSTEM_WIFI")
        } else if (lower.contains("bluetooth")) {
            actions.add("SYSTEM_BLUETOOTH")
        } else if (lower.contains("airplane mode") || lower.contains("flight mode")) {
            actions.add("SYSTEM_AIRPLANE_MODE")
        } else if (lower.contains("volume")) {
            actions.add("SYSTEM_VOLUME")
        } else if (lower.contains("brightness") || lower.contains("screen brighter") || lower.contains("dim screen")) {
            actions.add("SYSTEM_BRIGHTNESS")
        } else if (lower.contains("settings")) {
            actions.add("SYSTEM_SETTINGS")
        }

        if (actions.isEmpty()) {
            actions.add("GENERAL_ACTION")
        }

        return actions.distinct()
    }

    private fun extractAppTarget(lower: String): String {
        val targets = listOf("spotify", "youtube", "chrome", "maps", "whatsapp", "google", "settings", "app")
        return targets.firstOrNull { lower.contains(it) } ?: "app"
    }

    fun evaluatePlanCompleteness(goal: String, steps: List<TaskStep>): GoalCompletenessResult {
        val required = extractRequiredActions(goal)
        val planned = steps.map { mapToUniversalName(it.capabilityId) }.distinct()

        val matching = required.count { req ->
            planned.contains(req) ||
            // Cross-type equivalences
            (req == "OPEN_APP" && planned.contains("OPEN_URL")) ||
            (req == "SEARCH" && (planned.contains("FILE_DISCOVER") || planned.contains("OPEN_URL"))) ||
            (req == "PLAY_MEDIA" && (planned.contains("SEARCH") || planned.contains("OPEN_APP"))) ||
            // GENERAL_ACTION is satisfied by ANY non-empty plan (wildcard)
            (req == "GENERAL_ACTION" && planned.isNotEmpty()) ||
            // Instant/deterministic capabilities always satisfy themselves
            (req == "FLASHLIGHT" && planned.any { it == "FLASHLIGHT" || it.contains("FLASHLIGHT") }) ||
            (req == "PHONE_CALL" && planned.any { it == "PHONE_CALL" }) ||
            (req == "PHONE_DIAL" && planned.any { it == "PHONE_DIAL" || it == "PHONE_CALL" }) ||
            (req == "SYSTEM_SETTINGS" && planned.any { it.contains("SETTINGS") || it.contains("SYSTEM") })
        }

        val coverage = if (required.isNotEmpty()) {
            (matching * 100) / required.size
        } else {
            100
        }

        val isComplete = coverage >= 100

        Log.i("ACE_PLAN", "ACE_PLAN: goal=$goal")
        Log.i("ACE_PLAN", "ACE_PLAN: required_actions=$required")
        Log.i("ACE_PLAN", "ACE_PLAN: planned_actions=$planned")
        Log.i("ACE_PLAN", "ACE_PLAN: coverage=${coverage}%")
        Log.i("ACE_PLAN", "ACE_PLAN: plan_complete=$isComplete")

        return GoalCompletenessResult(
            goal = goal,
            requiredActions = required,
            plannedActions = planned,
            coveragePercentage = coverage,
            isPlanComplete = isComplete
        )
    }

    fun mapToUniversalName(capabilityId: String): String {
        return when (capabilityId.lowercase().trim()) {
            "app.launch", "ui_open_app", "open_app" -> "OPEN_APP"
            "web.open", "web_open_url", "open_url" -> "OPEN_URL"
            "web.search", "web_search", "youtube_search", "youtube.search", "universal_search", "search_in_app", "search" -> "SEARCH"
            "ui.type", "ui_type", "type_text", "fill_field" -> "TYPE_TEXT"
            "ui.find", "ui.click", "ui_click", "click", "select", "accessibility.execute" -> "CLICK"
            "ui.scroll", "ui_scroll", "scroll" -> "SCROLL"
            "system_mobile_data" -> "SYSTEM_MOBILE_DATA"
            "system_wifi" -> "SYSTEM_WIFI"
            "system_bluetooth" -> "SYSTEM_BLUETOOTH"
            "system_volume" -> "SYSTEM_VOLUME"
            "system_brightness" -> "SYSTEM_BRIGHTNESS"
            "system_airplane_mode" -> "SYSTEM_AIRPLANE_MODE"
            "device.open_settings", "system_settings" -> "SYSTEM_SETTINGS"
            "phone.call", "phone_call" -> "PHONE_CALL"
            "phone.dial", "phone_dialer" -> "PHONE_DIAL"
            "contact.lookup", "contact_lookup" -> "CONTACT_LOOKUP"
            "file.find", "file.open", "file_manager", "file_discovery", "file_discover" -> "FILE_DISCOVER"
            "media_playback", "play_media" -> "PLAY_MEDIA"
            "pause_media" -> "PAUSE_MEDIA"
            "file_pick" -> "FILE_PICK"
            "file_attach" -> "FILE_ATTACH"
            "app_share", "send_document", "file_share" -> "FILE_SHARE"
            "upload_file" -> "UPLOAD_FILE"
            "download_file" -> "DOWNLOAD_FILE"
            "submit_form" -> "SUBMIT_FORM"
            "ui_press_button", "navigate_back" -> "NAVIGATE_BACK"
            "flashlight" -> "FLASHLIGHT"
            else -> capabilityId.uppercase()
        }
    }

    fun mapUniversalToReadable(universalAction: String): String {
        return when (universalAction) {
            "OPEN_APP" -> "Open App"
            "OPEN_URL" -> "Open Web Page"
            "SEARCH" -> "Web Search"
            "TYPE_TEXT" -> "Type Text"
            "CLICK" -> "Click UI Element"
            "SYSTEM_SETTINGS" -> "Open System Settings"
            "PHONE_DIAL" -> "Make Phone Call"
            "CONTACT_LOOKUP" -> "Lookup Contact"
            "FILE_DISCOVER" -> "Find Photo/File in Storage"
            "PLAY_MEDIA" -> "Play Media"
            "FILE_SHARE" -> "Send/Share Content"
            "NAVIGATE_BACK" -> "Navigate Back"
            else -> universalAction.replace("_", " ").lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    fun extractGoalRequirements(goal: String): List<GoalRequirement> {
        val lower = goal.lowercase().trim()
        val requirements = mutableListOf<GoalRequirement>()

        // Instant Battery Intent
        if (lower.contains("battery")) {
            return listOf(
                GoalRequirement(
                    id = "req_1_battery",
                    description = "Battery information retrieved"
                )
            )
        }

        // Instant Flashlight Intent
        if (lower.contains("flashlight") || lower.contains("torch")) {
            return listOf(
                GoalRequirement(
                    id = "req_1_flashlight",
                    description = "Flashlight state updated"
                )
            )
        }

        // Multi-step goal handling: "Open App and search" or "Open App and <action>"
        if ((lower.startsWith("open ") || lower.startsWith("launch ")) && (lower.contains(" and ") || lower.contains("search") || lower.contains("find"))) {
            val appTarget = extractAppTarget(lower)
            requirements.add(
                GoalRequirement(
                    id = "req_1_open_app",
                    description = "Requested application '$appTarget' launched"
                )
            )
            
            // Check for search action
            if (lower.contains("search ") || lower.contains("find ")) {
                val searchTerm = extractSearchTerm(lower)
                requirements.add(
                    GoalRequirement(
                        id = "req_2_search",
                        description = if (searchTerm.isNotBlank()) "Search for '$searchTerm' in app" else "Search content in app"
                    )
                )
            }
            
            return requirements
        }

        // Instant App Launch Intent (single-step)
        if ((lower.startsWith("open ") || lower.startsWith("launch ")) && !lower.contains("photo") && !lower.contains("file")) {
            return listOf(
                GoalRequirement(
                    id = "req_1_open_app",
                    description = "Requested application launch intent sent"
                )
            )
        }

        // Instant Time/Date Intent
        if (lower.contains("time") || lower.contains("date") || lower.contains("today")) {
            return listOf(
                GoalRequirement(
                    id = "req_1_time_date",
                    description = "Current system date and time retrieved"
                )
            )
        }

        // Instant Storage Intent
        if (lower.contains("storage") || lower.contains("space") || lower.contains("memory")) {
            return listOf(
                GoalRequirement(
                    id = "req_1_storage",
                    description = "Available device storage checked"
                )
            )
        }

        if (lower.contains("send") || lower.contains("share") || (lower.contains("whatsapp") && (lower.contains("chat") || lower.contains("open")))) {
            val appTarget = when {
                lower.contains("whatsapp") -> "WhatsApp"
                lower.contains("telegram") -> "Telegram"
                lower.contains("sms") -> "SMS"
                else -> "Target App"
            }
            val recipientName = extractRecipientName(goal)
            val isMedia = lower.contains("photo") || lower.contains("image") || lower.contains("picture") || lower.contains("pdf") || lower.contains("video") || lower.contains("scan") || lower.contains("document")

            val reqDesc = if (isMedia && recipientName != "Recipient") {
                "Smart delivery of media to $recipientName on $appTarget"
            } else if (recipientName != "Recipient") {
                "Smart delivery to $recipientName on $appTarget"
            } else {
                "Smart delivery dispatch via $appTarget"
            }

            return listOf(
                GoalRequirement(
                    id = "req_1_delivery",
                    description = reqDesc
                )
            )
        }

        val actions = extractRequiredActions(goal)
        actions.forEachIndexed { idx, action ->
            requirements.add(
                GoalRequirement(
                    id = "req_${idx + 1}_${action.lowercase()}",
                    description = mapUniversalToReadable(action)
                )
            )
        }
        return requirements
    }

    private fun extractSearchTerm(lower: String): String {
        val searchPattern = Regex("""search\s+(?:for\s+)?["']?([^"']+)["']?(?:\s+|$)""", RegexOption.IGNORE_CASE)
        val match = searchPattern.find(lower)
        return match?.groupValues?.getOrNull(1)?.trim() ?: ""
    }


    private fun extractRecipientName(goal: String): String {
        val regex = Regex("""\bto\s+([A-Z][a-z]+|\b[a-z]+\b)""", RegexOption.IGNORE_CASE)
        val match = regex.find(goal)
        if (match != null) {
            val raw = match.groupValues[1].trim()
            if (raw.lowercase() != "whatsapp" && raw.lowercase() != "gallery" && raw.lowercase() != "the") {
                return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
        return "Recipient"
    }
}
