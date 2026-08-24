package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.Keep
import androidx.core.net.toUri
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.HookContext
import io.github.s1ddhants1.swiftbackupprem.hook.HookHandler
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

/**
 * Advanced & Experimental: Google Drive Full Scope Expander
 * Dynamically upgrades OAuth authorization scopes from app-restricted (drive.file)
 * to full Google Drive access (drive) across auth request builders, Uri parameters, and intents.
 */
@Keep
object GoogleDriveScopeHook : HookHandler {

    private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FULL_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    private const val ENCODED_DRIVE_FILE_SCOPE = "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"
    private const val ENCODED_FULL_DRIVE_SCOPE = "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive"

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (!prefs.customFirebaseApp || !prefs.enableDriveDiscovery) {
            Log.d(Consts.TAG, "Google Drive scope upgrade is disabled (requires custom Firebase app and Drive discovery)")
            return
        }

        Log.d(Consts.TAG, "Applying GoogleDriveScopeHook (OAuth scope expansion to full Drive)")
        hookOAuthHelper(module, targets.oauthHelperClass)
        hookAuthRequestBuilder(module, targets.authRequestBuilderClass)
        hookUriBuilder(module)
        hookActivityStart(module, classLoader)
        hookIntentSetData(module)
        hookGmsScope(module, classLoader)
    }

    private fun hookOAuthHelper(module: HookContext, clazz: Class<*>?) {
        if (clazz == null) return
        attempt("hook OAuthHelper constructors (${clazz.name})") {
            for (ctor in clazz.declaredConstructors) {
                module.hookTracked(
                    ctor,
                    idPrefix = "drive-scope-oauth-helper"
                ).intercept { chain ->
                    var modified = false
                    val newArgs = chain.args.map { arg ->
                        when (arg) {
                            is String -> if (arg.contains(DRIVE_FILE_SCOPE)) {
                                modified = true
                                arg.replace(DRIVE_FILE_SCOPE, FULL_DRIVE_SCOPE)
                            } else arg
                            is Iterable<*> -> arg.map { item ->
                                if (item is String && item == DRIVE_FILE_SCOPE) {
                                    modified = true
                                    FULL_DRIVE_SCOPE
                                } else item
                            }
                            else -> arg
                        }
                    }.toTypedArray()

                    if (modified) {
                        Log.d(Consts.TAG, "Upgraded OAuthHelper scopes to full Google Drive access")
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookAuthRequestBuilder(module: HookContext, clazz: Class<*>?) {
        if (clazz == null) return
        attempt("hook AuthRequestBuilder.setScopes (${clazz.name})") {
            for (m in clazz.declaredMethods) {
                if (m.parameterTypes.any { Iterable::class.java.isAssignableFrom(it) || it.isArray }) {
                    module.hookTracked(
                        m,
                        idPrefix = "drive-scope-auth-req-builder"
                    ).intercept { chain ->
                        val target = chain.thisObject
                        attempt("upgrade builder scopes field", silent = true) {
                            for (field in clazz.declaredFields) {
                                field.isAccessible = true
                                val value = field.get(target)
                                if (value is MutableCollection<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val coll = value as MutableCollection<Any?>
                                    if (coll.remove(DRIVE_FILE_SCOPE)) {
                                        coll.add(FULL_DRIVE_SCOPE)
                                        Log.d(Consts.TAG, "Replaced drive.file scope with drive in AuthRequestBuilder field: ${field.name}")
                                    }
                                }
                            }
                        }
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookUriBuilder(module: HookContext) {
        attempt("hook Uri.Builder.appendQueryParameter for OAuth scope and prompt") {
            val builderClass = Uri.Builder::class.java
            val m = builderClass.getMethod("appendQueryParameter", String::class.java, String::class.java)
            module.hookTracked(
                m,
                idPrefix = "drive-scope-uri-builder"
            ).intercept { chain ->
                val key = chain.getArg(0) as? String
                val value = chain.getArg(1) as? String
                when {
                    key == "scope" && value != null && value.contains(DRIVE_FILE_SCOPE) -> {
                        val upgraded = value.replace(DRIVE_FILE_SCOPE, FULL_DRIVE_SCOPE)
                        Log.d(Consts.TAG, "Upgraded scope parameter in OAuth Uri.Builder")
                        chain.proceed(arrayOf(key, upgraded))
                    }
                    key == "prompt" && value != "consent" -> chain.proceed(arrayOf(key, "consent"))
                    else -> chain.proceed()
                }
            }
        }
    }

    private fun hookActivityStart(module: HookContext, cl: ClassLoader) {
        attempt("hook Activity.startActivity for custom tabs OAuth intent") {
            val activityClass = cl.loadClass("android.app.Activity")
            for (m in activityClass.declaredMethods) {
                if (m.name == "startActivity" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Intent::class.java) {
                    module.hookTracked(
                        m,
                        idPrefix = "drive-scope-start-activity"
                    ).intercept { chain ->
                        (chain.getArg(0) as? Intent)?.let { upgradeIntentUri(it) }
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookIntentSetData(module: HookContext) {
        attempt("hook Intent.setData / setDataAndNormalize") {
            for (methodName in listOf("setData", "setDataAndNormalize")) {
                val m = Intent::class.java.getDeclaredMethod(methodName, Uri::class.java)
                module.hookTracked(
                    m,
                    idPrefix = "drive-scope-intent-$methodName"
                ).intercept { chain ->
                    val uri = chain.getArg(0) as? Uri
                    if (uri != null && (uri.toString().contains("drive.file") || uri.toString().contains("accounts.google.com"))) {
                        chain.proceed(arrayOf(upgradeUri(uri)))
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun upgradeIntentUri(intent: Intent) {
        intent.data?.let { intent.data = upgradeUri(it) }
    }

    private fun upgradeScopeString(s: String): String = s
        .replace(ENCODED_DRIVE_FILE_SCOPE, ENCODED_FULL_DRIVE_SCOPE)
        .replace(DRIVE_FILE_SCOPE, FULL_DRIVE_SCOPE)
        .replace("drive.file", "drive")

    fun upgradeUri(uri: Uri): Uri {
        var uriStr = upgradeScopeString(uri.toString())
        if (uriStr.contains("accounts.google.com/o/oauth2") && !uriStr.contains("prompt=consent")) {
            uriStr = if (uriStr.contains("prompt=")) uriStr.replace(Regex("prompt=[^&]*"), "prompt=consent")
            else "$uriStr&prompt=consent"
        }
        return uriStr.toUri()
    }

    private fun hookGmsScope(module: HookContext, cl: ClassLoader) {
        attempt("hook GMS Scope class fallback", silent = true) {
            val scopeClass = cl.loadClass("com.google.android.gms.common.api.Scope")
            for (ctor in scopeClass.declaredConstructors) {
                if (ctor.parameterCount == 1 && ctor.parameterTypes[0] == String::class.java) {
                    module.hookTracked(
                        ctor,
                        idPrefix = "drive-scope-gms-scope-ctor"
                    ).intercept { chain ->
                        if (chain.getArg(0) == DRIVE_FILE_SCOPE) chain.proceed(arrayOf(FULL_DRIVE_SCOPE))
                        else chain.proceed()
                    }
                }
            }
        }
    }
}
