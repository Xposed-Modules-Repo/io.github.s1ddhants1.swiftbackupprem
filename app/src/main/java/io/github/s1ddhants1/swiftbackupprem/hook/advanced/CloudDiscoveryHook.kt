package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Cloud Discovery & Direct Metadata Indexing Hook:
 *
 * Discovers cloud backups directly from Google Drive, downloads & decodes .extra metadata
 * using Facebook Conceal AES-GCM-256 + Zstandard, and injects them into Swift Backup's
 * cloud catalog across the entire app:
 *
 * 1. Single App Details (Cloud tab, sizes, restore button)
 * 2. Cloud Sync Tab (Total apps count, storage space used, device tags)
 * 3. Batch Cloud Restore & Quick Actions (Restore all apps)
 * 4. App List Filter ("Cloud synced apps" filter)
 */
@Keep
object CloudDiscoveryHook : HookHandler {

    private const val TAG = "SBP"
    private const val CACHE_FILE_NAME = "cloud_discovered_cache.json"
    val discoveredBackups = ConcurrentHashMap<String, DiscoveredCloudApp>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isScanRunning = AtomicBoolean(false)

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
    )

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (!prefs.enableDriveDiscovery) {
            Log.d(TAG, "Cloud Discovery is disabled by user preference")
            return
        }

        Log.d(TAG, "Applying CloudDiscoveryHook (Drive discovery & full-app cloud metadata indexing)")

        // 1. Load persistent disk cache immediately into memory
        loadDiskCache(context)

        // 2. Hook AppCloudBackups.Companion (single app queries)
        hookAppCloudBackups(module, context, classLoader, targets)

        // 3. Hook lk2.onDataChange (App Details Cloud tab)
        hookDetailCloudListener(module, context, classLoader, targets)

        // 4. Hook ua1.a (Batch Cloud App Loader / Restore all apps)
        hookBatchCloudLoader(module, context, classLoader, targets)

        // 5. Hook qq.b (AppListActivity "Cloud synced apps" filter)
        hookAppFilterHelper(module, context, classLoader, targets)

        // 6. Hook jg1.onDataChange & ng1.c (Cloud Sync tab ViewModel)
        hookCloudSyncTab(module, context, classLoader, targets)

        // 7. Hook CloudBackupTag.Companion (Tags discovery)
        hookCloudBackupTags(module, classLoader)

        // 8. Run background Drive scan with resilient retries
        startDriveScanWithRetry(context, classLoader, targets)
    }

    /**
     * Starts background Drive scan with exponential retries on startup.
     */
    fun startDriveScanWithRetry(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        Thread {
            if (!isScanRunning.compareAndSet(false, true)) return@Thread
            try {
                val retryDelays = longArrayOf(500L, 2000L, 5000L, 10000L)
                for (delay in retryDelays) {
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
        }.start()
    }

    /**
     * Loads previously discovered backups from disk cache.
     */
    private fun loadDiskCache(context: Context) {
        try {
            val cacheFile = File(context.filesDir?.parentFile, CACHE_FILE_NAME)
            if (!cacheFile.exists()) {
                val fallbackFile = File("/data/data/org.swiftapps.swiftbackup/$CACHE_FILE_NAME")
                if (fallbackFile.exists()) {
                    parseCacheJson(fallbackFile.readText(StandardCharsets.UTF_8))
                }
                return
            }
            parseCacheJson(cacheFile.readText(StandardCharsets.UTF_8))
        } catch (t: Throwable) {
            Log.d(TAG, "[CloudDiscovery] Error loading cache: ${t.message}")
        }
    }

    private fun parseCacheJson(jsonStr: String) {
        val root = JSONObject(jsonStr)
        root.keys().forEach { pkg ->
            val obj = root.getJSONObject(pkg)
            val app = DiscoveredCloudApp(
                packageName = obj.optString("packageName", pkg),
                sanitizedAppId = obj.optString("sanitizedAppId", pkg.replace(".", "")),
                backupId = obj.optString("backupId", ""),
                backupTag = obj.optString("backupTag", "DEFAULT"),
                apkLink = obj.optString("apkLink", "").ifBlank { null },
                apkSize = obj.optLong("apkSize", 0L),
                dataLink = obj.optString("dataLink", "").ifBlank { null },
                dataSize = obj.optLong("dataSize", 0L),
                extDataLink = obj.optString("extDataLink", "").ifBlank { null },
                extDataSize = obj.optLong("extDataSize", 0L),
                splitsLink = obj.optString("splitsLink", "").ifBlank { null },
                splitsSize = obj.optLong("splitsSize", 0L),
                extraLink = obj.optString("extraLink", "").ifBlank { null },
                extraSize = obj.optLong("extraSize", 0L),
                totalSize = obj.optLong("totalSize", 0L),
                ssaid = obj.optString("ssaid", "").ifBlank { null },
                permissionStatesCsv = obj.optString("permissionStatesCsv", "").ifBlank { null },
                notificationPolicyXml = obj.optString("notificationPolicyXml", "").ifBlank { null },
                dateBackup = obj.optLong("dateBackup", System.currentTimeMillis())
            )
            discoveredBackups[pkg] = app
        }
        Log.i(TAG, "[CloudDiscovery] Loaded ${discoveredBackups.size} apps from disk cache")
    }

    private fun saveDiskCache(context: Context) {
        try {
            val root = JSONObject()
            discoveredBackups.forEach { (pkg, app) ->
                val obj = JSONObject().apply {
                    put("packageName", app.packageName)
                    put("sanitizedAppId", app.sanitizedAppId)
                    put("backupId", app.backupId)
                    put("backupTag", app.backupTag)
                    app.apkLink?.let { put("apkLink", it) }
                    put("apkSize", app.apkSize)
                    app.dataLink?.let { put("dataLink", it) }
                    put("dataSize", app.dataSize)
                    app.extDataLink?.let { put("extDataLink", it) }
                    put("extDataSize", app.extDataSize)
                    app.splitsLink?.let { put("splitsLink", it) }
                    put("splitsSize", app.splitsSize)
                    app.extraLink?.let { put("extraLink", it) }
                    put("extraSize", app.extraSize)
                    put("totalSize", app.totalSize)
                    app.ssaid?.let { put("ssaid", it) }
                    app.permissionStatesCsv?.let { put("permissionStatesCsv", it) }
                    app.notificationPolicyXml?.let { put("notificationPolicyXml", it) }
                    put("dateBackup", app.dateBackup)
                }
                root.put(pkg, obj)
            }
            val cacheFile = File(context.filesDir?.parentFile, CACHE_FILE_NAME)
            cacheFile.writeText(root.toString(2), StandardCharsets.UTF_8)
            cacheFile.setReadable(true, false)
        } catch (_: Throwable) {}
    }

    /**
     * Hooks AppCloudBackups.Companion to return discovered cloud backups if Firebase RTDB is empty.
     */
    private fun hookAppCloudBackups(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return
        val companionClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$a") ?: return

        for (m in companionClass.declaredMethods) {
            if (m.name == "fromSnapshot") {
                attempt("hook fromSnapshot", silent = true) {
                    module.hook(m).intercept { chain ->
                        val initialResult = chain.proceed()
                        if (initialResult != null) return@intercept initialResult

                        if (discoveredBackups.isEmpty()) {
                            startDriveScanWithRetry(context, classLoader, targets)
                        }

                        val snapshotArg = chain.args.getOrNull(0)
                        var key: String? = null
                        if (snapshotArg != null) {
                            try {
                                val bField = snapshotArg.javaClass.getDeclaredField("b").apply { isAccessible = true }
                                val zc2Obj = bField.get(snapshotArg)
                                if (zc2Obj != null) {
                                    val eMethod = zc2Obj.javaClass.getDeclaredMethod("e")
                                    key = eMethod.invoke(zc2Obj) as? String
                                }
                            } catch (_: Throwable) {}
                        }

                        if (key != null) {
                            val matching = discoveredBackups.values.firstOrNull { it.sanitizedAppId == key || it.packageName == key }
                            if (matching != null) {
                                val cloudBackup = buildAppCloudBackup(matching, classLoader)
                                if (cloudBackup != null) {
                                    val ctor = appCloudBackupsClass.getConstructor(List::class.java)
                                    return@intercept ctor.newInstance(listOf(cloudBackup))
                                }
                            }
                        }
                        null
                    }
                }
            } else if (m.name == "fetchForPackage") {
                attempt("hook fetchForPackage", silent = true) {
                    module.hook(m).intercept { chain ->
                        val initialResult = chain.proceed()
                        val pkgName = chain.args.getOrNull(0) as? String
                        if (pkgName != null && (initialResult == null || isResultEmpty(initialResult))) {
                            if (discoveredBackups.isEmpty()) {
                                startDriveScanWithRetry(context, classLoader, targets)
                            }
                            val matching = discoveredBackups[pkgName] ?: discoveredBackups.values.firstOrNull { it.sanitizedAppId == pkgName.replace(".", "") }
                            if (matching != null) {
                                val cloudBackup = buildAppCloudBackup(matching, classLoader)
                                if (cloudBackup != null) {
                                    val ctor = appCloudBackupsClass.getConstructor(List::class.java)
                                    val backupsObj = ctor.newInstance(listOf(cloudBackup))
                                    val resultClass = m.returnType
                                    val resultCtor = resultClass.getConstructor(appCloudBackupsClass, loadClassFlexible(classLoader, "wc2"))
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

    /**
     * Hooks lk2.onDataChange (ValueEventListener attached to Firebase RTDB for App Details).
     */
    private fun hookDetailCloudListener(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val lk2Class = loadClassFlexible(classLoader, "lk2") ?: return

        for (m in lk2Class.declaredMethods) {
            if (m.name == "onDataChange") {
                attempt("hook lk2.onDataChange", silent = true) {
                    module.hook(m).intercept { chain ->
                        val lk2Instance = chain.thisObject ?: return@intercept chain.proceed()
                        val aField = lk2Instance.javaClass.getDeclaredField("a").apply { isAccessible = true }
                        val mk2Instance = aField.get(lk2Instance) ?: return@intercept chain.proceed()

                        val eField = mk2Instance.javaClass.getDeclaredField("e").apply { isAccessible = true }
                        val jiInstance = eField.get(mk2Instance) ?: return@intercept chain.proceed()

                        val getPkgMethod = jiInstance.javaClass.getDeclaredMethod("getPackageName")
                        val pkgName = getPkgMethod.invoke(jiInstance) as? String

                        if (pkgName != null) {
                            if (discoveredBackups.isEmpty()) {
                                startDriveScanWithRetry(context, classLoader, targets)
                            }

                            val matching = discoveredBackups[pkgName] ?: discoveredBackups.values.firstOrNull { it.sanitizedAppId == pkgName.replace(".", "") }
                            if (matching != null) {
                                val cloudBackup = buildAppCloudBackup(matching, classLoader)
                                if (cloudBackup != null) {
                                    val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups")
                                    if (appCloudBackupsClass != null) {
                                        val ctor = appCloudBackupsClass.getConstructor(List::class.java)
                                        val backupsObj = ctor.newInstance(listOf(cloudBackup))

                                        val setCloudBackupsMethod = jiInstance.javaClass.getDeclaredMethod("setCloudBackups", appCloudBackupsClass)
                                        setCloudBackupsMethod.invoke(jiInstance, backupsObj)

                                        try {
                                            val lField = mk2Instance.javaClass.getDeclaredField("l").apply { isAccessible = true }
                                            val ex6Instance = lField.get(mk2Instance)

                                            val wj2Class = loadClassFlexible(classLoader, "wj2")!!
                                            val yj2Class = loadClassFlexible(classLoader, "yj2")!!
                                            val xj2Class = loadClassFlexible(classLoader, "xj2")!!
                                            val backedUpEnum = xj2Class.enumConstants?.firstOrNull { it.toString() == "BackedUp" }

                                            val wj2Ctor = wj2Class.constructors.first()

                                            val apkSizeStr = formatBytes(matching.apkSize + matching.splitsSize)
                                            val dataSizeStr = if (matching.dataSize > 0) "${formatBytes(matching.dataSize)} \uD83D\uDD12" else ""
                                            val extDataSizeStr = if (matching.extDataSize > 0) "${formatBytes(matching.extDataSize)} \uD83D\uDD12" else ""
                                            val totalSizeStr = formatBytes(matching.totalSize)
                                            val dateStr = "Cloud Backup ($totalSizeStr)"
                                            val verStr = "Version: 1.0 (1)"
                                            val verStr2 = "Version: 1.0"

                                            val wj2Item = wj2Ctor.newInstance(
                                                cloudBackup,
                                                apkSizeStr,
                                                matching.splitsLink != null,
                                                false,
                                                dataSizeStr,
                                                matching.dataLink != null,
                                                "StandardEncryption",
                                                extDataSizeStr,
                                                matching.extDataLink != null,
                                                "StandardEncryption",
                                                "",
                                                false,
                                                null,
                                                "",
                                                totalSizeStr,
                                                dateStr,
                                                verStr2,
                                                verStr,
                                                false
                                            )

                                            val yj2Ctor = yj2Class.getConstructor(xj2Class, List::class.java)
                                            val yj2Instance = yj2Ctor.newInstance(backedUpEnum, listOf(wj2Item))

                                            val kMethod = ex6Instance.javaClass.getMethod("k", Any::class.java)
                                            kMethod.invoke(ex6Instance, yj2Instance)

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
    }

    /**
     * Hooks Batch Cloud App Loader (ua1.a) to include all discovered Google Drive apps.
     */
    private fun hookBatchCloudLoader(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        attempt("hook ua1.a (Batch Cloud App Loader)", silent = false) {
            val ua1Class = loadClassFlexible(classLoader, "ua1") ?: return@attempt
            for (m in ua1Class.declaredMethods) {
                if (m.name == "a" && m.parameterCount == 0) {
                    module.hook(m).intercept { chain ->
                        val result = chain.proceed()
                        if (discoveredBackups.isEmpty()) {
                            startDriveScanWithRetry(context, classLoader, targets)
                        }

                        if (discoveredBackups.isNotEmpty()) {
                            val jiList = mutableListOf<Any>()
                            val jiClass = loadClassFlexible(classLoader, "ji") ?: return@intercept result
                            val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return@intercept result
                            val fromCloudBackupsMethod = jiClass.getDeclaredMethod("fromCloudBackups", appCloudBackupsClass)
                            val appBackupsCtor = appCloudBackupsClass.getConstructor(List::class.java)

                            for (app in discoveredBackups.values) {
                                val cloudBackup = buildAppCloudBackup(app, classLoader)
                                if (cloudBackup != null) {
                                    val backupsObj = appBackupsCtor.newInstance(listOf(cloudBackup))
                                    val jiInstance = fromCloudBackupsMethod.invoke(null, backupsObj)
                                    if (jiInstance != null) {
                                        jiList.add(jiInstance)
                                    }
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
        }
    }

    /**
     * Hooks AppFilterHelper.getSyncedApps (qq.b) to display discovered apps under "Cloud synced apps".
     */
    private fun hookAppFilterHelper(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        attempt("hook qq.b (AppFilterHelper)", silent = false) {
            val qqClass = loadClassFlexible(classLoader, "qq") ?: return@attempt
            for (m in qqClass.declaredMethods) {
                if (m.name == "b" && m.parameterCount == 2) {
                    module.hook(m).intercept { chain ->
                        val listArg = chain.args.getOrNull(0) as? List<*> ?: return@intercept chain.proceed()
                        val ce3Arg = chain.args.getOrNull(1) ?: return@intercept chain.proceed()

                        if (ce3Arg.toString() == "Synced") {
                            if (discoveredBackups.isEmpty()) {
                                startDriveScanWithRetry(context, classLoader, targets)
                            }

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
                                    val matching = discoveredBackups[pkg] ?: discoveredBackups.values.firstOrNull { it.sanitizedAppId == pkg.replace(".", "") }
                                    if (matching != null) {
                                        val cloudBackup = buildAppCloudBackup(matching, classLoader)
                                        if (cloudBackup != null) {
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
        }
    }

    /**
     * Hooks Cloud Sync Tab (ng1.c and jg1.onDataChange) to render total backups count and used space.
     */
    private fun hookCloudSyncTab(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        attempt("hook ng1.c (Cloud Sync ViewModel)", silent = false) {
            val ng1Class = loadClassFlexible(classLoader, "ng1") ?: return@attempt
            for (m in ng1Class.declaredMethods) {
                if (m.name == "c") {
                    module.hook(m).intercept { chain ->
                        val result = chain.proceed()
                        if (discoveredBackups.isEmpty()) {
                            startDriveScanWithRetry(context, classLoader, targets)
                        }
                        postCloudSyncStats(chain.thisObject, classLoader)
                        result
                    }
                }
            }
        }

        attempt("hook jg1.onDataChange (Cloud Sync ValueEventListener)", silent = false) {
            val jg1Class = loadClassFlexible(classLoader, "jg1") ?: return@attempt
            for (m in jg1Class.declaredMethods) {
                if (m.name == "onDataChange") {
                    module.hook(m).intercept { chain ->
                        val jg1Instance = chain.thisObject ?: return@intercept chain.proceed()
                        val qField = jg1Instance.javaClass.getDeclaredField("q").apply { isAccessible = true }
                        val ng1Instance = qField.get(jg1Instance)

                        if (discoveredBackups.isNotEmpty()) {
                            postCloudSyncStats(ng1Instance, classLoader)
                            return@intercept null // Prevent empty Firebase RTDB snapshot from overwriting with 0!
                        }
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun postCloudSyncStats(ng1Instance: Any?, classLoader: ClassLoader) {
        if (ng1Instance == null || discoveredBackups.isEmpty()) return
        mainHandler.post {
            try {
                val fField = ng1Instance.javaClass.getDeclaredField("f").apply { isAccessible = true }
                val ex6Instance = fField.get(ng1Instance) ?: return@post
                val z8Class = loadClassFlexible(classLoader, "z8") ?: return@post
                val z8Ctor = z8Class.getConstructor(
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )

                val totalApps = discoveredBackups.size
                val totalSpace = discoveredBackups.values.sumOf { it.totalSize }
                val z8Instance = z8Ctor.newInstance(totalApps, totalSpace, 0, 0, 0)

                val kMethod = ex6Instance.javaClass.getMethod("k", Any::class.java)
                kMethod.invoke(ex6Instance, z8Instance)
                Log.i(TAG, "[CloudDiscovery] Posted Cloud Sync tab stats: $totalApps apps, ${formatBytes(totalSpace)}")
            } catch (t: Throwable) {
                Log.e(TAG, "[CloudDiscovery] Failed to post Cloud Sync stats: ${t.message}")
            }
        }
    }

    /**
     * Hooks CloudBackupTag.Companion (ob1.a) to include device tags for discovered backups.
     */
    private fun hookCloudBackupTags(module: XposedModule, classLoader: ClassLoader) {
        attempt("hook ob1.a (CloudBackupTag Companion)", silent = false) {
            val ob1Class = loadClassFlexible(classLoader, "ob1") ?: return@attempt
            for (m in ob1Class.declaredMethods) {
                if (m.name == "a") {
                    module.hook(m).intercept { chain ->
                        val result = chain.proceed() as? List<*> ?: mutableListOf<String>()
                        val tags = result.filterIsInstance<String>().toMutableList()

                        for (app in discoveredBackups.values) {
                            if (app.backupTag.isNotBlank() && !tags.contains(app.backupTag)) {
                                tags.add(app.backupTag)
                            }
                        }
                        tags
                    }
                }
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

    private fun isResultEmpty(result: Any): Boolean {
        return try {
            val getBackupsMethod = result.javaClass.getDeclaredMethod("getAppCloudBackups")
            val backups = getBackupsMethod.invoke(result)
            backups == null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Constructs a genuine AppCloudBackup instance matching Swift Backup models.
     */
    fun buildAppCloudBackup(app: DiscoveredCloudApp, classLoader: ClassLoader): Any? {
        return try {
            val cloudMetadataClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.CloudMetadata") ?: return null
            val appCloudBackupClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackup") ?: return null

            val metaCtor = cloudMetadataClass.constructors.first { it.parameterCount >= 60 }
            val now = app.dateBackup

            val args = arrayOfNulls<Any>(metaCtor.parameterCount)
            args[0] = app.packageName // packageName (String)
            args[1] = app.packageName // name (String)
            args[2] = now // dateBackup (Long)
            args[3] = now // dateBackupUpdated (Long)
            args[4] = "1.0" // versionName (String)
            args[5] = 1L // versionCode (Long)
            args[6] = app.apkLink // apkLink (String)
            args[7] = if (app.apkSize > 0) app.apkSize else null // apkSize (Long)
            args[8] = if (app.apkLink != null) now else null // apkBackupDate (Long)
            args[9] = if (app.apkLink != null) 580L else null // apkSBVersionCodeRequired (Long)
            args[10] = if (app.apkLink != null) "v4.2.3" else null // apkSBVersionNameRequired (String)
            args[11] = app.splitsLink // splitsLink (String)
            args[12] = if (app.splitsSize > 0) app.splitsSize else null // splitsSize (Long)
            args[13] = null // splitsSizeMirrored
            args[14] = if (app.splitsLink != null) 580L else null
            args[15] = if (app.splitsLink != null) "v4.2.3" else null
            args[16] = null // sharedLibsLink
            args[17] = null // sharedLibsSize
            args[18] = null
            args[19] = null
            args[20] = null
            args[21] = app.dataLink // dataLink (String)
            args[22] = if (app.dataSize > 0) app.dataSize else null // dataSize (Long)
            args[23] = null // dataSizeMirrored
            args[24] = if (app.dataLink != null) true else null // isDataEncrypted (Boolean)
            args[25] = if (app.dataLink != null) "StandardEncryption" else null // dataEncryptionMethod (String)
            args[26] = null // dataPasswordHash
            args[27] = if (app.dataLink != null) now else null // dataBackupDate (Long)
            args[28] = if (app.dataLink != null) 580L else null
            args[29] = if (app.dataLink != null) "v4.2.3" else null
            args[30] = app.extDataLink // extDataLink (String)
            args[31] = if (app.extDataSize > 0) app.extDataSize else null // extDataSize (Long)
            args[32] = null
            args[33] = if (app.extDataLink != null) true else null // isExtDataEncrypted (Boolean)
            args[34] = if (app.extDataLink != null) "StandardEncryption" else null
            args[35] = null
            args[36] = if (app.extDataLink != null) now else null
            args[37] = if (app.extDataLink != null) 580L else null
            args[38] = if (app.extDataLink != null) "v4.2.3" else null
            // Media (39..47)
            // Exp (48..53)
            args[54] = 580L // minSBVersionCodeRequired
            args[55] = null // permissionIdsCsv
            args[56] = app.permissionStatesCsv // permissionStatesCsv
            args[57] = null // ntfAccessComponent
            args[58] = app.extraLink // specialDataLink
            args[59] = if (app.extraSize > 0) app.extraSize else null // specialDataSize
            args[60] = null // accessibilityComponent
            args[61] = app.ssaid // ssaid
            args[62] = null // installerPackage
            args[63] = false // _protectedBackup
            args[64] = null // _note
            args[65] = 1 // keyVersion

            val metaObj = metaCtor.newInstance(*args)
            val backupCtor = appCloudBackupClass.getConstructor(String::class.java, cloudMetadataClass)
            backupCtor.newInstance(app.backupId, metaObj)
        } catch (t: Throwable) {
            Log.e(TAG, "[CloudDiscovery] Failed to build AppCloudBackup: ${t.message}")
            null
        }
    }

    /**
     * Scans Google Drive for backup files, downloads & decodes .extra files,
     * and caches/syncs CloudMetadata.
     */
    fun discoverDriveBackups(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ): Int {
        val sp: SharedPreferences = try {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            return 0
        }

        val token = sp.getString("nogms_access_token", null) ?: return 0
        val folderId = sp.getString("google_drive_cloud_main_folder_id", null) ?: return 0
        val deviceTag = sp.getString("google_drive_cloud_backup_tag", null) ?: "DEFAULT"

        val candidateUids = resolveCandidateUids(context, classLoader, targets)

        Log.d(TAG, "[CloudDiscovery] Querying Google Drive folder $folderId for cloud backups...")

        val fileList = queryDriveFolderFiles(folderId, token)
        if (fileList.length() == 0) {
            Log.d(TAG, "[CloudDiscovery] No files found in Drive folder $folderId")
            return 0
        }

        // Group files by (packageName, backupId, tag)
        // Regex: <pkg>.<part> (<tag>) (id-<backupId>)
        val regex = Pattern.compile("^(.*?)\\.([a-z]+)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")
        val groups = mutableMapOf<Triple<String, String, String>, MutableMap<String, JSONObject>>()

        for (i in 0 until fileList.length()) {
            val fileObj = fileList.getJSONObject(i)
            val name = fileObj.optString("name")
            val matcher = regex.matcher(name)
            if (matcher.matches()) {
                val pkg = matcher.group(1) ?: continue
                val part = matcher.group(2) ?: continue
                val tag = matcher.group(3) ?: continue
                val backupId = matcher.group(4) ?: continue

                val key = Triple(pkg, backupId, tag)
                val partMap = groups.getOrPut(key) { mutableMapOf() }
                partMap[part] = fileObj
            }
        }

        Log.i(TAG, "[CloudDiscovery] Found ${groups.size} unique cloud backups on Google Drive")
        var indexedCount = 0

        for ((key, parts) in groups) {
            val (pkg, backupId, tag) = key
            val sanitizedAppId = pkg.replace(".", "")

            var ssaid: String? = null
            var permissionStatesCsv: String? = null
            var notificationPolicyXml: String? = null

            // If .extra slice is present, download and decrypt it
            val extraObj = parts["extra"]
            val extraFileId = extraObj?.optString("id")
            val extraSize = extraObj?.optLong("size", 0L) ?: 0L

            if (!extraFileId.isNullOrBlank()) {
                val rawExtraText = downloadDriveFileText(extraFileId, token)
                if (rawExtraText != null) {
                    val extraParts = rawExtraText.split(":::").filter { it.isNotBlank() }
                    if (extraParts.size >= 3) {
                        for (candUid in candidateUids) {
                            try {
                                val keyBytes = BackupRebuilderHook.deriveConcealKey(candUid)
                                val decBytes = BackupRebuilderHook.concealDecrypt(extraParts[2].trim(), keyBytes)
                                val decompJson = decompressZstdOrRaw(decBytes, classLoader)
                                if (decompJson != null) {
                                    val json = JSONObject(decompJson)
                                    if (json.has("ssaid")) ssaid = json.optString("ssaid")
                                    if (json.has("permissionStatesCsv")) permissionStatesCsv = json.optString("permissionStatesCsv")
                                    if (json.has("notificationPolicyXml")) notificationPolicyXml = json.optString("notificationPolicyXml")
                                    break
                                }
                            } catch (_: Throwable) {}
                        }
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

            val totalSize = apkSize + dataSize + extDataSize + splitsSize

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
                totalSize = totalSize,
                ssaid = ssaid,
                permissionStatesCsv = permissionStatesCsv,
                notificationPolicyXml = notificationPolicyXml
            )
            discoveredBackups[pkg] = discovered

            // Safely sync to Firebase Realtime Database
            syncToFirebaseRealtimeDb(classLoader, tag, sanitizedAppId, backupId, discovered)
            indexedCount++
        }

        saveDiskCache(context)
        Log.i(TAG, "[CloudDiscovery] Successfully indexed $indexedCount cloud backups from Google Drive into catalog")
        return indexedCount
    }

    private fun queryDriveFolderFiles(folderId: String, token: String): org.json.JSONArray {
        return try {
            val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
            val url = URL("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name,size,modifiedTime)&pageSize=1000")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15000
                readTimeout = 15000
            }

            if (conn.responseCode == 200) {
                val respText = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(respText).optJSONArray("files") ?: org.json.JSONArray()
            } else {
                Log.w(TAG, "[CloudDiscovery] Drive list files HTTP error ${conn.responseCode}")
                org.json.JSONArray()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[CloudDiscovery] Drive list files exception: ${t.message}")
            org.json.JSONArray()
        }
    }

    private fun downloadDriveFileText(fileId: String, token: String): String? {
        return try {
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun syncToFirebaseRealtimeDb(
        classLoader: ClassLoader,
        tag: String,
        sanitizedAppId: String,
        backupId: String,
        app: DiscoveredCloudApp
    ) {
        val map = HashMap<String, Any>()
        map["appId"] = sanitizedAppId
        map["name"] = app.packageName
        map["packageName"] = app.packageName
        map["versionCode"] = 1L
        map["versionName"] = "1.0"
        map["dateBackup"] = app.dateBackup
        map["dateBackupUpdated"] = app.dateBackup
        map["backupTag"] = tag
        map["minSBVersionCodeRequired"] = 580L
        map["keyVersion"] = 1

        if (!app.apkLink.isNullOrBlank()) {
            map["apkLink"] = app.apkLink
            map["apkSize"] = app.apkSize
            map["apkBackupDate"] = app.dateBackup
            map["apkSBVersionCodeRequired"] = 580L
            map["apkSBVersionNameRequired"] = "v4.2.3"
        }
        if (!app.dataLink.isNullOrBlank()) {
            map["dataLink"] = app.dataLink
            map["dataSize"] = app.dataSize
            map["isDataEncrypted"] = true
            map["dataEncryptionMethod"] = "StandardEncryption"
            map["dataBackupDate"] = app.dateBackup
            map["dataSBVersionCodeRequired"] = 580L
            map["dataSBVersionNameRequired"] = "v4.2.3"
        }
        if (!app.extDataLink.isNullOrBlank()) {
            map["extDataLink"] = app.extDataLink
            map["extDataSize"] = app.extDataSize
            map["isExtDataEncrypted"] = true
            map["extDataEncryptionMethod"] = "StandardEncryption"
            map["extDataBackupDate"] = app.dateBackup
            map["extDataSBVersionCodeRequired"] = 580L
            map["extDataSBVersionNameRequired"] = "v4.2.3"
        }
        if (!app.splitsLink.isNullOrBlank()) {
            map["splitsLink"] = app.splitsLink
            map["splitsSize"] = app.splitsSize
            map["splitsSBVersionCodeRequired"] = 580L
            map["splitsSBVersionNameRequired"] = "v4.2.3"
        }
        if (!app.extraLink.isNullOrBlank()) {
            map["specialDataLink"] = app.extraLink
            map["specialDataSize"] = app.extraSize
        }

        app.ssaid?.let { map["ssaid"] = it }
        app.permissionStatesCsv?.let { map["permissionStatesCsv"] = it }
        app.notificationPolicyXml?.let { map["notificationPolicyXml"] = it }

        // Write directly to Firebase Realtime DB via re3.c() safely
        attempt("sync Firebase Realtime DB node", silent = true) {
            val re3Class = loadClassFlexible(classLoader, "re3")
            if (re3Class != null) {
                val cMethod = re3Class.getDeclaredMethod("c")
                val rootNode = cMethod.invoke(null)
                if (rootNode != null) {
                    val dMethod = rootNode.javaClass.getDeclaredMethod("d", String::class.java)
                    val appNode = dMethod.invoke(rootNode, sanitizedAppId)
                    val backupNode = dMethod.invoke(appNode, backupId)
                    val iMethod = backupNode.javaClass.getDeclaredMethod("i", Any::class.java)
                    iMethod.invoke(backupNode, map)
                    Log.d(TAG, "[CloudDiscovery] Synced RTDB node for ${app.packageName} ($backupId)")
                }
            }
        }
    }

    fun decompressZstdOrRaw(bytes: ByteArray, classLoader: ClassLoader): String? {
        try {
            val str = String(bytes, StandardCharsets.UTF_8)
            if (str.startsWith("{") && str.endsWith("}")) return str
        } catch (_: Throwable) {}

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

    fun resolveCandidateUids(context: Context?, classLoader: ClassLoader, targets: ResolvedTargets): List<String> {
        val uids = LinkedHashSet<String>()

        attempt("resolve UID via d45", silent = true) {
            val d45Class = loadClassFlexible(classLoader, "d45")
            if (d45Class != null) {
                val user = d45Class.getDeclaredMethod("a").invoke(null)
                if (user != null) {
                    val uid = user.javaClass.getDeclaredMethod("getUid").invoke(user) as? String
                    if (!uid.isNullOrBlank()) uids.add(uid)
                }
            }
        }

        attempt("resolve UID via FirebaseAuth", silent = true) {
            val fbAuthClass = classLoader.loadClass("com.google.firebase.auth.FirebaseAuth")
            val authInstance = fbAuthClass.getDeclaredMethod("getInstance").invoke(null)
            if (authInstance != null) {
                val currentUser = fbAuthClass.getDeclaredMethod("getCurrentUser").invoke(authInstance)
                if (currentUser != null) {
                    val uid = currentUser.javaClass.getDeclaredMethod("getUid").invoke(currentUser) as? String
                    if (!uid.isNullOrBlank()) uids.add(uid)
                }
            }
        }

        attempt("resolve UID via b45", silent = true) {
            val b45Class = loadClassFlexible(classLoader, "b45")
            if (b45Class != null) {
                val user = b45Class.getDeclaredMethod("a").invoke(null)
                if (user != null) {
                    val uid = user.javaClass.getDeclaredMethod("getUid").invoke(user) as? String
                    if (!uid.isNullOrBlank()) uids.add(uid)
                }
            }
        }

        attempt("resolve UIDs from shared_prefs Store XMLs", silent = true) {
            val sharedPrefsDir = if (context != null) {
                File(context.filesDir?.parentFile, "shared_prefs")
            } else {
                File("/data/data/org.swiftapps.swiftbackup/shared_prefs")
            }
            if (sharedPrefsDir.exists()) {
                sharedPrefsDir.listFiles { file -> file.name.startsWith("com.google.firebase.auth.api.Store") }?.forEach { storeFile ->
                    try {
                        val text = storeFile.readText(StandardCharsets.UTF_8)
                        val matcher = Pattern.compile("com\\.google\\.firebase\\.auth\\.GET_TOKEN_RESPONSE\\.([a-zA-Z0-9_-]+)").matcher(text)
                        while (matcher.find()) {
                            val uid = matcher.group(1)
                            if (!uid.isNullOrBlank()) uids.add(uid)
                        }
                    } catch (_: Throwable) {}
                }
            }
        }

        return uids.toList()
    }
}
