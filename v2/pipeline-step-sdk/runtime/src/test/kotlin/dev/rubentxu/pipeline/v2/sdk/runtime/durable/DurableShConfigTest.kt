package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DurableShConfigTest {

    @Test
    fun `default config has sensible defaults`() {
        // Clear any sysprops that might interfere
        System.clearProperty(DurableShConfig.RETURN_STDOUT_PROPERTY)
        System.clearProperty(DurableShConfig.CAPTURE_RETAIN_POLICY_PROPERTY)

        val config = DurableShConfig.fromSystemProperties()
        assertFalse(config.returnStdout)
        assertEquals(CaptureRetainPolicy.READ_THEN_DELETE, config.captureRetainPolicy)
    }

    @Test
    fun `returnStdout can be overridden via sysprop to true`() {
        System.setProperty(DurableShConfig.RETURN_STDOUT_PROPERTY, "true")
        try {
            val config = DurableShConfig.fromSystemProperties()
            assertTrue(config.returnStdout)
        } finally {
            System.clearProperty(DurableShConfig.RETURN_STDOUT_PROPERTY)
        }
    }

    @Test
    fun `returnStdout can be overridden via sysprop to false`() {
        System.setProperty(DurableShConfig.RETURN_STDOUT_PROPERTY, "false")
        try {
            val config = DurableShConfig.fromSystemProperties()
            assertFalse(config.returnStdout)
        } finally {
            System.clearProperty(DurableShConfig.RETURN_STDOUT_PROPERTY)
        }
    }

    @Test
    fun `captureRetainPolicy can be set to RETAIN via sysprop`() {
        System.setProperty(DurableShConfig.CAPTURE_RETAIN_POLICY_PROPERTY, "RETAIN")
        try {
            val config = DurableShConfig.fromSystemProperties()
            assertEquals(CaptureRetainPolicy.RETAIN, config.captureRetainPolicy)
        } finally {
            System.clearProperty(DurableShConfig.CAPTURE_RETAIN_POLICY_PROPERTY)
        }
    }

    @Test
    fun `captureRetainPolicy defaults to READ_THEN_DELETE`() {
        System.clearProperty(DurableShConfig.CAPTURE_RETAIN_POLICY_PROPERTY)
        val config = DurableShConfig.fromSystemProperties()
        assertEquals(CaptureRetainPolicy.READ_THEN_DELETE, config.captureRetainPolicy)
    }

    @Test
    fun `invalid captureRetainPolicy falls back to default`() {
        System.setProperty(DurableShConfig.CAPTURE_RETAIN_POLICY_PROPERTY, "INVALID")
        try {
            val config = DurableShConfig.fromSystemProperties()
            assertEquals(CaptureRetainPolicy.READ_THEN_DELETE, config.captureRetainPolicy)
        } finally {
            System.clearProperty(DurableShConfig.CAPTURE_RETAIN_POLICY_PROPERTY)
        }
    }

    @Test
    fun `returnStdout default is false (L1 guard)`() {
        // Per RTS-S-001: returnStdout=false leaves output.txt untouched (L1 guard)
        System.clearProperty(DurableShConfig.RETURN_STDOUT_PROPERTY)
        val config = DurableShConfig.fromSystemProperties()
        assertFalse(config.returnStdout)
    }

    @Test
    fun `config data class equality works`() {
        val config1 = DurableShConfig(
            heartbeatCheckInterval = 300L,
            heartbeatMinimumDelta = 2L,
            cleanupRetainOnFailure = true,
            returnStdout = false,
            captureRetainPolicy = CaptureRetainPolicy.READ_THEN_DELETE,
        )
        val config2 = DurableShConfig(
            heartbeatCheckInterval = 300L,
            heartbeatMinimumDelta = 2L,
            cleanupRetainOnFailure = true,
            returnStdout = false,
            captureRetainPolicy = CaptureRetainPolicy.READ_THEN_DELETE,
        )
        assertEquals(config1, config2)
    }
}
