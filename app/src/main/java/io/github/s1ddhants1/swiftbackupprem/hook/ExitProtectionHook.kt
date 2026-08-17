package io.github.s1ddhants1.swiftbackupprem.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.util.attempt

object ExitProtectionHook {
    fun apply(module: XposedModule) {
        // Neutralize System.exit and Runtime.exit to prevent forced JVM termination
        attempt("neutralize System.exit") {
            val systemExit = System::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            module.hook(systemExit).intercept { chain ->
                val code = chain.getArg(0) as? Int ?: 0
                Log.w("SBP", "Neutralized System.exit($code)")
                null
            }
        }

        attempt("neutralize Runtime.exit") {
            val runtimeExit = Runtime::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
            module.hook(runtimeExit).intercept { chain ->
                val code = chain.getArg(0) as? Int ?: 0
                Log.w("SBP", "Neutralized Runtime.exit($code)")
                null
            }
        }
    }
}
