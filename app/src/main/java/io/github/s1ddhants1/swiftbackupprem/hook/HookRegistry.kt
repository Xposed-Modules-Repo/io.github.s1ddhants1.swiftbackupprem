package io.github.s1ddhants1.swiftbackupprem.hook

import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.lang.reflect.Executable

const val PRIORITY_DEFAULT = 50
const val PRIORITY_LOWEST = -10000
const val PRIORITY_HIGHEST = 10000

enum class ExceptionMode {
    DEFAULT,
    PROTECTIVE,
    PASSTHROUGH
}

interface Chain {
    val thisObject: Any?
    val args: List<Any?>
    fun getArg(index: Int): Any? = args.getOrNull(index)
    fun proceed(): Any?
    fun proceed(args: Array<Any?>): Any?
}

fun interface HookHandle {
    fun unhook()
}

interface HookBuilder {
    fun setPriority(priority: Int): HookBuilder = this
    fun setExceptionMode(mode: ExceptionMode): HookBuilder = this
    fun setId(id: String?): HookBuilder = this
    fun intercept(hooker: (Chain) -> Any?): HookHandle
}

interface HookContext {
    fun hookTracked(
        executable: Executable,
        idPrefix: String = "${executable.declaringClass.name}#${executable.name}",
        priority: Int = PRIORITY_DEFAULT,
        deoptimize: Boolean = false
    ): HookBuilder

    fun deoptimize(executable: Executable): Boolean
}

fun Any.getFieldValue(name: String): Any? = attempt("get field $name", silent = true) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}

inline fun <reified T> Any.getTypedFieldValue(name: String): T? = getFieldValue(name) as? T


