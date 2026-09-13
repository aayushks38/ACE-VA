package com.ace.app.instant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class BatteryInstantCapability : InstantCapability {
    override val id: String = "device_battery"
    override val name: String = "Battery Instant Intelligence"
    override val source: String = "battery_api"

    override fun confidence(command: String): Float {
        val lower = command.lowercase().trim()
            .replace(Regex("""[\?!,\.]"""), " ")
            .trim()
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.contains("help ") || lower.contains("strategy")) {
            return 0.0f
        }
        val words = lower.split("\\s+".toRegex())
        if (words.size > 15) return 0.0f
        if (lower.contains("battery") || lower.contains("charging") || lower.contains("charge level")) {
            return 0.95f
        }
        return 0.0f
    }

    override suspend fun execute(context: Context?, command: String): InstantResult {
        if (context == null) {
            return InstantResult(
                isHandled = false,
                capabilityId = id,
                message = "Device context unavailable for battery status.",
                source = source,
                error = "Null context"
            )
        }

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct: Int = if (level != -1 && scale != -1) ((level / scale.toFloat()) * 100).toInt() else 0

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val lower = command.lowercase().trim()
        val message = when {
            lower.contains("charging") -> {
                if (isCharging) "Your phone is currently charging ($batteryPct%)."
                else "Your phone is not charging. Battery level is $batteryPct%."
            }
            lower.contains("low") -> {
                if (batteryPct <= 20) "Yes, your battery is low at $batteryPct%."
                else "No, your battery level is healthy at $batteryPct%."
            }
            else -> {
                if (isCharging) "Your battery level is $batteryPct% and currently charging."
                else "Your battery level is $batteryPct%."
            }
        }

        return InstantResult(
            isHandled = true,
            capabilityId = id,
            message = message,
            source = source,
            outputData = mapOf("batteryPct" to "$batteryPct", "isCharging" to "$isCharging")
        )
    }
}
