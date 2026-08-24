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
import io.github.s1ddhants1.swiftbackupprem.hook.getFieldValue
import io.github.s1ddhants1.swiftbackupprem.hook.hookTracked
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.loadClassFlexible
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Cloud Discovery & Direct Metadata Indexing Hook:
 * Discovers cloud backups (apps, system data, and folders) directly from Google Drive, downloads & decodes
 * metadata using Facebook Conceal AES-GCM-256 + Zstandard, and injects them into Swift Backup's cloud catalog.
 */
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
        val sourceFolder: String = "/storage/emulated/0"
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
                sourceFolder = obj.optString("sourceFolder", "/storage/emulated/0")
            )
        }
    }

    data class DiscoveredCloudCall(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val count: Int,
        val tag: String,
        val timestamp: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size)
            put("count", count); put("tag", tag); put("timestamp", timestamp)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudCall = DiscoveredCloudCall(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                count = obj.optInt("count", 1),
                tag = obj.optString("tag", "DEFAULT"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    data class DiscoveredCloudSms(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val totalCount: Int,
        val tag: String,
        val timestamp: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size)
            put("totalCount", totalCount); put("tag", tag); put("timestamp", timestamp)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudSms = DiscoveredCloudSms(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                totalCount = obj.optInt("totalCount", 1),
                tag = obj.optString("tag", "DEFAULT"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    data class DiscoveredCloudWall(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val timestamp: Long,
        val thumbnailLink: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size); put("timestamp", timestamp)
            thumbnailLink?.let { put("thumbnailLink", it) }
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudWall = DiscoveredCloudWall(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                thumbnailLink = obj.optString("thumbnailLink").takeIf { it.isNotBlank() }
            )
        }
    }

    data class DiscoveredCloudWifi(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val count: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size); put("count", count)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudWifi = DiscoveredCloudWifi(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                count = obj.optInt("count", 1)
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
        val dateBackup: Long = System.currentTimeMillis()
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
                    dateBackup = obj.optLong("dateBackup", System.currentTimeMillis())
                )
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

        Log.d(TAG, "Applying CloudDiscoveryHook (Drive discovery & full-app cloud metadata indexing)")
        loadDiskCache(context)
        hookAppCloudBackups(module, context, classLoader, targets)
        hookDetailCloudListener(module, context, classLoader, targets)
        hookBatchCloudLoader(module, context, classLoader, targets)
        hookAppFilterHelper(module, context, classLoader, targets)
        hookCloudSyncTab(module, context, classLoader, targets)
        hookCloudBackupTags(module, classLoader)
        hookWallpaperCloudLoader(module, classLoader)
        hookWifiCloudLoader(module, classLoader)
        startDriveScanWithRetry(context, classLoader, targets)
    }

    fun startDriveScanWithRetry(context: Context, classLoader: ClassLoader, targets: ResolvedTargets) {
        if (!isScanRunning.compareAndSet(false, true)) return
        scanExecutor.execute {
            try {
                for (delay in longArrayOf(500L, 2000L, 5000L, 10000L)) {
                    try {
                        Thread.sleep(delay)
                        val count = discoverDriveBackups(context, classLoader, targets)
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
        if (discoveredBackups.isEmpty()) startDriveScanWithRetry(context, classLoader, targets)
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
                
                // Apps
                val appsObj = root.optJSONObject("apps") ?: root
                appsObj.keys().forEach { pkg ->
                    if (AppUtils.isValidPackageName(pkg)) {
                        val appJson = appsObj.optJSONObject(pkg)
                        if (appJson != null) {
                            discoveredBackups[pkg] = DiscoveredCloudApp.fromJson(pkg, appJson)
                        }
                    }
                }

                // Folders
                root.optJSONObject("folders")?.let { foldersObj ->
                    foldersObj.keys().forEach { fid ->
                        val fJson = foldersObj.optJSONObject(fid)
                        if (fJson != null) {
                            discoveredFolders[fid] = DiscoveredCloudFolder.fromJson(fid, fJson)
                        }
                    }
                }

                // Calls
                root.optJSONObject("calls")?.let { callsObj ->
                    callsObj.keys().forEach { id ->
                        callsObj.optJSONObject(id)?.let { discoveredCalls[id] = DiscoveredCloudCall.fromJson(it) }
                    }
                }

                // SMS
                root.optJSONObject("sms")?.let { smsObj ->
                    smsObj.keys().forEach { id ->
                        smsObj.optJSONObject(id)?.let { discoveredSms[id] = DiscoveredCloudSms.fromJson(it) }
                    }
                }

                // Walls
                root.optJSONObject("walls")?.let { wallsObj ->
                    wallsObj.keys().forEach { id ->
                        wallsObj.optJSONObject(id)?.let { discoveredWalls[id] = DiscoveredCloudWall.fromJson(it) }
                    }
                }

                // WiFi
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
                    val lk2Instance = chain.thisObject ?: return@intercept chain.proceed()
                    val mk2Instance = lk2Instance.getFieldValue("a") ?: return@intercept chain.proceed()
                    val jiInstance = mk2Instance.getFieldValue("e") ?: return@intercept chain.proceed()

                    val pkgName = jiInstance.javaClass.getDeclaredMethod("getPackageName").invoke(jiInstance) as? String
                    if (pkgName != null) {
                        ensureScan(context, classLoader, targets)
                        findMatchingBackup(pkgName)?.let { matching ->
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
                                        Log.i(TAG, "[CloudDiscovery] Rendered cloud backup for $pkgName in UI directly")
                                        return@intercept null
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "[CloudDiscovery] Direct UI render error: ${t.message}")
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

                if (discoveredBackups.isNotEmpty()) {
                    val jiList = mutableListOf<Any>()
                    val jiClass = loadClassFlexible(classLoader, "ji") ?: return@intercept result
                    val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return@intercept result
                    val fromCloudBackupsMethod = jiClass.getDeclaredMethod("fromCloudBackups", appCloudBackupsClass)
                    val appBackupsCtor = appCloudBackupsClass.getConstructor(List::class.java)

                    for (app in discoveredBackups.values) {
                        buildAppCloudBackup(app, classLoader)?.let { cloudBackup ->
                            val backupsObj = appBackupsCtor.newInstance(listOf(cloudBackup))
                            fromCloudBackupsMethod.invoke(null, backupsObj)?.let { jiList.add(it) }
                        }
                    }

                    if (jiList.isNotEmpty()) {
                        val ik6Class = loadClassFlexible(classLoader, "ik6")
                        val hk6Class = loadClassFlexible(classLoader, "hk6")
                        val successEnum = hk6Class?.enumConstants?.firstOrNull { it.toString() == "Success" }
                        if (ik6Class != null && successEnum != null) {
                            val ctor = ik6Class.getConstructor(hk6Class, List::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            Log.i(TAG, "[CloudDiscovery] Returned ${jiList.size} apps for Batch Cloud Restore")
                            return@intercept ctor.newInstance(successEnum, jiList, false, 12)
                        }
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

                if (ce3Arg.toString() == "Synced") {
                    ensureScan(context, classLoader, targets)

                    if (discoveredBackups.isNotEmpty()) {
                        val filtered = mutableListOf<Any>()
                        val jiClass = loadClassFlexible(classLoader, "ji") ?: return@intercept chain.proceed()
                        val getPkgMethod = jiClass.getDeclaredMethod("getPackageName")
                        val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return@intercept chain.proceed()
                        val setCloudBackupsMethod = jiClass.getDeclaredMethod("setCloudBackups", appCloudBackupsClass)
                        val appBackupsCtor = appCloudBackupsClass.getConstructor(List::class.java)

                        for (item in listArg) {
                            if (item == null) continue
                            val pkg = getPkgMethod.invoke(item) as? String ?: continue
                            findMatchingBackup(pkg)?.let { matching ->
                                buildAppCloudBackup(matching, classLoader)?.let { cloudBackup ->
                                    val backupsObj = appBackupsCtor.newInstance(listOf(cloudBackup))
                                    setCloudBackupsMethod.invoke(item, backupsObj)
                                    filtered.add(item)
                                }
                            }
                        }
                        Log.i(TAG, "[CloudDiscovery] Filtered ${filtered.size} apps for 'Cloud synced apps'")
                        return@intercept filtered
                    }
                }
                chain.proceed()
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
                postCloudSyncStats(chain.thisObject, classLoader)
                result
            }
        }

        val jg1Class = loadClassFlexible(classLoader, "jg1")
        jg1Class?.declaredMethods?.filter { it.name == "onDataChange" }?.forEach { m ->
            module.hookTracked(m, idPrefix = "cloud-discovery-sync-tab-listener").intercept { chain ->
                val jg1Instance = chain.thisObject ?: return@intercept chain.proceed()
                val ng1Instance = jg1Instance.getFieldValue("q")

                if (discoveredBackups.isNotEmpty()) {
                    postCloudSyncStats(ng1Instance, classLoader)
                    return@intercept null
                }
                chain.proceed()
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
                Log.i(TAG, "[CloudDiscovery] Posted Cloud Sync tab stats: $totalApps apps, $totalFolders folders, $totalCalls calls, $totalMessages sms, ${formatBytes(totalSpace)}")
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

    private fun hookWallpaperCloudLoader(module: XposedModule, classLoader: ClassLoader) {
        val fu3Class = loadClassFlexible(classLoader, "fu3") ?: return
        val kMethod = fu3Class.declaredMethods.firstOrNull { it.name == "k" && it.parameterCount == 0 } ?: return
        val ui1Class = loadClassFlexible(classLoader, "ui1") ?: return
        val pg1Class = loadClassFlexible(classLoader, "pg1") ?: return

        module.hookTracked(
            kMethod,
            idPrefix = "gdrive-wall-cloud-k",
            priority = XposedInterface.PRIORITY_HIGHEST,
            deoptimize = true
        ).intercept { chain ->
            val original = chain.proceed()
            if (discoveredWalls.isEmpty()) return@intercept original

            attempt("inject discovered walls into fu3.k", silent = true) {
                val pg1List = ArrayList<Any>()
                for (wall in discoveredWalls.values) {
                    val pg1 = pg1Class.getConstructor(String::class.java, String::class.java).newInstance(wall.fileName, wall.fileId)
                    pg1Class.getDeclaredField("c").apply { isAccessible = true }.set(pg1, wall.size)
                    pg1Class.getDeclaredField("e").apply { isAccessible = true }.set(pg1, wall.timestamp)
                    if (!wall.thumbnailLink.isNullOrBlank()) {
                        pg1Class.getDeclaredField("f").apply { isAccessible = true }.set(pg1, wall.thumbnailLink)
                    }
                    pg1List.add(pg1)
                }
                ui1Class.getConstructor(Exception::class.java, List::class.java).newInstance(null, pg1List)
            } ?: original
        }

        // Hook wallpaper click handler to allow restore even if thumbnail image drawable hasn't loaded yet
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

                if (aVal != 0) { // Case 1: wallpaper click
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
                                    val lField = mo8Class.getField("l").get(cObj) // oo8 instance
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

    private fun hookWifiCloudLoader(module: XposedModule, classLoader: ClassLoader) {
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
                if (discoveredWifi.isEmpty()) return@intercept original

                attempt("inject discovered wifi into us8.c", silent = true) {
                    val firstWifi = discoveredWifi.values.firstOrNull() ?: return@attempt original
                    wifiCloudDetailsClass.getConstructor(String::class.java, java.lang.Long::class.java, java.lang.Integer::class.java)
                        .newInstance(firstWifi.fileId, firstWifi.size, firstWifi.count)
                } ?: original
            }
        }

        val fu3Class = loadClassFlexible(classLoader, "fu3") ?: return
        val lMethod = fu3Class.declaredMethods.firstOrNull { it.name == "l" && it.parameterCount == 0 } ?: return
        val ui1Class = loadClassFlexible(classLoader, "ui1") ?: return
        val pg1Class = loadClassFlexible(classLoader, "pg1") ?: return

        module.hookTracked(
            lMethod,
            idPrefix = "gdrive-wifi-cloud-l",
            priority = XposedInterface.PRIORITY_HIGHEST,
            deoptimize = true
        ).intercept { chain ->
            val original = chain.proceed()
            if (discoveredWifi.isEmpty()) return@intercept original

            attempt("inject discovered wifi into fu3.l", silent = true) {
                val pg1List = ArrayList<Any>()
                for (wifi in discoveredWifi.values) {
                    val pg1 = pg1Class.getConstructor(String::class.java, String::class.java).newInstance(wifi.fileName, wifi.fileId)
                    pg1Class.getDeclaredField("c").apply { isAccessible = true }.set(pg1, wifi.size)
                    pg1List.add(pg1)
                }
                ui1Class.getConstructor(Exception::class.java, List::class.java).newInstance(null, pg1List)
            } ?: original
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
        result.javaClass.getDeclaredMethod("getAppCloudBackups").invoke(result) == null
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
    ): Int {
        val sp: SharedPreferences = attempt("get swiftbackup prefs", silent = true) {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } ?: return 0

        val token = sp.getString("nogms_access_token", null) ?: return 0
        val folderId = sp.getString("google_drive_cloud_main_folder_id", null) ?: return 0
        val deviceTag = sp.getString("google_drive_cloud_backup_tag", null) ?: "DEFAULT"

        val candidateUids = resolveCandidateUids(context, classLoader, targets)
        Log.d(TAG, "[CloudDiscovery] Querying Google Drive folder $folderId for cloud backups...")

        val fileList = queryDriveFolderFiles(folderId, token)
        if (fileList.length() == 0) return 0

        val appRegex = Pattern.compile("^(.*?)\\.([a-z]+)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")
        val folderRegex = Pattern.compile("^folder-base\\.(fld|flm)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")
        val callRegex = Pattern.compile("^v3\\.(\\d+)\\.(\\d+)\\.(.*?)\\.cls(?:\\s+\\((.*?)\\))?$")
        val callFallbackRegex = Pattern.compile("^(.*?)\\.cls(?:\\s+\\((.*?)\\))?$")
        val smsRegex = Pattern.compile("^v3\\.(\\d+)\\.(\\d+)\\.(\\d+)\\.(.*?)\\.msg(?:\\s+\\((.*?)\\))?$")
        val smsFallbackRegex = Pattern.compile("^(.*?)\\.msg(?:\\s+\\((.*?)\\))?$")
        val wallRegex = Pattern.compile("^(.*?)\\.wal(?:\\.png)?(?:\\s+\\((.*?)\\))?$")
        val wifiRegex = Pattern.compile("^(.*?)\\.wfi(?:\\s+\\((.*?)\\))?$")

        val appGroups = mutableMapOf<Triple<String, String, String>, MutableMap<String, JSONObject>>()
        val folderGroups = mutableMapOf<Pair<String, String>, MutableMap<String, JSONObject>>()

        var indexedCount = 0

        for (i in 0 until fileList.length()) {
            val fileObj = fileList.getJSONObject(i)
            val fileName = fileObj.optString("name")
            val fileId = fileObj.optString("id")
            val fileSize = fileObj.optLong("size", 0L)

            // 1. Folders
            val folderMatcher = folderRegex.matcher(fileName)
            if (folderMatcher.matches()) {
                val part = folderMatcher.group(1) ?: continue
                val tag = folderMatcher.group(2) ?: deviceTag
                val folderIdClean = folderMatcher.group(3) ?: continue
                folderGroups.getOrPut(Pair(folderIdClean, tag)) { mutableMapOf() }[part] = fileObj
                continue
            }

            // 2. Call Logs (.cls)
            val callMatcher = callRegex.matcher(fileName)
            if (callMatcher.matches()) {
                val ts = callMatcher.group(1)?.toLongOrNull() ?: System.currentTimeMillis()
                val count = callMatcher.group(2)?.toIntOrNull() ?: 1
                val tag = callMatcher.group(4) ?: deviceTag
                discoveredCalls[fileId] = DiscoveredCloudCall(fileId, fileName, fileSize, count, tag, ts)
                indexedCount++
                continue
            } else {
                val callFbMatcher = callFallbackRegex.matcher(fileName)
                if (callFbMatcher.matches()) {
                    val tag = callFbMatcher.group(2) ?: deviceTag
                    discoveredCalls[fileId] = DiscoveredCloudCall(fileId, fileName, fileSize, 1, tag, System.currentTimeMillis())
                    indexedCount++
                    continue
                }
            }

            // 3. SMS (.msg)
            val smsMatcher = smsRegex.matcher(fileName)
            if (smsMatcher.matches()) {
                val ts = smsMatcher.group(1)?.toLongOrNull() ?: System.currentTimeMillis()
                val totalCount = smsMatcher.group(3)?.toIntOrNull() ?: 1
                val tag = smsMatcher.group(5) ?: deviceTag
                discoveredSms[fileId] = DiscoveredCloudSms(fileId, fileName, fileSize, totalCount, tag, ts)
                indexedCount++
                continue
            } else {
                val smsFbMatcher = smsFallbackRegex.matcher(fileName)
                if (smsFbMatcher.matches()) {
                    val tag = smsFbMatcher.group(2) ?: deviceTag
                    discoveredSms[fileId] = DiscoveredCloudSms(fileId, fileName, fileSize, 1, tag, System.currentTimeMillis())
                    indexedCount++
                    continue
                }
            }

            // 4. Wallpapers (.wal / .wal.png)
            val wallMatcher = wallRegex.matcher(fileName)
            if (wallMatcher.matches()) {
                val rawTs = wallMatcher.group(1)
                val ts = rawTs?.toLongOrNull() ?: System.currentTimeMillis()
                val thumbnailLink = fileObj.optString("thumbnailLink").takeIf { it.isNotBlank() }

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
                    thumbnailLink = thumbnailLink
                )
                indexedCount++
                continue
            }

            // 5. WiFi (.wfi)
            val wifiMatcher = wifiRegex.matcher(fileName)
            if (wifiMatcher.matches()) {
                discoveredWifi[fileId] = DiscoveredCloudWifi(fileId, fileName, fileSize, 1)
                indexedCount++
                continue
            }

            // 6. Apps (.app, .dat, .extra, etc.)
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

        // Process Apps
        for ((key, parts) in appGroups) {
            val (pkg, backupId, tag) = key
            val sanitizedAppId = pkg.replace(".", "")

            var ssaid: String? = null
            var permissionStatesCsv: String? = null
            var notificationPolicyXml: String? = null

            val extraObj = parts["extra"]
            val extraFileId = extraObj?.optString("id")
            val extraSize = extraObj?.optLong("size", 0L) ?: 0L

            if (!extraFileId.isNullOrBlank()) {
                val rawExtraText = downloadDriveFileText(extraFileId, token)
                if (rawExtraText != null) {
                    val extra = BackupCrypto.parseExtraPayload(rawExtraText, candidateUids, classLoader)
                    if (extra != null) {
                        ssaid = extra.ssaid
                        permissionStatesCsv = extra.permissionStatesCsv
                        notificationPolicyXml = extra.notificationPolicyXml
                    }
                }
            }

            val apkFileId = parts["app"]?.optString("id")
            val apkSize = parts["app"]?.optLong("size", 0L) ?: 0L
            val dataFileId = parts["dat"]?.optString("id")
            val dataSize = parts["dat"]?.optLong("size", 0L) ?: 0L
            val extDataFileId = parts["extdat"]?.optString("id")
            val extDataSize = parts["extdat"]?.optLong("size", 0L) ?: 0L
            val splitsFileId = parts["splits"]?.optString("id")
            val splitsSize = parts["splits"]?.optLong("size", 0L) ?: 0L

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
                notificationPolicyXml = notificationPolicyXml
            )
            discoveredBackups[pkg] = discovered
            syncToFirebaseRealtimeDb(classLoader, tag, sanitizedAppId, backupId, discovered)
            indexedCount++
        }

        // Process Folders
        for ((key, parts) in folderGroups) {
            val (fid, tag) = key
            val fldObj = parts["fld"]
            val flmObj = parts["flm"]
            val fldLink = fldObj?.optString("id")
            val fldSize = fldObj?.optLong("size", 0L) ?: 0L
            val flmLink = flmObj?.optString("id")
            val flmSize = flmObj?.optLong("size", 0L) ?: 0L

            // Resolve local folder displayName & sourceFolder if available
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
                sourceFolder = sourceFolder
            )
            discoveredFolders[fid] = discoveredFolder
            syncFolderToFirebaseRealtimeDb(classLoader, tag, fid, discoveredFolder)
            indexedCount++
        }

        // Sync System Data to Firebase RTDB
        syncSystemDataToFirebaseRealtimeDb(classLoader)

        saveDiskCache(context)
        Log.i(TAG, "[CloudDiscovery] Successfully indexed $indexedCount cloud items (apps, folders, calls, sms, walls, wifi) from Google Drive into catalog")
        return indexedCount
    }

    private fun syncFolderToFirebaseRealtimeDb(
        classLoader: ClassLoader,
        tag: String,
        folderId: String,
        folder: DiscoveredCloudFolder
    ) {
        val now = folder.timestamp
        val tsFormat = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        val tsStr = tsFormat.format(java.util.Date(now))

        val map = mapOf<String, Any>(
            "folderItem" to mapOf(
                "id" to folder.id,
                "displayName" to folder.displayName,
                "setupCreationTime" to folder.timestamp,
                "sourceFolder" to folder.sourceFolder
            ),
            "baseBackup" to mapOf(
                "backupLink" to (folder.fldLink ?: ""),
                "backupSize" to folder.fldSize,
                "manifestLink" to (folder.flmLink ?: ""),
                "manifestSize" to folder.flmSize,
                "originalSize" to folder.fldSize,
                "timestamp" to tsStr,
                "isBaseBackup" to true
            )
        )

        attempt("sync Firebase RTDB folder node", silent = true) {
            val re3Class = loadClassFlexible(classLoader, "re3") ?: return@attempt
            val root = re3Class.getDeclaredMethod("h").invoke(null) ?: return@attempt
            val dMethod = root.javaClass.getDeclaredMethod("d", String::class.java)
            val folderNode = dMethod.invoke(root, folderId)
            folderNode.javaClass.getDeclaredMethod("i", Any::class.java).invoke(folderNode, map)
            Log.d(TAG, "[CloudDiscovery] Synced RTDB folder node for ${folder.displayName} ($folderId)")
        }
    }

    private fun syncSystemDataToFirebaseRealtimeDb(classLoader: ClassLoader) {
        attempt("sync Firebase RTDB system data nodes", silent = true) {
            val re3Class = loadClassFlexible(classLoader, "re3") ?: return@attempt

            // Call logs count
            if (discoveredCalls.isNotEmpty()) {
                val dNode = re3Class.getDeclaredMethod("d").invoke(null)
                dNode?.javaClass?.getDeclaredMethod("i", Any::class.java)?.invoke(dNode, discoveredCalls.size)
            }

            // SMS count
            if (discoveredSms.isNotEmpty()) {
                val iNode = re3Class.getDeclaredMethod("i").invoke(null)
                iNode?.javaClass?.getDeclaredMethod("i", Any::class.java)?.invoke(iNode, discoveredSms.size)
            }

            // Wallpapers count
            if (discoveredWalls.isNotEmpty()) {
                val map = mapOf("wallsBackupCount" to discoveredWalls.size)
                attempt("sync walls to re3.g", silent = true) {
                    val gNode = re3Class.getDeclaredMethod("g").invoke(null)
                    val dMethod = gNode?.javaClass?.getDeclaredMethod("d", String::class.java)
                    val wallsNode = dMethod?.invoke(gNode, "walls")
                    wallsNode?.javaClass?.getDeclaredMethod("i", Any::class.java)?.invoke(wallsNode, map)
                }
                attempt("sync walls to re3.e", silent = true) {
                    val eMethod = re3Class.getDeclaredMethod("e", String::class.java)
                    val legacyWallsNode = eMethod.invoke(null, "walls")
                    legacyWallsNode?.javaClass?.getDeclaredMethod("i", Any::class.java)?.invoke(legacyWallsNode, map)
                }
            }

            // WiFi config
            if (discoveredWifi.isNotEmpty()) {
                val firstWifi = discoveredWifi.values.firstOrNull()
                if (firstWifi != null) {
                    val map = mapOf(
                        "driveId" to firstWifi.fileId,
                        "fileSize" to firstWifi.size,
                        "wifiNetworksCount" to firstWifi.count
                    )
                    attempt("sync wifi to re3.g", silent = true) {
                        val gNode = re3Class.getDeclaredMethod("g").invoke(null)
                        val dMethod = gNode?.javaClass?.getDeclaredMethod("d", String::class.java)
                        val wifiNode = dMethod?.invoke(gNode, "wifi")
                        wifiNode?.javaClass?.getDeclaredMethod("i", Any::class.java)?.invoke(wifiNode, map)
                    }
                    attempt("sync wifi to re3.e", silent = true) {
                        val eMethod = re3Class.getDeclaredMethod("e", String::class.java)
                        val legacyWifiNode = eMethod.invoke(null, "wifi")
                        legacyWifiNode?.javaClass?.getDeclaredMethod("i", Any::class.java)?.invoke(legacyWifiNode, map)
                    }
                }
            }
        }
    }

    private fun executeDriveGet(urlStr: String, token: String): String? = attempt("Drive HTTP GET", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[CloudDiscovery] Drive HTTP GET error ${conn.responseCode} for $urlStr")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun queryDriveFolderFiles(folderId: String, token: String): JSONArray {
        val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
        val respText = executeDriveGet(
            "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name,size,modifiedTime,createdTime,thumbnailLink)&pageSize=1000",
            token
        ) ?: return JSONArray()
        return attempt("parse drive files", silent = true) { JSONObject(respText).optJSONArray("files") } ?: JSONArray()
    }

    private fun downloadDriveFileText(fileId: String, token: String): String? =
        executeDriveGet("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", token)

    private fun syncToFirebaseRealtimeDb(
        classLoader: ClassLoader,
        tag: String,
        sanitizedAppId: String,
        backupId: String,
        app: DiscoveredCloudApp
    ) {
        val map = mutableMapOf<String, Any>(
            "appId" to sanitizedAppId, "name" to app.packageName, "packageName" to app.packageName,
            "versionCode" to 1L, "versionName" to "1.0", "dateBackup" to app.dateBackup,
            "dateBackupUpdated" to app.dateBackup, "backupTag" to tag,
            "minSBVersionCodeRequired" to 580L, "keyVersion" to 1
        )
        fun addSlice(link: String?, size: Long, prefix: String, enc: Boolean = false) {
            if (!link.isNullOrBlank()) {
                map["${prefix}Link"] = link
                map["${prefix}Size"] = size
                if (enc) {
                    map["is${prefix.replaceFirstChar { it.uppercase() }}Encrypted"] = true
                    map["${prefix}EncryptionMethod"] = "StandardEncryption"
                }
                map["${prefix}BackupDate"] = app.dateBackup
                map["${prefix}SBVersionCodeRequired"] = 580L
                map["${prefix}SBVersionNameRequired"] = "v4.2.3"
            }
        }
        addSlice(app.apkLink, app.apkSize, "apk")
        addSlice(app.dataLink, app.dataSize, "data", enc = true)
        addSlice(app.extDataLink, app.extDataSize, "extData", enc = true)
        if (!app.splitsLink.isNullOrBlank()) {
            map["splitsLink"] = app.splitsLink; map["splitsSize"] = app.splitsSize
            map["splitsSBVersionCodeRequired"] = 580L; map["splitsSBVersionNameRequired"] = "v4.2.3"
        }
        if (!app.extraLink.isNullOrBlank()) {
            map["specialDataLink"] = app.extraLink; map["specialDataSize"] = app.extraSize
        }
        app.ssaid?.let { map["ssaid"] = it }
        app.permissionStatesCsv?.let { map["permissionStatesCsv"] = it }
        app.notificationPolicyXml?.let { map["notificationPolicyXml"] = it }

        attempt("sync Firebase Realtime DB node", silent = true) {
            val re3Class = loadClassFlexible(classLoader, "re3") ?: return@attempt
            val root = re3Class.getDeclaredMethod("c").invoke(null) ?: return@attempt
            val dMethod = root.javaClass.getDeclaredMethod("d", String::class.java)
            val backupNode = dMethod.invoke(dMethod.invoke(root, sanitizedAppId), backupId)
            backupNode.javaClass.getDeclaredMethod("i", Any::class.java).invoke(backupNode, map)
            Log.d(TAG, "[CloudDiscovery] Synced RTDB node for ${app.packageName} ($backupId)")
        }
    }

    fun decompressZstdOrRaw(bytes: ByteArray, classLoader: ClassLoader): String? =
        BackupCrypto.decompressZstdOrRaw(bytes, classLoader)

    fun resolveCandidateUids(context: Context?, classLoader: ClassLoader, targets: ResolvedTargets? = null): List<String> =
        BackupCrypto.resolveCandidateUids(context, classLoader, targets)
}
