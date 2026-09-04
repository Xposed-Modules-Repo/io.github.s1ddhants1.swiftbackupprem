package io.github.s1ddhants1.swiftbackupprem.domain.usecase

import io.github.s1ddhants1.swiftbackupprem.util.BackupMigratorEngine
import io.github.s1ddhants1.swiftbackupprem.util.FirebaseSyncEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UseCaseInstantiationTest {

    @Test
    fun useCasesCanBeInstantiated() {
        val detectUidsUseCase = DetectCandidateUidsUseCase()
        val syncFirebaseUseCase = SyncFirebaseUseCase()
        val migrateBackupsUseCase = MigrateBackupsUseCase()

        assertNotNull(detectUidsUseCase)
        assertNotNull(syncFirebaseUseCase)
        assertNotNull(migrateBackupsUseCase)
    }

    @Test
    fun migrationConfigHoldsExpectedParameters() {
        val src = File("/tmp/src")
        val dst = File("/tmp/dst")
        val config = BackupMigratorEngine.MigrationConfig(
            sourceDir = src,
            sourceUid = "test_uid_123",
            targetMode = BackupMigratorEngine.TargetEncryptionMode.Anonymous(),
            targetDir = dst,
            syncToFirebase = false
        )

        assertEquals(src, config.sourceDir)
        assertEquals("test_uid_123", config.sourceUid)
        assertEquals(dst, config.targetDir)
        assertFalse(config.syncToFirebase)
    }

    @Test
    fun syncResultHoldsExpectedValues() {
        val result = FirebaseSyncEngine.SyncResult(
            totalSynced = 10,
            totalAlreadyExisting = 2,
            totalFailed = 0,
            errors = emptyList()
        )

        assertEquals(10, result.totalSynced)
        assertEquals(2, result.totalAlreadyExisting)
        assertEquals(0, result.totalFailed)
        assertTrue(result.errors.isEmpty())
    }
}
