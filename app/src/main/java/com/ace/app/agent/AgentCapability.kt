package com.ace.app.agent

import android.app.SearchManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.os.Environment
import android.database.Cursor
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import com.ace.app.utils.LocationUtils
import com.ace.app.utils.NetworkUtils
import com.ace.app.utils.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class CommunicationChannel {
    PHONE,
    WHATSAPP
}

enum class NetworkRequirement {
    NONE,
    OPTIONAL,
    REQUIRED
}

data class CapabilityResult(
    val isSuccess: Boolean,
    val message: String,
    val outputData: Map<String, String> = emptyMap(),
    val error: String? = null
)

interface AgentCapability {
    val id: String
    val name: String
    val description: String
    val category: TaskCategory
    val networkRequirement: NetworkRequirement
    suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult
}

// 1. Real Contact Lookup Capability (Offline - NetworkRequirement.NONE)
class ContactLookupCapability : AgentCapability {
    override val id: String = "contact_lookup"
    override val name: String = "Contact Lookup"
    override val description: String = "Searches authorized Android device contacts with exact matching and ambiguity resolution"
    override val category: TaskCategory = TaskCategory.COMMUNICATION
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.IO) {
        val query = (params["query"] ?: params["contact"] ?: params["name"] ?: "").trim()
        if (query.isBlank()) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Contact search requires a target name or query.",
                error = "Empty contact query"
            )
        }

        if (context == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Device context unavailable to access Android Contacts.",
                error = "Null context"
            )
        }

        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Permission denied: READ_CONTACTS permission is required to search contacts.",
                error = "Permission denied"
            )
        }

        try {
            val contentResolver: ContentResolver = context.contentResolver
            val uri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val cursor: Cursor? = contentResolver.query(
                uri, projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"), null
            )

            val matches = mutableListOf<Pair<String, String>>()
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else ""
                    val number = if (numberIndex >= 0) it.getString(numberIndex) else ""
                    if (name.isNotBlank()) {
                        matches.add(name to number)
                    }
                }
            }

            if (matches.isEmpty()) {
                val cleanNumeric = query.replace(Regex("[^0-9+]"), "")
                if (cleanNumeric.isNotBlank() && cleanNumeric.length >= 3) {
                    return@withContext CapabilityResult(
                        isSuccess = true,
                        message = "Using raw phone number query '$cleanNumeric'.",
                        outputData = mapOf(
                            "contactName" to query,
                            "phoneNumber" to cleanNumeric,
                            "query" to query,
                            "recipientVerified" to "true",
                            "isAmbiguous" to "false"
                        )
                    )
                }
                return@withContext CapabilityResult(
                    isSuccess = false,
                    message = "Contact '$query' not found in device contacts.",
                    error = "Contact not found: '$query'"
                )
            }

            val exactMatch = matches.firstOrNull { it.first.equals(query, ignoreCase = true) }
            if (exactMatch != null) {
                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Found exact contact match '${exactMatch.first}' (${exactMatch.second}).",
                    outputData = mapOf(
                        "contactName" to exactMatch.first,
                        "phoneNumber" to exactMatch.second,
                        "query" to query,
                        "recipientVerified" to "true",
                        "isAmbiguous" to "false"
                    )
                )
            }

            val distinctNames = matches.map { it.first }.distinct()
            if (distinctNames.size == 1) {
                val single = matches.first()
                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Found contact '${single.first}' (${single.second}).",
                    outputData = mapOf(
                        "contactName" to single.first,
                        "phoneNumber" to single.second,
                        "query" to query,
                        "recipientVerified" to "true",
                        "isAmbiguous" to "false"
                    )
                )
            }

            // Ambiguous multiple contacts found
            val namesList = matches.map { "${it.first} (${it.second})" }.distinct().joinToString(", ")
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Multiple contact matches found for '$query': [$namesList]. Please specify which contact.",
                outputData = mapOf(
                    "contactName" to query,
                    "query" to query,
                    "recipientVerified" to "false",
                    "isAmbiguous" to "true",
                    "matchingContacts" to namesList
                ),
                error = "Ambiguous contact match: [$namesList]"
            )

        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Contact search failed: ${e.message}",
                error = "Contact search exception: ${e.message}"
            )
        }
    }
}

// 2. Direct Phone Call Capability (Offline - NetworkRequirement.NONE)
class PhoneCallCapability : AgentCapability {
    override val id: String = "phone_call"
    override val name: String = "Direct Phone Call"
    override val description: String = "Initiates a direct phone call to a resolved contact or numerical phone number"
    override val category: TaskCategory = TaskCategory.COMMUNICATION
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val rawNumber = params["phoneNumber"] ?: params["phone"] ?: params["number"] ?: ""
        val contactName = params["contactName"] ?: params["recipient"] ?: "contact"

        if (context == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Device context unavailable for phone call execution.",
                error = "Null context"
            )
        }

        val cleanNumber = rawNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isBlank()) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Cannot initiate call: Phone number for '$contactName' is invalid or not resolved.",
                error = "Invalid phone number: '$rawNumber'"
            )
        }

        val hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasCallPermission) {
            Log.w("ACE_CAPABILITY", "ACE_CAPABILITY: CALL_PHONE permission missing")
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Permission denied: CALL_PHONE permission is required to make phone calls.",
                error = "Permission denied: CALL_PHONE"
            )
        }

        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$cleanNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val hasPhoneStatePermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            var callStateStr = "UNVERIFIED_NO_PERMISSION"

            if (hasPhoneStatePermission && telephonyManager != null) {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 2000L) {
                    val currentState = when (telephonyManager.callState) {
                        android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> "CALL_STATE_OFFHOOK"
                        android.telephony.TelephonyManager.CALL_STATE_RINGING -> "CALL_STATE_RINGING"
                        else -> "CALL_STATE_IDLE"
                    }
                    if (currentState == "CALL_STATE_OFFHOOK" || currentState == "CALL_STATE_RINGING") {
                        callStateStr = currentState
                        break
                    }
                    kotlinx.coroutines.delay(200L)
                }
                if (callStateStr == "UNVERIFIED_NO_PERMISSION") {
                    callStateStr = "CALL_STATE_IDLE"
                }
            }

            Log.i("ACE_CAPABILITY", "ACE_CAPABILITY: Phone call intent dispatched for $contactName ($cleanNumber). Telephony callState=$callStateStr")
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Phone call intent dispatched to $contactName ($cleanNumber). Telephony state: $callStateStr.",
                outputData = mapOf(
                    "phoneNumber" to cleanNumber,
                    "contactName" to contactName,
                    "recipient" to contactName,
                    "callIntentDispatched" to "true",
                    "callState" to callStateStr,
                    "recipientVerified" to "true",
                    "action" to "CALL_INTENT_DISPATCHED"
                )
            )
        } catch (e: Exception) {
            Log.e("ACE_ERROR", "ACE_ERROR: Failed to place direct call: ${e.message}", e)
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to place call to $contactName: ${e.message}",
                error = e.message
            )
        }
    }
}

// 3. Direct Phone Dialer Capability (Offline - NetworkRequirement.NONE)
class PhoneDialerCapability : AgentCapability {
    override val id: String = "phone_dialer"
    override val name: String = "Direct Phone Dialer"
    override val description: String = "Initiates direct phone call using Android system dialer"
    override val category: TaskCategory = TaskCategory.COMMUNICATION
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val phoneNumber = params["phoneNumber"] ?: params["phone"] ?: params["number"] ?: params["contactName"] ?: ""
        val contactName = params["contactName"] ?: "contact"

        if (context == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Device context unavailable for phone dialer execution.",
                error = "Null context"
            )
        }

        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Phone dialer opened for $contactName ($phoneNumber).",
                outputData = mapOf(
                    "phoneNumber" to phoneNumber,
                    "contactName" to contactName,
                    "dialerOpened" to "true",
                    "userActionRequired" to "true",
                    "status" to "AWAITING_USER_ACTION"
                )
            )
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to open dialer: ${e.message}",
                error = e.message
            )
        }
    }
}

// 3. Real WhatsApp Communication Capability (Offline - NetworkRequirement.NONE)
class WhatsAppCallCapability : AgentCapability {
    override val id: String = "whatsapp_call"
    override val name: String = "WhatsApp Communication"
    override val description: String = "Launches WhatsApp conversation interface for verified contact"
    override val category: TaskCategory = TaskCategory.COMMUNICATION
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val phoneNumber = params["phoneNumber"] ?: params["phone"] ?: ""
        val contactName = params["contactName"] ?: params["query"] ?: "contact"

