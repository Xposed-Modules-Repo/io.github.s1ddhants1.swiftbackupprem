package io.github.s1ddhants1.swiftbackupprem.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy

class RootServiceFixHookTest {

    private val originPath = "/data/user/0/org.swiftapps.swiftbackup/cache/lspatch/origin/2507548724.apk"
    private val installedApk = "/data/app/~~test/org.swiftapps.swiftbackup-abc/base.apk"

    @Test
    fun `test transformRootCommand transforms RootServerMain command`() {
        val originalCmd = "( CLASSPATH=/data/user/0/org.swiftapps.swiftbackup/cache/main.jar /system/bin/app_process64 -Xnoimage-dex2oat /system/bin --nice-name=org.swiftapps.swiftbackup:root:0 com.topjohnwu.superuser.internal.RootServerMain 'org.swiftapps.swiftbackup/com.topjohnwu.superuser.ipc.RootService\$1' 10123 1 >/dev/null 2>&1)&"

        val transformed = RootServiceFixHook.transformRootCommand(originalCmd, originPath, installedApk)

        assertTrue("Should contain unshare -m", transformed.contains("unshare -m"))
        assertTrue("Should contain toybox unshare", transformed.contains("toybox unshare -m"))
        assertTrue("Should contain mount rprivate", transformed.contains("mount -o rprivate none /"))
        assertTrue("Should contain mount bind for origin and installed APK", transformed.contains("mount -o bind '$originPath' '$installedApk'"))
        assertTrue("Should contain RootServerMain invocation", transformed.contains("com.topjohnwu.superuser.internal.RootServerMain"))
        assertTrue("Should preserve nice-name", transformed.contains("--nice-name=org.swiftapps.swiftbackup:root:0"))
        assertTrue("Should end with redirection and background", transformed.endsWith(">/dev/null 2>&1)&"))
    }

    @Test
    fun `test transformRootCommand ignores unrelated commands`() {
        val unrelatedCmd = "ls -la /data/local/tmp"
        val result = RootServiceFixHook.transformRootCommand(unrelatedCmd, originPath, installedApk)
        assertEquals("Unrelated command should be untouched", unrelatedCmd, result)
    }

    @Test
    fun `test transformRootCommand does not double wrap`() {
        val originalCmd = "( CLASSPATH=/cache/main.jar /system/bin/app_process64 com.topjohnwu.superuser.internal.RootServerMain 'test' 1000 1 >/dev/null 2>&1)&"
        val firstTransform = RootServiceFixHook.transformRootCommand(originalCmd, originPath, installedApk)
        val secondTransform = RootServiceFixHook.transformRootCommand(firstTransform, originPath, installedApk)

        assertEquals("Second transform should be idempotent", firstTransform, secondTransform)
    }

    @Test
    fun `test createInterceptingOutputStream intercepts STDIN writes`() {
        val originalCmd = "( CLASSPATH=/cache/main.jar /system/bin/app_process64 com.topjohnwu.superuser.internal.RootServerMain 'test' 1000 1 >/dev/null 2>&1)&"
        val destination = ByteArrayOutputStream()

        val interceptingStream = RootServiceFixHook.createInterceptingOutputStream(destination, originPath, installedApk)
        interceptingStream.write(originalCmd.toByteArray(Charsets.UTF_8))
        interceptingStream.write('\n'.code)
        interceptingStream.flush()

        val outputStr = destination.toString(Charsets.UTF_8.name())
        assertTrue("Stream output should contain unshare -m", outputStr.contains("unshare -m"))
        assertTrue("Stream output should contain mount --bind", outputStr.contains("mount --bind '$originPath' '$installedApk'"))
        assertTrue("Stream output should contain RootServerMain", outputStr.contains("RootServerMain"))
        assertTrue("Stream output should end with newline", outputStr.endsWith("\n"))
    }

    interface MockShellTask {
        fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream)
    }

    @Test
    fun `test wrapShellTask intercepts run method`() {
        var writtenToStdin = ""
        val mockTask = object : MockShellTask {
            override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) {
                val cmd = "( CLASSPATH=/cache/main.jar /system/bin/app_process64 com.topjohnwu.superuser.internal.RootServerMain 'test' 1000 1 >/dev/null 2>&1)&"
                stdin.write(cmd.toByteArray(Charsets.UTF_8))
                stdin.write('\n'.code)
                stdin.flush()
            }
        }

        val rawOut = ByteArrayOutputStream()
        val wrapped = RootServiceFixHook.wrapShellTask(
            originalTask = mockTask,
            classLoader = javaClass.classLoader!!,
            originPath = originPath,
            installedApkPath = installedApk
        ) as MockShellTask

        val mockIn = "".byteInputStream()
        wrapped.run(rawOut, mockIn, mockIn)

        writtenToStdin = rawOut.toString(Charsets.UTF_8.name())
        assertTrue("Proxy should have transformed the command on STDIN", writtenToStdin.contains("mount -o bind '$originPath' '$installedApk'"))
        assertTrue("Proxy should contain unshare -m", writtenToStdin.contains("unshare -m"))
    }
}
