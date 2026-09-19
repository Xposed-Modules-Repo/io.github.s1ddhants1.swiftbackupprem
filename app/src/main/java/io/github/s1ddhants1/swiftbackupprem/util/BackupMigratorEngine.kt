package io.github.s1ddhants1.swiftbackupprem.util

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32

object BackupMigratorEngine {

    const val SWIFT_BACKUP_ANONYMOUS_UID = "d58b0944415a4889d7f11aa95fbeca50"

    sealed class TargetEncryptionMode {
        data class Anonymous(val anonymousUid: String = SWIFT_BACKUP_ANONYMOUS_UID) : TargetEncryptionMode()
        data class Custom(val targetUid: String) : TargetEncryptionMode()
        data class Unencrypted(val targetAccountUid: String = SWIFT_BACKUP_ANONYMOUS_UID) : TargetEncryptionMode()
        data object PortableStandard : TargetEncryptionMode()

        val isEncrypted: Boolean
            get() = this is Anonymous || this is Custom

        val isPortable: Boolean
            get() = this is PortableStandard

        val resolvedUid: String
            get() = when (this) {
                is Anonymous -> anonymousUid
                is Custom -> targetUid
                is Unencrypted -> targetAccountUid
                is PortableStandard -> SWIFT_BACKUP_ANONYMOUS_UID
            }
    }

    data class MigrationConfig(
        val sourceDir: File,
        val sourceUid: String,
        val targetMode: TargetEncryptionMode = TargetEncryptionMode.Anonymous(),
        val targetDir: File,
        val syncToFirebase: Boolean = false,
        val firebaseDbUrl: String? = null,
        val firebaseApiKey: String? = null,
        val onProgress: ((MigrationProgress) -> Unit)? = null
    )

    data class MigrationProgress(
        val currentStep: String,
        val processedItems: Int,
        val totalItems: Int,
        val currentFileName: String,
        val logMessage: String? = null
    )

    data class MigrationResult(
        val success: Boolean,
        val totalAppsMigrated: Int,
        val totalFoldersMigrated: Int,
        val totalSyncedToFirebase: Int = 0,
        val targetAccountHash: String?,
        val outputDirectory: File,
        val logs: List<String>,
        val errors: List<String>
    )

