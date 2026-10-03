package dev.rubentxu.pipeline.v2.domain.directive

import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S3.1 — the `agent` requirement codec contract.
 *
 * A codec for a CONSTRAINT has a stricter duty than one for a convenience
 * value: a round trip that loses or weakens a requirement means the stage runs
 * somewhere the author did not choose, with no error anywhere. So the negative
 * cases here are as important as the positive ones — every malformed shape must
 * come back as a typed [DirectiveDecodeResult.Malformed] and never as a
 * half-built requirement, and never as an exception.
 */
class ExecutionTargetRequirementCodecTest {

    private fun decoded(encoded: String): ExecutionTargetRequirement? =
        (ExecutionTargetRequirementCodec.decode(encoded) as? DirectiveDecodeResult.Decoded)?.input

    private fun malformed(encoded: String): String? =
        (ExecutionTargetRequirementCodec.decode(encoded) as? DirectiveDecodeResult.Malformed)?.reason

    // ------------------------------------------------------------------
    // Round trip: every case of the sealed hierarchy
    // ------------------------------------------------------------------

    @Test
    fun `LocalAny round trips`() {
        val requirement = ExecutionTargetRequirement.LocalAny
        val encoded = ExecutionTargetRequirementCodec.encode(requirement)
        assertEquals("A", encoded, "LocalAny is the shortest legal encoding and must be exact")
        assertEquals(requirement, decoded(encoded))
    }

    @Test
    fun `LocalLabels round trips and carries every label`() {
        val requirement = ExecutionTargetRequirement.LocalLabels(
            setOf(AgentLabel("linux"), AgentLabel("docker"), AgentLabel("ssd")),
        )
        val decoded = decoded(ExecutionTargetRequirementCodec.encode(requirement))
        assertEquals(requirement, decoded)
    }

    @Test
    fun `CapabilitySet round trips and carries every capability key`() {
        val requirement = ExecutionTargetRequirement.CapabilitySet(
            setOf(StepCapability("http.transport"), StepCapability("network.egress")),
        )
        val decoded = decoded(ExecutionTargetRequirementCodec.encode(requirement))
        assertEquals(requirement, decoded)
    }

    @Test
    fun `Remote round trips, because it must be refusable with a good diagnostic`() {
        // Remote is refused at RESOLUTION, not at the syntax level. Encoding it
        // has to work, or the author gets a bare Kotlin signature error instead
        // of a message explaining that remote allocation arrives in RP-8.
        val requirement = ExecutionTargetRequirement.Remote(RemoteSelector("tcp://build-01:9000"))
        assertEquals(requirement, decoded(ExecutionTargetRequirementCodec.encode(requirement)))
    }

    // ------------------------------------------------------------------
    // Encoding properties the replay comparison depends on
    // ------------------------------------------------------------------

    @Test
    fun `encoding is canonical regardless of declaration order`() {
        // Two authors writing the same label set in a different order must
        // produce identical bytes, or a replay comparison over encoded
        // directives reports drift that does not exist.
        val a = ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("linux"), AgentLabel("docker")))
        val b = ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("docker"), AgentLabel("linux")))
        assertEquals(
            ExecutionTargetRequirementCodec.encode(a),
            ExecutionTargetRequirementCodec.encode(b),
            "a Set has no order, so the encoding must impose one",
        )
    }

    @Test
    fun `labels containing spaces and punctuation survive intact`() {
        // Length prefixing exists precisely so there is no escaping rule to get
        // wrong. A label with a space, a colon and a dot must round trip.
        val nasty = ExecutionTargetRequirement.LocalLabels(
            setOf(AgentLabel("linux && docker"), AgentLabel("zone:a.b.c"), AgentLabel("  padded  ")),
        )
        val decoded = decoded(ExecutionTargetRequirementCodec.encode(nasty))
        assertEquals(nasty, decoded, "length-prefixed strings must make any character legal")
    }

    @Test
    fun `multi byte labels are length prefixed by bytes not characters`() {
        // The length is compared byte-for-byte during replay, so a character
        // count would shift every following offset for a non-ASCII label.
        val requirement = ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("ñandú-ñ")))
        assertEquals(requirement, decoded(ExecutionTargetRequirementCodec.encode(requirement)))
    }

    // ------------------------------------------------------------------
    // Negative: every malformed shape is a value, never an exception
    // ------------------------------------------------------------------

    @Test
    fun `malformed inputs are typed failures and never throw`() {
        val hostile = listOf(
            "" to "empty",
            "   " to "whitespace only",
            "X" to "unknown tag",
            "L" to "labels without arity",
            "L 2 3:abc" to "labels with arity but too few entries",
            "L notanumber 3:abc" to "non numeric arity",
            "L 1 4:abc" to "declared length does not match content",
            "L 1 noColonHere" to "entry without a length separator",
            "L 1 0:" to "blank label",
            "C" to "capabilities without arity",
            "C 1 4:abc" to "capability with mismatched length",
            "C 1 0:" to "blank capability key",
            "R" to "remote without a selector",
            "R 99:short" to "remote selector with a wrong declared length",
            "R 0:" to "blank remote selector",
            "A extra" to "trailing tokens after a complete requirement",
            "L 1 3:abc extra" to "trailing tokens after a label set",
        )

        for ((input, description) in hostile) {
            val result = runCatching { ExecutionTargetRequirementCodec.decode(input) }
            assertTrue(
                result.isSuccess,
                "decode('$input') [$description] must not throw; it threw ${result.exceptionOrNull()}",
            )
            val reason = (result.getOrNull() as? DirectiveDecodeResult.Malformed)?.reason
            assertTrue(
                reason != null,
                "decode('$input') [$description] must be Malformed, but returned " +
                    "${result.getOrNull()}",
            )
            assertTrue(
                reason!!.contains("agent requirement"),
                "the reason must name the construct so a denial is diagnosable: $reason",
            )
        }
    }

    @Test
    fun `a truncated payload cannot masquerade as a valid shorter requirement`() {
        // "L 1 3:abc extra" declares one label and delivers it; the danger is a
        // payload that under-delivers while still parsing, which would silently
        // weaken the constraint. Arity plus length closes both.
        assertEquals(null, decoded("L 2 3:abc"), "arity 2 with one entry must not decode")
        assertEquals(null, decoded("L 1 9:abc"), "an over-declared length must not decode")
    }
}
