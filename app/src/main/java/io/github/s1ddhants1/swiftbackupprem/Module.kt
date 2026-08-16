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

                // Hook dynamically discovered classes from DexKit
                vClass?.let { hookVClass(it) }
                homeViewModelClass?.let { hookHomeViewModelClass(it) }
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

                if (backupApkClass != null && pathsClass != null) {
                    try {
                        hookBackupApk(cl, ctx, isCustomFirebase, prefs)
                    } catch (t: Throwable) {
                        Log.e("SBP", "Failed to hook BackupApk", t)
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                hookSwiftAppPremium(param.thisObject)
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

        hookKnownClasses(cl)
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

        // Guarantee non-null MFirebaseUser so System.exit(0) is never called
        try {
            val a3Class = cl.loadClass("org.swiftapps.swiftbackup.common.a3")
            for (m in a3Class.declaredMethods) {
                if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser")) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == null) {
                                param.result = getFallbackUser(cl)
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        try {
            val authAClass = cl.loadClass("org.swiftapps.swiftbackup.anonymous.a")
            for (m in authAClass.declaredMethods) {
                if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser")) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == null) {
                                param.result = getFallbackUser(cl)
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

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
        return try {
            val authAClass = cl.loadClass("org.swiftapps.swiftbackup.anonymous.a")
            val companionField = authAClass.getField("b")
            val companionInstance = companionField.get(null)
            val dMethod = companionInstance.javaClass.getMethod("d")
            dMethod.invoke(companionInstance)
        } catch (_: Throwable) {
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
}
