package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

object PremiumFeatureHook : HookHandler {

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val isPremium = prefs.enablePremium
        Log.d("SBP", "Applying premium state: $isPremium")

        targets.vClass?.let { hookVClass(module, it, isPremium) }
        targets.homeViewModelClass?.let { hookHomeViewModelClass(module, it, isPremium) }
        hookKnownClasses(module, classLoader, isPremium)
    }

    fun hookSwiftAppPremium(module: XposedModule, swiftApp: Any?, isPremium: Boolean) {
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
                                module.hook(m).intercept { chain ->
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
                                module.hook(m).intercept { chain ->
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

    private fun hookKnownClasses(module: XposedModule, cl: ClassLoader, isPremium: Boolean) {
        // V class hooks (via known class name as fallback)
        attempt("load and hook known V class fallback", silent = true) {
            val vClass = cl.loadClass("org.swiftapps.swiftbackup.common.V")
            hookVClass(module, vClass, isPremium)
        }

        // V$a hook
        attempt("load and hook known V\$a class", silent = true) {
            val vClassA = cl.loadClass("org.swiftapps.swiftbackup.common.V\$a")
            for (m in vClassA.declaredMethods) {
                if (m.name == "invoke") {
                    module.hook(m).intercept { isPremium }
                    break
                }
            }
        }
    }

    private fun hookVClass(module: XposedModule, targetClass: Class<*>, isPremium: Boolean) {
        Log.d("SBP", "Hooking V class: ${targetClass.name} (isPremium=$isPremium)")
        attempt("set V.vp field") {
            val vpField = targetClass.getDeclaredField("vp")
            vpField.isAccessible = true
            vpField.set(null, isPremium)
        }

        for (m in targetClass.declaredMethods) {
            when (m.name) {
                "getA", "getG", "getVp" -> {
                    attempt("hook V method ${m.name}") { module.hook(m).intercept { isPremium } }
                }
                "setA", "setVp" -> {
                    if (m.parameterCount == 1) {
                        attempt("hook V setter ${m.name}") {
                            module.hook(m).intercept { chain ->
                                chain.proceed(arrayOf(isPremium))
                            }
                        }
                    }
                }
                "getC" -> {
                    // getC is the 'isBlocked' check: MUST return FALSE!
                    attempt("hook V.getC") { module.hook(m).intercept { java.lang.Boolean.FALSE } }
                }
                "getB" -> {
                    // getB is the 'isBannedVersion' check: MUST return null
                    attempt("hook V.getB") { module.hook(m).intercept { null } }
                }
            }
        }
    }

    private fun hookHomeViewModelClass(module: XposedModule, targetClass: Class<*>, isPremium: Boolean) {
        Log.d("SBP", "Hooking HomeViewModel class: ${targetClass.name} (isPremium=$isPremium)")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 1 && (m.parameterTypes[0] == Boolean::class.javaPrimitiveType || m.parameterTypes[0] == Boolean::class.javaObjectType)) {
                Log.d("SBP", "Hooking HomeViewModel method: ${m.name}(${m.parameterTypes[0].name}) -> $isPremium")
                attempt("hook HomeViewModel setter ${m.name}") {
                    module.hook(m).intercept { chain ->
                        chain.proceed(arrayOf(isPremium))
                    }
                }
            } else if (m.parameterCount == 0 && (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType)) {
                attempt("hook HomeViewModel getter ${m.name}") {
                    module.hook(m).intercept { isPremium }
                }
            }
        }
    }
}
