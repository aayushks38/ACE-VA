package com.ace.app.utils

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AceLatencyTracker — Microsecond-precision timestamp and stage delta tracker for ACE execution:
 * T0 = speech/transcript finalized
 * T1 = intent parsed
 * T2 = routing completed
 * T3 = first Android action dispatched
 * T4 = target app foreground
 * T5 = search control found
 * T6 = search opened
 * T7 = query typed
 * T8 = search submitted
 * T9 = results observed
 * T10 = result selected
 * T11 = playback action
 * T12 = playback detected
 * T13 = verification completed
 * T14 = final TTS started
 *
 * Additional stages: DIRECT_ROUTE_ATTEMPT, DIRECT_ROUTE_SUCCESS, DIRECT_ROUTE_FAILURE, UI_SEARCH_FALLBACK
 */
object AceLatencyTracker {

    private const val TAG = "ACE_LATENCY"
    private var t0Ms: Long = 0L
    private var lastStageMs: Long = 0L
    private val stageTimestamps = ConcurrentHashMap<String, Long>()
    private var isTracking: Boolean = false

    @Volatile
    private var taskStartRealtimeMs: Long = 0L

    // Metrics counters
    val gemmaCallCount = AtomicInteger(0)
    val modelLoadCount = AtomicInteger(0)
    val accessibilityWaitMs = AtomicLong(0L)
    val fixedDelayCount = AtomicInteger(0)
    val fixedDelayTotalMs = AtomicLong(0L)
    val retryCount = AtomicInteger(0)
    val pollCount = AtomicInteger(0)
    val uiReasoningCount = AtomicInteger(0)

    @Synchronized
    fun startTask(t0TimestampMs: Long = System.currentTimeMillis()) {
        stageTimestamps.clear()
        t0Ms = t0TimestampMs
        lastStageMs = t0Ms
        stageTimestamps["T0"] = t0Ms
        isTracking = true

        taskStartRealtimeMs = SystemClock.elapsedRealtime()
        gemmaCallCount.set(0)
        accessibilityWaitMs.set(0L)
        fixedDelayCount.set(0)
        fixedDelayTotalMs.set(0L)
        retryCount.set(0)
        pollCount.set(0)
        uiReasoningCount.set(0)

        Log.i(TAG, "ACE_LATENCY: stage=T0 timestamp=$t0Ms")
        mark("speech_start")
    }

    @Synchronized
    fun recordStage(stage: String, timestampMs: Long = System.currentTimeMillis()) {
        if (!isTracking) {
            if (stage == "T0" || stage == "T1") {
                startTask(timestampMs)
                if (stage == "T0") return
            } else {
                return
            }
        }

        stageTimestamps[stage] = timestampMs
        val delta = timestampMs - lastStageMs
        lastStageMs = timestampMs
        Log.i(TAG, "ACE_LATENCY: stage=$stage timestamp=$timestampMs delta_ms=$delta")
        mark(stage.lowercase())
    }

    @Synchronized
    fun getStageTimestamp(stage: String): Long? = stageTimestamps[stage]

    fun mark(markerName: String) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (taskStartRealtimeMs > 0) now - taskStartRealtimeMs else 0L
        Log.i(TAG, "ACE_LATENCY: marker=$markerName elapsed_ms=$elapsed")
    }

    fun recordModelLoad() { modelLoadCount.incrementAndGet() }
    fun recordGemmaCall() { gemmaCallCount.incrementAndGet() }
    fun recordFixedDelay(ms: Long) { fixedDelayCount.incrementAndGet(); fixedDelayTotalMs.addAndGet(ms) }
    fun recordAccessibilityWait(ms: Long) { accessibilityWaitMs.addAndGet(ms) }
    fun recordPoll() { pollCount.incrementAndGet() }
    fun recordRetry() { retryCount.incrementAndGet() }
    fun recordUiReasoning() { uiReasoningCount.incrementAndGet() }

    fun logSummary() {
        val now = SystemClock.elapsedRealtime()
        val totalMs = if (taskStartRealtimeMs > 0) now - taskStartRealtimeMs else 0L
        Log.i(TAG, "ACE_LATENCY_SUMMARY: total_elapsed_ms=$totalMs")
        Log.i("ACE_METRICS", "ACE_GEMMA_CALL_COUNT=${gemmaCallCount.get()}")
        Log.i("ACE_METRICS", "ACE_MODEL_LOAD_COUNT=${modelLoadCount.get()}")
        Log.i("ACE_METRICS", "ACE_ACCESSIBILITY_WAIT_MS=${accessibilityWaitMs.get()}")
        Log.i("ACE_METRICS", "ACE_FIXED_DELAY_COUNT=${fixedDelayCount.get()}")
        Log.i("ACE_METRICS", "ACE_FIXED_DELAY_TOTAL_MS=${fixedDelayTotalMs.get()}")
        Log.i("ACE_METRICS", "ACE_RETRY_COUNT=${retryCount.get()}")
        Log.i("ACE_METRICS", "ACE_POLL_COUNT=${pollCount.get()}")
        Log.i("ACE_METRICS", "ACE_UI_REASONING_COUNT=${uiReasoningCount.get()}")
    }

    fun logLatencySummary(
        route: String,
        directRouteMs: Long,
        uiSearchMs: Long,
        verificationMs: Long,
        totalMs: Long
    ) {
        Log.i(TAG, "ACE_LATENCY: route=$route direct_route_latency_ms=$directRouteMs ui_search_latency_ms=$uiSearchMs verification_latency_ms=$verificationMs total_latency_ms=$totalMs")
    }

    @Synchronized
    fun reset() {
        isTracking = false
        t0Ms = 0L
        lastStageMs = 0L
        stageTimestamps.clear()
    }
}