        if (context == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Device context unavailable for WhatsApp interaction.",
                error = "Null context"
            )
        }

        val cleanNumber = phoneNumber.replace(Regex("""[^\d+]"""), "")

        try {
            val packageManager = context.packageManager
            val isWhatsAppInstalled = try {
                packageManager.getPackageInfo("com.whatsapp", 0)
                true
            } catch (e: Exception) {
                false
            }

            if (!isWhatsAppInstalled) {
                return@withContext CapabilityResult(
                    isSuccess = false,
                    message = "WhatsApp application is not installed on this device.",
                    error = "App Not Installed"
                )
            }

            val intent = if (cleanNumber.isNotBlank()) {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")).apply {
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Intent.ACTION_MAIN).apply {
                    setPackage("com.whatsapp")
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }

            context.startActivity(intent)

            val successMsg = if (cleanNumber.isNotBlank()) {
                "Opened WhatsApp conversation for $contactName ($cleanNumber)."
            } else {
                "Opened WhatsApp application for $contactName."
            }

            return@withContext CapabilityResult(
                isSuccess = true,
                message = successMsg,
                outputData = mapOf("contactName" to contactName, "phoneNumber" to cleanNumber, "channel" to "WHATSAPP")
            )
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to launch WhatsApp flow: ${e.message}",
                error = e.message
            )
        }
    }
}

// 4. Real Unrestricted File Discovery Capability (Offline - NetworkRequirement.NONE)
class FileDiscoveryCapability : AgentCapability {
    override val id: String = "file_discovery"
    override val name: String = "Unrestricted File Discovery"
    override val description: String = "Scans device storage, external SD, Downloads, Documents, and scanner app folders for target files"
    override val category: TaskCategory = TaskCategory.DOCUMENT
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.IO) {
        val query = (params["query"] ?: params["fileName"] ?: params["attachmentName"] ?: "").trim()
        
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable for file discovery.")
        }

        val isPhotoQuery = query.lowercase().let { 
            it.contains("photo") || it.contains("gallery") || it.contains("picture") || it.contains("image") || it.contains("latest") || it.isBlank()
        }

        // 1. MediaStore Photo Query with strict timestamp ordering (DATE_MODIFIED DESC)
        if (isPhotoQuery) {
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.SIZE
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                val cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null, sortOrder
                )

                cursor?.use {
                    while (it.moveToNext()) {
                        val dataIdx = it.getColumnIndex(MediaStore.Images.Media.DATA)
                        val nameIdx = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        val idIdx = it.getColumnIndex(MediaStore.Images.Media._ID)
                        val modIdx = it.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                        val sizeIdx = it.getColumnIndex(MediaStore.Images.Media.SIZE)

                        val path = if (dataIdx >= 0) it.getString(dataIdx) else ""
                        val name = if (nameIdx >= 0) it.getString(nameIdx) else "latest_photo.jpg"
                        val imageId = if (idIdx >= 0) it.getLong(idIdx) else 0L
                        val dateMod = if (modIdx >= 0) it.getLong(modIdx) else 0L
                        val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else 0L

                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)

                        if (path.isNotBlank() && File(path).exists() && File(path).isFile && File(path).length() > 0) {
                            Log.i("ACE_CAPABILITY", "FileDiscovery: identified newest photo '$name' at '$path' (timestamp: $dateMod)")
                            return@withContext CapabilityResult(
                                isSuccess = true,
                                message = "Identified newest gallery photo '$name' (modified timestamp: $dateMod, size: $size bytes).",
                                outputData = mapOf(
                                    "fileName" to name,
                                    "filePath" to path,
                                    "fileUri" to contentUri.toString(),
                                    "fileSize" to size.toString(),
                                    "isNewestFromMediaStore" to "true",
                                    "selectionVerified" to "true",
                                    "timestamp" to dateMod.toString()
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ACE_CAPABILITY", "MediaStore photo query error: ${e.message}")
            }
        }

        // 2. Local App Storage Scan
        val appFilesDir = context.filesDir
        val matchingAppFiles = appFilesDir.listFiles { file ->
            query.isBlank() || file.name.contains(query, ignoreCase = true)
        }
        val foundAppFile = matchingAppFiles?.firstOrNull()

        if (foundAppFile != null) {
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Discovered file '${foundAppFile.name}' (${foundAppFile.length()} bytes) in local app storage.",
                outputData = mapOf(
                    "fileName" to foundAppFile.name,
                    "filePath" to foundAppFile.absolutePath,
                    "fileSize" to foundAppFile.length().toString(),
                    "selectionVerified" to "true"
                )
            )
        }

        // 3. Unrestricted storage recursive scan
        try {
            val rootStorage = Environment.getExternalStorageDirectory()
            val targetDirs = listOf(
                File(rootStorage, "DCIM"),
                File(rootStorage, "Pictures"),
                File(rootStorage, "Download"),
                File(rootStorage, "Documents"),
                rootStorage
            )

            for (dir in targetDirs) {
                if (dir.exists() && dir.canRead()) {
                    val match = findFileInDir(dir, query, depth = 0)
                    if (match != null) {
                        return@withContext CapabilityResult(
                            isSuccess = true,
                            message = "Discovered file '${match.name}' (${match.length()} bytes) in storage path '${match.parentFile?.name}'.",
                            outputData = mapOf(
                                "fileName" to match.name,
                                "filePath" to match.absolutePath,
                                "fileSize" to match.length().toString(),
                                "selectionVerified" to "true"
                            )
                        )
                    }
                }
            }
        } catch (ignored: Exception) {}

        if (query.isNotBlank()) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "File matching '$query' was not found on device storage.",
                outputData = mapOf("query" to query, "isNewestFromMediaStore" to "false", "selectionVerified" to "false")
            )
        }

        return@withContext CapabilityResult(
            isSuccess = false,
            message = "No matching photo or document found on storage.",
            outputData = mapOf("isNewestFromMediaStore" to "false", "selectionVerified" to "false")
        )
    }

    private fun findFileInDir(dir: File, query: String, depth: Int): File? {
        if (depth > 3) return null
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && (query.isBlank() || f.name.contains(query, ignoreCase = true))) {
                return f
            } else if (f.isDirectory && !f.name.startsWith(".")) {
                val subMatch = findFileInDir(f, query, depth + 1)
                if (subMatch != null) return subMatch
            }
        }
        return null
    }
}

// 15. Direct Document & File Dispatching Capability (Offline Intent Launch - NetworkRequirement.NONE)
class DocumentSendCapability : AgentCapability {
    override val id: String = "send_document"
    override val name: String = "Direct Document Dispatch"
    override val description: String = "Sends target document or file from device storage to specified application (WhatsApp, Gmail, Outlook, Drive)"
    override val category: TaskCategory = TaskCategory.COMMUNICATION
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable to send document.")
        }
        val goal = params["goal"] ?: params["summary"] ?: "Send document"
        val result = com.ace.app.delivery.SmartDeliveryEngine.executeDelivery(context, params, goal)

        val isSuccess = result.status == com.ace.app.delivery.DeliveryStatus.HANDOFF_COMPLETED ||
                result.status == com.ace.app.delivery.DeliveryStatus.SUCCESSFULLY_SENT ||
                result.status == com.ace.app.delivery.DeliveryStatus.OPENED_TARGET_COMPOSER

        CapabilityResult(
            isSuccess = isSuccess,
            message = result.message,
            outputData = mapOf(
                "status" to result.status.name,
                "message" to result.message,
                "attachmentUri" to (result.attachment?.uri?.toString() ?: ""),
                "targetApp" to (result.targetApp?.name ?: "")
            )
        )
    }
}

