package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.pm.PackageInfoCompat
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.HookHandler
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.hook.hookTracked
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.loadClassFlexible
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Hook and engine that automatically detects, decrypts, and reconstructs missing
 * `<packageName>.xml` metadata files for cloud-downloaded and orphaned backups.
 */
@Keep
object BackupRebuilderHook : HookHandler {

    private const val TAG = Consts.TAG

    private fun logI(msg: String) { try { Log.i(TAG, "[BackupRebuilder] $msg") } catch (_: Throwable) {} }
    private fun logE(msg: String) { try { Log.e(TAG, "[BackupRebuilder] $msg") } catch (_: Throwable) {} }
    private fun logD(msg: String) { try { Log.d(TAG, "[BackupRebuilder] $msg") } catch (_: Throwable) {} }

    @Volatile
    private var rebuildExecutor: ScheduledExecutorService = createRebuildExecutor()

    private fun createRebuildExecutor(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "SBP-BackupRebuilder").apply { isDaemon = true }
        }

    fun shutdown() {
        try {
            rebuildExecutor.shutdownNow()
        } catch (_: Throwable) {}
        rebuildExecutor = createRebuildExecutor()
    }

    private data class BackupSlice(
        val suffix: String,
        val dateKey: String? = null,
        val sizeKey: String,
        val encryptedKey: String? = null,
        val encryptionMethodKey: String? = null
    )

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (!prefs.customFirebaseApp || !prefs.enableDriveDiscovery) {
            logD("Backup Rebuilder is disabled (requires custom Firebase app and Drive discovery)")
            return
        }

        val appBackupClass = targets.appBackupClass ?: attempt("load hk class", silent = true) {
            loadClassFlexible(classLoader, "defpackage.hk")
        }
        logD("Applying BackupRebuilderHook (appBackupClass: ${appBackupClass?.name})")

        if (appBackupClass != null) {
            hookAppBackupValidity(module, appBackupClass, classLoader, targets)
            hookAppBackupMetadata(module, appBackupClass, classLoader, targets)
        }

        rebuildExecutor.schedule({
            try {
                rebuildAllLocalBackups(context, classLoader, targets)
            } catch (t: Throwable) {
                logE("Startup backup scan error: ${t.message}")
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun hookAppBackupValidity(
        module: XposedModule,
        appBackupClass: Class<*>,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val isValidMethod = attempt("find hk.E", silent = true) {
            appBackupClass.declaredMethods.firstOrNull {
                it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.isEmpty() && it.name == "E"
            }
        } ?: return

        module.hookTracked(
            isValidMethod,
            idPrefix = "backup-rebuilder-validity",
            deoptimize = true
        ).intercept { chain ->
            chain.thisObject?.let { backupInstance ->
                attempt("auto-rebuild on isValid check", silent = true) {
                    rebuildFromBackupInstance(backupInstance, classLoader, targets)
                }
            }
            chain.proceed()
        }
    }

    private fun hookAppBackupMetadata(
        module: XposedModule,
        appBackupClass: Class<*>,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val getMetadataMethod = attempt("find hk.u", silent = true) {
            appBackupClass.declaredMethods.firstOrNull {
                it.parameterTypes.isEmpty() && (it.returnType.name.contains("LocalMetadata") || it.name == "u")
            }
        } ?: return

        module.hookTracked(
            getMetadataMethod,
            idPrefix = "backup-rebuilder-metadata"
        ).intercept { chain ->
            val initialResult = chain.proceed()
            if (initialResult == null && chain.thisObject != null) {
                val repaired = attempt("auto-rebuild on getMetadata", silent = true) {
                    rebuildFromBackupInstance(chain.thisObject, classLoader, targets)
                }
                if (repaired == true) return@intercept chain.proceed()
            }
            initialResult
        }
    }

    fun resolveAppLabel(context: Context?, pkgName: String, backupDir: File? = null): String {
        if (context != null) {
            attempt("resolve label for installed $pkgName", silent = true) {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                if (label.isNotBlank()) return label
            }
            if (backupDir != null && backupDir.exists()) {
                val apkFile = File(backupDir, "$pkgName.app").takeIf { it.exists() }
                    ?: File(backupDir, "$pkgName.apk").takeIf { it.exists() }
                if (apkFile != null) {
                    attempt("resolve label from apk $pkgName", silent = true) {
                        val pm = context.packageManager
                        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                        val appInfo = info?.applicationInfo
                        if (appInfo != null) {
                            appInfo.sourceDir = apkFile.absolutePath
                            appInfo.publicSourceDir = apkFile.absolutePath
                            val label = pm.getApplicationLabel(appInfo).toString()
                            if (label.isNotBlank()) return label
                        }
                    }
                }
            }
        }
        return pkgName
    }

    fun rebuildFromBackupInstance(
        backupInstance: Any,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        context: Context? = null
    ): Boolean = attempt("rebuildFromBackupInstance", silent = true) {
        val backupId = backupInstance.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(backupInstance) as? String ?: return false
        val pkgName = backupInstance.javaClass.getDeclaredField("b").apply { isAccessible = true }.get(backupInstance) as? String ?: return false

        val accountsDir = File(Environment.getExternalStorageDirectory(), "SwiftBackup/accounts")
        if (!accountsDir.exists()) return false

        val candidateUids = BackupCrypto.resolveCandidateUids(context, classLoader, targets)
        val primaryUid = candidateUids.firstOrNull() ?: "default_uid"
        var rebuilt = false

        accountsDir.listFiles { file -> file.isDirectory }?.forEach { accountFolder ->
            val backupDir = File(accountFolder, "backups/apps/local/$pkgName/$backupId")
            if (backupDir.isDirectory && rebuildBackupDirectory(backupDir, pkgName, backupId, classLoader, targets, primaryUid, context)) {
                rebuilt = true
            }
        }
        rebuilt
    } ?: false

    fun rebuildAllLocalBackups(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ): Int {
        val accountsDir = File(Environment.getExternalStorageDirectory(), "SwiftBackup/accounts")
        if (!accountsDir.isDirectory) return 0

        val candidateUids = BackupCrypto.resolveCandidateUids(context, classLoader, targets)
        val primaryUid = candidateUids.firstOrNull() ?: "default_uid"
        var totalRebuilt = 0

        accountsDir.listFiles { f -> f.isDirectory }?.forEach { account ->
            val appsLocalDir = File(account, "backups/apps/local")
            if (appsLocalDir.isDirectory) {
                appsLocalDir.listFiles { f -> f.isDirectory }?.forEach { pkgFolder ->
                    if (!AppUtils.isValidPackageName(pkgFolder.name)) {
                        logD("Skipping non-package directory: ${pkgFolder.name}")
                        return@forEach
                    }
                    pkgFolder.listFiles { f -> f.isDirectory }?.forEach { backupDir ->
                        if (rebuildBackupDirectory(backupDir, pkgFolder.name, backupDir.name, classLoader, targets, primaryUid, context)) {
                            totalRebuilt++
                        }
                    }
                }
            }

            val foldersLocalDir = File(account, "backups/folders/local")
            if (foldersLocalDir.isDirectory) {
                foldersLocalDir.listFiles { f -> f.isDirectory }?.forEach { folderDir ->
                    if (rebuildFolderDirectory(folderDir, folderDir.name)) {
                        totalRebuilt++
                    }
                }
            }
        }

        if (totalRebuilt > 0) logI("Rebuilt $totalRebuilt missing backup metadata files across storage")
        return totalRebuilt
    }

    @SuppressLint("SetWorldReadable", "SetWorldWritable")
    fun rebuildFolderDirectory(folderDir: File, folderDirName: String): Boolean {
        val metaFile = File(folderDir, "metadata.json")
        if (metaFile.exists() && metaFile.length() > 0) return false

        val fldFile = File(folderDir, "folder-base.fld")
        val flmFile = File(folderDir, "folder-base.flm")
        if (!fldFile.exists() && !flmFile.exists()) return false

        logI("Found folder backup without metadata.json at ${folderDir.absolutePath}. Auto-reconstructing metadata...")

        val cleanId = folderDirName.removePrefix("Folder-")
        val fldSize = if (fldFile.exists()) fldFile.length() else 0L
        val flmSize = if (flmFile.exists()) flmFile.length() else 0L
        val now = System.currentTimeMillis()
        val tsFormat = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
        val tsStr = tsFormat.format(java.util.Date(now))

        val metaJson = JSONObject().apply {
            put("folderItem", JSONObject().apply {
                put("id", cleanId)
                put("displayName", "Folder-$cleanId")
                put("sourceFolder", "/storage/emulated/0")
                put("setupCreationTime", now)
            })
            put("baseBackup", JSONObject().apply {
                put("backupLink", fldFile.absolutePath)
                put("backupSize", fldSize)
                put("manifestLink", flmFile.absolutePath)
                put("manifestSize", flmSize)
                put("originalSize", fldSize)
                put("timestamp", tsStr)
            })
        }

        try {
            metaFile.writeText(metaJson.toString(2), StandardCharsets.UTF_8)
            metaFile.setReadable(true, false)
            metaFile.setWritable(true, false)
            logI("Successfully generated metadata.json for folder $cleanId")
            return true
        } catch (t: Throwable) {
            logE("Failed to write metadata.json for folder $cleanId: ${t.message}")
            return false
        }
    }

    @SuppressLint("SetWorldReadable", "SetWorldWritable")
    fun rebuildBackupDirectory(
        backupDir: File,
        pkgName: String,
        backupId: String,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        activeUid: String,
        context: Context? = null
    ): Boolean {
        val xmlFile = File(backupDir, "$pkgName.xml")
        if (xmlFile.exists() && xmlFile.length() > 0) return false

        val slices = listOf(
            BackupSlice("app", "apkBackupDate", "apkBackupSize"),
            BackupSlice("dat", "dataBackupDate", "dataBackupSize", "isDataEncrypted", "dataEncryptionMethod"),
            BackupSlice("extdat", "extDataBackupDate", "extDataBackupSize", "isExtDataEncrypted", "extDataEncryptionMethod"),
            BackupSlice("splits", sizeKey = "splitsBackupSize"),
            BackupSlice("med", "mediaBackupDate", "mediaBackupSize", "isMediaEncrypted", "mediaEncryptionMethod")
        ).map { it to File(backupDir, "$pkgName.${it.suffix}") }
        val extraFile = File(backupDir, "$pkgName.extra")

        if (slices.none { (_, file) -> file.exists() } && !extraFile.exists()) return false

        logI("Found backup slices without .xml at ${backupDir.absolutePath}. Auto-reconstructing metadata...")

        var ssaid: String? = null
        var permissionStatesCsv: String? = null
        var notificationPolicyXml: String? = null
        var resolvedUid = activeUid
        var versionCode = 1L
        var versionName = "1.0"

        if (extraFile.exists() && extraFile.length() > 0) {
            attempt("decrypt .extra file") {
                val candidateUids = BackupCrypto.resolveCandidateUids(context, classLoader, targets)
                val extra = BackupCrypto.parseExtraPayload(extraFile.readText(StandardCharsets.UTF_8), candidateUids, classLoader)
                if (extra != null) {
                    ssaid = extra.ssaid
                    permissionStatesCsv = extra.permissionStatesCsv
                    notificationPolicyXml = extra.notificationPolicyXml
                    versionCode = extra.versionCode
                    versionName = extra.versionName
                    resolvedUid = extra.resolvedUid
                }
            }
        }

        if (context != null) {
            val apkFile = File(backupDir, "$pkgName.app").takeIf { it.exists() }
                ?: File(backupDir, "$pkgName.apk").takeIf { it.exists() }
            if (apkFile != null) {
                attempt("read version info from apk", silent = true) {
                    val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                    if (info != null) {
                        versionCode = PackageInfoCompat.getLongVersionCode(info)
                        if (!info.versionName.isNullOrBlank()) versionName = info.versionName!!
                    }
                }
            }
        }

        val appName = resolveAppLabel(context, pkgName, backupDir)
        val now = System.currentTimeMillis()
        val metaJson = JSONObject().apply {
            put("packageName", pkgName)
            put("name", appName)
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("dateBackup", now)
            put("dateBackupUpdated", now)
            put("minSBVersionCodeRequired", 580L)
            put("keyVersion", 1)

            slices.forEach { (slice, file) ->
                if (file.exists()) {
                    slice.dateKey?.let { put(it, now) }
                    put(slice.sizeKey, file.length())
                    if (slice.encryptedKey != null) {
                        put(slice.encryptedKey, true)
                        put(slice.encryptionMethodKey!!, "StandardEncryption")
                        val reqPrefix = when (slice.suffix) {
                            "app" -> "apk"
                            "dat" -> "data"
                            "extdat" -> "extData"
                            "med" -> "media"
                            else -> slice.suffix
                        }
                        put("${reqPrefix}SBVersionCodeRequired", 580L)
                        put("${reqPrefix}SBVersionNameRequired", "v4.2.3")
                    }
                }
            }

            ssaid?.let { put("ssaid", it) }
            permissionStatesCsv?.let { put("permissionStatesCsv", it) }
            notificationPolicyXml?.let { put("notificationPolicyXml", it) }
        }

        val key = deriveConcealKey(resolvedUid)
        val encUid = concealEncrypt(resolvedUid, key)
        val encMeta = concealEncrypt(metaJson.toString(), key)

        xmlFile.writeText("v1:::$encUid:::$encMeta", StandardCharsets.UTF_8)
        xmlFile.setReadable(true, false)
        xmlFile.setWritable(true, false)

        logI("Successfully generated $pkgName.xml for $appName ($pkgName / $backupId)")
        return true
    }

    fun deriveConcealKey(uid: String): ByteArray = BackupCrypto.deriveConcealKey(uid)
    fun concealDecrypt(base64Payload: String, key: ByteArray): ByteArray = BackupCrypto.concealDecrypt(base64Payload, key)
    fun concealEncrypt(plaintext: String, key: ByteArray): String = BackupCrypto.concealEncrypt(plaintext, key)
    fun decompressZstdOrRaw(bytes: ByteArray, classLoader: ClassLoader): String? = BackupCrypto.decompressZstdOrRaw(bytes, classLoader)
}
