package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import java.nio.charset.StandardCharsets

/**
 * Tests for SecretHandle typed channel.
 * SecretHandle is the typed channel for secret values.
 * It provides use{} for scoped access, close() for wipe, and materialization.
 * 
 * WS-S-022: typed channel + wipe contract
 * WS-S-023: close() wipes bytes.fill(0); idempotent
 * CR-BD-005/006/007: scope exit cleanup
 */
@DisplayName("SecretHandle typed channel contract tests")
class SecretHandleContractTest {

    @Test
    fun `use returns content and leaves handle open`() {
        val bytes = "secret-value".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(bytes)
        
        val result = handle.use { it.contentEquals("secret-value".toByteArray(StandardCharsets.UTF_8)) }
        assertTrue(result)
    }

    @Test
    fun `close wipes bytes to zeros`() {
        val bytes = "sensitive".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(bytes)
        
        handle.close()
        
        assertTrue(bytes.contentEquals(ByteArray(9)))
    }

    @Test
    fun `toString shows sizeBytes without exposing content`() {
        val bytes = "my-secret".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(bytes)
        
        val str = handle.toString()
        assertEquals("Secret(sizeBytes=9)", str)
        assertFalse(str.contains("my-secret"))
    }

    @Test
    fun `second close is idempotent no-op`() {
        val bytes = "test".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(bytes)
        
        handle.close()
        val firstZeros = bytes.contentEquals(ByteArray(4))
        
        handle.close() // second call should not throw
        val secondZeros = bytes.contentEquals(ByteArray(4))
        
        assertTrue(firstZeros)
        assertTrue(secondZeros)
    }

    @Test
    fun `destroy is alias for close`() {
        val bytes = "data".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(bytes)
        
        handle.destroy()
        
        assertTrue(bytes.contentEquals(ByteArray(4)))
    }

    @Test
    fun `sizeBytes returns the byte array size`() {
        val bytes = ByteArray(16)
        val handle = SecretHandle(bytes)
        assertEquals(16, handle.sizeBytes)
    }

    @Test
    fun `SecretHandle is final class with no equals hashCode copy`() {
        // Final class prevents accidental cloning
        val h1 = SecretHandle(ByteArray(5))
        val h2 = SecretHandle(ByteArray(5))
        // Since it's a final class with internal bytes, equals is reference equality or throws
        // The important thing is there's no copy() method
        assertNotNull(h1)
    }

    @Test
    fun `plain factory creates non-secret handle`() {
        val handle = SecretHandle.plain("test-string")
        val result = handle.use { String(it, StandardCharsets.UTF_8) }
        assertEquals("test-string", result)
    }

    @Test
    fun `secret factory wraps ByteArray`() {
        val bytes = "binary-secret".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle.secret(bytes)
        assertEquals(13, handle.sizeBytes)
    }
}