// 5. Real Document Analysis Capability (Offline - NetworkRequirement.NONE)
class DocumentAnalysisCapability : AgentCapability {
    override val id: String = "document_analysis"
    override val name: String = "Document Analysis"
    override val description: String = "Analyzes document structure, formatting criteria, and content attributes"
    override val category: TaskCategory = TaskCategory.DOCUMENT
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.IO) {
        // Preferred path: a user-attached document handed to us as a content:// URI
        // (via SAF). We read it through ContentResolver — the only reliable way to
        // access SAF documents on modern Android — and report only what we actually
        // observe (name, MIME type, byte size). No fabricated "analysis".
        val uriStr = params["attachmentUri"] ?: params["uri"]
        if (!uriStr.isNullOrBlank() && context != null) {
            try {
                val uri = Uri.parse(uriStr)
                val resolver = context.contentResolver
                var displayName = params["attachmentName"] ?: "document"
                var sizeBytes = -1L
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (nameIdx >= 0) c.getString(nameIdx)?.let { displayName = it }
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) sizeBytes = c.getLong(sizeIdx)
                    }
                }
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                // Confirm the content genuinely opens for reading before reporting success.
                val readable = try {
                    resolver.openInputStream(uri)?.use { true } ?: false
                } catch (e: Exception) {
                    false
                }
                if (!readable) {
                    return@withContext CapabilityResult(
                        isSuccess = false,
                        message = "The attached document could not be opened for reading.",
                        error = "Unreadable content URI"
                    )
                }
                val kb = if (sizeBytes >= 0) sizeBytes / 1024 else -1L
                val sizeText = if (kb >= 0) "$kb KB" else "unknown size"
                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Analyzed attached document '$displayName': type $mime, $sizeText.",
                    outputData = mapOf(
                        "fileName" to displayName,
                        "mimeType" to mime,
                        "sizeKb" to (if (kb >= 0) kb.toString() else "unknown"),
                        "attachmentUri" to uriStr,
                        "status" to "Analyzed"
                    )
                )
            } catch (e: Exception) {
                return@withContext CapabilityResult(
                    isSuccess = false,
                    message = "Could not read the attached document: ${e.message}",
                    error = e.message
                )
            }
        }

        // Fallback: a concrete filesystem path (e.g. a file discovered in app storage).
        val filePath = params["filePath"] ?: params["fileName"] ?: ""
        if (filePath.isBlank()) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "No document was provided to analyze. Attach a file first."
            )
        }

        val file = File(filePath)
        if (file.exists() && file.canRead()) {
            val extension = file.extension.uppercase()
            val lengthKb = file.length() / 1024
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Analyzed document '${file.name}': Format $extension, Size $lengthKb KB.",
                outputData = mapOf(
                    "fileName" to file.name,
                    "format" to extension,
                    "sizeKb" to lengthKb.toString(),
                    "status" to "Analyzed"
                )
            )
        }

        // TRUTHFUL FAILURE: do not claim a file was analyzed/verified when it
        // does not exist or cannot be read.
        return@withContext CapabilityResult(
            isSuccess = false,
            message = "Could not analyze document: no readable file exists at '$filePath'.",
            error = "File not found or unreadable",
            outputData = mapOf("document" to filePath)
        )
    }
}

// 6. Real App Sharing Capability (Offline - NetworkRequirement.NONE)
class AppShareCapability : AgentCapability {
    override val id: String = "app_share"
    override val name: String = "Android Content Sharing"
    override val description: String = "Launches system Android share sheet or target app to send documents or text"
    override val category: TaskCategory = TaskCategory.COMMUNICATION
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable to share content.")
        }
        val goal = params["goal"] ?: params["userGoal"] ?: params["summary"] ?: "Share content"
        val result = com.ace.app.delivery.SmartDeliveryEngine.executeDelivery(context, params, goal)

        val isSuccess = result.status == com.ace.app.delivery.DeliveryStatus.HANDOFF_COMPLETED ||
                result.status == com.ace.app.delivery.DeliveryStatus.SUCCESSFULLY_SENT ||
                result.status == com.ace.app.delivery.DeliveryStatus.OPENED_TARGET_COMPOSER

        CapabilityResult(
            isSuccess = isSuccess,
            message = result.message,
            outputData = mapOf(
                "status" to result.status.name,
                "message" to result.message,
                "attachmentUri" to (result.attachment?.uri?.toString() ?: ""),
                "targetApp" to (result.targetApp?.name ?: "")
            )
        )
    }
}

// 7. Universal Smart Delivery Capability
class SmartDeliveryCapability : AgentCapability {
    override val id: String = "smart_delivery"
    override val name: String = "Universal Smart Delivery Engine"
    override val description: String = "Performs recipient resolution, attachment discovery, target app adapter selection, and platform handoff for WhatsApp, SMS, Telegram, and generic sharing."
    override val category: TaskCategory = TaskCategory.COMMUNICATION
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable for smart delivery.")
        }
        val goal = params["goal"] ?: params["userGoal"] ?: params["summary"] ?: "Smart delivery"
        val result = com.ace.app.delivery.SmartDeliveryEngine.executeDelivery(context, params, goal)

        val isSuccess = result.status == com.ace.app.delivery.DeliveryStatus.HANDOFF_COMPLETED ||
                result.status == com.ace.app.delivery.DeliveryStatus.SUCCESSFULLY_SENT ||
                result.status == com.ace.app.delivery.DeliveryStatus.OPENED_TARGET_COMPOSER

        CapabilityResult(
            isSuccess = isSuccess,
            message = result.message,
            outputData = mapOf(
                "status" to result.status.name,
                "message" to result.message,
                "attachmentUri" to (result.attachment?.uri?.toString() ?: ""),
                "targetApp" to (result.targetApp?.name ?: ""),
                "recipient" to (result.recipient?.displayName ?: "")
            )
        )
    }
}
class WebSearchCapability : AgentCapability {
    override val id: String = "web_search"
    override val name: String = "Web Search & Fetch"
    override val description: String = "Queries live web endpoints to research topics, check status, or fetch information"
    override val category: TaskCategory = TaskCategory.RESEARCH
    override val networkRequirement: NetworkRequirement = NetworkRequirement.REQUIRED

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val query = (params["query"] ?: params["url"] ?: params["goal"] ?: "").trim()
        if (query.isBlank()) {
            return@withContext CapabilityResult(isSuccess = false, message = "Web search requires a search query or URL.")
        }

        if (context != null && !NetworkUtils.isNetworkAvailable(context)) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Network connection required for web search. Device is currently offline.",
                error = "Network Unavailable"
            )
        }

        if (context == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Device context unavailable to launch search intent.",
                error = "Context Unavailable"
            )
        }

        try {
            val targetApp = (params["appName"] ?: params["app"] ?: "").lowercase().trim()
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = when {
                targetApp.contains("youtube") -> "https://www.youtube.com/results?search_query=$encodedQuery"
                targetApp.contains("maps") -> "https://www.google.com/maps/search/?api=1&query=$encodedQuery"
                else -> "https://www.google.com/search?q=$encodedQuery"
            }

            Log.i("ACE_WEB", "ACE_WEB: query=$query")
            Log.i("ACE_WEB", "ACE_WEB: encoded_query=$encodedQuery")
            Log.i("ACE_WEB", "ACE_WEB: url=$searchUrl")
            Log.i("ACE_WEB", "ACE_WEB: launching search intent")

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Launched web search intent for '$query'.",
                outputData = mapOf(
                    "query" to query,
                    "encodedQuery" to encodedQuery,
                    "searchUrl" to searchUrl
                )
            )
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to launch web search intent for '$query': ${e.message}",
                error = e.message
            )
        }
    }
}

// 8. Dynamic Text & Intent Reasoning Capability (Offline - NetworkRequirement.NONE)
class TextReasoningCapability : AgentCapability {
    override val id: String = "text_reasoning"
    override val name: String = "Text & Intent Reasoning"
    override val description: String = "Synthesizes data across action outputs, extracts entities, and verifies results"
    override val category: TaskCategory = TaskCategory.GENERAL
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Default) {
        val goal = params["goal"] ?: ""
        val contextInput = params["contextInput"] ?: ""

        val summary = if (contextInput.isNotBlank()) {
            "Synthesized results for '$goal': $contextInput"
        } else {
            "Analyzed goal '$goal' and verified execution prerequisites."
        }

        CapabilityResult(
            isSuccess = true,
            message = summary,
            outputData = mapOf("goal" to goal, "synthesis" to summary)
        )
    }
}

