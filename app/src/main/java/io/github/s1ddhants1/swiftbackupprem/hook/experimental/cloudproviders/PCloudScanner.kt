package io.github.s1ddhants1.swiftbackupprem.hook.advanced.cloud

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

@Keep
object PCloudScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "pCloud"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val token = resolveToken(prefs)
        return !token.isNullOrBlank()
    }

    private fun resolveToken(prefs: SharedPreferences): String? {
        val keys = listOf("pcloud_access_token", "pcloud_token", "pcloud_auth_token", "pcloud_auth")
        for (k in keys) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = resolveToken(prefs) ?: return emptyList()
        val items = mutableListOf<CloudFileItem>()

        val urlStr = "https://api.pcloud.com/listfolder?folderid=0&auth=$token&recursive=1"
        val respText = executeGet(urlStr) ?: return emptyList()
        val root = attempt("parse pCloud folder items", silent = true) { JSONObject(respText) } ?: return emptyList()

        val metadata = root.optJSONObject("metadata") ?: root
        traverseMetadata(metadata, items)

        Log.d(TAG, "[PCloudScanner] Discovered ${items.size} backup items in pCloud")
        return items
    }

    private fun traverseMetadata(folderObj: JSONObject, items: MutableList<CloudFileItem>) {
        val contents = folderObj.optJSONArray("contents") ?: return
        for (i in 0 until contents.length()) {
            val item = contents.getJSONObject(i)
            val isFolder = item.optBoolean("isfolder", false)
            if (isFolder) {
                traverseMetadata(item, items)
            } else {
                val name = item.optString("name")
                val size = item.optLong("size", 0L)
                val fileId = item.optString("fileid")
                if (name.isNotBlank() && fileId.isNotBlank()) {
                    items.add(
                        CloudFileItem(
                            id = fileId,
                            name = name,
                            size = size,
                            provider = providerName
                        )
                    )
                }
            }
        }
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = resolveToken(prefs) ?: return null
        val linkUrl = "https://api.pcloud.com/getfilelink?fileid=${fileItem.id}&auth=$token"
        val respText = executeGet(linkUrl) ?: return null
        val linkRoot = attempt("parse pCloud link", silent = true) { JSONObject(respText) } ?: return null

        val hosts = linkRoot.optJSONArray("hosts")
        val path = linkRoot.optString("path")
        if (hosts != null && hosts.length() > 0 && path.isNotBlank()) {
            val host = hosts.getString(0)
            val downloadUrl = "https://$host$path"
            return executeGet(downloadUrl)
        }
        return null
    }

    private fun executeGet(urlStr: String): String? = attempt("pCloud HTTP GET", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[PCloudScanner] HTTP GET returned ${conn.responseCode} for $urlStr")
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
