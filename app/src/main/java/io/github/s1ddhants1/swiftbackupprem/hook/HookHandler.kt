package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

interface HookHandler {
    fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    )
}
