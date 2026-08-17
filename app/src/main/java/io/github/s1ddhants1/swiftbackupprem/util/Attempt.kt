package io.github.s1ddhants1.swiftbackupprem.util

import android.util.Log

/**
 * Executes [block] and returns its result, or returns `null` if an exception is thrown.
 *
 * @param operation Description of the operation for logging.
 * @param silent When `true`, suppresses warning logs (reserved for genuinely optional probes).
 */
inline fun <T> attempt(operation: String, silent: Boolean = false, block: () -> T): T? {
    return try {
        block()
    } catch (t: Throwable) {
        if (!silent) {
            Log.w("SBP", "Failed $operation: ${t.message}", t)
        }
        null
    }
}

/**
 * Executes [block] and returns its result, or returns [default] if an exception is thrown.
 */
inline fun <T> attemptOrDefault(operation: String, default: T, silent: Boolean = false, block: () -> T): T {
    return attempt(operation, silent, block) ?: default
}
