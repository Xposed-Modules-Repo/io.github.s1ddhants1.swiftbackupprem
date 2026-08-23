package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.HookHandler
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.hook.hookTracked
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.loadClassFlexible
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hook and engine that automatically detects, decrypts, and reconstructs missing
 * `<packageName>.xml` metadata files for cloud-downloaded and orphaned backups.
 */
@Keep
object BackupRebuilderHook : HookHandler {

    private const val TAG = Consts.TAG
    private const val CONCEAL_ENTITY = "SwiftBackup_Entity"

    private fun logI(msg: String) { try { Log.i(TAG, "[BackupRebuilder] $msg") } catch (_: Throwable) {} }
    private fun logE(msg: String) { try { Log.e(TAG, "[BackupRebuilder] $msg") } catch (_: Throwable) {} }
    private fun logD(msg: String) { try { Log.d(TAG, "[BackupRebuilder] $msg") } catch (_: Throwable) {} }

    private val rebuildExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SBP-BackupRebuilder").apply { isDaemon = true }
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
        if (!prefs.enableDriveDiscovery) {
            logD("Backup Rebuilder is disabled by user preference")
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
        }, 2, java.util.concurrent.TimeUnit.SECONDS)
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

        module.hookTracked(isValidMethod).intercept { chain ->
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

        module.hookTracked(getMetadataMethod).intercept { chain ->
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

        val candidateUids = CloudDiscoveryHook.resolveCandidateUids(context, classLoader, targets)
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

        val candidateUids = CloudDiscoveryHook.resolveCandidateUids(context, classLoader, targets)
        val primaryUid = candidateUids.firstOrNull() ?: "default_uid"
        var totalRebuilt = 0

        accountsDir.listFiles { f -> f.isDirectory }?.forEach { account ->
            val appsLocalDir = File(account, "backups/apps/local")
            if (appsLocalDir.isDirectory) {
                appsLocalDir.listFiles { f -> f.isDirectory }?.forEach { pkgFolder ->
                    pkgFolder.listFiles { f -> f.isDirectory }?.forEach { backupDir ->
                        if (rebuildBackupDirectory(backupDir, pkgFolder.name, backupDir.name, classLoader, targets, primaryUid, context)) {
                            totalRebuilt++
                        }
                    }
                }
            }
        }

        if (totalRebuilt > 0) logI("Rebuilt $totalRebuilt missing backup metadata files across storage")
        return totalRebuilt
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
                val parts = extraFile.readText(StandardCharsets.UTF_8).trim().split(":::").filter { it.isNotBlank() }
                if (parts.size >= 3) {
                    val candidateUids = CloudDiscoveryHook.resolveCandidateUids(context, classLoader, targets)
                    for (candUid in candidateUids) {
                        try {
                            val key = deriveConcealKey(candUid)
                            val decExtraBytes = concealDecrypt(parts[2].trim(), key)
                            val decompressedJson = decompressZstdOrRaw(decExtraBytes, classLoader)
                            if (decompressedJson != null) {
                                val json = JSONObject(decompressedJson)
                                if (json.has("ssaid")) ssaid = json.optString("ssaid")
                                if (json.has("permissionStatesCsv")) permissionStatesCsv = json.optString("permissionStatesCsv")
                                if (json.has("notificationPolicyXml")) notificationPolicyXml = json.optString("notificationPolicyXml")
                                if (json.has("versionCode")) versionCode = json.optLong("versionCode", 1L)
                                if (json.has("versionName")) versionName = json.optString("versionName", "1.0")
                                resolvedUid = candUid
                                break
                            }
                        } catch (_: Throwable) {}
                    }
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
                        versionCode = info.longVersionCode
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

    fun deriveConcealKey(uid: String): ByteArray {
        val repeated = uid.repeat((32 / uid.length) + 1).take(32)
        return repeated.toByteArray(StandardCharsets.UTF_8)
    }

    fun concealDecrypt(base64Payload: String, key: ByteArray): ByteArray {
        val raw = Base64.getDecoder().decode(base64Payload.trim().replace("\n", "").replace("\r", ""))
        val version = raw[0]
        val cipherId = raw[1]
        val iv = raw.copyOfRange(2, 14)
        val cipherTextAndTag = raw.copyOfRange(14, raw.size)

        val aad = byteArrayOf(version, cipherId) + CONCEAL_ENTITY.toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(cipherTextAndTag)
    }

    fun concealEncrypt(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val aad = byteArrayOf(1, 2) + CONCEAL_ENTITY.toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val cipherTextAndTag = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val output = byteArrayOf(1, 2) + iv + cipherTextAndTag
        return Base64.getEncoder().encodeToString(output)
    }

    fun decompressZstdOrRaw(bytes: ByteArray, classLoader: ClassLoader): String? {
        try {
            val str = String(bytes, StandardCharsets.UTF_8)
            if (str.startsWith("{") && str.endsWith("}")) return str
        } catch (_: Throwable) {}

        return attempt("decompress Zstandard payload", silent = true) {
            val unb64 = Base64.getDecoder().decode(bytes)
            val zstdClass = loadClassFlexible(classLoader, "com.swiftapps.sba.SbaZstdNative") ?: return@attempt null
            val nativeInstance = zstdClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
            val decompressMethod = zstdClass.getDeclaredMethod("decompressZstdBytes", ByteArray::class.java)
            val decompressed = decompressMethod.invoke(nativeInstance, unb64) as? ByteArray
            decompressed?.let { String(it, StandardCharsets.UTF_8) }
        }
    }
}
