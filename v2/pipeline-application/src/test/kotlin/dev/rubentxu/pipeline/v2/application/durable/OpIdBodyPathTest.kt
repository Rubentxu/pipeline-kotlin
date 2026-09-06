package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for OpId.bodyPath length-prefix encoding (ADR-0066 §1).
 *
 * Validates:
 * - Length-prefix encoding in format()
 * - Round-trip format() → parse()
 * - Empty bodyPath produces byte-identical format to legacy
 * - legacyFormat() adapter for empty bodyPath
 */
class OpIdBodyPathTest {

    @Test
    fun `format produces length-prefixed bodyPath`() {
        val opId = OpId(
            "r1", 0, 2,
            bodyPath = listOf(
                BlockSegment(0, PluginStepId("core.sh")),
                BlockSegment(1, PluginStepId("core.dir"))
            )
        )

        val formatted = opId.format()
        assertTrue(formatted.startsWith("r1-s0-2"), "Format should start with base: $formatted")
        assertTrue(formatted.contains("-bp2-"), "Format should contain length-prefix: $formatted")
        assertTrue(formatted.contains("0:core.sh"), "Format should contain first segment: $formatted")
        assertTrue(formatted.contains("1:core.dir"), "Format should contain second segment: $formatted")
    }

    @Test
    fun `format then parse round-trips equal`() {
        val original = OpId(
            "test-run", 3, 7,
            bodyPath = listOf(
                BlockSegment(0, PluginStepId("core.catchError")),
                BlockSegment(1, PluginStepId("core.sh")),
            )
        )

        val formatted = original.format()
        val parsed = OpId.parse(formatted)

        assertNotNull(parsed, "Parse should succeed for well-formed bodyPath opId")
        assertEquals(original.runId, parsed!!.runId)
        assertEquals(original.stageIndex, parsed.stageIndex)
        assertEquals(original.stepIndex, parsed.stepIndex)
        assertEquals(original.bodyPath.size, parsed.bodyPath.size)
        assertEquals(original.bodyPath[0].encoded, parsed.bodyPath[0].encoded)
        assertEquals(original.bodyPath[1].encoded, parsed.bodyPath[1].encoded)
    }

    @Test
    fun `empty bodyPath produces byte-identical format to legacy`() {
        val opId = OpId("r1", 0, 5)

        val formatted = opId.format()
        val legacy = opId.legacyFormat()

        assertEquals("r1-s0-5", formatted, "Empty bodyPath format should match legacy: $formatted")
        assertEquals("r1-s0-5", legacy, "Legacy format should match: $legacy")
        assertEquals(formatted, legacy, "format() should equal legacyFormat() for empty bodyPath")
    }

    @Test
    fun `format with branch but empty bodyPath produces correct format`() {
        val opId = OpId("run-x", 1, 3, branchIndex = 2)

        val formatted = opId.format()
        val legacy = opId.legacyFormat()

        assertEquals("run-x-s1-3-b2", formatted)
        assertEquals("run-x-s1-3-b2", legacy)
        assertEquals(formatted, legacy)
    }

    @Test
    fun `parse handles opId with bodyPath`() {
        val s = "r1-s0-2-bp2-0:core.sh-1:core.dir"
        val parsed = OpId.parse(s)

        assertNotNull(parsed, "Parse should succeed for bodyPath opId: $s")
        assertEquals("r1", parsed!!.runId)
        assertEquals(0, parsed.stageIndex)
        assertEquals(2, parsed.stepIndex)
        assertEquals(2, parsed.bodyPath.size)
        assertEquals("0:core.sh", parsed.bodyPath[0].encoded)
        assertEquals("1:core.dir", parsed.bodyPath[1].encoded)
    }

    @Test
    fun `parse handles opId without bodyPath (legacy format)`() {
        val s = "my-run-s2-7"
        val parsed = OpId.parse(s)

        assertNotNull(parsed, "Parse should succeed for legacy format: $s")
        assertEquals("my-run", parsed!!.runId)
        assertEquals(2, parsed.stageIndex)
        assertEquals(7, parsed.stepIndex)
        assertTrue(parsed.bodyPath.isEmpty(), "bodyPath should be empty for legacy format")
    }

    @Test
    fun `BlockSegment encoding follows index-pluginId pattern`() {
        val segment = BlockSegment(0, PluginStepId("core.sh"))
        assertEquals("0:core.sh", segment.encoded)

        val segment2 = BlockSegment(42, PluginStepId("core.catchError"))
        assertEquals("42:core.catchError", segment2.encoded)
    }

    @Test
    fun `BlockSegment round-trip through constructor and encoded`() {
        val index = 5
        val pluginId = PluginStepId("core.dir")
        val segment = BlockSegment(index, pluginId)

        val parsed = BlockSegment(segment.encoded)
        assertEquals(segment.encoded, parsed.encoded)
    }
}