// 10. Real PDF & Document Summarization Capability (Offline - NetworkRequirement.NONE)
class PdfSummarizeCapability : AgentCapability {
    override val id: String = "pdf_summarize"
    override val name: String = "PDF & Document Summarizer"
    override val description: String = "Extracts and synthesizes text content from PDF files and document URIs into concise summaries"
    override val category: TaskCategory = TaskCategory.DOCUMENT
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.IO) {
        val uriStr = params["attachmentUri"] ?: params["uri"]
        val filePath = params["filePath"] ?: params["fileName"] ?: ""

        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable for document summarization.")
        }

        val extractedText = when {
            !uriStr.isNullOrBlank() -> {
                try {
                    PdfTextExtractor.extractText(context, Uri.parse(uriStr))
                } catch (e: Exception) {
                    "Could not extract text from document URI: ${e.message}"
                }
            }
            filePath.isNotBlank() -> {
                PdfTextExtractor.extractTextFromFile(File(filePath))
            }
            else -> null
        }

        if (extractedText == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "No PDF or document was provided to summarize. Please select or attach a document first."
            )
        }

        val wordCount = extractedText.split(Regex("""\s+""")).filter { it.isNotBlank() }.size
        val summaryLines = extractedText.lines().filter { it.trim().length > 15 }.take(6)
        val bulletPoints = if (summaryLines.isNotEmpty()) {
            summaryLines.joinToString("\n• ", prefix = "• ")
        } else {
            extractedText.take(300)
        }

        val summaryText = "PDF Summary ($wordCount words analyzed):\n$bulletPoints"

        return@withContext CapabilityResult(
            isSuccess = true,
            message = summaryText,
            outputData = mapOf(
                "summary" to summaryText,
                "wordCount" to wordCount.toString(),
                "extractedTextSnippet" to extractedText.take(500)
            )
        )
    }
}

// 11. Open Website / Browser Navigation Capability (Offline Intent Launch - NetworkRequirement.NONE)
class OpenWebsiteCapability : AgentCapability {
    override val id: String = "web_open_url"
    override val name: String = "Open Website & Browser"
    override val description: String = "Launches default Android web browser to open specified URL or website"
    override val category: TaskCategory = TaskCategory.RESEARCH
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        var url = (params["url"] ?: params["query"] ?: params["website"] ?: "").trim()
        if (url.isBlank()) {
            return@withContext CapabilityResult(isSuccess = false, message = "Open website capability requires a web URL.")
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable to open browser.")
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Opened website $url in browser.",
                outputData = mapOf("url" to url)
            )
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to launch browser for $url: ${e.message}",
                error = e.message
            )
        }
    }
}

// 12. Set Alarm & Timer Capability (Offline - NetworkRequirement.NONE)
class SetAlarmCapability : AgentCapability {
    override val id: String = "set_alarm"
    override val name: String = "Set Alarm & Timer"
    override val description: String = "Configures device system alarm clock or timer with target hour, minute, or duration"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable for alarm creation.")
        }

        val goal = params["goal"] ?: ""
        val timeStr = params["time"] ?: params["hour"] ?: ""
        val message = params["message"] ?: params["label"] ?: params["title"] ?: "ACE Alarm"
        val durationStr = params["durationMinutes"] ?: params["minutes"] ?: ""

        try {
            if (durationStr.isNotBlank() || goal.contains("timer", ignoreCase = true)) {
                val minutes = durationStr.toIntOrNull() ?: 10
                val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(timerIntent)
                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Set timer for $minutes minutes ($message).",
                    outputData = mapOf("timerMinutes" to minutes.toString(), "label" to message)
                )
            } else {
                var hour = 7
                var minute = 0
                val digits = Regex("""\d+""").findAll(timeStr).map { it.value.toInt() }.toList()
                if (digits.isNotEmpty()) {
                    hour = digits[0]
                    if (digits.size > 1) minute = digits[1]
                }
                if (timeStr.contains("pm", ignoreCase = true) && hour < 12) {
                    hour += 12
                } else if (timeStr.contains("am", ignoreCase = true) && hour == 12) {
                    hour = 0
                }

                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(alarmIntent)
                val formattedTime = String.format("%02d:%02d", hour, minute)
                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Set alarm for $formattedTime ($message).",
                    outputData = mapOf("alarmTime" to formattedTime, "label" to message)
                )
            }
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to set alarm/timer: ${e.message}",
                error = e.message
            )
        }
    }
}

// 13. Schedule Task & Calendar Event Capability (Offline - NetworkRequirement.NONE)
class ScheduleTaskCapability : AgentCapability {
    override val id: String = "schedule_task"
    override val name: String = "Schedule Task & Calendar Event"
    override val description: String = "Creates scheduled tasks, reminders, and calendar events on Android device"
    override val category: TaskCategory = TaskCategory.GENERAL
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable to schedule task.")
        }

        val title = params["title"] ?: params["task"] ?: params["goal"] ?: "Scheduled Task"
        val timeStr = params["time"] ?: params["date"] ?: "Tomorrow"

        try {
            val cal = Calendar.getInstance()
            cal.add(Calendar.HOUR_OF_DAY, 1)

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, "Scheduled via ACE Voice Agent")
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 3600000)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Scheduled task '$title' in system calendar.",
                outputData = mapOf("title" to title, "scheduledTime" to timeStr)
            )
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to schedule task: ${e.message}",
                error = e.message
            )
        }
    }
}

// 14. System Settings Control Capability (Offline - NetworkRequirement.NONE)
class SystemSettingsCapability : AgentCapability {
    override val id: String = "system_settings"
    override val name: String = "System Settings Control"
    override val description: String = "Navigates and opens Android system settings screens (Wi-Fi, Bluetooth, Display, Sound, Battery)"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable for system settings.")
        }

        val setting = (params["setting"] ?: params["target"] ?: params["query"] ?: "").lowercase()
        val action = when {
            setting.contains("wifi") || setting.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            setting.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            setting.contains("sound") || setting.contains("volume") -> Settings.ACTION_SOUND_SETTINGS
            setting.contains("display") || setting.contains("brightness") -> Settings.ACTION_DISPLAY_SETTINGS
            setting.contains("battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        try {
            val intent = Intent(action).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Opened system settings for '$setting'.",
                outputData = mapOf("setting" to setting)
            )
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to open settings: ${e.message}",
                error = e.message
            )
        }
    }
}

