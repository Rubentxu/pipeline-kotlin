package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PK-0-57-RC1 / ADR-PK1-OUTPUT-FRAMING — matrix tests for
 * [OutputProtocol.negotiate].
 *
 * The ADR specifies an INTERSECTION-BASED rule with client priority
 * and fail-closed on no intersection. These tests pin every case the
 * roadmap calls out: empty list → DEFAULT, valid-only, all-unknown,
 * mixed valid+invalid in both orders, duplicates, blank tokens.
 * They are the regression net for the negotiator defect the
 * roadmap of 2026-10-13 names explicitly.
 *
 * The resolver lives in [OutputProtocol.negotiate] (a companion-object
 * helper), so this test is a pure unit test: no subprocess, no
 * Gradle, no process spawn. The integration with the CLI envelope
 * lives in `negotiateOutputProtocolOrExit` (Main.kt) and is covered
 * by the existing `ProcessOutputDecoderTest` and `CliContractTestKitTest`
 * for the happy paths, plus any subprocess matrix the gates run.
 */
class OutputProtocolNegotiationTest {

    @Test
    fun `empty list returns DEFAULT with no opt-in`() {
        // No --output-protocol flag at all: behaviour must be
        // identical to the R0.3 release.
        assertEquals(OutputProtocol.DEFAULT, OutputProtocol.negotiate(emptyList()))
        assertEquals(OutputProtocol.Published, OutputProtocol.DEFAULT)
    }

    @Test
    fun `single valid id resolves to that protocol`() {
        assertEquals(OutputProtocol.Published, OutputProtocol.negotiate(listOf("pipelinek.output/1")))
        assertEquals(OutputProtocol.SeparatedV2, OutputProtocol.negotiate(listOf("pipelinek.output/2")))
    }

    @Test
    fun `first valid id wins when valid precedes invalid`() {
        // [válido,inválido] → first (Published) wins; invalid is
        // skipped silently.
        val result = OutputProtocol.negotiate(listOf("pipelinek.output/1", "pipelinek.output/bogus"))
        assertEquals(OutputProtocol.Published, result)
    }

    @Test
    fun `first valid id wins when invalid precedes valid`() {
        // [inválido,válido] → invalid is skipped; second valid
        // (SeparatedV2) is the chosen intersection.
        val result = OutputProtocol.negotiate(listOf("pipelinek.output/bogus", "pipelinek.output/2"))
        assertEquals(OutputProtocol.SeparatedV2, result)
    }

    @Test
    fun `all unknown ids produce null fail-closed`() {
        // The CLI must exit 2 with a typed envelope listing the
        // supported ids. Returning null from the resolver is the
        // trigger for that envelope; the wrapper in Main.kt owns
        // the envelope itself.
        val result = OutputProtocol.negotiate(listOf("pipelinek.output/bogus", "output/v0", ""))
        assertNull(result)
    }

    @Test
    fun `duplicates collapse to first occurrence priority`() {
        // Two copies of Published before SeparatedV2: Published
        // wins by priority. Two copies of SeparatedV2 before
        // Published: SeparatedV2 wins.
        assertEquals(
            OutputProtocol.Published,
            OutputProtocol.negotiate(listOf("pipelinek.output/1", "pipelinek.output/1", "pipelinek.output/2")),
        )
        assertEquals(
            OutputProtocol.SeparatedV2,
            OutputProtocol.negotiate(listOf("pipelinek.output/2", "pipelinek.output/2", "pipelinek.output/1")),
        )
    }

    @Test
    fun `blank tokens are treated as unknown and skipped`() {
        // The parser rejects blank tokens with `MissingOptionValue`,
        // but if one slips through, the negotiator must not crash
        // and must not match it.
        val result = OutputProtocol.negotiate(listOf("", "   ", "pipelinek.output/2"))
        assertEquals(OutputProtocol.SeparatedV2, result)
    }

    @Test
    fun `priority is strictly the leftmost supported id`() {
        // Long mixed list: only the leftmost supported id matters.
        // Everything to its right is irrelevant for the selection.
        val result = OutputProtocol.negotiate(
            listOf(
                "pipelinek.output/bogus-1",
                "pipelinek.output/bogus-2",
                "pipelinek.output/1",
                "pipelinek.output/2",
                "pipelinek.output/bogus-3",
            ),
        )
        assertEquals(OutputProtocol.Published, result)
    }

    @Test
    fun `legacy wire ids are unknown — fail-closed not silent fallback`() {
        // The pre-fix wire ids (output/v1, output/v2) are NOT in
        // SUPPORTED_IDS. A client still passing them must be
        // rejected with the typed envelope, never silently mapped
        // to the renamed ids. This is the regression net for the
        // exact defect the 2026-10-13 roadmap names.
        val r1 = OutputProtocol.negotiate(listOf("output/v1"))
        val r2 = OutputProtocol.negotiate(listOf("output/v2"))
        assertNull(r1, "legacy id 'output/v1' must NOT match — was renamed to pipelinek.output/1")
        assertNull(r2, "legacy id 'output/v2' must NOT match — was renamed to pipelinek.output/2")
    }

    @Test
    fun `SUPPORTED_IDS contains exactly the pipelinek output family`() {
        // The supported set is the source of truth for the wire
        // contract; the ADR mandates the `pipelinek.*/N` pattern.
        assertEquals(
            listOf("pipelinek.output/1", "pipelinek.output/2"),
            OutputProtocol.SUPPORTED_IDS,
        )
    }

    @Test
    fun `fromId round-trips every supported id`() {
        for (id in OutputProtocol.SUPPORTED_IDS) {
            val resolved = OutputProtocol.fromId(id)
            assertNotNull(resolved, "fromId must resolve supported id '$id'")
            assertTrue(resolved!!.id == id, "resolved protocol's id must equal the input")
        }
    }

    @Test
    fun `fromId returns null for unknown ids`() {
        assertNull(OutputProtocol.fromId("output/v1"))
        assertNull(OutputProtocol.fromId("output/v2"))
        assertNull(OutputProtocol.fromId(""))
        assertNull(OutputProtocol.fromId("pipelinek.output/0"))
        assertNull(OutputProtocol.fromId("pipelinek.output/3"))
        assertNull(OutputProtocol.fromId("separated-v2"))
    }
}