package io.github.s1ddhants1.swiftbackupprem.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.attemptOrDefault
import java.lang.reflect.Modifier

object FirebaseInitHook : HookHandler {

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val isCustom = prefs.customFirebaseApp && prefs.toConfig().isCompleteFirebaseConfig
        if (!isCustom) return

        attempt("initialize FirebaseApp") {
            val fbAppClass = classLoader.loadClass("com.google.firebase.FirebaseApp")
            val optClass = classLoader.loadClass("com.google.firebase.FirebaseOptions")
            val ctor = optClass.getDeclaredConstructor(*Array(7) { String::class.java })

            val appId = prefs.googleAppId
            val apiKey = prefs.googleApiKey
            val dbUrl = prefs.firebaseDatabaseUrl
            val senderId = prefs.gcmDefaultSenderId
            val storageBucket = prefs.googleStorageBucket.ifBlank { "${prefs.projectId}.appspot.com" }
            val projectId = prefs.projectId

            val optionsInstance = ctor.newInstance(appId, apiKey, dbUrl, null, senderId, storageBucket, projectId)
            fbAppClass.getDeclaredMethod("initializeApp", Context::class.java, optClass).invoke(null, context, optionsInstance)
            Log.d(Consts.TAG, "Initialized FirebaseApp (custom: true, project: $projectId)")
        }

        attempt("hook getGoogleAuthAndroidClientId") {
            val swiftAppClass = classLoader.loadClass("org.swiftapps.swiftbackup.SwiftApp")
            module.hookTracked(swiftAppClass.getDeclaredMethod("getGoogleAuthAndroidClientId")).intercept { prefs.clientId }
        }
    }

    fun applyStaticClientId(targets: ResolvedTargets, prefs: PreferencesManager) {
        if (prefs.customFirebaseApp && prefs.clientId.isNotBlank()) {
            targets.clientIdClass?.let { cIdClass ->
                attempt("set static clientId on $cIdClass") {
                    for (f in cIdClass.declaredFields) {
                        if (f.type == String::class.java && Modifier.isStatic(f.modifiers)) {
                            f.isAccessible = true
                            f.set(null, prefs.clientId)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun getResourceString(ctx: Context, name: String, fallback: String): String {
        val resId = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (resId != 0) attemptOrDefault("getResourceString $name", fallback, silent = true) { ctx.getString(resId) } else fallback
    }
}
