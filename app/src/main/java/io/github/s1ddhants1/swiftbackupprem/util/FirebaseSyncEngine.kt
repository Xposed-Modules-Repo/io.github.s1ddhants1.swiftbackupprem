package io.github.s1ddhants1.swiftbackupprem.util

import android.content.Context
import android.os.Environment
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Engine to sync reconstructed and cloud discovered backup metadata directly
 * to a Custom Firebase Realtime Database instance.
 */
object FirebaseSyncEngine {

    private const val TAG = Consts.TAG

    data class SyncResult(
        val totalSynced: Int,
        val totalAlreadyExisting: Int = 0,
        val totalFailed: Int = 0,
        val errors: List<String> = emptyList()
    )

    data class AuthCredentials(
        val uid: String,
        val idToken: String?
    )

    fun cleanDbUrl(rawUrl: String): String {
        var url = rawUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    /**
     * Resolves authenticated Firebase credentials (UID and Firebase ID Token JWT)
     * by inspecting Swift Backup's OAuth state and exchanging tokens via Firebase Auth REST API.
     */
    fun resolveAuthCredentials(context: Context?, prefs: PreferencesManager): AuthCredentials? {
        val apiKey = prefs.googleApiKey.trim().takeIf { it.isNotBlank() }

        // 1. Try reading Swift Backup preferences
        val prefsContent = readSwiftBackupPreferences(context)
        if (!prefsContent.isNullOrBlank()) {
            val creds = parseCredentialsFromPrefsContent(prefsContent, apiKey, prefs.clientId.trim())
            if (creds != null) return creds
        }

        // 2. Fallback: resolve candidate UID without token if rules allow unauthenticated writes
        val fallbackUids = BackupCrypto.resolveCandidateUids(context, ClassLoader.getSystemClassLoader())
        val selectedUid = fallbackUids.firstOrNull { it != BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID }
            ?: fallbackUids.firstOrNull()

        return if (!selectedUid.isNullOrBlank()) {
            AuthCredentials(uid = selectedUid, idToken = null)
        } else {
            null
        }
    }

    private fun readSwiftBackupPreferences(context: Context?): String? {
        val candidateFiles = listOf(
            File("/data/data/org.swiftapps.swiftbackup/shared_prefs/org.swiftapps.swiftbackup_preferences.xml"),
            File("/data/user/0/org.swiftapps.swiftbackup/shared_prefs/org.swiftapps.swiftbackup_preferences.xml")
        )

        for (f in candidateFiles) {
            if (f.exists() && f.canRead()) {
                try {
                    val txt = f.readText(StandardCharsets.UTF_8)
                    if (txt.isNotBlank()) return txt
                } catch (_: Throwable) {}
            }
        }

        // Try reading via root shell if direct read fails
        val suBins = listOf("su", "/system/bin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su")
        for (su in suBins) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(su, "-c", "cat /data/data/org.swiftapps.swiftbackup/shared_prefs/org.swiftapps.swiftbackup_preferences.xml 2>/dev/null"))
                val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                val out = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    out.appendLine(line)
                }
                proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                val res = out.toString().trim()
                if (res.isNotBlank()) return res
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun parseCredentialsFromPrefsContent(xmlText: String, apiKey: String?, prefClientId: String): AuthCredentials? {
        var refreshToken: String? = null
        var clientId: String? = prefClientId.takeIf { it.isNotBlank() }
        var rawGoogleIdToken: String? = null

        // Extract from nogms_auth_state
        val matcherNoGms = Pattern.compile("name=\"nogms_auth_state\">([^<]+)<").matcher(xmlText)
        if (matcherNoGms.find()) {
            val rawJsonStr = matcherNoGms.group(1)?.replace("&quot;", "\"")?.replace("&amp;", "&")?.replace("\\/", "/")
            if (!rawJsonStr.isNullOrBlank()) {
                attempt("parse nogms_auth_state", silent = true) {
                    val jsonObj = JSONObject(rawJsonStr)
                    refreshToken = jsonObj.optString("refreshToken").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("refresh_token").takeIf { it.isNotBlank() }
                    
                    jsonObj.optJSONObject("lastAuthorizationResponse")?.optJSONObject("request")?.optString("clientId")?.let {
                        if (clientId.isNullOrBlank() && it.isNotBlank()) clientId = it
                    }
                    jsonObj.optJSONObject("mLastTokenResponse")?.optString("id_token")?.let {
                        if (it.isNotBlank()) rawGoogleIdToken = it
                    }
                }
            }
        }

        // If refreshToken and apiKey and clientId are available, exchange for fresh Firebase Auth token
        if (!refreshToken.isNullOrBlank() && !clientId.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            val freshTokens = exchangeGoogleRefreshTokenForFirebaseToken(refreshToken, clientId, apiKey)
            if (freshTokens != null) return freshTokens
        }

        // If we have a Google ID token and apiKey, try signInWithIdp
        if (!rawGoogleIdToken.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            val fbTokens = exchangeGoogleIdTokenForFirebaseToken(rawGoogleIdToken, apiKey)
            if (fbTokens != null) return fbTokens
        }

        return null
    }

