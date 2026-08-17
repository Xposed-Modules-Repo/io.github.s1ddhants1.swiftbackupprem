package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.io.File

@Keep
fun XposedModule.hookBackupApk(cl: ClassLoader, ctx: Context, customFirebaseApp: Boolean, prefs: PreferencesManager) {
    val bClass = backupApkClass ?: return
    val pClass = pathsClass ?: return

    val backupMethod = bClass.declaredMethods.firstOrNull { it.name == "c" }
        ?: bClass.declaredMethods.firstOrNull { it.name == "invoke" }
        ?: bClass.declaredMethods.firstOrNull { it.parameterTypes.isEmpty() }
        ?: return

    attempt("hook backup method ${backupMethod.name}") {
        hook(backupMethod).intercept { chain ->
            val result = chain.proceed()
            attempt("save module backup artifacts") {
                val basePath = attempt("resolve basePath") {
                    if (pClass.name == "defpackage.ry5" || pClass.declaredFields.any { it.name == "v" }) {
                        val vField = pClass.getDeclaredField("v")
                        vField.isAccessible = true
                        var vInstance = vField.get(null)
                        if (vInstance == null) {
                            attempt("invoke qy5.f to obtain vInstance") {
                                val qy5 = cl.loadClass("defpackage.qy5")
                                val fMethod = qy5.getDeclaredMethod("f", Boolean::class.javaPrimitiveType, cl.loadClass("defpackage.zn7"))
                                vInstance = fMethod.invoke(null, false, null)
                            }
                        }
                        val aField = pClass.getDeclaredField("a")
                        aField.isAccessible = true
                        aField.get(vInstance) as? String
                    } else {
                        val pathsA = cl.loadClass("${pClass.name}\$a")
                        val aInstance = pClass.declaredFields.firstOrNull { it.type == pathsA }?.get(null)
                        val instance = if (aInstance != null) pathsA.getDeclaredMethod("d").invoke(aInstance) else null
                        if (instance != null) pClass.getDeclaredMethod("m").invoke(instance) as? String else null
                    }
                } ?: return@intercept result

                val dir = File(basePath, "sbp")
                if (!dir.exists()) dir.mkdirs()

                val apkFile = File(dir, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).apk")
                if (!apkFile.exists()) {
                    attempt("copy module APK") {
                        File(ctx.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0).applicationInfo!!.sourceDir).copyTo(apkFile, true)
                    }
                }

                if (customFirebaseApp) {
                    val json = GoogleServicesJson.buildFromPrefs(prefs).toString(2)
                    attempt("write google-services.json backup") {
                        File(dir, "google-services.json").run { if (!exists() || readText() != json) writeText(json) }
                    }
                }
            }
            result
        }
    }
}
