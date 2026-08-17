package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import java.io.File

@Keep
fun XposedModule.hookBackupApk(cl: ClassLoader, ctx: Context, customFirebaseApp: Boolean, prefs: PreferencesManager) {
    val bClass = backupApkClass ?: return
    val pClass = pathsClass ?: return

    val backupMethod = bClass.declaredMethods.firstOrNull { it.name == "c" }
        ?: bClass.declaredMethods.firstOrNull { it.name == "invoke" }
        ?: bClass.declaredMethods.firstOrNull { it.parameterTypes.isEmpty() }
        ?: return

    try {
        hook(backupMethod).intercept { chain ->
            val result = chain.proceed()
            try {
                val basePath = try {
                    if (pClass.name == "defpackage.ry5" || pClass.declaredFields.any { it.name == "v" }) {
                        val vField = pClass.getDeclaredField("v")
                        vField.isAccessible = true
                        var vInstance = vField.get(null)
                        if (vInstance == null) {
                            try {
                                val qy5 = cl.loadClass("defpackage.qy5")
                                val fMethod = qy5.getDeclaredMethod("f", Boolean::class.javaPrimitiveType, cl.loadClass("defpackage.zn7"))
                                vInstance = fMethod.invoke(null, false, null)
                            } catch (t: Throwable) {
                                Log.e("SBP", "Failed to invoke qy5.f", t)
                            }
                        }
                        val aField = pClass.getDeclaredField("a")
                        aField.isAccessible = true
                        aField.get(vInstance) as? String ?: return@intercept result
                    } else {
                        val pathsA = cl.loadClass("${pClass.name}\$a")
                        val aInstance = pClass.declaredFields.firstOrNull { it.type == pathsA }?.get(null) ?: return@intercept result
                        val instance = pathsA.getDeclaredMethod("d").invoke(aInstance)
                        pClass.getDeclaredMethod("m").invoke(instance) as? String ?: return@intercept result
                    }
                } catch (e: Throwable) {
                    Log.e("SBP", "Failed to resolve basePath", e)
                    return@intercept result
                }

                val dir = File(basePath, "sbp")
                if (!dir.exists()) dir.mkdirs()

                val apkFile = File(dir, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).apk")
                if (!apkFile.exists()) {
                    try {
                        File(ctx.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0).applicationInfo!!.sourceDir).copyTo(apkFile, true)
                    } catch (t: Throwable) {
                        Log.e("SBP", "Failed copying module APK", t)
                    }
                }

                if (customFirebaseApp) {
                    val json = GoogleServicesJson.buildFromPrefs(prefs).toString(2)

                    try {
                        File(dir, "google-services.json").run { if (!exists() || readText() != json) writeText(json) }
                    } catch (t: Throwable) {
                        Log.e("SBP", "Failed writing google-services.json", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e("SBP", "Failed in hookBackupApk after hook", t)
            }
            result
        }
    } catch (t: Throwable) {
        Log.e("SBP", "Failed to hook backup method", t)
    }
}
