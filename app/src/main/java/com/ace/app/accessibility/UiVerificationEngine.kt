package com.ace.app.accessibility

import android.content.Context
import android.util.Log
import com.ace.app.agent.ActionResultStatus
import com.ace.app.agent.AppDiscoveryEngine

class UiVerificationEngine {

    fun isTargetAppForeground(targetApp: String, currentPackageName: String, context: Context? = null): Boolean {
        val lowerApp = targetApp.lowercase().trim()
        val currentPkg = currentPackageName.lowercase().trim()
        if (currentPkg.isBlank()) return false

        val foundApp = if (context != null) AppDiscoveryEngine.findApp(context, targetApp) else null
        val appPkg = foundApp?.packageName?.lowercase()?.trim()

        return currentPkg.contains(lowerApp) || lowerApp.contains(currentPkg) || (appPkg != null && currentPkg == appPkg)
    }

    fun isSearchResultsScreen(snapshot: UiSnapshot): Boolean {
        val hasEditableSearch = snapshot.nodes.any { it.isEditable }
        val hasSearchResultSubtitle = snapshot.nodes.any { node ->
            val text = node.text.lowercase().trim()
            val desc = node.contentDescription.lowercase().trim()
            text.startsWith("song •") || desc.startsWith("song •") ||
            text.contains("top result") || desc.contains("top result") ||
            text == "songs" || desc == "songs" || text == "artists" || desc == "artists" ||
            text.contains("see all songs") || desc.contains("see all songs") ||
            text.contains("search results") || desc.contains("search results")
        }
        val hasPlayerPauseControl = snapshot.nodes.any { node ->
            val resId = node.resourceId.lowercase()
            val desc = node.contentDescription.lowercase()
            val text = node.text.lowercase()
            (desc.contains("pause") || text.contains("pause")) &&
            (resId.contains("player") || resId.contains("bar") || resId.contains("now_playing") || resId.contains("bottom") || resId.isBlank())
        }
        return (hasEditableSearch || hasSearchResultSubtitle) && !hasPlayerPauseControl
    }

    fun extractActiveMediaTitle(snapshot: UiSnapshot, requestedQuery: String): String {
        val lowerQuery = requestedQuery.lowercase().trim()

        // 1. Look for explicit player title view resource IDs first
        val playerTitleNode = snapshot.nodes.firstOrNull { node ->
            val resId = node.resourceId.lowercase()
            (resId.contains("track_name") || resId.contains("track_title") || resId.contains("song_title") ||
             resId.contains("media_title") || resId.contains("now_playing") || resId.endsWith(":id/title")) &&
            node.text.isNotBlank() && !node.isEditable
        }

        if (playerTitleNode != null) {
            return playerTitleNode.text
        }

        // 2. If screen is NOT search results list, look for non-editable matched node
        if (!isSearchResultsScreen(snapshot)) {
            val matchedNode = snapshot.nodes.firstOrNull { node ->
                val text = node.text.lowercase().trim()
                val desc = node.contentDescription.lowercase().trim()
                (text.contains(lowerQuery) || desc.contains(lowerQuery)) && !node.isEditable &&
                !text.contains("based on your interest") && !desc.contains("based on your interest") &&
                !text.startsWith("song •") && !desc.startsWith("song •") &&
                !text.contains("top result") && !desc.contains("top result") &&
                !text.contains("related") && !desc.contains("related") &&
                !text.contains("video") && !desc.contains("video") &&
                !text.contains("in search") && !desc.contains("in search") &&
                !text.contains("more options") && !desc.contains("more options") &&
                !text.contains("add suggestion") && !desc.contains("add suggestion") &&
                !text.contains("search results") && !desc.contains("search results")
            }

            if (matchedNode != null) {
                val title = matchedNode.text.ifBlank { matchedNode.contentDescription }
                if (title.isNotBlank()) return title
            }
        }

        return "unknown"
    }

