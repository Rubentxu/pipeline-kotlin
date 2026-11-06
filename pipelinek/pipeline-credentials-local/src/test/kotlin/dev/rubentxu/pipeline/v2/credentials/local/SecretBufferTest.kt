package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The owner of a secret buffer, and the one operation that forgets it.
 *
 * ## The guarantee
 * A buffer that has been wiped holds nothing that can be read: the bytes it
 * was given are unreadable for whoever held them, and the buffer hands out no
 * bytes at all.
 *
 * ## Why the test holds the bytes
 * The bytes are the only thing that can be observed here, and the only party
 * that can observe them is the one that gave them. That is why this test
 * creates the array, gives it to the buffer, and looks at it afterwards: the
 * guarantee is about what happens to THOSE bytes, and a test that looked at
 * the buffer from the outside could only see that the buffer said it was
 * empty.
 *
 * ## Why this test is a UNIT test
 * A buffer is a value over bytes. It reads no file, asks for no clock and
 * talks to nobody, and so it belongs with the other pure primitives of this
 * module.
 */
class SecretBufferTest {

    @Test
    fun `a buffer hands out the bytes it was given`() {
        val bytes = ByteArray(32) { (it + 1).toByte() }

        val buffer = SecretBuffer.of(bytes)

        assertArrayEquals(
            bytes,
            buffer.bytes(),
            "the buffer did not hand out the bytes it was given",
        )
    }

    @Test
    fun `wiping a buffer makes the bytes it was given unreadable`() {
        val bytes = ByteArray(32) { (it + 1).toByte() }
        val buffer = SecretBuffer.of(bytes)

        buffer.wipe()

        assertTrue(
            bytes.all { it == 0.toByte() },
            "the bytes the buffer was given are still readable after the wipe",
        )
    }

    @Test
    fun `a wiped buffer hands out no bytes`() {
        val buffer = SecretBuffer.of(ByteArray(32) { 7 })

        buffer.wipe()

        assertThrows(
            IllegalStateException::class.java,
            { buffer.bytes() },
            "a wiped buffer handed out bytes that it does not have",
        )
    }

    @Test
    fun `wiping a buffer twice is not an error`() {
        val bytes = ByteArray(32) { (it + 1).toByte() }
        val buffer = SecretBuffer.of(bytes)

        buffer.wipe()
        buffer.wipe()

        assertTrue(
            bytes.all { it == 0.toByte() },
            "the second wipe changed the bytes it was given",
        )
    }

    @Test
    fun `the owner is told once, when the buffer is wiped`() {
        var told = 0
        val buffer = SecretBuffer.of(ByteArray(32) { 7 }) { told++ }

        buffer.wipe()
        buffer.wipe()

        assertEquals(
            1,
            told,
            "the owner was not told exactly once that the buffer was wiped",
        )
    }
}
