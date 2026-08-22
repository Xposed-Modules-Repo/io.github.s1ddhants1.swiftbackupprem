package io.github.s1ddhants1.swiftbackupprem.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Module
import java.lang.reflect.Executable

fun XposedModule.hookTracked(
    executable: Executable,
    idPrefix: String = "${executable.declaringClass.name}#${executable.name}"
): XposedInterface.HookBuilder {
    val params = executable.parameterTypes.joinToString(",") { it.name }
    val sig = "${executable.declaringClass.name}#${executable.name}($params)"
    val hookId = "${idPrefix.replace(Regex("[^A-Za-z0-9_.#-]"), "_")}:${sig.hashCode().toUInt().toString(16)}"

    val builder = hook(executable).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
    if (apiVersion >= XposedInterface.API_102) builder.setId(hookId)
    val mod = this as? Module

    return object : XposedInterface.HookBuilder {
        override fun setPriority(priority: Int) = apply { builder.setPriority(priority) }
        override fun setExceptionMode(mode: XposedInterface.ExceptionMode) = apply { builder.setExceptionMode(mode) }
        override fun setId(id: String?) = apply { if (apiVersion >= XposedInterface.API_102) builder.setId(id) }
        override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
            builder.intercept(hooker).also { mod?.rememberHook(hookId, it) }
    }
}
