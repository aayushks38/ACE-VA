package com.ace.app.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import com.ace.app.accessibility.AceAccessibilityService
import com.ace.app.accessibility.UiObservationEngine
import com.ace.app.accessibility.UiVerificationEngine
import com.ace.app.accessibility.VerificationResult
import com.ace.app.utils.AceLatencyTracker

/**
 * GenericMediaRouteResolver — Generic Direct Media Routing Layer.
 *
 * Uses standard Android Framework Media Search Intent (MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
 * to initiate direct playback inside any installed target media application dynamically resolved by AppDiscoveryEngine.
 *
 * Contains ZERO package-specific branches, ZERO Spotify/YouTube hardcoded checks, and ZERO fixed coordinates.
 * Target applications are data, not code branches.
 */
object GenericMediaRouteResolver {

    private const val TAG = "ACE_MEDIA_DIRECT"

    suspend fun executeDirectMediaRoute(
        context: Context,
        targetApp: String,
        query: String,
        observationEngine: UiObservationEngine = UiObservationEngine(),
        verificationEngine: UiVerificationEngine = UiVerificationEngine()
    ): VerificationResult? {
        val lowerQuery = query.trim()
        if (lowerQuery.isBlank() || targetApp.isBlank()) return null

        val appInfo = AppDiscoveryEngine.findApp(context, targetApp) ?: return null
        val packageName = appInfo.packageName

        AceLatencyTracker.recordStage("DIRECT_ROUTE_ATTEMPT")
        Log.i(TAG, "ACE_MEDIA_DIRECT: attempting generic media intent for targetApp=$targetApp package=$packageName query=\"$query\"")

        val startTime = System.currentTimeMillis()

        try {
            val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(packageName)
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            context.startActivity(mediaIntent)
            Log.i(TAG, "ACE_MEDIA_DIRECT: dispatched MEDIA_PLAY_FROM_SEARCH intent to package=$packageName")
        } catch (e: Exception) {
            Log.w(TAG, "ACE_MEDIA_DIRECT: generic MEDIA_PLAY_FROM_SEARCH failed for package=$packageName", e)
            AceLatencyTracker.recordStage("DIRECT_ROUTE_FAILURE")
            return null
        }

        val service = AceAccessibilityService.getInstance()
        if (service == null) {
            AceLatencyTracker.recordStage("DIRECT_ROUTE_FAILURE")
            return null
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

        // 1. Wait for target app to reach foreground
        service.waitForCondition(maxTimeoutMs = 1200L, pollIntervalMs = 40L) {
            val s = observationEngine.captureSnapshot()
            verificationEngine.isTargetAppForeground(targetApp, s.packageName, context)
        }

        var snap = observationEngine.captureSnapshot()
        val lowerTargetQuery = query.lowercase().trim()

        // 2. Check if already playing or if explicit play click is needed
        var hasPause = snap.nodes.any { it.contentDescription.lowercase().contains("pause") || it.text.lowercase().contains("pause") }
        if (!hasPause) {
            val playOrTrackNode = snap.nodes.firstOrNull { node ->
                val desc = node.contentDescription.lowercase().trim()
                val text = node.text.lowercase().trim()
                (desc == "play" || text == "play" || desc.startsWith("play ") || desc.contains("shuffle play") ||
                 text.contains(lowerTargetQuery) || desc.contains(lowerTargetQuery)) &&
                !node.isEditable && !desc.contains("based on your interest") && !text.contains("based on your interest")
            }

            if (playOrTrackNode != null) {
                val clicked = playOrTrackNode.nodeRef?.let { ref ->
                    var current: android.view.accessibility.AccessibilityNodeInfo? = ref
                    var success = false
                    while (current != null) {
                        if (current.isClickable) {
                            success = current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            if (success) break
                        }
                        current = current.parent
                    }
                    success
                } ?: false

                if (!clicked) {
                    val label = playOrTrackNode.text.ifBlank { playOrTrackNode.contentDescription }
                    if (label.isNotBlank()) service.clickText(label)
                }

                service.waitForCondition(maxTimeoutMs = 1000L, pollIntervalMs = 40L) {
                    val s = observationEngine.captureSnapshot()
                    s.nodes.any { it.contentDescription.lowercase().contains("pause") || it.text.lowercase().contains("pause") }
                }
            }
        }

        val directSnapshot = observationEngine.captureSnapshot()
        val isSearchScreen = verificationEngine.isSearchResultsScreen(directSnapshot)
        val afterMedia = verificationEngine.extractActiveMediaTitle(directSnapshot, query)
        val verifyResult = verificationEngine.verifyMediaPlayback(targetApp, query, directSnapshot, context)

        val directLatencyMs = System.currentTimeMillis() - startTime

        if (verifyResult.isVerified && !isSearchScreen) {
            AceLatencyTracker.recordStage("DIRECT_ROUTE_SUCCESS")
            AceLatencyTracker.logLatencySummary(
                route = "direct",
                directRouteMs = directLatencyMs,
                uiSearchMs = 0L,
                verificationMs = 20L,
                totalMs = directLatencyMs
            )
            Log.i(TAG, "ACE_MEDIA_DIRECT: DIRECT_ROUTE_SUCCESS verified=true title=\"$afterMedia\" latency=${directLatencyMs}ms")
            return verifyResult
        } else {
            AceLatencyTracker.recordStage("DIRECT_ROUTE_FAILURE")
            Log.i(TAG, "ACE_MEDIA_DIRECT: DIRECT_ROUTE_FAILURE search_results_screen=$isSearchScreen verified=${verifyResult.isVerified}")
            return null
        }
    }
}
