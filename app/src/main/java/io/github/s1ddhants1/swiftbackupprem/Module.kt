package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

@Keep
class Module : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Consts.packageName) return

        try {
            System.loadLibrary("nativelib")
        } catch (t: Throwable) {
            Log.e("SBP", "Failed to load nativelib native library", t)
        }

        val xPrefs = XSharedPreferences(BuildConfig.APPLICATION_ID)
        try {
            @Suppress("DEPRECATION")
            xPrefs.makeWorldReadable()
        } catch (t: Throwable) {
            Log.w("SBP", "Could not set world readable on XSharedPreferences", t)
        }

        val prefs = PreferencesManager(xPrefs)
        val cl = lpparam.classLoader

        // Neutralize System.exit and Runtime.exit to prevent forced JVM termination
        try {
            XposedHelpers.findAndHookMethod(System::class.java, "exit", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val code = param.args[0] as? Int ?: 0
                    Log.w("SBP", "Neutralized System.exit($code)")
                    param.result = null
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(Runtime::class.java, "exit", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val code = param.args[0] as? Int ?: 0
                    Log.w("SBP", "Neutralized Runtime.exit($code)")
                    param.result = null
                }
            })
        } catch (_: Throwable) {}

        val swiftAppClass = try {
            cl.loadClass("org.swiftapps.swiftbackup.SwiftApp")
        } catch (t: Throwable) {
            Log.e("SBP", "Failed to load SwiftApp class", t)
            return
        }

        XposedHelpers.findAndHookMethod(swiftAppClass, "onCreate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                xPrefs.reload()
                val ctx = param.thisObject as? Context ?: return

                val isCustomFirebase = prefs.customFirebaseApp &&
                        prefs.googleAppId.isNotBlank() &&
                        prefs.googleApiKey.isNotBlank() &&
                        prefs.firebaseDatabaseUrl.isNotBlank() &&
                        prefs.gcmDefaultSenderId.isNotBlank() &&
                        prefs.projectId.isNotBlank() &&
                        prefs.clientId.isNotBlank()

                try {
                    val appSourceDir = lpparam.appInfo?.sourceDir ?: return
                    findObfuscatedClasses(ctx, cl, appSourceDir)
                } catch (t: Throwable) {
                    Log.e("SBP", "Failed DexKit search", t)
                }

                // Initialize FirebaseApp (custom if configured, or default with APK resources)
                try {
                    val firebaseAppClass = cl.loadClass("com.google.firebase.FirebaseApp")
                    val optionsClass = cl.loadClass("com.google.firebase.FirebaseOptions")
                    val params = Array(7) { String::class.java }
                    val constructor = optionsClass.getDeclaredConstructor(*params)

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
                        XposedHelpers.findAndHookMethod(
                            swiftAppClass,
                            "getGoogleAuthAndroidClientId",
                            XC_MethodReplacement.returnConstant(prefs.clientId)
                        )
                    } catch (_: Throwable) {}
                }

                if (prefs.enablePremium) {
                    // Hook dynamically discovered classes from DexKit
                    vClass?.let { hookVClass(it) }
                    homeViewModelClass?.let { hookHomeViewModelClass(it) }
                    authUserClass?.let { hookAuthUserClass(it, cl) }
                    hookSwiftAppPremium(param.thisObject)

                    clientIdClass?.let { cIdClass ->
                        for (m in cIdClass.declaredMethods) {
                            if (m.name == "e" && m.parameterCount == 2) {
                                try {
                                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            val task = param.args[1] ?: return
                                            val isSuccessful = try {
                                                task.javaClass.getMethod("isSuccessful").invoke(task) as? Boolean ?: true
                                            } catch (_: Throwable) {
                                                true
                                            }
                                            if (!isSuccessful) {
                                                val exceptionMsg = try {
                                                    (task.javaClass.getMethod("getException").invoke(task) as? Throwable)?.message
                                                } catch (_: Throwable) {
                                                    "unknown"
                                                }
                                                Log.w("SBP", "FirebaseAuth failed ($exceptionMsg), bypassing account block and forcing sign-in success")
                                                val callback = param.args[0] ?: return
                                                try {
                                                    for (nested in cIdClass.declaredClasses) {
                                                        for (inner in nested.declaredClasses) {
                                                            if (inner.simpleName == "b") {
                                                                val successInstance = inner.getField("a").get(null)
                                                                val invokeMethod = callback.javaClass.getMethod("invoke", Any::class.java)
                                                                invokeMethod.invoke(callback, successInstance)
                                                                param.result = null
                                                                return
                                                            }
                                                        }
                                                    }
                                                } catch (t: Throwable) {
                                                    Log.e("SBP", "Failed setting success callback", t)
                                                }
                                            }
                                        }
                                    })
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                } else {
                    Log.d("SBP", "Premium hooks disabled by user preference")
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

            override fun afterHookedMethod(param: MethodHookParam) {
                if (prefs.enablePremium) {
                    hookSwiftAppPremium(param.thisObject)
                }
                val isCustomFirebase = prefs.customFirebaseApp && prefs.clientId.isNotBlank()
                if (isCustomFirebase) {
                    clientIdClass?.let { cIdClass ->
                        try {
                            for (f in cIdClass.declaredFields) {
                                if (f.type == String::class.java && java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                                    f.isAccessible = true
                                    f.set(null, prefs.clientId)
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e("SBP", "Failed setting clientId on $cIdClass in afterHookedMethod", t)
                        }
                    }
                }
            }
        })

        if (prefs.enablePremium) {
            hookKnownClasses(cl)
        }
        hookTelemetrySuppression(cl, prefs)
    }

    private fun hookKnownClasses(cl: ClassLoader) {
        // V class hooks
        try {
            val vClass = cl.loadClass("org.swiftapps.swiftbackup.common.V")
            hookVClass(vClass)
        } catch (_: Throwable) {}

        // V$a hook
        try {
            val vClassA = cl.loadClass("org.swiftapps.swiftbackup.common.V\$a")
            for (m in vClassA.declaredMethods) {
                if (m.name == "invoke") {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(java.lang.Boolean.TRUE))
                    break
                }
            }
        } catch (_: Throwable) {}

        // Obfuscated HomeViewModel hook
        homeViewModelClass?.let { hookHomeViewModelClass(it) }

        // Obfuscated AuthUser hook
        authUserClass?.let { hookAuthUserClass(it, cl) }

        // Hook NoGmsSignInActivity to dismiss blocked/failed dialogs and proceed with RESULT_OK
        try {
            val noGmsClass = cl.loadClass("org.swiftapps.swiftbackup.cloud.connect.NoGmsSignInActivity")
            for (m in noGmsClass.declaredMethods) {
                if (m.parameterCount == 2 && m.parameterTypes[0] == noGmsClass && m.parameterTypes[1] == String::class.java) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val activity = param.args[0] as? android.app.Activity
                            activity?.let {
                                it.setResult(android.app.Activity.RESULT_OK)
                                it.finish()
                            }
                            param.result = null
                        }
                    })
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
                if (m.name == "Z0" || (m.parameterCount == 1 && m.parameterTypes[0] == String::class.java && java.lang.reflect.Modifier.isSynchronized(m.modifiers))) {
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
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
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == null) {
                                param.result = getFallbackUser(cl)
                            }
                        }
                    })
                } catch (_: Throwable) {}
            }
        }
    }

    private fun hookSwiftAppPremium(swiftApp: Any?) {
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
                    Log.d("SBP", "Found SwiftApp premium LiveData field: ${field.name} (${liveDataClass.name})")

                    // 1. Immediately force value to true
                    for (m in liveDataClass.methods) {
                        if (m.parameterCount == 1 && (m.parameterTypes[0] == Any::class.java || m.parameterTypes[0] == java.lang.Boolean::class.java || m.parameterTypes[0] == Boolean::class.javaPrimitiveType)) {
                            if (m.name in listOf("k", "setValue", "postValue", "i", "l", "p")) {
                                try { m.invoke(liveDataObj, java.lang.Boolean.TRUE) } catch (_: Throwable) {}
                            }
                        }
                    }

                    // 2. Hook setter methods to always force true on this specific instance
                    for (m in liveDataClass.declaredMethods) {
                        if (m.parameterCount == 1) {
                            try {
                                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        if (param.thisObject === liveDataObj && (param.args[0] is Boolean || param.args[0] == null)) {
                                            param.args[0] = java.lang.Boolean.TRUE
                                        }
                                    }
                                })
                            } catch (_: Throwable) {}
                        }
                        if (m.parameterCount == 0 && (m.name == "getValue" || m.name == "d")) {
                            try {
                                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        if (param.thisObject === liveDataObj) {
                                            param.result = java.lang.Boolean.TRUE
                                        }
                                    }
                                })
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
                    if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser") && java.lang.reflect.Modifier.isStatic(m.modifiers)) {
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

    private fun hookVClass(targetClass: Class<*>) {
        Log.d("SBP", "Hooking V class: ${targetClass.name}")
        try {
            val vpField = targetClass.getDeclaredField("vp")
            vpField.isAccessible = true
            vpField.set(null, true)
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(targetClass, "getA", XC_MethodReplacement.returnConstant(java.lang.Boolean.TRUE))
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(targetClass, "setA", Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = java.lang.Boolean.TRUE
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(targetClass, "getG", XC_MethodReplacement.returnConstant(java.lang.Boolean.TRUE))
        } catch (_: Throwable) {}

        // getC is the 'isBlocked' check: MUST return FALSE!
        try {
            XposedHelpers.findAndHookMethod(targetClass, "getC", XC_MethodReplacement.returnConstant(java.lang.Boolean.FALSE))
        } catch (_: Throwable) {}

        // getB is the 'isBannedVersion' check: MUST return null
        try {
            XposedHelpers.findAndHookMethod(targetClass, "getB", XC_MethodReplacement.returnConstant(null))
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(targetClass, "getVp", XC_MethodReplacement.returnConstant(java.lang.Boolean.TRUE))
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(targetClass, "setVp", Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = java.lang.Boolean.TRUE
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookHomeViewModelClass(targetClass: Class<*>) {
        Log.d("SBP", "Hooking HomeViewModel class: ${targetClass.name}")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 1 && (m.parameterTypes[0] == Boolean::class.javaPrimitiveType || m.parameterTypes[0] == Boolean::class.javaObjectType)) {
                Log.d("SBP", "Hooking HomeViewModel method: ${m.name}(${m.parameterTypes[0].name})")
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args[0] = java.lang.Boolean.TRUE
                        }
                    })
                } catch (t: Throwable) {
                    Log.e("SBP", "Failed hooking ${m.name} on ${targetClass.name}", t)
                }
            } else if (m.parameterCount == 0 && (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType)) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(java.lang.Boolean.TRUE))
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
        if (!prefs.suppressTelemetry) {
            Log.d("SBP", "Telemetry suppression disabled by user preference")
            return
        }

        Log.d("SBP", "Applying telemetry, analytics, and tracking suppression")

        suppressDataTransport(cl)
        suppressCrashlytics(cl)
        suppressAnalytics(cl)
        suppressSessions(cl)
        suppressInstallations(cl)
    }

    private fun suppressDataTransport(cl: ClassLoader) {
        // 1. Hook TransportRuntime
        try {
            val runtimeClass = cl.loadClass("com.google.android.datatransport.runtime.TransportRuntime")
            for (m in runtimeClass.declaredMethods) {
                when (m.name) {
                    "send" -> {
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    Log.d("SBP", "Blocked DataTransport.send() request")
                                    param.result = null
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                    "schedule" -> {
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    Log.d("SBP", "Intercepted DataTransport.schedule() request")
                                    val callback = param.args.lastOrNull()
                                    if (callback != null) {
                                        try {
                                            val onSchedule = callback.javaClass.getMethod("onSchedule", Exception::class.java)
                                            onSchedule.invoke(callback, null)
                                        } catch (_: Throwable) {}
                                    }
                                    param.result = null
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Hook CctTransportBackend
        try {
            val cctClass = cl.loadClass("com.google.android.datatransport.cct.CctTransportBackend")
            for (m in cctClass.declaredMethods) {
                if (m.name == "send" || (m.parameterCount == 1 && m.returnType.simpleName == "BackendResponse")) {
                    try {
                        val backendResponseClass = cl.loadClass("com.google.android.datatransport.runtime.backends.BackendResponse")
                        val okMethod = backendResponseClass.getMethod("ok", Long::class.javaPrimitiveType)
                        val dummyResponse = okMethod.invoke(null, 1000L)
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                Log.d("SBP", "Blocked CctTransportBackend.send() network request to firebaselogging.googleapis.com")
                                param.result = dummyResponse
                            }
                        })
                    } catch (_: Throwable) {
                        try {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Hook Schedulers & Uploaders
        val transportSchedulers = listOf(
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.Uploader",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoScheduler",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerScheduler"
        )
        for (className in transportSchedulers) {
            try {
                val clazz = cl.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name in listOf("upload", "schedule", "logAndUpdateState")) {
                        try {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        Log.d("SBP", "Google DataTransport suppressed")
    }

    private fun suppressCrashlytics(cl: ClassLoader) {
        // 1. Hook FirebaseCrashlytics main class
        try {
            val crashlyticsClass = cl.loadClass("com.google.firebase.crashlytics.FirebaseCrashlytics")
            for (m in crashlyticsClass.declaredMethods) {
                when (m.name) {
                    "setCrashlyticsCollectionEnabled" -> {
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (param.args.isNotEmpty()) {
                                        param.args[0] = java.lang.Boolean.FALSE
                                    }
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                    "recordException", "log", "setCustomKey", "setUserId", "sendUnsentReports", "deleteUnsentReports" -> {
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    Log.d("SBP", "Blocked Crashlytics call: ${m.name}")
                                    param.result = null
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                    "checkForUnsentReports" -> {
                        try {
                            val tasksClass = cl.loadClass("com.google.android.gms.tasks.Tasks")
                            val forResult = tasksClass.getMethod("forResult", Any::class.java)
                            val falseTask = forResult.invoke(null, java.lang.Boolean.FALSE)
                            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(falseTask))
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Hook CrashlyticsCore / CrashlyticsController
        val coreClasses = listOf(
            "com.google.firebase.crashlytics.internal.common.CrashlyticsCore",
            "com.google.firebase.crashlytics.internal.common.CrashlyticsController",
            "com.google.firebase.crashlytics.ndk.FirebaseCrashlyticsNdk"
        )
        for (name in coreClasses) {
            try {
                val clazz = cl.loadClass(name)
                for (m in clazz.declaredMethods) {
                    if (m.name in listOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler")) {
                        try {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        Log.d("SBP", "Firebase Crashlytics suppressed")
    }

    private fun suppressAnalytics(cl: ClassLoader) {
        // 1. Hook FirebaseAnalytics
        try {
            val analyticsClass = cl.loadClass("com.google.firebase.analytics.FirebaseAnalytics")
            for (m in analyticsClass.declaredMethods) {
                when (m.name) {
                    "setAnalyticsCollectionEnabled" -> {
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (param.args.isNotEmpty()) {
                                        param.args[0] = java.lang.Boolean.FALSE
                                    }
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                    "logEvent" -> {
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val eventName = param.args.getOrNull(0)
                                    Log.d("SBP", "Blocked Firebase Analytics logEvent: $eventName")
                                    param.result = null
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                    "setUserProperty", "setUserId", "setCurrentScreen", "resetAnalyticsData" -> {
                        try {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Hook AppMeasurement
        val measurementClasses = listOf(
            "com.google.android.gms.measurement.AppMeasurement",
            "com.google.android.gms.measurement.internal.zzhd",
            "com.google.android.gms.measurement.internal.zzha"
        )
        for (name in measurementClasses) {
            try {
                val clazz = cl.loadClass(name)
                for (m in clazz.declaredMethods) {
                    if (m.name.startsWith("logEvent") || m.name.startsWith("setUserProperty")) {
                        try {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        Log.d("SBP", "Firebase Analytics suppressed")
    }

    private fun suppressSessions(cl: ClassLoader) {
        val sessionClasses = listOf(
            "com.google.firebase.sessions.FirebaseSessions",
            "com.google.firebase.sessions.SessionFirelogPublisherImpl",
            "com.google.firebase.sessions.SessionFirelogPublisher"
        )
        for (name in sessionClasses) {
            try {
                val clazz = cl.loadClass(name)
                for (m in clazz.declaredMethods) {
                    if (m.name in listOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent")) {
                        try {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        Log.d("SBP", "Firebase Sessions suppressed")
    }

    private fun suppressInstallations(cl: ClassLoader) {
        // 1. Hook FirebaseInstallations
        try {
            val installationsClass = cl.loadClass("com.google.firebase.installations.FirebaseInstallations")
            for (m in installationsClass.declaredMethods) {
                if (m.name == "delete") {
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // 2. Hook FirebaseInstallationServiceClient
        try {
            val clientClass = cl.loadClass("com.google.firebase.installations.remote.FirebaseInstallationServiceClient")
            for (m in clientClass.declaredMethods) {
                if (m.name in listOf("deleteFirebaseInstallation")) {
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        Log.d("SBP", "Firebase Installations suppressed")
    }
}