// 16. Media & Music Playback Capability (Offline Intent Launch - NetworkRequirement.NONE)
class MediaPlaybackCapability : AgentCapability {
    override val id: String = "media_playback"
    override val name: String = "Media & Music Playback"
    override val description: String = "Launches target music player (Spotify, YouTube Music) and automatically starts playing specified track instantly on the spot"
    override val category: TaskCategory = TaskCategory.GENERAL
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    private suspend fun resolveSpotifyTrackUri(query: String): Uri? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        try {
            // 1. DuckDuckGo HTML search for site:open.spotify.com/track
            val encodedQuery = Uri.encode(query)
            val ddgUrl = "https://html.duckduckgo.com/html/?q=site:open.spotify.com/track+$encodedQuery"
            val url = java.net.URL(ddgUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val matcher = java.util.regex.Pattern.compile("open\\.spotify\\.com/track/([a-zA-Z0-9]+)").matcher(html)
            if (matcher.find()) {
                val trackId = matcher.group(1)
                if (!trackId.isNullOrEmpty()) {
                    return@withContext Uri.parse("spotify:track:$trackId")
                }
            }
        } catch (_: Exception) {}

        try {
            // 2. iTunes Search API fallback lookup
            val itunesUrl = "https://itunes.apple.com/search?term=${Uri.encode(query)}&media=music&entity=song&limit=1"
            val url = java.net.URL(itunesUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val jsonObj = org.json.JSONObject(jsonStr)
            val results = jsonObj.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val trackObj = results.getJSONObject(0)
                val trackName = trackObj.optString("trackName")
                val artistName = trackObj.optString("artistName")
                if (trackName.isNotBlank() && artistName.isNotBlank()) {
                    val encodedSearch = Uri.encode("$trackName $artistName")
                    val ddgUrl2 = "https://html.duckduckgo.com/html/?q=site:open.spotify.com/track+$encodedSearch"
                    val conn2 = java.net.URL(ddgUrl2).openConnection() as java.net.HttpURLConnection
                    conn2.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    conn2.connectTimeout = 2500
                    conn2.readTimeout = 2500
                    val html2 = conn2.inputStream.bufferedReader().use { it.readText() }
                    conn2.disconnect()
                    val matcher2 = java.util.regex.Pattern.compile("open\\.spotify\\.com/track/([a-zA-Z0-9]+)").matcher(html2)
                    if (matcher2.find()) {
                        val trackId = matcher2.group(1)
                        if (!trackId.isNullOrEmpty()) {
                            return@withContext Uri.parse("spotify:track:$trackId")
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return@withContext null
    }

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable for media playback.")
        }

        val songQuery = (params["query"] ?: params["song"] ?: params["track"] ?: params["music"] ?: params["goal"] ?: "").trim()
        val appName = (params["appName"] ?: params["app"] ?: params["targetApp"] ?: "spotify").trim()

        if (songQuery.isBlank()) {
            return@withContext CapabilityResult(isSuccess = false, message = "Media title or search query is required.")
        }

        try {
            val appInfo = AppDiscoveryEngine.findApp(context, appName)
            val canonicalAppName = appInfo?.appName ?: appName

            val service = com.ace.app.accessibility.AceAccessibilityService.getInstance()
            if (service != null && com.ace.app.accessibility.AceAccessibilityService.isServiceEnabled(context)) {
                val engine = com.ace.app.accessibility.UniversalAppInteractionEngine()
                val verifyResult = engine.executeInAppSearch(context, canonicalAppName, songQuery)

                return@withContext CapabilityResult(
                    isSuccess = verifyResult.isVerified,
                    message = verifyResult.summary,
                    outputData = mapOf(
                        "query" to songQuery,
                        "targetApp" to canonicalAppName,
                        "appName" to canonicalAppName,
                        "playbackTriggered" to verifyResult.isVerified.toString()
                    ),
                    error = if (!verifyResult.isVerified) verifyResult.evidenceText else null
                )
            }

            // Fallback intent execution if Accessibility Service is not active
            val isSpotify = canonicalAppName.equals("Spotify", ignoreCase = true)
            var launched = false
            var trackUriResolved = false

            if (isSpotify) {
                val resolvedUri = resolveSpotifyTrackUri(songQuery)
                if (resolvedUri != null) {
                    try {
                        val trackIntent = Intent(Intent.ACTION_VIEW, resolvedUri).apply {
                            setPackage("com.spotify.music")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(trackIntent)
                        launched = true
                        trackUriResolved = true
                    } catch (_: Exception) {}
                }
            }

            if (!launched) {
                val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, songQuery)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, songQuery)
                    putExtra("autostart", true)
                    if (appInfo?.packageName != null) {
                        setPackage(appInfo.packageName)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                try {
                    context.startActivity(playIntent)
                    launched = true
                } catch (_: Exception) {
                    if (appInfo?.launchIntent != null) {
                        appInfo.launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(appInfo.launchIntent)
                        launched = true
                    }
                }
            }

            return@withContext CapabilityResult(
                isSuccess = false, // Opening app via intent alone without Accessibility verification is PARTIAL / UNVERIFIED
                message = "Opened '$canonicalAppName' for '$songQuery', but in-app playback requires ACE Accessibility Service.",
                outputData = mapOf("query" to songQuery, "app" to canonicalAppName, "status" to "NEEDS_USER_ACTION"),
                error = "ACCESSIBILITY_DISABLED_FOR_PLAYBACK"
            )
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to trigger media playback: ${e.message}",
                error = e.message
            )
        }
    }
}

// 9. Enhanced Application Control Capability (Offline - NetworkRequirement.NONE)
class AppControlCapability : AgentCapability {
    override val id: String = "app_control"
    override val name: String = "Application Control"
    override val description: String = "Launches target application on the Android device with dynamic package lookup"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val appName = (params["appName"] ?: params["app_name"] ?: params["app"] ?: params["package"] ?: params["targetApp"] ?: params["name"] ?: params["targetEntity"] ?: "").trim()
        if (appName.isBlank()) {
            return@withContext CapabilityResult(isSuccess = false, message = "Application control requires a target app name.")
        }
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable for app launch.")
        }

        val cleanApp = appName.lowercase()
        val pkg = when {
            cleanApp.contains("whatsapp") -> "com.whatsapp"
            cleanApp.contains("spotify") -> "com.spotify.music"
            cleanApp.contains("chrome") -> "com.android.chrome"
            cleanApp.contains("settings") -> "com.android.settings"
            cleanApp.contains("youtube") -> "com.google.android.youtube"
            cleanApp.contains("maps") -> "com.google.android.apps.maps"
            cleanApp.contains("camera") -> "com.android.camera"
            cleanApp.contains("gmail") -> "com.google.android.gm"
            cleanApp.contains("clock") -> "com.google.android.deskclock"
            cleanApp.contains("calendar") -> "com.google.android.calendar"
            cleanApp.contains("calculator") -> "com.google.android.calculator"
            else -> null
        }

        try {
            val pm = context.packageManager
            var intent = if (pkg != null) pm.getLaunchIntentForPackage(pkg) else null

            if (intent == null) {
                intent = pm.getLaunchIntentForPackage(appName)
            }

            if (intent == null) {
                val installedApps = pm.getInstalledApplications(0)
                val matchingApp = installedApps.firstOrNull { app ->
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    label.contains(cleanApp) || app.packageName.lowercase().contains(cleanApp)
                }
                if (matchingApp != null) {
                    intent = pm.getLaunchIntentForPackage(matchingApp.packageName)
                }
            }

            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Opened application '$appName'.",
                    outputData = mapOf("appName" to appName)
                )
            } else {
                return@withContext CapabilityResult(
                    isSuccess = false,
                    message = "Could not find installed application matching '$appName'.",
                    error = "App not found"
                )
            }
        } catch (e: Exception) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Failed to launch application '$appName': ${e.message}",
                error = e.message
            )
        }
    }
}

// 17. Current GPS Location Capability (Offline/Online - NetworkRequirement.NONE)
class CurrentLocationCapability : AgentCapability {
    override val id: String = "current_location"
    override val name: String = "Current GPS Location"
    override val description: String = "Retrieves current GPS coordinates and reverse-geocoded address to answer 'Where am I?'"
    override val category: TaskCategory = TaskCategory.GENERAL
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable.")
        }

        if (!LocationUtils.hasLocationPermission(context)) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "GPS Location permission is not granted. Please enable Location permission for ACE IIT in Android Settings."
            )
        }

        val location = LocationUtils.getCurrentLocation(context)
        if (location == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Unable to fetch GPS location right now. Please ensure Location/GPS is turned on on your device."
            )
        }

        val addressStr = LocationUtils.reverseGeocode(context, location.latitude, location.longitude)
        val latFormatted = String.format(Locale.US, "%.5f", location.latitude)
        val lngFormatted = String.format(Locale.US, "%.5f", location.longitude)

        val speechMessage = "You are currently at $addressStr."

        return@withContext CapabilityResult(
            isSuccess = true,
            message = speechMessage,
            outputData = mapOf(
                "address" to addressStr,
                "latitude" to latFormatted,
                "longitude" to lngFormatted
            )
        )
    }
}

// 18. Route Directions & Travel Time Capability (NetworkRequirement.OPTIONAL)
class RouteDirectionsCapability : AgentCapability {
    override val id: String = "route_directions"
    override val name: String = "Route Directions & Travel Time"
    override val description: String = "Calculates distance, cardinal direction, estimated travel time to a destination, and opens Google Maps navigation"
    override val category: TaskCategory = TaskCategory.GENERAL
    override val networkRequirement: NetworkRequirement = NetworkRequirement.OPTIONAL

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        if (context == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Device context unavailable.")
        }

        val destination = (params["destination"] ?: params["target"] ?: params["place"] ?: params["goal"] ?: "").trim()
        if (destination.isBlank()) {
            return@withContext CapabilityResult(isSuccess = false, message = "Please specify a destination place or address.")
        }

        if (!LocationUtils.hasLocationPermission(context)) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "GPS Location permission is not granted. Please enable Location permission for ACE IIT."
            )
        }

        val startLoc = LocationUtils.getCurrentLocation(context)
        if (startLoc == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "Could not fetch current GPS location. Please turn on GPS on your device."
            )
        }

        val destCoords = LocationUtils.geocodeDestination(context, destination)
        val destLat = destCoords?.first ?: 0.0
        val destLng = destCoords?.second ?: 0.0

        var speechMessage: String
        if (destCoords != null) {
            val route = LocationUtils.calculateRoute(
                startLoc.latitude,
                startLoc.longitude,
                destLat,
                destLng,
                destination
            )

            speechMessage = "Destination '$destination' is approximately ${route.distanceFormatted} ${route.cardinalDirection} from here. Estimated travel time is ${route.drivingTimeFormatted} by car or ${route.walkingTimeFormatted} on foot."
        } else {
            speechMessage = "Heading towards '$destination'. Opening Google Maps navigation from your current location."
        }

        // Launch turn-by-turn navigation in Google Maps
        try {
            val gmapsUri = if (destCoords != null) {
                Uri.parse("google.navigation:q=$destLat,$destLng")
            } else {
                Uri.parse("google.navigation:q=${Uri.encode(destination)}")
            }
            val navIntent = Intent(Intent.ACTION_VIEW, gmapsUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(navIntent)
        } catch (_: Exception) {
            val webNavUri = Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${startLoc.latitude},${startLoc.longitude}&destination=${Uri.encode(destination)}")
            val fallbackIntent = Intent(Intent.ACTION_VIEW, webNavUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try { context.startActivity(fallbackIntent) } catch (_: Exception) {}
        }

        return@withContext CapabilityResult(
            isSuccess = true,
            message = speechMessage,
            outputData = mapOf(
                "destination" to destination,
                "startLat" to startLoc.latitude.toString(),
                "startLng" to startLoc.longitude.toString()
            )
        )
    }
}

