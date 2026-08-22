package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

object TelemetrySuppressionHook : HookHandler {

    private val SCHEDULER_METHODS = setOf("upload", "schedule", "logAndUpdateState")
    private val CRASH_METHODS = setOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler")
    private val SESSION_METHODS = setOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent")

    val nullTargets: Map<String, Set<String>> = mapOf(
        "com.google.android.datatransport.runtime.TransportRuntime" to setOf("send"),
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.Uploader" to SCHEDULER_METHODS,
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoScheduler" to SCHEDULER_METHODS,
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerScheduler" to SCHEDULER_METHODS,
        "com.google.firebase.crashlytics.FirebaseCrashlytics" to setOf("recordException", "log", "setCustomKey", "setUserId", "sendUnsentReports", "deleteUnsentReports"),
        "com.google.firebase.crashlytics.internal.common.CrashlyticsCore" to CRASH_METHODS,
        "com.google.firebase.crashlytics.internal.common.CrashlyticsController" to CRASH_METHODS,
        "com.google.firebase.crashlytics.ndk.FirebaseCrashlyticsNdk" to CRASH_METHODS,
        "com.google.firebase.analytics.FirebaseAnalytics" to setOf("logEvent", "setUserProperty", "setUserId", "setCurrentScreen", "resetAnalyticsData"),
        "com.google.firebase.sessions.FirebaseSessions" to SESSION_METHODS,
        "com.google.firebase.sessions.SessionFirelogPublisherImpl" to SESSION_METHODS,
        "com.google.firebase.sessions.SessionFirelogPublisher" to SESSION_METHODS,
        "com.google.firebase.installations.FirebaseInstallations" to setOf("delete"),
        "com.google.firebase.installations.remote.FirebaseInstallationServiceClient" to setOf("deleteFirebaseInstallation"),
    )

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (!prefs.disableTelemetry) return
        Log.d(Consts.TAG, "Applying telemetry, analytics, and tracking suppression")

        for ((className, methods) in nullTargets) {
            hookMethodsNull(module, classLoader, className) { it in methods }
        }

        for (name in listOf(
            "com.google.android.gms.measurement.AppMeasurement",
            "com.google.android.gms.measurement.internal.zzhd",
            "com.google.android.gms.measurement.internal.zzha"
        )) {
            hookMethodsNull(module, classLoader, name) { it.startsWith("logEvent") || it.startsWith("setUserProperty") }
        }

        for (className in listOf("com.google.firebase.crashlytics.FirebaseCrashlytics", "com.google.firebase.analytics.FirebaseAnalytics")) {
            attempt("hook collection enable in $className", silent = true) {
                val clazz = classLoader.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name.startsWith("set") && m.name.endsWith("CollectionEnabled")) {
                        module.hookTracked(m).intercept { chain ->
                            if (chain.args.isNotEmpty()) chain.proceed(arrayOf(java.lang.Boolean.FALSE)) else chain.proceed()
                        }
                    }
                }
            }
        }

        attempt("hook TransportRuntime.schedule", silent = true) {
            val runtimeClass = classLoader.loadClass("com.google.android.datatransport.runtime.TransportRuntime")
            for (m in runtimeClass.declaredMethods) {
                if (m.name == "schedule") {
                    module.hookTracked(m).intercept { chain ->
                        chain.args.lastOrNull()?.let { callback ->
                            attempt("invoke schedule callback", silent = true) {
                                callback.javaClass.getMethod("onSchedule", Exception::class.java).invoke(callback, null)
                            }
                        }
                        null
                    }
                }
            }
        }

        attempt("hook CctTransportBackend.send", silent = true) {
            val cctClass = classLoader.loadClass("com.google.android.datatransport.cct.CctTransportBackend")
            val backendResponseClass = classLoader.loadClass("com.google.android.datatransport.runtime.backends.BackendResponse")
            val dummyResponse = backendResponseClass.getMethod("ok", Long::class.javaPrimitiveType).invoke(null, 1000L)
            for (m in cctClass.declaredMethods) {
                if (m.name == "send" || (m.parameterCount == 1 && m.returnType.simpleName == "BackendResponse")) {
                    module.hookTracked(m).intercept { dummyResponse }
                }
            }
        }

        attempt("hook checkForUnsentReports", silent = true) {
            val crashlyticsClass = classLoader.loadClass("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val tasksClass = classLoader.loadClass("com.google.android.gms.tasks.Tasks")
            val falseTask = tasksClass.getMethod("forResult", Any::class.java).invoke(null, java.lang.Boolean.FALSE)
            for (m in crashlyticsClass.declaredMethods) {
                if (m.name == "checkForUnsentReports") {
                    module.hookTracked(m).intercept { falseTask }
                }
            }
        }
    }

    private fun hookMethodsNull(
        module: XposedModule,
        classLoader: ClassLoader,
        className: String,
        predicate: (String) -> Boolean
    ) {
        attempt("hook telemetry in $className", silent = true) {
            val clazz = classLoader.loadClass(className)
            for (m in clazz.declaredMethods) {
                if (predicate(m.name)) {
                    attempt("hook method ${m.name} in $className", silent = true) {
                        module.hookTracked(m).intercept { null }
                    }
                }
            }
        }
    }
}
