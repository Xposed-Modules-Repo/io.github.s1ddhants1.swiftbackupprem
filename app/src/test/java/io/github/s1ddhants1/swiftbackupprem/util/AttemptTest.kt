package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.*
import org.junit.Test

class AttemptTest {

    @Test
    fun attemptReturnsResultOnSuccess() {
        val result = attempt("successful computation") {
            40 + 2
        }
        assertEquals(42, result)
    }

    @Test
    fun attemptReturnsNullOnException() {
        val result = attempt("failing computation", silent = true) {
            throw IllegalStateException("Something went wrong")
        }
        assertNull(result)
    }

    @Test
    fun attemptOrDefaultReturnsResultOnSuccess() {
        val result = attemptOrDefault("successful computation", default = 100) {
            10 * 5
        }
        assertEquals(50, result)
    }

    @Test
    fun attemptOrDefaultReturnsDefaultOnException() {
        val result = attemptOrDefault("failing computation", default = "fallback", silent = true) {
            throw RuntimeException("Network error")
        }
        assertEquals("fallback", result)
    }

    @Test
    fun loadClassFlexibleLoadsStandardClasses() {
        val cl = this.javaClass.classLoader!!
        val loaded = loadClassFlexible(cl, "java.lang.String")
        assertNotNull(loaded)
        assertEquals(String::class.java, loaded)
    }

    @Test
    fun loadClassFlexibleHandlesDefpackagePrefix() {
        val cl = this.javaClass.classLoader!!
        val loaded = loadClassFlexible(cl, "defpackage.java.lang.String")
        assertNotNull(loaded)
        assertEquals(String::class.java, loaded)
    }

    @Test
    fun loadClassFlexibleReturnsNullForNonExistentClass() {
        val cl = this.javaClass.classLoader!!
        val loaded = loadClassFlexible(cl, "com.nonexistent.NoSuchClass12345")
        assertNull(loaded)
    }
}
