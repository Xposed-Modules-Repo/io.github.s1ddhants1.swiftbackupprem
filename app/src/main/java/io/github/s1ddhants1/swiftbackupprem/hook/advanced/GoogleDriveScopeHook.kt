package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.hook.HookHandler
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

/**
 * Advanced & Experimental: Google Drive Full Scope Expander
 *
 * Dynamically upgrades OAuth authorization scopes from app-restricted (drive.file)
 * to full Google Drive access (drive) across all auth request builders, Uri parameters,
 * and activity intents.
 */
@Keep
object GoogleDriveScopeHook : HookHandler {

    private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FULL_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    private const val ENCODED_DRIVE_FILE_SCOPE = "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"
    private const val ENCODED_FULL_DRIVE_SCOPE = "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive"

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (!prefs.enableDriveDiscovery) {
            Log.d("SBP", "Google Drive scope upgrade is disabled by user preference")
            return
        }

        Log.d("SBP", "Applying GoogleDriveScopeHook (OAuth scope expansion to full Drive)")

        hookOAuthHelper(module, targets.oauthHelperClass)
        hookAuthRequestBuilder(module, targets.authRequestBuilderClass)
        hookUriBuilder(module)
        hookActivityStart(module, classLoader)
        hookIntentSetData(module)
        hookGmsScope(module, classLoader)
    }

    private fun hookOAuthHelper(module: XposedModule, clazz: Class<*>?) {
        if (clazz == null) return
        attempt("hook OAuthHelper constructors (${clazz.name})") {
            for (ctor in clazz.declaredConstructors) {
                module.hook(ctor).intercept { chain ->
                    val args = chain.args
                    var modified = false
                    val newArgs = args.map { arg ->
                        when {
                            arg is List<*> && arg.contains(DRIVE_FILE_SCOPE) -> {
                                modified = true
                                arg.map { if (it == DRIVE_FILE_SCOPE) FULL_DRIVE_SCOPE else it }
                            }
                            arg is String && arg == DRIVE_FILE_SCOPE -> {
                                modified = true
                                FULL_DRIVE_SCOPE
                            }
                            else -> arg
                        }
                    }.toTypedArray()

                    if (modified) {
                        Log.d("SBP", "Upgraded Google Drive scope in OAuthHelper constructor")
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookAuthRequestBuilder(module: XposedModule, clazz: Class<*>?) {
        if (clazz == null) return
        attempt("hook AuthRequestBuilder methods (${clazz.name})") {
            for (m in clazz.declaredMethods) {
                if (m.returnType != Void.TYPE && m.parameterCount == 0) {
                    module.hook(m).intercept { chain ->
                        val target = chain.thisObject
                        if (target != null) {
                            for (field in target.javaClass.declaredFields) {
                                try {
                                    field.isAccessible = true
                                    val value = field.get(target)
                                    if (value is String && value.contains(DRIVE_FILE_SCOPE)) {
                                        field.set(target, value.replace(DRIVE_FILE_SCOPE, FULL_DRIVE_SCOPE))
                                        Log.d("SBP", "Upgraded scope String field in ${target.javaClass.name}")
                                    } else if (value is List<*> && value.contains(DRIVE_FILE_SCOPE)) {
                                        val upgradedList = value.map { if (it == DRIVE_FILE_SCOPE) FULL_DRIVE_SCOPE else it }
                                        field.set(target, upgradedList)
                                        Log.d("SBP", "Upgraded scope List field in ${target.javaClass.name}")
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookUriBuilder(module: XposedModule) {
        attempt("hook Uri.Builder.appendQueryParameter") {
            val builderClass = Uri.Builder::class.java
            val m = builderClass.getDeclaredMethod("appendQueryParameter", String::class.java, String::class.java)
            module.hook(m).intercept { chain ->
                val key = chain.getArg(0) as? String
                val value = chain.getArg(1) as? String
                when {
                    key == "scope" && value != null && value.contains("drive.file") -> {
                        val upgraded = value
                            .replace(ENCODED_DRIVE_FILE_SCOPE, ENCODED_FULL_DRIVE_SCOPE)
                            .replace(DRIVE_FILE_SCOPE, FULL_DRIVE_SCOPE)
                            .replace("drive.file", "drive")
                        Log.d("SBP", "Upgraded Uri.Builder scope param: $upgraded")
                        chain.proceed(arrayOf(key, upgraded))
                    }
                    key == "prompt" && value != "consent" -> {
                        Log.d("SBP", "Forced Uri.Builder prompt param: consent")
                        chain.proceed(arrayOf(key, "consent"))
                    }
                    else -> chain.proceed()
                }
            }
        }
    }

    private fun hookActivityStart(module: XposedModule, cl: ClassLoader) {
        attempt("hook NoGmsSignInActivity.startActivityForResult", silent = true) {
            val activityClass = cl.loadClass("org.swiftapps.swiftbackup.cloud.connect.NoGmsSignInActivity")
            for (m in activityClass.declaredMethods) {
                if ((m.name == "startActivityForResult" || m.name == "O") && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Intent::class.java) {
                    module.hook(m).intercept { chain ->
                        val intent = chain.getArg(0) as? Intent
                        upgradeIntentUri(intent)
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookIntentSetData(module: XposedModule) {
        attempt("hook Intent.setData / setDataAndNormalize") {
            val intentClass = Intent::class.java
            for (methodName in listOf("setData", "setDataAndNormalize")) {
                val m = intentClass.getDeclaredMethod(methodName, Uri::class.java)
                module.hook(m).intercept { chain ->
                    val uri = chain.getArg(0) as? Uri
                    if (uri != null && (uri.toString().contains("drive.file") || uri.toString().contains("accounts.google.com"))) {
                        val newUri = upgradeUri(uri)
                        Log.d("SBP", "Upgraded Intent $methodName URI: $newUri")
                        chain.proceed(arrayOf(newUri))
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun upgradeIntentUri(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (data != null) {
            intent.data = upgradeUri(data)
        }
    }

    fun upgradeUri(uri: Uri): Uri {
        var uriStr = uri.toString()
        if (uriStr.contains("drive.file")) {
            uriStr = uriStr
                .replace(ENCODED_DRIVE_FILE_SCOPE, ENCODED_FULL_DRIVE_SCOPE)
                .replace(DRIVE_FILE_SCOPE, FULL_DRIVE_SCOPE)
                .replace("drive.file", "drive")
        }
        if (uriStr.contains("accounts.google.com/o/oauth2") && !uriStr.contains("prompt=consent")) {
            uriStr = if (uriStr.contains("prompt=")) {
                uriStr.replace(Regex("prompt=[^&]*"), "prompt=consent")
            } else {
                "$uriStr&prompt=consent"
            }
        }
        return Uri.parse(uriStr)
    }

    private fun hookGmsScope(module: XposedModule, cl: ClassLoader) {
        attempt("hook GMS Scope class fallback", silent = true) {
            val scopeClass = cl.loadClass("com.google.android.gms.common.api.Scope")
            for (ctor in scopeClass.declaredConstructors) {
                if (ctor.parameterCount == 1 && ctor.parameterTypes[0] == String::class.java) {
                    module.hook(ctor).intercept { chain ->
                        val scopeUri = chain.getArg(0) as? String
                        if (scopeUri == DRIVE_FILE_SCOPE) {
                            Log.d("SBP", "Upgraded GMS Scope constructor to $FULL_DRIVE_SCOPE")
                            chain.proceed(arrayOf(FULL_DRIVE_SCOPE))
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        }
    }
}
