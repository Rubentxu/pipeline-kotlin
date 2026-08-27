package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Tests for BoundPurpose enum.
 * BoundPurpose records how a credential is bound to a step:
 * - ENV: injected via environment variable
 * - FILE: bound to a temp file (reserved for ML-R4.1)
 * - VALUE: returned via returnStdout
 */
@DisplayName("BoundPurpose enum contract tests")
class BoundPurposeTest {

    @Test
    fun `BoundPurpose has exactly ENV, FILE, VALUE values`() {
        val values = BoundPurpose.entries
        assertEquals(3, values.size)
        assertTrue(BoundPurpose.ENV in values)
        assertTrue(BoundPurpose.FILE in values)
        assertTrue(BoundPurpose.VALUE in values)
    }

    @Test
    fun `BoundPurpose FILE is reserved for ML-R4_1 file binding`() {
        // FILE is defined but not used in L4
        assertEquals("FILE", BoundPurpose.FILE.name)
    }

    @Test
    fun `BoundPurpose enum has expected ordinal ordering`() {
        assertEquals(0, BoundPurpose.ENV.ordinal)
        assertEquals(1, BoundPurpose.FILE.ordinal)
        assertEquals(2, BoundPurpose.VALUE.ordinal)
    }
}
