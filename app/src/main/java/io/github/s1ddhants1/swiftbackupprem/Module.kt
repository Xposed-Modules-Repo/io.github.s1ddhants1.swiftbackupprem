package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import java.lang.reflect.Modifier

@Keep
class Module : XposedModule() {

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != Consts.packageName) return

        try {
            System.loadLibrary("nativelib")
        } catch (t: Throwable) {
            Log.e("SBP", "Failed to load nativelib native library", t)
        }

        val remotePrefs: SharedPreferences? = try {
            getRemotePreferences("settings")
        } catch (t: Throwable) {
            Log.w("SBP", "Failed to get remote preferences", t)
            null
        }

        val prefs = PreferencesManager(remotePrefs, isDynamic = true)
        val cl = param.classLoader

        // Neutralize System.exit and Runtime.exit to prevent forced JVM termination
        try {
            val systemExit = System::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            hook(systemExit).intercept { chain ->
                val code = chain.getArg(0) as? Int ?: 0
                Log.w("SBP", "Neutralized System.exit($code)")
                null
            }
        } catch (_: Throwable) {}

        try {
            val runtimeExit = Runtime::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            hook(runtimeExit).intercept { chain ->
                val code = chain.getArg(0) as? Int ?: 0
                Log.w("SBP", "Neutralized Runtime.exit($code)")
                null
            }
        } catch (_: Throwable) {}

        val swiftAppClass = try {
            cl.loadClass("org.swiftapps.swiftbackup.SwiftApp")
        } catch (t: Throwable) {
            Log.e("SBP", "Failed to load SwiftApp class", t)
            return
        }

        val onCreateMethod = try {
            swiftAppClass.getDeclaredMethod("onCreate")
        } catch (t: Throwable) {
            Log.e("SBP", "Failed to find SwiftApp.onCreate", t)
            return
        }

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

                try {
                    val appSourceDir = param.applicationInfo.sourceDir
                    findObfuscatedClasses(ctx, cl, appSourceDir)
                } catch (t: Throwable) {
                    Log.e("SBP", "Failed DexKit search", t)
                }

                // Initialize FirebaseApp (custom if configured, or default with APK resources)
                try {
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
                } catch (t: Throwable) {
                    Log.e("SBP", "Failed to initialize FirebaseApp", t)
                }

                if (isCustomFirebase) {
                    try {
                        val getClientIdMethod = swiftAppClass.getDeclaredMethod("getGoogleAuthAndroidClientId")
                        hook(getClientIdMethod).intercept { prefs.clientId }
                    } catch (_: Throwable) {}
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
                    try {
                        hookBackupApk(cl, ctx, isCustomFirebase, prefs)
                    } catch (t: Throwable) {
                        Log.e("SBP", "Failed to hook BackupApk", t)
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
                    try {
                        for (f in cIdClass.declaredFields) {
                            if (f.type == String::class.java && Modifier.isStatic(f.modifiers)) {
                                f.isAccessible = true
                                f.set(null, prefs.clientId)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e("SBP", "Failed setting clientId on $cIdClass in after onCreate", t)
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
                    try {
                        hook(m).intercept { chain ->
                            val task = chain.getArg(1)
                            val isSuccessful = try {
                                if (task != null) {
                                    task.javaClass.getMethod("isSuccessful").invoke(task) as? Boolean ?: true
                                } else true
                            } catch (_: Throwable) {
                                true
                            }
                            if (!isSuccessful && task != null) {
                                val exceptionMsg = try {
                                    (task.javaClass.getMethod("getException").invoke(task) as? Throwable)?.message
                                } catch (_: Throwable) {
                                    "unknown"
                                }
                                Log.w("SBP", "FirebaseAuth failed ($exceptionMsg), bypassing account block and forcing sign-in success")
                                val callback = chain.getArg(0)
                                if (callback != null) {
                                    try {
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
                                    } catch (t: Throwable) {
                                        Log.e("SBP", "Failed setting success callback", t)
                                    }
                                }
                            }
                            chain.proceed()
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun hookKnownClasses(cl: ClassLoader, isPremium: Boolean) {
        // V class hooks (via known class name as fallback)
        try {
            val vClass = cl.loadClass("org.swiftapps.swiftbackup.common.V")
            hookVClass(vClass, isPremium)
        } catch (_: Throwable) {}

        // V$a hook
        try {
            val vClassA = cl.loadClass("org.swiftapps.swiftbackup.common.V\$a")
            for (m in vClassA.declaredMethods) {
                if (m.name == "invoke") {
                    hook(m).intercept { isPremium }
                    break
                }
            }
        } catch (_: Throwable) {}

        // Hook NoGmsSignInActivity to dismiss blocked/failed dialogs and proceed with RESULT_OK
        try {
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
        } catch (_: Throwable) {}

        // Guarantee non-null MFirebaseUser across all known class names
        val knownAuthClassNames = listOf(
            "defpackage.d45",
            "org.swiftapps.swiftbackup.common.a3",
            "org.swiftapps.swiftbackup.anonymous.a"
        )
        for (name in knownAuthClassNames) {
            try {
                val authClass = cl.loadClass(name)
                hookAuthUserClass(authClass, cl)
            } catch (_: Throwable) {}
        }

        // Neutralize Const.Z0 (the block/signOut/exit handler)
        try {
            val constClass = cl.loadClass("org.swiftapps.swiftbackup.common.Const")
            for (m in constClass.declaredMethods) {
                if (m.name == "Z0" || (m.parameterCount == 1 && m.parameterTypes[0] == String::class.java && Modifier.isSynchronized(m.modifiers))) {
                    try {
                        hook(m).intercept { null }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookAuthUserClass(targetClass: Class<*>, cl: ClassLoader) {
        Log.d("SBP", "Hooking AuthUser class: ${targetClass.name}")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser")) {
                try {
                    hook(m).intercept { chain ->
                        val res = chain.proceed()
                        res ?: getFallbackUser(cl)
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun hookSwiftAppPremium(swiftApp: Any?, isPremium: Boolean) {
        if (swiftApp == null) return
        try {
            val appClass = swiftApp.javaClass
            for (field in appClass.declaredFields) {
                field.isAccessible = true
                val liveDataObj = try { field.get(swiftApp) } catch (_: Throwable) { null } ?: continue
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
                                try { m.invoke(liveDataObj, isPremium) } catch (_: Throwable) {}
                            }
                        }
                    }

                    // 2. Hook setter methods to always force isPremium on this specific instance
                    for (m in liveDataClass.declaredMethods) {
                        if (m.parameterCount == 1) {
                            try {
                                hook(m).intercept { chain ->
                                    if (chain.thisObject === liveDataObj && (chain.getArg(0) is Boolean || chain.getArg(0) == null)) {
                                        chain.proceed(arrayOf(isPremium))
                                    } else {
                                        chain.proceed()
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                        if (m.parameterCount == 0 && (m.name == "getValue" || m.name == "d")) {
                            try {
                                hook(m).intercept { chain ->
                                    if (chain.thisObject === liveDataObj) {
                                        isPremium
                                    } else {
                                        chain.proceed()
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("SBP", "Error hooking SwiftApp premium LiveData", t)
        }
    }

    private fun getFallbackUser(cl: ClassLoader): Any? {
        // 1. Try anonUserClass or known anonymous user generator classes
        val anonClasses = listOfNotNull(
            anonUserClass,
            try { cl.loadClass("defpackage.b45") } catch (_: Throwable) { null },
            try { cl.loadClass("org.swiftapps.swiftbackup.anonymous.a") } catch (_: Throwable) { null }
        )
        for (c in anonClasses) {
            try {
                for (m in c.declaredMethods) {
                    if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser") && Modifier.isStatic(m.modifiers)) {
                        m.isAccessible = true
                        val res = m.invoke(null)
                        if (res != null) return res
                    }
                }
            } catch (_: Throwable) {}
        }

        // 2. Direct MFirebaseUser instantiation
        return try {
            val mUserClass = cl.loadClass("org.swiftapps.swiftbackup.anonymous.MFirebaseUser")
            for (ctor in mUserClass.declaredConstructors) {
                if (ctor.parameterTypes.size == 7) {
                    ctor.isAccessible = true
                    return ctor.newInstance(
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
        } catch (t: Throwable) {
            Log.e("SBP", "Failed creating fallback MFirebaseUser", t)
            null
        }
    }

    private fun hookVClass(targetClass: Class<*>, isPremium: Boolean) {
        Log.d("SBP", "Hooking V class: ${targetClass.name} (isPremium=$isPremium)")
        try {
            val vpField = targetClass.getDeclaredField("vp")
            vpField.isAccessible = true
            vpField.set(null, isPremium)
        } catch (_: Throwable) {}

        for (m in targetClass.declaredMethods) {
            when (m.name) {
                "getA", "getG", "getVp" -> {
                    try { hook(m).intercept { isPremium } } catch (_: Throwable) {}
                }
                "setA", "setVp" -> {
                    if (m.parameterCount == 1) {
                        try {
                            hook(m).intercept { chain ->
                                chain.proceed(arrayOf(isPremium))
                            }
                        } catch (_: Throwable) {}
                    }
                }
                "getC" -> {
                    // getC is the 'isBlocked' check: MUST return FALSE!
                    try { hook(m).intercept { java.lang.Boolean.FALSE } } catch (_: Throwable) {}
                }
                "getB" -> {
                    // getB is the 'isBannedVersion' check: MUST return null
                    try { hook(m).intercept { null } } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun hookHomeViewModelClass(targetClass: Class<*>, isPremium: Boolean) {
        Log.d("SBP", "Hooking HomeViewModel class: ${targetClass.name} (isPremium=$isPremium)")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 1 && (m.parameterTypes[0] == Boolean::class.javaPrimitiveType || m.parameterTypes[0] == Boolean::class.javaObjectType)) {
                Log.d("SBP", "Hooking HomeViewModel method: ${m.name}(${m.parameterTypes[0].name}) -> $isPremium")
                try {
                    hook(m).intercept { chain ->
                        chain.proceed(arrayOf(isPremium))
                    }
                } catch (t: Throwable) {
                    Log.e("SBP", "Failed hooking ${m.name} on ${targetClass.name}", t)
                }
            } else if (m.parameterCount == 0 && (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType)) {
                try {
                    hook(m).intercept { isPremium }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun getResourceString(ctx: Context, name: String, fallback: String): String {
        val resId = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (resId != 0) {
            try {
                ctx.getString(resId)
            } catch (_: Throwable) {
                fallback
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
            try {
                val clazz = cl.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name in methods) {
                        try { hook(m).intercept { null } } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        // Prefix-match for AppMeasurement variants
        for (name in listOf(
            "com.google.android.gms.measurement.AppMeasurement",
            "com.google.android.gms.measurement.internal.zzhd",
            "com.google.android.gms.measurement.internal.zzha"
        )) {
            try {
                val clazz = cl.loadClass(name)
                for (m in clazz.declaredMethods) {
                    if (m.name.startsWith("logEvent") || m.name.startsWith("setUserProperty")) {
                        try { hook(m).intercept { null } } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        // Force-disable collection: override arg to FALSE
        for ((className, methodName) in listOf(
            "com.google.firebase.crashlytics.FirebaseCrashlytics" to "setCrashlyticsCollectionEnabled",
            "com.google.firebase.analytics.FirebaseAnalytics" to "setAnalyticsCollectionEnabled"
        )) {
            try {
                val clazz = cl.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name == methodName) {
                        try {
                            hook(m).intercept { chain ->
                                if (chain.args.isNotEmpty()) chain.proceed(arrayOf(java.lang.Boolean.FALSE))
                                else chain.proceed()
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        // TransportRuntime.schedule: invoke onSchedule callback to prevent retries
        try {
            val runtimeClass = cl.loadClass("com.google.android.datatransport.runtime.TransportRuntime")
            for (m in runtimeClass.declaredMethods) {
                if (m.name == "schedule") {
                    try {
                        hook(m).intercept { chain ->
                            chain.args.lastOrNull()?.let { callback ->
                                try {
                                    callback.javaClass.getMethod("onSchedule", Exception::class.java)
                                        .invoke(callback, null)
                                } catch (_: Throwable) {}
                            }
                            null
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // CctTransportBackend.send: return BackendResponse.ok(1000L) instead of making network call
        try {
            val cctClass = cl.loadClass("com.google.android.datatransport.cct.CctTransportBackend")
            val backendResponseClass = cl.loadClass("com.google.android.datatransport.runtime.backends.BackendResponse")
            val dummyResponse = backendResponseClass.getMethod("ok", Long::class.javaPrimitiveType).invoke(null, 1000L)
            for (m in cctClass.declaredMethods) {
                if (m.name == "send" || (m.parameterCount == 1 && m.returnType.simpleName == "BackendResponse")) {
                    try { hook(m).intercept { dummyResponse } } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // checkForUnsentReports: return Tasks.forResult(false) to prevent report uploads
        try {
            val crashlyticsClass = cl.loadClass("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val tasksClass = cl.loadClass("com.google.android.gms.tasks.Tasks")
            val falseTask = tasksClass.getMethod("forResult", Any::class.java).invoke(null, java.lang.Boolean.FALSE)
            for (m in crashlyticsClass.declaredMethods) {
                if (m.name == "checkForUnsentReports") {
                    try { hook(m).intercept { falseTask } } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }
}
