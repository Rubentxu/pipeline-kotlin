package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S3-R1-A — the `agent` requirement codec must be TOTAL, and totality is a
 * claim about behaviour under hostile input, not about a happy path.
 *
 * ## Why this suite exists separately from the S3.1 codec suite
 *
 * [ExecutionTargetRequirementCodecTest] covers round trips and truncated
 * payloads. It passed 9/9 while the codec still had two reachable failure
 * modes, because neither had a test:
 *
 * ```
 * "L 0"  -> readLabels -> readStrings(0) -> empty list -> LocalLabels(emptySet())
 *           -> require(labels.isNotEmpty()) THROWS IllegalArgumentException
 * "C 0"  -> the same shape through CapabilitySet
 * ```
 *
 * The codec's own KDoc claims "Total, and it never throws on user input", and
 * `BeforeStageDirectiveEngine.decode()` calls `decodeAny` with no guard, so
 * that exception crossed the BEFORE_STAGE seam instead of becoming a
 * [DirectiveDecodeResult.Malformed] the engine could deny on. The full S3.4
 * gate could not find this: it runs the tests that exist, and no test existed.
 *
 * ## The two properties pinned
 *
 *  1. **No input makes `decode` throw.** Every malformed payload is a VALUE.
 *     Asserted by calling `decode` directly with no `assertThrows` wrapper, so
 *     a throw fails the test rather than being caught and reported as a value.
 *  2. **No input makes the decoder allocate from a claimed arity.** A payload
 *     may declare a child count of `Int.MAX_VALUE`; the decoder must discover
 *     the payload is short BEFORE reserving anything for that many entries.
 */
class S3R1AgentCodecTotalityTest {

    private fun assertMalformed(encoded: String, because: String) {
        val result = runCatching { ExecutionTargetRequirementCodec.decode(encoded) }
        assertTrue(
            result.isSuccess,
            "$because: decode(\"$encoded\") THREW ${result.exceptionOrNull()?.let { it::class.simpleName }} " +
                "instead of returning a value. The codec's contract is that every failure is a " +
                "DirectiveDecodeResult, so a throw here crosses BeforeStageDirectiveEngine as an " +
                "exception rather than a denial.",
        )
        assertTrue(
            result.getOrNull() is DirectiveDecodeResult.Malformed,
            "$because: decode(\"$encoded\") returned ${result.getOrNull()} — expected Malformed",
        )
    }

    // ------------------------------------------------------------------
    // 1. An empty collection cannot be a LocalLabels / CapabilitySet
    // ------------------------------------------------------------------

    @Test
    fun `an arity of zero is Malformed, not an exception from the case invariant`() {
        // The exact payload that threw. `LocalLabels` and `CapabilitySet` both
        // `require` a non-empty set, so building them from an empty decoded
        // list hit a precondition instead of producing a typed failure.
        assertMalformed("L 0", "LocalLabels cannot be empty")
        assertMalformed("C 0", "CapabilitySet cannot be empty")
    }

    @Test
    fun `an arity of zero with trailing bytes is Malformed for the right reason`() {
        // Belt and braces: even with something after the count, the decoder
        // must not construct the case first and check afterwards.
        assertMalformed("L 0 1:linux", "LocalLabels cannot be empty, even with trailing bytes")
        assertMalformed("C 0 1:x", "CapabilitySet cannot be empty, even with trailing bytes")
    }

    // ------------------------------------------------------------------
    // 2. A claimed arity must never drive an allocation
    // ------------------------------------------------------------------

    @Test
    fun `a huge claimed arity is Malformed without reserving room for it`() {
        // `readStrings(count)` used to call `ArrayList<String>(count)` BEFORE
        // proving the elements exist, so this payload asked the JVM to reserve
        // room for ~2^31 references from a five-character string. The correct
        // order is: prove the payload is long enough, or return a value.
        assertMalformed("L 2147483647 ", "a huge claimed arity must not preallocate")
        assertMalformed("C 2147483647 ", "a huge claimed arity must not preallocate")
    }

    @Test
    fun `a moderately large but unsatisfiable arity is Malformed`() {
        // The same class at a size that does not exhaust the heap but is still
        // far larger than any plausible pipeline, so a size-based guard cannot
        // be the only thing standing between the payload and an allocation.
        assertMalformed("L 1000000 1:a", "an unsatisfiable large arity must not preallocate")
        assertMalformed("C 1000000 1:a", "an unsatisfiable large arity must not preallocate")
    }

