package io.github.s1ddhants1.swiftbackupprem.ui

import org.junit.Assert.*
import org.junit.Test

class BackupMigratorViewModelTest {

    @Test
    fun defaultUiStateInitializesCorrectly() {
        val viewModel = BackupMigratorViewModel()
        val state = viewModel.uiState.value

        assertEquals("", state.sourcePath)
        assertEquals("", state.sourceUid)
        assertTrue(state.detectedUids.isEmpty())
        assertEquals(TargetModeSelection.ANONYMOUS, state.targetMode)
        assertEquals("", state.customTargetUid)
        assertFalse(state.exportPortableFormats)
        assertEquals("", state.targetPath)
        assertFalse(state.syncToFirebase)
        assertFalse(state.isMigrating)
        assertFalse(state.isSyncingFirebase)
        assertNull(state.errorMessage)
        assertNull(state.migrationResult)
    }

    @Test
    fun settersUpdateUiStateFields() {
        val viewModel = BackupMigratorViewModel()

        viewModel.setSourcePath("/sdcard/SwiftBackup")
        assertEquals("/sdcard/SwiftBackup", viewModel.uiState.value.sourcePath)

        viewModel.setSourceUid("uid_abc123")
        assertEquals("uid_abc123", viewModel.uiState.value.sourceUid)

        viewModel.setTargetMode(TargetModeSelection.CUSTOM_UID)
        assertEquals(TargetModeSelection.CUSTOM_UID, viewModel.uiState.value.targetMode)

        viewModel.setCustomTargetUid("uid_xyz789")
        assertEquals("uid_xyz789", viewModel.uiState.value.customTargetUid)

        viewModel.setExportPortableFormats(true)
        assertTrue(viewModel.uiState.value.exportPortableFormats)

        viewModel.setTargetPath("/sdcard/Migrated")
        assertEquals("/sdcard/Migrated", viewModel.uiState.value.targetPath)

        viewModel.setSyncToFirebase(true)
        assertTrue(viewModel.uiState.value.syncToFirebase)
    }

    @Test
    fun eventsHoldCorrectData() {
        val successEvent = BackupMigratorUiEvent.FirebaseSyncResult(totalSynced = 5, error = null)
        assertEquals(5, successEvent.totalSynced)
        assertNull(successEvent.error)

        val failEvent = BackupMigratorUiEvent.FirebaseSyncResult(totalSynced = 0, error = "Network failure")
        assertEquals(0, failEvent.totalSynced)
        assertEquals("Network failure", failEvent.error)
    }

    @Test
    fun dismissResultClearsMigrationResult() {
        val viewModel = BackupMigratorViewModel()
        viewModel.dismissResult()
        assertNull(viewModel.uiState.value.migrationResult)
    }
}
