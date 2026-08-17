package io.github.s1ddhants1.swiftbackupprem.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleServicesJsonTest {

    @Test
    fun applyToPrefsReadsFirebaseProjectAndClientValues() {
        val prefs = PreferencesManager(null)
        val json = JSONObject(
            """
            {
              "project_info": {
                "firebase_url": "https://example.firebaseio.com",
                "project_number": "123456",
                "storage_bucket": "example.appspot.com",
                "project_id": "example"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:123456:android:abcdef"
                  },
                  "api_key": [
                    {
                      "current_key": "api-key"
                    }
                  ]
                }
              ],
              "oauth_client_id": "oauth-client"
            }
            """.trimIndent()
        )

        GoogleServicesJson.applyToPrefs(json, prefs)

        assertEquals("1:123456:android:abcdef", prefs.googleAppId)
        assertEquals("api-key", prefs.googleApiKey)
        assertEquals("https://example.firebaseio.com", prefs.firebaseDatabaseUrl)
        assertEquals("123456", prefs.gcmDefaultSenderId)
        assertEquals("example.appspot.com", prefs.googleStorageBucket)
        assertEquals("example", prefs.projectId)
        assertEquals("oauth-client", prefs.clientId)
    }

    @Test
    fun buildFromPrefsWritesExpectedFirebaseShape() {
        val prefs = PreferencesManager(null).apply {
            googleAppId = "app-id"
            googleApiKey = "api-key"
            firebaseDatabaseUrl = "https://example.firebaseio.com"
            gcmDefaultSenderId = "123456"
            googleStorageBucket = "example.appspot.com"
            projectId = "example"
            clientId = "oauth-client"
        }

        val json = GoogleServicesJson.buildFromPrefs(prefs)
        val client = json.getJSONArray("client").getJSONObject(0)
        val projectInfo = json.getJSONObject("project_info")

        assertEquals("app-id", client.getJSONObject("client_info").getString("mobilesdk_app_id"))
        assertEquals("api-key", client.getJSONArray("api_key").getJSONObject(0).getString("current_key"))
        assertEquals("https://example.firebaseio.com", projectInfo.getString("firebase_url"))
        assertEquals("123456", projectInfo.getString("project_number"))
        assertEquals("example.appspot.com", projectInfo.getString("storage_bucket"))
        assertEquals("example", projectInfo.getString("project_id"))
        assertEquals("oauth-client", json.getString("oauth_client_id"))
    }
}
