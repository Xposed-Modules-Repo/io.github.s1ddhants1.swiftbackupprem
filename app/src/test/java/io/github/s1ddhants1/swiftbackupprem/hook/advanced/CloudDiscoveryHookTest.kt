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

    @Test
    fun testMetadataMapStructure() {
        val app = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.dv.adm",
            sanitizedAppId = "comdvadm",
            backupId = "20260821-232918-NK",
            backupTag = "CPH2573",
            apkLink = "https://drive.google.com/file/d/apk1",
            apkSize = 40000000L,
            dataLink = "https://drive.google.com/file/d/dat1",
            dataSize = 3000000L,
            totalSize = 43000000L,
            dateBackup = 1724282958000L
        )

        val rootMap = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(app)
        assertTrue(rootMap.containsKey("20260821-232918-NK"))

        @Suppress("UNCHECKED_CAST")
        val meta = rootMap["20260821-232918-NK"] as Map<String, Any>
        assertEquals("comdvadm", meta["appId"])
        assertEquals("com.dv.adm", meta["packageName"])
        assertEquals("CPH2573", meta["backupTag"])
        assertEquals(1724282958000L, meta["dateBackup"])
        assertEquals(580L, meta["minSBVersionCodeRequired"])
        assertEquals(1, meta["keyVersion"])
    }

    @Test
    fun testMetadataMapSliceGeneration() {
        val app = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "demigos.com.mobilism",
            sanitizedAppId = "demigoscommobilism",
            backupId = "20260824-000000-XX",
            backupTag = "DEVICE1",
            apkLink = "https://drive.google.com/apk",
            apkSize = 1000L,
            dataLink = "https://drive.google.com/dat",
            dataSize = 2000L,
            extDataLink = "https://drive.google.com/extdat",
            extDataSize = 3000L,
            splitsLink = "https://drive.google.com/splits",
            splitsSize = 4000L,
            extraLink = "https://drive.google.com/extra",
            extraSize = 5000L,
            totalSize = 15000L,
            ssaid = "dummy_ssaid",
            permissionStatesCsv = "perm1,perm2",
            notificationPolicyXml = "<policy/>"
        )

        val rootMap = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(app)
        @Suppress("UNCHECKED_CAST")
        val meta = rootMap["20260824-000000-XX"] as Map<String, Any>

        assertEquals("https://drive.google.com/apk", meta["apkLink"])
        assertEquals(1000L, meta["apkSize"])
        assertEquals("https://drive.google.com/dat", meta["dataLink"])
        assertEquals(2000L, meta["dataSize"])
        assertEquals("https://drive.google.com/extdat", meta["extDataLink"])
        assertEquals(3000L, meta["extDataSize"])
        assertEquals("https://drive.google.com/splits", meta["splitsLink"])
        assertEquals(4000L, meta["splitsSize"])
        assertEquals("https://drive.google.com/extra", meta["specialDataLink"])
        assertEquals(5000L, meta["specialDataSize"])
        assertEquals("dummy_ssaid", meta["ssaid"])
        assertEquals("perm1,perm2", meta["permissionStatesCsv"])
        assertEquals("<policy/>", meta["notificationPolicyXml"])
    }

    @Test
    fun testMetadataMapEncryptionFields() {
        val app = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.meld.app",
            sanitizedAppId = "commeldapp",
            backupId = "20260824-012623-FQ",
            backupTag = "DEVICE1",
            dataLink = "https://drive.google.com/dat",
            dataSize = 5000L,
            extDataLink = "https://drive.google.com/extdat",
            extDataSize = 6000L,
            totalSize = 11000L
        )

        val rootMap = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(app)
        @Suppress("UNCHECKED_CAST")
        val meta = rootMap["20260824-012623-FQ"] as Map<String, Any>

        assertEquals(true, meta["isDataEncrypted"])
        assertEquals("StandardEncryption", meta["dataEncryptionMethod"])
        assertEquals(true, meta["isExtDataEncrypted"])
        assertEquals("StandardEncryption", meta["extDataEncryptionMethod"])
    }
}
