package io.github.s1ddhants1.swiftbackupprem.util

import io.github.s1ddhants1.swiftbackupprem.Consts
import org.json.JSONArray
import org.json.JSONObject

object GoogleServicesJson {

    /** Parse a google-services.json and apply Firebase values to [prefs]. */
    fun applyToPrefs(json: JSONObject, prefs: PreferencesManager) {
        if (json.has("client")) {
            val clientArray = json.getJSONArray("client")
            if (clientArray.length() > 0) {
                val clientObj = clientArray.getJSONObject(0)
                clientObj.optJSONObject("client_info")?.let { clientInfo ->
                    prefs.googleAppId = clientInfo.optString("mobilesdk_app_id", "")
                }
                clientObj.optJSONArray("api_key")?.let { apiKeyArray ->
                    if (apiKeyArray.length() > 0) {
                        prefs.googleApiKey = apiKeyArray.getJSONObject(0).optString("current_key", "")
                    }
                }
            }
        }
        if (json.has("project_info")) {
            val projInfo = json.getJSONObject("project_info")
            prefs.firebaseDatabaseUrl = projInfo.optString("firebase_url", "")
            prefs.gcmDefaultSenderId = projInfo.optString("project_number", "")
            prefs.googleStorageBucket = projInfo.optString("storage_bucket", "")
            prefs.projectId = projInfo.optString(Consts.projectId, "")
        }
        if (json.has(Consts.oauthClientId)) {
            prefs.clientId = json.optString(Consts.oauthClientId, "")
        }
    }

    /** Build a google-services.json [JSONObject] from [prefs] values. */
    fun buildFromPrefs(prefs: PreferencesManager): JSONObject = JSONObject().apply {
        put("client", JSONArray().apply {
            put(JSONObject().apply {
                put("client_info", JSONObject().apply { put("mobilesdk_app_id", prefs.googleAppId) })
                put("api_key", JSONArray().apply { put(JSONObject().apply { put("current_key", prefs.googleApiKey) }) })
            })
        })
        put("project_info", JSONObject().apply {
            put("firebase_url", prefs.firebaseDatabaseUrl)
            put("project_number", prefs.gcmDefaultSenderId)
            put("storage_bucket", prefs.googleStorageBucket)
            put(Consts.projectId, prefs.projectId)
        })
        put(Consts.oauthClientId, prefs.clientId)
    }
}