    @Test
    fun `a huge declared string length is Malformed without overflowing the bounds check`() {
        // The TWIN of the arity defect, in `readString` rather than
        // `readStrings`, and it is an integer-overflow defect rather than an
        // allocation one:
        //
        // ```
        // if (index + declared > bytes.size) return null
        // ```
        //
        // `declared` comes from the payload and is only bounded by `toIntOrNull`.
        // With index=5 and declared=Int.MAX_VALUE the addition wraps negative, the
        // bounds check passes, and the cursor hands `String(bytes, 5, MAX_VALUE, …)`
        // a range that does not exist. The correct comparison subtracts instead,
        // because `bytes.size - index` can never be negative.
        assertMalformed("L 1 2147483647:x", "a huge declared length must not overflow the bound")
        assertMalformed("C 1 2147483647:x", "a huge declared length must not overflow the bound")
        assertMalformed("R 2147483647:x", "a huge declared length must not overflow the bound")
    }

    @Test
    fun `an arity larger than the remaining payload is rejected on the count alone`() {
        // 3 labels declared, 1 supplied. The decoder must notice from the
        // byte budget that three length-prefixed entries cannot follow, rather
        // than building one and discovering the shortfall by trial.
        assertMalformed("L 3 5:linux", "a count larger than the payload is Malformed")
    }

    // ------------------------------------------------------------------
    // 3. Totality holds for the whole hostile surface, in one assertion
    // ------------------------------------------------------------------

    @Test
    fun `no hostile payload makes decode throw`() {
        // A single sweep, so a future case added to the codec cannot be
        // exempted from totality by simply not being listed here: the list is
        // an inventory, and property 1 above is what enforces the rule. This
        // test exists to make the CURRENT surface explicit and to fail loudly
        // if a new case is added without a totality canary for it.
        val hostile = listOf(
            "", " ", "A", "A extra", "Z", "L", "C", "R", "L ", "C ", "R ",
            "L 0", "C 0", "L -1", "C -1", "L x", "C x", "L 1", "C 1",
            "L 1 ", "C 1 ", "R 0:", "R :x", "L 1 0:", "C 1 0:",
            "L 1 1: ", "L 1 99:x", "C 1 99:x", "L 2 1:a", "C 2 1:a",
            "L 2147483647 ", "C 2147483647 ", "L 99999999999999999999",
            "C 99999999999999999999", "L 1 1:a 1:b", "C 1 1:a 1:b",
            "L 1 2147483647:x", "C 1 2147483647:x", "R 2147483647:x",
            "L 1 2147483646:xx", "C 1 -1:x", "R -1:x",
            "l 1 1:a", "c 1 1:a", "r 1:a", "A\n", "L\t1 1:a",
        )
        for (payload in hostile) {
            val outcome = runCatching { ExecutionTargetRequirementCodec.decode(payload) }
            assertTrue(
                outcome.isSuccess,
                "decode(${payload.replace("\n", "\\n").replace("\t", "\\t")}) threw " +
                    "${outcome.exceptionOrNull()?.let { it::class.simpleName }}: " +
                    "${outcome.exceptionOrNull()?.message}. Totality is the codec's stated contract.",
            )
        }
    }

    // ------------------------------------------------------------------
    // 4. The well-formed shapes still decode — totality must not become refusal
    // ------------------------------------------------------------------

    @Test
    fun `fixing totality did not turn valid payloads into refusals`() {
        // The other direction, and the one a careless fix breaks: rejecting the
        // empty set is correct, rejecting every arity is not. A single label is
        // still the minimum valid requirement.
        val single = ExecutionTargetRequirementCodec.decode("L 1 5:linux")
        assertTrue(single is DirectiveDecodeResult.Decoded, "a single label is valid: $single")
        assertEquals(
            setOf(AgentLabel("linux")),
            (single as DirectiveDecodeResult.Decoded).input.let {
                (it as ExecutionTargetRequirement.LocalLabels).labels
            },
        )
        assertTrue(
            ExecutionTargetRequirementCodec.decode("A") is DirectiveDecodeResult.Decoded,
            "LocalAny has no arity and must remain decodable",
        )
    }
}
