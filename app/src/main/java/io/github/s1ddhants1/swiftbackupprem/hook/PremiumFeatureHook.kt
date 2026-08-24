package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

object PremiumFeatureHook : HookHandler {

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val isPremium = prefs.enablePremium
        Log.d(Consts.TAG, "Applying premium state: $isPremium")

        targets.vClass?.let { hookVClass(module, it, isPremium) }
        targets.homeViewModelClass?.let { hookHomeViewModelClass(module, it, isPremium) }
        hookKnownClasses(module, classLoader, isPremium, targets.vClass)
    }

    fun hookSwiftAppPremium(module: HookContext, swiftApp: Any?, isPremium: Boolean) {
        if (swiftApp == null) return
        attempt("hook SwiftApp premium LiveData") {
            for (field in swiftApp.javaClass.declaredFields) {
                field.isAccessible = true
                val liveDataObj = attempt("read field ${field.name}", silent = true) { field.get(swiftApp) } ?: continue
                val ldClass = liveDataObj.javaClass

                val isTarget = field.name == "a" || field.name == "mutablePremium"

                if (isTarget) {
                    for (m in ldClass.methods) {
                        if (m.parameterCount == 1 && m.name in listOf("k", "setValue", "postValue")) {
                            attempt("invoke LiveData setter ${m.name}", silent = true) { m.invoke(liveDataObj, isPremium) }
                        }
                    }
                    for (m in ldClass.declaredMethods) {
                        if (m.parameterCount == 1 && m.name in listOf("k", "setValue", "postValue")) {
                            attempt("hook LiveData setter ${m.name}") {
                                module.hookTracked(
                                    m,
                                    idPrefix = "premium-livedata-set-${m.name}",
                                    deoptimize = true
                                ).intercept { chain ->
                                    if (chain.thisObject === liveDataObj && (chain.getArg(0) is Boolean || chain.getArg(0) == null)) {
                                        chain.proceed(arrayOf(isPremium))
                                    } else chain.proceed()
                                }
                            }
                        } else if (m.parameterCount == 0 && (m.name == "getValue" || m.name == "d")) {
                            attempt("hook LiveData getter ${m.name}") {
                                module.hookTracked(
                                    m,
                                    idPrefix = "premium-livedata-get-${m.name}",
                                    deoptimize = true
                                ).intercept { chain ->
                                    if (chain.thisObject === liveDataObj) isPremium else chain.proceed()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookKnownClasses(module: HookContext, cl: ClassLoader, isPremium: Boolean, resolvedVClass: Class<*>?) {
        if (resolvedVClass?.name != "org.swiftapps.swiftbackup.common.V") {
            attempt("load and hook known V class fallback", silent = true) {
                hookVClass(module, cl.loadClass("org.swiftapps.swiftbackup.common.V"), isPremium)
            }
        }
        attempt("load and hook known V\$a class", silent = true) {
            val vClassA = cl.loadClass("org.swiftapps.swiftbackup.common.V\$a")
            for (m in vClassA.declaredMethods) {
                if (m.name == "invoke") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-lambda-invoke",
                        deoptimize = true
                    ).intercept { isPremium }
                    break
                }
            }
        }
    }

    private fun hookVClass(module: HookContext, targetClass: Class<*>, isPremium: Boolean) {
        Log.d(Consts.TAG, "Hooking V class: ${targetClass.name} (isPremium=$isPremium)")
        attempt("set V.vp field") {
            targetClass.getDeclaredField("vp").apply { isAccessible = true }.set(null, isPremium)
        }

        for (m in targetClass.declaredMethods) {
            when (m.name) {
                "getA", "getG", "getVp" -> attempt("hook V getter ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-${m.name}",
                        deoptimize = true
                    ).intercept { isPremium }
                }
                "setA", "setVp" -> if (m.parameterCount == 1) attempt("hook V setter ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-${m.name}",
                        deoptimize = true
                    ).intercept { chain -> chain.proceed(arrayOf(isPremium)) }
                }
                "getC" -> attempt("hook V.getC") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-getC",
                        deoptimize = true
                    ).intercept { java.lang.Boolean.FALSE }
                }
                "getB" -> attempt("hook V.getB") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-v-getB",
                        deoptimize = true
                    ).intercept { null }
                }
            }
        }
    }

    private fun hookHomeViewModelClass(module: HookContext, targetClass: Class<*>, isPremium: Boolean) {
        Log.d(Consts.TAG, "Hooking HomeViewModel class: ${targetClass.name} (isPremium=$isPremium)")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 1 && (m.parameterTypes[0] == Boolean::class.javaPrimitiveType || m.parameterTypes[0] == Boolean::class.javaObjectType)) {
                attempt("hook HomeViewModel setter ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-homevm-set-${m.name}",
                        deoptimize = true
                    ).intercept { chain -> chain.proceed(arrayOf(isPremium)) }
                }
            } else if (m.parameterCount == 0 && (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType)) {
                attempt("hook HomeViewModel getter ${m.name}") {
                    module.hookTracked(
                        m,
                        idPrefix = "premium-homevm-get-${m.name}",
                        deoptimize = true
                    ).intercept { isPremium }
                }
            }
        }
    }
}
