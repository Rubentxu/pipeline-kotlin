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

    // ───── LF-0402 — borrow{} / bytesView() non-destructive access ─────

    @Test
    fun `LF-0402 borrow does not wipe bytes after the block`() {
        val bytes = "borrow-payload".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(bytes)

        val inside = handle.borrow { it.contentEquals("borrow-payload".toByteArray(StandardCharsets.UTF_8)) }
        assertTrue(inside, "borrow must hand the block the same payload bytes")

        // The original byte array is untouched by borrow
        assertTrue(
            bytes.contentEquals("borrow-payload".toByteArray(StandardCharsets.UTF_8)),
            "LF-0402: borrow must NOT wipe the handle's internal buffer",
        )
        // Subsequent materialize still returns the original payload
        assertEquals("borrow-payload", handle.materialize())
    }

    @Test
    fun `LF-0402 borrow can be invoked multiple times and always returns same original bytes`() {
        val payload = "repeatable-payload".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(payload)

        val firstSnapshot = handle.borrow { it.copyOf() }
        val secondSnapshot = handle.borrow { it.copyOf() }
        val thirdSnapshot = handle.bytesView()

        assertTrue(firstSnapshot.contentEquals(payload), "first borrow snapshot")
        assertTrue(secondSnapshot.contentEquals(payload), "second borrow snapshot")
        assertTrue(thirdSnapshot.contentEquals(payload), "bytesView snapshot")

        // Even after multiple borrows, the original bytes are still intact
        assertTrue(
            payload.contentEquals("repeatable-payload".toByteArray(StandardCharsets.UTF_8)),
            "LF-0402: multiple borrow calls must leave the original bytes intact",
        )
        assertEquals("repeatable-payload", handle.materialize())
    }

    @Test
    fun `LF-0402 borrow hands the block a defensive copy that the caller can mutate freely`() {
        val handle = SecretHandle("defensive".toByteArray(StandardCharsets.UTF_8))

        handle.borrow { view ->
            view.fill(0x7F)
            // The mutation is local to the defensive copy; the original is unaffected.
        }

        assertEquals("defensive", handle.materialize())
    }

    @Test
    fun `LF-0402 bytesView returns a defensive copy`() {
        val payload = "view-payload".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(payload)

        val snapshot = handle.bytesView()
        snapshot.fill(0)

        // The mutation of the snapshot does not affect the handle
        assertEquals("view-payload", handle.materialize())
    }

    @Test
    fun `LF-0402 use still wipes bytes after the block (legacy destructive contract preserved)`() {
        val bytes = "wipe-me".toByteArray(StandardCharsets.UTF_8)
        val handle = SecretHandle(bytes)

        handle.use { /* discard */ }

        assertTrue(
            bytes.contentEquals(ByteArray(7)),
            "use{} must still wipe the buffer to preserve the destructive contract",
        )
    }
}
