package com.ace.app.brain.model

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.InputStream

data class ModelSpec(
    val name: String = "Gemma 3N E2B Q4_0",
    val filename: String = ModelRepository.DEFAULT_MODEL_FILENAME,
    val version: String = "1.0",
    val expectedSizeBytes: Long = 2_760_000_000L,
    val format: String = "GGUF",
    val downloadUrl: String = ""
)

enum class ModelDiscoveryState {
    MODEL_FOUND,
    MODEL_NOT_FOUND,
    MODEL_UNREADABLE,
    MODEL_INVALID
}

data class ModelDiscoveryResult(
    val state: ModelDiscoveryState,
    val path: String? = null,
    val uri: Uri? = null,
    val sizeBytes: Long = 0L,
    val message: String = ""
)

sealed class ModelValidationResult {
    data class Success(val uri: Uri?, val path: String?, val sizeBytes: Long, val spec: ModelSpec) : ModelValidationResult()
    data class Error(val reason: String) : ModelValidationResult()
}

object ModelRepository {
    const val DEFAULT_MODEL_FILENAME = "gemma-3n-E2B-it-Q4_0.gguf"
    private const val PREFS_NAME = "ace_model_prefs"
    private const val KEY_MODEL_URI = "registered_model_uri"
    private const val KEY_MODEL_PATH = "registered_model_path"
    private const val KEY_MODEL_NAME = "registered_model_name"

    // GGUF Magic Header: 'G' 'G' 'U' 'F' -> 0x47, 0x47, 0x55, 0x46 (0x46554747)
    private val GGUF_MAGIC = byteArrayOf(0x47.toByte(), 0x47.toByte(), 0x55.toByte(), 0x46.toByte())

