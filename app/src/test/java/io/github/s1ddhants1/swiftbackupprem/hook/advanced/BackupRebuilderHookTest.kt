package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class BackupRebuilderHookTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testUid = "ymiQ80Ks3RStD4CEugbLgyQPCUM2"

    @Test
    fun testDeriveConcealKey_lengthAndPadding() {
        val key = BackupRebuilderHook.deriveConcealKey(testUid)
        assertEquals(32, key.size)
        val keyString = String(key, StandardCharsets.UTF_8)
        assertEquals("ymiQ80Ks3RStD4CEugbLgyQPCUM2ymiQ", keyString)
    }

    @Test
    fun testDeriveConcealKey_shortUidSafety() {
        val shortUid = "abc"
        val key = BackupRebuilderHook.deriveConcealKey(shortUid)
        assertEquals(32, key.size)
        val keyString = String(key, StandardCharsets.UTF_8)
        assertEquals("abcabcabcabcabcabcabcabcabcabcab", keyString)
    }

    @Test
    fun testConcealEncryptAndDecrypt() {
        val key = BackupRebuilderHook.deriveConcealKey(testUid)
        val originalText = "Hello SwiftBackup Conceal Encryption!"

        val encryptedBase64 = BackupRebuilderHook.concealEncrypt(originalText, key)
        assertNotNull(encryptedBase64)
        assertTrue(encryptedBase64.isNotEmpty())

        val decryptedBytes = BackupRebuilderHook.concealDecrypt(encryptedBase64, key)
        val decryptedText = String(decryptedBytes, StandardCharsets.UTF_8)
        assertEquals(originalText, decryptedText)
    }

    @Test
    fun testRebuildBackupDirectory_generatesXmlFromSlices() {
        val backupDir = tempFolder.newFolder("com.example.testapp", "20260822-120000-AB")
        val appFile = File(backupDir, "com.example.testapp.app").apply { writeBytes(ByteArray(1024)) }
        val datFile = File(backupDir, "com.example.testapp.dat").apply { writeBytes(ByteArray(2048)) }

        val xmlFile = File(backupDir, "com.example.testapp.xml")
        assertFalse(xmlFile.exists())

        val result = BackupRebuilderHook.rebuildBackupDirectory(
            backupDir,
            "com.example.testapp",
            "20260822-120000-AB",
            this.javaClass.classLoader!!,
            io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets(),
            testUid
        )

        assertTrue(result)
        assertTrue(xmlFile.exists())
        assertTrue(xmlFile.length() > 0)

        // Verify XML structure (v1:::<encUid>:::<encMeta>)
        val content = xmlFile.readText(StandardCharsets.UTF_8)
        val parts = content.split(":::").filter { it.isNotBlank() }
        assertEquals(3, parts.size)
        assertEquals("v1", parts[0])

        val key = BackupRebuilderHook.deriveConcealKey(testUid)
        val decUid = String(BackupRebuilderHook.concealDecrypt(parts[1], key), StandardCharsets.UTF_8)
        assertEquals(testUid, decUid)

        val decMetaJson = String(BackupRebuilderHook.concealDecrypt(parts[2], key), StandardCharsets.UTF_8)
        val json = JSONObject(decMetaJson)
        assertEquals("com.example.testapp", json.getString("packageName"))
        assertEquals(1024L, json.getLong("apkBackupSize"))
        assertEquals(2048L, json.getLong("dataBackupSize"))
        assertTrue(json.getBoolean("isDataEncrypted"))
    }
}