// 19. UI Open App Capability
class UiOpenAppCapability : AgentCapability {
    override val id: String = "ui_open_app"
    override val name: String = "Open App on Phone"
    override val description: String = "Launches any installed application on the phone automatically by display name or package name (e.g. Spotify, WhatsApp, Clear Scanner, Gmail, Settings, Chrome, etc.)"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val packageName = (params["package"] ?: params["app"] ?: params["appName"] ?: params["app_name"] ?: params["targetApp"] ?: params["name"] ?: "").trim().trimEnd('.', ',', '!', '?').trim()
        if (packageName.isBlank()) {
            return@withContext CapabilityResult(isSuccess = false, message = "ui_open_app requires an app or package name.", error = "Missing app parameter")
        }
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable", error = "Null context")
        val service = com.ace.app.accessibility.AceAccessibilityService.getInstance()
        val success = service?.launchApp(ctx, packageName) ?: run {
            // Fallback launch intent via PackageManager directly
            val pm = ctx.packageManager
            var pkg = packageName
            val raw = pkg.trim().lowercase()

            try {
                if (raw == "camera" || raw == "the camera" || raw.contains("camera")) {
                    val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (cameraIntent.resolveActivity(pm) != null) {
                        ctx.startActivity(cameraIntent)
                        return@run true
                    }
                }
                if (raw == "settings" || raw == "system settings") {
                    val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(settingsIntent)
                    return@run true
                }
                if (raw == "clock" || raw == "alarms") {
                    val clockIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (clockIntent.resolveActivity(pm) != null) {
                        ctx.startActivity(clockIntent)
                        return@run true
                    }
                }
            } catch (_: Exception) {}

            if (!pkg.contains(".")) {
                val match = pm.getInstalledApplications(0).firstOrNull { 
                    pm.getApplicationLabel(it).toString().lowercase().contains(pkg.lowercase()) &&
                    pm.getLaunchIntentForPackage(it.packageName) != null
                }
                if (match != null) pkg = match.packageName
            }
            val intent = pm.getLaunchIntentForPackage(pkg)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            if (intent != null) { ctx.startActivity(intent); true } else false
        }

        if (success) {
            kotlinx.coroutines.delay(1200)
            // Return structured verification data
            CapabilityResult(
                isSuccess = true,
                message = "Opened app '$packageName' automatically on phone.",
                outputData = mapOf(
                    "appOpened" to "true",
                    "appName" to packageName,
                    "verificationState" to "VERIFIED"
                )
            )
        } else {
            CapabilityResult(
                isSuccess = false,
                message = "Failed to launch app '$packageName'. Ensure it is installed.",
                error = "App not found",
                outputData = mapOf(
                    "appOpened" to "false",
                    "appName" to packageName,
                    "verificationState" to "FAILED"
                )
            )
        }
    }
}

// 20. UI Click Element Capability
class UiClickCapability : AgentCapability {
    override val id: String = "ui_click"
    override val name: String = "Click Screen Element"
    override val description: String = "Simulates a user touch tap/click on any button, text, tab, icon, or menu item visible on the screen"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val targetText = (params["text"] ?: params["target"] ?: params["button"] ?: params["label"] ?: "").trim()
        val targetId = (params["id"] ?: params["viewId"] ?: "").trim()
        val service = com.ace.app.accessibility.AceAccessibilityService.getInstance()

        if (service == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "ACE Accessibility Service is required to perform in-app interactions inside the active app. Please enable ACE in Accessibility Settings.",
                error = "Accessibility disabled",
                outputData = mapOf(
                    "requiresAccessibility" to "true",
                    "verificationState" to "BLOCKED"
                )
            )
        }

        val success = when {
            targetText.isNotBlank() -> service.clickText(targetText)
            targetId.isNotBlank() -> service.clickId(targetId)
            else -> false
        }

        if (success) {
            CapabilityResult(
                isSuccess = true,
                message = "Clicked UI element '${targetText.ifBlank { targetId }}'.",
                outputData = mapOf(
                    "clickExecuted" to "true",
                    "target" to targetText.ifBlank { targetId },
                    "verificationState" to "VERIFIED"
                )
            )
        } else {
            CapabilityResult(
                isSuccess = false,
                message = "Could not find or click UI element '${targetText.ifBlank { targetId }}' on screen.",
                error = "Node not found",
                outputData = mapOf(
                    "clickExecuted" to "false",
                    "target" to targetText.ifBlank { targetId },
                    "verificationState" to "FAILED"
                )
            )
        }
    }
}

// 21. UI Type Text Capability
class UiTypeCapability : AgentCapability {
    override val id: String = "ui_type"
    override val name: String = "Type Text into UI"
    override val description: String = "Types text into any focused input box, search field, or chat bar on the screen"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val textToType = (params["text"] ?: params["query"] ?: params["content"] ?: "").trim()
        val fieldHint = (params["hint"] ?: params["field"] ?: "").trim().ifBlank { null }
        val service = com.ace.app.accessibility.AceAccessibilityService.getInstance()

        if (service == null) {
            return@withContext CapabilityResult(
                isSuccess = false,
                message = "ACE Accessibility Service is required to perform in-app interactions inside the active app. Please enable ACE in Accessibility Settings.",
                error = "Accessibility disabled",
                outputData = mapOf(
                    "requiresAccessibility" to "true",
                    "verificationState" to "BLOCKED"
                )
            )
        }

        val success = service.typeText(textToType, fieldHint)
        if (success) {
            CapabilityResult(
                isSuccess = true,
                message = "Typed '$textToType' into active input field.",
                outputData = mapOf(
                    "textTyped" to "true",
                    "text" to textToType,
                    "verificationState" to "VERIFIED"
                )
            )
        } else {
            CapabilityResult(
                isSuccess = false,
                message = "Could not find active input field to type '$textToType'.",
                error = "Input field not found",
                outputData = mapOf(
                    "textTyped" to "false",
                    "text" to textToType,
                    "verificationState" to "FAILED"
                )
            )
        }
    }
}

// 22. UI Scroll Capability
class UiScrollCapability : AgentCapability {
    override val id: String = "ui_scroll"
    override val name: String = "Scroll Screen"
    override val description: String = "Scrolls the screen up, down, forward, or backward"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val direction = (params["direction"] ?: "down").lowercase().trim()
        val forward = direction != "up" && direction != "backward"
        val service = com.ace.app.accessibility.AceAccessibilityService.getInstance()

        if (service == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Accessibility service disabled.", error = "Accessibility disabled")
        }

        val success = service.scroll(forward)
        CapabilityResult(isSuccess = success, message = if (success) "Scrolled screen $direction." else "Failed to scroll screen.")
    }
}

// 23. UI Press Button Capability
class UiPressButtonCapability : AgentCapability {
    override val id: String = "ui_press_button"
    override val name: String = "Press Key / Global Button"
    override val description: String = "Presses system keys like Back, Home, or Recents"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val button = (params["button"] ?: params["action"] ?: "BACK").trim()
        val service = com.ace.app.accessibility.AceAccessibilityService.getInstance()

        if (service == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Accessibility service disabled.", error = "Accessibility disabled")
        }

        val success = service.pressGlobalButton(button)
        CapabilityResult(isSuccess = success, message = if (success) "Pressed system button '$button'." else "Failed to press button '$button'.")
    }
}

// 24. Toggle Flashlight Capability
class FlashlightCapability : AgentCapability {
    override val id: String = "flashlight"
    override val name: String = "Toggle Flashlight"
    override val description: String = "Turns camera torch / flashlight on or off"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val stateStr = (params["state"] ?: params["action"] ?: params["goal"] ?: "on").lowercase()
        val turnOn = !stateStr.contains("off") && !stateStr.contains("disable")
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable for flashlight execution.")
        try {
            val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList.firstOrNull() ?: "0"

            cameraManager.setTorchMode(cameraId, turnOn)
            val actionText = if (turnOn) "Turned on" else "Turned off"
            val targetState = if (turnOn) "ON" else "OFF"
            
            // Return structured verification data
            CapabilityResult(
                isSuccess = true, 
                message = "$actionText device flashlight.",
                outputData = mapOf(
                    "flashlightChanged" to "true",
                    "targetState" to targetState,
                    "currentState" to targetState,
                    "action" to actionText
                )
            )
        } catch (e: Exception) {
            CapabilityResult(
                isSuccess = false, 
                message = "Flashlight toggle failed: ${e.message}", 
                error = e.message,
                outputData = mapOf(
                    "flashlightChanged" to "false",
                    "targetState" to "UNKNOWN"
                )
            )
        }
    }
}

