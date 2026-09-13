package com.ace.app.brain

import android.content.Context
import android.net.Uri
import com.ace.app.brain.model.ModelSpec

enum class BrainState {
    UNINITIALIZED,
    MODEL_FOUND,
    MODEL_COPYING,
    MODEL_VALIDATING,
    LOADING_MODEL,
    MODEL_LOADED,
    INFERENCE_TESTING,
    READY,
    GENERATING,
    CANCELLED,
    MODEL_TOO_SLOW,
    MODEL_PRESENT_NO_RUNTIME,
    ERROR
}

data class ModelHandle(
    val uri: Uri?,
    val path: String?,
    val name: String,
    val sizeBytes: Long,
    val spec: ModelSpec = ModelSpec()
)

sealed class BrainResult {
    data class Success(val message: String = "Model initialization successful", val rawReasoning: String = "") : BrainResult()
    data class Error(val message: String) : BrainResult()
    object Cancelled : BrainResult()
}


interface LocalBrain : ReasoningBrain {
    suspend fun initialize(context: Context, handle: ModelHandle): BrainResult
    suspend fun cancel()
    fun getBrainState(): BrainState
    fun close()
}
