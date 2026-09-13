package com.ace.app.instant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DateAndTimeInstantCapability : InstantCapability {
    override val id: String = "device_date"
    override val name: String = "Date & Time Instant Intelligence"
    override val source: String = "system_clock"

    override fun confidence(command: String): Float {
        val lower = command.lowercase().trim()
            .replace(Regex("""[\?!,\.]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("set alarm") ||
            lower.startsWith("set timer") || lower.startsWith("help ") || lower.contains("strategy") ||
            lower.contains("revision") || lower.contains("create") || lower.contains("explain")) {
            return 0.0f
        }

        val words = lower.split("\\s+".toRegex())
        if (words.size > 15) return 0.0f

        val isDateQuery = lower.contains("date") || lower.contains("time is it") || lower.contains("is the time") ||
                lower.contains("the time") || lower.contains("current time") || lower.contains("day is it") ||
                lower.contains("day will tomorrow be") || lower.contains("day will it be tomorrow") ||
                lower.contains("what month") || lower.contains("what year") || lower.contains("yesterday") || lower.contains("what day")

        if (isDateQuery) {
            return 0.95f
        }
        return 0.0f
    }

    override suspend fun execute(context: Context?, command: String): InstantResult {
        val lower = command.lowercase().trim()
        val calendar = Calendar.getInstance()

        val responseMessage: String = when {
            lower.contains("tomorrow") -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                val dayName = SimpleDateFormat("EEEE", Locale.US).format(calendar.time)
                val fullDate = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(calendar.time)
                "Tomorrow will be $dayName ($fullDate)."
            }
            lower.contains("yesterday") -> {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                val dayName = SimpleDateFormat("EEEE", Locale.US).format(calendar.time)
                val fullDate = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(calendar.time)
                "Yesterday was $dayName ($fullDate)."
            }
            lower.contains("time") -> {
                val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(calendar.time)
                "The current time is $timeStr."
            }
            lower.contains("month") -> {
                val monthStr = SimpleDateFormat("MMMM", Locale.US).format(calendar.time)
                "The current month is $monthStr."
            }
            lower.contains("year") -> {
                val yearStr = SimpleDateFormat("yyyy", Locale.US).format(calendar.time)
                "The current year is $yearStr."
            }
            lower.contains("day is it") || lower.contains("what day") -> {
                val dayName = SimpleDateFormat("EEEE", Locale.US).format(calendar.time)
                "Today is $dayName."
            }
            else -> {
                val fullDate = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(calendar.time)
                "Today is $fullDate."
            }
        }

        return InstantResult(
            isHandled = true,
            capabilityId = id,
            message = responseMessage,
            source = source,
            outputData = mapOf("date_time_response" to responseMessage)
        )
    }
}
