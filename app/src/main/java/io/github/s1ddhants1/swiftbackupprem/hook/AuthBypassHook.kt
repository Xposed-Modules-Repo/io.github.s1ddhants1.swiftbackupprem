package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

/**
 * Core Authentication Hook Handler:
 * - Provides authenticated user resolution
 * - Manages fallback user objects for offline / bypass usage
 */
@Keep
object AuthBypassHook : HookHandler {

    private const val TAG = "SBP"

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        Log.d(TAG, "Applying core AuthBypassHook")

        // 1. Hook legacy FirebaseAuth bypass if clientIdClass exists
        hookLegacyFirebaseAuthBypass(module, targets)
    }

    private fun hookLegacyFirebaseAuthBypass(module: XposedModule, targets: ResolvedTargets) {
        targets.clientIdClass?.let { cIdClass ->
            for (m in cIdClass.declaredMethods) {
                if (m.name == "e" && m.parameterCount == 2) {
                    attempt("hook legacy FirebaseAuth bypass method ${m.name}") {
                        module.hook(m).intercept { chain ->
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
                                Log.w(TAG, "Legacy FirebaseAuth failed ($exceptionMsg), forcing sign-in success")
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
}
