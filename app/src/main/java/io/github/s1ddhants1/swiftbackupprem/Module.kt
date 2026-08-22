package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.s1ddhants1.swiftbackupprem.hook.*
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.BackupRebuilderHook
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.CloudDiscoveryHook
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.GoogleDriveScopeHook
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.util.concurrent.ConcurrentHashMap

@Keep
class Module : XposedModule() {
    private val hookHandles = ConcurrentHashMap<String, XposedInterface.HookHandle>()

    fun rememberHook(id: String?, handle: XposedInterface.HookHandle) {
        if (id != null) hookHandles[id] = handle
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (!param.isFirstPackage || param.packageName != Consts.packageName) return

        attempt("load nativelib native library") { System.loadLibrary("nativelib") }

        val remotePrefs = attempt("get remote preferences") { getRemotePreferences(Consts.PREFS_SETTINGS) }
        val prefs = PreferencesManager(remotePrefs, isDynamic = true)
        val cl = param.classLoader

        ExitProtectionHook.apply(this)

        val swiftAppClass = attempt("load SwiftApp class") { cl.loadClass("org.swiftapps.swiftbackup.SwiftApp") } ?: return
        val onCreateMethod = attempt("find SwiftApp.onCreate") { swiftAppClass.getDeclaredMethod("onCreate") } ?: return

        hookTracked(onCreateMethod, "swift-app-on-create").intercept { chain ->
            val ctx = chain.thisObject as? Context
            var targets = ResolvedTargets()

            if (ctx != null) {
                attempt("find obfuscated classes with DexKit") {
                    targets = TargetClassResolver.resolve(ctx, cl, param.applicationInfo.sourceDir)
                }

                FirebaseInitHook.apply(this, ctx, cl, targets, prefs)
                PremiumFeatureHook.apply(this, ctx, cl, targets, prefs)
                PremiumFeatureHook.hookSwiftAppPremium(this, chain.thisObject, prefs.enablePremium)
                AuthBypassHook.apply(this, ctx, cl, targets, prefs)
                GoogleDriveScopeHook.apply(this, ctx, cl, targets, prefs)
                TelemetrySuppressionHook.apply(this, ctx, cl, targets, prefs)
                BackupRebuilderHook.apply(this, ctx, cl, targets, prefs)
                CloudDiscoveryHook.apply(this, ctx, cl, targets, prefs)
            }

            val result = chain.proceed()
            PremiumFeatureHook.hookSwiftAppPremium(this, chain.thisObject, prefs.enablePremium)
            FirebaseInitHook.applyStaticClientId(targets, prefs)
            result
        }
    }
}
