package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SystemRuntimeConfigTest {

    @Test
    fun `property with default returns the default when the key is missing`() {
        val config = SystemRuntimeConfig()

        // Use a property that is virtually guaranteed not to be set in any
        // CI or developer JVM.
        assertEquals("fallback-value", config.property("pipeline.runtime-config.test.missing", "fallback-value"))
    }

    @Test
    fun `property with default returns the actual value when the key is set`() {
        val key = "pipeline.runtime-config.test.present"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "actual-value")
            val config = SystemRuntimeConfig()

            assertEquals("actual-value", config.property(key, "default-value"))
            assertEquals("actual-value", config.property(key))
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    @Test
    fun `osName returns the JVM os name`() {
        val config = SystemRuntimeConfig()

        assertEquals(System.getProperty("os.name"), config.osName())
    }

    @Test
    fun `property without default returns null when the key is missing`() {
        val key = "pipeline.runtime-config.test.never-set"
        val previous = System.getProperty(key)
        try {
            System.clearProperty(key)
            val config = SystemRuntimeConfig()

            assertEquals(null, config.property(key))
        } finally {
            if (previous != null) System.setProperty(key, previous)
        }
    }
}
