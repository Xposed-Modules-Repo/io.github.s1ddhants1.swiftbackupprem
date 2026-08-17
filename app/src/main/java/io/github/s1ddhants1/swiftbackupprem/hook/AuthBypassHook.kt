package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.lang.reflect.Modifier

object AuthBypassHook : HookHandler {

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        targets.authUserClass?.let { hookAuthUserClass(module, it, classLoader, targets) }

        if (prefs.enablePremium) {
            hookFirebaseAuthBypass(module, targets)
        }

        hookKnownAuthClasses(module, classLoader, targets)
    }

    private fun hookFirebaseAuthBypass(module: XposedModule, targets: ResolvedTargets) {
        targets.clientIdClass?.let { cIdClass ->
            for (m in cIdClass.declaredMethods) {
                if (m.name == "e" && m.parameterCount == 2) {
                    attempt("hook FirebaseAuth bypass method ${m.name}") {
                        module.hook(m).intercept { chain ->
                            val task = chain.getArg(1)
                            val isSuccessful = attempt("check task isSuccessful", silent = true) {
                                if (task != null) {
                                    task.javaClass.getMethod("isSuccessful").invoke(task) as? Boolean ?: true
                                } else true
                            } ?: true

                            if (!isSuccessful && task != null) {
                                val exceptionMsg = attempt("get task exception message", silent = true) {
                                    (task.javaClass.getMethod("getException").invoke(task) as? Throwable)?.message
                                } ?: "unknown"
                                Log.w("SBP", "FirebaseAuth failed ($exceptionMsg), bypassing account block and forcing sign-in success")
                                val callback = chain.getArg(0)
                                if (callback != null) {
                                    attempt("set auth success callback") {
                                        for (nested in cIdClass.declaredClasses) {
                                            for (inner in nested.declaredClasses) {
                                                if (inner.simpleName == "b") {
                                                    val successInstance = inner.getField("a").get(null)
                                                    val invokeMethod = callback.javaClass.getMethod("invoke", Any::class.java)
                                                    invokeMethod.invoke(callback, successInstance)
                                                    return@intercept null
                                                }
                                            }
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
    }

    private fun hookKnownAuthClasses(module: XposedModule, cl: ClassLoader, targets: ResolvedTargets) {
        // Hook NoGmsSignInActivity to dismiss blocked/failed dialogs and proceed with RESULT_OK
        attempt("hook NoGmsSignInActivity", silent = true) {
            val noGmsClass = cl.loadClass("org.swiftapps.swiftbackup.cloud.connect.NoGmsSignInActivity")
            for (m in noGmsClass.declaredMethods) {
                if (m.parameterCount == 2 && m.parameterTypes[0] == noGmsClass && m.parameterTypes[1] == String::class.java) {
                    module.hook(m).intercept { chain ->
                        val activity = chain.getArg(0) as? android.app.Activity
                        activity?.let {
                            it.setResult(android.app.Activity.RESULT_OK)
                            it.finish()
                        }
                        null
                    }
                }
            }
        }

        // Guarantee non-null MFirebaseUser across all known class names
        val knownAuthClassNames = listOf(
            "defpackage.d45",
            "org.swiftapps.swiftbackup.common.a3",
            "org.swiftapps.swiftbackup.anonymous.a"
        )
        for (name in knownAuthClassNames) {
            attempt("load and hook auth class $name", silent = true) {
                val authClass = cl.loadClass(name)
                hookAuthUserClass(module, authClass, cl, targets)
            }
        }

        // Neutralize Const.Z0 (the block/signOut/exit handler)
        attempt("neutralize Const.Z0", silent = true) {
            val constClass = cl.loadClass("org.swiftapps.swiftbackup.common.Const")
            for (m in constClass.declaredMethods) {
                if (m.name == "Z0" || (m.parameterCount == 1 && m.parameterTypes[0] == String::class.java && Modifier.isSynchronized(m.modifiers))) {
                    attempt("hook Const.Z0 method ${m.name}") {
                        module.hook(m).intercept { null }
                    }
                }
            }
        }
    }

    private fun hookAuthUserClass(module: XposedModule, targetClass: Class<*>, cl: ClassLoader, targets: ResolvedTargets) {
        Log.d("SBP", "Hooking AuthUser class: ${targetClass.name}")
        for (m in targetClass.declaredMethods) {
            if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser")) {
                attempt("hook AuthUser method ${m.name}") {
                    module.hook(m).intercept { chain ->
                        val res = chain.proceed()
                        res ?: getFallbackUser(cl, targets)
                    }
                }
            }
        }
    }

    private fun getFallbackUser(cl: ClassLoader, targets: ResolvedTargets): Any? {
        // 1. Try anonUserClass or known anonymous user generator classes
        val anonClasses = listOfNotNull(
            targets.anonUserClass,
            attempt("load defpackage.b45", silent = true) { cl.loadClass("defpackage.b45") },
            attempt("load org.swiftapps.swiftbackup.anonymous.a", silent = true) { cl.loadClass("org.swiftapps.swiftbackup.anonymous.a") }
        )
        for (c in anonClasses) {
            attempt("get fallback user from ${c.name}", silent = true) {
                for (m in c.declaredMethods) {
                    if (m.parameterCount == 0 && m.returnType.name.contains("MFirebaseUser") && Modifier.isStatic(m.modifiers)) {
                        m.isAccessible = true
                        val res = m.invoke(null)
                        if (res != null) return res
                    }
                }
            }
        }

        // 2. Direct MFirebaseUser instantiation
        return attempt("create direct fallback MFirebaseUser") {
            val mUserClass = cl.loadClass("org.swiftapps.swiftbackup.anonymous.MFirebaseUser")
            for (ctor in mUserClass.declaredConstructors) {
                if (ctor.parameterTypes.size == 7) {
                    ctor.isAccessible = true
                    return@attempt ctor.newInstance(
                        "anonymous_user",
                        "anonymous@swiftbackup.app",
                        true,
                        "Anonymous user",
                        null,
                        emptyList<Any>(),
                        "anonymous"
                    )
                }
            }
            null
        }
    }
}
