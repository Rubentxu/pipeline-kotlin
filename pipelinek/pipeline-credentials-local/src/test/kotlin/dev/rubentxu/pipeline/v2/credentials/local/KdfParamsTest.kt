package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The local credential store persists [KdfParams] in its file header and uses them every time it
 * derives a key. If a value below the OWASP floor were accepted, a header written by an older
 * binary would silently get weaker parameters on every read.
 *
 * The floor and the upgrade are tiny, but they are the only line of defence against a future
 * value being authored lower than the current policy, and the only place where the
 * "is it the floor" decision is centralized. The class has no IO, no clock and no globals,
 * so the whole contract fits in this file.
 */
class KdfParamsTest {

    @Test
    fun `the OWASP floor is 19456 KiB, 2 iterations, 1 parallelism`() {
        // The floor is the smallest accepted value, not a recommended one. Recording the three
        // numbers as independent assertions catches a partial edit (a typo in m, t or p) rather
        // than letting it pass because the others happen to be right.
        assertEquals(19_456, KdfParams.OWASP_MIN.m, "memory in KiB")
        assertEquals(2, KdfParams.OWASP_MIN.t, "iterations")
        assertEquals(1, KdfParams.OWASP_MIN.p, "parallelism")
    }

    @Test
    fun `params at the floor are not below it`() {
        assertFalse(KdfParams.OWASP_MIN.isBelowCurrentFloor(), "the floor is the floor, not below it")
    }

    @Test
    fun `params below the floor on memory are below the floor`() {
        val lowerMemory = KdfParams(m = 19_456 - 1, t = 2, p = 1, salt = ByteArray(0))

        assertTrue(
            lowerMemory.isBelowCurrentFloor(),
            "one KiB below the memory floor must still be detected",
        )
    }

    @Test
    fun `params below the floor on iterations are below the floor`() {
        val lowerIterations = KdfParams(m = 19_456, t = 1, p = 1, salt = ByteArray(0))

        assertTrue(
            lowerIterations.isBelowCurrentFloor(),
            "one iteration below the floor must still be detected",
        )
    }

    @Test
    fun `params below the floor on parallelism are below the floor`() {
        val lowerParallelism = KdfParams(m = 19_456, t = 2, p = 0, salt = ByteArray(0))

        assertTrue(
            lowerParallelism.isBelowCurrentFloor(),
            "parallelism zero is below the floor of 1",
        )
    }

    @Test
    fun `params above the floor are not below it`() {
        val stronger = KdfParams(m = 32_768, t = 3, p = 4, salt = ByteArray(0))

        assertFalse(stronger.isBelowCurrentFloor(), "more than the floor is not below it")
    }

    @Test
    fun `upgradeToFloor raises only the parameters that are below the floor`() {
        val stronger = KdfParams(m = 32_768, t = 3, p = 4, salt = ByteArray(0))

        val upgraded = stronger.upgradeToFloor()

        // The stronger values survive; a strict copy() would have left them but a strict
        // override would have clobbered them. maxOf() is the contract.
        assertEquals(32_768, upgraded.m, "memory is preserved when already at or above the floor")
        assertEquals(3, upgraded.t, "iterations are preserved when already above the floor")
        assertEquals(4, upgraded.p, "parallelism is preserved when already above the floor")
    }

    @Test
    fun `upgradeToFloor brings a below-floor value up to the floor exactly`() {
        val weaker = KdfParams(m = 1024, t = 1, p = 1, salt = ByteArray(16) { it.toByte() })

        val upgraded = weaker.upgradeToFloor()

        assertEquals(19_456, upgraded.m)
        assertEquals(2, upgraded.t)
        assertEquals(1, upgraded.p)
        // The salt is part of the identity: upgrading parameters must NOT change the salt,
        // otherwise a rotated file would not derive the same key.
        assertTrue(upgraded.salt.contentEquals(weaker.salt), "the salt survives the upgrade")
    }

    @Test
    fun `equals compares the salt byte-by-byte, not by reference`() {
        val a = KdfParams(19_456, 2, 1, salt = byteArrayOf(1, 2, 3))
        val b = KdfParams(19_456, 2, 1, salt = byteArrayOf(1, 2, 3))
        val c = KdfParams(19_456, 2, 1, salt = byteArrayOf(1, 2, 4))

        // Two ByteArray instances with the same contents are equal but not the same instance;
        // without contentEquals, the data class' generated equals() would compare references.
        assertNotEquals(a.salt, b.salt, "sanity: the two salts are not the same object")
        assertEquals(a, b, "KdfParams.equals must compare salts by content")
        assertNotEquals(a, c, "one byte of difference in the salt is enough to make them unequal")
    }

    @Test
    fun `hashCode uses contentHashCode on the salt, so equal params hash equally`() {
        val a = KdfParams(19_456, 2, 1, salt = byteArrayOf(7, 8, 9))
        val b = KdfParams(19_456, 2, 1, salt = byteArrayOf(7, 8, 9))

        assertEquals(
            a.hashCode(),
            b.hashCode(),
            "two equal KdfParams must hash the same; otherwise a HashMap lookup after a " +
                "round-trip would miss",
        )
    }
}
