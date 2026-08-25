package io.github.s1ddhants1.swiftbackupprem.util

import android.annotation.SuppressLint
import android.content.Context
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared cryptography and metadata decoding engine for Facebook Conceal (AES-GCM-256),
 * Zstandard decompression, and UID resolution across Cloud Discovery and Backup Rebuilder.
 */
object BackupCrypto {

    private const val CONCEAL_ENTITY = "SwiftBackup_Entity"

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

    data class DecryptedExtra(
        val ssaid: String? = null,
        val permissionStatesCsv: String? = null,
        val notificationPolicyXml: String? = null,
        val versionCode: Long = 1L,
        val versionName: String = "1.0",
        val resolvedUid: String = ""
    )

    fun parseExtraPayload(
        rawExtraText: String,
        candidateUids: List<String>,
        classLoader: ClassLoader
    ): DecryptedExtra? {
        val parts = rawExtraText.split(":::").filter { it.isNotBlank() }
        if (parts.size < 3) return null

        val payload = parts[2].trim()
        for (candUid in candidateUids) {
            try {
                val key = deriveConcealKey(candUid)
                val decBytes = concealDecrypt(payload, key)
                val decompJson = decompressZstdOrRaw(decBytes, classLoader) ?: continue
                val json = JSONObject(decompJson)
                return DecryptedExtra(
                    ssaid = json.optString("ssaid").takeIf { it.isNotBlank() },
                    permissionStatesCsv = json.optString("permissionStatesCsv").takeIf { it.isNotBlank() },
                    notificationPolicyXml = json.optString("notificationPolicyXml").takeIf { it.isNotBlank() },
                    versionCode = json.optLong("versionCode", 1L),
                    versionName = json.optString("versionName", "1.0"),
                    resolvedUid = candUid
                )
            } catch (_: Throwable) {}
        }
        return null
    }

    data class DecryptedFolderManifest(
        val sourcePath: String = "/storage/emulated/0",
        val displayName: String = "",
        val created: Long = 0L,
        val backupId: String = ""
    )

    fun parseFolderManifest(
        rawFlmText: String,
        candidateUids: List<String>,
        classLoader: ClassLoader
    ): DecryptedFolderManifest? {
        val parts = rawFlmText.split(":::").filter { it.isNotBlank() }
        if (parts.size < 3) return null

        val payload = parts[2].trim()
        for (candUid in candidateUids) {
            try {
                val key = deriveConcealKey(candUid)
                val decBytes = concealDecrypt(payload, key)
                val decompJson = decompressZstdOrRaw(decBytes, classLoader) ?: continue
                val json = JSONObject(decompJson)
                val srcPath = json.optString("sourcePath").takeIf { it.isNotBlank() } ?: "/storage/emulated/0"
                val name = srcPath.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: srcPath
                val created = json.optLong("created", 0L)
                val bId = json.optString("backupId")
                return DecryptedFolderManifest(
                    sourcePath = srcPath,
                    displayName = name,
                    created = created,
                    backupId = bId
                )
            } catch (_: Throwable) {}
        }
        return null
    }

    @SuppressLint("SdCardPath")
    fun resolveCandidateUids(context: Context?, classLoader: ClassLoader, targets: ResolvedTargets? = null): List<String> {
        val uids = LinkedHashSet<String>()

        for (className in listOf("d45", "b45")) {
            attempt("resolve UID via $className", silent = true) {
                val user = loadClassFlexible(classLoader, className)?.getDeclaredMethod("a")?.invoke(null)
                val uid = user?.javaClass?.getDeclaredMethod("getUid")?.invoke(user) as? String
                if (!uid.isNullOrBlank()) uids.add(uid)
            }
        }

        attempt("resolve UID via FirebaseAuth", silent = true) {
            val fbAuthClass = classLoader.loadClass("com.google.firebase.auth.FirebaseAuth")
            val authInstance = fbAuthClass.getDeclaredMethod("getInstance").invoke(null)
            val currentUser = authInstance?.let { fbAuthClass.getDeclaredMethod("getCurrentUser").invoke(it) }
            val uid = currentUser?.let { it.javaClass.getDeclaredMethod("getUid").invoke(it) as? String }
            if (!uid.isNullOrBlank()) uids.add(uid)
        }

        attempt("resolve UIDs from shared_prefs Store XMLs", silent = true) {
            val sharedPrefsDir = if (context != null) File(context.filesDir?.parentFile, "shared_prefs") else File("/data/data/org.swiftapps.swiftbackup/shared_prefs")
            if (sharedPrefsDir.exists()) {
                sharedPrefsDir.listFiles { file -> file.name.startsWith("com.google.firebase.auth.api.Store") }?.forEach { storeFile ->
                    val matcher = Pattern.compile("com\\.google\\.firebase\\.auth\\.GET_TOKEN_RESPONSE\\.([a-zA-Z0-9_-]+)").matcher(storeFile.readText(StandardCharsets.UTF_8))
                    while (matcher.find()) {
                        val uid = matcher.group(1)
                        if (!uid.isNullOrBlank()) uids.add(uid)
                    }
                }
            }
        }

        return uids.toList()
    }
}
