package io.github.s1ddhants1.swiftbackupprem.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
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
        val isCustomFirebase = prefs.customFirebaseApp &&
                prefs.googleAppId.isNotBlank() &&
                prefs.googleApiKey.isNotBlank() &&
                prefs.firebaseDatabaseUrl.isNotBlank() &&
                prefs.gcmDefaultSenderId.isNotBlank() &&
                prefs.projectId.isNotBlank() &&
                prefs.clientId.isNotBlank()

        // Initialize FirebaseApp (custom if configured, or default with APK resources)
        attempt("initialize FirebaseApp") {
            val firebaseAppClass = classLoader.loadClass("com.google.firebase.FirebaseApp")
            val optionsClass = classLoader.loadClass("com.google.firebase.FirebaseOptions")
            val constructorParams = Array(7) { String::class.java }
            val constructor = optionsClass.getDeclaredConstructor(*constructorParams)

            val appId: String
            val apiKey: String
            val dbUrl: String
            val senderId: String
            val storageBucket: String
            val projectId: String

            if (isCustomFirebase) {
                appId = prefs.googleAppId
                apiKey = prefs.googleApiKey
                dbUrl = prefs.firebaseDatabaseUrl
                senderId = prefs.gcmDefaultSenderId
                storageBucket = if (prefs.googleStorageBucket.isNotBlank()) prefs.googleStorageBucket else "${prefs.projectId}.appspot.com"
                projectId = prefs.projectId
            } else {
                appId = getResourceString(context, "google_app_id", "1:65312358122:android:ea39a9e3952e6522")
                apiKey = getResourceString(context, "google_api_key", "")
                dbUrl = getResourceString(context, "firebase_database_url", "https://swift-backup-31751.firebaseio.com")
                senderId = getResourceString(context, "gcm_defaultSenderId", "65312358122")
                storageBucket = getResourceString(context, "google_storage_bucket", "swift-backup-31751.appspot.com")
                projectId = getResourceString(context, "project_id", "swift-backup-31751")
            }

            val optionsInstance = constructor.newInstance(
                appId,
                apiKey,
                dbUrl,
                null,
                senderId,
                storageBucket,
                projectId
            )

            val initializeAppMethod = firebaseAppClass.getDeclaredMethod(
                "initializeApp",
                Context::class.java,
                optionsClass
            )
            initializeAppMethod.invoke(null, context, optionsInstance)
            Log.d("SBP", "Initialized FirebaseApp (custom: $isCustomFirebase, project: $projectId)")
        }

        if (isCustomFirebase) {
            attempt("hook getGoogleAuthAndroidClientId") {
                val swiftAppClass = classLoader.loadClass("org.swiftapps.swiftbackup.SwiftApp")
                val getClientIdMethod = swiftAppClass.getDeclaredMethod("getGoogleAuthAndroidClientId")
                module.hook(getClientIdMethod).intercept { prefs.clientId }
            }
        }
    }

    fun applyStaticClientId(targets: ResolvedTargets, prefs: PreferencesManager) {
        val isCustomFirebase = prefs.customFirebaseApp && prefs.clientId.isNotBlank()
        if (isCustomFirebase) {
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
        return if (resId != 0) {
            attemptOrDefault("getResourceString $name", fallback, silent = true) {
                ctx.getString(resId)
            }
        } else {
            fallback
        }
    }
}
