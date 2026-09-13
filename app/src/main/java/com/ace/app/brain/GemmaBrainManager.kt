package com.ace.app.brain

import android.content.Context
import android.util.Log
import com.ace.app.brain.model.ModelDiscoveryResult
import com.ace.app.brain.model.ModelDiscoveryState
import com.ace.app.brain.model.ModelRepository
import com.ace.app.brain.model.ModelValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class ModelInstallationState {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
    FAILED
}

enum class BrainRuntimeState {
    NOT_LOADED,
    LOADING,
    READY,
    ERROR
}

/**
 * Single Central Brain Manager responsible for Gemma model lifecycle, persistent storage,
 * and optimized runtime memory management.
 *
 * Enforces:
 * 1. Separation of Model Installation State (Disk) vs Brain Runtime State (RAM).
 * 2. Downloading Gemma model ONLY once across future app launches.
 * 3. Exactly ONE Gemma local brain instance per app process.
 * 4. Thread-safe state machine (NOT_LOADED -> LOADING -> READY) protected by Mutex.
 * 5. Structured ACE_BRAIN logcat markers, instance IDs, and timing metrics.
 * 6. Optimized cold-start loading by caching model discovery and eliminating redundant scans.
 */
object GemmaBrainManager {

    private const val TAG_BRAIN = "ACE_BRAIN"
    private const val MIN_EXPECTED_SIZE = 500_000_000L // 500 MB minimum for GGUF model

    @Volatile
    private var sharedBrain: GemmaLocalBrain? = null

    @Volatile
    private var cachedDiscovery: ModelDiscoveryResult? = null

    private val initMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _installationState = MutableStateFlow(ModelInstallationState.NOT_INSTALLED)
    val installationState: StateFlow<ModelInstallationState> = _installationState.asStateFlow()

    private val _runtimeState = MutableStateFlow(BrainRuntimeState.NOT_LOADED)
    val runtimeState: StateFlow<BrainRuntimeState> = _runtimeState.asStateFlow()

    // Timing metrics
    private var processStartTimeMs: Long = System.currentTimeMillis()
    private var modelFoundTimeMs: Long = 0L
    private var runtimeInitStartTimeMs: Long = 0L
    private var lastDownloadTimeMs: Long = 0L
    private var lastValidationTimeMs: Long = 0L
    private var lastRuntimeLoadTimeMs: Long = 0L
    private var lastBrainReadyTimeMs: Long = 0L

    fun recordProcessStart() {
        processStartTimeMs = System.currentTimeMillis()
    }

    /**
     * Returns the single authoritative GemmaLocalBrain instance per process.
     */
    fun getBrain(context: Context? = null): GemmaLocalBrain {
        return sharedBrain ?: synchronized(this) {
            sharedBrain ?: GemmaLocalBrain().also { sharedBrain = it }
        }
    }

    /**
     * Checks if Gemma model file exists on disk and is readable/valid.
     * Uses in-memory caching after initial discovery to eliminate redundant disk/MediaStore scans.
     */
    fun isModelInstalled(context: Context): Boolean {
        val cached = cachedDiscovery
        if (cached != null && cached.state == ModelDiscoveryState.MODEL_FOUND) {
            val path = cached.path
            if (!path.isNullOrBlank() && File(path).exists() && File(path).length() >= MIN_EXPECTED_SIZE) {
                _installationState.value = ModelInstallationState.INSTALLED
                return true
            }
        }

        val valStart = System.currentTimeMillis()
        val discovery = ModelRepository.discoverModel(context)
        cachedDiscovery = discovery
        val fileExists = ModelRepository.ensureLocalModelFile(context, null, null) != null

        val isInstalled = discovery.state == ModelDiscoveryState.MODEL_FOUND || fileExists
        lastValidationTimeMs = System.currentTimeMillis() - valStart
        modelFoundTimeMs = System.currentTimeMillis()

        if (isInstalled) {
            _installationState.value = ModelInstallationState.INSTALLED
            Log.i(TAG_BRAIN, "ACE_BRAIN: model_found=true")
            Log.i(TAG_BRAIN, "ACE_BRAIN: using_existing_local_model=true")
            Log.i(TAG_BRAIN, "ACE_BRAIN: download_skipped=true")
            Log.i(TAG_BRAIN, "ACE_BRAIN: model_validation=SUCCESS")
            Log.i(TAG_BRAIN, "ACE_BRAIN: model_validation_time_ms=$lastValidationTimeMs")
        } else {
            if (_installationState.value != ModelInstallationState.DOWNLOADING) {
                _installationState.value = ModelInstallationState.NOT_INSTALLED
            }
            Log.i(TAG_BRAIN, "ACE_BRAIN: model_found=false")
        }
        return isInstalled
    }

