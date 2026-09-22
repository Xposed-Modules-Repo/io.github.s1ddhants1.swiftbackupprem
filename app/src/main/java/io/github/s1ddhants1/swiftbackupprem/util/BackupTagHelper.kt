package io.github.s1ddhants1.swiftbackupprem.util

import android.os.Build

object BackupTagHelper {
    fun sanitize(tag: String?): String {
        val raw = tag?.takeIf { it.isNotBlank() } ?: getDefaultTag()
        return raw.replace(Regex("[.#$\\[\\]/]"), "")
    }

    fun getDefaultTag(): String {
        val model = try {
            Build.MODEL?.takeIf { it.isNotBlank() } ?: "DEFAULT"
        } catch (_: Throwable) {
            "DEFAULT"
        }
        return model.replace(Regex("[.#$\\[\\]/]"), "")
    }
}
