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
object BoxScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "Box"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val token = resolveToken(prefs)
        return !token.isNullOrBlank()
    }

    private fun resolveToken(prefs: SharedPreferences): String? {
        val keys = listOf("box_access_token", "box_token", "box_auth_token", "box_oauth_token", "box_refresh_token")
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
        folderQueue.add("0")

        var scannedCount = 0
        while (folderQueue.isNotEmpty() && scannedCount < 20) {
            val folderId = folderQueue.poll() ?: break
            if (!visited.add(folderId)) continue
            scannedCount++

            val urlStr = "https://api.box.com/2.0/folders/$folderId/items?limit=1000&fields=id,type,name,size"
            val respText = executeGet(urlStr, token) ?: continue
            val root = attempt("parse Box folder items", silent = true) { JSONObject(respText) } ?: continue

            val entries = root.optJSONArray("entries")
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val type = entry.optString("type")
                    val id = entry.optString("id")
                    val name = entry.optString("name")
                    val size = entry.optLong("size", 0L)

                    if (type == "folder") {
                        if (visited.add(id)) {
                            folderQueue.add(id)
                        }
                    } else if (type == "file" && name.isNotBlank() && id.isNotBlank()) {
                        items.add(
                            CloudFileItem(
                                id = id,
                                name = name,
                                size = size,
                                provider = providerName
                            )
                        )
                    }
                }
            }
        }

        Log.d(TAG, "[BoxScanner] Discovered ${items.size} backup items in Box")
        return items
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = resolveToken(prefs) ?: return null
        val downloadUrl = "https://api.box.com/2.0/files/${fileItem.id}/content"
        return executeGet(downloadUrl, token)
    }

    private fun executeGet(urlStr: String, token: String): String? = attempt("Box HTTP GET", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[BoxScanner] HTTP GET returned ${conn.responseCode} for $urlStr")
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
