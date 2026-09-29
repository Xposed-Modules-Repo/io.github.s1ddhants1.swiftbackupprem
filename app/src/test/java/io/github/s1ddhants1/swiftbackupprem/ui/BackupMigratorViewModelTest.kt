package io.github.s1ddhants1.swiftbackupprem.ui

import android.content.ContextWrapper
import io.github.s1ddhants1.swiftbackupprem.R
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
        assertNull(state.errorMessageRes)
        assertNull(state.migrationResult)
    }

    @Test
    fun settersUpdateUiStateFieldsAndClearErrors() {
        val viewModel = BackupMigratorViewModel()

        viewModel.setSourcePath("/sdcard/SwiftBackup")
        assertEquals("/sdcard/SwiftBackup", viewModel.uiState.value.sourcePath)
        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.errorMessageRes)

        viewModel.setSourceUid("uid_abc123")
        assertEquals("uid_abc123", viewModel.uiState.value.sourceUid)
        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.errorMessageRes)

        viewModel.setTargetMode(TargetModeSelection.CUSTOM_UID)
        assertEquals(TargetModeSelection.CUSTOM_UID, viewModel.uiState.value.targetMode)

        viewModel.setCustomTargetUid("uid_xyz789")
        assertEquals("uid_xyz789", viewModel.uiState.value.customTargetUid)
        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.errorMessageRes)

        viewModel.setExportPortableFormats(true)
        assertTrue(viewModel.uiState.value.exportPortableFormats)

        viewModel.setTargetPath("/sdcard/Migrated")
        assertEquals("/sdcard/Migrated", viewModel.uiState.value.targetPath)
        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.errorMessageRes)

        viewModel.setSyncToFirebase(true)
        assertTrue(viewModel.uiState.value.syncToFirebase)
    }

    @Test
    fun startMigrationValidatesInputsAndSetsErrorResources() {
        val viewModel = BackupMigratorViewModel()
        val dummyContext = ContextWrapper(null)

        viewModel.startMigration(dummyContext)
        assertEquals(R.string.migrator_error_source_dir_invalid, viewModel.uiState.value.errorMessageRes)
        assertNotNull(viewModel.uiState.value.errorMessage)

        val tempDir = java.io.File.createTempFile("test_sbp", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            viewModel.setSourcePath(tempDir.absolutePath)
            viewModel.startMigration(dummyContext)
            assertEquals(R.string.migrator_error_source_uid_required, viewModel.uiState.value.errorMessageRes)

            viewModel.setSourceUid("test-uid")
            viewModel.startMigration(dummyContext)
            assertEquals(R.string.migrator_error_dest_path_required, viewModel.uiState.value.errorMessageRes)

            viewModel.setTargetPath(tempDir.absolutePath)
            viewModel.setTargetMode(TargetModeSelection.CUSTOM_UID)
            viewModel.setCustomTargetUid("")
            viewModel.startMigration(dummyContext)
            assertEquals(R.string.migrator_error_target_uid_required, viewModel.uiState.value.errorMessageRes)
        } finally {
            tempDir.deleteRecursively()
        }
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
