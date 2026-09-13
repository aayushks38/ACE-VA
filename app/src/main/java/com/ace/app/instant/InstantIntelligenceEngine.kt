package com.ace.app.instant

import android.content.Context
import android.util.Log

object InstantIntelligenceEngine {

    private val capabilities: List<InstantCapability> = listOf(
        CalculatorInstantCapability(),
        DateAndTimeInstantCapability(),
        BatteryInstantCapability(),
        StorageInstantCapability(),
        ConnectivityInstantCapability(),
        DeviceStateInstantCapability(),
        PhoneInfoInstantCapability(),
        LocalFileInstantCapability()
    )

    fun canHandle(command: String): Boolean {
        return findBestCapability(command) != null
    }

    fun findBestCapability(command: String): InstantCapability? {
        val clean = command.trim()
        if (clean.isBlank()) return null

        var maxConfidence = 0.0f
        var bestCap: InstantCapability? = null

        for (cap in capabilities) {
            val conf = cap.confidence(clean)
            if (conf >= 0.75f && conf > maxConfidence) {
                maxConfidence = conf
                bestCap = cap
            }
        }

        return bestCap
    }

    suspend fun execute(context: Context?, command: String): InstantResult {
        val cap = findBestCapability(command)
        if (cap == null) {
            Log.e("ACE_ERROR", "ACE_ERROR: No matching Instant Capability found for '$command'")
            return InstantResult(
                isHandled = false,
                capabilityId = "none",
                message = "No matching instant capability.",
                source = "none",
                error = "Capability not found"
            )
        }

        Log.i("ACE_INSTANT", "ACE_INSTANT: capability=${cap.id}")
        Log.i("ACE_DEVICE", "ACE_DEVICE: source=${cap.source}")

        val result = cap.execute(context, command)

        Log.i("ACE_RESPONSE", "ACE_RESPONSE: completed=${result.isHandled}")
        return result
    }
}
