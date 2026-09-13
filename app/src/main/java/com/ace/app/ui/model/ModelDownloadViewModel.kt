package com.ace.app.ui.model

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ace.app.brain.GemmaLocalBrain
import com.ace.app.brain.ModelHandle
import com.ace.app.brain.model.ModelRepository
import com.ace.app.brain.model.ModelSpec
import com.ace.app.brain.model.ModelValidationResult
import com.ace.app.download.DownloadState
import com.ace.app.download.ModelDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ModelUiState(
    val isLoading: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedFormatted: String = "0 MB",
    val totalFormatted: String = "0 MB",
    val isReady: Boolean = false,
    val errorMessage: String? = null
)

class ModelDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ModelUiState())
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    private val downloader = ModelDownloader()
    private var downloadJob: Job? = null

    init {
        android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: entered model selection")
    }

    fun checkRegisteredModel(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val isInstalled = com.ace.app.brain.GemmaBrainManager.isModelInstalled(context)
            val isSetupComplete = com.ace.app.brain.model.OnboardingManager.isModelSetupComplete(context)
            
            if (isInstalled && isSetupComplete) {
                val loaded = try {
                    com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoaded(context)
                } catch (t: Throwable) {
                    android.util.Log.e("ACE_ONBOARDING", "ACE_ONBOARDING: Exception during checkRegisteredModel: ${t.message}", t)
                    false
                }

                if (loaded && com.ace.app.brain.GemmaBrainManager.getBrain(context).isReady()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, isReady = true)
                    return@launch
                }
            }

            _uiState.value = _uiState.value.copy(isLoading = false, isReady = false)
        }
    }

    fun initializeDiscoveredModel(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: auto-detected model selected")
            android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: initialization started")

            val discovery = ModelRepository.discoverModel(context)
            if (discovery.state == com.ace.app.brain.model.ModelDiscoveryState.MODEL_FOUND) {
                ModelRepository.registerModel(context, discovery.uri, discovery.path, "Gemma 3N E2B Q4_0")
                
                val loaded = try {
                    com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoaded(context)
                } catch (t: Throwable) {
                    android.util.Log.e("ACE_ONBOARDING", "ACE_ONBOARDING: initialization failed with exception: ${t.message}", t)
                    false
                }

                if (loaded && com.ace.app.brain.GemmaBrainManager.getBrain(context).isReady()) {
                    android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: initialization succeeded")
                    com.ace.app.brain.model.OnboardingManager.setModelSetupComplete(context, true)
                    _uiState.value = _uiState.value.copy(isLoading = false, isReady = true)
                } else {
                    android.util.Log.e("ACE_ONBOARDING", "ACE_ONBOARDING: initialization failed")
                    com.ace.app.brain.model.OnboardingManager.setModelSetupComplete(context, false)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isReady = false,
                        errorMessage = "Model initialization failed. Please verify memory availability or select another file."
                    )
                }
            } else {
                android.util.Log.e("ACE_ONBOARDING", "ACE_ONBOARDING: initialization failed - no model found")
                com.ace.app.brain.model.OnboardingManager.setModelSetupComplete(context, false)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isReady = false,
                    errorMessage = "No valid model file discovered on device."
                )
            }
        }
    }

    fun onFileSelected(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: model selected via SAF URI")
            android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: initialization started")

            val validation = ModelRepository.validateModel(context, uri, null)
            when (validation) {
                is ModelValidationResult.Success -> {
                    ModelRepository.registerModel(context, uri, "/storage/emulated/0/Download/AceModels/gemma-3n-E2B-it-Q4_0.gguf", validation.spec.name)
                    
                    val loaded = try {
                        com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoaded(context)
                    } catch (t: Throwable) {
                        android.util.Log.e("ACE_ONBOARDING", "ACE_ONBOARDING: initialization failed with exception: ${t.message}", t)
                        false
                    }

                    if (loaded && com.ace.app.brain.GemmaBrainManager.getBrain(context).isReady()) {
                        android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: initialization succeeded")
                        com.ace.app.brain.model.OnboardingManager.setModelSetupComplete(context, true)
                        _uiState.value = _uiState.value.copy(isLoading = false, isReady = true)
                    } else {
                        android.util.Log.e("ACE_ONBOARDING", "ACE_ONBOARDING: initialization failed")
                        com.ace.app.brain.model.OnboardingManager.setModelSetupComplete(context, false)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isReady = false,
                            errorMessage = "Model initialization failed. Please retry or choose another file."
                        )
                    }
                }
                is ModelValidationResult.Error -> {
                    android.util.Log.e("ACE_ONBOARDING", "ACE_ONBOARDING: initialization failed - invalid model file")
                    com.ace.app.brain.model.OnboardingManager.setModelSetupComplete(context, false)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isReady = false,
                        errorMessage = "Invalid Model File: ${validation.reason}"
                    )
                }
            }
        }
    }

    fun startDownload(context: Context) {
        _uiState.value = _uiState.value.copy(isDownloading = true, errorMessage = null, downloadProgress = 0f)
        com.ace.app.brain.GemmaBrainManager.onDownloadStarted()

        val downloadStartMs = System.currentTimeMillis()
        downloadJob = viewModelScope.launch {
            val destFile = File(context.filesDir, "google_gemma-3n-E4B-it-Q4_K_M.gguf")

            downloader.downloadModel(destFile).collect { state ->
                when (state) {
                    is DownloadState.Progress -> {
                        val progress = if (state.totalBytes > 0) state.bytesDownloaded.toFloat() / state.totalBytes else 0f
                        _uiState.value = _uiState.value.copy(
                            isDownloading = true,
                            downloadProgress = progress,
                            downloadedFormatted = "${state.bytesDownloaded / (1024 * 1024)} MB",
                            totalFormatted = if (state.totalBytes > 0) "${state.totalBytes / (1024 * 1024)} MB" else "Unknown"
                        )
                    }
                    is DownloadState.Success -> {
                        val durationMs = System.currentTimeMillis() - downloadStartMs
                        com.ace.app.brain.GemmaBrainManager.onDownloadCompleted(context, destFile, durationMs)
                        _uiState.value = _uiState.value.copy(isDownloading = false, isReady = true)
                    }
                    is DownloadState.Error -> {
                        com.ace.app.brain.GemmaBrainManager.onDownloadFailed(state.message)
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            errorMessage = state.message
                        )
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        com.ace.app.brain.GemmaBrainManager.onDownloadFailed("Download cancelled by user.")
        _uiState.value = _uiState.value.copy(isDownloading = false, errorMessage = "Download cancelled by user.")
    }
}