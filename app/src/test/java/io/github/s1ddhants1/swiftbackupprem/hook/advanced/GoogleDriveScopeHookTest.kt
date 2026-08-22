package io.github.s1ddhants1.swiftbackupprem.hook.advanced

import org.junit.Assert.*
import org.junit.Test

class GoogleDriveScopeHookTest {

    @Test
    fun testScopeUpgradeLogic() {
        val driveFileScope = "https://www.googleapis.com/auth/drive.file"
        val fullDriveScope = "https://www.googleapis.com/auth/drive"

        val upgradedSingle = driveFileScope.replace(driveFileScope, fullDriveScope)
        assertEquals(fullDriveScope, upgradedSingle)

        val combinedScope = "openid email https://www.googleapis.com/auth/drive.file profile"
        val upgradedCombined = combinedScope.replace(driveFileScope, fullDriveScope)
        assertEquals("openid email https://www.googleapis.com/auth/drive profile", upgradedCombined)

        val scopesArray = arrayOf("openid", "https://www.googleapis.com/auth/drive.file", "profile")
        val upgradedArray = scopesArray.map { if (it == driveFileScope) fullDriveScope else it }.toTypedArray()
        assertArrayEquals(arrayOf("openid", "https://www.googleapis.com/auth/drive", "profile"), upgradedArray)
    }

    @Test
    fun testPromptConsentAndUriUpgrade() {
        val original = "https://accounts.google.com/o/oauth2/v2/auth?scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file&prompt=select_account"
        var upgraded = original
            .replace("https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file", "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive")
            .replace(Regex("prompt=[^&]*"), "prompt=consent")

        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive&prompt=consent", upgraded)
    }
}