    /**
     * Ensures Gemma model runtime is loaded into RAM.
     * Guaranteed thread-safe with Mutex protection and singleton brain reuse.
     */
    suspend fun ensureRuntimeLoaded(context: Context): Boolean = initMutex.withLock {
        return withContext(Dispatchers.IO) {
            val brain = getBrain(context)
            val instanceId = System.identityHashCode(brain)
            val currentRuntime = _runtimeState.value

            if (brain.isReady() || currentRuntime == BrainRuntimeState.READY) {
                _runtimeState.value = BrainRuntimeState.READY
                Log.i(TAG_BRAIN, "ACE_BRAIN: existing_instance_reused=true")
                Log.i(TAG_BRAIN, "ACE_BRAIN: runtime_reload_skipped=true")
                Log.i(TAG_BRAIN, "ACE_BRAIN: runtime_ready=true")
                Log.i(TAG_BRAIN, "ACE_BRAIN: instance_id=$instanceId")
                return@withContext true
            }

            if (currentRuntime == BrainRuntimeState.LOADING) {
                Log.i(TAG_BRAIN, "ACE_BRAIN: initialization_already_in_progress=true")
                Log.i(TAG_BRAIN, "ACE_BRAIN: instance_id=$instanceId")
                return@withContext false
            }

            // Check persistent model on disk
            if (!isModelInstalled(context)) {
                Log.w(TAG_BRAIN, "ACE_BRAIN: cannot load runtime - model file not installed on disk.")
                Log.e("ACE_MODEL_ERROR", "ACE_MODEL_ERROR: FAILED - model file not found or unreadable on disk")
                _runtimeState.value = BrainRuntimeState.NOT_LOADED
                return@withContext false
            }

            // RAM Pre-check: Ensure device has sufficient memory to host ~4 GB Gemma runtime safely
            if (!hasSufficientMemory(context)) {
                Log.w(TAG_BRAIN, "ACE_BRAIN: Memory pre-check failed. Insufficient device RAM for 4.46B parameter Gemma model.")
                Log.e("ACE_MODEL_ERROR", "ACE_MODEL_ERROR: FAILED - Insufficient device RAM for local Gemma model")
                _runtimeState.value = BrainRuntimeState.ERROR
                return@withContext false
            }

            _runtimeState.value = BrainRuntimeState.LOADING
            runtimeInitStartTimeMs = System.currentTimeMillis()
            Log.i(TAG_BRAIN, "ACE_BRAIN: runtime_initialization_started=true")
            Log.i(TAG_BRAIN, "ACE_BRAIN: instance_id=$instanceId")

            val overallStart = System.currentTimeMillis()
            val discovery = cachedDiscovery ?: ModelRepository.discoverModel(context).also { cachedDiscovery = it }
            val handle = ModelHandle(
                uri = discovery.uri,
                path = discovery.path ?: "/storage/emulated/0/Download/${ModelRepository.DEFAULT_MODEL_FILENAME}",
                name = "Gemma 3N E2B Q4_0",
                sizeBytes = discovery.sizeBytes
            )

            Log.i("ACE_MODEL_INIT", "ACE_MODEL_INIT: INITIALIZING path=${handle.path} size=${handle.sizeBytes}")

            val loadStart = System.currentTimeMillis()
            val initResult = brain.initialize(context, handle)
            val readyTimeMs = System.currentTimeMillis()
            lastRuntimeLoadTimeMs = readyTimeMs - loadStart
            lastBrainReadyTimeMs = readyTimeMs - processStartTimeMs

            if (brain.isReady()) {
                _runtimeState.value = BrainRuntimeState.READY
                val discMs = if (modelFoundTimeMs > 0) modelFoundTimeMs - processStartTimeMs else 0L
                val rtLoadMs = readyTimeMs - runtimeInitStartTimeMs

                Log.i("ACE_MODEL_READY", "ACE_MODEL_READY: READY load_time_ms=$rtLoadMs total_time_ms=$lastBrainReadyTimeMs")
                Log.i(TAG_BRAIN, "ACE_BRAIN: runtime_ready=true")
                Log.i(TAG_BRAIN, "ACE_BRAIN: instance_id=$instanceId")
                Log.i(TAG_BRAIN, "ACE_BRAIN: model_discovery_ms=$discMs")
                Log.i(TAG_BRAIN, "ACE_BRAIN: runtime_load_ms=$rtLoadMs")
                Log.i(TAG_BRAIN, "ACE_BRAIN: total_brain_ready_ms=$lastBrainReadyTimeMs")
                true
            } else {
                _runtimeState.value = BrainRuntimeState.ERROR
                Log.e("ACE_MODEL_ERROR", "ACE_MODEL_ERROR: FAILED reason=$initResult")
                Log.e(TAG_BRAIN, "ACE_BRAIN: runtime_state=ERROR (Initialization failed: $initResult)")
                false
            }
        }
    }

