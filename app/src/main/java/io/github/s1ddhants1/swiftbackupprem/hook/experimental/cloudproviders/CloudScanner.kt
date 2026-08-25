package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep

@Keep
data class CloudFileItem(
    val id: String,
    val name: String,
    val size: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val thumbnailLink: String? = null,
    val provider: String = "Generic",
    val customDownloadUrl: String? = null
)

@Keep
interface CloudScanner {
    val providerName: String
    fun isConfigured(context: Context, prefs: SharedPreferences): Boolean
    fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem>
    fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String?
}
