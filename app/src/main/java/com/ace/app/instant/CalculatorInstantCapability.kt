package com.ace.app.instant

import android.content.Context
import java.util.Locale
import kotlin.math.sqrt
import kotlin.math.pow

class CalculatorInstantCapability : InstantCapability {
    override val id: String = "local_calculator"
    override val name: String = "Local Calculator"
    override val source: String = "local_evaluator"

    override fun confidence(command: String): Float {
        val lower = command.lowercase().trim()
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.contains("help") || lower.contains("strategy")) {
            return 0.0f
        }
        val (_, result) = tryParseAndEval(command)
        return if (result != null) 1.0f else 0.0f
    }

    override suspend fun execute(context: Context?, command: String): InstantResult {
        val (parsedExpr, result) = tryParseAndEval(command)
        return if (result != null) {
            val formatted = if (result % 1.0 == 0.0) result.toLong().toString() else String.format(Locale.US, "%.4f", result).trimEnd('0').trimEnd('.')
            InstantResult(
                isHandled = true,
                capabilityId = id,
                message = "$parsedExpr = $formatted",
                source = source,
                outputData = mapOf("expression" to parsedExpr, "result" to formatted)
            )
        } else {
            InstantResult(
                isHandled = false,
                capabilityId = id,
                message = "Could not evaluate calculation.",
                source = source,
                error = "Evaluation failed"
            )
        }
    }

    companion object {
        fun tryParseAndEval(command: String): Pair<String, Double?> {
            var raw = command.lowercase().trim()
                .replace(Regex("""[\?!,\.]"""), " ")
                .trim()

            // Remove leading conversational fillers
            raw = raw.replace(Regex("""^(what is|what's|calculate|evaluate|how much is|can you calculate|could you calculate|please calculate|solve)\s+"""), "").trim()

            if (raw.isBlank()) return Pair("", null)

            // Verbal replacements
            var expr = raw
                .replace("multiplied by", " * ")
                .replace("divided by", " / ")
                .replace("percent of", " % of ")
                .replace("square root of", " sqrt ")
                .replace("square root", " sqrt ")
                .replace("squared", " ^ 2 ")
                .replace("plus", " + ")
                .replace("add", " + ")
                .replace("minus", " - ")
                .replace("subtract", " - ")
                .replace("times", " * ")
                .replace("×", " * ")
                .replace("÷", " / ")

            // Handle word numbers (e.g., "twenty four" -> 24)
            expr = convertWordsToNumbers(expr)

            // Clean multiple spaces
            expr = expr.replace(Regex("""\s+"""), " ").trim()

            val evalResult = evalExpression(expr)
            return Pair(expr, evalResult)
        }

        private fun convertWordsToNumbers(text: String): String {
            val wordMap = mapOf(
                "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
                "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
                "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13",
                "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
                "eighteen" to "18", "nineteen" to "19", "twenty" to "20", "thirty" to "30",
                "forty" to "40", "fifty" to "50", "sixty" to "60", "seventy" to "70",
                "eighty" to "80", "ninety" to "90"
            )

            var s = text
            // Handle "hundred" combinations e.g. "one hundred twenty four" -> 124
            s = s.replace(Regex("""\b(one|two|three|four|five|six|seven|eight|nine)\s+hundred\s+(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\s+(one|two|three|four|five|six|seven|eight|nine)\b""")) { match ->
                val (h, t, u) = match.destructured
                val hv = (wordMap[h]?.toInt() ?: 1) * 100
                val tv = wordMap[t]?.toInt() ?: 0
                val uv = wordMap[u]?.toInt() ?: 0
                (hv + tv + uv).toString()
            }
            s = s.replace(Regex("""\b(one|two|three|four|five|six|seven|eight|nine)\s+hundred\s+(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\b""")) { match ->
                val (h, t) = match.destructured
                val hv = (wordMap[h]?.toInt() ?: 1) * 100
                val tv = wordMap[t]?.toInt() ?: 0
                (hv + tv).toString()
            }
            s = s.replace(Regex("""\b(one|two|three|four|five|six|seven|eight|nine)\s+hundred\s+(one|two|three|four|five|six|seven|eight|nine)\b""")) { match ->
                val (h, u) = match.destructured
                val hv = (wordMap[h]?.toInt() ?: 1) * 100
                val uv = wordMap[u]?.toInt() ?: 0
                (hv + uv).toString()
            }
            s = s.replace(Regex("""\b(one|two|three|four|five|six|seven|eight|nine)\s+hundred\b""")) { match ->
                val (h) = match.destructured
                val hv = (wordMap[h]?.toInt() ?: 1) * 100
                hv.toString()
            }
            // Compound tens + units e.g. "twenty four" -> 24
            s = s.replace(Regex("""\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\s+(one|two|three|four|five|six|seven|eight|nine)\b""")) { match ->
                val (t, u) = match.destructured
                val tv = wordMap[t]?.toInt() ?: 0
                val uv = wordMap[u]?.toInt() ?: 0
                (tv + uv).toString()
            }
            // Single word replacements
            wordMap.forEach { (word, digit) ->
                s = s.replace(Regex("""\b$word\b"""), digit)
            }
            return s
        }

        private fun evalExpression(expr: String): Double? {
            try {
                val clean = expr.trim()

                // Handle "X % of Y"
                if (clean.contains("% of")) {
                    val parts = clean.split("% of")
                    if (parts.size == 2) {
                        val pct = parts[0].trim().toDoubleOrNull()
                        val total = parts[1].trim().toDoubleOrNull()
                        if (pct != null && total != null) {
                            return (pct / 100.0) * total
                        }
                    }
                }

                // Handle "sqrt X" or "sqrt(X)"
                if (clean.startsWith("sqrt")) {
                    val numStr = clean.replace("sqrt", "").replace("(", "").replace(")", "").trim()
                    val num = numStr.toDoubleOrNull()
                    if (num != null && num >= 0) return sqrt(num)
                }

                // Standard operator tokenization e.g. "24 * 24", "120 + 120", "50 - 10", "12 ^ 2"
                val formatted = clean
                    .replace("+", " + ")
                    .replace("-", " - ")
                    .replace("*", " * ")
                    .replace("/", " / ")
                    .replace("^", " ^ ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                val tokens = formatted.split(" ").filter { it.isNotBlank() }
                if (tokens.size == 3) {
                    val left = tokens[0].toDoubleOrNull() ?: return null
                    val op = tokens[1]
                    val right = tokens[2].toDoubleOrNull() ?: return null
                    return when (op) {
                        "+" -> left + right
                        "-" -> left - right
                        "*" -> left * right
                        "/" -> if (right != 0.0) left / right else null
                        "^" -> left.pow(right)
                        else -> null
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }
}

