package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

@Keep
object AuthBypassHook : HookHandler {

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        Log.d(Consts.TAG, "Applying core AuthBypassHook")
        targets.clientIdClass?.let { cIdClass ->
            for (m in cIdClass.declaredMethods) {
                if (m.name == "e" && m.parameterCount == 2) {
                    attempt("hook legacy FirebaseAuth bypass method ${m.name}") {
                        module.hookTracked(m).intercept { chain ->
                            val task = chain.getArg(1)
                            val isSuccessful = attempt("check task isSuccessful", silent = true) {
                                if (task != null) task.javaClass.getMethod("isSuccessful").invoke(task) as? Boolean ?: true else true
                            } ?: true

                            if (!isSuccessful && task != null) {
                                val callback = chain.getArg(0)
                                if (callback != null) {
                                    attempt("set auth success callback") {
                                        for (nested in cIdClass.declaredClasses) {
                                            for (inner in nested.declaredClasses) {
                                                if (inner.simpleName == "b") {
                                                    val successInstance = inner.getField("a").get(null)
                                                    callback.javaClass.getMethod("invoke", Any::class.java).invoke(callback, successInstance)
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
