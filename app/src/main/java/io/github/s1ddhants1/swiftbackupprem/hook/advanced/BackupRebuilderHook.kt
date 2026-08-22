package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.hook.HookHandler
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
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
 *
 * This enables 100% predictable, 1-click restoration of past cloud backups
 * without relying on dirty workarounds or fragile manual steps.
 */
@Keep
object BackupRebuilderHook : HookHandler {

    private const val TAG = "SBP"
    private const val CONCEAL_ENTITY = "SwiftBackup_Entity"

    private fun logI(msg: String) {
        try {
            Log.i(TAG, "[BackupRebuilder] $msg")
        } catch (_: Throwable) {
            println("[$TAG] [BackupRebuilder] $msg")
        }
    }

    private fun logE(msg: String) {
        try {
            Log.e(TAG, "[BackupRebuilder] $msg")
        } catch (_: Throwable) {
            System.err.println("[$TAG] [BackupRebuilder] $msg")
        }
    }

    private fun logD(msg: String) {
        try {
            Log.d(TAG, "[BackupRebuilder] $msg")
        } catch (_: Throwable) {
            println("[$TAG] [BackupRebuilder] $msg")
        }
    }

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

        // Hook hk.E() (backup validity check) to trigger auto-rebuild if .xml is missing
        val appBackupClass = targets.appBackupClass ?: attempt("load hk class", silent = true) {
            loadClassFlexible(classLoader, "defpackage.hk")
        }

        logD("Applying BackupRebuilderHook (appBackupClass: ${appBackupClass?.name})")

        if (appBackupClass != null) {
            hookAppBackupValidity(module, appBackupClass, classLoader, targets)
            hookAppBackupMetadata(module, appBackupClass, classLoader, targets)
        }

