package io.github.s1ddhants1.swiftbackupprem.util

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class BackupMigratorEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sourceUid = "test_source_firebase_uid_12345"
    private val targetUid = "test_destination_custom_uid_67890"

    private lateinit var sourceDir: File
    private lateinit var outputDir: File

    @Before
    fun setup() {
        sourceDir = tempFolder.newFolder("source_backups")
        outputDir = tempFolder.newFolder("output_storage")
    }

    @Test
    fun testAccountHashComputation() {
        val hash = BackupMigratorEngine.computeAccountHash(sourceUid)
        assertNotNull(hash)
        assertEquals(16, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })

        val anonHash = BackupMigratorEngine.computeAccountHash(BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID)
        assertEquals(16, anonHash.length)
    }

    @Test
    fun testFindAppBackupDirs() {
        val appDir = File(sourceDir, "com.example.testapp/1724716800000").apply { mkdirs() }
        File(appDir, "com.example.testapp.app").writeText("DUMMY_APK_BYTES")
        File(appDir, "com.example.testapp.dat").writeText("DUMMY_DAT_BYTES")

        val found = BackupMigratorEngine.findAppBackupDirs(sourceDir)
        assertEquals(1, found.size)
        assertEquals("com.example.testapp", found[0].first)
        assertEquals(appDir.absolutePath, found[0].second.absolutePath)
    }

    @Test
    fun testFindFolderBackupDirs() {
        val folderDir = File(sourceDir, "Folder-1724716800000").apply { mkdirs() }
        File(folderDir, "folder-base.fld").writeText("DUMMY_FLD_BYTES")

        val found = BackupMigratorEngine.findFolderBackupDirs(sourceDir)
        assertEquals(1, found.size)
        assertEquals(folderDir.absolutePath, found[0].absolutePath)
    }

    @Test
    fun testMigrateAppBackup_toAnonymous() {
        val pkg = "com.swiftapps.test"
        val backupId = "1724716800000"
        val appDir = File(sourceDir, "$pkg/$backupId").apply { mkdirs() }

        // Create sample encrypted .xml
        val sourceKey = BackupCrypto.deriveConcealKey(sourceUid)
        val metaJson = JSONObject().apply {
            put("packageName", pkg)
            put("name", "Test Application")
            put("versionCode", 100L)
            put("versionName", "1.0.0")
        }
        val encUid = BackupCrypto.concealEncrypt(sourceUid, sourceKey)
        val encMeta = BackupCrypto.concealEncrypt(metaJson.toString(), sourceKey)
        File(appDir, "$pkg.xml").writeText("v1:::$encUid:::$encMeta", StandardCharsets.UTF_8)

        // Create slices
        File(appDir, "$pkg.app").writeText("APK_CONTENT")
        File(appDir, "$pkg.dat").writeText("DATA_CONTENT")

        val config = BackupMigratorEngine.MigrationConfig(
            sourceDir = sourceDir,
            sourceUid = sourceUid,
            targetMode = BackupMigratorEngine.TargetEncryptionMode.Anonymous(),
            targetDir = outputDir
        )

        val result = BackupMigratorEngine.migrate(config)
        assertTrue(result.success)
        assertEquals(1, result.totalAppsMigrated)

        val anonHash = BackupMigratorEngine.computeAccountHash(BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID)
        assertEquals(anonHash, result.targetAccountHash)

        val destAppDir = File(outputDir, "SwiftBackup/accounts/$anonHash/backups/apps/local/$pkg/$backupId")
        assertTrue("Destination app dir must exist", destAppDir.exists())

        val destXml = File(destAppDir, "$pkg.xml")
        assertTrue("Destination .xml must exist", destXml.exists())

        // Verify that destination .xml can be decrypted using anonymous key!
        val anonKey = BackupCrypto.deriveConcealKey(BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID)
        val parts = destXml.readText(StandardCharsets.UTF_8).split(":::").filter { it.isNotBlank() }
        assertEquals(3, parts.size)

        val decUid = String(BackupCrypto.concealDecrypt(parts[1], anonKey), StandardCharsets.UTF_8)
        assertEquals(BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID, decUid)

        val decMetaStr = String(BackupCrypto.concealDecrypt(parts[2], anonKey), StandardCharsets.UTF_8)
        val decMetaJson = JSONObject(decMetaStr)
        assertEquals(pkg, decMetaJson.getString("packageName"))
        assertEquals("Test Application", decMetaJson.getString("name"))
        assertTrue(decMetaJson.getBoolean("isDataEncrypted"))
    }

    @Test
    fun testMigrateAppBackup_toCustomUid() {
        val pkg = "org.example.custom"
        val backupId = "1724716900000"
        val appDir = File(sourceDir, "$pkg/$backupId").apply { mkdirs() }

        // Slices without .xml (tests auto-reconstruction)
        File(appDir, "$pkg.app").writeText("APK_CONTENT")
        File(appDir, "$pkg.dat").writeText("DATA_CONTENT")
        File(appDir, "$pkg.extdat").writeText("EXT_DATA_CONTENT")

        val config = BackupMigratorEngine.MigrationConfig(
            sourceDir = sourceDir,
            sourceUid = sourceUid,
            targetMode = BackupMigratorEngine.TargetEncryptionMode.Custom(targetUid),
            targetDir = outputDir
        )

        val result = BackupMigratorEngine.migrate(config)
        assertTrue(result.success)
        assertEquals(1, result.totalAppsMigrated)

        val targetHash = BackupMigratorEngine.computeAccountHash(targetUid)
        assertEquals(targetHash, result.targetAccountHash)

        val destAppDir = File(outputDir, "SwiftBackup/accounts/$targetHash/backups/apps/local/$pkg/$backupId")
        assertTrue(destAppDir.exists())

        val destXml = File(destAppDir, "$pkg.xml")
        assertTrue(destXml.exists())

        // Verify decryption with targetKey
        val targetKey = BackupCrypto.deriveConcealKey(targetUid)
        val parts = destXml.readText(StandardCharsets.UTF_8).split(":::").filter { it.isNotBlank() }
        val decMetaStr = String(BackupCrypto.concealDecrypt(parts[2], targetKey), StandardCharsets.UTF_8)
        val decJson = JSONObject(decMetaStr)

        assertEquals(pkg, decJson.getString("packageName"))
        assertTrue(decJson.getBoolean("isDataEncrypted"))
        assertTrue(decJson.getBoolean("isExtDataEncrypted"))
    }

    @Test
    fun testMigrateFolderBackup() {
        val folderName = "Folder-999888"
        val folderDir = File(sourceDir, folderName).apply { mkdirs() }
        File(folderDir, "folder-base.fld").writeText("FOLDER_PAYLOAD")

        val config = BackupMigratorEngine.MigrationConfig(
            sourceDir = sourceDir,
            sourceUid = sourceUid,
            targetMode = BackupMigratorEngine.TargetEncryptionMode.Anonymous(),
            targetDir = outputDir
        )

        val result = BackupMigratorEngine.migrate(config)
        assertTrue(result.success)
        assertEquals(1, result.totalFoldersMigrated)

        val anonHash = BackupMigratorEngine.computeAccountHash(BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID)
        val destFolderDir = File(outputDir, "SwiftBackup/accounts/$anonHash/backups/folders/local/$folderName")
        assertTrue(destFolderDir.exists())

        val metaFile = File(destFolderDir, "metadata.json")
        assertTrue(metaFile.exists())

        val metaJson = JSONObject(metaFile.readText(StandardCharsets.UTF_8))
        assertEquals("999888", metaJson.getJSONObject("folderItem").getString("id"))
    }

    @Test
    fun testMigrateAppBackup_toUnencrypted() {
        val pkg = "com.unencrypted.test"
        val backupId = "1724717000000"
        val appDir = File(sourceDir, "$pkg/$backupId").apply { mkdirs() }

        val sourceKey = BackupCrypto.deriveConcealKey(sourceUid)
        val plainDataPayload = "COMPRESSED_TAR_STREAM_PAYLOAD_UNENCRYPTED"

        // Create Conceal encrypted .dat file
        val encBase64 = BackupCrypto.concealEncrypt(plainDataPayload, sourceKey)
        val encRawBytes = Base64Wrapper.decode(encBase64)
        File(appDir, "$pkg.app").writeText("APK_CONTENT")
        File(appDir, "$pkg.dat").writeBytes(encRawBytes)

        val config = BackupMigratorEngine.MigrationConfig(
            sourceDir = sourceDir,
            sourceUid = sourceUid,
            targetMode = BackupMigratorEngine.TargetEncryptionMode.Unencrypted(),
            targetDir = outputDir
        )

        val result = BackupMigratorEngine.migrate(config)
        assertTrue(result.success)
        assertEquals(1, result.totalAppsMigrated)

        val anonHash = BackupMigratorEngine.computeAccountHash(BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID)
        val destAppDir = File(outputDir, "SwiftBackup/accounts/$anonHash/backups/apps/local/$pkg/$backupId")
        assertTrue(destAppDir.exists())

        // Verify .dat was decrypted
        val destDat = File(destAppDir, "$pkg.dat")
        assertTrue(destDat.exists())
        assertEquals(plainDataPayload, destDat.readText(StandardCharsets.UTF_8))

        // Verify .xml is unencrypted plaintext JSON and encryption flags are false
        val destXml = File(destAppDir, "$pkg.xml")
        assertTrue(destXml.exists())
        val xmlContent = destXml.readText(StandardCharsets.UTF_8)
        assertFalse(xmlContent.startsWith("v1:::"))
        val json = JSONObject(xmlContent)
        assertEquals(pkg, json.getString("packageName"))
        assertFalse(json.getBoolean("isDataEncrypted"))
        assertFalse(json.has("dataEncryptionMethod"))
    }
}