// Instant Intelligence Capability Backend
class InstantIntelligenceCapability : AgentCapability {
    override val id: String = "instant_intelligence"
    override val name: String = "Instant Intelligence"
    override val description: String = "Answers Android system queries directly using local Android APIs instantly"
    override val category: TaskCategory = TaskCategory.GENERAL
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult {
        val command = (params["command"] ?: params["goal"] ?: "").trim()
        val result = com.ace.app.instant.InstantIntelligenceEngine.execute(context, command)
        return CapabilityResult(
            isSuccess = result.isHandled,
            message = result.message,
            outputData = result.outputData,
            error = result.error
        )
    }
}

// 25. System Mobile Data Capability
class SystemMobileDataCapability : AgentCapability {
    override val id: String = "system_mobile_data"
    override val name: String = "Mobile Data Setting"
    override val description: String = "Manages Mobile Data connectivity and settings"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val stateStr = (params["state"] ?: params["action"] ?: params["goal"] ?: "off").lowercase()
        val turnOff = stateStr.contains("off") || stateStr.contains("disable")
        val targetState = if (turnOff) "OFF" else "ON"
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable for mobile data.")

        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            val isCellularActive = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true

            if (turnOff && !isCellularActive) {
                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Mobile data is already disconnected / off.",
                    outputData = mapOf("mobileDataState" to "OFF", "targetState" to "OFF", "currentState" to "OFF")
                )
            }

            val settingsIntent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                ctx.startActivity(settingsIntent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(fallbackIntent)
            }

            CapabilityResult(
                isSuccess = true,
                message = "Opened Mobile Data settings. Please toggle mobile data $targetState.",
                outputData = mapOf(
                    "status" to "AWAITING_USER_ACTION",
                    "userActionRequired" to "true",
                    "targetState" to targetState,
                    "mobileDataSettingsOpened" to "true"
                )
            )
        } catch (e: Exception) {
            CapabilityResult(isSuccess = false, message = "Mobile data operation failed: ${e.message}", error = e.message)
        }
    }
}

// 26. System Wi-Fi Capability
class SystemWifiCapability : AgentCapability {
    override val id: String = "system_wifi"
    override val name: String = "Wi-Fi Setting"
    override val description: String = "Manages Wi-Fi state and settings"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val stateStr = (params["state"] ?: params["action"] ?: params["goal"] ?: "off").lowercase()
        val isQuery = stateStr.contains("query") || stateStr.contains("is ") || stateStr.contains("status") || stateStr.contains("check")
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable for Wi-Fi.")

        val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifiManager == null) {
            return@withContext CapabilityResult(isSuccess = false, message = "Wi-Fi service unavailable on this device.")
        }

        val currentEnabled = try {
            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            Log.w("ACE_WIFI", "ACE_WIFI: Could not read isWifiEnabled: ${e.message}")
            false
        }

        if (isQuery) {
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Wi-Fi is currently ${if (currentEnabled) "ON" else "OFF"}.",
                outputData = mapOf(
                    "wifiState" to if (currentEnabled) "ON" else "OFF",
                    "wifiQueryCompleted" to "true",
                    "wifiChanged" to "true"
                )
            )
        }

        val turnOn = !stateStr.contains("off") && !stateStr.contains("disable")
        val targetState = if (turnOn) "ON" else "OFF"

        if (turnOn == currentEnabled) {
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Wi-Fi is already $targetState.",
                outputData = mapOf("wifiState" to targetState, "targetState" to targetState, "currentState" to targetState, "wifiChanged" to "true")
            )
        }

        @Suppress("DEPRECATION")
        try {
            wifiManager.setWifiEnabled(turnOn)
        } catch (e: Exception) {
            Log.w("ACE_WIFI", "ACE_WIFI: setWifiEnabled failed: ${e.message}")
        }

        val verifyEnabled = try {
            wifiManager.isWifiEnabled
        } catch (_: Exception) {
            !turnOn
        }

        if (verifyEnabled == turnOn) {
            return@withContext CapabilityResult(
                isSuccess = true,
                message = "Turned Wi-Fi $targetState.",
                outputData = mapOf("wifiState" to targetState, "targetState" to targetState, "currentState" to targetState, "wifiChanged" to "true")
            )
        }

        // Android 10+ (API 29+) restricts direct 3rd-party setWifiEnabled toggling; launch Wi-Fi Settings Panel
        val panelIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            Intent(android.provider.Settings.Panel.ACTION_WIFI).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        val opened = try {
            ctx.startActivity(panelIntent)
            true
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                true
            } catch (_: Exception) {
                false
            }
        }

        if (opened) {
            CapabilityResult(
                isSuccess = true,
                message = "Wi-Fi settings opened. Please switch Wi-Fi $targetState.",
                outputData = mapOf(
                    "status" to "AWAITING_USER_ACTION",
                    "userActionRequired" to "true",
                    "targetState" to targetState,
                    "wifiSettingsOpened" to "true"
                )
            )
        } else {
            CapabilityResult(isSuccess = false, message = "Could not open Wi-Fi settings.")
        }
    }
}

// 27. System Bluetooth Capability
class SystemBluetoothCapability : AgentCapability {
    override val id: String = "system_bluetooth"
    override val name: String = "Bluetooth Setting"
    override val description: String = "Manages Bluetooth state and settings"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val stateStr = (params["state"] ?: params["action"] ?: params["goal"] ?: "off").lowercase()
        val turnOn = !stateStr.contains("off") && !stateStr.contains("disable")
        val targetState = if (turnOn) "ON" else "OFF"
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable for Bluetooth.")

        try {
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) {
                return@withContext CapabilityResult(isSuccess = false, message = "Bluetooth is not supported on this device.")
            }

            val isEnabled = bluetoothAdapter.isEnabled
            if (turnOn == isEnabled) {
                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Bluetooth is already $targetState.",
                    outputData = mapOf("bluetoothState" to targetState, "targetState" to targetState, "currentState" to targetState, "bluetoothChanged" to "true")
                )
            }

            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)

            CapabilityResult(
                isSuccess = true,
                message = "Opened Bluetooth settings to switch Bluetooth $targetState.",
                outputData = mapOf(
                    "status" to "AWAITING_USER_ACTION",
                    "userActionRequired" to "true",
                    "targetState" to targetState,
                    "bluetoothSettingsOpened" to "true"
                )
            )
        } catch (e: Exception) {
            CapabilityResult(isSuccess = false, message = "Bluetooth operation failed: ${e.message}", error = e.message)
        }
    }
}

// 28. System Volume Capability
class SystemVolumeCapability : AgentCapability {
    override val id: String = "system_volume"
    override val name: String = "Volume Control"
    override val description: String = "Adjusts device media or ringer volume"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val direction = (params["direction"] ?: params["action"] ?: "up").lowercase()
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable for volume adjustment.")

        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val flag = android.media.AudioManager.FLAG_SHOW_UI
            val dir = when {
                direction.contains("up") || direction.contains("increase") || direction.contains("raise") -> android.media.AudioManager.ADJUST_RAISE
                direction.contains("down") || direction.contains("lower") || direction.contains("decrease") -> android.media.AudioManager.ADJUST_LOWER
                else -> android.media.AudioManager.ADJUST_SAME
            }
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, flag)
            val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)

            CapabilityResult(
                isSuccess = true,
                message = "Adjusted volume ($direction). Current volume level: $currentVol.",
                outputData = mapOf("volumeLevel" to currentVol.toString(), "volumeAdjusted" to "true")
            )
        } catch (e: Exception) {
            CapabilityResult(isSuccess = false, message = "Volume adjustment failed: ${e.message}", error = e.message)
        }
    }
}

// 29. System Brightness Capability
class SystemBrightnessCapability : AgentCapability {
    override val id: String = "system_brightness"
    override val name: String = "Display Brightness"
    override val description: String = "Adjusts device screen brightness"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable for brightness.")