    private fun exchangeGoogleRefreshTokenForFirebaseToken(
        refreshToken: String,
        clientId: String,
        apiKey: String
    ): AuthCredentials? = attempt("exchange refresh token for Firebase ID token", silent = true) {
        // 1. Refresh Google OAuth Token
        val oauthEndpoint = "https://oauth2.googleapis.com/token"
        val postBody = "client_id=" + java.net.URLEncoder.encode(clientId, "UTF-8") +
                "&refresh_token=" + java.net.URLEncoder.encode(refreshToken, "UTF-8") +
                "&grant_type=refresh_token"

        val conn = (URL(oauthEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(postBody); it.flush() }
        if (conn.responseCode !in 200..299) {
            Log.w(TAG, "[FirebaseSync] Failed to refresh Google OAuth token (HTTP ${conn.responseCode})")
            conn.disconnect()
            return@attempt null
        }

        val googleResp = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        val googleIdToken = JSONObject(googleResp).optString("id_token")
        if (googleIdToken.isBlank()) return@attempt null

        // 2. Exchange with Firebase Identity Toolkit
        exchangeGoogleIdTokenForFirebaseToken(googleIdToken, apiKey)
    }

    private fun exchangeGoogleIdTokenForFirebaseToken(
        googleIdToken: String,
        apiKey: String
    ): AuthCredentials? = attempt("exchange Google ID token for Firebase Auth ID token", silent = true) {
        val endpoint = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$apiKey"
        val payload = JSONObject().apply {
            put("postBody", "id_token=$googleIdToken&providerId=google.com")
            put("requestUri", "http://localhost")
            put("returnSecureToken", true)
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload.toString()); it.flush() }
        if (conn.responseCode !in 200..299) {
            Log.w(TAG, "[FirebaseSync] Failed to exchange token with Firebase Auth (HTTP ${conn.responseCode})")
            conn.disconnect()
            return@attempt null
        }

        val fbResp = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        val json = JSONObject(fbResp)
        val idToken = json.optString("idToken")
        val uid = json.optString("localId")

        if (uid.isNotBlank() && idToken.isNotBlank()) {
            Log.i(TAG, "[FirebaseSync] Successfully resolved Firebase Auth UID: $uid")
            AuthCredentials(uid = uid, idToken = idToken)
        } else {
            null
        }
    }

