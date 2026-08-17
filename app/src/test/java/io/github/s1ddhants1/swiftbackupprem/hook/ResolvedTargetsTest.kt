package io.github.s1ddhants1.swiftbackupprem.hook

import org.junit.Assert.*
import org.junit.Test

class ResolvedTargetsTest {

    @Test
    fun isFullyResolvedReturnsTrueWhenAllCoreClassesPresent() {
        val targets = ResolvedTargets(
            clientIdClass = String::class.java,
            vClass = Int::class.java,
            homeViewModelClass = Boolean::class.java,
            authUserClass = Long::class.java
        )

        assertTrue(targets.isFullyResolved)
    }

    @Test
    fun isFullyResolvedReturnsFalseWhenAnyCoreClassMissing() {
        val targets1 = ResolvedTargets(
            clientIdClass = null,
            vClass = Int::class.java,
            homeViewModelClass = Boolean::class.java,
            authUserClass = Long::class.java
        )
        assertFalse(targets1.isFullyResolved)

        val targets2 = ResolvedTargets(
            clientIdClass = String::class.java,
            vClass = null,
            homeViewModelClass = Boolean::class.java,
            authUserClass = Long::class.java
        )
        assertFalse(targets2.isFullyResolved)

        val targets3 = ResolvedTargets(
            clientIdClass = String::class.java,
            vClass = Int::class.java,
            homeViewModelClass = null,
            authUserClass = Long::class.java
        )
        assertFalse(targets3.isFullyResolved)

        val targets4 = ResolvedTargets(
            clientIdClass = String::class.java,
            vClass = Int::class.java,
            homeViewModelClass = Boolean::class.java,
            authUserClass = null
        )
        assertFalse(targets4.isFullyResolved)
    }
}
