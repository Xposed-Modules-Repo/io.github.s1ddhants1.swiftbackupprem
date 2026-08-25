package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUtilsTest {

    @Test
    fun randomFingerprintProducesExpectedColonSeparatedHex() {
        val fingerprint = AppUtils.randomFingerprint()

        assertEquals(59, fingerprint.length)
        assertEquals(19, fingerprint.count { it == ':' })
        assertTrue(fingerprint.all { it.isDigit() || it in 'A'..'F' || it == ':' })

        val segments = fingerprint.split(":")
        assertEquals(20, segments.size)
        assertTrue(segments.all { it.length == 2 })
    }

    @Test
    fun randomFingerprintGeneratesDistinctValues() {
        val first = AppUtils.randomFingerprint()
        val second = AppUtils.randomFingerprint()
        assertNotEquals(first, second)
    }
}

