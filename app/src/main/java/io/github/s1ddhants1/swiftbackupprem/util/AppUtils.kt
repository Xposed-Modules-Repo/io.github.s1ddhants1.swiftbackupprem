package io.github.s1ddhants1.swiftbackupprem.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

object AppUtils {
    fun openSwiftBackup(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(Consts.packageName)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(Consts.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(Consts.packageName)
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        attempt("launch Swift Backup") {
            context.startActivity(launchIntent)
        }
    }

    suspend fun forceStopSwiftBackup(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean {
        val stopped = withContext(ioDispatcher) {
            var process: Process? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop ${Consts.packageName}"))
                withTimeoutOrNull(3_000) { process.waitFor() } == 0
            } catch (e: Exception) {
                Log.w("SBP", "Could not force-stop Swift Backup with su", e)
                false
            } finally {
                process?.destroy()
            }
        }
        if (stopped) return true

        attempt("open Swift Backup app settings") {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", Consts.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        return false
    }

    fun getSwiftBackupSha1(context: Context): String =
        getSha1ForPackage(context, Consts.packageName)
            ?: getSha1ForPackage(context, context.packageName)
            ?: ""

    fun formatSha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString(":") { "%02X".format(it) }

    private fun getSha1ForPackage(context: Context, packageName: String): String? {
        return attempt("get SHA-1 for package $packageName", silent = true) {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.let {
                    if (it.hasMultipleSigners()) it.apkContentsSigners
                    else it.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            signatures?.firstOrNull()?.toByteArray()?.let { cert ->
                formatSha1(cert)
            }
        }
    }
}
