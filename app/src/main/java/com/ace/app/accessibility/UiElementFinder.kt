package com.ace.app.accessibility

import android.util.Log

class UiElementFinder {

    fun findElement(
        snapshot: UiSnapshot,
        targetRoleOrText: String,
        screenBounds: android.graphics.Rect? = null
    ): CandidateMatch? {
        val lowerTarget = targetRoleOrText.lowercase().trim()
        if (snapshot.nodes.isEmpty()) return null

        var bestMatch: CandidateMatch? = null
        var maxScore = 0.0f
        val isSearchTarget = lowerTarget.contains("search") || lowerTarget == "search_field" || lowerTarget == "search_button"

        val semanticVariations = listOf(
            "search", "find", "explore", "search songs", "search music", "search videos",
            "search products", "search restaurants", "search places", "search location",
            "search dishes", "search items", "search here", "type to search"
        )

        val screenHeight = screenBounds?.height() ?: 2400
        val maxToolbarTop = (screenHeight * 0.35f).toInt()
        val minBottomTabTop = (screenHeight * 0.70f).toInt()

        for (uiNode in snapshot.nodes) {
            val text = uiNode.text.lowercase().trim()
            val desc = uiNode.contentDescription.lowercase().trim()
            val hint = uiNode.hintText.lowercase().trim()
            val resId = uiNode.resourceId.lowercase().trim()

            var score = 0.0f
            var strategy = "None"
            val matchedLabel = uiNode.text.ifBlank { uiNode.contentDescription }
                .ifBlank { uiNode.hintText }
                .ifBlank { uiNode.resourceId }
                .ifBlank { "UI Control" }

            val isVoiceControl = text.contains("voice") || desc.contains("voice") || resId.contains("voice") || text.contains("mic") || desc.contains("mic") || desc.contains("speak")

            if (isSearchTarget) {
                if (isVoiceControl) {
                    // Skip voice search buttons when looking for text search affordances
                    score = 0.0f
                } else when {
                    // Strategy 1: Exact visible text match
                    text == "search" -> {
                        score = 1.00f
                        strategy = "Strategy 1: Exact Text Match"
                    }
                    // Strategy 2: Content description match
                    desc == "search" || desc.contains("search button") || desc.contains("search bar") || desc.contains("search field") -> {
                        score = 0.95f
                        strategy = "Strategy 2: Content Description Match"
                    }
                    // Strategy 3: Hint text match
                    hint.contains("search") -> {
                        score = 0.90f
                        strategy = "Strategy 3: Hint Text Match"
                    }
                    // Strategy 4: Contains 'search', 'find', 'query' in text, desc, hint or resId
                    text.contains("search") || desc.contains("search") || hint.contains("search") || resId.contains("search") || resId.contains("query") || resId.contains("find") -> {
                        score = 0.85f
                        if (uiNode.isClickable || uiNode.isEditable) score += 0.05f
                        strategy = "Strategy 4: General Search Semantics Match"
                    }
                    // Strategy 5: Semantic variations
                    semanticVariations.any { text.contains(it) || desc.contains(it) || resId.contains(it) } -> {
                        score = 0.80f
                        if (uiNode.isClickable || uiNode.isEditable) score += 0.05f
                        strategy = "Strategy 5: Semantic Variations Match"
                    }
                    // Strategy 6: Editable field
                    uiNode.isEditable -> {
                        score = 0.70f
                        strategy = "Strategy 6: Visible Editable Field"
                    }
                }

                // Strategy 7 & 8: Visual Bounding Box Region Fallback (normalized coordinates)
                if (score < 0.70f && !isVoiceControl) {
                    val rect = uiNode.getRect()
                    if (rect != null && !rect.isEmpty && rect.width() >= 100 && rect.height() >= 25) {
                        // Top toolbar / header search region candidate (top <= 35% of screen height)
                        if (rect.top <= maxToolbarTop && rect.bottom >= (screenHeight * 0.02f).toInt() && (uiNode.isClickable || uiNode.resourceId.contains("search") || uiNode.resourceId.contains("header") || uiNode.resourceId.contains("bar") || uiNode.className.contains("EditText") || uiNode.className.contains("View"))) {
                            val candidateScore = if (uiNode.isClickable || uiNode.isEditable) 0.75f else 0.65f
                            if (candidateScore > score) {
                                score = candidateScore
                                strategy = "Strategy 7: Normalized Top Search Region (${rect.left},${rect.top})"
                            }
                        }
                        // Bottom navigation search tab candidate (top >= 70% of screen height)
                        if (rect.top >= minBottomTabTop && (text.contains("search") || desc.contains("search") || text.contains("explore") || desc.contains("explore"))) {
                            if (0.85f > score) {
                                score = 0.85f
                                strategy = "Strategy 8: Normalized Bottom Search Tab (${rect.left},${rect.top})"
                            }
                        }
                    }
                }
            } else {
                when {
                    text == lowerTarget -> {
                        score = 1.00f
                        strategy = "Exact Text Match"
                    }
                    desc == lowerTarget -> {
                        score = 0.95f
                        strategy = "Content Description Match"
                    }
                    hint == lowerTarget -> {
                        score = 0.90f
                        strategy = "Hint Text Match"
                    }
                    text.contains(lowerTarget) || desc.contains(lowerTarget) || hint.contains(lowerTarget) -> {
                        score = 0.75f
                        strategy = "Partial Text Match"
                    }
                    resId.contains(lowerTarget) -> {
                        score = 0.50f
                        strategy = "Resource ID Match"
                    }
                }
            }

            if (score > 0.0f) {
                Log.i("ACE_UI_SCORE", "ACE_UI_SCORE: candidate=\"$matchedLabel\" strategy=\"$strategy\" score=${"%.2f".format(score)}")
            }

            if (score > maxScore) {
                maxScore = score
                val coords = uiNode.getCenterCoordinates()
                bestMatch = CandidateMatch(uiNode, matchedLabel, score, strategy, coords?.first, coords?.second)
            }
        }

        if (bestMatch != null && maxScore >= 0.30f) {
            val formattedConfidence = "%.2f".format(maxScore)
            Log.i("ACE_UI_FIND", "ACE_UI_FIND: target=$targetRoleOrText candidate=\"${bestMatch.candidateText}\" strategy=\"${bestMatch.strategyUsed}\" confidence=$formattedConfidence coords=(${bestMatch.centerX},${bestMatch.centerY})")
            return bestMatch.copy(confidence = maxScore)
        } else {
            Log.w("ACE_UI_FIND", "ACE_UI_FIND: target=$targetRoleOrText candidate_found=false candidates=${snapshot.nodes.size}")
            return null
        }
    }