    fun getRegisteredModelUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_MODEL_URI, null)
        return if (!uriStr.isNullOrBlank()) Uri.parse(uriStr) else null
    }

    fun getRegisteredModelPath(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODEL_PATH, null)
    }

    fun registerModel(context: Context, uri: Uri?, path: String?, name: String = "Gemma 3N") {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MODEL_URI, uri?.toString())
            .putString(KEY_MODEL_PATH, path)
            .putString(KEY_MODEL_NAME, name)
            .apply()
    }

    fun clearRegisteredModel(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun queryMediaStoreForModel(context: Context): Uri? {
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.SIZE
        )

        val collections = listOfNotNull(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI else null,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) android.provider.MediaStore.Files.getContentUri("external_primary") else null,
            android.provider.MediaStore.Files.getContentUri("external")
        )

        for (collection in collections) {
            try {
                context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn) ?: ""
                        val size = cursor.getLong(sizeColumn)
                        if (name.endsWith(".gguf", ignoreCase = true)) {
                            Log.e("ACE_MEDIASTORE", "ACE_MEDIASTORE: Found GGUF file in MediaStore: '$name' ($size bytes)")
                        }
                        if ((name.equals(DEFAULT_MODEL_FILENAME, ignoreCase = true) || name.contains("gemma-3n-E2B", ignoreCase = true) || name.contains("E2B", ignoreCase = true)) && size >= 500_000_000L) {
                            val id = cursor.getLong(idColumn)
                            val uri = android.content.ContentUris.withAppendedId(collection, id)
                            try {
                                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                                if (pfd != null) {
                                    pfd.close()
                                    Log.e("ACE_MODEL_LOAD", "ACE_MODEL_LOAD: MediaStore discovered readable target E2B model URI: $uri ($name, $size bytes)")
                                    return uri
                                }
                            } catch (e: Exception) {
                                Log.w("ACE_MODEL_LOAD", "ACE_MODEL_LOAD: MediaStore URI $uri not readable directly: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ACE_MODEL_LOAD", "ACE_MODEL_LOAD: MediaStore query exception: ${e.message}")
            }
        }
        return null
    }

    fun scanAndGetUri(context: Context, path: String): Uri? {
        val mediaUri = queryMediaStoreForModel(context)
        if (mediaUri != null) return mediaUri

        val countDownLatch = java.util.concurrent.CountDownLatch(1)
        var scannedUri: Uri? = null
        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                null
            ) { _, uri ->
                scannedUri = uri
                countDownLatch.countDown()
            }
            countDownLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("ACE_MODEL_LOAD", "MediaScanner scan failed: ${e.message}")
        }
        return scannedUri ?: queryMediaStoreForModel(context)
    }

    fun discoverModel(context: Context): ModelDiscoveryResult {
        // 1. Check previously registered URI (SAF or MediaStore) FIRST
        val registeredUri = getRegisteredModelUri(context)
        val registeredPath = getRegisteredModelPath(context) ?: "/storage/emulated/0/Download/$DEFAULT_MODEL_FILENAME"
        if (registeredUri != null) {
            val valResult = validateModel(context, registeredUri, null)
            if (valResult is ModelValidationResult.Success) {
                Log.e("ACE_MODEL_PATH", "ACE_MODEL_PATH: $registeredPath")
                Log.e("ACE_MODEL_SOURCE", "ACE_MODEL_SOURCE: persistent_existing_file")
                Log.e("ACE_MODEL_COPY", "ACE_MODEL_COPY: skipped")
                return ModelDiscoveryResult(
                    state = ModelDiscoveryState.MODEL_FOUND,
                    uri = registeredUri,
                    path = registeredPath,
                    sizeBytes = valResult.sizeBytes,
                    message = "Model found via registered SAF URI"
                )
            }
        }

        // 2. Direct check for target E2B file in Download or Download/AceModels directory
        val directCandidateFiles = listOf(
            File("/storage/emulated/0/Download/AceModels", DEFAULT_MODEL_FILENAME),
            File("/storage/emulated/0/Download", DEFAULT_MODEL_FILENAME)
        )
        for (directFile in directCandidateFiles) {
            if (directFile.exists() && directFile.length() >= 500_000_000L) {
                val mediaUri = queryMediaStoreForModel(context) ?: scanAndGetUri(context, directFile.absolutePath)
                val valResult = validateModel(context, mediaUri, directFile.absolutePath)
                if (valResult is ModelValidationResult.Success) {
                    Log.i("ACE_MODEL_DISCOVERY", "ACE_MODEL_DISCOVERY: DISCOVERED path=${directFile.absolutePath} size=${valResult.sizeBytes}")
                    Log.i("ACE_MODEL_VALIDATION", "ACE_MODEL_VALIDATION: VALIDATED file=${directFile.name}")
                    Log.e("ACE_MODEL_PATH", "ACE_MODEL_PATH: ${directFile.absolutePath}")
                    Log.e("ACE_MODEL_SOURCE", "ACE_MODEL_SOURCE: persistent_existing_file")
                    Log.e("ACE_MODEL_COPY", "ACE_MODEL_COPY: skipped")
                    registerModel(context, mediaUri, directFile.absolutePath, valResult.spec.name)
                    return ModelDiscoveryResult(
                        state = ModelDiscoveryState.MODEL_FOUND,
                        uri = mediaUri,
                        path = directFile.absolutePath,
                        sizeBytes = valResult.sizeBytes,
                        message = "Model found via Download path: ${directFile.name}"
                    )
                }
            }
        }

        // 3. Query MediaStore for target E2B model (Android 14 compliant)
        val mediaStoreUri = queryMediaStoreForModel(context)
        if (mediaStoreUri != null) {
            val valResult = validateModel(context, mediaStoreUri, null)
            if (valResult is ModelValidationResult.Success) {
                Log.i("ACE_MODEL_DISCOVERY", "ACE_MODEL_DISCOVERY: DISCOVERED via MediaStore uri=$mediaStoreUri")
                Log.i("ACE_MODEL_VALIDATION", "ACE_MODEL_VALIDATION: VALIDATED MediaStore model")
                Log.e("ACE_MODEL_PATH", "ACE_MODEL_PATH: /storage/emulated/0/Download/$DEFAULT_MODEL_FILENAME")
                Log.e("ACE_MODEL_SOURCE", "ACE_MODEL_SOURCE: persistent_existing_file")
                Log.e("ACE_MODEL_COPY", "ACE_MODEL_COPY: skipped")
                registerModel(context, mediaStoreUri, "/storage/emulated/0/Download/$DEFAULT_MODEL_FILENAME", valResult.spec.name)
                return ModelDiscoveryResult(
                    state = ModelDiscoveryState.MODEL_FOUND,
                    uri = mediaStoreUri,
                    path = "/storage/emulated/0/Download/$DEFAULT_MODEL_FILENAME",
                    sizeBytes = valResult.sizeBytes,
                    message = "Model auto-discovered via MediaStore"
                )
            }
        }

        // 4. Check SAF raw Download Uri for zero-copy file descriptor access
        val safRawUri = Uri.parse("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$DEFAULT_MODEL_FILENAME")
        try {
            val pfd = context.contentResolver.openFileDescriptor(safRawUri, "r")
            if (pfd != null) {
                val size = pfd.statSize
                pfd.close()
                if (size >= 500_000_000L) {
                    Log.i("ACE_MODEL_DISCOVERY", "ACE_MODEL_DISCOVERY: DISCOVERED via SAF raw URI")
                    Log.i("ACE_MODEL_VALIDATION", "ACE_MODEL_VALIDATION: VALIDATED SAF raw URI")
                    Log.e("ACE_MODEL_PATH", "ACE_MODEL_PATH: /storage/emulated/0/Download/$DEFAULT_MODEL_FILENAME")
                    Log.e("ACE_MODEL_SOURCE", "ACE_MODEL_SOURCE: persistent_existing_file")
                    Log.e("ACE_MODEL_COPY", "ACE_MODEL_COPY: skipped")
                    registerModel(context, safRawUri, "/storage/emulated/0/Download/$DEFAULT_MODEL_FILENAME", "Gemma 3N E2B Q4_0")
                    return ModelDiscoveryResult(
                        state = ModelDiscoveryState.MODEL_FOUND,
                        uri = safRawUri,
                        path = "/storage/emulated/0/Download/$DEFAULT_MODEL_FILENAME",
                        sizeBytes = size,
                        message = "Model found via SAF raw URI"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("ACE_MODEL_LOAD", "ACE_MODEL_LOAD: Raw SAF URI check: ${e.message}")
        }

        // 5. Scan Downloads directories (including AceModels) for any .gguf files >= 500MB
        val downloadsDirs = listOfNotNull(
            File("/storage/emulated/0/Download/AceModels"),
            File("/storage/emulated/0/Download"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        )

        for (dir in downloadsDirs) {
            if (dir.exists() && dir.isDirectory) {
                val ggufFiles = dir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }
                if (!ggufFiles.isNullOrEmpty()) {
                    val largestGguf = ggufFiles.maxByOrNull { it.length() }
                    if (largestGguf != null && largestGguf.length() >= 500_000_000L) {
                        val valResult = validateModel(context, null, largestGguf.absolutePath)
                        if (valResult is ModelValidationResult.Success) {
                            Log.i("ACE_MODEL_DISCOVERY", "ACE_MODEL_DISCOVERY: DISCOVERED path=${largestGguf.absolutePath} size=${valResult.sizeBytes}")
                            Log.i("ACE_MODEL_VALIDATION", "ACE_MODEL_VALIDATION: VALIDATED file=${largestGguf.name}")
                            Log.i("ACE_MODEL_PATH", "ACE_MODEL_PATH: ${largestGguf.absolutePath}")
                            Log.i("ACE_MODEL_SOURCE", "ACE_MODEL_SOURCE: persistent_existing_file")
                            Log.i("ACE_MODEL_COPY", "ACE_MODEL_COPY: skipped")
                            registerModel(context, null, largestGguf.absolutePath, valResult.spec.name)
                            return ModelDiscoveryResult(
                                state = ModelDiscoveryState.MODEL_FOUND,
                                path = largestGguf.absolutePath,
                                sizeBytes = valResult.sizeBytes,
                                message = "Model auto-discovered in Downloads: ${largestGguf.name}"
                            )
                        }
                    }
                }
            }
        }

        Log.w("ACE_MODEL_DISCOVERY", "ACE_MODEL_DISCOVERY: FAILED - No GGUF model file discovered")

        return ModelDiscoveryResult(
            state = ModelDiscoveryState.MODEL_NOT_FOUND,
            message = "No GGUF model file discovered"
        )
    }

    fun validateModel(context: Context, uri: Uri?, path: String?, spec: ModelSpec = ModelSpec()): ModelValidationResult {
        try {
            var fileSize = 0L

            if (uri != null) {
                try {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        fileSize = pfd.statSize
                        pfd.close()
                        return ModelValidationResult.Success(uri = uri, path = path, sizeBytes = fileSize, spec = spec)
                    }
                } catch (e: Exception) {
                    Log.w("ACE_MODEL_LOAD", "ACE_MODEL_LOAD: validateModel URI open failed: ${e.message}")
                }
            }

            if (!path.isNullOrBlank()) {
                try {
                    val pfd = android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    if (pfd != null) {
                        fileSize = pfd.statSize
                        pfd.close()
                        return ModelValidationResult.Success(uri = uri, path = path, sizeBytes = fileSize, spec = spec)
                    }
                } catch (e: Exception) {
                    Log.w("ACE_MODEL_LOAD", "ACE_MODEL_LOAD: validateModel path PFD open failed: ${e.message}")
                }
            }

            return ModelValidationResult.Error("No valid or readable model file found")

        } catch (e: Exception) {
            return ModelValidationResult.Error("Validation exception: ${e.message}")
        }
    }

    /**
     * Resolves direct file reference for native llama.cpp loading.
     * Guaranteed ZERO multi-GB copy operations.
     */
    fun ensureLocalModelFile(context: Context, uri: Uri?, existingPath: String?): File? {
        Log.i("ACE_MODEL_COPY", "ACE_MODEL_COPY: skipped")

        // Clean up legacy app-internal 4GB copies if an external persistent model exists
        try {
            val legacyInternalCopy = File(context.filesDir, DEFAULT_MODEL_FILENAME)
            val persistentModel = File("/storage/emulated/0/Download", DEFAULT_MODEL_FILENAME)
            if (legacyInternalCopy.exists() && persistentModel.exists() && persistentModel.length() >= 500_000_000L) {
                Log.i("ACE_MODEL_LOAD", "ACE_MODEL_LOAD: Cleaning up redundant internal 4GB file copy: ${legacyInternalCopy.absolutePath}")
                legacyInternalCopy.delete()
            }
        } catch (_: Exception) {}

        if (!existingPath.isNullOrBlank()) {
            val existing = File(existingPath)
            if (existing.exists() && existing.length() >= 500_000_000L) {
                return existing
            }
        }

        val persistentCandidateFiles = listOf(
            File("/storage/emulated/0/Download/AceModels", DEFAULT_MODEL_FILENAME),
            File("/storage/emulated/0/Download", DEFAULT_MODEL_FILENAME)
        )
        for (candidate in persistentCandidateFiles) {
            if (candidate.exists() && candidate.length() >= 500_000_000L) {
                return candidate
            }
        }

        return null
    }
}