    fun computeAccountHash(uid: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(uid.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun migrate(config: MigrationConfig, context: Context? = null): MigrationResult {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()

        fun log(msg: String) {
            logs.add(msg)
            config.onProgress?.invoke(
                MigrationProgress(
                    currentStep = "Migrating",
                    processedItems = 0,
                    totalItems = 0,
                    currentFileName = "",
                    logMessage = msg
                )
            )
        }

        if (!config.sourceDir.exists() || !config.sourceDir.isDirectory) {
            val err = "Source directory does not exist or is not a directory: ${config.sourceDir.absolutePath}"
            errors.add(err)
            return MigrationResult(false, 0, 0, 0, null, config.targetDir, logs, errors)
        }

        val sourceKey = BackupCrypto.deriveConcealKey(config.sourceUid)
        val isEncrypted = config.targetMode.isEncrypted
        val isPortable = config.targetMode.isPortable
        val targetUid = if (isEncrypted) config.targetMode.resolvedUid else null
        val targetKey = targetUid?.let { BackupCrypto.deriveConcealKey(it) }
        val targetAccountHash = if (isPortable) "portable" else computeAccountHash(config.targetMode.resolvedUid)

        val targetBaseDir = if (isPortable) {
            File(config.targetDir, "ExtractedBackups")
        } else {
            File(config.targetDir, "SwiftBackup/accounts/$targetAccountHash/backups")
        }
        val appsTargetBase = if (isPortable) File(targetBaseDir, "apps") else File(targetBaseDir, "apps/local")
        val foldersTargetBase = if (isPortable) File(targetBaseDir, "folders") else File(targetBaseDir, "folders/local")

        appsTargetBase.mkdirs()
        foldersTargetBase.mkdirs()

        log("Starting migration. Mode: ${when {
            isPortable -> "Standard Portable Files (.apk, .tar, .json)"
            isEncrypted -> "Encrypted ($targetUid)"
            else -> "Unencrypted (Swift Backup Plaintext)"
        }}")
        if (!isPortable) log("Target Account Hash: $targetAccountHash")
        log("Source directory: ${config.sourceDir.absolutePath}")
        log("Target directory: ${targetBaseDir.absolutePath}")

        var totalApps = 0
        var totalFolders = 0
        var totalSynced = 0

        val appBackupDirs = findAppBackupDirs(config.sourceDir)
        val folderBackupDirs = findFolderBackupDirs(config.sourceDir)
        val totalWork = appBackupDirs.size + folderBackupDirs.size

        var completedWork = 0

        val authCreds = if (config.syncToFirebase && !config.firebaseDbUrl.isNullOrBlank()) {
            FirebaseSyncEngine.resolveAuthCredentials(context, PreferencesManager(null).apply {
                googleApiKey = config.firebaseApiKey ?: ""
                firebaseDatabaseUrl = config.firebaseDbUrl
            })
        } else null
        val idToken = authCreds?.idToken

        for ((pkgName, backupDir) in appBackupDirs) {
            val backupId = backupDir.name
            val destBackupDir = if (isPortable) File(appsTargetBase, pkgName) else File(appsTargetBase, "$pkgName/$backupId")
            destBackupDir.mkdirs()

            config.onProgress?.invoke(
                MigrationProgress(
                    currentStep = "Processing Apps",
                    processedItems = completedWork,
                    totalItems = totalWork,
                    currentFileName = "$pkgName ($backupId)",
                    logMessage = "Processing app $pkgName..."
                )
            )

            val migrated = processAppBackup(
                backupDir = backupDir,
                destBackupDir = destBackupDir,
                pkgName = pkgName,
                backupId = backupId,
                sourceUid = config.sourceUid,
                sourceKey = sourceKey,
                targetUid = targetUid,
                targetKey = targetKey,
                isPortable = isPortable,
                syncToFirebase = config.syncToFirebase,
                firebaseDbUrl = config.firebaseDbUrl,
                idToken = idToken,
                onSynced = { totalSynced++ },
                context = context,
                log = ::log,
                error = { errors.add(it) }
            )

            if (migrated) totalApps++
            completedWork++
        }

        for (folderDir in folderBackupDirs) {
            val folderName = folderDir.name
            val destFolderDir = File(foldersTargetBase, folderName)
            destFolderDir.mkdirs()

            config.onProgress?.invoke(
                MigrationProgress(
                    currentStep = "Processing Folders",
                    processedItems = completedWork,
                    totalItems = totalWork,
                    currentFileName = folderName,
                    logMessage = "Processing folder $folderName..."
                )
            )

            val migrated = processFolderBackup(
                folderDir = folderDir,
                destFolderDir = destFolderDir,
                folderName = folderName,
                sourceUid = config.sourceUid,
                sourceKey = sourceKey,
                targetUid = targetUid,
                targetKey = targetKey,
                isPortable = isPortable,
                syncToFirebase = config.syncToFirebase,
                firebaseDbUrl = config.firebaseDbUrl,
                idToken = idToken,
                onSynced = { totalSynced++ },
                log = ::log,
                error = { errors.add(it) }
            )

            if (migrated) totalFolders++
            completedWork++
        }

        log("Migration finished! Successfully processed $totalApps apps and $totalFolders folders.")

        return MigrationResult(
            success = errors.isEmpty() || (totalApps + totalFolders > 0),
            totalAppsMigrated = totalApps,
            totalFoldersMigrated = totalFolders,
            totalSyncedToFirebase = totalSynced,
            targetAccountHash = targetAccountHash,
            outputDirectory = targetBaseDir,
            logs = logs,
            errors = errors
        )
    }

    fun findAppBackupDirs(sourceDir: File): List<Pair<String, File>> {
        val results = mutableListOf<Pair<String, File>>()

        fun isBackupDir(dir: File): Boolean {
            val files = dir.listFiles() ?: return false
            return files.any { f ->
                f.name.endsWith(".app") || f.name.endsWith(".apk") || f.name.endsWith(".dat") ||
                        f.name.endsWith(".extdat") || f.name.endsWith(".med") || f.name.endsWith(".xml") ||
                        f.name.endsWith(".extra") || f.name.endsWith(".splits")
            }
        }

        sourceDir.walkTopDown().maxDepth(20).filter { it.isDirectory }.forEach { dir ->
            if (isBackupDir(dir)) {
                val parentPkg = dir.parentFile?.name ?: ""
                val pkgName = if (AppUtils.isValidPackageName(parentPkg)) {
                    parentPkg
                } else if (AppUtils.isValidPackageName(dir.name)) {
                    dir.name
                } else {
                    val slice = dir.listFiles()?.firstOrNull { f ->
                        f.name.endsWith(".app") || f.name.endsWith(".apk") || f.name.endsWith(".dat") ||
                                f.name.endsWith(".xml") || f.name.endsWith(".extra") || f.name.endsWith(".splits")
                    }
                    val cand = slice?.name?.substringBeforeLast('.') ?: dir.name
                    if (AppUtils.isValidPackageName(cand)) cand else (if (cand.contains('.')) cand else dir.name)
                }
                if (pkgName.isNotBlank()) {
                    results.add(pkgName to dir)
                }
            }
        }

        return results.distinctBy { it.second.absolutePath }
    }

    fun findFolderBackupDirs(sourceDir: File): List<File> {
        val results = mutableListOf<File>()

        sourceDir.walkTopDown().maxDepth(20).filter { it.isDirectory }.forEach { dir ->
            val hasFolderSlices = dir.listFiles()?.any {
                it.name.startsWith("folder-base.") || it.name == "metadata.json" || it.name.endsWith(".fld") || it.name.endsWith(".flm")
            } == true
            if (dir.name.startsWith("Folder-") || hasFolderSlices) {
                results.add(dir)
            }
        }

        return results.distinctBy { it.absolutePath }
    }

    private fun processAppBackup(
        backupDir: File,
        destBackupDir: File,
        pkgName: String,
        backupId: String,
        sourceUid: String,
        sourceKey: ByteArray,
        targetUid: String?,
        targetKey: ByteArray?,
        isPortable: Boolean = false,
        syncToFirebase: Boolean = false,
        firebaseDbUrl: String? = null,
        idToken: String? = null,
        onSynced: (() -> Unit)? = null,
        context: Context?,
        log: (String) -> Unit,
        error: (String) -> Unit
    ): Boolean {
        try {
            val files = backupDir.listFiles() ?: return false
            var appName = pkgName
            var versionCode = 1L
            var versionName = "1.0"
            var ssaid: String? = null
            var permissionStatesCsv: String? = null
            var notificationPolicyXml: String? = null
            var existingMetaJson: JSONObject? = null

            val apkFile = files.firstOrNull { it.name.endsWith(".app") || it.name.endsWith(".apk") }
            if (apkFile != null && context != null) {
                attempt("read apk metadata", silent = true) {
                    val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                    if (info != null) {
                        versionCode = PackageInfoCompat.getLongVersionCode(info)
                        if (!info.versionName.isNullOrBlank()) versionName = info.versionName!!
                        val appInfo = info.applicationInfo
                        if (appInfo != null) {
                            appInfo.sourceDir = apkFile.absolutePath
                            appInfo.publicSourceDir = apkFile.absolutePath
                            appName = context.packageManager.getApplicationLabel(appInfo).toString().ifBlank { pkgName }
                        }
                    }
                }
            }

            val classLoader = BackupMigratorEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

            val extraFile = files.firstOrNull { it.name.endsWith(".extra") }
            if (extraFile != null && extraFile.length() > 0) {
                attempt("decrypt .extra payload", silent = true) {
                    val extraText = extraFile.readText(StandardCharsets.UTF_8)
                    val parts = extraText.split(":::").filter { it.isNotBlank() }
                    if (parts.size >= 3) {
                        val decBytes = BackupCrypto.concealDecrypt(parts[2], sourceKey)
                        val decompJson = BackupCrypto.decompressZstdOrRaw(decBytes, classLoader)
                        if (decompJson != null) {
                            val j = JSONObject(decompJson)
                            ssaid = j.optString("ssaid").takeIf { it.isNotBlank() }
                            permissionStatesCsv = j.optString("permissionStatesCsv").takeIf { it.isNotBlank() }
                            notificationPolicyXml = j.optString("notificationPolicyXml").takeIf { it.isNotBlank() }
                            if (j.has("versionCode")) versionCode = j.optLong("versionCode", versionCode)
                            if (j.has("versionName")) versionName = j.optString("versionName", versionName)
                        }

                        val destExtra = if (isPortable) File(destBackupDir, "${pkgName}_extras.json") else File(destBackupDir, "$pkgName.extra")
                        if (targetUid != null && targetKey != null) {
                            val encUid = BackupCrypto.concealEncrypt(targetUid, targetKey)
                            val encPayload = BackupCrypto.concealEncrypt(Base64Wrapper.encodeToString(decBytes), targetKey)
                            destExtra.writeText("v1:::$encUid:::$encPayload", StandardCharsets.UTF_8)
                        } else {
                            destExtra.writeText(decompJson ?: String(decBytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                        }
                        destExtra.setReadable(true, false)
                        destExtra.setWritable(true, false)
                    }
                }
            }

            val xmlFile = files.firstOrNull { it.name.endsWith(".xml") }
            if (xmlFile != null && xmlFile.length() > 0) {
                attempt("decrypt .xml metadata", silent = true) {
                    val xmlText = xmlFile.readText(StandardCharsets.UTF_8)
                    val parts = xmlText.split(":::").filter { it.isNotBlank() }
                    if (parts.size >= 3) {
                        val decBytes = BackupCrypto.concealDecrypt(parts[2], sourceKey)
                        val decStr = String(decBytes, StandardCharsets.UTF_8)
                        existingMetaJson = JSONObject(decStr)
                        existingMetaJson.let { meta ->
                            if (meta.has("name")) appName = meta.optString("name", appName)
                            if (meta.has("versionCode")) versionCode = meta.optLong("versionCode", versionCode)
                            if (meta.has("versionName")) versionName = meta.optString("versionName", versionName)
                        }
                    }
                }
            }

            files.forEach { file ->
                if (!file.name.endsWith(".xml") && !file.name.endsWith(".extra")) {
                    val isSbaCandidate = file.name.endsWith(".dat") || file.name.endsWith(".extdat")
                            || file.name.endsWith(".med") || file.name.endsWith(".splits")
                    val destFile = if (isPortable && isSbaCandidate && Sba1Parser.isSba1File(file)) {
                        processSba1ForPortable(file, destBackupDir, pkgName)
                    } else {
                        val destFileName = if (isPortable) resolvePortableFileName(pkgName, file.name) else file.name
                        val dest = File(destBackupDir, destFileName)
                        if (targetUid == null && (file.name.endsWith(".dat") || file.name.endsWith(".extdat") || file.name.endsWith(".med"))) {
                            try {
                                val rawBytes = file.readBytes()
                                val decBytes = BackupCrypto.concealDecryptRawBytes(rawBytes, sourceKey)
                                dest.writeBytes(decBytes)
                            } catch (_: Throwable) {
                                file.copyTo(dest, overwrite = true)
                            }
                        } else {
                            file.copyTo(dest, overwrite = true)
                        }
                        dest
                    }
                    destFile.setReadable(true, false)
                    destFile.setWritable(true, false)
                }
            }

            val now = System.currentTimeMillis()
            val metaJson = (existingMetaJson ?: JSONObject()).apply {
                put("packageName", pkgName)
                put("name", appName)
                put("versionCode", versionCode)
                put("versionName", versionName)
                if (!has("dateBackup")) put("dateBackup", now)
                put("dateBackupUpdated", now)
                put("minSBVersionCodeRequired", 580L)
                put("keyVersion", 1)

                val sliceDefinitions = listOf(
                    Triple("app", "apkBackupDate", "apkBackupSize"),
                    Triple("dat", "dataBackupDate", "dataBackupSize"),
                    Triple("extdat", "extDataBackupDate", "extDataBackupSize"),
                    Triple("med", "mediaBackupDate", "mediaBackupSize")
                )

                sliceDefinitions.forEach { (suffix, dateKey, sizeKey) ->
                    val sliceFile = findDestSliceFile(destBackupDir, pkgName, suffix, isPortable)
                    if (sliceFile != null) {
                        if (!has(dateKey)) put(dateKey, now)
                        put(sizeKey, sliceFile.length())
                        if (suffix != "app") {
                            val prefix = if (suffix == "dat") "data" else if (suffix == "extdat") "extData" else "media"
                            val capPrefix = prefix.replaceFirstChar { it.uppercase() }
                            if (targetUid != null) {
                                put("is${capPrefix}Encrypted", true)
                                put("${prefix}EncryptionMethod", "StandardEncryption")
                                put("${prefix}SBVersionCodeRequired", 580L)
                                put("${prefix}SBVersionNameRequired", "v4.2.3")
                            } else {
                                put("is${capPrefix}Encrypted", false)
                                remove("${prefix}EncryptionMethod")
                            }
                        }
                    }
                }

                val splitsFile = findDestSliceFile(destBackupDir, pkgName, "splits", isPortable)
                if (splitsFile != null) {
                    put("splitsBackupSize", splitsFile.length())
                }

                ssaid?.let { put("ssaid", it) }
                permissionStatesCsv?.let { put("permissionStatesCsv", it) }
                notificationPolicyXml?.let { put("notificationPolicyXml", it) }
            }

            val destXml = if (isPortable) File(destBackupDir, "${pkgName}_metadata.json") else File(destBackupDir, "$pkgName.xml")
            if (targetUid != null && targetKey != null) {
                val encUid = BackupCrypto.concealEncrypt(targetUid, targetKey)
                val encMeta = BackupCrypto.concealEncrypt(metaJson.toString(), targetKey)
                destXml.writeText("v1:::$encUid:::$encMeta", StandardCharsets.UTF_8)
            } else {
                destXml.writeText(metaJson.toString(2), StandardCharsets.UTF_8)
            }
            destXml.setReadable(true, false)
            destXml.setWritable(true, false)

            if (syncToFirebase && !firebaseDbUrl.isNullOrBlank()) {
                val syncUid = targetUid ?: sourceUid
                val ok = FirebaseSyncEngine.syncAppMetadata(
                    firebaseDbUrl = firebaseDbUrl,
                    uid = syncUid,
                    pkgName = pkgName,
                    backupId = backupId,
                    metadataJson = metaJson,
                    idToken = idToken
                )
                if (ok) {
                    onSynced?.invoke()
                    log("Synced metadata to Custom Firebase: $pkgName")
                }
            }

            log("Migrated app: $pkgName ($backupId)")
            return true
        } catch (t: Throwable) {
            val err = "Failed to process app $pkgName: ${t.message}"
            error(err)
            return false
        }
    }

    private fun processFolderBackup(
        folderDir: File,
        destFolderDir: File,
        folderName: String,
        sourceUid: String,
        sourceKey: ByteArray,
        targetUid: String?,
        targetKey: ByteArray?,
        isPortable: Boolean = false,
        syncToFirebase: Boolean = false,
        firebaseDbUrl: String? = null,
        idToken: String? = null,
        onSynced: (() -> Unit)? = null,
        log: (String) -> Unit,
        error: (String) -> Unit
    ): Boolean {
        try {
            val files = folderDir.listFiles() ?: return false
            val cleanId = folderName.removePrefix("Folder-").ifBlank { "custom_folder" }
            var sourcePath = "/storage/emulated/0"
            var displayName = folderName
            var created = System.currentTimeMillis()
            val classLoader = BackupMigratorEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

            val flmFile = files.firstOrNull { it.name.endsWith(".flm") }
            if (flmFile != null && flmFile.length() > 0) {
                attempt("decrypt .flm manifest", silent = true) {
                    val rawFlmText = flmFile.readText(StandardCharsets.UTF_8)
                    val parts = rawFlmText.split(":::").filter { it.isNotBlank() }
                    if (parts.size >= 3) {
                        val decBytes = BackupCrypto.concealDecrypt(parts[2], sourceKey)
                        val decompJson = BackupCrypto.decompressZstdOrRaw(decBytes, classLoader)
                        if (decompJson != null) {
                            val j = JSONObject(decompJson)
                            sourcePath = j.optString("sourcePath", sourcePath)
                            displayName = j.optString("displayName", displayName)
                            created = j.optLong("created", created)
                        }

                        val destFlm = if (isPortable) File(destFolderDir, "${folderName}_manifest.json") else File(destFolderDir, "folder-base.flm")
                        if (targetUid != null && targetKey != null) {
                            val encUid = BackupCrypto.concealEncrypt(targetUid, targetKey)
                            val encPayload = BackupCrypto.concealEncrypt(Base64Wrapper.encodeToString(decBytes), targetKey)
                            destFlm.writeText("v1:::$encUid:::$encPayload", StandardCharsets.UTF_8)
                        } else {
                            destFlm.writeText(decompJson ?: String(decBytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                        }
                        destFlm.setReadable(true, false)
                        destFlm.setWritable(true, false)
                    }
                }
            }

            files.forEach { file ->
                if (!file.name.endsWith(".flm") && file.name != "metadata.json") {
                    val destFile = if (isPortable && file.name == "folder-base.fld" && Sba1Parser.isSba1File(file)) {
                        val info = Sba1Parser.parse(file)
                        if (info != null && !info.header.isEncrypted) {
                            val ext = if (info.header.isZstdCompressed) ".tar.zst" else ".tar"
                            val dest = File(destFolderDir, "$folderName$ext")
                            Sba1Parser.extractFirstEntryPayload(file, dest)
                            dest
                        } else {
                            val dest = File(destFolderDir, "$folderName.sba")
                            file.copyTo(dest, overwrite = true)
                            dest
                        }
                    } else {
                        val destFileName = if (isPortable && file.name == "folder-base.fld") "$folderName.tar" else file.name
                        val dest = File(destFolderDir, destFileName)
                        if (targetUid == null && file.name == "folder-base.fld") {
                            try {
                                val rawBytes = file.readBytes()
                                val decBytes = BackupCrypto.concealDecryptRawBytes(rawBytes, sourceKey)
                                dest.writeBytes(decBytes)
                            } catch (_: Throwable) {
                                file.copyTo(dest, overwrite = true)
                            }
                        } else {
                            file.copyTo(dest, overwrite = true)
                        }
                        dest
                    }
                    destFile.setReadable(true, false)
                    destFile.setWritable(true, false)
                }
            }

            val destFld = File(destFolderDir, "folder-base.fld")
            val destFlm = File(destFolderDir, "folder-base.flm")
            val fldSize = if (destFld.exists()) destFld.length() else 0L
            val flmSize = if (destFlm.exists()) destFlm.length() else 0L
            val tsFormat = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
            val tsStr = tsFormat.format(java.util.Date(created))

            val metaJson = JSONObject().apply {
                put("folderItem", JSONObject().apply {
                    put("id", cleanId)
                    put("displayName", displayName)
                    put("sourceFolder", sourcePath)
                    put("setupCreationTime", created)
                })
                put("baseBackup", JSONObject().apply {
                    put("backupLink", destFld.absolutePath)
                    put("backupSize", fldSize)
                    put("manifestLink", destFlm.absolutePath)
                    put("manifestSize", flmSize)
                    put("originalSize", fldSize)
                    put("timestamp", tsStr)
                })
            }

            val metaFile = File(destFolderDir, "metadata.json")
            metaFile.writeText(metaJson.toString(2), StandardCharsets.UTF_8)
            metaFile.setReadable(true, false)
            metaFile.setWritable(true, false)

            if (syncToFirebase && !firebaseDbUrl.isNullOrBlank()) {
                val syncUid = targetUid ?: sourceUid
                val ok = FirebaseSyncEngine.syncFolderMetadata(
                    firebaseDbUrl = firebaseDbUrl,
                    uid = syncUid,
                    folderId = cleanId,
                    metadataJson = metaJson,
                    idToken = idToken
                )
                if (ok) {
                    onSynced?.invoke()
                    log("Synced folder metadata to Custom Firebase: $cleanId")
                }
            }

            log("Migrated folder: $folderName")
            return true
        } catch (t: Throwable) {
            val err = "Failed to process folder $folderName: ${t.message}"
            error(err)
            return false
        }
    }

    private fun resolvePortableFileName(pkgName: String, fileName: String): String = when {
        fileName.endsWith(".app") || fileName.endsWith(".apk") -> "$pkgName.apk"
        fileName.endsWith(".splits") -> "${pkgName}_splits.tar"
        fileName.endsWith(".dat") -> "${pkgName}_data.tar"
        fileName.endsWith(".extdat") -> "${pkgName}_external_data.tar"
        fileName.endsWith(".med") -> "${pkgName}_media.tar"
        fileName.endsWith(".cls") -> "${pkgName}_call_logs.json"
        fileName.endsWith(".msg") -> "${pkgName}_sms_messages.json"
        fileName.endsWith(".wfi") -> "${pkgName}_wifi.json"
        fileName.endsWith(".wal") -> "${pkgName}_wallpaper.png"
        else -> fileName
    }

    private fun processSba1ForPortable(file: File, destDir: File, pkgName: String): File {
        val info = Sba1Parser.parse(file)
        val suffix = portableSuffix(file.name)

        if (info != null && !info.header.isEncrypted) {
            val ext = if (info.header.isZstdCompressed) ".tar.zst" else ".tar"
            val dest = File(destDir, "${pkgName}$suffix$ext")
            Sba1Parser.extractFirstEntryPayload(file, dest)
            return dest
        }

        val dest = File(destDir, "${pkgName}$suffix.sba")
        file.copyTo(dest, overwrite = true)
        return dest
    }

    private fun portableSuffix(fileName: String): String = when {
        fileName.endsWith(".splits") || fileName == "splits" -> "_splits"
        fileName.endsWith(".dat") || fileName == "dat" -> "_data"
        fileName.endsWith(".extdat") || fileName == "extdat" -> "_external_data"
        fileName.endsWith(".med") || fileName == "med" -> "_media"
        else -> ""
    }

    private fun findDestSliceFile(destDir: File, pkgName: String, suffix: String, isPortable: Boolean): File? {
        if (!isPortable) {
            val f = File(destDir, "$pkgName.$suffix")
            return if (f.exists()) f else null
        }
        val defaultName = resolvePortableFileName(pkgName, "$pkgName.$suffix")
        val defaultFile = File(destDir, defaultName)
        if (defaultFile.exists()) return defaultFile
        val pfx = portableSuffix(suffix)
        val zstFile = File(destDir, "${pkgName}$pfx.tar.zst")
        if (zstFile.exists()) return zstFile
        val sbaFile = File(destDir, "${pkgName}$pfx.sba")
        if (sbaFile.exists()) return sbaFile
        return null
    }

    object Sba1Parser {
        private val SBA1_MAGIC = byteArrayOf(0x53, 0x42, 0x41, 0x31)
        private val FOOTER_MAGIC = byteArrayOf(0x53, 0x41, 0x46, 0x31)
        private val INDEX_MAGIC = byteArrayOf(0x53, 0x41, 0x49, 0x31)
        private const val FOOTER_SIZE = 32
        private const val V1_HEADER_SIZE = 96
        private const val V2_HEADER_SIZE = 144

        data class SbaHeader(
            val version: Int,
            val headerSize: Int,
            val compressionMethod: Int,
            val encryptionMethod: Int
        ) {
            val isEncrypted: Boolean get() = encryptionMethod != 0
            val isZstdCompressed: Boolean get() = compressionMethod == 1
        }

        data class SbaFooter(
            val indexOffset: Long,
            val indexSize: Long,
            val indexCrc32: Int
        )

        data class SbaEntry(
            val name: String,
            val entryHeaderOffset: Long,
            val payloadOffset: Long,
            val storedSize: Long,
            val compressedSize: Long,
            val tarSize: Long
        )

        data class SbaArchiveInfo(
            val header: SbaHeader,
            val footer: SbaFooter,
            val entries: List<SbaEntry>
        )

        fun isSba1File(file: File): Boolean {
            if (file.length() < FOOTER_SIZE + V1_HEADER_SIZE) return false
            return file.inputStream().use { stream ->
                val magic = ByteArray(4)
                stream.read(magic) == 4 && magic.contentEquals(SBA1_MAGIC)
            }
        }

        fun isSba1Bytes(raw: ByteArray): Boolean {
            return raw.size >= 4 &&
                    raw[0] == 0x53.toByte() &&
                    raw[1] == 0x42.toByte() &&
                    raw[2] == 0x41.toByte() &&
                    raw[3] == 0x31.toByte()
        }

        fun parse(file: File): SbaArchiveInfo? = attempt("parse SBA1 archive", silent = true) {
            RandomAccessFile(file, "r").use { raf ->
                val header = parseHeader(raf) ?: return@attempt null
                val footer = parseFooter(raf, file.length(), header.version) ?: return@attempt null
                val indexBytes = readAndVerifyIndex(raf, footer) ?: return@attempt null
                val entries = parseIndex(indexBytes, header.version) ?: return@attempt null
                SbaArchiveInfo(header, footer, entries)
            }
        }

        fun extractFirstEntryPayload(sourceFile: File, destFile: File): String? =
            attempt("extract SBA1 payload", silent = true) {
                val info = parse(sourceFile) ?: return@attempt null
                if (info.header.isEncrypted) return@attempt null
                val entry = info.entries.firstOrNull() ?: return@attempt null

                RandomAccessFile(sourceFile, "r").use { raf ->
                    raf.seek(entry.payloadOffset)
                    val payloadSize = if (info.header.isZstdCompressed) entry.compressedSize else entry.storedSize
                    destFile.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var remaining = payloadSize
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val read = raf.read(buffer, 0, toRead)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }

                if (info.header.isZstdCompressed) ".tar.zst" else ".tar"
            }

        private fun parseHeader(raf: RandomAccessFile): SbaHeader? {
            raf.seek(0)
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!magic.contentEquals(SBA1_MAGIC)) return null

            val version = raf.readUnsignedShort()
            if (version !in 1..2) return null
            val headerSize = raf.readUnsignedShort()
            val expectedSize = if (version == 1) V1_HEADER_SIZE else V2_HEADER_SIZE
            if (headerSize != expectedSize) return null

            raf.readInt()
            raf.readLong()

            val compressionMethod = raf.readUnsignedShort()
            raf.readUnsignedShort()
            val encryptionMethod = raf.readUnsignedShort()

            return SbaHeader(version, headerSize, compressionMethod, encryptionMethod)
        }

        private fun parseFooter(raf: RandomAccessFile, fileSize: Long, headerVersion: Int): SbaFooter? {
            if (fileSize < FOOTER_SIZE) return null
            val footerStart = fileSize - FOOTER_SIZE
            raf.seek(footerStart)

            val footerBytes = ByteArray(FOOTER_SIZE)
            raf.readFully(footerBytes)

            val storedCrc = readInt32BE(footerBytes, 28)
            val crc32 = CRC32()
            crc32.update(footerBytes, 0, 28)
            if (storedCrc != crc32.value.toInt()) return null

            val footerMagic = footerBytes.copyOfRange(0, 4)
            if (!footerMagic.contentEquals(FOOTER_MAGIC)) return null

            val footerDataSize = readUShort16BE(footerBytes, 4)
            if (footerDataSize != FOOTER_SIZE) return null
            val footerVersion = readUShort16BE(footerBytes, 6)
            if (footerVersion != headerVersion) return null

            val indexOffset = readLong64BE(footerBytes, 8)
            val indexSize = readLong64BE(footerBytes, 16)
            val indexCrc32 = readInt32BE(footerBytes, 24)

            return SbaFooter(indexOffset, indexSize, indexCrc32)
        }

        private fun readAndVerifyIndex(raf: RandomAccessFile, footer: SbaFooter): ByteArray? {
            if (footer.indexSize <= 0 || footer.indexSize > 10 * 1024 * 1024) return null
            raf.seek(footer.indexOffset)
            val indexBytes = ByteArray(footer.indexSize.toInt())
            raf.readFully(indexBytes)

            val crc32 = CRC32()
            crc32.update(indexBytes)
            if (footer.indexCrc32 != crc32.value.toInt()) return null

            return indexBytes
        }

        private fun parseIndex(indexBytes: ByteArray, archiveVersion: Int): List<SbaEntry>? {
            if (indexBytes.size < 16) return null

            val magic = indexBytes.copyOfRange(0, 4)
            if (!magic.contentEquals(INDEX_MAGIC)) return null

            val headerSize = readUShort16BE(indexBytes, 4)
            if (headerSize != 16) return null
            val indexVersion = readUShort16BE(indexBytes, 6)
            if (indexVersion !in 1..2) return null

            val entryCount = readInt32BE(indexBytes, 8)
            if (entryCount < 0) return null
            val metadataLength = readInt32BE(indexBytes, 12)
            if (metadataLength < 0) return null

            var offset = 16 + metadataLength

            val fixedRecordSize = if (indexVersion >= 2) 108 else 84
            val entries = mutableListOf<SbaEntry>()

            for (i in 0 until entryCount) {
                if (offset + fixedRecordSize > indexBytes.size) return null

                val entryHeaderOffset = readLong64BE(indexBytes, offset)
                val payloadOffset = readLong64BE(indexBytes, offset + 8)
                val storedSize = readLong64BE(indexBytes, offset + 16)
                val compressedSize = readLong64BE(indexBytes, offset + 24)
                val tarSize = readLong64BE(indexBytes, offset + 32)
                val nameLength = readUShort16BE(indexBytes, offset + 44)
                val nameStart = offset + (if (indexVersion >= 2) 108 else 84)
                if (nameStart + nameLength > indexBytes.size) return null

                val name = String(indexBytes, nameStart, nameLength, Charsets.UTF_8)
                entries.add(SbaEntry(name, entryHeaderOffset, payloadOffset, storedSize, compressedSize, tarSize))
                offset = nameStart + nameLength
            }

            return entries
        }

        private fun readUShort16BE(bytes: ByteArray, off: Int): Int =
            ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

        private fun readInt32BE(bytes: ByteArray, off: Int): Int =
            ((bytes[off].toInt() and 0xFF) shl 24) or
            ((bytes[off + 1].toInt() and 0xFF) shl 16) or
            ((bytes[off + 2].toInt() and 0xFF) shl 8) or
            (bytes[off + 3].toInt() and 0xFF)

        private fun readLong64BE(bytes: ByteArray, off: Int): Long =
            ((bytes[off].toLong() and 0xFF) shl 56) or
            ((bytes[off + 1].toLong() and 0xFF) shl 48) or
            ((bytes[off + 2].toLong() and 0xFF) shl 40) or
            ((bytes[off + 3].toLong() and 0xFF) shl 32) or
            ((bytes[off + 4].toLong() and 0xFF) shl 24) or
            ((bytes[off + 5].toLong() and 0xFF) shl 16) or
            ((bytes[off + 6].toLong() and 0xFF) shl 8) or
            (bytes[off + 7].toLong() and 0xFF)
    }
}

typealias Sba1Parser = BackupMigratorEngine.Sba1Parser

object Base64Wrapper {
    fun encodeToString(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun decode(base64: String): ByteArray = java.util.Base64.getDecoder().decode(base64.trim().replace("\n", "").replace("\r", ""))
}