    fun findGenericNavigationAffordance(snapshot: UiSnapshot, screenBounds: android.graphics.Rect? = null): CandidateMatch? {
        val screenHeight = screenBounds?.height() ?: 2400
        val maxToolbarTop = (screenHeight * 0.35f).toInt()

        for (uiNode in snapshot.nodes) {
            val desc = uiNode.contentDescription.lowercase().trim()
            val text = uiNode.text.lowercase().trim()
            val resId = uiNode.resourceId.lowercase().trim()

            val isNavControl = desc.contains("menu") || desc.contains("drawer") || desc.contains("navigation") ||
                               desc.contains("more options") || desc.contains("overflow") || desc.contains("explore") ||
                               resId.contains("menu") || resId.contains("drawer") || resId.contains("nav")

            if (isNavControl && (uiNode.isClickable || uiNode.isEditable)) {
                val rect = uiNode.getRect()
                if (rect != null && rect.top <= maxToolbarTop) {
                    val matchedLabel = text.ifBlank { desc }.ifBlank { resId }.ifBlank { "Navigation Control" }
                    val coords = uiNode.getCenterCoordinates()
                    return CandidateMatch(
                        node = uiNode,
                        candidateText = matchedLabel,
                        confidence = 0.60f,
                        strategyUsed = "Generic Navigation Perception (${rect.left},${rect.top})",
                        centerX = coords?.first,
                        centerY = coords?.second
                    )
                }
            }
        }
        return null
    }
}
