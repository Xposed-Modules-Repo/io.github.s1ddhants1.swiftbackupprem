package io.github.s1ddhants1.swiftbackupprem.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.attempt

object ExitProtectionHook {
    fun apply(module: XposedModule) {
        for (clazz in listOf(System::class.java, Runtime::class.java)) {
            attempt("neutralize ${clazz.simpleName}.exit") {
                val m = clazz.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
                module.hookTracked(m).intercept { chain ->
                    Log.w(Consts.TAG, "Neutralized ${clazz.simpleName}.exit(${chain.getArg(0)})")
                    null
                }
            }
        }
    }
}
