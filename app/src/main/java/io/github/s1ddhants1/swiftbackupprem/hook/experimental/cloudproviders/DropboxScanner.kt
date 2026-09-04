package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

@Keep
object DropboxScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "Dropbox"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val token = resolveDropboxToken(prefs)
        return !token.isNullOrBlank()
    }

    private fun resolveDropboxToken(prefs: SharedPreferences): String? {
        val keys = listOf("dropbox_access_token", "dropbox_token", "dropbox_auth_token", "dropbox_oauth_token", "dropbox_refresh_token")
        for (k in keys) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = resolveDropboxToken(prefs) ?: return emptyList()
        val items = mutableListOf<CloudFileItem>()

        var cursor: String? = null
        var isFirst = true

        do {
            val urlStr = if (isFirst) {
                "https://api.dropboxapi.com/2/files/list_folder"
            } else {
                "https://api.dropboxapi.com/2/files/list_folder/continue"
            }

            val body = if (isFirst) {
                JSONObject().apply {
                    put("path", "")
                    put("recursive", true)
                    put("include_media_info", false)
                }.toString()
            } else {
                JSONObject().apply {
                    put("cursor", cursor)
                }.toString()
            }

            val respText = executePostJson(urlStr, token, body) ?: break
            val root = attempt("parse Dropbox list_folder", silent = true) { JSONObject(respText) } ?: break

            val entries = root.optJSONArray("entries")
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val tag = entry.optString(".tag")
                    if (tag == "file") {
                        val name = entry.optString("name")
                        val id = entry.optString("id").takeIf { it.isNotBlank() } ?: entry.optString("path_lower")
                        val size = entry.optLong("size", 0L)
                        val pathDisplay = entry.optString("path_display", id)

                        if (name.isNotBlank() && id.isNotBlank()) {
                            items.add(
                                CloudFileItem(
                                    id = pathDisplay,
                                    name = name,
                                    size = size,
                                    provider = providerName,
                                    customDownloadUrl = pathDisplay
                                )
                            )
                        }
                    }
                }
            }

            isFirst = false
            val hasMore = root.optBoolean("has_more", false)
            cursor = if (hasMore) root.optString("cursor").takeIf { it.isNotBlank() } else null
        } while (cursor != null)

        Log.d(TAG, "[DropboxScanner] Discovered ${items.size} backup items in Dropbox")
        return items
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = resolveDropboxToken(prefs) ?: return null
        val path = fileItem.customDownloadUrl ?: fileItem.id
        val downloadUrl = "https://content.dropboxapi.com/2/files/download"

        return attempt("Dropbox download", silent = true) {
            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                val argObj = JSONObject().apply { put("path", path) }
                setRequestProperty("Dropbox-API-Arg", argObj.toString())
                connectTimeout = 15000
                readTimeout = 15000
            }
            try {
                if (conn.responseCode == 200) {
                    conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                } else {
                    Log.w(TAG, "[DropboxScanner] HTTP download error ${conn.responseCode} for ${AppUtils.sanitizeUrl(path)}")
                    null
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun executePostJson(urlStr: String, token: String, jsonBody: String): String? = attempt("Dropbox POST JSON", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[DropboxScanner] POST returned ${conn.responseCode} for ${AppUtils.sanitizeUrl(urlStr)}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
