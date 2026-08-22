package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class CloudDiscoveryHookTest {

    @Test
    fun testDriveBackupFileNameRegex() {
        val regex = Pattern.compile("^(.*?)\\.([a-z]+)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")

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
}
