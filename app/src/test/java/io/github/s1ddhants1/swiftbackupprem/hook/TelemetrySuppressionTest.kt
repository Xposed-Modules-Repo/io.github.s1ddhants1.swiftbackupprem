package io.github.s1ddhants1.swiftbackupprem.hook

import org.junit.Assert.*
import org.junit.Test

class TelemetrySuppressionTest {

    @Test
    fun nullTargetsContainsExpectedCoreFirebasePackages() {
        val targets = TelemetrySuppressionHook.nullTargets

        assertTrue(targets.containsKey("com.google.android.datatransport.runtime.TransportRuntime"))
        assertTrue(targets.containsKey("com.google.firebase.crashlytics.FirebaseCrashlytics"))
        assertTrue(targets.containsKey("com.google.firebase.analytics.FirebaseAnalytics"))
        assertTrue(targets.containsKey("com.google.firebase.sessions.FirebaseSessions"))
        assertTrue(targets.containsKey("com.google.firebase.installations.FirebaseInstallations"))
    }

    @Test
    fun nullTargetsEntriesHaveNonEmptyMethodSets() {
        for ((className, methods) in TelemetrySuppressionHook.nullTargets) {
            assertTrue("Class name is blank", className.isNotBlank())
            assertTrue("Method set for $className is empty", methods.isNotEmpty())
            for (method in methods) {
                assertTrue("Method name is blank in $className", method.isNotBlank())
            }
        }
    }
}
