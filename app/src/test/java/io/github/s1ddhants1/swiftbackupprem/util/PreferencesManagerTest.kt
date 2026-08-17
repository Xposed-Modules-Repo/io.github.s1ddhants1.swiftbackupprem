package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesManagerTest {

    @Test
    fun defaultValuesAreCorrectWhenPreferencesAreNull() {
        val prefs = PreferencesManager(null)

        assertTrue(prefs.enablePremium)
        assertTrue(prefs.disableTelemetry)
        assertFalse(prefs.customFirebaseApp)
        assertEquals("", prefs.googleAppId)
        assertEquals("", prefs.googleApiKey)
        assertEquals("", prefs.firebaseDatabaseUrl)
        assertEquals("", prefs.gcmDefaultSenderId)
        assertEquals("", prefs.googleStorageBucket)
        assertEquals("", prefs.projectId)
        assertEquals("", prefs.clientId)
    }

    @Test
    fun mutatingPreferencesUpdatesValuesInMemory() {
        val prefs = PreferencesManager(null)

        prefs.enablePremium = false
        prefs.disableTelemetry = false
        prefs.customFirebaseApp = true
        prefs.googleAppId = "test-app-id"
        prefs.googleApiKey = "test-api-key"
        prefs.firebaseDatabaseUrl = "https://test.firebaseio.com"
        prefs.gcmDefaultSenderId = "987654"
        prefs.googleStorageBucket = "test.appspot.com"
        prefs.projectId = "test-project"
        prefs.clientId = "test-client-id"

        assertFalse(prefs.enablePremium)
        assertFalse(prefs.disableTelemetry)
        assertTrue(prefs.customFirebaseApp)
        assertEquals("test-app-id", prefs.googleAppId)
        assertEquals("test-api-key", prefs.googleApiKey)
        assertEquals("https://test.firebaseio.com", prefs.firebaseDatabaseUrl)
        assertEquals("987654", prefs.gcmDefaultSenderId)
        assertEquals("test.appspot.com", prefs.googleStorageBucket)
        assertEquals("test-project", prefs.projectId)
        assertEquals("test-client-id", prefs.clientId)
    }
}
