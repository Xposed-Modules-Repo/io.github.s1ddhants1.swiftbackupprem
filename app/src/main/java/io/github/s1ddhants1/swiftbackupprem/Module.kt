package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.content.SharedPreferences
import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.attemptOrDefault
import java.lang.reflect.Modifier

@Keep
class Module : XposedModule() {

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != Consts.packageName) return

        attempt("load nativelib native library") {
            System.loadLibrary("nativelib")
        }

        val remotePrefs: SharedPreferences? = attempt("get remote preferences") {
            getRemotePreferences("settings")
        }

        val prefs = PreferencesManager(remotePrefs, isDynamic = true)
        val cl = param.classLoader

        // Neutralize System.exit and Runtime.exit to prevent forced JVM termination
        attempt("neutralize System.exit") {
            val systemExit = System::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            hook(systemExit).intercept { chain ->
                val code = chain.getArg(0) as? Int ?: 0
                Log.w("SBP", "Neutralized System.exit($code)")
                null
            }
        }

        attempt("neutralize Runtime.exit") {
            val runtimeExit = Runtime::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            hook(runtimeExit).intercept { chain ->
                val code = chain.getArg(0) as? Int ?: 0
                Log.w("SBP", "Neutralized Runtime.exit($code)")
                null
            }
        }

        val swiftAppClass = attempt("load SwiftApp class") {
            cl.loadClass("org.swiftapps.swiftbackup.SwiftApp")
        } ?: return

        val onCreateMethod = attempt("find SwiftApp.onCreate") {
            swiftAppClass.getDeclaredMethod("onCreate")
        } ?: return

        hook(onCreateMethod).intercept { chain ->
            val ctx = chain.thisObject as? Context
            if (ctx != null) {
                val isCustomFirebase = prefs.customFirebaseApp &&
                        prefs.googleAppId.isNotBlank() &&
                        prefs.googleApiKey.isNotBlank() &&
                        prefs.firebaseDatabaseUrl.isNotBlank() &&
                        prefs.gcmDefaultSenderId.isNotBlank() &&
                        prefs.projectId.isNotBlank() &&
                        prefs.clientId.isNotBlank()

                attempt("find obfuscated classes with DexKit") {
                    val appSourceDir = param.applicationInfo.sourceDir
                    findObfuscatedClasses(ctx, cl, appSourceDir)
                }

                // Initialize FirebaseApp (custom if configured, or default with APK resources)
                attempt("initialize FirebaseApp") {
                    val firebaseAppClass = cl.loadClass("com.google.firebase.FirebaseApp")
                    val optionsClass = cl.loadClass("com.google.firebase.FirebaseOptions")
                    val constructorParams = Array(7) { String::class.java }
                    val constructor = optionsClass.getDeclaredConstructor(*constructorParams)

                    val appId: String
                    val apiKey: String
                    val dbUrl: String
                    val senderId: String
                    val storageBucket: String
                    val projectId: String

                    if (isCustomFirebase) {
                        appId = prefs.googleAppId
                        apiKey = prefs.googleApiKey
                        dbUrl = prefs.firebaseDatabaseUrl
                        senderId = prefs.gcmDefaultSenderId
                        storageBucket = if (prefs.googleStorageBucket.isNotBlank()) prefs.googleStorageBucket else "${prefs.projectId}.appspot.com"
                        projectId = prefs.projectId
                    } else {
                        appId = getResourceString(ctx, "google_app_id", "1:65312358122:android:ea39a9e3952e6522")
                        apiKey = getResourceString(ctx, "google_api_key", "")
                        dbUrl = getResourceString(ctx, "firebase_database_url", "https://swift-backup-31751.firebaseio.com")
                        senderId = getResourceString(ctx, "gcm_defaultSenderId", "65312358122")
                        storageBucket = getResourceString(ctx, "google_storage_bucket", "swift-backup-31751.appspot.com")
                        projectId = getResourceString(ctx, "project_id", "swift-backup-31751")
                    }

                    val optionsInstance = constructor.newInstance(
                        appId,
                        apiKey,
                        dbUrl,
                        null,
                        senderId,
                        storageBucket,
                        projectId
                    )

                    val initializeAppMethod = firebaseAppClass.getDeclaredMethod(
                        "initializeApp",
                        Context::class.java,
                        optionsClass
                    )
                    initializeAppMethod.invoke(null, ctx, optionsInstance)
                    Log.d("SBP", "Initialized FirebaseApp (custom: $isCustomFirebase, project: $projectId)")
                }

                if (isCustomFirebase) {
                    attempt("hook getGoogleAuthAndroidClientId") {
                        val getClientIdMethod = swiftAppClass.getDeclaredMethod("getGoogleAuthAndroidClientId")
                        hook(getClientIdMethod).intercept { prefs.clientId }
                    }
                }

                val isPremium = prefs.enablePremium
                Log.d("SBP", "Applying premium state: $isPremium")

                // Hook dynamically discovered classes from DexKit
                vClass?.let { hookVClass(it, isPremium) }
                homeViewModelClass?.let { hookHomeViewModelClass(it, isPremium) }
                authUserClass?.let { hookAuthUserClass(it, cl) }
                hookKnownClasses(cl, isPremium)
                hookSwiftAppPremium(chain.thisObject, isPremium)

                if (isPremium) {
                    hookFirebaseAuthBypass()
                }

                if (backupApkClass != null && pathsClass != null) {
                    attempt("hook BackupApk") {
                        hookBackupApk(cl, ctx, isCustomFirebase, prefs)
                    }
                }

                // Apply telemetry suppression hooks
                hookTelemetrySuppression(cl, prefs)
            }

            // Proceed with original SwiftApp.onCreate()
            val result = chain.proceed()

            // After onCreate
            hookSwiftAppPremium(chain.thisObject, prefs.enablePremium)
            val isCustomFirebase = prefs.customFirebaseApp && prefs.clientId.isNotBlank()
            if (isCustomFirebase) {
                clientIdClass?.let { cIdClass ->
                    attempt("set static clientId on $cIdClass") {
                        for (f in cIdClass.declaredFields) {
                            if (f.type == String::class.java && Modifier.isStatic(f.modifiers)) {
                                f.isAccessible = true
                                f.set(null, prefs.clientId)
                            }
                        }
                    }
                }
            }

            result
        }
    }

    private fun hookFirebaseAuthBypass() {
        clientIdClass?.let { cIdClass ->
            for (m in cIdClass.declaredMethods) {
                if (m.name == "e" && m.parameterCount == 2) {
                    attempt("hook FirebaseAuth bypass method ${m.name}") {
                        hook(m).intercept { chain ->
                            val task = chain.getArg(1)
                            val isSuccessful = attempt("check task isSuccessful", silent = true) {
                                if (task != null) {
                                    task.javaClass.getMethod("isSuccessful").invoke(task) as? Boolean ?: true
                                } else true
                            } ?: true

                            if (!isSuccessful && task != null) {
                                val exceptionMsg = attempt("get task exception message", silent = true) {
                                    (task.javaClass.getMethod("getException").invoke(task) as? Throwable)?.message
                                } ?: "unknown"
                                Log.w("SBP", "FirebaseAuth failed ($exceptionMsg), bypassing account block and forcing sign-in success")
                                val callback = chain.getArg(0)
                                if (callback != null) {
                                    attempt("set auth success callback") {
                                        for (nested in cIdClass.declaredClasses) {
                                            for (inner in nested.declaredClasses) {
                                                if (inner.simpleName == "b") {
                                                    val successInstance = inner.getField("a").get(null)
                                                    val invokeMethod = callback.javaClass.getMethod("invoke", Any::class.java)
                                                    invokeMethod.invoke(callback, successInstance)
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
            }
        }
    }

    private fun hookKnownClasses(cl: ClassLoader, isPremium: Boolean) {
        // V class hooks (via known class name as fallback)
        attempt("load and hook known V class fallback", silent = true) {
            val vClass = cl.loadClass("org.swiftapps.swiftbackup.common.V")
            hookVClass(vClass, isPremium)
        }

        // V$a hook
        attempt("load and hook known V\$a class", silent = true) {
            val vClassA = cl.loadClass("org.swiftapps.swiftbackup.common.V\$a")
            for (m in vClassA.declaredMethods) {
                if (m.name == "invoke") {
                    hook(m).intercept { isPremium }
                    break
                }
            }
        }

        // Hook NoGmsSignInActivity to dismiss blocked/failed dialogs and proceed with RESULT_OK
        attempt("hook NoGmsSignInActivity", silent = true) {
            val noGmsClass = cl.loadClass("org.swiftapps.swiftbackup.cloud.connect.NoGmsSignInActivity")
            for (m in noGmsClass.declaredMethods) {
                if (m.parameterCount == 2 && m.parameterTypes[0] == noGmsClass && m.parameterTypes[1] == String::class.java) {
                    hook(m).intercept { chain ->
                        val activity = chain.getArg(0) as? android.app.Activity
                        activity?.let {
                            it.setResult(android.app.Activity.RESULT_OK)
                            it.finish()
                        }
                        null
                    }
                }
            }
        }

        // Guarantee non-null MFirebaseUser across all known class names
        val knownAuthClassNames = listOf(
            "defpackage.d45",
            "org.swiftapps.swiftbackup.common.a3",
            "org.swiftapps.swiftbackup.anonymous.a"
        )
        for (name in knownAuthClassNames) {
            attempt("load and hook auth class $name", silent = true) {
                val authClass = cl.loadClass(name)
                hookAuthUserClass(authClass, cl)
            }
        }

        // Neutralize Const.Z0 (the block/signOut/exit handler)
        attempt("neutralize Const.Z0", silent = true) {
            val constClass = cl.loadClass("org.swiftapps.swiftbackup.common.Const")
            for (m in constClass.declaredMethods) {
                if (m.name == "Z0" || (m.parameterCount == 1 && m.parameterTypes[0] == String::class.java && Modifier.isSynchronized(m.modifiers))) {
                    attempt("hook Const.Z0 method ${m.name}") {
                        hook(m).intercept { null }
                    }
                }
            }
        }
    }

    private fun hookAuthUserClass(targetClass: Class<*>, cl: ClassLoader) {
        Log.d("SBP", "Hooking AuthUser class: ${targetClass.name}")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser")) {
                attempt("hook AuthUser method ${m.name}") {
                    hook(m).intercept { chain ->
                        val res = chain.proceed()
                        res ?: getFallbackUser(cl)
                    }
                }
            }
        }
    }

    private fun hookSwiftAppPremium(swiftApp: Any?, isPremium: Boolean) {
        if (swiftApp == null) return
        attempt("hook SwiftApp premium LiveData") {
            val appClass = swiftApp.javaClass
            for (field in appClass.declaredFields) {
                field.isAccessible = true
                val liveDataObj = attempt("read field ${field.name}", silent = true) { field.get(swiftApp) } ?: continue
                val liveDataClass = liveDataObj.javaClass

                val isTarget = field.name == "a" ||
                        field.name == "mutablePremium" ||
                        liveDataClass.name.contains("LiveData") ||
                        liveDataClass.superclass?.name?.contains("LiveData") == true ||
                        liveDataClass.name == "defpackage.ex6" ||
                        liveDataClass.name == "el.a"

                if (isTarget) {
                    Log.d("SBP", "Found SwiftApp premium LiveData field: ${field.name} (${liveDataClass.name}) -> setting $isPremium")

                    // 1. Immediately force value to isPremium
                    for (m in liveDataClass.methods) {
                        if (m.parameterCount == 1 && (m.parameterTypes[0] == Any::class.java || m.parameterTypes[0] == Boolean::class.javaObjectType || m.parameterTypes[0] == Boolean::class.javaPrimitiveType)) {
                            if (m.name in listOf("k", "setValue", "postValue", "i", "l", "p")) {
                                attempt("invoke LiveData setter ${m.name}", silent = true) { m.invoke(liveDataObj, isPremium) }
                            }
                        }
                    }

                    // 2. Hook setter methods to always force isPremium on this specific instance
                    for (m in liveDataClass.declaredMethods) {
                        if (m.parameterCount == 1) {
                            attempt("hook LiveData setter ${m.name}") {
                                hook(m).intercept { chain ->
                                    if (chain.thisObject === liveDataObj && (chain.getArg(0) is Boolean || chain.getArg(0) == null)) {
                                        chain.proceed(arrayOf(isPremium))
                                    } else {
                                        chain.proceed()
                                    }
                                }
                            }
                        }
                        if (m.parameterCount == 0 && (m.name == "getValue" || m.name == "d")) {
                            attempt("hook LiveData getter ${m.name}") {
                                hook(m).intercept { chain ->
                                    if (chain.thisObject === liveDataObj) {
                                        isPremium
                                    } else {
                                        chain.proceed()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getFallbackUser(cl: ClassLoader): Any? {
        // 1. Try anonUserClass or known anonymous user generator classes
        val anonClasses = listOfNotNull(
            anonUserClass,
            attempt("load defpackage.b45", silent = true) { cl.loadClass("defpackage.b45") },
            attempt("load org.swiftapps.swiftbackup.anonymous.a", silent = true) { cl.loadClass("org.swiftapps.swiftbackup.anonymous.a") }
        )
        for (c in anonClasses) {
            attempt("get fallback user from ${c.name}", silent = true) {
                for (m in c.declaredMethods) {
                    if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser") && Modifier.isStatic(m.modifiers)) {
                        m.isAccessible = true
                        val res = m.invoke(null)
                        if (res != null) return res
                    }
                }
            }
        }

        // 2. Direct MFirebaseUser instantiation
        return attempt("create direct fallback MFirebaseUser") {
            val mUserClass = cl.loadClass("org.swiftapps.swiftbackup.anonymous.MFirebaseUser")
            for (ctor in mUserClass.declaredConstructors) {
                if (ctor.parameterTypes.size == 7) {
                    ctor.isAccessible = true
                    return@attempt ctor.newInstance(
                        "anonymous_user",
                        "anonymous@swiftbackup.app",
                        true,
                        "Anonymous user",
                        null,
                        emptyList<Any>(),
                        "anonymous"
                    )
                }
            }
            null
        }
    }

    private fun hookVClass(targetClass: Class<*>, isPremium: Boolean) {
        Log.d("SBP", "Hooking V class: ${targetClass.name} (isPremium=$isPremium)")
        attempt("set V.vp field") {
            val vpField = targetClass.getDeclaredField("vp")
            vpField.isAccessible = true
            vpField.set(null, isPremium)
        }

        for (m in targetClass.declaredMethods) {
            when (m.name) {
                "getA", "getG", "getVp" -> {
                    attempt("hook V method ${m.name}") { hook(m).intercept { isPremium } }
                }
                "setA", "setVp" -> {
                    if (m.parameterCount == 1) {
                        attempt("hook V setter ${m.name}") {
                            hook(m).intercept { chain ->
                                chain.proceed(arrayOf(isPremium))
                            }
                        }
                    }
                }
                "getC" -> {
                    // getC is the 'isBlocked' check: MUST return FALSE!
                    attempt("hook V.getC") { hook(m).intercept { java.lang.Boolean.FALSE } }
                }
                "getB" -> {
                    // getB is the 'isBannedVersion' check: MUST return null
                    attempt("hook V.getB") { hook(m).intercept { null } }
                }
            }
        }
    }

    private fun hookHomeViewModelClass(targetClass: Class<*>, isPremium: Boolean) {
        Log.d("SBP", "Hooking HomeViewModel class: ${targetClass.name} (isPremium=$isPremium)")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 1 && (m.parameterTypes[0] == Boolean::class.javaPrimitiveType || m.parameterTypes[0] == Boolean::class.javaObjectType)) {
                Log.d("SBP", "Hooking HomeViewModel method: ${m.name}(${m.parameterTypes[0].name}) -> $isPremium")
                attempt("hook HomeViewModel setter ${m.name}") {
                    hook(m).intercept { chain ->
                        chain.proceed(arrayOf(isPremium))
                    }
                }
            } else if (m.parameterCount == 0 && (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType)) {
                attempt("hook HomeViewModel getter ${m.name}") {
                    hook(m).intercept { isPremium }
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun getResourceString(ctx: Context, name: String, fallback: String): String {
        val resId = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (resId != 0) {
            attemptOrDefault("getResourceString $name", fallback, silent = true) {
                ctx.getString(resId)
            }
        } else {
            fallback
        }
    }

    private fun hookTelemetrySuppression(cl: ClassLoader, prefs: PreferencesManager) {
        if (!prefs.disableTelemetry) return
        Log.d("SBP", "Applying telemetry, analytics, and tracking suppression")

        // Bulk null-intercept: hook methods and return null
        val nullTargets = mapOf(
            // DataTransport
            "com.google.android.datatransport.runtime.TransportRuntime" to setOf("send"),
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.Uploader" to setOf("upload", "schedule", "logAndUpdateState"),
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoScheduler" to setOf("upload", "schedule", "logAndUpdateState"),
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerScheduler" to setOf("upload", "schedule", "logAndUpdateState"),
            // Crashlytics
            "com.google.firebase.crashlytics.FirebaseCrashlytics" to setOf("recordException", "log", "setCustomKey", "setUserId", "sendUnsentReports", "deleteUnsentReports"),
            "com.google.firebase.crashlytics.internal.common.CrashlyticsCore" to setOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler"),
            "com.google.firebase.crashlytics.internal.common.CrashlyticsController" to setOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler"),
            "com.google.firebase.crashlytics.ndk.FirebaseCrashlyticsNdk" to setOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler"),
            // Analytics
            "com.google.firebase.analytics.FirebaseAnalytics" to setOf("logEvent", "setUserProperty", "setUserId", "setCurrentScreen", "resetAnalyticsData"),
            // Sessions
            "com.google.firebase.sessions.FirebaseSessions" to setOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent"),
            "com.google.firebase.sessions.SessionFirelogPublisherImpl" to setOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent"),
            "com.google.firebase.sessions.SessionFirelogPublisher" to setOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent"),
            // Installations
            "com.google.firebase.installations.FirebaseInstallations" to setOf("delete"),
            "com.google.firebase.installations.remote.FirebaseInstallationServiceClient" to setOf("deleteFirebaseInstallation"),
        )
        for ((className, methods) in nullTargets) {
            attempt("hook telemetry in $className", silent = true) {
                val clazz = cl.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name in methods) {
                        attempt("hook method ${m.name} in $className", silent = true) {
                            hook(m).intercept { null }
                        }
                    }
                }
            }
        }

        // Prefix-match for AppMeasurement variants
        for (name in listOf(
            "com.google.android.gms.measurement.AppMeasurement",
            "com.google.android.gms.measurement.internal.zzhd",
            "com.google.android.gms.measurement.internal.zzha"
        )) {
            attempt("hook AppMeasurement class $name", silent = true) {
                val clazz = cl.loadClass(name)
                for (m in clazz.declaredMethods) {
                    if (m.name.startsWith("logEvent") || m.name.startsWith("setUserProperty")) {
                        attempt("hook method ${m.name} in $name", silent = true) {
                            hook(m).intercept { null }
                        }
                    }
                }
            }
        }

        // Force-disable collection: override arg to FALSE
        for ((className, methodName) in listOf(
            "com.google.firebase.crashlytics.FirebaseCrashlytics" to "setCrashlyticsCollectionEnabled",
            "com.google.firebase.analytics.FirebaseAnalytics" to "setAnalyticsCollectionEnabled"
        )) {
            attempt("hook collection enable method in $className", silent = true) {
                val clazz = cl.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name == methodName) {
                        attempt("hook $methodName in $className", silent = true) {
                            hook(m).intercept { chain ->
                                if (chain.args.isNotEmpty()) chain.proceed(arrayOf(java.lang.Boolean.FALSE))
                                else chain.proceed()
                            }
                        }
                    }
                }
            }
        }

        // TransportRuntime.schedule: invoke onSchedule callback to prevent retries
        attempt("hook TransportRuntime.schedule", silent = true) {
            val runtimeClass = cl.loadClass("com.google.android.datatransport.runtime.TransportRuntime")
            for (m in runtimeClass.declaredMethods) {
                if (m.name == "schedule") {
                    attempt("hook schedule in TransportRuntime", silent = true) {
                        hook(m).intercept { chain ->
                            chain.args.lastOrNull()?.let { callback ->
                                attempt("invoke schedule callback", silent = true) {
                                    callback.javaClass.getMethod("onSchedule", Exception::class.java)
                                        .invoke(callback, null)
                                }
                            }
                            null
                        }
                    }
                }
            }
        }

        // CctTransportBackend.send: return BackendResponse.ok(1000L) instead of making network call
        attempt("hook CctTransportBackend.send", silent = true) {
            val cctClass = cl.loadClass("com.google.android.datatransport.cct.CctTransportBackend")
            val backendResponseClass = cl.loadClass("com.google.android.datatransport.runtime.backends.BackendResponse")
            val dummyResponse = backendResponseClass.getMethod("ok", Long::class.javaPrimitiveType).invoke(null, 1000L)
            for (m in cctClass.declaredMethods) {
                if (m.name == "send" || (m.parameterCount == 1 && m.returnType.simpleName == "BackendResponse")) {
                    attempt("hook send in CctTransportBackend", silent = true) {
                        hook(m).intercept { dummyResponse }
                    }
                }
            }
        }

        // checkForUnsentReports: return Tasks.forResult(false) to prevent report uploads
        attempt("hook checkForUnsentReports", silent = true) {
            val crashlyticsClass = cl.loadClass("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val tasksClass = cl.loadClass("com.google.android.gms.tasks.Tasks")
            val falseTask = tasksClass.getMethod("forResult", Any::class.java).invoke(null, java.lang.Boolean.FALSE)
            for (m in crashlyticsClass.declaredMethods) {
                if (m.name == "checkForUnsentReports") {
                    attempt("hook checkForUnsentReports in FirebaseCrashlytics", silent = true) {
                        hook(m).intercept { falseTask }
                    }
                }
            }
        }
    }
}
