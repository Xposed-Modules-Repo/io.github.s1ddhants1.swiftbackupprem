package io.github.s1ddhants1.swiftbackupprem.ui

import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImportExportTest {

    private val viewModel = MainViewModel()

    @Test
    fun parseAndApplyConfigImportsFullSbpJson() {
        val prefs = PreferencesManager(null)
        val json = JSONObject(
            """
            {
              "enablePremium": true,
              "disableTelemetry": false,
              "customFirebaseApp": true,
              "googleAppId": "1:99999:android:12345",
              "googleApiKey": "custom-key",
              "firebaseDatabaseUrl": "https://custom.firebaseio.com",
              "gcmDefaultSenderId": "99999",
              "googleStorageBucket": "custom.appspot.com",
              "projectId": "custom-project",
              "clientId": "custom-client-id"
            }
            """.trimIndent()
        )

        viewModel.parseAndApplyConfig(json, prefs)

        assertTrue(prefs.enablePremium)
        assertFalse(prefs.disableTelemetry)
        assertTrue(prefs.customFirebaseApp)
        assertEquals("1:99999:android:12345", prefs.googleAppId)
        assertEquals("custom-key", prefs.googleApiKey)
        assertEquals("https://custom.firebaseio.com", prefs.firebaseDatabaseUrl)
        assertEquals("99999", prefs.gcmDefaultSenderId)
        assertEquals("custom.appspot.com", prefs.googleStorageBucket)
        assertEquals("custom-project", prefs.projectId)
        assertEquals("custom-client-id", prefs.clientId)
    }

    @Test
    fun parseAndApplyConfigSupportsLegacySuppressTelemetryKey() {
        val prefs = PreferencesManager(null)
        val json = JSONObject(
            """
            {
              "suppressTelemetry": false,
              "enablePremium": true
            }
            """.trimIndent()
        )

        viewModel.parseAndApplyConfig(json, prefs)

        assertFalse(prefs.disableTelemetry)
        assertTrue(prefs.enablePremium)
    }

    @Test
    fun parseAndApplyConfigAppliesGoogleServicesJsonDirectly() {
        val prefs = PreferencesManager(null)
        val json = JSONObject(
            """
            {
              "project_info": {
                "firebase_url": "https://imported.firebaseio.com",
                "project_number": "777888",
                "storage_bucket": "imported.appspot.com",
                "project_id": "imported-project"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:777888:android:xyz"
                  },
                  "api_key": [
                    {
                      "current_key": "imported-key"
                    }
                  ]
                }
              ],
              "oauth_client_id": "imported-oauth-client"
            }
            """.trimIndent()
        )

        viewModel.parseAndApplyConfig(json, prefs)

        assertTrue(prefs.customFirebaseApp)
        assertEquals("1:777888:android:xyz", prefs.googleAppId)
        assertEquals("imported-key", prefs.googleApiKey)
        assertEquals("https://imported.firebaseio.com", prefs.firebaseDatabaseUrl)
        assertEquals("777888", prefs.gcmDefaultSenderId)
        assertEquals("imported.appspot.com", prefs.googleStorageBucket)
        assertEquals("imported-project", prefs.projectId)
        assertEquals("imported-oauth-client", prefs.clientId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseAndApplyConfigRejectsUnrecognizedJsonWithoutMutatingPrefs() {
        val prefs = PreferencesManager(null).apply {
            projectId = "original-project"
        }
        val json = JSONObject(
            """
            {
              "unknown_field": "some_value",
              "another_random_key": 123
            }
            """.trimIndent()
        )

        try {
            viewModel.parseAndApplyConfig(json, prefs)
        } finally {
            // Verify preferences were not mutated
            assertEquals("original-project", prefs.projectId)
        }
    }
}
