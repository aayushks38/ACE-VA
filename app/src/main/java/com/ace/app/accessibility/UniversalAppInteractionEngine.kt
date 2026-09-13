package com.ace.app.accessibility

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ace.app.agent.ActionResultStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class UniversalAppInteractionEngine(
    private val observationEngine: UiObservationEngine = UiObservationEngine(),
    private val elementFinder: UiElementFinder = UiElementFinder(),
    private val verificationEngine: UiVerificationEngine = UiVerificationEngine()
) {

    suspend fun executeInAppSearch(
        context: Context,
        targetApp: String,
        query: String,
        maxActionAttempts: Int = 1
    ): VerificationResult = withContext(Dispatchers.Main) {
        val service = AceAccessibilityService.getInstance()
        if (service == null || !AceAccessibilityService.isServiceEnabled(context)) {
            Log.w("ACE_UI_ERROR", "ACE_UI_ERROR: Accessibility service is disabled.")
            return@withContext VerificationResult(
                isVerified = false,
                status = ActionResultStatus.NEEDS_USER_ACTION,
                summary = "ACE Accessibility Service is required to interact inside $targetApp. Please enable ACE in Accessibility Settings."
            )
        }

        com.ace.app.utils.AceLatencyTracker.mark("app_launch_start")
        Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=OPEN_APP target_app=$targetApp")
        val launched = service.launchApp(context, targetApp)
        com.ace.app.utils.AceLatencyTracker.mark("app_launch_end")

        if (!launched) {
            Log.e("ACE_UI_ERROR", "ACE_UI_ERROR: Failed to launch app package for '$targetApp'")
            return@withContext VerificationResult(
                isVerified = false,
                status = ActionResultStatus.FAILED,
                summary = "Could not launch application '$targetApp'.",
                evidenceText = "App launch failed"
            )
        }

        com.ace.app.utils.AceLatencyTracker.mark("app_wait_start")
        service.waitForCondition(maxTimeoutMs = 1200L, pollIntervalMs = 20L) {
            val root = service.rootInActiveWindow
            root != null && verificationEngine.isTargetAppForeground(targetApp, root.packageName?.toString().orEmpty(), context)
        }
        com.ace.app.utils.AceLatencyTracker.mark("app_wait_end")

        com.ace.app.utils.AceLatencyTracker.mark("observation_start")
        var snapshot = observationEngine.captureSnapshot()
        com.ace.app.utils.AceLatencyTracker.mark("observation_end")
        val bounds = android.graphics.Rect(0, 0, 1080, 2400)

        if (!verificationEngine.isTargetAppForeground(targetApp, snapshot.packageName, context)) {
            Log.e("ACE_UI_ERROR", "ACE_UI_ERROR: Target app $targetApp not in foreground (active: ${snapshot.packageName})")
            return@withContext VerificationResult(
                isVerified = false,
                status = ActionResultStatus.FAILED,
                summary = "Could not bring $targetApp to foreground.",
                evidenceText = "App not active in foreground"
            )
        }

        Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")

        val dialogResult = inspectDialogs(snapshot)
        if (dialogResult.type == InterruptionType.SENSITIVE_INTERRUPTION) {
            Log.w("ACE_UI_DIALOG", "ACE_UI_DIALOG: type=SENSITIVE_INTERRUPTION candidate=\"${dialogResult.actionButtonText}\"")
            return@withContext VerificationResult(
                isVerified = false,
                status = ActionResultStatus.NEEDS_USER_ACTION,
                summary = "Action paused: Sensitive UI dialog ('${dialogResult.actionButtonText}') requires user confirmation.",
                evidenceText = "Sensitive dialog detected"
            )
        } else if (dialogResult.type == InterruptionType.SAFE_INTERRUPTION && dialogResult.actionButtonNode != null) {
            Log.i("ACE_UI_DIALOG", "ACE_UI_DIALOG: type=SAFE_INTERRUPTION action=AUTO_DISMISS candidate=\"${dialogResult.actionButtonText}\"")
            service.clickText(dialogResult.actionButtonText)
            service.waitForCondition(maxTimeoutMs = 300L, pollIntervalMs = 20L) { service.rootInActiveWindow != null }
            snapshot = observationEngine.captureSnapshot()
        }

        com.ace.app.utils.AceLatencyTracker.mark("search_find_start")
        val searchMatch = elementFinder.findElement(snapshot, "SEARCH", bounds)
        com.ace.app.utils.AceLatencyTracker.mark("search_find_end")

        com.ace.app.utils.AceLatencyTracker.mark("action_start")
        if (searchMatch != null) {
            if (searchMatch.node.isEditable) {
                Log.i("ACE_UI_FIND", "ACE_UI_FIND: target=EDITABLE_SEARCH candidate=\"${searchMatch.candidateText}\" strategy=\"${searchMatch.strategyUsed}\" confidence=${"%.2f".format(searchMatch.confidence)}")
                Log.i("ACE_UI", "ACE_UI: stage=QUERY_TYPED text=\"$query\"")
                Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=TYPE_TEXT text=\"$query\" candidate=\"${searchMatch.candidateText}\"")
                service.typeText(query, searchMatch.candidateText)

                snapshot = observationEngine.captureSnapshot()
                Log.i("ACE_UI", "ACE_UI: stage=SEARCH_SUBMITTED")
                Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=SUBMIT_SEARCH")
                val suggestionNode = snapshot.nodes.firstOrNull { node ->
                    val text = node.text.lowercase().trim()
                    val desc = node.contentDescription.lowercase().trim()
                    (text.contains(query.lowercase()) || desc.contains(query.lowercase())) && node.isClickable && !node.isEditable
                }
                if (suggestionNode != null) {
                    Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=CLICK candidate=\"${suggestionNode.text.ifBlank { suggestionNode.contentDescription }}\" strategy=\"Search Suggestion\"")
                    suggestionNode.nodeRef?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    val searchBtn = snapshot.nodes.firstOrNull { node ->
                        val desc = node.contentDescription.lowercase().trim()
                        val text = node.text.lowercase().trim()
                        val resId = node.resourceId.lowercase().trim()
                        (desc.contains("search") || text == "search" || text == "go" || resId.contains("search_button") || resId.contains("search_icon")) && node.isClickable
                    }
                    if (searchBtn != null) {
                        Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=CLICK candidate=\"${searchBtn.text.ifBlank { searchBtn.contentDescription }}\" strategy=\"Search Button\"")
                        searchBtn.nodeRef?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        service.clickText(query)
                        service.clickText("Search")
                    }
                }
            } else {
                Log.i("ACE_UI_FIND", "ACE_UI_FIND: target=SEARCH_CONTROL candidate=\"${searchMatch.candidateText}\" strategy=\"${searchMatch.strategyUsed}\" confidence=${"%.2f".format(searchMatch.confidence)}")
                Log.i("ACE_UI", "ACE_UI: stage=SEARCH_CLICKED candidate=\"${searchMatch.candidateText}\"")
                Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=CLICK candidate=\"${searchMatch.candidateText}\"")
                var clicked = false
                val nodeRef = searchMatch.node.nodeRef
                if (nodeRef != null) {
                    clicked = nodeRef.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!clicked && searchMatch.centerX != null && searchMatch.centerY != null && isCoordinateSafe(searchMatch.centerX, searchMatch.centerY, bounds)) {
                    clicked = service.clickCoordinates(searchMatch.centerX, searchMatch.centerY)
                }
                if (!clicked) {
                    service.clickText(searchMatch.candidateText)
                }

                service.waitForCondition(maxTimeoutMs = 300L, pollIntervalMs = 20L) { service.rootInActiveWindow != null }
                snapshot = observationEngine.captureSnapshot()
                Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")

                val editableMatch = elementFinder.findElement(snapshot, "SEARCH", bounds)
                val searchFieldCandidate = editableMatch?.candidateText ?: searchMatch.candidateText
                Log.i("ACE_UI_FIND", "ACE_UI_FIND: target=EDITABLE_SEARCH candidate=\"$searchFieldCandidate\" strategy=\"${editableMatch?.strategyUsed ?: "Search Control"}\" confidence=${"%.2f".format(editableMatch?.confidence ?: 0.90)}")
                Log.i("ACE_UI", "ACE_UI: stage=QUERY_TYPED text=\"$query\"")
                Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=TYPE_TEXT text=\"$query\" candidate=\"$searchFieldCandidate\"")
                service.typeText(query, searchFieldCandidate)

                snapshot = observationEngine.captureSnapshot()
                Log.i("ACE_UI", "ACE_UI: stage=SEARCH_SUBMITTED")
                Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=SUBMIT_SEARCH")
                val suggestionNode = snapshot.nodes.firstOrNull { node ->
                    val text = node.text.lowercase().trim()
                    val desc = node.contentDescription.lowercase().trim()
                    (text.contains(query.lowercase()) || desc.contains(query.lowercase())) && node.isClickable && !node.isEditable
                }
                if (suggestionNode != null) {
                    Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=CLICK candidate=\"${suggestionNode.text.ifBlank { suggestionNode.contentDescription }}\" strategy=\"Search Suggestion\"")
                    suggestionNode.nodeRef?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    val searchBtn = snapshot.nodes.firstOrNull { node ->
                        val desc = node.contentDescription.lowercase().trim()
                        val text = node.text.lowercase().trim()
                        val resId = node.resourceId.lowercase().trim()
                        (desc.contains("search") || text == "search" || text == "go" || resId.contains("search_button") || resId.contains("search_icon")) && node.isClickable
                    }
                    if (searchBtn != null) {
                        Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=CLICK candidate=\"${searchBtn.text.ifBlank { searchBtn.contentDescription }}\" strategy=\"Search Button\"")
                        searchBtn.nodeRef?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        service.clickText(query)
                        service.clickText("Search")
                    }
                }
            }
        } else {
            Log.w("ACE_FALLBACK", "ACE_FALLBACK: trigger=SEARCH_CONTROL_NOT_FOUND strategy=VISUAL_SCREEN_GEOMETRY_FALLBACK")
            val visualTargetX = bounds.width() / 2.0f
            val visualTargetY = bounds.height() * 0.15f
            if (isCoordinateSafe(visualTargetX, visualTargetY, bounds)) {
                service.clickCoordinates(visualTargetX, visualTargetY)
            }

            snapshot = observationEngine.captureSnapshot()
            val editableMatch = elementFinder.findElement(snapshot, "SEARCH", bounds)
            val targetField = editableMatch?.candidateText ?: "Search"
            service.typeText(query, targetField)
            service.clickText(query)
            service.clickText("Search")
        }
        com.ace.app.utils.AceLatencyTracker.mark("action_end")

        com.ace.app.utils.AceLatencyTracker.mark("post_action_observation_start")
        snapshot = observationEngine.captureSnapshot()
        com.ace.app.utils.AceLatencyTracker.mark("post_action_observation_end")
        Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")

        com.ace.app.utils.AceLatencyTracker.mark("verification_start")
        val verifyResult = verificationEngine.verifySearchGoal(targetApp, query, snapshot, context)
        com.ace.app.utils.AceLatencyTracker.mark("verification_end")

        val resultStatusStr = if (verifyResult.isVerified) "VERIFIED" else verifyResult.status.name
        Log.i("ACE_VERIFY", "ACE_VERIFY: goal=SEARCH target_app=$targetApp query=\"$query\" result=$resultStatusStr")
        Log.i("ACE_UI_VERIFY", "ACE_UI_VERIFY: package=${snapshot.packageName} target_app=$targetApp query_entered=\"$query\" result_verified=${verifyResult.isVerified}")
        return@withContext verifyResult
    }

    suspend fun executeInAppPlayMedia(
        context: Context,
        targetApp: String,
        query: String
    ): VerificationResult = withContext(Dispatchers.Main) {
        val service = AceAccessibilityService.getInstance()
        if (service == null || !AceAccessibilityService.isServiceEnabled(context)) {
            Log.e("ACE_MEDIA", "ACE_MEDIA: stage=FINAL status=FAILED reason=\"Accessibility service disabled\"")
            return@withContext VerificationResult(false, ActionResultStatus.NEEDS_USER_ACTION, "Accessibility service disabled")
        }

        val uiSearchStartTime = System.currentTimeMillis()
        val lowerQuery = query.lowercase().trim()

        val initialSnapshot = observationEngine.captureSnapshot()
        val beforeMedia = verificationEngine.extractActiveMediaTitle(initialSnapshot, query)

        Log.i("ACE_MEDIA", "ACE_MEDIA: stage=OPEN_APP targetApp=$targetApp")
        Log.i("ACE_MEDIA", "ACE_MEDIA: before_media=\"$beforeMedia\"")
        Log.i("ACE_MEDIA", "ACE_MEDIA: requested_media=\"$query\"")

        // 1. OPEN APP & WAIT FOR TARGET APP FOREGROUND (T3, T4)
        com.ace.app.utils.AceLatencyTracker.recordStage("T3")
        val launched = service.launchApp(context, targetApp)
        if (!launched) {
            Log.e("ACE_MEDIA", "ACE_MEDIA: stage=FINAL status=FAILED reason=\"App launch failed\"")
            return@withContext VerificationResult(false, ActionResultStatus.FAILED, "Could not launch application '$targetApp'.")
        }

        var snapshot = waitForTargetAppForeground(targetApp, maxTimeoutMs = 2500)
        com.ace.app.utils.AceLatencyTracker.recordStage("T4")

        // Check for Interruptions / Dialogs
        val dialogResult = inspectDialogs(snapshot)
        if (dialogResult.type == InterruptionType.SAFE_INTERRUPTION && dialogResult.actionButtonNode != null) {
            service.clickText(dialogResult.actionButtonText)
            snapshot = waitForCondition(400) { it.nodes.size > snapshot.nodes.size }
        }

        // 2. ADAPTIVE SEARCH EXECUTION (T5, T6, T7, T8)
        val existingEditable = snapshot.nodes.firstOrNull { it.isEditable }
        if (existingEditable != null) {
            com.ace.app.utils.AceLatencyTracker.recordStage("T5")
            com.ace.app.utils.AceLatencyTracker.recordStage("T6")
            Log.i("ACE_MEDIA", "ACE_MEDIA: stage=SEARCH_OPENED strategy=IMMEDIATE_EDITABLE")
            val targetHint = existingEditable.text.ifBlank { existingEditable.contentDescription }
            service.typeText(query, targetHint)
            com.ace.app.utils.AceLatencyTracker.recordStage("T7")
        } else {
            var searchMatch = elementFinder.findElement(snapshot, "SEARCH")
            com.ace.app.utils.AceLatencyTracker.recordStage("T5")

            if (searchMatch != null) {
                val targetFieldHint = searchMatch.candidateText
                searchMatch.node.nodeRef?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ?: service.clickText(targetFieldHint)

                snapshot = waitForCondition(600) { snap -> snap.nodes.any { it.isEditable } }
                com.ace.app.utils.AceLatencyTracker.recordStage("T6")
                Log.i("ACE_MEDIA", "ACE_MEDIA: stage=SEARCH_OPENED")

                val editHint = snapshot.nodes.firstOrNull { it.isEditable }?.let { it.text.ifBlank { it.contentDescription } } ?: "Search"
                service.typeText(query, editHint)
                com.ace.app.utils.AceLatencyTracker.recordStage("T7")
            } else {
                service.typeText(query, "Search")
                com.ace.app.utils.AceLatencyTracker.recordStage("T6")
                com.ace.app.utils.AceLatencyTracker.recordStage("T7")
            }
        }
        Log.i("ACE_MEDIA", "ACE_MEDIA: stage=QUERY_TYPED query=\"$query\"")

        val isNavTab = { text: String, desc: String ->
            text == "search" || desc == "search" || text == "home" || desc == "home" ||
            text == "library" || desc == "library" || text == "your library" || desc == "your library" ||
            text == "premium" || desc == "premium" || text == "subscriptions" || desc == "subscriptions"
        }

        // Submit Search Query
        val suggestionNode = snapshot.nodes.firstOrNull { node ->
            val text = node.text.lowercase().trim()
            val desc = node.contentDescription.lowercase().trim()
            (text.contains(lowerQuery) || desc.contains(lowerQuery)) && node.isClickable && !node.isEditable && !isNavTab(text, desc)
        }

        if (suggestionNode != null) {
            val clicked = performClickOnNodeOrClickableParent(suggestionNode.nodeRef)
            if (!clicked) {
                service.clickText(suggestionNode.text.ifBlank { suggestionNode.contentDescription })
            }
        } else {
            val editNode = snapshot.nodes.firstOrNull { it.isEditable }
            editNode?.nodeRef?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            service.clickText("Go")
        }
        com.ace.app.utils.AceLatencyTracker.recordStage("T8")
        Log.i("ACE_MEDIA", "ACE_MEDIA: stage=SEARCH_SUBMITTED query=\"$query\"")

        // 3. OBSERVE FRESH SEARCH RESULTS & FIND BEST MATCHING SONG RESULT (T9, T10)
        snapshot = waitForCondition(1200) { snap ->
            snap.nodes.any { node ->
                val t = node.text.lowercase().trim()
                val d = node.contentDescription.lowercase().trim()
                (t.contains(lowerQuery) || d.contains(lowerQuery)) && !node.isEditable && !isNavTab(t, d)
            }
        }
        com.ace.app.utils.AceLatencyTracker.recordStage("T9")
        Log.i("ACE_MEDIA", "ACE_MEDIA: stage=SEARCH_RESULTS nodes=${snapshot.nodes.size}")

        val isAccessoryControl = { desc: String, text: String ->
            desc.contains("more options") || desc.contains("add suggestion") || desc.contains("clear query") ||
            desc.contains("voice search") || desc.contains("overflow") || desc.contains("context menu") ||
            text.contains("more options") || text.contains("add suggestion") || text.contains("clear query") ||
            desc.contains("based on your interest") || text.contains("based on your interest")
        }

        val isSongResultNode = { node: UiNode ->
            val text = node.text.lowercase().trim()
            val desc = node.contentDescription.lowercase().trim()
            if (desc.contains("song") || text.contains("song") || desc.contains("song •") || text.contains("song •")) {
                true
            } else {
                var foundInParent = false
                var currRef = node.nodeRef?.parent
                var depth = 0
                while (currRef != null && depth < 3) {
                    val count = currRef.childCount
                    for (i in 0 until count) {
                        val child = currRef.getChild(i)
                        if (child != null) {
                            val cText = (child.text ?: "").toString().lowercase()
                            val cDesc = (child.contentDescription ?: "").toString().lowercase()
                            if (cText.contains("song") || cDesc.contains("song")) {
                                foundInParent = true
                                break
                            }
                        }
                    }
                    if (foundInParent) break
                    currRef = currRef.parent
                    depth++
                }
                foundInParent
            }
        }

        val displayMetrics = context.resources.displayMetrics
        val displayHeight = displayMetrics.heightPixels

        val resultCandidates = snapshot.nodes.filter { node ->
            val text = node.text.lowercase().trim()
            val desc = node.contentDescription.lowercase().trim()
            val rect = node.getRect()
            val nodeTop = rect?.top ?: 0
            val nodeBottom = rect?.bottom ?: 0
            (text.contains(lowerQuery) || desc.contains(lowerQuery)) &&
            !isAccessoryControl(desc, text) && !isNavTab(text, desc) &&
            nodeTop in 301..(displayHeight - 100) && nodeBottom <= (displayHeight + 200)
        }

        // Deterministic Local Match Ranking: Song result preferred > Exact > Starts-with > Partial
        val resultMatchNode = resultCandidates.filter { !it.isEditable }.maxByOrNull { node ->
            val text = node.text.lowercase().trim()
            val desc = node.contentDescription.lowercase().trim()
            val hasSongTag = isSongResultNode(node)
            when {
                (text == lowerQuery || desc == lowerQuery) && hasSongTag -> 150
                text == lowerQuery || desc == lowerQuery -> 100
                (text.startsWith(lowerQuery) || desc.startsWith(lowerQuery)) && hasSongTag -> 90
                text.startsWith(lowerQuery) || desc.startsWith(lowerQuery) -> 80
                (text.contains(lowerQuery) || desc.contains(lowerQuery)) && hasSongTag -> 60
                text.contains(lowerQuery) || desc.contains(lowerQuery) -> 50
                else -> 10
            }
        }

        var selectedTitle = query
        if (resultMatchNode != null) {
            selectedTitle = resultMatchNode.text.ifBlank { resultMatchNode.contentDescription }
            
            var targetRef = resultMatchNode.nodeRef
            var containerRect = resultMatchNode.getRect() ?: android.graphics.Rect()
            var curr = resultMatchNode.nodeRef
            while (curr != null) {
                if (curr.isClickable) {
                    targetRef = curr
                    val r = android.graphics.Rect()
                    curr.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        containerRect = r
                    }
                    break
                }
                curr = curr.parent
            }

            Log.i("ACE_MEDIA", "ACE_MEDIA: stage=RESULT_SELECTED title=\"$selectedTitle\" nodeBounds=${resultMatchNode.boundsInScreen} containerBounds=$containerRect")
            
            val service = AceAccessibilityService.getInstance()
            var clicked = targetRef?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (service != null && containerRect.width() > 0 && containerRect.height() > 0) {
                val cx = containerRect.centerX().toFloat()
                val cy = containerRect.centerY().toFloat()
                val gestureClicked = service.clickCoordinates(cx, cy)
                clicked = clicked || gestureClicked
            }
            com.ace.app.utils.AceLatencyTracker.recordStage("T10")
            Log.i("ACE_MEDIA", "ACE_MEDIA: stage=PLAYBACK_OBSERVATION clicked=$clicked")
            snapshot = waitForCondition(2500) { snap ->
                val hasPause = snap.nodes.any { it.contentDescription.lowercase().contains("pause") || it.text.lowercase().contains("pause") }
                val isAudio = try { (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true } catch (_: Exception) { false }
                (hasPause || isAudio) && !verificationEngine.isSearchResultsScreen(snap)
            }
        } else {
            com.ace.app.utils.AceLatencyTracker.recordStage("T10")
        }

        Log.i("ACE_MEDIA", "ACE_MEDIA: stage=POST_SELECTION_OBSERVED nodes=${snapshot.nodes.size}")

        // 4. ACTIVATE PLAYBACK IF NEEDED (T11, T12)
        val hasPauseControl = snapshot.nodes.any { it.contentDescription.lowercase().contains("pause") || it.text.lowercase().contains("pause") }
        if (!hasPauseControl) {
            val detailPlayButton = snapshot.nodes.firstOrNull { node ->
                val text = node.text.lowercase().trim()
                val desc = node.contentDescription.lowercase().trim()
                (desc == "play" || text == "play" || desc.startsWith("play ") || node.resourceId.contains("play")) &&
                node.isClickable && !desc.contains("pause") && !text.contains("pause") && !desc.contains("playlist")
            }
            if (detailPlayButton != null) {
                val playCandidate = detailPlayButton.text.ifBlank { detailPlayButton.contentDescription }
                Log.i("ACE_MEDIA", "ACE_MEDIA: stage=PLAY_CONTROL_FOUND candidate=\"$playCandidate\" confidence=1.0")
                Log.i("ACE_MEDIA", "ACE_MEDIA: stage=PLAY_ACTION candidate=\"$playCandidate\"")
                val playCenter = detailPlayButton.getCenterCoordinates()
                detailPlayButton.nodeRef?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (service != null && playCenter != null) {
                    service.clickCoordinates(playCenter.first, playCenter.second)
                }
                com.ace.app.utils.AceLatencyTracker.recordStage("T11")
                snapshot = waitForCondition(1000) { snap ->
                    snap.nodes.any { it.contentDescription.lowercase().contains("pause") }
                }
            } else {
                com.ace.app.utils.AceLatencyTracker.recordStage("T11")
            }
        } else {
            com.ace.app.utils.AceLatencyTracker.recordStage("T11")
        }

        com.ace.app.utils.AceLatencyTracker.recordStage("T12")

        if (snapshot.nodes.size < 10) {
            delay(400)
            snapshot = observationEngine.captureSnapshot()
        }

        // 5. STRICT DOUBLE VERIFICATION WITH BEFORE/AFTER PROTECTION (T13)
        val afterMedia = verificationEngine.extractActiveMediaTitle(snapshot, query)
        var verifyResult = verificationEngine.verifyMediaPlayback(targetApp, query, snapshot, context)

        if (beforeMedia != "unknown" && afterMedia.equals(beforeMedia, ignoreCase = true) && !afterMedia.lowercase().contains(lowerQuery)) {
            Log.w("ACE_MEDIA", "ACE_MEDIA: before_media equals after_media ('$afterMedia') — track did not switch!")
            verifyResult = VerificationResult(
                isVerified = false,
                status = ActionResultStatus.FAILED,
                summary = "Playback did not switch to requested track '$query'.",
                evidenceText = "TRACK_NOT_SWITCHED"
            )
        }

        val stateStr = if (verifyResult.isVerified) "PLAYING" else "UNVERIFIED"
        val statusStr = if (verifyResult.isVerified) "VERIFIED" else "UNVERIFIED"

        Log.i("ACE_MEDIA", "ACE_MEDIA: after_media=\"$afterMedia\"")
        Log.i("ACE_MEDIA", "ACE_MEDIA: playback_state=$stateStr")
        Log.i("ACE_MEDIA", "ACE_MEDIA: verification_evidence=\"${verifyResult.evidenceText ?: ""}\"")
        Log.i("ACE_MEDIA", "ACE_MEDIA: FINAL=$statusStr")

        com.ace.app.utils.AceLatencyTracker.recordStage("T13")
        val uiSearchLatencyMs = System.currentTimeMillis() - uiSearchStartTime
        com.ace.app.utils.AceLatencyTracker.logLatencySummary(
            route = "ui_search",
            directRouteMs = 0L,
            uiSearchMs = uiSearchLatencyMs,
            verificationMs = 20L,
            totalMs = uiSearchLatencyMs
        )
        return@withContext verifyResult
    }

    private fun performClickOnNodeOrClickableParent(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
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
            val service = AceAccessibilityService.getInstance()
            if (service != null) {
                Log.i("ACE_ACCESSIBILITY", "Accessibility action click failed on node/parents, falling back to gesture tap at (${bounds.centerX()}, ${bounds.centerY()})")
                return service.clickCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        }
        return false
    }

    private suspend fun waitForTargetAppForeground(
        targetApp: String,
        maxTimeoutMs: Long = 2500,
        pollIntervalMs: Long = 30
    ): UiSnapshot {
        val startTime = System.currentTimeMillis()
        var snapshot = observationEngine.captureSnapshot()
        if (verificationEngine.isTargetAppForeground(targetApp, snapshot.packageName)) return snapshot

        while (System.currentTimeMillis() - startTime < maxTimeoutMs) {
            delay(pollIntervalMs)
            snapshot = observationEngine.captureSnapshot()
            if (verificationEngine.isTargetAppForeground(targetApp, snapshot.packageName)) return snapshot
        }
        return snapshot
    }

    private suspend fun waitForCondition(
        maxTimeoutMs: Long = 1000,
        pollIntervalMs: Long = 20,
        predicate: (UiSnapshot) -> Boolean
    ): UiSnapshot {
        val startTime = System.currentTimeMillis()
        var snapshot = observationEngine.captureSnapshot()
        if (predicate(snapshot)) return snapshot

        while (System.currentTimeMillis() - startTime < maxTimeoutMs) {
            delay(pollIntervalMs)
            snapshot = observationEngine.captureSnapshot()
            if (predicate(snapshot)) return snapshot
        }
        return snapshot
    }

    suspend fun executeFileSelection(context: Context, fileNameOrType: String): VerificationResult = withContext(Dispatchers.Main) {
        Log.i("ACE_UI_FILE", "ACE_UI_FILE: action=FILE_DISCOVERY target=\"$fileNameOrType\" status=STARTED")
        val service = AceAccessibilityService.getInstance()
        if (service == null || !AceAccessibilityService.isServiceEnabled(context)) {
            Log.w("ACE_UI_FILE", "ACE_UI_FILE: status=FAILED reason=\"Accessibility Service Disabled\"")
            return@withContext VerificationResult(false, ActionResultStatus.NEEDS_USER_ACTION, "Accessibility service disabled")
        }

        var snapshot = observationEngine.captureSnapshot()
        Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")

        val uploadButton = elementFinder.findElement(snapshot, "Upload")
            ?: elementFinder.findElement(snapshot, "Choose file")
            ?: elementFinder.findElement(snapshot, "Browse")

        if (uploadButton != null) {
            Log.i("ACE_UI_FILE", "ACE_UI_FILE: candidate=\"${uploadButton.candidateText}\" action=CLICK")
            service.clickText(uploadButton.candidateText)
            service.waitForCondition(maxTimeoutMs = 300L, pollIntervalMs = 20L) { service.rootInActiveWindow != null }
            snapshot = observationEngine.captureSnapshot()
        }

        val fileMatch = elementFinder.findElement(snapshot, fileNameOrType)
        if (fileMatch != null) {
            Log.i("ACE_UI_FILE", "ACE_UI_FILE: candidate=\"${fileMatch.candidateText}\" status=SUCCESS")
            service.clickText(fileMatch.candidateText)
            return@withContext VerificationResult(true, ActionResultStatus.SUCCESS, "File '$fileNameOrType' selected successfully.")
        }

        Log.w("ACE_UI_FILE", "ACE_UI_FILE: target=\"$fileNameOrType\" status=PARTIAL message=\"File picker active, awaiting user selection\"")
        return@withContext VerificationResult(false, ActionResultStatus.PARTIAL, "File picker active for '$fileNameOrType'. Awaiting selection.")
    }

    private fun inspectDialogs(snapshot: UiSnapshot): DialogCheckResult {
        val safeKeywords = listOf("allow", "cancel", "not now", "got it", "continue", "accept", "close", "dismiss")
        val sensitiveKeywords = listOf("place order", "submit payment", "delete account", "send money", "confirm purchase", "confirm payment")

        for (node in snapshot.nodes) {
            val text = node.text.lowercase().trim()
            val desc = node.contentDescription.lowercase().trim()
            val label = if (text.isNotBlank()) text else desc

            if (label.isNotBlank() && (label.length <= 30)) {
                val isModalClass = node.className.contains("Dialog") || node.className.contains("AlertDialog") || node.resourceId.contains("dialog")
                if (sensitiveKeywords.any { kw -> label == kw || (isModalClass && Regex("\\b${Regex.escape(kw)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) }) {
                    return DialogCheckResult(InterruptionType.SENSITIVE_INTERRUPTION, label, node)
                }
                if (safeKeywords.any { kw -> label == kw || (isModalClass && Regex("\\b${Regex.escape(kw)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) } && node.isClickable) {
                    val isDialogLike = isModalClass || (node.isClickable && node.text.length < 10 && node.hintText.isBlank())
                    if (isDialogLike && label != "search") {
                        return DialogCheckResult(InterruptionType.SAFE_INTERRUPTION, label, node)
                    }
                }
            }
        }
        return DialogCheckResult(InterruptionType.NONE)
    }

    private fun isCoordinateSafe(x: Float?, y: Float?, bounds: android.graphics.Rect): Boolean {
        if (x == null || y == null) return false
        return x >= 0f && x <= bounds.width().toFloat() && y >= 0f && y <= bounds.height().toFloat()
    }
}
