package io.github.s1ddhants1.swiftbackupprem.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
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
        try {
            context.startActivity(launchIntent)
        } catch (e: Throwable) {
            Log.e("SBP", "Could not launch Swift Backup: ${e.localizedMessage}")
        }
    }

    fun forceStopSwiftBackup(context: Context) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop ${Consts.packageName}"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                return
            }
        } catch (_: Throwable) {}

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", Consts.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {}
    }

    fun getSwiftBackupSha1(context: Context): String =
        getSha1ForPackage(context, Consts.packageName)
            ?: getSha1ForPackage(context, context.packageName)
            ?: ""

    private fun getSha1ForPackage(context: Context, packageName: String): String? {
        return try {
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
                MessageDigest.getInstance("SHA-1").digest(cert)
                    .joinToString(":") { "%02X".format(it) }
            }
        } catch (_: Throwable) { null }
    }
}
