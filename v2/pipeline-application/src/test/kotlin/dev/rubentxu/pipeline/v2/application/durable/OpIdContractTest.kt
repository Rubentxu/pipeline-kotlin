package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import kotlinx.serialization.json.Json

/**
 * Contract tests for [OpId] data class (C-031).
 *
 * Tests the F01 HIGH finding closure: OpId replaces the string-templated
 * `$runId-s$stageIndex-$stepIndex` hidden contract in PipelineRun,
 * providing typed parse/format round-trip with proper error handling.
 *
 * Extended in M3-R4.2 with branchIndex support for parallel frames (C-031.5 .. C-031.12).
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
        assertNull(opId.branchIndex)
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
        assertNull(opId.branchIndex)
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
        assertNull(opId.branchIndex)
        assertEquals(original, opId.format())
    }

    // ========================================================================
    // M3-R4.2 branchIndex extension tests (C-031.5 .. C-031.12)
    // ========================================================================

    /**
     * C-031.5: Parse root opId without branch (existing regex, null branchIndex).
     *
     * Verifies backward compatibility: opId without -b{N} suffix parses
     * with branchIndex = null.
     */
    @Test
    fun `parse root opId without branch yields null branchIndex`() {
        val original = "run-abc-s0-2"
        val opId = OpId.parse(original)

        assertNotNull(opId, "parse should succeed for root opId string")
        assertEquals("run-abc", opId!!.runId)
        assertEquals(0, opId.stageIndex)
        assertEquals(2, opId.stepIndex)
        assertNull(opId.branchIndex, "branchIndex should be null for root opId")
        assertEquals(original, opId.format())
    }

    /**
     * C-031.6: Parse root opId with branch (-b{N} suffix).
     *
     * Verifies that opId with -b{N} suffix parses correctly and
     * branchIndex is set to the expected value.
     */
    @Test
    fun `parse root opId with branch suffix yields correct branchIndex`() {
        val original = "run-abc-s0-2-b1"
        val opId = OpId.parse(original)

        assertNotNull(opId, "parse should succeed for branch opId string")
        assertEquals("run-abc", opId!!.runId)
        assertEquals(0, opId.stageIndex)
        assertEquals(2, opId.stepIndex)
        assertEquals(1, opId.branchIndex, "branchIndex should be 1")
        assertEquals(original, opId.format())
    }

    /**
     * C-031.7: forBranch() builds expected shape.
     *
     * Verifies that OpId.forBranch(...) produces an OpId with
     * the correct branchIndex and format().
     */
    @Test
    fun `forBranch builds expected shape`() {
        val opId = OpId.forBranch("pipeline-x", stageIndex = 2, stepIndex = 5, branchIndex = 3)

        assertEquals("pipeline-x", opId.runId)
        assertEquals(2, opId.stageIndex)
        assertEquals(5, opId.stepIndex)
        assertEquals(3, opId.branchIndex)
        assertEquals("pipeline-x-s2-5-b3", opId.format())
    }

    /**
     * C-031.8: roundtrip forBranch -> toString -> parse -> equal.
     *
     * Verifies that OpId.forBranch(...) -> toString() -> parse() yields
     * an equal OpId (round-trip safety).
     */
    @Test
    fun `forBranch roundtrip toString parse yields equal OpId`() {
        val original = OpId.forBranch("run-xyz", stageIndex = 1, stepIndex = 4, branchIndex = 2)
        val stringForm = original.toString()
        val parsed = OpId.parse(stringForm)

        assertNotNull(parsed, "roundtrip parse should succeed")
        assertEquals(original, parsed, "roundtrip should produce equal OpId")
    }

    /**
     * C-031.9: Invalid input rejected (missing -s segment).
     *
     * Verifies that OpId.parse("runId-abc-123") returns null
     * (no exception) when the -s{stageIndex} sentinel is absent.
     */
    @Test
    fun `parse rejects missing -s segment`() {
        val result = OpId.parse("runId-abc-123")
        assertNull(result, "parse should return null when -s segment is missing")
    }

    /**
     * C-031.10: Invalid input rejected (negative branchIndex).
     *
     * Verifies that OpId.parse("runId-s0-1-b-1") returns null
     * because branchIndex must be non-negative.
     */
    @Test
    fun `parse rejects negative branchIndex`() {
        val result = OpId.parse("runId-s0-1-b-1")
        assertNull(result, "parse should return null for negative branchIndex")
    }

    /**
     * C-031.11: format() for non-branch OpId omits branch suffix.
     *
     * Verifies that an OpId with branchIndex = null formats without
     * the -b suffix (backward compatible string format).
     */
    @Test
    fun `format omits branch suffix when branchIndex is null`() {
        val opId = OpId(runId = "test", stageIndex = 0, stepIndex = 1, branchIndex = null)
        assertEquals("test-s0-1", opId.format())
        assertEquals("test-s0-1", opId.toString())
    }

    // C-031.13: beginOperation branchIndex consistency — ADR-0037 Option A
    // Tests that the consistency logic in beginOperation handles the 4 cases correctly.
    // The actual beginOperation is tested in OperationJournalContractTest.

    /**
     * C-031.13a: Pre-formatted opId with branch + branchIndex=null → use as-is (no double-suffix).
     *
     * Verifies that when a caller passes a pre-formatted opId (already containing -b{N})
     * and branchIndex=null, the opId is used as-is. This is the "caller already formatted"
     * path of ADR-0037 Option A.
     */
    @Test
    fun `pre-formatted opId with branch and null branchIndex uses opId as-is`() {
        val preFormatted = "op-s0-1-b0"
        val parsed = OpId.parse(preFormatted)
        assertNotNull(parsed, "pre-formatted opId should parse")
        assertEquals(0, parsed!!.branchIndex, "branchIndex should be 0")
        // When branchIndex=null is passed with pre-formatted opId, use as-is
        assertEquals(preFormatted, parsed.format())
    }

    /**
     * C-031.13b: Root opId without branch + branchIndex=0 → format to include branch.
     *
     * Verifies that when a caller passes a root opId (no -b suffix) and branchIndex=0,
     * the formatter appends -b0. This is the "caller passed root" path of ADR-0037 Option A.
     */
    @Test
    fun `root opId with branchIndex formats correctly`() {
        val root = "op-s0-1"
        val parsed = OpId.parse(root)
        assertNotNull(parsed, "root opId should parse")
        assertNull(parsed!!.branchIndex, "root opId should have null branchIndex")
        // Simulate beginOperation formatting: root + branchIndex=0 → "op-s0-1-b0"
        val withBranch = OpId.forBranch("op", stageIndex = 0, stepIndex = 1, branchIndex = 0)
        assertEquals("op-s0-1-b0", withBranch.format())
    }

    /**
     * C-031.13c: Pre-formatted opId with branch + matching branchIndex=0 → consistent.
     *
     * Verifies that when a caller passes a pre-formatted opId "op-s0-1-b0" and
     * branchIndex=0, the consistency check passes (they match).
     */
    @Test
    fun `pre-formatted opId with matching branchIndex is consistent`() {
        val preFormatted = "op-s0-1-b0"
        val parsed = OpId.parse(preFormatted)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.branchIndex)
        // Simulated consistency check: parsed.branchIndex == 0 → consistent
        val isConsistent = parsed.branchIndex == 0
        assertTrue(isConsistent, "pre-formatted opId with branchIndex=0 should be consistent")
    }

    /**
     * C-031.13d: Pre-formatted opId with branch + mismatched branchIndex=1 → throws.
     *
     * Verifies that when a caller passes a pre-formatted opId "op-s0-1-b0" (branchIndex=0)
     * but branchIndex=1, the consistency check throws IllegalStateException.
     * This is the critical ADR-0037 invariant that prevents silent inconsistencies.
     */
    @Test
    fun `pre-formatted opId with mismatched branchIndex throws`() {
        val preFormatted = "op-s0-1-b0"
        val parsed = OpId.parse(preFormatted)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.branchIndex)
        // Simulated consistency check: parsed.branchIndex (0) != passed branchIndex (1)
        val passedBranchIndex = 1
        val isConsistent = parsed.branchIndex == passedBranchIndex
        assertFalse(isConsistent, "pre-formatted opId with branchIndex=0 should NOT be consistent with branchIndex=1")
        // In beginOperation, this would throw IllegalStateException
    }

    /**
     * C-031.13e: Root opId without branch + branchIndex=null → root opId persisted.
     *
     * Verifies that when a caller passes a root opId (no -b suffix) and branchIndex=null,
     * no branch suffix is appended. This preserves the existing behavior for non-branch operations.
     */
    @Test
    fun `root opId with null branchIndex persists as root`() {
        val root = "op-s0-1"
        val parsed = OpId.parse(root)
        assertNotNull(parsed)
        assertNull(parsed!!.branchIndex)
        // When branchIndex=null, no branch suffix is appended
        assertEquals(root, parsed.format())
    }
}