        // Run an asynchronous background scan on startup to repair all existing backup folders
        Thread {
            try {
                Thread.sleep(2000)
                rebuildAllLocalBackups(context, classLoader, targets)
            } catch (t: Throwable) {
                logE("Startup backup scan error: ${t.message}")
            }
        }.start()
    }

    /**
     * Hooks `hk.E()` (boolean check for whether a backup directory is valid).
     * If the XML file is missing but data/apk slices exist, auto-rebuilds the XML before proceeding.
     */
    private fun hookAppBackupValidity(
        module: XposedModule,
        appBackupClass: Class<*>,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val isValidMethod = attempt("find hk.E", silent = true) {
            appBackupClass.declaredMethods.firstOrNull {
                it.returnType == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes.isEmpty() &&
                        it.name == "E"
            }
        } ?: return

        module.hook(isValidMethod).intercept { chain ->
            val backupInstance = chain.thisObject
            if (backupInstance != null) {
                attempt("auto-rebuild on isValid check", silent = true) {
                    rebuildFromBackupInstance(backupInstance, classLoader, targets)
                }
            }
            chain.proceed()
        }
    }

    /**
     * Hooks `hk.u()` (retrieves LocalMetadata).
     * If the metadata is null/missing, attempts auto-rebuild and reload.
     */
    private fun hookAppBackupMetadata(
        module: XposedModule,
        appBackupClass: Class<*>,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val getMetadataMethod = attempt("find hk.u", silent = true) {
            appBackupClass.declaredMethods.firstOrNull {
                it.parameterTypes.isEmpty() &&
                        (it.returnType.name.contains("LocalMetadata") || it.name == "u")
            }
        } ?: return

        module.hook(getMetadataMethod).intercept { chain ->
            val initialResult = chain.proceed()
            if (initialResult == null && chain.thisObject != null) {
                val repaired = attempt("auto-rebuild on getMetadata", silent = true) {
                    rebuildFromBackupInstance(chain.thisObject, classLoader, targets)
                }
                if (repaired == true) {
                    return@intercept chain.proceed()
                }
            }
            initialResult
        }
    }

    /**
     * Rebuilds metadata for a single `hk` backup model instance.
     */
    fun rebuildFromBackupInstance(
        backupInstance: Any,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ): Boolean {
        return try {
            val backupIdField = backupInstance.javaClass.getDeclaredField("a").apply { isAccessible = true }
            val pkgNameField = backupInstance.javaClass.getDeclaredField("b").apply { isAccessible = true }

            val backupId = backupIdField.get(backupInstance) as? String ?: return false
            val pkgName = pkgNameField.get(backupInstance) as? String ?: return false

            val accountsDir = File(Environment.getExternalStorageDirectory(), "SwiftBackup/accounts")
            if (!accountsDir.exists()) return false

            val candidateUids = CloudDiscoveryHook.resolveCandidateUids(null, classLoader, targets)
            val primaryUid = candidateUids.firstOrNull() ?: "default_uid"

            var rebuilt = false
            accountsDir.listFiles { file -> file.isDirectory }?.forEach { accountFolder ->
                val backupDir = File(accountFolder, "backups/apps/local/$pkgName/$backupId")
                if (backupDir.exists() && backupDir.isDirectory) {
                    if (rebuildBackupDirectory(backupDir, pkgName, backupId, classLoader, targets, primaryUid)) {
                        rebuilt = true
                    }
                }
            }
            rebuilt
        } catch (t: Throwable) {
            Log.d(TAG, "rebuildFromBackupInstance skipped: ${t.message}")
            false
        }
    }

    /**
     * Scans and rebuilds all local backup directories across all accounts.
     */
    fun rebuildAllLocalBackups(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ): Int {
        val accountsDir = File(Environment.getExternalStorageDirectory(), "SwiftBackup/accounts")
        if (!accountsDir.exists() || !accountsDir.isDirectory) return 0

        val candidateUids = CloudDiscoveryHook.resolveCandidateUids(context, classLoader, targets)
        val primaryUid = candidateUids.firstOrNull() ?: "default_uid"
        var totalRebuilt = 0

        accountsDir.listFiles { f -> f.isDirectory }?.forEach { accountFolder ->
            val appsLocalDir = File(accountFolder, "backups/apps/local")
            if (appsLocalDir.exists() && appsLocalDir.isDirectory) {
                appsLocalDir.listFiles { f -> f.isDirectory }?.forEach { pkgFolder ->
                    val pkgName = pkgFolder.name
                    pkgFolder.listFiles { f -> f.isDirectory }?.forEach { backupDir ->
                        val backupId = backupDir.name
                        if (rebuildBackupDirectory(backupDir, pkgName, backupId, classLoader, targets, primaryUid)) {
                            totalRebuilt++
                        }
                    }
                }
            }
        }

        if (totalRebuilt > 0) {
            logI("Rebuilt $totalRebuilt missing backup metadata files across storage")
        }
        return totalRebuilt
    }

    /**
     * Inspects a specific backup folder and creates `<pkgName>.xml` if missing.
     */
    fun rebuildBackupDirectory(
        backupDir: File,
        pkgName: String,
        backupId: String,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        activeUid: String
    ): Boolean {
        val xmlFile = File(backupDir, "$pkgName.xml")
        if (xmlFile.exists() && xmlFile.length() > 0) {
            return false // Already valid
        }

        val appFile = File(backupDir, "$pkgName.app")
        val datFile = File(backupDir, "$pkgName.dat")
        val extDatFile = File(backupDir, "$pkgName.extdat")
        val splitsFile = File(backupDir, "$pkgName.splits")
        val extraFile = File(backupDir, "$pkgName.extra")
        val medFile = File(backupDir, "$pkgName.med")

        val hasAnySlice = appFile.exists() || datFile.exists() || extDatFile.exists() ||
                splitsFile.exists() || extraFile.exists() || medFile.exists()

        if (!hasAnySlice) {
            return false
        }

        logI("Found backup slices without .xml at ${backupDir.absolutePath}. Auto-reconstructing metadata...")

        var ssaid: String? = null
        var permissionStatesCsv: String? = null
        var notificationPolicyXml: String? = null
        var resolvedUid = activeUid

        // If .extra exists, decrypt it using Conceal AES-GCM + Zstandard
        if (extraFile.exists() && extraFile.length() > 0) {
            attempt("decrypt .extra file") {
                val extraContent = extraFile.readText(StandardCharsets.UTF_8).trim()
                val parts = extraContent.split(":::").filter { it.isNotBlank() }
                if (parts.size >= 3) {
                    val candidateUids = CloudDiscoveryHook.resolveCandidateUids(null, classLoader, targets)
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
                                resolvedUid = candUid
                                break
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val metaJson = JSONObject().apply {
            put("packageName", pkgName)
            put("name", pkgName)
            put("versionCode", 1L)
            put("versionName", "1.0")
            put("dateBackup", now)
            put("dateBackupUpdated", now)

            if (appFile.exists()) {
                put("apkBackupDate", now)
                put("apkBackupSize", appFile.length())
            }
            if (datFile.exists()) {
                put("dataBackupDate", now)
                put("dataBackupSize", datFile.length())
                put("isDataEncrypted", true)
                put("dataEncryptionMethod", "StandardEncryption")
            }
            if (extDatFile.exists()) {
                put("extDataBackupDate", now)
                put("extDataBackupSize", extDatFile.length())
                put("isExtDataEncrypted", true)
                put("extDataEncryptionMethod", "StandardEncryption")
            }
            if (splitsFile.exists()) {
                put("splitsBackupSize", splitsFile.length())
            }
            if (medFile.exists()) {
                put("mediaBackupDate", now)
                put("mediaBackupSize", medFile.length())
                put("isMediaEncrypted", true)
                put("mediaEncryptionMethod", "StandardEncryption")
            }

            ssaid?.let { put("ssaid", it) }
            permissionStatesCsv?.let { put("permissionStatesCsv", it) }
            notificationPolicyXml?.let { put("notificationPolicyXml", it) }
        }

        val key = deriveConcealKey(resolvedUid)
        val encUid = concealEncrypt(resolvedUid, key)
        val encMeta = concealEncrypt(metaJson.toString(), key)

        val xmlContent = "v1:::$encUid:::$encMeta"
        xmlFile.writeText(xmlContent, StandardCharsets.UTF_8)
        xmlFile.setReadable(true, false)
        xmlFile.setWritable(true, false)

        logI("Successfully generated $pkgName.xml for $pkgName ($backupId)")
        return true
    }

    /**
     * Derives Facebook Conceal 32-byte AES key from Firebase UID.
     */
    fun deriveConcealKey(uid: String): ByteArray {
        val repeated = StringBuilder(uid)
        while (repeated.length < 32) {
            repeated.append(uid)
        }
        val padded = repeated.substring(0, 32)
        return padded.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Conceal AES-GCM-256 Decrypt.
     */
    fun concealDecrypt(base64Payload: String, key: ByteArray): ByteArray {
        val raw = Base64.getDecoder().decode(base64Payload.trim().replace("\n", "").replace("\r", ""))
        val version = raw[0]
        val cipherId = raw[1]
        val iv = raw.copyOfRange(2, 14)
        val cipherTextAndTag = raw.copyOfRange(14, raw.size)

        val aad = byteArrayOf(version, cipherId) + CONCEAL_ENTITY.toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")

        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        cipher.updateAAD(aad)
        return cipher.doFinal(cipherTextAndTag)
    }

    /**
     * Conceal AES-GCM-256 Encrypt.
     */
    fun concealEncrypt(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val aad = byteArrayOf(1, 2) + CONCEAL_ENTITY.toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        cipher.updateAAD(aad)
        val cipherTextAndTag = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val output = byteArrayOf(1, 2) + iv + cipherTextAndTag
        return Base64.getEncoder().encodeToString(output)
    }

    /**
     * Decompresses Zstandard or Base64-Zstd payload if present.
     */
    fun decompressZstdOrRaw(bytes: ByteArray, classLoader: ClassLoader): String? {
        // Try decoding as direct UTF-8 first
        try {
            val str = String(bytes, StandardCharsets.UTF_8)
            if (str.startsWith("{") && str.endsWith("}")) {
                return str
            }
        } catch (_: Throwable) {}

        // Try Base64 unwrap then Zstandard
        try {
            val unb64 = Base64.getDecoder().decode(bytes)
            val zstdClass = attempt("load SbaZstdNative", silent = true) {
                loadClassFlexible(classLoader, "com.swiftapps.sba.SbaZstdNative")
            }
            if (zstdClass != null) {
                val nativeInstance = zstdClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
                val decompressMethod = zstdClass.getDeclaredMethod("decompressZstdBytes", ByteArray::class.java)
                val decompressed = decompressMethod.invoke(nativeInstance, unb64) as? ByteArray
                if (decompressed != null) {
                    return String(decompressed, StandardCharsets.UTF_8)
                }
            }
        } catch (_: Throwable) {}

        return null
    }
}
