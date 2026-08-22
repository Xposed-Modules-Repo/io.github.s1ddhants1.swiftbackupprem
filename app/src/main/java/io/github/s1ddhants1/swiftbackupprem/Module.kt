package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.s1ddhants1.swiftbackupprem.hook.AuthBypassHook
import io.github.s1ddhants1.swiftbackupprem.hook.ExitProtectionHook
import io.github.s1ddhants1.swiftbackupprem.hook.FirebaseInitHook
import io.github.s1ddhants1.swiftbackupprem.hook.PremiumFeatureHook
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.hook.TargetClassResolver
import io.github.s1ddhants1.swiftbackupprem.hook.TelemetrySuppressionHook
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.BackupRebuilderHook
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.CloudDiscoveryHook
import io.github.s1ddhants1.swiftbackupprem.hook.advanced.GoogleDriveScopeHook
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

@Keep
class Module : XposedModule() {

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != Consts.packageName) return

        attempt("load nativelib native library") {
            System.loadLibrary("nativelib")
        }

        val remotePrefs: SharedPreferences? = attempt("get remote preferences") {
            getRemotePreferences("settings")
        }

        val prefs = PreferencesManager(remotePrefs, isDynamic = true)
        val cl = param.classLoader

        // Neutralize forced JVM exits
        ExitProtectionHook.apply(this)

        val swiftAppClass = attempt("load SwiftApp class") {
            cl.loadClass("org.swiftapps.swiftbackup.SwiftApp")
        } ?: return

        val onCreateMethod = attempt("find SwiftApp.onCreate") {
            swiftAppClass.getDeclaredMethod("onCreate")
        } ?: return

        hook(onCreateMethod).intercept { chain ->
            val ctx = chain.thisObject as? Context
            var targets = ResolvedTargets()

            if (ctx != null) {
                attempt("find obfuscated classes with DexKit") {
                    val appSourceDir = param.applicationInfo.sourceDir
                    targets = TargetClassResolver.resolve(ctx, cl, appSourceDir)
                }

                // 1. Initialize Firebase backend (custom or default)
                FirebaseInitHook.apply(this, ctx, cl, targets, prefs)

                // 2. Apply Premium unlocks
                PremiumFeatureHook.apply(this, ctx, cl, targets, prefs)
                PremiumFeatureHook.hookSwiftAppPremium(this, chain.thisObject, prefs.enablePremium)

                // 3. Apply Authentication bypass
                AuthBypassHook.apply(this, ctx, cl, targets, prefs)

                // 4. Upgrade Google Drive OAuth scopes for full cloud backup access
                GoogleDriveScopeHook.apply(this, ctx, cl, targets, prefs)

                // 5. Suppress telemetry and tracking
                TelemetrySuppressionHook.apply(this, ctx, cl, targets, prefs)

                // 6. Automated 1-Click Backup Rebuilder & Restorer
                BackupRebuilderHook.apply(this, ctx, cl, targets, prefs)

                // 7. Google Drive Cloud Discovery & Metadata Indexer
                CloudDiscoveryHook.apply(this, ctx, cl, targets, prefs)
            }

            // Proceed with original SwiftApp.onCreate()
            val result = chain.proceed()

            // Post-onCreate adjustments
            PremiumFeatureHook.hookSwiftAppPremium(this, chain.thisObject, prefs.enablePremium)
            FirebaseInitHook.applyStaticClientId(targets, prefs)

            result
        }
    }
}
