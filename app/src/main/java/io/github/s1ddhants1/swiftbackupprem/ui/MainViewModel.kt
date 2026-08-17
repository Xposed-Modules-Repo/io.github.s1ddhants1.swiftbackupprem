package io.github.s1ddhants1.swiftbackupprem.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainViewModel : ViewModel() {

    fun exportConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        prefs: PreferencesManager,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val json = JSONObject().apply {
                        put("enablePremium", prefs.enablePremium)
                        put("disableTelemetry", prefs.disableTelemetry)
                        put("customFirebaseApp", prefs.customFirebaseApp)
                        put("googleAppId", prefs.googleAppId)
                        put("googleApiKey", prefs.googleApiKey)
                        put("firebaseDatabaseUrl", prefs.firebaseDatabaseUrl)
                        put("gcmDefaultSenderId", prefs.gcmDefaultSenderId)
                        put("googleStorageBucket", prefs.googleStorageBucket)
                        put("projectId", prefs.projectId)
                        put("clientId", prefs.clientId)
                    }.toString(2)
                    outputStream.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open selected export destination")
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun importConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        prefs: PreferencesManager,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonStr = inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    parseAndApplyConfig(json, prefs)
                } ?: error("Could not open selected import file")
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    companion object {
        fun parseAndApplyConfig(json: JSONObject, prefs: PreferencesManager) {
            val isGoogleServices = json.has("client") && json.has("project_info")
            val hasSbpKeys = json.has("enablePremium") ||
                    json.has("disableTelemetry") ||
                    json.has("suppressTelemetry") ||
                    json.has("customFirebaseApp") ||
                    json.has("googleAppId") ||
                    json.has("projectId")

            if (!isGoogleServices && !hasSbpKeys) {
                throw IllegalArgumentException("Unrecognized or invalid configuration file format")
            }

            if (isGoogleServices) {
                prefs.customFirebaseApp = true
                GoogleServicesJson.applyToPrefs(json, prefs)
                return
            }

            // Extract all parsed values before mutating preferences
            val enablePremium = if (json.has("enablePremium")) json.optBoolean("enablePremium", true) else null
            val disableTelemetry = when {
                json.has("disableTelemetry") -> json.optBoolean("disableTelemetry", true)
                json.has("suppressTelemetry") -> json.optBoolean("suppressTelemetry", true)
                else -> null
            }
            val customFirebaseApp = if (json.has("customFirebaseApp")) json.optBoolean("customFirebaseApp", true) else null
            val googleAppId = if (json.has("googleAppId")) json.optString("googleAppId", "") else null
            val googleApiKey = if (json.has("googleApiKey")) json.optString("googleApiKey", "") else null
            val firebaseDatabaseUrl = if (json.has("firebaseDatabaseUrl")) json.optString("firebaseDatabaseUrl", "") else null
            val gcmDefaultSenderId = if (json.has("gcmDefaultSenderId")) json.optString("gcmDefaultSenderId", "") else null
            val googleStorageBucket = if (json.has("googleStorageBucket")) json.optString("googleStorageBucket", "") else null
            val projectId = if (json.has("projectId")) json.optString("projectId", "") else null
            val clientId = if (json.has("clientId")) json.optString("clientId", "") else null

            // Apply all extracted values to prefs
            enablePremium?.let { prefs.enablePremium = it }
            disableTelemetry?.let { prefs.disableTelemetry = it }
            customFirebaseApp?.let { prefs.customFirebaseApp = it }
            googleAppId?.let { prefs.googleAppId = it }
            googleApiKey?.let { prefs.googleApiKey = it }
            firebaseDatabaseUrl?.let { prefs.firebaseDatabaseUrl = it }
            gcmDefaultSenderId?.let { prefs.gcmDefaultSenderId = it }
            googleStorageBucket?.let { prefs.googleStorageBucket = it }
            projectId?.let { prefs.projectId = it }
            clientId?.let { prefs.clientId = it }
        }
    }
}
