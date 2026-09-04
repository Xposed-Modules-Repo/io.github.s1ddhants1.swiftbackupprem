package io.github.s1ddhants1.swiftbackupprem.domain.usecase

import android.content.Context
import io.github.s1ddhants1.swiftbackupprem.util.BackupMigratorEngine

class MigrateBackupsUseCase {
    operator fun invoke(
        config: BackupMigratorEngine.MigrationConfig,
        context: Context
    ): BackupMigratorEngine.MigrationResult {
        return BackupMigratorEngine.migrate(config, context)
    }
}
