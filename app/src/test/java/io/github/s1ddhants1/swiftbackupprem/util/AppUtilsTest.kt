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

    @Test
    fun sanitizeUrlMasksUserInfoCredentials() {
        val url = "https://admin:superSecretPassword123@webdav.example.com/remote.php/webdav"
        val sanitized = AppUtils.sanitizeUrl(url)
        assertEquals("https://***:***@webdav.example.com/remote.php/webdav", sanitized)
    }

    @Test
    fun sanitizeUrlMasksAuthAndTokenQueryParams() {
        val pcloudUrl = "https://api.pcloud.com/listfolder?folderid=0&auth=my_pcloud_secret_token_12345&recursive=1"
        val sanitizedPcloud = AppUtils.sanitizeUrl(pcloudUrl)
        assertEquals("https://api.pcloud.com/listfolder?folderid=0&auth=***&recursive=1", sanitizedPcloud)

        val oauthUrl = "https://example.com/api?access_token=secret123&key=apiKey999&user=john"
        val sanitizedOauth = AppUtils.sanitizeUrl(oauthUrl)
        assertEquals("https://example.com/api?access_token=***&key=***&user=john", sanitizedOauth)
    }

    @Test
    fun sanitizeUrlPreservesCleanUrls() {
        val cleanUrl = "https://graph.microsoft.com/v1.0/me/drive/items/root/children"
        val sanitized = AppUtils.sanitizeUrl(cleanUrl)
        assertEquals(cleanUrl, sanitized)
    }
}

