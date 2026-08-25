package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.HookHandler
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.cloud.CloudFileItem
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.cloud.CloudScanner
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.cloud.CloudScannerRegistry
import io.github.s1ddhants1.swiftbackupprem.hook.getFieldValue
import io.github.s1ddhants1.swiftbackupprem.hook.hookTracked
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.loadClassFlexible
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

@Keep
object CloudDiscoveryHook : HookHandler {

    private const val TAG = Consts.TAG
    private const val CACHE_FILE_NAME = "cloud_discovered_cache.json"
    val discoveredBackups = ConcurrentHashMap<String, DiscoveredCloudApp>()
    val discoveredFolders = ConcurrentHashMap<String, DiscoveredCloudFolder>()
    val discoveredCalls = ConcurrentHashMap<String, DiscoveredCloudCall>()
    val discoveredSms = ConcurrentHashMap<String, DiscoveredCloudSms>()
    val discoveredWalls = ConcurrentHashMap<String, DiscoveredCloudWall>()
    val discoveredWifi = ConcurrentHashMap<String, DiscoveredCloudWifi>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val isScanRunning = AtomicBoolean(false)
    @Volatile
    private var scanExecutor = createScanExecutor()

    private fun createScanExecutor() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SBP-CloudDiscovery").apply { isDaemon = true }
    }

    fun shutdown() {
        try { scanExecutor.shutdownNow() } catch (_: Throwable) {}
        scanExecutor = createScanExecutor()
        isScanRunning.set(false)
    }

    data class DiscoveredCloudFolder(
        val id: String,
        val displayName: String,
        val tag: String,
        val fldLink: String? = null,
        val fldSize: Long = 0,
        val flmLink: String? = null,
        val flmSize: Long = 0,
        val totalSize: Long = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val sourceFolder: String = "/storage/emulated/0",
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("displayName", displayName)
            put("tag", tag)
            fldLink?.let { put("fldLink", it) }
            put("fldSize", fldSize)
            flmLink?.let { put("flmLink", it) }
            put("flmSize", flmSize)
            put("totalSize", totalSize)
            put("timestamp", timestamp)
            put("sourceFolder", sourceFolder)
            put("provider", provider)
        }

        companion object {
            fun fromJson(id: String, obj: JSONObject): DiscoveredCloudFolder = DiscoveredCloudFolder(
                id = obj.optString("id", id),
                displayName = obj.optString("displayName", "Folder-$id"),
                tag = obj.optString("tag", "DEFAULT"),
                fldLink = obj.optString("fldLink").takeIf { it.isNotBlank() },
                fldSize = obj.optLong("fldSize", 0L),
                flmLink = obj.optString("flmLink").takeIf { it.isNotBlank() },
                flmSize = obj.optLong("flmSize", 0L),
                totalSize = obj.optLong("totalSize", 0L),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                sourceFolder = obj.optString("sourceFolder", "/storage/emulated/0"),
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudCall(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val count: Int,
        val tag: String,
        val timestamp: Long,
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size)
            put("count", count); put("tag", tag); put("timestamp", timestamp)
            put("provider", provider)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudCall = DiscoveredCloudCall(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                count = obj.optInt("count", 1),
                tag = obj.optString("tag", "DEFAULT"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudSms(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val totalCount: Int,
        val tag: String,
        val timestamp: Long,
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size)
            put("totalCount", totalCount); put("tag", tag); put("timestamp", timestamp)
            put("provider", provider)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudSms = DiscoveredCloudSms(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                totalCount = obj.optInt("totalCount", 1),
                tag = obj.optString("tag", "DEFAULT"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudWall(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val timestamp: Long,
        val thumbnailLink: String? = null,
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size); put("timestamp", timestamp)
            thumbnailLink?.let { put("thumbnailLink", it) }
            put("provider", provider)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudWall = DiscoveredCloudWall(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                thumbnailLink = obj.optString("thumbnailLink").takeIf { it.isNotBlank() },
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudWifi(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val count: Int,
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size); put("count", count)
            put("provider", provider)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudWifi = DiscoveredCloudWifi(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                count = obj.optInt("count", 1),
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudApp(
        val packageName: String,
        val sanitizedAppId: String,
        val backupId: String,
        val backupTag: String,
        val apkLink: String? = null,
        val apkSize: Long = 0,
        val dataLink: String? = null,
        val dataSize: Long = 0,
        val extDataLink: String? = null,
        val extDataSize: Long = 0,
        val splitsLink: String? = null,
        val splitsSize: Long = 0,
        val extraLink: String? = null,
        val extraSize: Long = 0,
        val totalSize: Long = 0,
        val ssaid: String? = null,
        val permissionStatesCsv: String? = null,
        val notificationPolicyXml: String? = null,
        val dateBackup: Long = System.currentTimeMillis(),
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("packageName", packageName)
            put("sanitizedAppId", sanitizedAppId)
            put("backupId", backupId)
            put("backupTag", backupTag)
            apkLink?.let { put("apkLink", it) }
            put("apkSize", apkSize)
            dataLink?.let { put("dataLink", it) }
            put("dataSize", dataSize)
            extDataLink?.let { put("extDataLink", it) }
            put("extDataSize", extDataSize)
            splitsLink?.let { put("splitsLink", it) }
            put("splitsSize", splitsSize)
            extraLink?.let { put("extraLink", it) }
            put("extraSize", extraSize)
            put("totalSize", totalSize)
            ssaid?.let { put("ssaid", it) }
            permissionStatesCsv?.let { put("permissionStatesCsv", it) }
            notificationPolicyXml?.let { put("notificationPolicyXml", it) }
            put("dateBackup", dateBackup)
            put("provider", provider)
        }

        companion object {
            fun fromJson(pkg: String, obj: JSONObject): DiscoveredCloudApp {
                fun s(k: String) = obj.optString(k, "").ifBlank { null }
                fun l(k: String) = obj.optLong(k, 0L)
                return DiscoveredCloudApp(
                    packageName = obj.optString("packageName", pkg),
                    sanitizedAppId = obj.optString("sanitizedAppId", pkg.replace(".", "")),
                    backupId = obj.optString("backupId", ""),
                    backupTag = obj.optString("backupTag", "DEFAULT"),
                    apkLink = s("apkLink"), apkSize = l("apkSize"),
                    dataLink = s("dataLink"), dataSize = l("dataSize"),
                    extDataLink = s("extDataLink"), extDataSize = l("extDataSize"),
                    splitsLink = s("splitsLink"), splitsSize = l("splitsSize"),
                    extraLink = s("extraLink"), extraSize = l("extraSize"),
                    totalSize = l("totalSize"), ssaid = s("ssaid"),
                    permissionStatesCsv = s("permissionStatesCsv"),
                    notificationPolicyXml = s("notificationPolicyXml"),
                    dateBackup = obj.optLong("dateBackup", System.currentTimeMillis()),
                    provider = obj.optString("provider", "Generic")
                )
            }
        }
    }

    /**
     * Synthesizes a Firebase DataSnapshot from a DiscoveredCloudApp's metadata.
     *
     * The snapshot structure mirrors what Swift Backup's official RTDB would
     * store: a root node keyed by backupId, containing all CloudMetadata fields.
     * When passed to the native onDataChange pipeline, Swift Backup's own
     * AppCloudBackups.fromSnapshot() decodes it identically to a real RTDB entry.
     */
    private object FirebaseSnapshotSynthesizer {

        private const val SYNTH_TAG = "$TAG-Synth"

        private data class FirebaseClasses(
            val nodeUtilities: Class<*>,
            val indexedNode: Class<*>,
            val node: Class<*>,
            val dataSnapshot: Class<*>
        )

        private fun resolveFirebaseClasses(classLoader: ClassLoader): FirebaseClasses? {
            val nodeUtils = listOf(
                "com.google.firebase.database.snapshot.NodeUtilities",
                "com.google.firebase.database.snapshot.NodeUtility",
                "xh8"
            ).firstNotNullOfOrNull { loadClassFlexible(classLoader, it) } ?: run {
                Log.d(SYNTH_TAG, "NodeUtilities class not found")
                return null
            }

            val node = listOf(
                "com.google.firebase.database.snapshot.Node",
                "qn5"
            ).firstNotNullOfOrNull { loadClassFlexible(classLoader, it) } ?: run {
                Log.d(SYNTH_TAG, "Node class not found")
                return null
            }

            val indexedNode = listOf(
                "com.google.firebase.database.snapshot.IndexedNode",
                "sb4"
            ).firstNotNullOfOrNull { loadClassFlexible(classLoader, it) } ?: run {
                Log.d(SYNTH_TAG, "IndexedNode class not found")
                return null
            }

            val dataSnapshot = listOf(
                "com.google.firebase.database.DataSnapshot",
                "eb2"
            ).firstNotNullOfOrNull { loadClassFlexible(classLoader, it) } ?: run {
                Log.d(SYNTH_TAG, "DataSnapshot class not found")
                return null
            }

            return FirebaseClasses(nodeUtils, indexedNode, node, dataSnapshot)
        }

        private fun buildMetadataMap(app: DiscoveredCloudApp): Map<String, Any> {
            val backupMap = mutableMapOf<String, Any>(
                "appId" to app.sanitizedAppId,
                "packageName" to app.packageName,
                "name" to app.packageName,
                "versionCode" to 1L,
                "versionName" to "1.0",
                "dateBackup" to app.dateBackup,
                "dateBackupUpdated" to app.dateBackup,
                "backupTag" to app.backupTag,
                "minSBVersionCodeRequired" to 580L,
                "keyVersion" to 1
            )

            fun addSlice(link: String?, size: Long, prefix: String, encrypted: Boolean = false) {
                if (!link.isNullOrBlank()) {
                    backupMap["${prefix}Link"] = link
                    backupMap["${prefix}Size"] = size
                    if (encrypted) {
                        backupMap["is${prefix.replaceFirstChar { it.uppercase() }}Encrypted"] = true
                        backupMap["${prefix}EncryptionMethod"] = "StandardEncryption"
                    }
                    backupMap["${prefix}BackupDate"] = app.dateBackup
                    backupMap["${prefix}SBVersionCodeRequired"] = 580L
                    backupMap["${prefix}SBVersionNameRequired"] = "v4.2.3"
                }
            }

            addSlice(app.apkLink, app.apkSize, "apk")
            addSlice(app.dataLink, app.dataSize, "data", encrypted = true)
            addSlice(app.extDataLink, app.extDataSize, "extData", encrypted = true)

            if (!app.splitsLink.isNullOrBlank()) {
                backupMap["splitsLink"] = app.splitsLink
                backupMap["splitsSize"] = app.splitsSize
                backupMap["splitsSBVersionCodeRequired"] = 580L
                backupMap["splitsSBVersionNameRequired"] = "v4.2.3"
            }

            if (!app.extraLink.isNullOrBlank()) {
                backupMap["specialDataLink"] = app.extraLink
                backupMap["specialDataSize"] = app.extraSize
            }

            app.ssaid?.let { backupMap["ssaid"] = it }
            app.permissionStatesCsv?.let { backupMap["permissionStatesCsv"] = it }
            app.notificationPolicyXml?.let { backupMap["notificationPolicyXml"] = it }

            return mapOf(app.backupId to backupMap)
        }

        /**
         * Creates a synthetic DataSnapshot that the native onDataChange pipeline
         * will process identically to a real Firebase RTDB snapshot.
         *
         * Pipeline: Map → NodeFromJSON → IndexedNode.from → DataSnapshot(ref, indexed)
         *
         * Returns null if any step fails — caller must implement fallback.
         */
        fun createSyntheticSnapshot(
            classLoader: ClassLoader,
            queryRef: Any,
            app: DiscoveredCloudApp
        ): Any? = attempt("synthesize DataSnapshot for ${app.packageName}", silent = true) {
            val fb = resolveFirebaseClasses(classLoader) ?: return@attempt null

            val metadataMap = buildMetadataMap(app)

            val nodeFromJson = fb.nodeUtilities.declaredMethods.firstOrNull { m ->
                (m.parameterCount == 1 && m.parameterTypes[0] == Any::class.java && fb.node.isAssignableFrom(m.returnType)) ||
                (m.parameterCount == 2 && m.parameterTypes[0] == Any::class.java && fb.node.isAssignableFrom(m.parameterTypes[1]) && fb.node.isAssignableFrom(m.returnType))
            } ?: attempt("fallback NodeFromJSON", silent = true) { fb.nodeUtilities.getMethod("NodeFromJSON", Any::class.java) }

            val nodeObj = if (nodeFromJson != null) {
                if (nodeFromJson.parameterCount == 2) {
                    nodeFromJson.invoke(null, metadataMap, null)
                } else {
                    nodeFromJson.invoke(null, metadataMap)
                }
            } else {
                null
            } ?: run {
                Log.w(SYNTH_TAG, "NodeFromJSON returned null")
                return@attempt null
            }

            val indexedFromNode = fb.indexedNode.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 && fb.node.isAssignableFrom(m.parameterTypes[0]) && fb.indexedNode.isAssignableFrom(m.returnType)
            } ?: attempt("fallback IndexedNode.from", silent = true) { fb.indexedNode.getMethod("from", fb.node) }

            val indexedNodeObj = if (indexedFromNode != null) {
                indexedFromNode.invoke(null, nodeObj)
            } else {
                val defaultIndex = attempt("get default index", silent = true) {
                    fb.indexedNode.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type != fb.indexedNode }?.apply { isAccessible = true }?.get(null)
                }
                val ctor = fb.indexedNode.constructors.firstOrNull { it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(nodeObj.javaClass) }
                ctor?.newInstance(nodeObj, defaultIndex)
            } ?: run { Log.w(SYNTH_TAG, "IndexedNode resolution returned null"); return@attempt null }

            val ctor = fb.dataSnapshot.constructors
                .filter { it.parameterCount == 2 }
                .firstOrNull { c ->
                    val p0 = c.parameterTypes[0]
                    val p1 = c.parameterTypes[1]
                    (p0.isAssignableFrom(queryRef.javaClass) || p0.name.contains("Query") || p0.name.contains("zc2")) &&
                    (p1.isAssignableFrom(indexedNodeObj.javaClass) || p1.name.contains("IndexedNode") || p1.name.contains("sb4"))
                } ?: fb.dataSnapshot.constructors.firstOrNull { it.parameterCount == 2 }

            if (ctor == null) {
                Log.w(SYNTH_TAG, "No matching DataSnapshot constructor found. Available: ${
                    fb.dataSnapshot.constructors.joinToString { c ->
                        "(${c.parameterTypes.joinToString { it.simpleName }})"
                    }
                }")
                return@attempt null
            }

            ctor.newInstance(queryRef, indexedNodeObj)
        }

        /**
         * Creates a synthetic DataSnapshot containing cloud sync statistics.
         *
         * The RTDB schema for sync stats stores aggregate counts:
         *   { "apps": N, "cloudStorageUsed": N, "sms": N, "callLogs": N, "folders": N }
         */
        fun createSyncStatsSnapshot(
            classLoader: ClassLoader,
            queryRef: Any,
            apps: Int,
            cloudStorageUsed: Long,
            sms: Int,
            callLogs: Int,
            folders: Int
        ): Any? = attempt("synthesize sync stats DataSnapshot", silent = true) {
            val fb = resolveFirebaseClasses(classLoader) ?: return@attempt null

            val statsMap = mutableMapOf<String, Any>(
                "apps" to apps,
                "cloudStorageUsed" to cloudStorageUsed,
                "sms" to sms,
                "callLogs" to callLogs,
                "folders" to folders
            )

            val nodeFromJson = fb.nodeUtilities.declaredMethods.firstOrNull { m ->
                (m.parameterCount == 1 && m.parameterTypes[0] == Any::class.java && fb.node.isAssignableFrom(m.returnType)) ||
                (m.parameterCount == 2 && m.parameterTypes[0] == Any::class.java && fb.node.isAssignableFrom(m.parameterTypes[1]) && fb.node.isAssignableFrom(m.returnType))
            } ?: attempt("fallback NodeFromJSON for stats", silent = true) { fb.nodeUtilities.getMethod("NodeFromJSON", Any::class.java) }

            val nodeObj = if (nodeFromJson != null) {
                if (nodeFromJson.parameterCount == 2) {
                    nodeFromJson.invoke(null, statsMap, null)
                } else {
                    nodeFromJson.invoke(null, statsMap)
                }
            } else {
                null
            } ?: run {
                Log.w(SYNTH_TAG, "NodeFromJSON returned null for sync stats")
                return@attempt null
            }

            val indexedFromNode = fb.indexedNode.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 && fb.node.isAssignableFrom(m.parameterTypes[0]) && fb.indexedNode.isAssignableFrom(m.returnType)
            } ?: attempt("fallback IndexedNode.from for stats", silent = true) { fb.indexedNode.getMethod("from", fb.node) }

            val indexedNodeObj = if (indexedFromNode != null) {
                indexedFromNode.invoke(null, nodeObj)
            } else {
                val defaultIndex = attempt("get default index for stats", silent = true) {
                    fb.indexedNode.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type != fb.indexedNode }?.apply { isAccessible = true }?.get(null)
                }
                val ctor = fb.indexedNode.constructors.firstOrNull { it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(nodeObj.javaClass) }
                ctor?.newInstance(nodeObj, defaultIndex)
            } ?: run { Log.w(SYNTH_TAG, "IndexedNode.from returned null for sync stats"); return@attempt null }

            val ctor = fb.dataSnapshot.constructors
                .filter { it.parameterCount == 2 }
                .firstOrNull { c ->
                    val p0 = c.parameterTypes[0]
                    val p1 = c.parameterTypes[1]
                    (p0.isAssignableFrom(queryRef.javaClass) || p0.name.contains("Query") || p0.name.contains("zc2")) &&
                    (p1.isAssignableFrom(indexedNodeObj.javaClass) || p1.name.contains("IndexedNode") || p1.name.contains("sb4"))
                } ?: fb.dataSnapshot.constructors.firstOrNull { it.parameterCount == 2 }

            if (ctor == null) {
                Log.w(SYNTH_TAG, "No matching DataSnapshot constructor for sync stats")
                return@attempt null
            }

            ctor.newInstance(queryRef, indexedNodeObj)
        }
    }

    /**
     * Resilient factory for constructing pg1 (Drive file item) instances.
     *
     * Instead of hardcoding field names (c, e, f), discovers fields by type
     * from the pg1 class. This survives obfuscation renames as long as the
     * field types remain stable (Long for size/timestamp, String for links).
     */
    private class DriveFileItemFactory(classLoader: ClassLoader) {
        val pg1Class: Class<*>? = loadClassFlexible(classLoader, "pg1")
        val ui1Class: Class<*>? = loadClassFlexible(classLoader, "ui1")
        private val pg1Ctor = pg1Class?.constructors?.firstOrNull {
            it.parameterCount == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java
        }

        private val longFields = pg1Class?.declaredFields
            ?.filter { it.type == Long::class.javaPrimitiveType || it.type == Long::class.javaObjectType }
            ?.sortedBy { it.name }
            ?.onEach { it.isAccessible = true }
            ?: emptyList()

        private val extraStringFields = pg1Class?.declaredFields
            ?.filter { it.type == String::class.java }
            ?.sortedBy { it.name }
            ?.drop(2)
            ?.onEach { it.isAccessible = true }
            ?: emptyList()

        private val sizeField = longFields.getOrNull(0)
        private val timestampField = longFields.getOrNull(1)
        private val thumbnailField = extraStringFields.getOrNull(0)

        val isAvailable: Boolean = pg1Class != null && ui1Class != null && pg1Ctor != null

        fun create(
            fileName: String,
            fileId: String,
            size: Long = 0L,
            timestamp: Long = 0L,
            thumbnailLink: String? = null
        ): Any? = attempt("create drive file item", silent = true) {
            val item = pg1Ctor?.newInstance(fileName, fileId) ?: return@attempt null
            if (size > 0L) sizeField?.set(item, size)
            if (timestamp > 0L) timestampField?.set(item, timestamp)
            if (!thumbnailLink.isNullOrBlank()) thumbnailField?.set(item, thumbnailLink)
            item
        }

        fun wrapInResult(items: List<Any>): Any? = attempt("wrap in ui1 result", silent = true) {
            ui1Class?.getConstructor(Exception::class.java, List::class.java)
                ?.newInstance(null, items)
        }

        fun extractExistingItems(result: Any?): List<Any> =
            attempt("extract items from result", silent = true) {
                val listField = result?.javaClass?.declaredFields
                    ?.firstOrNull { List::class.java.isAssignableFrom(it.type) }
                listField?.apply { isAccessible = true }?.get(result) as? List<*>
            }?.filterNotNull() ?: emptyList()

        fun extractFileId(item: Any): String? = attempt("get fileId from item", silent = true) {
            val bField = item.javaClass.getDeclaredField("b").apply { isAccessible = true }
            (bField.get(item) as? String) ?: run {
                val aField = item.javaClass.getDeclaredField("a").apply { isAccessible = true }
                aField.get(item) as? String
            }
        }
    }

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (!prefs.customFirebaseApp || !prefs.enableDriveDiscovery) {
            Log.d(TAG, "Cloud Discovery is disabled (requires custom Firebase app and Drive discovery)")
            return
        }

        Log.d(TAG, "Applying CloudDiscoveryHook (Universal Cloud discovery & full-app cloud metadata indexing)")
        loadDiskCache(context)
        val driveFileItemFactory = DriveFileItemFactory(classLoader)
        hookAppCloudBackups(module, context, classLoader, targets)
        hookDetailCloudListener(module, context, classLoader, targets)
        hookBatchCloudLoader(module, context, classLoader, targets)
        hookAppFilterHelper(module, context, classLoader, targets)
        hookCloudSyncTab(module, context, classLoader, targets)
        hookCloudBackupTags(module, classLoader)
        hookWallpaperCloudLoader(module, classLoader, driveFileItemFactory)
        hookWifiCloudLoader(module, classLoader, driveFileItemFactory)
        startCloudScanWithRetry(context, classLoader, targets)
    }

    fun startDriveScanWithRetry(context: Context, classLoader: ClassLoader, targets: ResolvedTargets) =
        startCloudScanWithRetry(context, classLoader, targets)

    fun startCloudScanWithRetry(context: Context, classLoader: ClassLoader, targets: ResolvedTargets) {
        if (!isScanRunning.compareAndSet(false, true)) return
        scanExecutor.execute {
            try {
                for (delay in longArrayOf(500L, 2000L, 5000L, 10000L)) {
                    try {
                        Thread.sleep(delay)
                        val count = discoverAllCloudBackups(context, classLoader, targets)
                        if (count > 0 || discoveredBackups.isNotEmpty()) {
                            Log.i(TAG, "[CloudDiscovery] Background scan completed with ${discoveredBackups.size} apps")
                            break
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "[CloudDiscovery] Background scan retry failed: ${t.message}")
                    }
                }
            } finally {
                isScanRunning.set(false)
            }
        }
    }

    private fun ensureScan(context: Context, classLoader: ClassLoader, targets: ResolvedTargets) {
        if (discoveredBackups.isEmpty()) startCloudScanWithRetry(context, classLoader, targets)
    }

    fun findMatchingBackup(key: String): DiscoveredCloudApp? =
        discoveredBackups[key] ?: discoveredBackups.values.firstOrNull {
            it.sanitizedAppId == key || it.packageName == key || it.sanitizedAppId == key.replace(".", "")
        }

    private fun createBackupsObject(cloudBackup: Any, classLoader: ClassLoader): Any? =
        loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups")
            ?.getConstructor(List::class.java)?.newInstance(listOf(cloudBackup))

    @SuppressLint("SdCardPath")
    private fun loadDiskCache(context: Context) {
        try {
            val cacheFile = File(context.filesDir?.parentFile, CACHE_FILE_NAME)
            val fileToRead = if (cacheFile.exists()) cacheFile else File("/data/data/org.swiftapps.swiftbackup/$CACHE_FILE_NAME")
            if (fileToRead.exists()) {
                val root = JSONObject(fileToRead.readText(StandardCharsets.UTF_8))
                
                val appsObj = root.optJSONObject("apps") ?: root
                appsObj.keys().forEach { pkg ->
                    if (AppUtils.isValidPackageName(pkg)) {
                        val appJson = appsObj.optJSONObject(pkg)
                        if (appJson != null) {
                            discoveredBackups[pkg] = DiscoveredCloudApp.fromJson(pkg, appJson)
                        }
                    }
                }

                root.optJSONObject("folders")?.let { foldersObj ->
                    foldersObj.keys().forEach { fid ->
                        val fJson = foldersObj.optJSONObject(fid)
                        if (fJson != null) {
                            discoveredFolders[fid] = DiscoveredCloudFolder.fromJson(fid, fJson)
                        }
                    }
                }

                root.optJSONObject("calls")?.let { callsObj ->
                    callsObj.keys().forEach { id ->
                        callsObj.optJSONObject(id)?.let { discoveredCalls[id] = DiscoveredCloudCall.fromJson(it) }
                    }
                }

                root.optJSONObject("sms")?.let { smsObj ->
                    smsObj.keys().forEach { id ->
                        smsObj.optJSONObject(id)?.let { discoveredSms[id] = DiscoveredCloudSms.fromJson(it) }
                    }
                }

                root.optJSONObject("walls")?.let { wallsObj ->
                    wallsObj.keys().forEach { id ->
                        wallsObj.optJSONObject(id)?.let { discoveredWalls[id] = DiscoveredCloudWall.fromJson(it) }
                    }
                }

                root.optJSONObject("wifi")?.let { wifiObj ->
                    wifiObj.keys().forEach { id ->
                        wifiObj.optJSONObject(id)?.let { discoveredWifi[id] = DiscoveredCloudWifi.fromJson(it) }
                    }
                }

                Log.i(TAG, "[CloudDiscovery] Loaded ${discoveredBackups.size} apps, ${discoveredFolders.size} folders, ${discoveredCalls.size} calls, ${discoveredSms.size} sms, ${discoveredWalls.size} walls, ${discoveredWifi.size} wifi from cache")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[CloudDiscovery] Error loading cache: ${t.message}")
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun saveDiskCache(context: Context) {
        try {
            val root = JSONObject()
            val appsObj = JSONObject()
            discoveredBackups.forEach { (pkg, app) -> appsObj.put(pkg, app.toJson()) }
            root.put("apps", appsObj)

            val foldersObj = JSONObject()
            discoveredFolders.forEach { (fid, folder) -> foldersObj.put(fid, folder.toJson()) }
            root.put("folders", foldersObj)

            val callsObj = JSONObject()
            discoveredCalls.forEach { (id, call) -> callsObj.put(id, call.toJson()) }
            root.put("calls", callsObj)

            val smsObj = JSONObject()
            discoveredSms.forEach { (id, s) -> smsObj.put(id, s.toJson()) }
            root.put("sms", smsObj)

            val wallsObj = JSONObject()
            discoveredWalls.forEach { (id, w) -> wallsObj.put(id, w.toJson()) }
            root.put("walls", wallsObj)

            val wifiObj = JSONObject()
            discoveredWifi.forEach { (id, w) -> wifiObj.put(id, w.toJson()) }
            root.put("wifi", wifiObj)

            val cacheFile = File(context.filesDir?.parentFile, CACHE_FILE_NAME)
            cacheFile.writeText(root.toString(2), StandardCharsets.UTF_8)
            cacheFile.setReadable(true, false)
        } catch (_: Throwable) {}
    }

    private fun hookAppCloudBackups(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return
        val companionClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$a") ?: return

        companionClass.declaredMethods.forEach { m ->
            when (m.name) {
                "fromSnapshot" -> attempt("hook fromSnapshot", silent = true) {
                    module.hookTracked(m, idPrefix = "cloud-discovery-app-backups-fromSnapshot").intercept { chain ->
                        val initialResult = chain.proceed()
                        if (initialResult != null) return@intercept initialResult

                        ensureScan(context, classLoader, targets)
                        val key = chain.args.firstOrNull()?.let { arg ->
                            attempt("read snapshot key", silent = true) {
                                val zc2Obj = arg.getFieldValue("b")
                                zc2Obj?.javaClass?.getDeclaredMethod("e")?.invoke(zc2Obj) as? String
                            }
                        }

                        if (key != null) {
                            findMatchingBackup(key)?.let { app ->
                                buildAppCloudBackup(app, classLoader)?.let { backup ->
                                    return@intercept createBackupsObject(backup, classLoader)
                                }
                            }
                        }
                        null
                    }
                }
                "fetchForPackage" -> attempt("hook fetchForPackage", silent = true) {
                    module.hookTracked(m, idPrefix = "cloud-discovery-app-backups-fetchForPackage").intercept { chain ->
                        val initialResult = chain.proceed()
                        val pkgName = chain.args.firstOrNull() as? String
                        if (pkgName != null && (initialResult == null || isResultEmpty(initialResult))) {
                            ensureScan(context, classLoader, targets)
                            findMatchingBackup(pkgName)?.let { app ->
                                buildAppCloudBackup(app, classLoader)?.let { backup ->
                                    val backupsObj = createBackupsObject(backup, classLoader)
                                    val resultCtor = m.returnType.getConstructor(appCloudBackupsClass, loadClassFlexible(classLoader, "wc2"))
                                    return@intercept resultCtor.newInstance(backupsObj, null)
                                }
                            }
                        }
                        initialResult
                    }
                }
            }
        }
    }

    private fun hookDetailCloudListener(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val lk2Class = loadClassFlexible(classLoader, "lk2") ?: return
        lk2Class.declaredMethods.filter { it.name == "onDataChange" }.forEach { m ->
            attempt("hook lk2.onDataChange", silent = true) {
                module.hookTracked(m, idPrefix = "cloud-discovery-detail-listener-onDataChange").intercept { chain ->
                    val snapshot = chain.args.firstOrNull()
                    val snapshotHasData = attempt("check snapshot exists", silent = true) {
                        if (snapshot == null) false
                        else {
                            val exists = snapshot.javaClass.getMethod("exists").invoke(snapshot) as? Boolean
                            val value = snapshot.javaClass.getMethod("getValue").invoke(snapshot)
                            val childrenCount = snapshot.javaClass.getMethod("getChildrenCount").invoke(snapshot) as? Long
                            (exists == true) || (value != null) || (childrenCount != null && childrenCount > 0L)
                        }
                    } ?: false

                    if (snapshotHasData) {
                        return@intercept chain.proceed()
                    }

                    val lk2Instance = chain.thisObject ?: return@intercept chain.proceed()
                    val mk2Instance = lk2Instance.getFieldValue("a") ?: return@intercept chain.proceed()
                    val jiInstance = mk2Instance.getFieldValue("e") ?: return@intercept chain.proceed()

                    val existingCloudBackups = attempt("getCloudBackups", silent = true) {
                        jiInstance.javaClass.getDeclaredMethod("getCloudBackups").invoke(jiInstance)
                    }
                    if (existingCloudBackups != null && !isResultEmpty(existingCloudBackups)) {
                        return@intercept chain.proceed()
                    }

                    val pkgName = jiInstance.javaClass.getDeclaredMethod("getPackageName").invoke(jiInstance) as? String
                    if (pkgName != null) {
                        ensureScan(context, classLoader, targets)
                        findMatchingBackup(pkgName)?.let { matching ->

                            val injected = attempt("synthetic snapshot injection", silent = true) {
                                if (snapshot == null) return@attempt false
                                val queryRef = snapshot.getFieldValue("query")
                                    ?: snapshot.getFieldValue("a")
                                    ?: return@attempt false

                                val syntheticSnapshot = FirebaseSnapshotSynthesizer.createSyntheticSnapshot(
                                    classLoader, queryRef, matching
                                ) ?: return@attempt false

                                chain.proceed(arrayOf(syntheticSnapshot))
                                Log.i(TAG, "[CloudDiscovery] \u2713 Synthetic DataSnapshot injected for $pkgName")
                                true
                            } ?: false

                            if (injected) return@intercept null

                            Log.d(TAG, "[CloudDiscovery] Snapshot synthesis unavailable for $pkgName, falling back to UI reflection")
                            buildAppCloudBackup(matching, classLoader)?.let { cloudBackup ->
                                createBackupsObject(cloudBackup, classLoader)?.let { backupsObj ->
                                    val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups")
                                    jiInstance.javaClass.getDeclaredMethod("setCloudBackups", appCloudBackupsClass).invoke(jiInstance, backupsObj)

                                    try {
                                        val ex6Instance = mk2Instance.getFieldValue("l") ?: return@let
                                        val wj2Class = loadClassFlexible(classLoader, "wj2")!!
                                        val yj2Class = loadClassFlexible(classLoader, "yj2")!!
                                        val xj2Class = loadClassFlexible(classLoader, "xj2")!!
                                        val backedUpEnum = xj2Class.enumConstants?.firstOrNull { it.toString() == "BackedUp" }

                                        val apkSizeStr = formatBytes(matching.apkSize + matching.splitsSize)
                                        val dataSizeStr = if (matching.dataSize > 0) "${formatBytes(matching.dataSize)} \uD83D\uDD12" else ""
                                        val extDataSizeStr = if (matching.extDataSize > 0) "${formatBytes(matching.extDataSize)} \uD83D\uDD12" else ""
                                        val totalSizeStr = formatBytes(matching.totalSize)

                                        val wj2Item = wj2Class.constructors.first().newInstance(
                                            cloudBackup, apkSizeStr, matching.splitsLink != null, false,
                                            dataSizeStr, matching.dataLink != null, "StandardEncryption",
                                            extDataSizeStr, matching.extDataLink != null, "StandardEncryption",
                                            "", false, null, "", totalSizeStr, "Cloud Backup ($totalSizeStr)",
                                            "Version: 1.0", "Version: 1.0 (1)", false
                                        )

                                        val yj2Instance = yj2Class.getConstructor(xj2Class, List::class.java).newInstance(backedUpEnum, listOf(wj2Item))
                                        ex6Instance.javaClass.getMethod("k", Any::class.java).invoke(ex6Instance, yj2Instance)
                                        Log.i(TAG, "[CloudDiscovery] Rendered cloud backup for $pkgName via UI reflection fallback")
                                        return@intercept null
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "[CloudDiscovery] UI reflection fallback also failed: ${t.message}")
                                    }
                                }
                            }
                        }
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun hookBatchCloudLoader(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val ua1Class = loadClassFlexible(classLoader, "ua1") ?: return
        ua1Class.declaredMethods.filter { it.name == "a" && it.parameterCount == 0 }.forEach { m ->
            module.hookTracked(m, idPrefix = "cloud-discovery-batch-loader").intercept { chain ->
                val result = chain.proceed()
                ensureScan(context, classLoader, targets)

                if (discoveredBackups.isEmpty()) return@intercept result

                val jiClass = loadClassFlexible(classLoader, "ji") ?: return@intercept result
                val getPkgMethod = jiClass.getDeclaredMethod("getPackageName")
                val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return@intercept result
                val fromCloudBackupsMethod = jiClass.getDeclaredMethod("fromCloudBackups", appCloudBackupsClass)
                val appBackupsCtor = appCloudBackupsClass.getConstructor(List::class.java)

                val existingList = attempt("extract list from batch restore result", silent = true) {
                    val listField = result?.javaClass?.declaredFields?.firstOrNull { List::class.java.isAssignableFrom(it.type) }
                    listField?.apply { isAccessible = true }?.get(result) as? List<*>
                } ?: emptyList<Any>()

                val existingPackages = existingList.mapNotNull { item ->
                    if (item != null) getPkgMethod.invoke(item) as? String else null
                }.toSet()

                val mergedList = mutableListOf<Any>()
                existingList.filterNotNull().forEach { mergedList.add(it) }

                var newlyAddedCount = 0
                for (app in discoveredBackups.values) {
                    if (existingPackages.contains(app.packageName) || existingPackages.contains(app.sanitizedAppId)) {
                        continue
                    }
                    buildAppCloudBackup(app, classLoader)?.let { cloudBackup ->
                        val backupsObj = appBackupsCtor.newInstance(listOf(cloudBackup))
                        fromCloudBackupsMethod.invoke(null, backupsObj)?.let {
                            mergedList.add(it)
                            newlyAddedCount++
                        }
                    }
                }

                if (newlyAddedCount > 0) {
                    val ik6Class = loadClassFlexible(classLoader, "ik6")
                    val hk6Class = loadClassFlexible(classLoader, "hk6")
                    val successEnum = hk6Class?.enumConstants?.firstOrNull { it.toString() == "Success" }
                    if (ik6Class != null && successEnum != null) {
                        val ctor = ik6Class.getConstructor(hk6Class, List::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        Log.i(TAG, "[CloudDiscovery] Returned ${mergedList.size} apps for Batch Cloud Restore (${existingList.size} from RTDB, $newlyAddedCount discovered)")
                        return@intercept ctor.newInstance(successEnum, mergedList, false, 12)
                    }
                }
                result
            }
        }
    }

    private fun hookAppFilterHelper(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val qqClass = loadClassFlexible(classLoader, "qq") ?: return
        qqClass.declaredMethods.filter { it.name == "b" && it.parameterCount == 2 }.forEach { m ->
            module.hookTracked(m, idPrefix = "cloud-discovery-filter-helper").intercept { chain ->
                val listArg = chain.args.getOrNull(0) as? List<*> ?: return@intercept chain.proceed()
                val ce3Arg = chain.args.getOrNull(1) ?: return@intercept chain.proceed()

                val originalFiltered = chain.proceed() as? List<*> ?: emptyList<Any>()

                if (ce3Arg.toString() == "Synced" && discoveredBackups.isNotEmpty()) {
                    ensureScan(context, classLoader, targets)

                    val jiClass = loadClassFlexible(classLoader, "ji") ?: return@intercept originalFiltered
                    val getPkgMethod = jiClass.getDeclaredMethod("getPackageName")
                    val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return@intercept originalFiltered
                    val setCloudBackupsMethod = jiClass.getDeclaredMethod("setCloudBackups", appCloudBackupsClass)
                    val appBackupsCtor = appCloudBackupsClass.getConstructor(List::class.java)

                    val existingSyncedPkgs = originalFiltered.mapNotNull { item ->
                        if (item != null) getPkgMethod.invoke(item) as? String else null
                    }.toSet()

                    val mergedFiltered = mutableListOf<Any>()
                    originalFiltered.filterNotNull().forEach { mergedFiltered.add(it) }

                    var newlyAddedCount = 0
                    for (item in listArg) {
                        if (item == null) continue
                        val pkg = getPkgMethod.invoke(item) as? String ?: continue
                        if (existingSyncedPkgs.contains(pkg)) continue

                        val existingCloudBackups = attempt("check existing cloud backups", silent = true) {
                            item.javaClass.getDeclaredMethod("getCloudBackups").invoke(item)
                        }
                        if (existingCloudBackups != null && !isResultEmpty(existingCloudBackups)) {
                            mergedFiltered.add(item)
                            continue
                        }

                        findMatchingBackup(pkg)?.let { matching ->
                            buildAppCloudBackup(matching, classLoader)?.let { cloudBackup ->
                                val backupsObj = appBackupsCtor.newInstance(listOf(cloudBackup))
                                setCloudBackupsMethod.invoke(item, backupsObj)
                                mergedFiltered.add(item)
                                newlyAddedCount++
                            }
                        }
                    }

                    if (newlyAddedCount > 0) {
                        Log.i(TAG, "[CloudDiscovery] Filtered ${mergedFiltered.size} apps for 'Cloud synced apps' (${originalFiltered.size} from RTDB, $newlyAddedCount discovered)")
                        return@intercept mergedFiltered
                    }
                }
                originalFiltered
            }
        }
    }

    private fun hookCloudSyncTab(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val ng1Class = loadClassFlexible(classLoader, "ng1")
        ng1Class?.declaredMethods?.filter { it.name == "c" }?.forEach { m ->
            module.hookTracked(m, idPrefix = "cloud-discovery-sync-tab-vm").intercept { chain ->
                val result = chain.proceed()
                ensureScan(context, classLoader, targets)
                result
            }
        }

        val jg1Class = loadClassFlexible(classLoader, "jg1")
        jg1Class?.declaredMethods?.filter { it.name == "onDataChange" }?.forEach { m ->
            module.hookTracked(m, idPrefix = "cloud-discovery-sync-tab-listener").intercept { chain ->
                val snapshot = chain.args.firstOrNull()
                val snapshotHasData = attempt("check sync tab snapshot", silent = true) {
                    if (snapshot == null) false
                    else {
                        val exists = snapshot.javaClass.getMethod("exists").invoke(snapshot) as? Boolean
                        val hasChildren = snapshot.javaClass.getMethod("hasChildren").invoke(snapshot) as? Boolean
                        (exists == true) && (hasChildren == true)
                    }
                } ?: false

                if (snapshotHasData) {
                    return@intercept chain.proceed()
                }

                val hasDiscovered = discoveredBackups.isNotEmpty() || discoveredFolders.isNotEmpty() ||
                        discoveredCalls.isNotEmpty() || discoveredSms.isNotEmpty() ||
                        discoveredWalls.isNotEmpty() || discoveredWifi.isNotEmpty()

                if (!hasDiscovered) return@intercept chain.proceed()

                val injected = attempt("synthetic sync stats injection", silent = true) {
                    if (snapshot == null) return@attempt false
                    val queryRef = snapshot.getFieldValue("query")
                        ?: snapshot.getFieldValue("a")
                        ?: return@attempt false

                    val totalApps = discoveredBackups.size
                    val totalSpace = discoveredBackups.values.sumOf { it.totalSize } +
                            discoveredFolders.values.sumOf { it.totalSize } +
                            discoveredCalls.values.sumOf { it.size } +
                            discoveredSms.values.sumOf { it.size } +
                            discoveredWalls.values.sumOf { it.size } +
                            discoveredWifi.values.sumOf { it.size }
                    val totalMessages = discoveredSms.size
                    val totalCalls = discoveredCalls.size
                    val totalFolders = discoveredFolders.size

                    val syntheticSnapshot = FirebaseSnapshotSynthesizer.createSyncStatsSnapshot(
                        classLoader, queryRef,
                        totalApps, totalSpace, totalMessages, totalCalls, totalFolders
                    ) ?: return@attempt false

                    chain.proceed(arrayOf(syntheticSnapshot))
                    Log.i(TAG, "[CloudDiscovery] ✓ Synthetic sync stats DataSnapshot injected")
                    true
                } ?: false

                if (injected) return@intercept null

                Log.d(TAG, "[CloudDiscovery] Sync stats snapshot synthesis unavailable, falling back to z8 reflection")
                val jg1Instance = chain.thisObject ?: return@intercept chain.proceed()
                val ng1Instance = jg1Instance.getFieldValue("q")
                postCloudSyncStats(ng1Instance, classLoader)
                return@intercept null
            }
        }
    }

    private fun postCloudSyncStats(ng1Instance: Any?, classLoader: ClassLoader) {
        if (ng1Instance == null) return
        mainHandler.post {
            try {
                val ex6Instance = ng1Instance.getFieldValue("f") ?: return@post
                val z8Class = loadClassFlexible(classLoader, "z8") ?: return@post
                val z8Ctor = z8Class.getConstructor(
                    Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )

                val totalApps = discoveredBackups.size
                val totalSpace = discoveredBackups.values.sumOf { it.totalSize } +
                        discoveredFolders.values.sumOf { it.totalSize } +
                        discoveredCalls.values.sumOf { it.size } +
                        discoveredSms.values.sumOf { it.size } +
                        discoveredWalls.values.sumOf { it.size } +
                        discoveredWifi.values.sumOf { it.size }
                val totalMessages = discoveredSms.size
                val totalCalls = discoveredCalls.size
                val totalFolders = discoveredFolders.size

                val z8Instance = z8Ctor.newInstance(totalApps, totalSpace, totalMessages, totalCalls, totalFolders)
                ex6Instance.javaClass.getMethod("k", Any::class.java).invoke(ex6Instance, z8Instance)
                Log.i(TAG, "[CloudDiscovery] Posted Cloud Sync tab stats via fallback: $totalApps apps, $totalFolders folders, $totalCalls calls, $totalMessages sms, ${formatBytes(totalSpace)}")
            } catch (t: Throwable) {
                Log.e(TAG, "[CloudDiscovery] Failed to post Cloud Sync stats: ${t.message}")
            }
        }
    }

    private fun hookCloudBackupTags(module: XposedModule, classLoader: ClassLoader) {
        val ob1Class = loadClassFlexible(classLoader, "ob1") ?: return
        ob1Class.declaredMethods.filter { it.name == "a" }.forEach { m ->
            module.hookTracked(m, idPrefix = "cloud-discovery-backup-tags").intercept { chain ->
                val result = chain.proceed() as? List<*> ?: mutableListOf<String>()
                val tags = result.filterIsInstance<String>().toMutableList()
                for (app in discoveredBackups.values) {
                    if (app.backupTag.isNotBlank() && !tags.contains(app.backupTag)) {
                        tags.add(app.backupTag)
                    }
                }
                for (folder in discoveredFolders.values) {
                    if (folder.tag.isNotBlank() && !tags.contains(folder.tag)) {
                        tags.add(folder.tag)
                    }
                }
                for (call in discoveredCalls.values) {
                    if (call.tag.isNotBlank() && !tags.contains(call.tag)) {
                        tags.add(call.tag)
                    }
                }
                for (sms in discoveredSms.values) {
                    if (sms.tag.isNotBlank() && !tags.contains(sms.tag)) {
                        tags.add(sms.tag)
                    }
                }
                tags
            }
        }
    }

    private fun hookWallpaperCloudLoader(
        module: XposedModule,
        classLoader: ClassLoader,
        factory: DriveFileItemFactory
    ) {
        val fu3Class = loadClassFlexible(classLoader, "fu3") ?: return
        val kMethod = fu3Class.declaredMethods.firstOrNull { it.name == "k" && it.parameterCount == 0 } ?: return
        if (!factory.isAvailable) return

        module.hookTracked(
            kMethod,
            idPrefix = "gdrive-wall-cloud-k",
            priority = XposedInterface.PRIORITY_HIGHEST,
            deoptimize = true
        ).intercept { chain ->
            val original = chain.proceed()
            if (discoveredWalls.isEmpty()) return@intercept original

            val existingWalls = factory.extractExistingItems(original)
            val existingFileIds = existingWalls.mapNotNull { factory.extractFileId(it) }.toSet()

            val pg1List = ArrayList<Any>(existingWalls)
            var newlyAdded = 0

            for (wall in discoveredWalls.values) {
                if (existingFileIds.contains(wall.fileId) || existingFileIds.contains(wall.fileName)) {
                    continue
                }
                factory.create(wall.fileName, wall.fileId, wall.size, wall.timestamp, wall.thumbnailLink)?.let {
                    pg1List.add(it)
                    newlyAdded++
                }
            }

            if (newlyAdded > 0) {
                factory.wrapInResult(pg1List) ?: original
            } else {
                original
            }
        }

        hookWallpaperClickFallback(module, classLoader)
    }

    private fun hookWallpaperClickFallback(module: XposedModule, classLoader: ClassLoader) {
        val xr0Class = loadClassFlexible(classLoader, "xr0")
        val onClickMethod = xr0Class?.declaredMethods?.firstOrNull { it.name == "onClick" && it.parameterCount == 1 }
        if (onClickMethod != null) {
            module.hookTracked(
                onClickMethod,
                idPrefix = "wall-item-click-fallback",
                priority = XposedInterface.PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                val thisObj = chain.thisObject ?: return@intercept chain.proceed()
                val aVal = attempt("read xr0.a", silent = true) {
                    xr0Class.getDeclaredField("a").apply { isAccessible = true }.getInt(thisObj)
                } ?: -1

                if (aVal != 0) {
                    val bObj = xr0Class.getDeclaredField("b").apply { isAccessible = true }.get(thisObj)
                    val cObj = xr0Class.getDeclaredField("c").apply { isAccessible = true }.get(thisObj)
                    val dObj = xr0Class.getDeclaredField("d").apply { isAccessible = true }.get(thisObj)

                    if (bObj != null && cObj != null && dObj != null) {
                        attempt("fallback wallpaper click", silent = true) {
                            val lo8Class = bObj.javaClass
                            val ivWall = lo8Class.getDeclaredField("u").apply { isAccessible = true }.get(bObj) as? ImageView
                            if (ivWall != null && ivWall.drawable == null) {
                                val mo8Class = cObj.javaClass
                                val eField = mo8Class.getField("e").get(cObj)
                                val isMultiSelect = eField?.javaClass?.getField("c")?.getBoolean(eField) ?: false
                                if (!isMultiSelect) {
                                    val lField = mo8Class.getField("l").get(cObj)
                                    val ao8Class = loadClassFlexible(classLoader, "ao8")
                                    if (ao8Class != null && lField != null) {
                                        val dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                                        val ao8Instance = ao8Class.getConstructor(dObj.javaClass, Bitmap::class.java).newInstance(dObj, dummyBitmap)
                                        lField.javaClass.getMethod("U", ao8Class).invoke(lField, ao8Instance)
                                        return@intercept null
                                    }
                                }
                            }
                        }
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun hookWifiCloudLoader(
        module: XposedModule,
        classLoader: ClassLoader,
        factory: DriveFileItemFactory
    ) {
        val us8Class = loadClassFlexible(classLoader, "us8")
        val cMethod = us8Class?.declaredMethods?.firstOrNull { it.name == "c" && it.parameterCount == 0 && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val wifiCloudDetailsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.firebase.WifiCloudDetails")

        if (cMethod != null && wifiCloudDetailsClass != null) {
            module.hookTracked(
                cMethod,
                idPrefix = "wifi-helper-cloud-details-c",
                priority = XposedInterface.PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                val original = chain.proceed()
                if (original != null) return@intercept original
                if (discoveredWifi.isEmpty()) return@intercept original

                attempt("inject discovered wifi into us8.c", silent = true) {
                    val firstWifi = discoveredWifi.values.firstOrNull() ?: return@attempt original
                    wifiCloudDetailsClass.getConstructor(String::class.java, Long::class.javaObjectType, Int::class.javaObjectType)
                        .newInstance(firstWifi.fileId, firstWifi.size, firstWifi.count)
                } ?: original
            }
        }

        val fu3Class = loadClassFlexible(classLoader, "fu3") ?: return
        val lMethod = fu3Class.declaredMethods.firstOrNull { it.name == "l" && it.parameterCount == 0 } ?: return
        if (!factory.isAvailable) return

        module.hookTracked(
            lMethod,
            idPrefix = "gdrive-wifi-cloud-l",
            priority = XposedInterface.PRIORITY_HIGHEST,
            deoptimize = true
        ).intercept { chain ->
            val original = chain.proceed()
            if (discoveredWifi.isEmpty()) return@intercept original

            val existingWifi = factory.extractExistingItems(original)
            val existingFileIds = existingWifi.mapNotNull { factory.extractFileId(it) }.toSet()

            val pg1List = ArrayList<Any>(existingWifi)
            var newlyAdded = 0

            for (wifi in discoveredWifi.values) {
                if (existingFileIds.contains(wifi.fileId) || existingFileIds.contains(wifi.fileName)) {
                    continue
                }
                factory.create(wifi.fileName, wifi.fileId, wifi.size)?.let {
                    pg1List.add(it)
                    newlyAdded++
                }
            }

            if (newlyAdded > 0) {
                factory.wrapInResult(pg1List) ?: original
            } else {
                original
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.2f %s", size, units[unitIndex])
    }

    private fun isResultEmpty(result: Any): Boolean = attempt("check isResultEmpty", silent = true) {
        val cloudBackups = result.javaClass.getDeclaredMethod("getAppCloudBackups").invoke(result) ?: return@attempt true
        val backupsList = attempt("getBackups", silent = true) {
            cloudBackups.javaClass.getDeclaredMethod("getBackups").invoke(cloudBackups) as? List<*>
        } ?: attempt("field a", silent = true) {
            cloudBackups.getFieldValue("a") as? List<*>
        }
        backupsList == null || backupsList.isEmpty()
    } ?: false

    fun buildAppCloudBackup(app: DiscoveredCloudApp, classLoader: ClassLoader): Any? = attempt("build AppCloudBackup", silent = true) {
        val metaClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.CloudMetadata") ?: return null
        val backupClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackup") ?: return null
        val metaCtor = metaClass.constructors.first { it.parameterCount >= 60 }
        val now = app.dateBackup
        val args = arrayOfNulls<Any>(metaCtor.parameterCount)

        fun set(idx: Int, value: Any?) { if (idx < args.size) args[idx] = value }

        set(0, app.packageName); set(1, app.packageName); set(2, now); set(3, now); set(4, "1.0"); set(5, 1L)
        set(6, app.apkLink)
        if (app.apkSize > 0) set(7, app.apkSize)
        if (app.apkLink != null) { set(8, now); set(9, 580L); set(10, "v4.2.3") }
        set(11, app.splitsLink)
        if (app.splitsSize > 0) set(12, app.splitsSize)
        if (app.splitsLink != null) { set(14, 580L); set(15, "v4.2.3") }
        set(21, app.dataLink)
        if (app.dataSize > 0) set(22, app.dataSize)
        if (app.dataLink != null) { set(24, true); set(25, "StandardEncryption"); set(27, now); set(28, 580L); set(29, "v4.2.3") }
        set(30, app.extDataLink)
        if (app.extDataSize > 0) set(31, app.extDataSize)
        if (app.extDataLink != null) { set(33, true); set(34, "StandardEncryption"); set(36, now); set(37, 580L); set(38, "v4.2.3") }
        set(54, 580L); set(56, app.permissionStatesCsv); set(58, app.extraLink)
        if (app.extraSize > 0) set(59, app.extraSize)
        set(61, app.ssaid); set(63, false); set(65, 1)

        val metaObj = metaCtor.newInstance(*args)
        backupClass.getConstructor(String::class.java, metaClass).newInstance(app.backupId, metaObj)
    }

    fun discoverDriveBackups(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ): Int = discoverAllCloudBackups(context, classLoader, targets)

    fun discoverAllCloudBackups(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ): Int {
        val sp: SharedPreferences = attempt("get swiftbackup prefs", silent = true) {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } ?: return 0

        val deviceTag = sp.getString("google_drive_cloud_backup_tag", null)
            ?: sp.getString("cloud_backup_tag", null)
            ?: "DEFAULT"

        val candidateUids = resolveCandidateUids(context, classLoader, targets)
        Log.d(TAG, "[CloudDiscovery] Starting discovery across all configured cloud providers...")

        val providerResults = CloudScannerRegistry.scanAllConfiguredProviders(context)
        if (providerResults.isEmpty()) {
            Log.d(TAG, "[CloudDiscovery] No cloud providers returned items")
            return 0
        }

        val appRegex = Pattern.compile("^(.*?)\\.([a-z]+)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")
        val folderRegex = Pattern.compile("^folder-base\\.(fld|flm)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")
        val callRegex = Pattern.compile("^v3\\.(\\d+)\\.(\\d+)\\.(.*?)\\.cls(?:\\s+\\((.*?)\\))?$")
        val callFallbackRegex = Pattern.compile("^(.*?)\\.cls(?:\\s+\\((.*?)\\))?$")
        val smsRegex = Pattern.compile("^v3\\.(\\d+)\\.(\\d+)\\.(\\d+)\\.(.*?)\\.msg(?:\\s+\\((.*?)\\))?$")
        val smsFallbackRegex = Pattern.compile("^(.*?)\\.msg(?:\\s+\\((.*?)\\))?$")
        val wallRegex = Pattern.compile("^(.*?)\\.wal(?:\\.png)?(?:\\s+\\((.*?)\\))?$")
        val wifiRegex = Pattern.compile("^(.*?)\\.wfi(?:\\s+\\((.*?)\\))?$")

        var totalIndexedCount = 0

        for (providerResult in providerResults) {
            val scanner = providerResult.scanner
            val fileList = providerResult.items
            val providerName = scanner.providerName

            val appGroups = mutableMapOf<Triple<String, String, String>, MutableMap<String, CloudFileItem>>()
            val folderGroups = mutableMapOf<Pair<String, String>, MutableMap<String, CloudFileItem>>()

            for (fileObj in fileList) {
                val fileName = fileObj.name
                val fileId = fileObj.id
                val fileSize = fileObj.size

                val folderMatcher = folderRegex.matcher(fileName)
                if (folderMatcher.matches()) {
                    val part = folderMatcher.group(1) ?: continue
                    val tag = folderMatcher.group(2) ?: deviceTag
                    val folderIdClean = folderMatcher.group(3) ?: continue
                    folderGroups.getOrPut(Pair(folderIdClean, tag)) { mutableMapOf() }[part] = fileObj
                    continue
                }

                val callMatcher = callRegex.matcher(fileName)
                if (callMatcher.matches()) {
                    val ts = callMatcher.group(1)?.toLongOrNull() ?: fileObj.timestamp
                    val count = callMatcher.group(2)?.toIntOrNull() ?: 1
                    val tag = callMatcher.group(4) ?: deviceTag
                    discoveredCalls[fileId] = DiscoveredCloudCall(fileId, fileName, fileSize, count, tag, ts, providerName)
                    totalIndexedCount++
                    continue
                } else {
                    val callFbMatcher = callFallbackRegex.matcher(fileName)
                    if (callFbMatcher.matches()) {
                        val tag = callFbMatcher.group(2) ?: deviceTag
                        discoveredCalls[fileId] = DiscoveredCloudCall(fileId, fileName, fileSize, 1, tag, fileObj.timestamp, providerName)
                        totalIndexedCount++
                        continue
                    }
                }

                val smsMatcher = smsRegex.matcher(fileName)
                if (smsMatcher.matches()) {
                    val ts = smsMatcher.group(1)?.toLongOrNull() ?: fileObj.timestamp
                    val totalCount = smsMatcher.group(3)?.toIntOrNull() ?: 1
                    val tag = smsMatcher.group(5) ?: deviceTag
                    discoveredSms[fileId] = DiscoveredCloudSms(fileId, fileName, fileSize, totalCount, tag, ts, providerName)
                    totalIndexedCount++
                    continue
                } else {
                    val smsFbMatcher = smsFallbackRegex.matcher(fileName)
                    if (smsFbMatcher.matches()) {
                        val tag = smsFbMatcher.group(2) ?: deviceTag
                        discoveredSms[fileId] = DiscoveredCloudSms(fileId, fileName, fileSize, 1, tag, fileObj.timestamp, providerName)
                        totalIndexedCount++
                        continue
                    }
                }

                val wallMatcher = wallRegex.matcher(fileName)
                if (wallMatcher.matches()) {
                    val rawTs = wallMatcher.group(1)
                    val ts = rawTs?.toLongOrNull() ?: fileObj.timestamp
                    val thumbnailLink = fileObj.thumbnailLink

                    val cleanFileName = when {
                        fileName.contains("home_wall") -> "home_wall.wal"
                        fileName.contains("lock_wall") -> "lock_wall.wal"
                        fileName.endsWith(".wal") && !fileName.contains(" ") -> fileName
                        else -> "$ts.wal"
                    }

                    discoveredWalls[fileId] = DiscoveredCloudWall(
                        fileId = fileId,
                        fileName = cleanFileName,
                        size = fileSize,
                        timestamp = ts,
                        thumbnailLink = thumbnailLink,
                        provider = providerName
                    )
                    totalIndexedCount++
                    continue
                }

                val wifiMatcher = wifiRegex.matcher(fileName)
                if (wifiMatcher.matches()) {
                    discoveredWifi[fileId] = DiscoveredCloudWifi(fileId, fileName, fileSize, 1, providerName)
                    totalIndexedCount++
                    continue
                }

                val appMatcher = appRegex.matcher(fileName)
                if (appMatcher.matches()) {
                    val pkg = appMatcher.group(1) ?: continue
                    if (!AppUtils.isValidPackageName(pkg)) {
                        Log.d(TAG, "[CloudDiscovery] Skipping non-package file: $fileName")
                        continue
                    }
                    val part = appMatcher.group(2) ?: continue
                    val tag = appMatcher.group(3) ?: continue
                    val backupId = appMatcher.group(4) ?: continue
                    appGroups.getOrPut(Triple(pkg, backupId, tag)) { mutableMapOf() }[part] = fileObj
                }
            }

            for ((key, parts) in appGroups) {
                val (pkg, backupId, tag) = key
                val sanitizedAppId = pkg.replace(".", "")

                var ssaid: String? = null
                var permissionStatesCsv: String? = null
                var notificationPolicyXml: String? = null

                val extraItem = parts["extra"]
                val extraFileId = extraItem?.id
                val extraSize = extraItem?.size ?: 0L

                if (extraItem != null) {
                    val rawExtraText = scanner.downloadFileText(context, sp, extraItem)
                    if (rawExtraText != null) {
                        val extra = BackupCrypto.parseExtraPayload(rawExtraText, candidateUids, classLoader)
                        if (extra != null) {
                            ssaid = extra.ssaid
                            permissionStatesCsv = extra.permissionStatesCsv
                            notificationPolicyXml = extra.notificationPolicyXml
                        }
                    }
                }

                val apkFileId = parts["app"]?.id
                val apkSize = parts["app"]?.size ?: 0L
                val dataFileId = parts["dat"]?.id
                val dataSize = parts["dat"]?.size ?: 0L
                val extDataFileId = parts["extdat"]?.id
                val extDataSize = parts["extdat"]?.size ?: 0L
                val splitsFileId = parts["splits"]?.id
                val splitsSize = parts["splits"]?.size ?: 0L

                val discovered = DiscoveredCloudApp(
                    packageName = pkg,
                    sanitizedAppId = sanitizedAppId,
                    backupId = backupId,
                    backupTag = tag,
                    apkLink = apkFileId,
                    apkSize = apkSize,
                    dataLink = dataFileId,
                    dataSize = dataSize,
                    extDataLink = extDataFileId,
                    extDataSize = extDataSize,
                    splitsLink = splitsFileId,
                    splitsSize = splitsSize,
                    extraLink = extraFileId,
                    extraSize = extraSize,
                    totalSize = apkSize + dataSize + extDataSize + splitsSize,
                    ssaid = ssaid,
                    permissionStatesCsv = permissionStatesCsv,
                    notificationPolicyXml = notificationPolicyXml,
                    provider = providerName
                )
                discoveredBackups[pkg] = discovered
                totalIndexedCount++
            }

            for ((key, parts) in folderGroups) {
                val (fid, tag) = key
                val fldObj = parts["fld"]
                val flmObj = parts["flm"]
                val fldLink = fldObj?.id
                val fldSize = fldObj?.size ?: 0L
                val flmLink = flmObj?.id
                val flmSize = flmObj?.size ?: 0L

                var displayName = "Folder-$fid"
                var sourceFolder = "/storage/emulated/0"
                val accountsDir = File(Environment.getExternalStorageDirectory(), "SwiftBackup/accounts")
                if (accountsDir.isDirectory) {
                    accountsDir.listFiles { f -> f.isDirectory }?.forEach { acc ->
                        val localMetaFile = File(acc, "backups/folders/local/Folder-$fid/metadata.json")
                        if (localMetaFile.exists()) {
                            attempt("read local folder metadata", silent = true) {
                                val obj = JSONObject(localMetaFile.readText(StandardCharsets.UTF_8))
                                obj.optJSONObject("folderItem")?.let { item ->
                                    item.optString("displayName").takeIf { it.isNotBlank() }?.let { displayName = it }
                                    item.optString("sourceFolder").takeIf { it.isNotBlank() }?.let { sourceFolder = it }
                                }
                            }
                        }
                    }
                }

                val discoveredFolder = DiscoveredCloudFolder(
                    id = fid,
                    displayName = displayName,
                    tag = tag,
                    fldLink = fldLink,
                    fldSize = fldSize,
                    flmLink = flmLink,
                    flmSize = flmSize,
                    totalSize = fldSize + flmSize,
                    timestamp = System.currentTimeMillis(),
                    sourceFolder = sourceFolder,
                    provider = providerName
                )
                discoveredFolders[fid] = discoveredFolder
                totalIndexedCount++
            }
        }

        saveDiskCache(context)
        Log.i(TAG, "[CloudDiscovery] Successfully indexed $totalIndexedCount cloud items across providers into catalog")
        return totalIndexedCount
    }

    fun decompressZstdOrRaw(bytes: ByteArray, classLoader: ClassLoader): String? =
        BackupCrypto.decompressZstdOrRaw(bytes, classLoader)

    fun resolveCandidateUids(context: Context?, classLoader: ClassLoader, targets: ResolvedTargets? = null): List<String> =
        BackupCrypto.resolveCandidateUids(context, classLoader, targets)
}
