package com.pipeline.v2.application.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Contract tests for [OpId] data class (C-031).
 *
 * Tests the F01 HIGH finding closure: OpId replaces the string-templated
 * `$runId-s$stageIndex-$stepIndex` hidden contract in PipelineRun,
 * providing typed parse/format round-trip with proper error handling.
 */
class OpIdContractTest {

    /**
     * C-031.1: OpId.parse and format round-trip correctly for simple runIds.
     *
     * Verifies that OpId.parse("$runId-s0-3").format() == "$runId-s0-3"
     * for a simple runId without hyphens.
     */
    @Test
    fun `format and parse round-trips for simple runId`() {
        val original = "abc123-s0-3"
        val opId = OpId.parse(original)

        assertNotNull(opId, "parse should succeed for well-formed opId string")
        assertEquals("abc123", opId!!.runId)
        assertEquals(0, opId.stageIndex)
        assertEquals(3, opId.stepIndex)
        assertEquals(original, opId.format())
    }

    /**
     * C-031.2: OpId.parse returns null for malformed strings without throwing.
     *
     * Verifies that OpId.parse("invalid") returns null (no exception),
     * allowing reconciliation to continue gracefully.
     */
    @Test
    fun `parse malformed returns null no throw`() {
        val result = OpId.parse("invalid")
        assertNull(result, "parse should return null for malformed string, not throw")
    }

    /**
     * C-031.3: OpId.parse handles runIds with hyphens correctly.
     *
     * For runId="abc-def", parsing "$abc-def-s0-1" returns OpId("abc-def", 0, 1).
     * This proves the sentinel-collision test: hyphens in runId are safe because
     * the -s prefix unambiguously delimits the stage/step suffix.
     */
    @Test
    fun `parse handles runId with dash`() {
        val original = "abc-def-s0-1"
        val opId = OpId.parse(original)

        assertNotNull(opId, "parse should succeed for runId containing dashes")
        assertEquals("abc-def", opId!!.runId, "runId with dash should parse correctly")
        assertEquals(0, opId.stageIndex)
        assertEquals(1, opId.stepIndex)
        assertEquals(original, opId.format())
    }

    /**
     * Additional test: verify parse handles runId with multiple hyphens.
     */
    @Test
    fun `parse handles runId with multiple hyphens`() {
        val original = "abc-def-ghi-s2-5"
        val opId = OpId.parse(original)

        assertNotNull(opId, "parse should succeed for runId with multiple dashes")
        assertEquals("abc-def-ghi", opId!!.runId)
        assertEquals(2, opId.stageIndex)
        assertEquals(5, opId.stepIndex)
        assertEquals(original, opId.format())
    }
}
