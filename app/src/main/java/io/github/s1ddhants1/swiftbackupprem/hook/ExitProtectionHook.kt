package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

@Keep
object ExitProtectionHook : HookHandler {

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        neutralizeSystemExit(module)
    }

    fun applyEarly(module: XposedModule, classLoader: ClassLoader) {
        neutralizeSystemExit(module)
    }

    private fun neutralizeSystemExit(module: XposedModule) {
        attempt("neutralize System.exit", silent = true) {
            val m = System::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            module.hookTracked(m).intercept { chain ->
                val code = chain.getArg(0)
                Log.w(Consts.TAG, "Neutralized System.exit($code)")
                null
            }
        }
        attempt("neutralize Runtime.exit", silent = true) {
            val m = Runtime::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            module.hookTracked(m).intercept { chain ->
                val code = chain.getArg(0)
                Log.w(Consts.TAG, "Neutralized Runtime.exit($code)")
                null
            }
        }
    }
}

