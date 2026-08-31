package io.github.s1ddhants1.swiftbackupprem.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.s1ddhants1.swiftbackupprem.domain.usecase.DetectCandidateUidsUseCase
import io.github.s1ddhants1.swiftbackupprem.domain.usecase.MigrateBackupsUseCase
import io.github.s1ddhants1.swiftbackupprem.domain.usecase.SyncFirebaseUseCase
import io.github.s1ddhants1.swiftbackupprem.util.BackupMigratorEngine
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class TargetModeSelection {
    ANONYMOUS,
    CUSTOM_UID,
    UNENCRYPTED,
    PORTABLE_STANDARD
}

data class BackupMigratorUiState(
    val sourcePath: String = "",
    val sourceUid: String = "",
    val detectedUids: List<String> = emptyList(),
    val targetMode: TargetModeSelection = TargetModeSelection.ANONYMOUS,
    val customTargetUid: String = "",
    val exportPortableFormats: Boolean = false,
    val targetPath: String = "",
    val syncToFirebase: Boolean = false,
    val isMigrating: Boolean = false,
    val isSyncingFirebase: Boolean = false,
    val currentStep: String = "",
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val currentItem: String = "",
    val logs: List<String> = emptyList(),
    val migrationResult: BackupMigratorEngine.MigrationResult? = null,
    val errorMessage: String? = null
)

sealed interface BackupMigratorUiEvent {
    data class FirebaseSyncResult(val totalSynced: Int, val error: String? = null) : BackupMigratorUiEvent
}

class BackupMigratorViewModel(
    private val detectCandidateUidsUseCase: DetectCandidateUidsUseCase = DetectCandidateUidsUseCase(),
    private val syncFirebaseUseCase: SyncFirebaseUseCase = SyncFirebaseUseCase(),
    private val migrateBackupsUseCase: MigrateBackupsUseCase = MigrateBackupsUseCase(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupMigratorUiState())
    val uiState: StateFlow<BackupMigratorUiState> = _uiState.asStateFlow()

    private val _events = Channel<BackupMigratorUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setSourcePath(path: String) {
        _uiState.update { it.copy(sourcePath = path, errorMessage = null) }
    }

    fun setSourceUid(uid: String) {
        _uiState.update { it.copy(sourceUid = uid, errorMessage = null) }
    }

    fun setTargetMode(mode: TargetModeSelection) {
        _uiState.update { it.copy(targetMode = mode) }
    }

    fun setExportPortableFormats(export: Boolean) {
        _uiState.update { it.copy(exportPortableFormats = export) }
    }

    fun setCustomTargetUid(uid: String) {
        _uiState.update { it.copy(customTargetUid = uid, errorMessage = null) }
    }

    fun setTargetPath(path: String) {
        _uiState.update { it.copy(targetPath = path, errorMessage = null) }
    }

    fun setSyncToFirebase(sync: Boolean) {
        _uiState.update { it.copy(syncToFirebase = sync) }
    }

    fun autoDetectSourceUids(context: Context) {
        viewModelScope.launch(ioDispatcher) {
            val uids = detectCandidateUidsUseCase(context, javaClass.classLoader)
            _uiState.update { state ->
                state.copy(detectedUids = uids)
            }
        }
    }

    fun syncFirebaseAll(context: Context, prefs: PreferencesManager) {
        if (_uiState.value.isSyncingFirebase) return
        _uiState.update { it.copy(isSyncingFirebase = true) }

        viewModelScope.launch(ioDispatcher) {
            val result = syncFirebaseUseCase(context, prefs)
            _uiState.update { it.copy(isSyncingFirebase = false) }
            _events.send(
                BackupMigratorUiEvent.FirebaseSyncResult(
                    totalSynced = result.totalSynced,
                    error = result.errors.firstOrNull()
                )
            )
        }
    }

    fun startMigration(context: Context, prefs: PreferencesManager? = null) {
        val state = _uiState.value
        val srcDir = File(state.sourcePath.trim())
        if (!srcDir.exists() || !srcDir.isDirectory) {
            _uiState.update { it.copy(errorMessage = "Source directory does not exist or is invalid.") }
            return
        }

        if (state.sourceUid.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Source Firebase UID is required to decrypt backups.") }
            return
        }

        if (state.targetPath.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Destination folder path is required.") }
            return
        }

        val targetMode = when (state.targetMode) {
            TargetModeSelection.ANONYMOUS -> BackupMigratorEngine.TargetEncryptionMode.Anonymous()
            TargetModeSelection.CUSTOM_UID -> {
                if (state.customTargetUid.isBlank()) {
                    _uiState.update { it.copy(errorMessage = "Target Firebase UID is required.") }
                    return
                }
                BackupMigratorEngine.TargetEncryptionMode.Custom(state.customTargetUid.trim())
            }
            TargetModeSelection.UNENCRYPTED -> {
                if (state.exportPortableFormats) BackupMigratorEngine.TargetEncryptionMode.PortableStandard
                else BackupMigratorEngine.TargetEncryptionMode.Unencrypted()
            }
            TargetModeSelection.PORTABLE_STANDARD -> BackupMigratorEngine.TargetEncryptionMode.PortableStandard
        }

        val outDir = File(state.targetPath.trim())

        _uiState.update {
            it.copy(
                isMigrating = true,
                errorMessage = null,
                migrationResult = null,
                logs = listOf("Initializing migration pipeline..."),
                processedCount = 0,
                totalCount = 0
            )
        }

        val shouldSync = (state.syncToFirebase || prefs?.syncMetadataToFirebase == true) && (prefs?.customFirebaseApp == true) && (prefs.firebaseDatabaseUrl.isNotBlank())

        viewModelScope.launch(ioDispatcher) {
            val config = BackupMigratorEngine.MigrationConfig(
                sourceDir = srcDir,
                sourceUid = state.sourceUid.trim(),
                targetMode = targetMode,
                targetDir = outDir,
                syncToFirebase = shouldSync,
                firebaseDbUrl = if (shouldSync) prefs.firebaseDatabaseUrl else null,
                firebaseApiKey = if (shouldSync) prefs.googleApiKey else null,
                onProgress = { progress ->
                    _uiState.update { s ->
                        val updatedLogs = if (progress.logMessage != null) s.logs + progress.logMessage else s.logs
                        s.copy(
                            currentStep = progress.currentStep,
                            processedCount = progress.processedItems,
                            totalCount = progress.totalItems,
                            currentItem = progress.currentFileName,
                            logs = updatedLogs.takeLast(100)
                        )
                    }
                }
            )

            val result = migrateBackupsUseCase(config, context)
            _uiState.update {
                it.copy(
                    isMigrating = false,
                    migrationResult = result,
                    logs = it.logs + "Migration finished. Total Apps: ${result.totalAppsMigrated}, Folders: ${result.totalFoldersMigrated}" +
                            if (result.totalSyncedToFirebase > 0) " (Synced to Firebase: ${result.totalSyncedToFirebase})" else ""
                )
            }
        }
    }

    fun dismissResult() {
        _uiState.update { it.copy(migrationResult = null) }
    }
}
