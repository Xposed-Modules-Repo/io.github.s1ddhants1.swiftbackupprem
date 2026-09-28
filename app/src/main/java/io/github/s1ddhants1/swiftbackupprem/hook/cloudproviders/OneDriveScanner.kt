package io.github.s1ddhants1.swiftbackupprem.hook.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.Queue
import java.util.regex.Pattern

@Keep
object OneDriveScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "OneDrive"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val token = resolveToken(prefs)
        return !token.isNullOrBlank()
    }

    fun resolveToken(prefs: SharedPreferences): String? {
        val knownKeys = listOf(
            "onedrive_access_token",
            "onedrive_token",
            "ms_graph_token",
            "onedrive_oauth_token",
            "onedrive_refresh_token",
            "msal_access_token",
            "microsoft_access_token",
            "ms_access_token",
            "graph_token",
            "onedrive_auth_token",
            "onedrive_bearer_token",
            "onedrive_key",
            "onedrive_cloud_token",
            "onedrive_cloud_access_token"
        )
        for (k in knownKeys) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrBlank()) return v.trim()
        }

        try {
            for ((key, value) in prefs.all) {
                if (value is String && value.isNotBlank()) {
                    val lk = key.lowercase()
                    if ((lk.contains("onedrive") || lk.contains("msal") || lk.contains("graph")) &&
                        (lk.contains("token") || lk.contains("access") || lk.contains("bearer") || lk.contains("auth"))
                    ) {
                        return value.trim()
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private data class FolderTarget(val url: String, val isRoot: Boolean, val folderPath: String = "")
    private val SWIFT_BACKUP_FOLDER_PATTERN = Pattern.compile("^.*swift[ _-]?backup.*$", Pattern.CASE_INSENSITIVE)

    private fun resolveRelativePath(itemObj: JSONObject, target: FolderTarget, name: String): String {
        val parentRef = itemObj.optJSONObject("parentReference")
        val parentPath = parentRef?.optString("path")
        if (!parentPath.isNullOrBlank()) {
            val rel = if (parentPath.contains("root:")) {
                parentPath.substringAfter("root:").trim('/')
            } else {
                parentPath.trim('/')
            }
            if (rel.isNotBlank()) {
                return "$rel/$name"
            }
        }
        return if (target.folderPath.isBlank()) name else "${target.folderPath}/$name"
    }

    private fun scanFolder(
        token: String,
        folderUrl: String,
        folderPath: String,
        items: MutableList<CloudFileItem>,
        seenFileIds: MutableSet<String>,
        maxFolders: Int = 15
    ) {
        val queue: Queue<FolderTarget> = LinkedList()
        val visited = mutableSetOf<String>()
        queue.add(FolderTarget(folderUrl, isRoot = false, folderPath = folderPath))

        var scannedCount = 0
        while (queue.isNotEmpty() && scannedCount < maxFolders) {
            val target = queue.poll() ?: break
            if (!visited.add(target.url)) continue
            scannedCount++

            var currentUrl: String? = target.url
            while (currentUrl != null) {
                val respText = executeGet(currentUrl, token) ?: break
                val root = attempt("parse OneDrive folder JSON", silent = true) { JSONObject(respText) } ?: break
                val valueArr = root.optJSONArray("value")
                if (valueArr != null) {
                    for (i in 0 until valueArr.length()) {
                        val itemObj = valueArr.optJSONObject(i) ?: continue
                        val id = itemObj.optString("id")
                        val name = itemObj.optString("name")
                        val size = itemObj.optLong("size", 0L)
                        val timeStr = itemObj.optString("lastModifiedDateTime").ifBlank {
                            itemObj.optJSONObject("fileSystemInfo")?.optString("lastModifiedDateTime") ?: ""
                        }
                        val timestamp = if (timeStr.isNotBlank()) {
                            attempt("parse OneDrive ISO date", silent = true) {
                                java.time.Instant.parse(timeStr).toEpochMilli()
                            } ?: 0L
                        } else 0L
                        val downloadUrl = itemObj.optString("@microsoft.graph.downloadUrl").takeIf { it.isNotBlank() }
                        val isFolder = itemObj.has("folder") || itemObj.optJSONObject("remoteItem")?.has("folder") == true

                        if (isFolder) {
                            val childFolderPath = resolveRelativePath(itemObj, target, name)
                            val childUrl = "https://graph.microsoft.com/v1.0/me/drive/items/$id/children"
                            if (!visited.contains(childUrl)) {
                                queue.add(FolderTarget(childUrl, isRoot = false, folderPath = childFolderPath))
                            }
                        } else if (name.isNotBlank() && id.isNotBlank() && seenFileIds.add(id)) {
                            val relativePath = resolveRelativePath(itemObj, target, name)
                            items.add(
                                CloudFileItem(
                                    id = relativePath,
                                    name = name,
                                    size = size,
                                    timestamp = timestamp,
                                    provider = providerName,
                                    customDownloadUrl = downloadUrl
                                )
                            )
                        }
                    }
                }
                currentUrl = root.optString("@odata.nextLink").takeIf { it.isNotBlank() }
            }
        }
    }

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = resolveToken(prefs) ?: return emptyList()
        val items = mutableListOf<CloudFileItem>()
        val seenFileIds = mutableSetOf<String>()

        val mainFolderId = resolveMainFolderId(token, prefs)
        if (!mainFolderId.isNullOrBlank()) {
            val mainUrl = "https://graph.microsoft.com/v1.0/me/drive/items/$mainFolderId/children"
            scanFolder(token, mainUrl, folderPath = "Swift Backup", items = items, seenFileIds = seenFileIds)
            if (items.isNotEmpty()) {
                Log.d(TAG, "[OneDriveScanner] Direct path discovered ${items.size} backup items in main folder ($mainFolderId)")
                return items
            }
        }

        val directUrl = "https://graph.microsoft.com/v1.0/me/drive/root:/Swift%20Backup:/children"
        scanFolder(token, directUrl, folderPath = "Swift Backup", items = items, seenFileIds = seenFileIds)
        if (items.isNotEmpty()) {
            Log.d(TAG, "[OneDriveScanner] Direct named path discovered ${items.size} backup items in 'Swift Backup'")
            return items
        }

        val rootUrl = "https://graph.microsoft.com/v1.0/me/drive/root/children"
        val rootResp = executeGet(rootUrl, token)
        if (!rootResp.isNullOrBlank()) {
            val rootObj = attempt("parse root children", silent = true) { JSONObject(rootResp) }
            val valArr = rootObj?.optJSONArray("value")
            if (valArr != null) {
                for (i in 0 until valArr.length()) {
                    val item = valArr.optJSONObject(i) ?: continue
                    if (item.has("folder")) {
                        val name = item.optString("name", "")
                        val id = item.optString("id", "")
                        if (id.isNotBlank() && SWIFT_BACKUP_FOLDER_PATTERN.matcher(name).matches()) {
                            val folderUrl = "https://graph.microsoft.com/v1.0/me/drive/items/$id/children"
                            scanFolder(token, folderUrl, folderPath = name, items = items, seenFileIds = seenFileIds)
                        }
                    }
                }
            }
        }

        Log.d(TAG, "[OneDriveScanner] Discovered ${items.size} backup items in OneDrive")
        return items
    }

    private fun encodeGraphPath(path: String): String {
        return path.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = resolveToken(prefs)
        if (!fileItem.customDownloadUrl.isNullOrBlank()) {
            val direct = executeGet(fileItem.customDownloadUrl, token = null)
            if (!direct.isNullOrBlank()) return direct
        }

        if (token.isNullOrBlank()) return null
        val fallbackUrl = if (fileItem.id.contains("/") || fileItem.id.contains(" ")) {
            "https://graph.microsoft.com/v1.0/me/drive/root:/" + encodeGraphPath(fileItem.id) + ":/content"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/${fileItem.id}/content"
        }
        return executeGet(fallbackUrl, token)
    }

    override fun downloadByteRange(
        context: Context,
        prefs: SharedPreferences,
        fileItem: CloudFileItem,
        startByte: Long,
        endByte: Long
    ): ByteArray? {
        val token = resolveToken(prefs)
        if (!fileItem.customDownloadUrl.isNullOrBlank()) {
            val direct = executeGetRange(fileItem.customDownloadUrl, token = null, startByte, endByte)
            if (direct != null && direct.isNotEmpty()) return direct
        }

        if (token.isNullOrBlank()) return null
        val fallbackUrl = if (fileItem.id.contains("/") || fileItem.id.contains(" ")) {
            "https://graph.microsoft.com/v1.0/me/drive/root:/" + encodeGraphPath(fileItem.id) + ":/content"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/${fileItem.id}/content"
        }
        return executeGetRange(fallbackUrl, token, startByte, endByte)
    }

    private fun executeGetRange(
        urlStr: String,
        token: String?,
        startByte: Long,
        endByte: Long
    ): ByteArray? = CloudHttpHelper.executeGetRange(
        urlStr = urlStr,
        headers = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap(),
        startByte = startByte,
        endByte = endByte,
        onAuthRedirect = { loc ->
            if (loc.contains("blob.core.windows.net") || loc.contains("1drv.ms") || !loc.contains("graph.microsoft.com")) {
                emptyMap()
            } else {
                token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            }
        }
    )

    private fun executeGet(
        urlStr: String,
        token: String?
    ): String? = CloudHttpHelper.executeGet(
        urlStr = urlStr,
        headers = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap(),
        onAuthRedirect = { loc ->
            if (loc.contains("blob.core.windows.net") || loc.contains("1drv.ms") || !loc.contains("graph.microsoft.com")) {
                emptyMap()
            } else {
                token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            }
        }
    )

    override fun uploadFileText(
        context: Context,
        prefs: SharedPreferences,
        remoteRelativePath: String,
        content: String
    ): Boolean = attempt("OneDrive uploadFileText", silent = true) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val contentType = when {
            remoteRelativePath.endsWith(".json", ignoreCase = true) -> "application/json; charset=utf-8"
            remoteRelativePath.endsWith(".xml", ignoreCase = true) -> "application/xml; charset=utf-8"
            else -> "text/plain; charset=utf-8"
        }
        uploadBytes(prefs, remoteRelativePath, bytes, contentType)
    } ?: false

    override fun uploadFile(
        context: Context,
        prefs: SharedPreferences,
        remoteRelativePath: String,
        file: File
    ): Boolean = attempt("OneDrive uploadFile", silent = true) {
        if (!file.exists() || !file.canRead()) return@attempt false
        val bytes = file.readBytes()
        uploadBytes(prefs, remoteRelativePath, bytes, "application/octet-stream")
    } ?: false

    fun resolveMainFolderId(token: String?, prefs: SharedPreferences): String? {
        val knownId = listOf(
            "one_drive_cloud_main_folder_id",
            "onedrive_cloud_main_folder_id",
            "onedrive_main_folder_id",
            "one_drive_main_folder_id"
        ).firstNotNullOfOrNull { k -> prefs.getString(k, null)?.trim()?.takeIf { it.isNotBlank() } }
        if (knownId != null) return knownId

        if (token.isNullOrBlank()) return null

        val respText = executeGet("https://graph.microsoft.com/v1.0/me/drive/root/children?\$select=id,name,folder", token)
        if (!respText.isNullOrBlank()) {
            val root = attempt("parse OneDrive root children", silent = true) { JSONObject(respText) }
            val arr = root?.optJSONArray("value")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (item.has("folder")) {
                        val name = item.optString("name", "")
                        if (SWIFT_BACKUP_FOLDER_PATTERN.matcher(name).matches()) {
                            val id = item.optString("id", "").trim()
                            if (id.isNotBlank()) return id
                        }
                    }
                }
            }
        }
        return null
    }

    fun buildUploadUrl(prefs: SharedPreferences, remoteRelativePath: String, token: String? = null): String {
        val cleanPath = remoteRelativePath.trim().trimStart('/')
        val isExplicitRoot = cleanPath.startsWith("Swift Backup/", ignoreCase = true) ||
                cleanPath.startsWith("SwiftBackup/", ignoreCase = true)

        if (isExplicitRoot) {
            return "https://graph.microsoft.com/v1.0/me/drive/root:/" + encodeGraphPath(cleanPath) + ":/content"
        }

        val folderId = resolveMainFolderId(token, prefs)
        return if (!folderId.isNullOrBlank()) {
            "https://graph.microsoft.com/v1.0/me/drive/items/$folderId:/" + encodeGraphPath(cleanPath) + ":/content"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/root:/Swift Backup/" + encodeGraphPath(cleanPath) + ":/content"
        }
    }

    private fun uploadBytes(
        prefs: SharedPreferences,
        remoteRelativePath: String,
        bytes: ByteArray,
        contentType: String
    ): Boolean {
        val token = resolveToken(prefs) ?: return false
        val cleanPath = remoteRelativePath.trim().trimStart('/')
        if (cleanPath.isBlank()) return false

        val targetUrl = buildUploadUrl(prefs, cleanPath, token)
        return executePut(targetUrl, token, bytes, contentType)
    }

    fun executePut(
        urlStr: String,
        token: String,
        bytes: ByteArray,
        contentType: String
    ): Boolean = attempt("OneDrive HTTP PUT", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Content-Length", bytes.size.toString())
        }
        try {
            conn.outputStream.use { os ->
                os.write(bytes)
                os.flush()
            }
            val code = conn.responseCode
            Log.d(TAG, "[OneDriveScanner] HTTP PUT returned $code for $urlStr (${bytes.size} bytes)")
            code in 200..299
        } finally {
            conn.disconnect()
        }
    } ?: false
}
