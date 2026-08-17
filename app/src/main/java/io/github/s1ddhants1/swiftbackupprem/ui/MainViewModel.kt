package io.github.s1ddhants1.swiftbackupprem.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel : ViewModel() {

    fun exportConfig(contentResolver: ContentResolver, uri: Uri, prefs: PreferencesManager) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun importConfig(contentResolver: ContentResolver, uri: Uri, prefs: PreferencesManager) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonStr = inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)

                    if (json.has("enablePremium")) {
                        prefs.enablePremium = json.optBoolean("enablePremium", true)
                    }
                    if (json.has("disableTelemetry")) {
                        prefs.disableTelemetry = json.optBoolean("disableTelemetry", true)
                    } else if (json.has("suppressTelemetry")) {
                        prefs.disableTelemetry = json.optBoolean("suppressTelemetry", true)
                    }

                    if (json.has("customFirebaseApp") || json.has("googleAppId")) {
                        if (json.has("customFirebaseApp")) {
                            prefs.customFirebaseApp = json.optBoolean("customFirebaseApp", true)
                        }
                        prefs.googleAppId = json.optString("googleAppId", "")
                        prefs.googleApiKey = json.optString("googleApiKey", "")
                        prefs.firebaseDatabaseUrl = json.optString("firebaseDatabaseUrl", "")
                        prefs.gcmDefaultSenderId = json.optString("gcmDefaultSenderId", "")
                        prefs.googleStorageBucket = json.optString("googleStorageBucket", "")
                        prefs.projectId = json.optString("projectId", "")
                        prefs.clientId = json.optString("clientId", "")
                    } else if (json.has("client") && json.has("project_info")) {
                        prefs.customFirebaseApp = true
                        GoogleServicesJson.applyToPrefs(json, prefs)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
}