    /**
     * Non-blocking background launch of runtime initialization.
     */
    fun ensureRuntimeLoadedAsync(context: Context) {
        scope.launch {
            ensureRuntimeLoaded(context)
        }
    }

    /**
     * Mark installation state when a download starts.
     */
    fun onDownloadStarted() {
        _installationState.value = ModelInstallationState.DOWNLOADING
        Log.i(TAG_BRAIN, "ACE_BRAIN: model_found=false")
        Log.i(TAG_BRAIN, "ACE_BRAIN: starting_model_download=true")
    }

    /**
     * Mark installation state when a download completes and validate the file.
     */
    fun onDownloadCompleted(context: Context, downloadedFile: File, durationMs: Long) {
        lastDownloadTimeMs = durationMs
        Log.i(TAG_BRAIN, "ACE_BRAIN: model_download_complete=true")
        Log.i(TAG_BRAIN, "ACE_BRAIN: model_download_time_ms=$durationMs")

        val valStart = System.currentTimeMillis()
        val validation = ModelRepository.validateModel(context, null, downloadedFile.absolutePath)
        lastValidationTimeMs = System.currentTimeMillis() - valStart

        if (validation is ModelValidationResult.Success) {
            ModelRepository.registerModel(context, null, downloadedFile.absolutePath, validation.spec.name)
            cachedDiscovery = ModelDiscoveryResult(
                state = ModelDiscoveryState.MODEL_FOUND,
                path = downloadedFile.absolutePath,
                sizeBytes = downloadedFile.length(),
                message = "Downloaded and validated"
            )
            _installationState.value = ModelInstallationState.INSTALLED
            Log.i(TAG_BRAIN, "ACE_BRAIN: model_validation=SUCCESS")
            Log.i(TAG_BRAIN, "ACE_BRAIN: model_validation_time_ms=$lastValidationTimeMs")

            // Automatically kick off runtime loading after fresh download
            ensureRuntimeLoadedAsync(context)
        } else {
            _installationState.value = ModelInstallationState.FAILED
            Log.e(TAG_BRAIN, "ACE_BRAIN: model_validation=FAILED")
        }
    }

    fun onDownloadFailed(reason: String) {
        _installationState.value = ModelInstallationState.FAILED
        Log.e(TAG_BRAIN, "ACE_BRAIN: model_download_failed reason=$reason")
    }

    /**
     * Memory pre-check helper.
     */
    fun hasSufficientMemory(context: Context): Boolean {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return true
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalRamMb = memInfo.totalMem / (1024 * 1024)
            val availRamMb = memInfo.availMem / (1024 * 1024)
            Log.i(TAG_BRAIN, "ACE_BRAIN: device_total_ram_mb=$totalRamMb avail_ram_mb=$availRamMb low_memory=${memInfo.lowMemory}")
            if (totalRamMb < 3500 || availRamMb < 1000 || memInfo.lowMemory) {
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG_BRAIN, "ACE_BRAIN: Could not query memory info: ${e.message}")
        }
        return true
    }

    /**
     * Metrics reporting helper.
     */
    fun getMetricsLog(): String {
        return "Metrics [download=${lastDownloadTimeMs}ms, val=${lastValidationTimeMs}ms, load=${lastRuntimeLoadTimeMs}ms, ready=${lastBrainReadyTimeMs}ms]"
    }
}
