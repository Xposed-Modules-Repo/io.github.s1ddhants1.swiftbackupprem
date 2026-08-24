package io.github.s1ddhants1.swiftbackupprem.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Module
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.lang.reflect.Executable

private val HOOK_ID_SANITIZER = Regex("[^A-Za-z0-9_.#-]")

fun XposedModule.hookTracked(
    executable: Executable,
    idPrefix: String = "${executable.declaringClass.name}#${executable.name}",
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    deoptimize: Boolean = false
): XposedInterface.HookBuilder {
    if (deoptimize) {
        attempt("deoptimize ${executable.declaringClass.simpleName}#${executable.name}", silent = true) {
            deoptimize(executable)
        }
    }

    val params = executable.parameterTypes.joinToString(",") { it.name }
    val sig = "${executable.declaringClass.name}#${executable.name}($params)"
    var hookId = "${idPrefix.replace(HOOK_ID_SANITIZER, "_")}:${sig.hashCode().toUInt().toString(16)}"

    val builder = hook(executable)
        .setPriority(priority)
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)

    if (apiVersion >= XposedInterface.API_102) {
        builder.setId(hookId)
    }
    val mod = this as? Module

    return object : XposedInterface.HookBuilder {
        override fun setPriority(priority: Int) = apply { builder.setPriority(priority) }
        override fun setExceptionMode(mode: XposedInterface.ExceptionMode) = apply { builder.setExceptionMode(mode) }
        override fun setId(id: String?) = apply {
            if (id != null) hookId = id
            if (apiVersion >= XposedInterface.API_102) builder.setId(id)
        }
        override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
            builder.intercept(hooker).also { mod?.rememberHook(hookId, it) }
    }
}

fun Any.getFieldValue(name: String): Any? = attempt("get field $name", silent = true) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}

inline fun <reified T> Any.getTypedFieldValue(name: String): T? = getFieldValue(name) as? T


