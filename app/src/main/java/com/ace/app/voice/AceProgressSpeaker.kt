package com.ace.app.voice

import android.util.Log

/**
 * AceProgressSpeaker — central hub for all execution-progress speech in ACE.
 *
 * Rules:
 * - All progress speech flows through here, never raw TTS.speak() calls scattered around.
 * - Only user-facing milestones are spoken — no internal capability names or step numbers.
 * - Throttled: minimum 1200ms between progress utterances.
 * - Coalesced: if speaking, the next event replaces any pending one (no long queues).
 * - Generation-guarded: stale callbacks from cancelled tasks are silently dropped.
 * - Deduplicates: final response won't repeat the last progress phrase.
 */
object AceProgressSpeaker {

    private const val TAG = "ACE_VOICE"
    private const val MIN_SPEECH_GAP_MS = 1200L

    // The VoiceManager is set by TaskViewModel when it initialises.
    private var voiceManager: VoiceManager? = null
    private var currentGenerationId: Long = 0L

    /** Last phrase spoken so we can deduplicate. */
    var lastSpokenPhrase: String = ""
        private set

    private var lastSpokenAtMs: Long = 0L

    // Pending phrase waiting to fire after the current utterance ends.
    // We keep at most one pending phrase; newer events replace older ones.
    private var pendingPhrase: String? = null
    private var pendingGenerationId: Long = 0L

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun attach(vm: VoiceManager, generationId: Long) {
        voiceManager = vm
        currentGenerationId = generationId
    }

