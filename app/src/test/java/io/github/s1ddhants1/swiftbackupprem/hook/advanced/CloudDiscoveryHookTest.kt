package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class CloudDiscoveryHookTest {

    @Test
    fun testDriveBackupFileNameRegex() {
        val regex = Pattern.compile("^(.*?)\\.(app|dat|extdat|splits|extra|med)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")

        val fileName1 = "com.dv.adm.app (CPH2573) (id-20260821-232918-NK)"
        val matcher1 = regex.matcher(fileName1)
        assertTrue(matcher1.matches())
        assertEquals("com.dv.adm", matcher1.group(1))
        assertEquals("app", matcher1.group(2))
        assertEquals("CPH2573", matcher1.group(3))
        assertEquals("20260821-232918-NK", matcher1.group(4))

        val fileName2 = "io.github.samolego.canta.extra (CPH2573) (id-20260822-040206-WD)"
        val matcher2 = regex.matcher(fileName2)
        assertTrue(matcher2.matches())
        assertEquals("io.github.samolego.canta", matcher2.group(1))
        assertEquals("extra", matcher2.group(2))
        assertEquals("CPH2573", matcher2.group(3))
        assertEquals("20260822-040206-WD", matcher2.group(4))

        val fileName3 = "com.meld.app.app (CPH2573) (id-20260824-012623-FQ)"
        val matcher3 = regex.matcher(fileName3)
        assertTrue(matcher3.matches())
        assertEquals("com.meld.app", matcher3.group(1))
        assertEquals("app", matcher3.group(2))
        assertEquals("CPH2573", matcher3.group(3))
        assertEquals("20260824-012623-FQ", matcher3.group(4))
    }

    @Test
    fun testDiscoveredCloudAppTotalSizeCalculation() {
        val app = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.dv.adm",
            sanitizedAppId = "comdvadm",
            backupId = "20260821-232918-NK",
            backupTag = "CPH2573",
            apkSize = 40000000L,
            dataSize = 3000000L,
            extDataSize = 2000000L,
            splitsSize = 3500000L,
            totalSize = 48500000L
        )

        assertEquals("com.dv.adm", app.packageName)
        assertEquals("comdvadm", app.sanitizedAppId)
        assertEquals(48500000L, app.totalSize)
    }

    @Test
    fun testFormatBytesFormatting() {
        assertEquals("0 B", CloudDiscoveryHook.formatBytes(0L))
        assertEquals("0 B", CloudDiscoveryHook.formatBytes(-100L))
        assertEquals("500.00 B", CloudDiscoveryHook.formatBytes(500L))
        assertEquals("1.00 KB", CloudDiscoveryHook.formatBytes(1024L))
        assertEquals("1.50 MB", CloudDiscoveryHook.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", CloudDiscoveryHook.formatBytes((2.0 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testDiscoveredAppMatchingByKeyAndPackage() {
        val app = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "org.telegram.messenger",
            sanitizedAppId = "orgtelegrammessenger",
            backupId = "20260825-100000-AB",
            backupTag = "DEVICE1",
            apkSize = 50000000L,
            totalSize = 50000000L,
            provider = "GoogleDrive"
        )
        CloudDiscoveryHook.discoveredBackups[app.packageName] = app

        assertEquals(app, CloudDiscoveryHook.findMatchingBackup("org.telegram.messenger"))
        assertEquals(app, CloudDiscoveryHook.findMatchingBackup("orgtelegrammessenger"))
        assertNull(CloudDiscoveryHook.findMatchingBackup("com.unknown.app"))

        CloudDiscoveryHook.discoveredBackups.clear()
    }

    @Test
    fun testRtdbAppPreservationAgainstDiscoveredDuplicates() {
        val rtdbPackages = setOf("com.whatsapp", "org.telegram.messenger")

        val discoveredApp1 = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.whatsapp",
            sanitizedAppId = "comwhatsapp",
            backupId = "20260825-110000-CD",
            backupTag = "DEVICE1",
            apkSize = 45000000L,
            totalSize = 45000000L,
            provider = "GoogleDrive"
        )
        val discoveredApp2 = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.orphaned.app",
            sanitizedAppId = "comorphanedapp",
            backupId = "20260825-120000-EF",
            backupTag = "DEVICE1",
            apkSize = 25000000L,
            totalSize = 25000000L,
            provider = "GoogleDrive"
        )

        val discoveredMap = mapOf(
            discoveredApp1.packageName to discoveredApp1,
            discoveredApp2.packageName to discoveredApp2
        )

        val newlyAdded = discoveredMap.values.filter {
            !rtdbPackages.contains(it.packageName) && !rtdbPackages.contains(it.sanitizedAppId)
        }

        assertEquals(1, newlyAdded.size)
        assertEquals("com.orphaned.app", newlyAdded.first().packageName)
    }
}
