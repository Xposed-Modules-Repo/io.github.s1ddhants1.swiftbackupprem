package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.Queue

@Keep
object OneDriveScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "OneDrive"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val token = resolveToken(prefs)
        return !token.isNullOrBlank()
    }

    private fun resolveToken(prefs: SharedPreferences): String? {
        val keys = listOf("onedrive_access_token", "onedrive_token", "ms_graph_token", "onedrive_oauth_token", "onedrive_refresh_token")
        for (k in keys) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = resolveToken(prefs) ?: return emptyList()
        val items = mutableListOf<CloudFileItem>()

        val folderQueue: Queue<String> = LinkedList()
        val visited = mutableSetOf<String>()

        folderQueue.add("https://graph.microsoft.com/v1.0/me/drive/root/children")
        folderQueue.add("https://graph.microsoft.com/v1.0/me/drive/special/approot/children")

        var scannedCount = 0
        while (folderQueue.isNotEmpty() && scannedCount < 50) {
            val startUrl = folderQueue.poll() ?: break
            if (!visited.add(startUrl)) continue
            scannedCount++

            var currentUrl: String? = startUrl
            while (currentUrl != null) {
                val urlToFetch = currentUrl
                val respText = executeGet(urlToFetch, token) ?: break
                val root = attempt("parse OneDrive JSON", silent = true) { JSONObject(respText) } ?: break

                val valueArr = root.optJSONArray("value")
                if (valueArr != null) {
                    for (i in 0 until valueArr.length()) {
                        val itemObj = valueArr.getJSONObject(i)
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
                            val childUrl = "https://graph.microsoft.com/v1.0/me/drive/items/$id/children"
                            if (!visited.contains(childUrl)) {
                                folderQueue.add(childUrl)
                            }
                        } else if (name.isNotBlank() && id.isNotBlank()) {
                            items.add(
                                CloudFileItem(
                                    id = id,
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

        Log.d(TAG, "[OneDriveScanner] Discovered ${items.size} backup items in OneDrive")
        return items
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = resolveToken(prefs)
        if (!fileItem.customDownloadUrl.isNullOrBlank()) {
            val direct = executeGet(fileItem.customDownloadUrl, token = null)
            if (direct != null) return direct
        }

        if (token.isNullOrBlank()) return null
        val fallbackUrl = "https://graph.microsoft.com/v1.0/me/drive/items/${fileItem.id}/content"
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
            if (direct != null) return direct
        }

        if (token.isNullOrBlank()) return null
        val fallbackUrl = "https://graph.microsoft.com/v1.0/me/drive/items/${fileItem.id}/content"
        return executeGetRange(fallbackUrl, token, startByte, endByte)
    }

    private fun executeGetRange(urlStr: String, token: String?, startByte: Long, endByte: Long): ByteArray? = attempt("OneDrive HTTP GET Range", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            setRequestProperty("Range", "bytes=$startByte-$endByte")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                Log.w(TAG, "[OneDriveScanner] HTTP Range GET returned ${conn.responseCode} for $urlStr (bytes=$startByte-$endByte)")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun executeGet(urlStr: String, token: String?): String? = attempt("OneDrive HTTP GET", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[OneDriveScanner] HTTP GET returned ${conn.responseCode} for $urlStr")
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
