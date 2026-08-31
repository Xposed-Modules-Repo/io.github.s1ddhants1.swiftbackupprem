package io.github.s1ddhants1.swiftbackupprem.util

import io.github.s1ddhants1.swiftbackupprem.model.SbpConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMigrationSettingsTest {

    @Test
    fun migrationSettingsDefaultToDisabled() {
        val prefs = PreferencesManager(null)
        assertFalse(prefs.enableGoogleDriveScope)
        assertFalse(prefs.enableCloudDiscovery)
        assertFalse(prefs.enableSnapshotInjection)
        assertFalse(prefs.enableBackupRebuilder)
        assertFalse(prefs.syncMetadataToFirebase)
        assertFalse(prefs.customFirebaseApp)

        val config = SbpConfig()
        assertFalse(config.enableGoogleDriveScope)
        assertFalse(config.enableCloudDiscovery)
        assertFalse(config.enableSnapshotInjection)
        assertFalse(config.enableBackupRebuilder)
        assertFalse(config.syncMetadataToFirebase)
        assertFalse(config.customFirebaseApp)
    }

    @Test
    fun disablingCustomFirebaseForcesAllMigrationSettingsOffInApplyConfig() {
        val prefs = PreferencesManager(null)
        val configWithMigrationEnabled = SbpConfig(
            customFirebaseApp = false,
            enableGoogleDriveScope = true,
            enableCloudDiscovery = true,
            enableSnapshotInjection = true,
            enableBackupRebuilder = true,
            syncMetadataToFirebase = true
        )

        prefs.applyConfig(configWithMigrationEnabled)

        assertFalse(prefs.customFirebaseApp)
        assertFalse(prefs.enableGoogleDriveScope)
        assertFalse(prefs.enableCloudDiscovery)
        assertFalse(prefs.enableSnapshotInjection)
        assertFalse(prefs.enableBackupRebuilder)
        assertFalse(prefs.syncMetadataToFirebase)
    }

    @Test
    fun disablingCloudDiscoveryDisablesSnapshotInjectionInApplyConfig() {
        val prefs = PreferencesManager(null)
        val config = SbpConfig(
            customFirebaseApp = true,
            enableCloudDiscovery = false,
            enableSnapshotInjection = true
        )

        prefs.applyConfig(config)

        assertTrue(prefs.customFirebaseApp)
        assertFalse(prefs.enableCloudDiscovery)
        assertFalse(prefs.enableSnapshotInjection)
    }

    @Test
    fun firebaseSyncEngineFailsFastWhenSyncMetadataDisabled() {
        val prefs = PreferencesManager(null).apply {
            customFirebaseApp = true
            syncMetadataToFirebase = false
            firebaseDatabaseUrl = "https://test.firebaseio.com"
        }

        val result = FirebaseSyncEngine.syncAll(android.app.Application(), prefs)

        org.junit.Assert.assertEquals(0, result.totalSynced)
        assertTrue(result.errors.any { it.contains("disabled") })
    }
}