    /** Called when a new task starts or the user interrupts. Drops all pending speech. */
    fun clear(newGenerationId: Long) {
        currentGenerationId = newGenerationId
        pendingPhrase = null
        pendingGenerationId = 0L
        lastSpokenPhrase = ""
        lastSpokenAtMs = 0L
        Log.i(TAG, "ACE_VOICE: progress_cleared new_generation=$newGenerationId")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Speak a short task-acceptance acknowledgement ("On it.") immediately after task is accepted.
     * Silent for simple fast tasks (calculations, date/time, battery, etc.).
     */
    fun speakTaskAccepted(goal: String, isFastTask: Boolean, generationId: Long) {
        if (!isCurrentGeneration(generationId)) return
        if (isFastTask) {
            Log.d(TAG, "ACE_VOICE: task_accepted fast_task=true (silent)")
            return
        }
        val phrase = "On it."
        Log.i(TAG, "ACE_VOICE: task_accepted fast_task=false phrase=\"$phrase\" generation=$generationId")
        doSpeak(phrase, generationId)
    }

    /**
     * Speak a short phrase announcing that an action is about to start.
     * Only fires for multi-step / meaningful milestones.
     */
    fun speakActionStarted(capabilityId: String, params: Map<String, String>, generationId: Long) {
        val phrase = buildStartedPhrase(capabilityId, params) ?: return
        maybeSpeak(phrase, generationId, event = "ACTION_STARTED", action = capabilityId)
    }

    /**
     * Speak the final task result. Deduplicates against lastSpokenPhrase so we
     * don't repeat "Opening WhatsApp." if it was already said as a progress update.
     */
    fun speakTaskCompleted(spokenText: String, generationId: Long) {
        if (spokenText.isBlank()) return
        // If the final response is the same sentence we just said, condense to "Done."
        val phrase = if (spokenText.trim().equals(lastSpokenPhrase.trim(), ignoreCase = true) ||
            lastSpokenPhrase.isNotBlank() && spokenText.startsWith(lastSpokenPhrase.trimEnd('.'))
        ) {
            "Done."
        } else {
            spokenText
        }
        Log.i(TAG, "ACE_VOICE: progress_event=TASK_COMPLETED phrase=\"$phrase\" generation=$generationId")
        doSpeak(phrase, generationId)
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun maybeSpeak(phrase: String, generationId: Long, event: String, action: String) {
        if (!isCurrentGeneration(generationId)) {
            Log.w(TAG, "ACE_VOICE: progress_dropped stale generation=$generationId current=$currentGenerationId")
            return
        }
        // Dedup: same phrase as last spoken
        if (phrase.equals(lastSpokenPhrase, ignoreCase = true)) {
            Log.d(TAG, "ACE_VOICE: progress_deduped phrase=\"$phrase\"")
            return
        }

        Log.i(TAG, "ACE_VOICE: progress_event=$event action=$action")
        Log.i(TAG, "ACE_VOICE: progress_spoken=\"$phrase\"")

        val now = System.currentTimeMillis()
        if (now - lastSpokenAtMs < MIN_SPEECH_GAP_MS) {
            // Too soon — store as pending (replace any older pending)
            pendingPhrase = phrase
            pendingGenerationId = generationId
            Log.d(TAG, "ACE_VOICE: progress_throttled storing pending=\"$phrase\"")
            return
        }

        doSpeak(phrase, generationId)
    }

    private fun doSpeak(phrase: String, generationId: Long) {
        if (!isCurrentGeneration(generationId)) return
        val vm = voiceManager ?: return
        val sanitized = sanitizeForTts(phrase)
        if (sanitized.isBlank()) return
        lastSpokenPhrase = sanitized
        lastSpokenAtMs = System.currentTimeMillis()
        vm.speak(sanitized, generationId) { currentGenerationId }
    }

    private fun sanitizeForTts(raw: String): String {
        var clean = raw.trim()
        if (clean.isBlank()) return ""
        if (clean.startsWith("{") || clean.startsWith("[")) return "Done."
        clean = clean.replace(Regex("""\+?\d{7,15}"""), "")
        clean = clean.replace(Regex("""com\.[a-z0-9\._]+"""), "")
        clean = clean.replace(Regex("""https?://\S+"""), "")
        clean = clean.replace(Regex("""(phone_call|contact_lookup|ui_open_app|system_wifi|system_mobile_data|system_bluetooth)"""), "")
        return clean.trim().replace(Regex("""\s+"""), " ")
    }

    private fun isCurrentGeneration(id: Long): Boolean {
        if (id == 0L) return true
        val matches = id == currentGenerationId
        if (!matches) {
            Log.w(TAG, "ACE_TASK: STALE_REJECTED eventGeneration=$id currentGeneration=$currentGenerationId")
        }
        return matches
    }

    // ── Phrase mapping ───────────────────────────────────────────────────────

    /**
     * Maps a capability ID + params to a natural short English sentence.
     * Returns null for instant/fast capabilities that need no progress narration.
     */
    private fun buildStartedPhrase(capabilityId: String, params: Map<String, String>): String? {
        val id = capabilityId.lowercase().trim()
        val app = params["appName"] ?: params["app"] ?: ""
        val query = params["query"] ?: params["text"] ?: params["search"] ?: ""
        val contact = params["contactName"] ?: params["name"] ?: params["recipient"] ?: query
        val goal = params["goal"] ?: ""

        return when {
            // ── App opening ──────────────────────────────────────────────────
            id.contains("open_app") || id.contains("ui_open") || id == "open_app" -> {
                val appName = friendlyAppName(app.ifBlank { goal })
                if (appName.isNotBlank()) "Opening $appName." else null
            }

            // ── In-app search / UI search ────────────────────────────────────
            (id.contains("search") || id.contains("ui_search") || id.contains("search_in_app")) -> {
                when {
                    query.isNotBlank() && app.isNotBlank() -> "Searching for $query."
                    query.isNotBlank() -> "Searching for $query."
                    app.isNotBlank() -> "Searching in $app."
                    else -> "Searching."
                }
            }

            // ── File / media discovery ────────────────────────────────────────
            id.contains("file_discover") || id.contains("file_discovery") || id.contains("media_query") -> {
                val target = when {
                    goal.contains("photo", true) || goal.contains("image", true) || goal.contains("picture", true) -> "the latest photo"
                    goal.contains("video", true) -> "the latest video"
                    goal.contains("document", true) || goal.contains("pdf", true) -> "the document"
                    else -> "the file"
                }
                "Finding $target."
            }

            // ── Contact lookup ───────────────────────────────────────────────
            id.contains("contact") -> {
                if (contact.isNotBlank()) "Finding $contact." else "Looking up the contact."
            }

            // ── Share / send / deliver ───────────────────────────────────────
            id.contains("app_share") || id.contains("file_share") || id.contains("send_document") -> {
                "Preparing the file."
            }
            id.contains("send") && !id.contains("file") -> {
                "Sending it."
            }

            // ── Media playback ───────────────────────────────────────────────
            id.contains("media_playback") || id.contains("play_media") -> {
                val track = params["song"] ?: params["track"] ?: query
                if (track.isNotBlank()) "Playing $track." else "Playing."
            }

            // ── Web search ───────────────────────────────────────────────────
            id.contains("web_search") -> {
                if (query.isNotBlank()) "Searching for $query." else "Searching the web."
            }

            // ── Settings ─────────────────────────────────────────────────────
            id.contains("system_settings") || id.contains("settings") -> "Adjusting settings."

            // ── Type / click / scroll — low-level UI automation ──────────────
            // Only speak if it's a meaningful high-level action (not raw click)
            id.contains("ui_type") || id.contains("type_text") -> null   // silent
            id.contains("ui_click") || id == "click" -> null             // silent
            id.contains("ui_scroll") || id == "scroll" -> null           // silent
            id.contains("navigate_back") -> null                         // silent

            // ── Instant capabilities — no progress narration ─────────────────
            id.contains("battery") -> null
            id.contains("flashlight") || id.contains("torch") -> null
            id.contains("system_mobile_data") || id.contains("mobile_data") -> null
            id.contains("system_wifi") || id.contains("wifi") -> null
            id.contains("system_bluetooth") || id.contains("bluetooth") -> null
            id.contains("system_volume") || id.contains("volume") -> null
            id.contains("system_brightness") || id.contains("brightness") -> null
            id.contains("system_airplane_mode") || id.contains("airplane_mode") -> null
            id.contains("time") || id.contains("date") -> null
            id.contains("storage") || id.contains("memory") -> null
            id.contains("text_reasoning") -> null

            // ── Multi-step fallback ──────────────────────────────────────────
            else -> {
                // Only emit a generic phrase for clearly multi-step tasks
                if (params["goal"]?.split(" ")?.size ?: 0 > 3) "Working on it." else null
            }
        }
    }

    private fun friendlyAppName(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("settings") -> "Settings"
            lower.contains("chrome") -> "Chrome"
            lower.contains("spotify") -> "Spotify"
            lower.contains("youtube") -> "YouTube"
            lower.contains("maps") -> "Google Maps"
            lower.contains("gmail") -> "Gmail"
            lower.contains("camera") -> "Camera"
            lower.contains("gallery") || lower.contains("photos") -> "Gallery"
            lower.contains("calculator") -> "Calculator"
            lower.contains("calendar") -> "Calendar"
            lower.contains("clock") -> "Clock"
            lower.contains("telegram") -> "Telegram"
            lower.contains("instagram") -> "Instagram"
            lower.contains("twitter") || lower.contains("x.com") -> "Twitter"
            lower.contains("facebook") -> "Facebook"
            lower.contains("outlook") -> "Outlook"
            lower.contains("drive") -> "Google Drive"
            lower.contains("docs") -> "Google Docs"
            else -> raw.split(" ").firstOrNull { it.length > 2 }
                ?.replaceFirstChar { c -> c.uppercaseChar() } ?: ""
        }
    }
}