    fun verifyMediaPlayback(
        targetApp: String,
        query: String,
        snapshot: UiSnapshot,
        context: Context? = null
    ): VerificationResult {
        val lowerQuery = query.lowercase().trim()
        val appMatched = isTargetAppForeground(targetApp, snapshot.packageName, context)

        val isSearchScreen = isSearchResultsScreen(snapshot)

        val hasPauseNode = snapshot.nodes.any { node ->
            val desc = node.contentDescription.lowercase().trim()
            val text = node.text.lowercase().trim()
            desc.contains("pause") || text.contains("pause")
        }

        val actualTitle = extractActiveMediaTitle(snapshot, query)
        val lowerTitle = actualTitle.lowercase().trim()

        val titleMatch = lowerTitle.contains(lowerQuery) || (actualTitle != "unknown" && lowerTitle == lowerQuery)

        val isAudioActive = try {
            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.isMusicActive == true
        } catch (_: Exception) { false }

        val isPlaying = (hasPauseNode || isAudioActive) && !isSearchScreen

        Log.i("ACE_UI_VERIFY", "ACE_UI_VERIFY: requested=\"$query\" actual=\"$actualTitle\" title_match=$titleMatch playing=$isPlaying search_results_only=$isSearchScreen")

        if (isSearchScreen) {
            Log.e("ACE_RESULT", "ACE_RESULT: status=UNVERIFIED reason=SEARCH_RESULTS_ONLY")
            return VerificationResult(
                isVerified = false,
                status = ActionResultStatus.FAILED,
                summary = "Target app remained on search results screen. Song did not start playing.",
                evidenceText = "SEARCH_RESULTS_ONLY"
            )
        }

        if (appMatched && isPlaying && titleMatch) {
            val evidence = "Active playback detected (pause=$hasPauseNode, audio=$isAudioActive) & title '$actualTitle' matches '$query'"
            Log.i("ACE_RESULT", "ACE_RESULT: status=SUCCESS evidence=\"$evidence\"")
            return VerificationResult(
                isVerified = true,
                status = ActionResultStatus.SUCCESS,
                summary = "Playback of '$query' started successfully on $targetApp.",
                evidenceText = evidence
            )
        }

        if (appMatched && isPlaying) {
            val evidence = "Media playing in $targetApp but track title could not be confirmed as '$query'"
            Log.w("ACE_RESULT", "ACE_RESULT: status=PARTIAL_SUCCESS message=\"$evidence\"")
            return VerificationResult(
                isVerified = false,
                status = ActionResultStatus.PARTIAL,
                summary = "Playback active in $targetApp, but title could not be confirmed.",
                evidenceText = evidence
            )
        }

        Log.e("ACE_RESULT", "ACE_RESULT: status=FAILED reason=\"$targetApp playback unverified or paused.\"")
        return VerificationResult(
            isVerified = false,
            status = ActionResultStatus.FAILED,
            summary = "Could not confirm playback of '$query' on $targetApp.",
            evidenceText = "Playback unverified"
        )
    }

    fun verifySearchGoal(
        targetApp: String,
        query: String,
        snapshot: UiSnapshot,
        context: Context? = null
    ): VerificationResult {
        val lowerQuery = query.lowercase().trim()
        val currentPkg = snapshot.packageName.lowercase().trim()
        val appMatched = isTargetAppForeground(targetApp, currentPkg, context)

        val matchedNode = snapshot.nodes.firstOrNull { node ->
            val text = node.text.lowercase()
            val desc = node.contentDescription.lowercase()
            text.contains(lowerQuery) || desc.contains(lowerQuery)
        }

        val hasEvidence = matchedNode != null || snapshot.nodes.any { node ->
            val text = node.text.lowercase()
            text.contains("results") || text.contains("showing") || text.contains("top matches") || text.contains("search") || text.contains("places") || text.contains("restaurants")
        }

        Log.i("ACE_UI_VERIFY", "ACE_UI_VERIFY: package=$currentPkg app_matched=$appMatched query_visible=${matchedNode != null} evidence=$hasEvidence")

        if (appMatched && (matchedNode != null || hasEvidence)) {
            val evidence = matchedNode?.text?.ifBlank { matchedNode.contentDescription } ?: "Search results UI active"
            Log.i("ACE_RESULT", "ACE_RESULT: status=SUCCESS evidence=\"$evidence\"")
            return VerificationResult(
                isVerified = true,
                status = ActionResultStatus.SUCCESS,
                summary = "Verified search results for '$query' inside $targetApp.",
                evidenceText = evidence
            )
        }

        if (appMatched && !hasEvidence) {
            Log.w("ACE_RESULT", "ACE_RESULT: status=PARTIAL_SUCCESS message=\"$targetApp opened, but search results for '$query' could not be fully verified.\"")
            return VerificationResult(
                isVerified = false,
                status = ActionResultStatus.PARTIAL,
                summary = "$targetApp opened successfully, but ACE could not verify search results for '$query'.",
                evidenceText = "App active without confirmed query text"
            )
        }

        Log.e("ACE_RESULT", "ACE_RESULT: status=FAILED reason=\"Target app $targetApp not in foreground or UI unresponsive.\"")
        return VerificationResult(
            isVerified = false,
            status = ActionResultStatus.FAILED,
            summary = "Failed to complete in-app search in $targetApp.",
            evidenceText = "App not active"
        )
    }
}
