package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Keep
object GoogleDriveScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "GoogleDrive"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val token = prefs.getString("nogms_access_token", null)
        val folderId = prefs.getString("google_drive_cloud_main_folder_id", null)
        return !token.isNullOrBlank() && !folderId.isNullOrBlank()
    }

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = prefs.getString("nogms_access_token", null) ?: return emptyList()
        val folderId = prefs.getString("google_drive_cloud_main_folder_id", null) ?: return emptyList()

        val items = mutableListOf<CloudFileItem>()
        var pageToken: String? = null

        do {
            val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
            val pageParam = if (pageToken != null) "&pageToken=$pageToken" else ""
            val urlStr = "https://www.googleapis.com/drive/v3/files?q=$q&fields=nextPageToken,files(id,name,size,modifiedTime,createdTime,thumbnailLink)&pageSize=1000$pageParam"

            val respText = executeGet(urlStr, token) ?: break
            val root = attempt("parse drive files", silent = true) { JSONObject(respText) } ?: break
            val filesArr = root.optJSONArray("files") ?: JSONArray()

            for (i in 0 until filesArr.length()) {
                val fileObj = filesArr.getJSONObject(i)
                val id = fileObj.optString("id")
                val name = fileObj.optString("name")
                val size = fileObj.optLong("size", 0L)
                val thumbnailLink = fileObj.optString("thumbnailLink").takeIf { it.isNotBlank() }
                val timeStr = fileObj.optString("modifiedTime").ifBlank { fileObj.optString("createdTime") }
                val timestamp = if (timeStr.isNotBlank()) {
                    try {
                        java.time.Instant.parse(timeStr).toEpochMilli()
                    } catch (_: Throwable) {
                        0L
                    }
                } else 0L

                if (id.isNotBlank() && name.isNotBlank()) {
                    items.add(
                        CloudFileItem(
                            id = id,
                            name = name,
                            size = size,
                            timestamp = timestamp,
                            thumbnailLink = thumbnailLink,
                            provider = providerName
                        )
                    )
                }
            }

            pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)

        Log.d(TAG, "[GoogleDriveScanner] Found ${items.size} files in folder $folderId")
        return items
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = prefs.getString("nogms_access_token", null) ?: return null
        val urlStr = "https://www.googleapis.com/drive/v3/files/${fileItem.id}?alt=media"
        return executeGet(urlStr, token)
    }

    override fun downloadByteRange(
        context: Context,
        prefs: SharedPreferences,
        fileItem: CloudFileItem,
        startByte: Long,
        endByte: Long
    ): ByteArray? {
        val token = prefs.getString("nogms_access_token", null) ?: return null
        val urlStr = "https://www.googleapis.com/drive/v3/files/${fileItem.id}?alt=media"
        return executeGetRange(urlStr, token, startByte, endByte)
    }

    private fun executeGetRange(urlStr: String, token: String, startByte: Long, endByte: Long): ByteArray? = attempt("Drive HTTP GET Range", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Range", "bytes=$startByte-$endByte")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 200 || conn.responseCode == 206) {
                conn.inputStream.use { it.readBytes() }
            } else {
                Log.w(TAG, "[GoogleDriveScanner] HTTP Range GET returned ${conn.responseCode} for ${AppUtils.sanitizeUrl(urlStr)} (bytes=$startByte-$endByte)")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun executeGet(urlStr: String, token: String): String? = attempt("Drive HTTP GET", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[GoogleDriveScanner] HTTP GET returned ${conn.responseCode} for ${AppUtils.sanitizeUrl(urlStr)}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
