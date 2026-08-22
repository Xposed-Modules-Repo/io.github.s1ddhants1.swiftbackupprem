package io.github.s1ddhants1.swiftbackupprem.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SbpConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun defaultValuesAreAsExpected() {
        val config = SbpConfig()
        assertTrue(config.enablePremium)
        assertTrue(config.disableTelemetry)
        assertFalse(config.enableDriveDiscovery)
        assertFalse(config.customFirebaseApp)
        assertEquals("", config.googleAppId)
        assertEquals("", config.googleApiKey)
        assertEquals("", config.firebaseDatabaseUrl)
        assertEquals("", config.gcmDefaultSenderId)
        assertEquals("", config.googleStorageBucket)
        assertEquals("", config.projectId)
        assertEquals("", config.clientId)
        assertFalse(config.isCompleteFirebaseConfig)
    }

    @Test
    fun isCompleteFirebaseConfigReturnsTrueWhenAllRequiredFieldsPresent() {
        val config = SbpConfig(
            googleAppId = "1:123:android:456",
            googleApiKey = "key",
            firebaseDatabaseUrl = "https://test.firebaseio.com",
            gcmDefaultSenderId = "123",
            projectId = "test-project",
            clientId = "test-client-id"
        )
        assertTrue(config.isCompleteFirebaseConfig)
    }

    @Test
    fun isCompleteFirebaseConfigReturnsFalseWhenAnyRequiredFieldIsBlank() {
        val config = SbpConfig(
            googleAppId = "1:123:android:456",
            googleApiKey = "",
            firebaseDatabaseUrl = "https://test.firebaseio.com",
            gcmDefaultSenderId = "123",
            projectId = "test-project",
            clientId = "test-client-id"
        )
        assertFalse(config.isCompleteFirebaseConfig)
    }

    @Test
    fun serializationAndDeserializationRoundTripMatches() {
        val original = SbpConfig(
            enablePremium = true,
            disableTelemetry = false,
            enableDriveDiscovery = true,
            customFirebaseApp = true,
            googleAppId = "app-id-123",
            googleApiKey = "api-key-456",
            firebaseDatabaseUrl = "https://db.firebaseio.com",
            gcmDefaultSenderId = "999",
            googleStorageBucket = "bucket.appspot.com",
            projectId = "my-project",
            clientId = "client-777"
        )

        val serialized = json.encodeToString(SbpConfig.serializer(), original)
        val deserialized = json.decodeFromString(SbpConfig.serializer(), serialized)

        assertEquals(original, deserialized)
    }
}
