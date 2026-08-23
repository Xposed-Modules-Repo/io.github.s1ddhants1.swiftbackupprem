package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.os.Bundle
import android.util.Log
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
        if (!param.isFirstPackage || param.packageName != Consts.packageName) {
            if (apiVersion >= XposedInterface.API_102) {
                attempt("detach non-target package", silent = true) { detach() }
            }
            return
        }

        attempt("load nativelib native library") { System.loadLibrary("nativelib") }

        val cl = param.classLoader
        ExitProtectionHook.applyEarly(this, cl)

        val swiftAppClass = attempt("load SwiftApp class") { cl.loadClass("org.swiftapps.swiftbackup.SwiftApp") } ?: return
        val onCreateMethod = attempt("find SwiftApp.onCreate") { swiftAppClass.getDeclaredMethod("onCreate") } ?: return

        hookTracked(
            onCreateMethod,
            idPrefix = "swift-app-on-create",
            priority = XposedInterface.PRIORITY_HIGHEST,
            deoptimize = true
        ).intercept { chain ->
            val ctx = chain.thisObject as? Context
            if (ctx != null) {
                applyHooks(ctx, cl, param.applicationInfo.sourceDir, chain.thisObject)
            }
            chain.proceed()
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        Log.i(Consts.TAG, "Preparing old module generation for hot reload...")
        
        val state = Bundle().apply {
            putLong("hot_reload_timestamp", System.currentTimeMillis())
        }
        param.setSavedInstanceState(state)

        BackupRebuilderHook.shutdown()
        CloudDiscoveryHook.shutdown()
        ExitProtectionHook.reset()
        hookHandles.clear()

        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        Log.i(Consts.TAG, "Hot reloaded SwiftBackupPrem in process ${param.processName}!")

        for (oldHandle in param.oldHookHandles) {
            try {
                oldHandle.unhook()
            } catch (_: Throwable) {}
        }
        hookHandles.clear()

        attempt("load nativelib native library on hot reload") { System.loadLibrary("nativelib") }

        val app = attempt("get current Application", silent = true) {
            val atClass = Class.forName("android.app.ActivityThread")
            atClass.getDeclaredMethod("currentApplication").invoke(null) as? android.app.Application
        }
        if (app != null && app.packageName == Consts.packageName) {
            val cl = app.classLoader
            ExitProtectionHook.applyEarly(this, cl)
            applyHooks(app, cl, app.applicationInfo.sourceDir, app)
        }
    }

    private fun applyHooks(ctx: Context, cl: ClassLoader, sourceDir: String, swiftAppInstance: Any? = null) {
        val remotePrefs = attempt("get remote preferences") { getRemotePreferences(Consts.PREFS_SETTINGS) }
        val prefs = PreferencesManager(remotePrefs, isDynamic = true)

        var targets = ResolvedTargets()
        attempt("find obfuscated classes with DexKit") {
            targets = TargetClassResolver.resolve(ctx, cl, sourceDir)
        }

        ExitProtectionHook.apply(this, ctx, cl, targets, prefs)
        FirebaseInitHook.apply(this, ctx, cl, targets, prefs)
        PremiumFeatureHook.apply(this, ctx, cl, targets, prefs)
        if (swiftAppInstance != null) {
            PremiumFeatureHook.hookSwiftAppPremium(this, swiftAppInstance, prefs.enablePremium)
        }
        AuthBypassHook.apply(this, ctx, cl, targets, prefs)
        GoogleDriveScopeHook.apply(this, ctx, cl, targets, prefs)
        TelemetrySuppressionHook.apply(this, ctx, cl, targets, prefs)
        BackupRebuilderHook.apply(this, ctx, cl, targets, prefs)
        CloudDiscoveryHook.apply(this, ctx, cl, targets, prefs)

        if (swiftAppInstance != null) {
            PremiumFeatureHook.hookSwiftAppPremium(this, swiftAppInstance, prefs.enablePremium)
        }
        FirebaseInitHook.applyStaticClientId(targets, prefs)
    }
}

