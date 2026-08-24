package dev.rubentxu.pipeline.v2.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompatibilityLevelEnumTest {

    @Test
    fun `CompatibilityLevel has 4 values`() {
        val values = CompatibilityLevel.entries
        assertEquals(4, values.size)
    }

    @Test
    fun `NAMING has level 0`() {
        assertEquals(0, CompatibilityLevel.NAMING.level)
        assertEquals("NAMING", CompatibilityLevel.NAMING.name)
    }

    @Test
    fun `SURFACE has level 1`() {
        assertEquals(1, CompatibilityLevel.SURFACE.level)
        assertEquals("SURFACE", CompatibilityLevel.SURFACE.name)
    }

    @Test
    fun `BEHAVIORAL has level 2`() {
        assertEquals(2, CompatibilityLevel.BEHAVIORAL.level)
        assertEquals("BEHAVIORAL", CompatibilityLevel.BEHAVIORAL.name)
    }

    @Test
    fun `MIGRATION has level 3`() {
        assertEquals(3, CompatibilityLevel.MIGRATION.level)
        assertEquals("MIGRATION", CompatibilityLevel.MIGRATION.name)
    }

    @Test
    fun `levels are in ascending order`() {
        assertEquals(0, CompatibilityLevel.NAMING.level)
        assertEquals(1, CompatibilityLevel.SURFACE.level)
        assertEquals(2, CompatibilityLevel.BEHAVIORAL.level)
        assertEquals(3, CompatibilityLevel.MIGRATION.level)
    }
}
