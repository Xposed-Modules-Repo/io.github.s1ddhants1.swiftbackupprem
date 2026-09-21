package io.github.s1ddhants1.swiftbackupprem.hook

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
        CloudDiscoveryHook.addDiscoveredBackup(app)

        assertEquals(app, CloudDiscoveryHook.findMatchingBackup("org.telegram.messenger"))
        assertEquals(app, CloudDiscoveryHook.findMatchingBackup("orgtelegrammessenger"))
        assertNull(CloudDiscoveryHook.findMatchingBackup("com.unknown.app"))

        CloudDiscoveryHook.discoveredBackups.clear()
    }

    @Test
    fun testMultiBackupGroupingAndTags() {
        val app1 = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.whatsapp",
            sanitizedAppId = "comwhatsapp",
            backupId = "20260825-100000-W1",
            backupTag = "PHONE_A",
            apkSize = 40000000L,
            totalSize = 40000000L
        )
        val app2 = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.whatsapp",
            sanitizedAppId = "comwhatsapp",
            backupId = "20260825-200000-W2",
            backupTag = "PHONE_B",
            apkSize = 42000000L,
            totalSize = 42000000L
        )

        CloudDiscoveryHook.addDiscoveredBackup(app1)
        CloudDiscoveryHook.addDiscoveredBackup(app2)

        val backups = CloudDiscoveryHook.findMatchingBackups("com.whatsapp")
        assertEquals(2, backups.size)
        assertEquals("PHONE_A", backups[0].backupTag)
        assertEquals("PHONE_B", backups[1].backupTag)

        val multiMap = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(backups)
        assertEquals(2, multiMap.size)
        assertTrue(multiMap.containsKey("20260825-100000-W1"))
        assertTrue(multiMap.containsKey("20260825-200000-W2"))

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

    @Test
    fun testAppSliceExtensionMatching() {
        val app = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.caydey.ffshare",
            sanitizedAppId = "comcaydeyffshare",
            backupId = "20260825-233823-DB",
            backupTag = "CPH2573",
            appName = "FFShare",
            apkLink = "1D7PApDB2KgFggfYSjrRs-Nb2cBcCJ84j",
            apkSize = 76712163L,
            apkBackupDate = 1787683259040L,
            dataLink = "14fQSZm6fhEClnhK_Zox5Za7O7DGm4dKU",
            dataSize = 31431L,
            dataBackupDate = 1787683247390L,
            totalSize = 76743594L,
            versionCode = 23L,
            versionName = "2.0.0"
        )

        assertEquals("1D7PApDB2KgFggfYSjrRs-Nb2cBcCJ84j", app.apkLink)
        assertEquals(76712163L, app.apkSize)
        assertEquals(76743594L, app.totalSize)
        assertEquals("FFShare", app.appName)
        assertEquals(23L, app.versionCode)
        assertEquals("2.0.0", app.versionName)

        val rootMap = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(app)
        @Suppress("UNCHECKED_CAST")
        val meta = rootMap["20260825-233823-DB"] as Map<String, Any>
        assertEquals("1D7PApDB2KgFggfYSjrRs-Nb2cBcCJ84j", meta["apkLink"])
        assertEquals(76712163L, meta["apkSize"])
        assertEquals(1787683259040L, meta["apkBackupDate"])
        assertEquals("14fQSZm6fhEClnhK_Zox5Za7O7DGm4dKU", meta["dataLink"])
        assertEquals(31431L, meta["dataSize"])
        assertEquals(1787683247390L, meta["dataBackupDate"])
        assertEquals(true, meta["dataEncrypted"])
        assertEquals(true, meta["isDataEncrypted"])
        assertEquals("FFShare", meta["name"])
        assertEquals(23L, meta["versionCode"])
        assertEquals("2.0.0", meta["versionName"])
    }

    @Test
    fun testDirectIndexRecordParsingWithoutReconstruction() {
        val rawJson = """
            {
              "packageName": "com.dv.adm",
              "appId": "comdvadm",
              "backupId": "20260908-195034",
              "backupTag": "CPH2573",
              "name": "Advanced Download Manager",
              "versionCode": 140400,
              "versionName": "14.0.40",
              "dateBackup": 1788877834000,
              "specialDataLink": "drive-file-id-extra",
              "specialDataSize": 2048,
              "apkSize": 15000000,
              "dataSize": 50000
            }
        """.trimIndent()

        val parsed = CloudDiscoveryHook.DiscoveredCloudApp.fromJson("com.dv.adm", org.json.JSONObject(rawJson))

        assertEquals("com.dv.adm", parsed.packageName)
        assertEquals("comdvadm", parsed.sanitizedAppId)
        assertEquals("20260908-195034", parsed.backupId)
        assertEquals("CPH2573", parsed.backupTag)
        assertEquals("Advanced Download Manager", parsed.appName)
        assertEquals(140400L, parsed.versionCode)
        assertEquals("14.0.40", parsed.versionName)
        assertEquals("drive-file-id-extra", parsed.extraLink)
        assertEquals(2048L, parsed.extraSize)
        assertEquals(15000000L, parsed.apkSize)
        assertEquals(50000L, parsed.dataSize)
    }

    @Test
    fun testDexScannerResolvesNodeUtilities() {
        val apkPath = System.getenv("SWIFT_BACKUP_APK_PATH")
            ?: System.getProperty("swift.backup.apk")
            ?: "scratch/base.apk"
        val baseApk = java.io.File(apkPath)
        if (baseApk.exists()) {
            val resolvedClass = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.scanApkForNodeMethod(
                baseApk.absolutePath,
                "Lqn5;"
            )
            assertEquals("xh8", resolvedClass)
        }
    }

    @Test
    fun testSnapshotInjectionEnabledWithLocalCloudWithoutFullCloudCrawl() {
        val prefs = io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager(null)
        prefs.unlockLocalCloudFeatures = true
        prefs.enableCloudDiscovery = false
        CloudDiscoveryHook.setPreferencesForTesting(prefs)

        assertTrue(CloudDiscoveryHook.isSnapshotInjectionEnabled())
        assertFalse(CloudDiscoveryHook.isCloudDiscoveryEnabled())
    }

    @Test
    fun testUniversalCloudDiscoveryEnabledWithLocalCloudFeatures() {
        val prefs = io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager(null)
        prefs.unlockLocalCloudFeatures = true
        prefs.enableCloudDiscovery = true
        CloudDiscoveryHook.setPreferencesForTesting(prefs)

        assertTrue(CloudDiscoveryHook.isSnapshotInjectionEnabled())
        assertTrue(CloudDiscoveryHook.isCloudDiscoveryEnabled())
    }

    @Test
    fun testUniversalCloudDiscoveryWithCustomFirebase() {
        val prefs = io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager(null)
        prefs.unlockLocalCloudFeatures = false
        prefs.customFirebaseApp = true
        prefs.enableCloudDiscovery = true
        prefs.enableSnapshotInjection = true
        CloudDiscoveryHook.setPreferencesForTesting(prefs)

        assertTrue(CloudDiscoveryHook.isSnapshotInjectionEnabled())
        assertTrue(CloudDiscoveryHook.isCloudDiscoveryEnabled())
    }

    @Test
    fun testHooksDisabledWhenNoCloudFeaturesEnabled() {
        val prefs = io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager(null)
        prefs.unlockLocalCloudFeatures = false
        prefs.customFirebaseApp = false
        prefs.enableCloudDiscovery = true
        prefs.enableSnapshotInjection = true
        CloudDiscoveryHook.setPreferencesForTesting(prefs)

        assertFalse(CloudDiscoveryHook.isSnapshotInjectionEnabled())
        assertFalse(CloudDiscoveryHook.isCloudDiscoveryEnabled())
    }

    @Test
    fun testFolderFileNameRegexBaseAndIncremental() {
        val regex = Pattern.compile("^folder-(base|inc-[a-zA-Z0-9_-]+)\\.(fld|flm)(?:\\s+\\((.*?)\\))?\\s+\\(id-(.*?)\\)$")

        val baseFld = "folder-base.fld (POCO X6) (id-EDAXUQ)"
        val m1 = regex.matcher(baseFld)
        assertTrue(m1.matches())
        assertEquals("base", m1.group(1))
        assertEquals("fld", m1.group(2))
        assertEquals("POCO X6", m1.group(3))
        assertEquals("EDAXUQ", m1.group(4))

        val incFlm = "folder-inc-20260917-183547-281.flm (POCO X6) (id-EDAXUQ)"
        val m2 = regex.matcher(incFlm)
        assertTrue(m2.matches())
        assertEquals("inc-20260917-183547-281", m2.group(1))
        assertEquals("flm", m2.group(2))
        assertEquals("POCO X6", m2.group(3))
        assertEquals("EDAXUQ", m2.group(4))

        val baseNoTag = "folder-base.flm (id-BKFEUA)"
        val m3 = regex.matcher(baseNoTag)
        assertTrue(m3.matches())
        assertEquals("base", m3.group(1))
        assertEquals("flm", m3.group(2))
        assertNull(m3.group(3))
        assertEquals("BKFEUA", m3.group(4))

        val incNoTag = "folder-inc-20260904-185417-999.fld (id-EVBCHQ)"
        val m4 = regex.matcher(incNoTag)
        assertTrue(m4.matches())
        assertEquals("inc-20260904-185417-999", m4.group(1))
        assertEquals("fld", m4.group(2))
        assertNull(m4.group(3))
        assertEquals("EVBCHQ", m4.group(4))
    }

    @Test
    fun testDiscoveredCloudFolderIncrementalSlicesAndFirebaseMap() {
        val incSlice = CloudDiscoveryHook.DiscoveredIncrementalSlice(
            timestamp = "20260917-183547-281",
            fldLink = "drive_fld_123",
            fldSize = 5000000L,
            flmLink = "drive_flm_123",
            flmSize = 2048L,
            originalSize = 5000000L
        )

        val folder = CloudDiscoveryHook.DiscoveredCloudFolder(
            id = "EDAXUQ",
            displayName = "Pictures",
            tag = "POCO X6",
            fldLink = "base_fld_link",
            fldSize = 10000000L,
            flmLink = "base_flm_link",
            flmSize = 4096L,
            totalSize = 15006144L,
            timestamp = 1726588547000L,
            sourceFolder = "/storage/emulated/0/Pictures",
            provider = "GoogleDrive",
            incrementalSlices = mapOf("20260917-183547-281" to incSlice)
        )

        // Test JSON round trip
        val json = folder.toJson()
        val restored = CloudDiscoveryHook.DiscoveredCloudFolder.fromJson("EDAXUQ", json)
        assertEquals(folder.id, restored.id)
        assertEquals(folder.displayName, restored.displayName)
        assertEquals(folder.sourceFolder, restored.sourceFolder)
        assertEquals(1, restored.incrementalSlices.size)
        assertEquals("drive_fld_123", restored.incrementalSlices["20260917-183547-281"]?.fldLink)

        // Test Firebase metadata map
        val fbMap = folder.toFirebaseMetadataMap()
        assertTrue(fbMap.containsKey("folderItem"))
        assertTrue(fbMap.containsKey("baseBackup"))
        assertTrue(fbMap.containsKey("incrementalBackups"))

        @Suppress("UNCHECKED_CAST")
        val itemMap = fbMap["folderItem"] as Map<String, Any?>
        assertEquals("EDAXUQ", itemMap["id"])
        assertEquals("Pictures", itemMap["displayName"])
        assertEquals("/storage/emulated/0/Pictures", itemMap["sourceFolder"])

        @Suppress("UNCHECKED_CAST")
        val baseMap = fbMap["baseBackup"] as Map<String, Any?>
        assertEquals("base_fld_link", baseMap["backupLink"])
        assertEquals(10000000L, baseMap["backupSize"])

        @Suppress("UNCHECKED_CAST")
        val incBackups = fbMap["incrementalBackups"] as Map<String, Any?>
        assertTrue(incBackups.containsKey("20260917-183547-281"))
    }
}


