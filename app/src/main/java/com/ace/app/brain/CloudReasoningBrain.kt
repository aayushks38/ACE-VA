package com.ace.app.brain

import android.content.Context
import android.util.Log
import com.ace.app.agent.AgentTaskContext
import com.ace.app.agent.GoalInterpretation
import com.ace.app.agent.GoalUnderstandingEngine
import com.ace.app.agent.ScreenObservation
import com.ace.app.agent.ScreenObservationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Pluggable Cloud Reasoning Provider.
 * Connects to a user-configured cloud endpoint (e.g., OpenAI / Gemini / Claude API).
 * Reads user-provided API credentials securely from app preferences.
 * Implements the exact same ReasoningBrain cognitive contract as local on-device inference.
 */
class CloudReasoningBrain(private val context: Context) : ReasoningBrain {

    override val backendType: ReasoningBackend = ReasoningBackend.CLOUD_PROVIDER

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ACE_CLOUD_BRAIN"
        private const val PREFS_NAME = "ace_cloud_settings"
        private const val KEY_ENDPOINT = "cloud_api_endpoint"
        private const val KEY_API_KEY = "cloud_api_key"
        private const val KEY_MODEL = "cloud_model_name"
    }

    override fun isReady(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        return apiKey.isNotBlank()
    }

    override suspend fun interpretGoal(
        goal: String,
        observation: ScreenObservation,
        context: AgentTaskContext
    ): GoalInterpretation = withContext(Dispatchers.IO) {
        val cleanGoal = goal.trim()
        if (!isReady()) {
            Log.w(TAG, "ACE_CLOUD_BRAIN: Cloud reasoning brain is not ready (API key unconfigured).")
            return@withContext GoalInterpretation(rawGoal = cleanGoal, objectiveType = "UNINTERPRETED_BACKEND_UNAVAILABLE")
        }

        val prefs = this@CloudReasoningBrain.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val endpoint = prefs.getString(KEY_ENDPOINT, "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions"
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val modelName = prefs.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"

        val jsonPayload = JSONObject().apply {
            put("model", modelName)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are the cognitive goal understanding engine for ACE, an autonomous computer-use agent. Analyze natural language goal semantically. Return JSON: {\"objectiveType\":\"INFORMATION_RETRIEVAL|STATE_MODIFICATION|NAVIGATION|COMMUNICATION|TRANSACTION|GENERAL\",\"requestedOutcome\":\"<outcome>\",\"targetEntities\":[\"<entity>\"],\"constraints\":[\"<constraint>\"],\"temporalRequirements\":[],\"desiredFinalState\":\"<state>\",\"desiredInformation\":\"<info>\",\"successConditions\":[\"<condition>\"],\"unresolvedAmbiguities\":[],\"clarificationRequired\":false,\"clarificationQuestion\":null}")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Goal: $cleanGoal")
                })
            })
            put("temperature", 0.1)
        }

        try {
            val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (response.isSuccessful && responseBodyStr.isNotBlank()) {
                val jsonRes = JSONObject(responseBodyStr)
                val choices = jsonRes.optJSONArray("choices")
                val contentStr = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""

                val rawTrimmed = contentStr.trim()
                val sStart = rawTrimmed.indexOf('{')
                val sEnd = rawTrimmed.lastIndexOf('}')
                if (sStart != -1 && sEnd > sStart) {
                    val json = JSONObject(rawTrimmed.substring(sStart, sEnd + 1))
                    val typeStr = json.optString("objectiveType", "GENERAL")
                    val isAmbig = json.optBoolean("clarificationRequired", json.optBoolean("isAmbiguous", false))
                    val q = if (isAmbig) json.optString("clarificationQuestion", "Could you clarify your goal?") else null
                    
                    val entities = mutableListOf<String>()
                    val arrEnt = json.optJSONArray("targetEntities")
                    if (arrEnt != null) {
                        for (i in 0 until arrEnt.length()) entities.add(arrEnt.optString(i))
                    }

                    val reqs = mutableListOf<String>()
                    val arrReq = json.optJSONArray("successConditions")
                    if (arrReq != null) {
                        for (i in 0 until arrReq.length()) reqs.add(arrReq.optString(i))
                    }

                    return@withContext GoalInterpretation(
                        rawGoal = cleanGoal,
                        objectiveType = typeStr,
                        requestedOutcome = json.optString("requestedOutcome", cleanGoal),
                        targetEntities = entities,
                        constraints = emptyList(),
                        temporalRequirements = emptyList(),
                        desiredFinalState = json.optString("desiredFinalState", ""),
                        desiredInformation = json.optString("desiredInformation", ""),
                        successConditions = reqs,
                        unresolvedAmbiguities = emptyList(),
                        clarificationRequired = isAmbig,
                        clarificationQuestion = q
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ACE_CLOUD_BRAIN: interpretGoal error: ${e.message}")
        }

        return@withContext GoalInterpretation(rawGoal = cleanGoal, objectiveType = "UNINTERPRETED_BACKEND_UNAVAILABLE")
    }

    override suspend fun reasonNextDecision(
        goal: String,
        observation: ScreenObservation,
        context: AgentTaskContext,
        generationId: Long
    ): AgentDecision = withContext(Dispatchers.IO) {
        val prefs = this@CloudReasoningBrain.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val endpoint = prefs.getString(KEY_ENDPOINT, "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions"
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val modelName = prefs.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"

        if (apiKey.isBlank()) {
            Log.w(TAG, "ACE_CLOUD_BRAIN: API key not configured by user.")
            return@withContext AgentDecision.Blocked("Cloud API key not configured.")
        }

        val compactUi = ScreenObservationEngine.formatCompactUiRepresentation(goal, observation)
        val jsonPayload = JSONObject().apply {
            put("model", modelName)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are ACE, an autonomous computer-use reasoning model for Android. Based on the user goal, postcondition, previous actions, and observation tree, select ONE next decision. Return JSON with status: DONE|CONTINUE|CLARIFY|REPLAN|BLOCKED, action: ui_click|ui_type|ui_scroll|web_open_url|ui_open_app, target, text, question, reason.")
                })
                put(JSONObject().apply {
                    val postconditionSummary = context.expectedPostcondition.summary.ifBlank { goal }
                    val historyStr = context.actionHistory.takeLast(4).joinToString("; ")
                    val blockersStr = context.blockers.joinToString("; ")
                    put("role", "user")
                    put("content", "Goal: $goal\nExpected Outcome: $postconditionSummary\nDesired State: ${context.expectedPostcondition.desiredState}\nDesired Info: ${context.expectedPostcondition.desiredInformation}\nTarget Entities: ${context.expectedPostcondition.targetEntities}\nPrevious Actions: $historyStr\nBlockers: $blockersStr\nPerception Available: ${observation.isPerceptionAvailable}\nObservation:\n$compactUi")
                })
            })
            put("temperature", 0.1)
        }

        try {
            val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful || responseBodyStr.isBlank()) {
                Log.e(TAG, "ACE_CLOUD_BRAIN: Cloud HTTP error code=${response.code} body=$responseBodyStr")
                return@withContext AgentDecision.Blocked("Cloud reasoning service error (HTTP ${response.code}).")
            }

            val jsonRes = JSONObject(responseBodyStr)
            val choices = jsonRes.optJSONArray("choices")
            val contentStr = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""

            val rawTrimmed = contentStr.trim()
            val sStart = rawTrimmed.indexOf('{')
            val sEnd = rawTrimmed.lastIndexOf('}')
            if (sStart != -1 && sEnd > sStart) {
                val parsedObj = JSONObject(rawTrimmed.substring(sStart, sEnd + 1))
                val status = parsedObj.optString("status", "CONTINUE").uppercase()
                when {
                    status == "CLARIFY" || parsedObj.optBoolean("clarificationNeeded", false) -> {
                        AgentDecision.Clarify(parsedObj.optString("question", "Could you clarify what you'd like me to do?"))
                    }
                    status == "DONE" || status == "COMPLETE" -> {
                        AgentDecision.Complete(parsedObj.optString("reason", "Goal satisfied"))
                    }
                    status == "REPLAN" -> {
                        AgentDecision.Replan(
                            updatedGoal = parsedObj.optString("updatedGoal", goal),
                            reason = parsedObj.optString("reason", "")
                        )
                    }
                    status == "BLOCKED" -> {
                        AgentDecision.Blocked(parsedObj.optString("reason", "Action blocked"))
                    }
                    else -> {
                        AgentDecision.Action(
                            primitive = parsedObj.optString("action", "ui_click"),
                            target = parsedObj.optString("target", ""),
                            inputText = parsedObj.optString("text", "")
                        )
                    }
                }
            } else {
                AgentDecision.ConversationalResponse(rawTrimmed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ACE_CLOUD_BRAIN: Exception during cloud reasoning call: ${e.message}")
            AgentDecision.Blocked("Cloud reasoning execution error: ${e.message}")
        }
    }

    override suspend fun verifyPostcondition(
        goal: String,
        observation: ScreenObservation,
        context: AgentTaskContext
    ): com.ace.app.agent.IndependentVerificationOutcome = withContext(Dispatchers.IO) {
        val empirical = com.ace.app.agent.IndependentGoalVerifier.evaluateSemanticPostcondition(
            goal,
            context.expectedPostcondition,
            com.ace.app.agent.EnvironmentEvidence(
                screenObservation = observation,
                capturedEvidenceMap = context.capturedEvidence,
                actionHistory = context.actionHistory,
                blockers = context.blockers,
                brainHypothesis = context.capturedEvidence["brain_completion_hypothesis"]
            )
        )

        if (empirical.isVerified) return@withContext empirical

        if (!isReady()) return@withContext empirical

        val prefs = this@CloudReasoningBrain.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val endpoint = prefs.getString(KEY_ENDPOINT, "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions"
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val modelName = prefs.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"

        val jsonPayload = JSONObject().apply {
            put("model", modelName)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are the model-grounded postcondition verifier for ACE. Evaluate if current observation and evidence prove that expected goal postcondition is satisfied. Return JSON: {\"isVerified\":true|false,\"summary\":\"<explanation>\"}")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Goal: $goal\nExpected Outcome: ${context.expectedPostcondition.summary}\nTarget Entities: ${context.expectedPostcondition.targetEntities}\nSuccess Conditions: ${context.expectedPostcondition.successConditions}\nDesired Info: ${context.expectedPostcondition.desiredInformation}\nDesired State: ${context.expectedPostcondition.desiredState}\nApp: ${observation.appName} (${observation.packageName})\nPerception Available: ${observation.isPerceptionAvailable}\nVisible Text: ${observation.visibleText.take(15)}\nCaptured Evidence: ${context.capturedEvidence}")
                })
            })
            put("temperature", 0.0)
        }

        try {
            val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (response.isSuccessful && responseBodyStr.isNotBlank()) {
                val jsonRes = JSONObject(responseBodyStr)
                val choices = jsonRes.optJSONArray("choices")
                val contentStr = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""

                val rawTrimmed = contentStr.trim()
                val sStart = rawTrimmed.indexOf('{')
                val sEnd = rawTrimmed.lastIndexOf('}')
                if (sStart != -1 && sEnd > sStart) {
                    val parsedObj = JSONObject(rawTrimmed.substring(sStart, sEnd + 1))
                    val verified = parsedObj.optBoolean("isVerified", false)
                    val summaryStr = parsedObj.optString("summary", "Model verified postcondition")
                    if (verified && observation.isPerceptionAvailable) {
                        return@withContext com.ace.app.agent.IndependentVerificationOutcome(
                            isVerified = true,
                            status = com.ace.app.agent.TaskStatus.COMPLETED,
                            evidence = "Model-grounded semantic verification satisfied: $summaryStr",
                            summary = summaryStr
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ACE_CLOUD_BRAIN: verifyPostcondition error: ${e.message}")
        }

        return@withContext empirical
    }
}
