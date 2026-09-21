package io.github.s1ddhants1.swiftbackupprem.util

import android.os.Build

object BackupTagHelper {
    /**
     * Replicates Swift Backup's `qb1.n(Build.MODEL)` logic:
     * Strips `[.#$\[\]/]` so that the tag string is safe for Firebase Realtime Database keys.
     */
    fun sanitize(tag: String?): String {
        val raw = tag?.takeIf { it.isNotBlank() } ?: getDefaultTag()
        return raw.replace(Regex("[.#$\\[\\]/]"), "")
    }

    /**
     * Resolves the default tag matching stock Swift Backup:
     * Falls back to `qb1.n(Build.MODEL)` (e.g. "CPH2573"), or "DEFAULT" if Build.MODEL is blank/mocked.
     */
    fun getDefaultTag(): String {
        val model = try {
            Build.MODEL?.takeIf { it.isNotBlank() } ?: "DEFAULT"
        } catch (_: Throwable) {
            "DEFAULT"
        }
        return model.replace(Regex("[.#$\\[\\]/]"), "")
    }
}
