package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

object TelemetrySuppressionHook : HookHandler {

    val nullTargets: Map<String, Set<String>> = mapOf(
        // DataTransport
        "com.google.android.datatransport.runtime.TransportRuntime" to setOf("send"),
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.Uploader" to setOf("upload", "schedule", "logAndUpdateState"),
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoScheduler" to setOf("upload", "schedule", "logAndUpdateState"),
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerScheduler" to setOf("upload", "schedule", "logAndUpdateState"),
        // Crashlytics
        "com.google.firebase.crashlytics.FirebaseCrashlytics" to setOf("recordException", "log", "setCustomKey", "setUserId", "sendUnsentReports", "deleteUnsentReports"),
        "com.google.firebase.crashlytics.internal.common.CrashlyticsCore" to setOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler"),
        "com.google.firebase.crashlytics.internal.common.CrashlyticsController" to setOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler"),
        "com.google.firebase.crashlytics.ndk.FirebaseCrashlyticsNdk" to setOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler"),
        // Analytics
        "com.google.firebase.analytics.FirebaseAnalytics" to setOf("logEvent", "setUserProperty", "setUserId", "setCurrentScreen", "resetAnalyticsData"),
        // Sessions
        "com.google.firebase.sessions.FirebaseSessions" to setOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent"),
        "com.google.firebase.sessions.SessionFirelogPublisherImpl" to setOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent"),
        "com.google.firebase.sessions.SessionFirelogPublisher" to setOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent"),
        // Installations
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
        Log.d("SBP", "Applying telemetry, analytics, and tracking suppression")

        // Bulk null-intercept: hook methods and return null
        for ((className, methods) in nullTargets) {
            attempt("hook telemetry in $className", silent = true) {
                val clazz = classLoader.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name in methods) {
                        attempt("hook method ${m.name} in $className", silent = true) {
                            module.hook(m).intercept { null }
                        }
                    }
                }
            }
        }

        // Prefix-match for AppMeasurement variants
        for (name in listOf(
            "com.google.android.gms.measurement.AppMeasurement",
            "com.google.android.gms.measurement.internal.zzhd",
            "com.google.android.gms.measurement.internal.zzha"
        )) {
            attempt("hook AppMeasurement class $name", silent = true) {
                val clazz = classLoader.loadClass(name)
                for (m in clazz.declaredMethods) {
                    if (m.name.startsWith("logEvent") || m.name.startsWith("setUserProperty")) {
                        attempt("hook method ${m.name} in $name", silent = true) {
                            module.hook(m).intercept { null }
                        }
                    }
                }
            }
        }

        // Force-disable collection: override arg to FALSE
        for ((className, methodName) in listOf(
            "com.google.firebase.crashlytics.FirebaseCrashlytics" to "setCrashlyticsCollectionEnabled",
            "com.google.firebase.analytics.FirebaseAnalytics" to "setAnalyticsCollectionEnabled"
        )) {
            attempt("hook collection enable method in $className", silent = true) {
                val clazz = classLoader.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name == methodName) {
                        attempt("hook $methodName in $className", silent = true) {
                            module.hook(m).intercept { chain ->
                                if (chain.args.isNotEmpty()) chain.proceed(arrayOf(java.lang.Boolean.FALSE))
                                else chain.proceed()
                            }
                        }
                    }
                }
            }
        }

        // TransportRuntime.schedule: invoke onSchedule callback to prevent retries
        attempt("hook TransportRuntime.schedule", silent = true) {
            val runtimeClass = classLoader.loadClass("com.google.android.datatransport.runtime.TransportRuntime")
            for (m in runtimeClass.declaredMethods) {
                if (m.name == "schedule") {
                    attempt("hook schedule in TransportRuntime", silent = true) {
                        module.hook(m).intercept { chain ->
                            chain.args.lastOrNull()?.let { callback ->
                                attempt("invoke schedule callback", silent = true) {
                                    callback.javaClass.getMethod("onSchedule", Exception::class.java)
                                        .invoke(callback, null)
                                }
                            }
                            null
                        }
                    }
                }
            }
        }

        // CctTransportBackend.send: return BackendResponse.ok(1000L) instead of making network call
        attempt("hook CctTransportBackend.send", silent = true) {
            val cctClass = classLoader.loadClass("com.google.android.datatransport.cct.CctTransportBackend")
            val backendResponseClass = classLoader.loadClass("com.google.android.datatransport.runtime.backends.BackendResponse")
            val dummyResponse = backendResponseClass.getMethod("ok", Long::class.javaPrimitiveType).invoke(null, 1000L)
            for (m in cctClass.declaredMethods) {
                if (m.name == "send" || (m.parameterCount == 1 && m.returnType.simpleName == "BackendResponse")) {
                    attempt("hook send in CctTransportBackend", silent = true) {
                        module.hook(m).intercept { dummyResponse }
                    }
                }
            }
        }

        // checkForUnsentReports: return Tasks.forResult(false) to prevent report uploads
        attempt("hook checkForUnsentReports", silent = true) {
            val crashlyticsClass = classLoader.loadClass("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val tasksClass = classLoader.loadClass("com.google.android.gms.tasks.Tasks")
            val falseTask = tasksClass.getMethod("forResult", Any::class.java).invoke(null, java.lang.Boolean.FALSE)
            for (m in crashlyticsClass.declaredMethods) {
                if (m.name == "checkForUnsentReports") {
                    attempt("hook checkForUnsentReports in FirebaseCrashlytics", silent = true) {
                        module.hook(m).intercept { falseTask }
                    }
                }
            }
        }
    }
}