    /**
     * Fetches existing backups tree from /users/<uid>/backups.json to determine matching records.
     */
    fun fetchExistingBackups(firebaseDbUrl: String, uid: String, idToken: String? = null): JSONObject? {
        return attempt("fetch existing backups from RTDB", silent = true) {
            val base = cleanDbUrl(firebaseDbUrl)
            val authParam = if (!idToken.isNullOrBlank()) "?auth=$idToken" else ""
            val endpoint = "$base/users/$uid/backups.json$authParam"

            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val respText = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
                conn.disconnect()
                if (respText.isNotBlank() && respText != "null") {
                    JSONObject(respText)
                } else {
                    JSONObject()
                }
            } else {
                conn.disconnect()
                null
            }
        }
    }

    /**
     * Pushes a single app's backup metadata to /users/<uid>/backups/apps/<sanitizedAppId>/<backupId>.json
     */
    fun syncAppMetadata(
        firebaseDbUrl: String,
        uid: String,
        pkgName: String,
        backupId: String,
        metadataJson: JSONObject,
        idToken: String? = null
    ): Boolean = attempt("sync $pkgName ($backupId) metadata to Firebase Realtime Database", silent = true) {
        val base = cleanDbUrl(firebaseDbUrl)
        val sanitizedAppId = pkgName.replace(".", "")
        val authParam = if (!idToken.isNullOrBlank()) "?auth=$idToken" else ""
        val endpoint = "$base/users/$uid/backups/apps/$sanitizedAppId/$backupId.json$authParam"

        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(metadataJson.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        conn.disconnect()
        if (responseCode in 200..299) {
            Log.d(TAG, "[FirebaseSync] Successfully synced metadata for $pkgName ($backupId) to $endpoint")
            true
        } else {
            Log.w(TAG, "[FirebaseSync] Failed to sync $pkgName ($backupId): HTTP $responseCode")
            false
        }
    } ?: false

    /**
     * Pushes a single folder's backup metadata to /users/<uid>/backups/folders/<folderId>.json
     */
    fun syncFolderMetadata(
        firebaseDbUrl: String,
        uid: String,
        folderId: String,
        metadataJson: JSONObject,
        idToken: String? = null
    ): Boolean = attempt("sync folder $folderId metadata to Firebase Realtime Database", silent = true) {
        val base = cleanDbUrl(firebaseDbUrl)
        val authParam = if (!idToken.isNullOrBlank()) "?auth=$idToken" else ""
        val endpoint = "$base/users/$uid/backups/folders/$folderId.json$authParam"

        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(metadataJson.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        conn.disconnect()
        if (responseCode in 200..299) {
            Log.d(TAG, "[FirebaseSync] Successfully synced folder metadata for $folderId to $endpoint")
            true
        } else {
            Log.w(TAG, "[FirebaseSync] Failed to sync folder $folderId: HTTP $responseCode")
            false
        }
    } ?: false

    /**
     * Reads cloud discovery cache JSON from disk or root shell.
     */
    fun readCloudDiscoveryCache(context: Context?): JSONObject? {
        val candidateFiles = listOf(
            File("/data/data/org.swiftapps.swiftbackup/cloud_discovered_cache.json"),
            File("/data/user/0/org.swiftapps.swiftbackup/cloud_discovered_cache.json"),
            context?.filesDir?.parentFile?.let { File(it, "cloud_discovered_cache.json") }
        ).filterNotNull()

        for (f in candidateFiles) {
            if (f.exists() && f.canRead()) {
                try {
                    val txt = f.readText(StandardCharsets.UTF_8)
                    if (txt.isNotBlank()) return JSONObject(txt)
                } catch (_: Throwable) {}
            }
        }

        // Root shell fallback
        val suBins = listOf("su", "/system/bin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su")
        for (su in suBins) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(su, "-c", "cat /data/data/org.swiftapps.swiftbackup/cloud_discovered_cache.json 2>/dev/null"))
                val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                val out = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    out.appendLine(line)
                }
                proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                val res = out.toString().trim()
                if (res.isNotBlank()) return JSONObject(res)
            } catch (_: Throwable) {}
        }

        return null
    }

    /**
     * Performs a full sync of both Cloud Discovered Cache and Local Backups to Custom Firebase RTDB.
     */
    fun syncAll(context: Context, prefs: PreferencesManager): SyncResult {
        val errors = mutableListOf<String>()
        var totalSynced = 0
        var totalAlreadyExisting = 0
        var totalFailed = 0

        val dbUrl = prefs.firebaseDatabaseUrl.trim()
        if (dbUrl.isBlank()) {
            return SyncResult(0, 0, 0, listOf("Firebase Realtime Database URL is empty."))
        }

        val creds = resolveAuthCredentials(context, prefs)
        if (creds == null) {
            return SyncResult(0, 0, 0, listOf("Could not resolve Firebase Auth credentials. Please ensure Swift Backup is logged into your Custom Firebase account."))
        }

        val uid = creds.uid
        val idToken = creds.idToken
        Log.i(TAG, "[FirebaseSync] Starting sync for Firebase UID: $uid (Token attached: ${idToken != null})")

        // Fetch existing RTDB records to detect already synced items
        val existingBackups = fetchExistingBackups(dbUrl, uid, idToken)
        val existingAppsObj = existingBackups?.optJSONObject("apps")
        val existingFoldersObj = existingBackups?.optJSONObject("folders")

        // 1. Sync Cloud Discovered Backups
        val cacheJson = readCloudDiscoveryCache(context)
        if (cacheJson != null) {
            val appsObj = cacheJson.optJSONObject("apps")
            if (appsObj != null) {
                appsObj.keys().forEach { pkg ->
                    val sanitizedAppId = pkg.replace(".", "")
                    val existingAppBackups = existingAppsObj?.optJSONObject(sanitizedAppId)

                    val appArray = appsObj.optJSONArray(pkg)
                    if (appArray != null) {
                        for (i in 0 until appArray.length()) {
                            val item = appArray.optJSONObject(i) ?: continue
                            val backupId = item.optString("backupId").takeIf { it.isNotBlank() } ?: "default"
                            if (existingAppBackups?.has(backupId) == true) {
                                totalAlreadyExisting++
                                continue
                            }
                            val rtdbPayload = formatDiscoveredAppForRtdb(pkg, item)
                            val ok = syncAppMetadata(dbUrl, uid, pkg, backupId, rtdbPayload, idToken)
                            if (ok) totalSynced++ else {
                                totalFailed++
                                errors.add("Failed to sync cloud backup $pkg ($backupId)")
                            }
                        }
                    } else {
                        val item = appsObj.optJSONObject(pkg)
                        if (item != null) {
                            val backupId = item.optString("backupId").takeIf { it.isNotBlank() } ?: "default"
                            if (existingAppBackups?.has(backupId) == true) {
                                totalAlreadyExisting++
                            } else {
                                val rtdbPayload = formatDiscoveredAppForRtdb(pkg, item)
                                val ok = syncAppMetadata(dbUrl, uid, pkg, backupId, rtdbPayload, idToken)
                                if (ok) totalSynced++ else {
                                    totalFailed++
                                    errors.add("Failed to sync cloud backup $pkg ($backupId)")
                                }
                            }
                        }
                    }
                }
            }

            val foldersObj = cacheJson.optJSONObject("folders")
            if (foldersObj != null) {
                foldersObj.keys().forEach { fid ->
                    val fItem = foldersObj.optJSONObject(fid) ?: return@forEach
                    val folderId = fItem.optString("id", fid)
                    if (existingFoldersObj?.has(folderId) == true) {
                        totalAlreadyExisting++
                        return@forEach
                    }
                    val ok = syncFolderMetadata(dbUrl, uid, folderId, fItem, idToken)
                    if (ok) totalSynced++ else {
                        totalFailed++
                        errors.add("Failed to sync cloud folder $folderId")
                    }
                }
            }
        }

        // 2. Sync Local Backups on storage
        try {
            val storageRoot = File(Environment.getExternalStorageDirectory(), "SwiftBackup/accounts")
            if (storageRoot.isDirectory) {
                storageRoot.listFiles { f -> f.isDirectory }?.forEach { accountDir ->
                    val accountHash = accountDir.name
                    val appsDir = File(accountDir, "backups/apps/local")
                    if (appsDir.isDirectory) {
                        appsDir.listFiles { f -> f.isDirectory }?.forEach { pkgDir ->
                            val pkgName = pkgDir.name
                            val sanitizedAppId = pkgName.replace(".", "")
                            val existingAppBackups = existingAppsObj?.optJSONObject(sanitizedAppId)

                            pkgDir.listFiles { f -> f.isDirectory }?.forEach { backupDir ->
                                val backupId = backupDir.name
                                if (existingAppBackups?.has(backupId) == true) {
                                    totalAlreadyExisting++
                                    return@forEach
                                }

                                val xmlFile = File(backupDir, "$pkgName.xml")
                                if (xmlFile.exists()) {
                                    try {
                                        val content = xmlFile.readText(StandardCharsets.UTF_8)
                                        val json = if (content.startsWith("{")) JSONObject(content)
                                        else {
                                            val parts = content.split(":::").filter { it.isNotBlank() }
                                            if (parts.size >= 3) {
                                                val key = BackupCrypto.deriveConcealKey(accountHash)
                                                val dec = String(BackupCrypto.concealDecrypt(parts[2], key), StandardCharsets.UTF_8)
                                                JSONObject(dec)
                                            } else null
                                        }
                                        if (json != null) {
                                            val rtdbPayload = formatDiscoveredAppForRtdb(pkgName, json, backupId)
                                            val ok = syncAppMetadata(dbUrl, uid, pkgName, backupId, rtdbPayload, idToken)
                                            if (ok) totalSynced++ else {
                                                totalFailed++
                                                errors.add("Failed to sync local backup $pkgName ($backupId)")
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        totalFailed++
                                        errors.add("Error parsing local backup $pkgName: ${t.message}")
                                    }
                                }
                            }
                        }
                    }

                    val foldersDir = File(accountDir, "backups/folders/local")
                    if (foldersDir.isDirectory) {
                        foldersDir.listFiles { f -> f.isDirectory }?.forEach { folderDir ->
                            val folderId = folderDir.name.removePrefix("Folder-")
                            if (existingFoldersObj?.has(folderId) == true) {
                                totalAlreadyExisting++
                                return@forEach
                            }

                            val metaFile = File(folderDir, "metadata.json")
                            if (metaFile.exists()) {
                                try {
                                    val metaJson = JSONObject(metaFile.readText(StandardCharsets.UTF_8))
                                    val ok = syncFolderMetadata(dbUrl, uid, folderId, metaJson, idToken)
                                    if (ok) totalSynced++ else {
                                        totalFailed++
                                        errors.add("Failed to sync local folder $folderId")
                                    }
                                } catch (t: Throwable) {
                                    totalFailed++
                                    errors.add("Error parsing folder ${folderDir.name}: ${t.message}")
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            errors.add("Local scan error: ${t.message}")
        }

        Log.i(TAG, "[FirebaseSync] Finished sync: $totalSynced newly synced, $totalAlreadyExisting already existing, $totalFailed failed")
        return SyncResult(
            totalSynced = totalSynced,
            totalAlreadyExisting = totalAlreadyExisting,
            totalFailed = totalFailed,
            errors = errors
        )
    }

    private fun formatDiscoveredAppForRtdb(pkgName: String, json: JSONObject, fallbackBackupId: String = "default"): JSONObject {
        val sanitized = pkgName.replace(".", "")
        val backupId = json.optString("backupId", fallbackBackupId)
        val now = System.currentTimeMillis()
        val dateBackup = json.optLong("dateBackup", json.optLong("dateBackupUpdated", now))

        return JSONObject().apply {
            put("appId", sanitized)
            put("packageName", pkgName)
            put("name", json.optString("appName", json.optString("name", pkgName)))
            put("versionCode", json.optLong("versionCode", 1L))
            put("versionName", json.optString("versionName", "1.0"))
            put("dateBackup", dateBackup)
            put("dateBackupUpdated", dateBackup)
            put("backupTag", json.optString("backupTag", "DEFAULT"))
            put("minSBVersionCodeRequired", 580L)
            put("keyVersion", 1)

            json.optString("apkLink").takeIf { it.isNotBlank() }?.let { put("apkLink", it) }
            put("apkSize", json.optLong("apkSize", json.optLong("apkBackupSize", 0L)))
            put("apkBackupDate", json.optLong("apkBackupDate", dateBackup))

            json.optString("dataLink").takeIf { it.isNotBlank() }?.let { put("dataLink", it) }
            put("dataSize", json.optLong("dataSize", json.optLong("dataBackupSize", 0L)))
            put("dataBackupDate", json.optLong("dataBackupDate", dateBackup))

            json.optString("extDataLink").takeIf { it.isNotBlank() }?.let { put("extDataLink", it) }
            put("extDataSize", json.optLong("extDataSize", json.optLong("extDataBackupSize", 0L)))
            put("extDataBackupDate", json.optLong("extDataBackupDate", dateBackup))

            json.optString("splitsLink").takeIf { it.isNotBlank() }?.let { put("splitsLink", it) }
            put("splitsSize", json.optLong("splitsSize", json.optLong("splitsBackupSize", 0L)))
            put("splitsBackupDate", json.optLong("splitsBackupDate", dateBackup))

            val extraLink = json.optString("extraLink").takeIf { it.isNotBlank() } ?: json.optString("specialDataLink").takeIf { it.isNotBlank() }
            extraLink?.let { put("specialDataLink", it) }
            val extraSize = json.optLong("extraSize", json.optLong("specialDataSize", 0L))
            if (extraSize > 0L) put("specialDataSize", extraSize)

            json.optString("ssaid").takeIf { it.isNotBlank() }?.let { put("ssaid", it) }
            json.optString("permissionStatesCsv").takeIf { it.isNotBlank() }?.let { put("permissionStatesCsv", it) }
            json.optString("notificationPolicyXml").takeIf { it.isNotBlank() }?.let { put("notificationPolicyXml", it) }
        }
    }
}
