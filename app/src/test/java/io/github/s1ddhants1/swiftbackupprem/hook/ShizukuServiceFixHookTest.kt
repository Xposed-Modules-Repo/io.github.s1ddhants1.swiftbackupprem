package io.github.s1ddhants1.swiftbackupprem.hook

import android.os.IBinder
import android.os.IInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuServiceFixHookTest {

    enum class TestExecutionMode {
        Auto,
        Local,
        Root,
        Shizuku
    }

    enum class OtherEnum {
        OptionA,
        OptionB
    }

    interface MockSbaAidlInterface : IInterface {
        fun isSbaFile(path: String): Boolean
        fun getUid(): Int
        fun createSba(token: Long): Long
        fun getSbaNativeVersion(): String
        fun readSbaInfo(path: String): Any?
    }

    @Test
    fun `test redirectEnumToLocal redirects Shizuku to Local`() {
        val result = ShizukuServiceFixHook.redirectEnumToLocal(TestExecutionMode.Shizuku)
        assertEquals(TestExecutionMode.Local, result)
    }

    @Test
    fun `test redirectEnumToLocal preserves non-Shizuku modes`() {
        assertEquals(TestExecutionMode.Local, ShizukuServiceFixHook.redirectEnumToLocal(TestExecutionMode.Local))
        assertEquals(TestExecutionMode.Root, ShizukuServiceFixHook.redirectEnumToLocal(TestExecutionMode.Root))
        assertEquals(TestExecutionMode.Auto, ShizukuServiceFixHook.redirectEnumToLocal(TestExecutionMode.Auto))
    }

    @Test
    fun `test redirectEnumToLocal gracefully preserves enum without Local constant`() {
        val original = OtherEnum.OptionA
        val result = ShizukuServiceFixHook.redirectEnumToLocal(original)
        assertEquals(original, result)
    }

    @Test
    fun `test createDummySbaProxy returns null for non-interface`() {
        val proxy = ShizukuServiceFixHook.createDummySbaProxy(String::class.java, javaClass.classLoader!!)
        assertNull("Proxy should be null for non-interface classes", proxy)
    }

    @Test
    fun `test createDummySbaProxy handles AIDL interface methods safely`() {
        val proxy = ShizukuServiceFixHook.createDummySbaProxy(
            MockSbaAidlInterface::class.java,
            javaClass.classLoader!!
        ) as? MockSbaAidlInterface

        assertNotNull("Proxy should be created for interface", proxy)
        assertFalse("isSbaFile should safely return false without NPE", proxy!!.isSbaFile("/sdcard/test.sba"))
        assertEquals("getUid should return 0", 0, proxy.getUid())
        assertEquals("createSba should return -1", -1L, proxy.createSba(12345L))
        assertEquals("getSbaNativeVersion should return empty string", "", proxy.getSbaNativeVersion())
        assertNull("readSbaInfo should return null without crash", proxy.readSbaInfo("/sdcard/test.sba"))
    }

    @Test
    fun `test createDummySbaProxy asBinder returns dead binder`() {
        val proxy = ShizukuServiceFixHook.createDummySbaProxy(
            MockSbaAidlInterface::class.java,
            javaClass.classLoader!!
        ) as? MockSbaAidlInterface

        assertNotNull(proxy)
        val binder = proxy!!.asBinder()
        assertNotNull("asBinder should return non-null dummy binder", binder)
        assertFalse("Binder should not be alive", binder.isBinderAlive)
        assertFalse("pingBinder should return false", binder.pingBinder())
    }

    @Test
    fun `test createDummySbaProxy standard Object methods`() {
        val proxy = ShizukuServiceFixHook.createDummySbaProxy(
            MockSbaAidlInterface::class.java,
            javaClass.classLoader!!
        ) as? MockSbaAidlInterface

        assertNotNull(proxy)
        val nonNullProxy = proxy!!
        assertEquals("DummySbaServiceProxy", nonNullProxy.toString())
        assertEquals(1, nonNullProxy.hashCode())
        assertTrue(nonNullProxy.equals(nonNullProxy))
    }
}
