package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUtilsTest {

    @Test
    fun formatSha1ProducesExpectedColonSeparatedHex() {
        val input = "SwiftBackupTestCertificate".toByteArray(Charsets.UTF_8)
        val formatted = AppUtils.formatSha1(input)

        // SHA-1 is 20 bytes -> 20 hex pairs separated by colons -> 59 characters
        assertEquals(59, formatted.length)
        assertEquals(19, formatted.count { it == ':' })
        assertTrue(formatted.all { it.isDigit() || it in 'A'..'F' || it == ':' })
    }

    @Test
    fun formatSha1MatchesKnownDigest() {
        val input = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val formatted = AppUtils.formatSha1(input)
        assertEquals("A0:2A:05:B0:25:B9:28:C0:39:CF:1A:E7:E8:EE:04:E7:C1:90:C0:DB", formatted)
    }
}