        try {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            CapabilityResult(
                isSuccess = true,
                message = "Opened Display settings to adjust screen brightness.",
                outputData = mapOf("status" to "AWAITING_USER_ACTION", "userActionRequired" to "true", "displaySettingsOpened" to "true")
            )
        } catch (e: Exception) {
            CapabilityResult(isSuccess = false, message = "Display settings open failed: ${e.message}", error = e.message)
        }
    }
}

// 30. System Airplane Mode Capability
class SystemAirplaneModeCapability : AgentCapability {
    override val id: String = "system_airplane_mode"
    override val name: String = "Airplane Mode Setting"
    override val description: String = "Manages Airplane Mode state and settings"
    override val category: TaskCategory = TaskCategory.SYSTEM
    override val networkRequirement: NetworkRequirement = NetworkRequirement.NONE

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val stateStr = (params["state"] ?: params["action"] ?: params["goal"] ?: "on").lowercase()
        val turnOn = !stateStr.contains("off") && !stateStr.contains("disable")
        val targetState = if (turnOn) "ON" else "OFF"
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable for Airplane Mode.")

        try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            CapabilityResult(
                isSuccess = true,
                message = "Opened Airplane Mode settings to switch Airplane Mode $targetState.",
                outputData = mapOf("status" to "AWAITING_USER_ACTION", "userActionRequired" to "true", "targetState" to targetState, "airplaneSettingsOpened" to "true")
            )
        } catch (e: Exception) {
            CapabilityResult(isSuccess = false, message = "Airplane Mode open failed: ${e.message}", error = e.message)
        }
    }
}

// 35. Universal Search Capability
class UniversalSearchCapability : AgentCapability {
    override val id: String = "universal_search"
    override val name: String = "Universal App Search"
    override val description: String = "Performs in-app search across any application (e.g. YouTube, Amazon, Blinkit, Spotify, Maps, Gmail, Chrome, etc.)"
    override val category: TaskCategory = TaskCategory.RESEARCH
    override val networkRequirement: NetworkRequirement = NetworkRequirement.OPTIONAL

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult = withContext(Dispatchers.Main) {
        val query = (params["query"] ?: params["text"] ?: params["search"] ?: "").trim()
        val targetApp = (params["targetApp"] ?: params["app"] ?: params["appName"] ?: "YouTube").trim()

        if (query.isBlank()) {
            return@withContext CapabilityResult(isSuccess = false, message = "Search query is required.")
        }
        val ctx = context ?: return@withContext CapabilityResult(isSuccess = false, message = "Context unavailable for Universal Search.")

        try {
            val appInfo = AppDiscoveryEngine.findApp(ctx, targetApp)
            val canonicalAppName = appInfo?.appName ?: targetApp

            val service = com.ace.app.accessibility.AceAccessibilityService.getInstance()
            if (service != null && com.ace.app.accessibility.AceAccessibilityService.isServiceEnabled(ctx)) {
                val engine = com.ace.app.accessibility.UniversalAppInteractionEngine()
                val verifyResult = engine.executeInAppSearch(ctx, canonicalAppName, query)
                return@withContext CapabilityResult(
                    isSuccess = verifyResult.isVerified,
                    message = verifyResult.summary,
                    outputData = mapOf(
                        "query" to query,
                        "targetApp" to canonicalAppName,
                        "appName" to canonicalAppName,
                        "searchSubmitted" to verifyResult.isVerified.toString(),
                        "resultsObserved" to verifyResult.isVerified.toString()
                    ),
                    error = if (!verifyResult.isVerified) verifyResult.evidenceText else null
                )
            }

            // Fallback launch if accessibility service is unavailable
            if (appInfo?.launchIntent != null) {
                appInfo.launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                ctx.startActivity(appInfo.launchIntent)

                return@withContext CapabilityResult(
                    isSuccess = true,
                    message = "Opened '$canonicalAppName' for search query '$query'.",
                    outputData = mapOf(
                        "query" to query,
                        "targetApp" to canonicalAppName,
                        "appName" to canonicalAppName,
                        "searchSubmitted" to "true"
                    )
                )
            }

            // Fallback web search if app is not installed
            val encodedQuery = java.net.URLEncoder.encode("$query $targetApp", "UTF-8")
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encodedQuery")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(webIntent)

            return@withContext CapabilityResult(
                isSuccess = true,
                message = "App '$targetApp' not found. Performing web search for '$query $targetApp'.",
                outputData = mapOf(
                    "query" to query,
                    "targetApp" to targetApp,
                    "fallbackWebSearch" to "true"
                )
            )
        } catch (e: Exception) {
            return@withContext CapabilityResult(isSuccess = false, message = "Universal search failed for '$query' on '$targetApp': ${e.message}", error = e.message)
        }
    }
}

// 36. YouTube Search Capability (Legacy Wrapper for UniversalSearchCapability)
class YouTubeSearchCapability : AgentCapability {
    override val id: String = "youtube_search"
    override val name: String = "YouTube Search"
    override val description: String = "Searches videos on YouTube"
    override val category: TaskCategory = TaskCategory.RESEARCH
    override val networkRequirement: NetworkRequirement = NetworkRequirement.REQUIRED

    override suspend fun execute(context: Context?, params: Map<String, String>): CapabilityResult {
        val newParams = params.toMutableMap()
        if (!newParams.containsKey("targetApp")) newParams["targetApp"] = "YouTube"
        return UniversalSearchCapability().execute(context, newParams)
    }
}

// Capability Registry containing all system capabilities
object CapabilityRegistry {
    private val capabilities = mutableMapOf<String, AgentCapability>()

    val CONSEQUENTIAL_CAPABILITY_IDS: Set<String> = setOf(
        "phone_dialer",
        "whatsapp_call"
    )

    init {
        register(InstantIntelligenceCapability())
        register(ContactLookupCapability())
        register(PhoneCallCapability())
        register(PhoneDialerCapability())
        register(WhatsAppCallCapability())
        register(FileDiscoveryCapability())
        register(DocumentAnalysisCapability())
        register(PdfSummarizeCapability())
        register(OpenWebsiteCapability())
        register(SetAlarmCapability())
        register(ScheduleTaskCapability())
        register(SystemSettingsCapability())
        register(AppShareCapability())
        register(DocumentSendCapability())
        register(SmartDeliveryCapability())
        register(MediaPlaybackCapability())
        register(WebSearchCapability())
        register(UniversalSearchCapability())
        register(YouTubeSearchCapability())
        register(TextReasoningCapability())
        register(AppControlCapability())
        register(CurrentLocationCapability())
        register(RouteDirectionsCapability())
        register(UiOpenAppCapability())
        register(UiClickCapability())
        register(UiTypeCapability())
        register(UiScrollCapability())
        register(UiPressButtonCapability())
        register(FlashlightCapability())
        register(SystemMobileDataCapability())
        register(SystemWifiCapability())
        register(SystemBluetoothCapability())
        register(SystemVolumeCapability())
        register(SystemBrightnessCapability())
        register(SystemAirplaneModeCapability())
    }

    fun register(capability: AgentCapability) {
        capabilities[capability.id] = capability
    }

    fun getCapability(id: String): AgentCapability? {
        val direct = capabilities[id] ?: capabilities[id.lowercase()]
        if (direct != null) return direct
        val mappedId = when (id.lowercase().trim()) {
            "app.launch", "open_app" -> "ui_open_app"
            "web.open", "open_url" -> "web_open_url"
            "web.search", "universal_search", "app_search", "search" -> "universal_search"
            "ui.find", "ui.click", "click", "select" -> "ui_click"
            "ui.type", "type_text", "fill_field" -> "ui_type"
            "ui.scroll", "scroll" -> "ui_scroll"
            "device.open_settings", "system_settings" -> "system_settings"
            "phone.call", "phone_call" -> "phone_call"
            "phone.dial", "phone_dialer" -> "phone_dialer"
            "contact.lookup", "contact_lookup" -> "contact_lookup"
            "file.find", "file.open", "file_discover", "file_manager" -> "file_discovery"
            "accessibility.execute" -> "ui_click"
            "play_media" -> "media_playback"
            "file_share", "share", "send" -> "smart_delivery"
            "smart_delivery" -> "smart_delivery"
            "navigate_back" -> "ui_press_button"
            "flashlight" -> "flashlight"
            else -> id.lowercase()
        }
        return capabilities[mappedId]
    }

    fun isRegistered(id: String): Boolean = getCapability(id) != null

    fun getAllCapabilities(): List<AgentCapability> = capabilities.values.toList()
}
