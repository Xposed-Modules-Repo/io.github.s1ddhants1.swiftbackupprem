package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTagHelperTest {

    @Test
    fun testSanitizeRemovesFirebaseInvalidChars() {
        val input = "tag.with#invalid\$chars[and]slashes/here"
        val expected = "tagwithinvalidcharsandslasheshere"
        assertEquals(expected, BackupTagHelper.sanitize(input))
    }

    @Test
    fun testSanitizePreservesValidChars() {
        val input = "CPH2573_OnePlus-12"
        assertEquals("CPH2573_OnePlus-12", BackupTagHelper.sanitize(input))
    }

    @Test
    fun testSanitizeNullOrBlankFallsBackToDefault() {
        val defaultTag = BackupTagHelper.getDefaultTag()
        assertEquals(defaultTag, BackupTagHelper.sanitize(null))
        assertEquals(defaultTag, BackupTagHelper.sanitize(""))
        assertEquals(defaultTag, BackupTagHelper.sanitize("   "))
    }
}
