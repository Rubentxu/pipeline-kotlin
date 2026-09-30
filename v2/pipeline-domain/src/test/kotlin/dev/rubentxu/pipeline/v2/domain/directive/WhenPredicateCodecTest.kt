package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * S2-A: the `core.when` argument codec.
 *
 * The codec is the ONLY place the encoded form is understood, so these tests own
 * the contract in both directions:
 *
 *   - ROUND TRIP over every [WhenPredicate] case. This is the property that
 *     actually matters for a gate: whatever the DSL builds must be what the
 *     runtime reads. The first implementation serialized compound predicates to
 *     a multi-line block and re-parsed it with a regex; nesting silently
 *     produced `Malformed`, so a composed gate was DISCARDED — a fail-OPEN
 *     defect, caught only because a round-trip assertion existed.
 *   - TOTALS: every malformed input yields a typed [DirectiveDecodeResult.Malformed]
 *     naming the problem. Nothing throws, and nothing partially decodes.
 */
class WhenPredicateCodecTest {

    private fun decoded(encoded: String): WhenPredicate {
        val result = WhenPredicateCodec.decode(encoded)
        assertTrue(
            result is DirectiveDecodeResult.Decoded,
            "expected '$encoded' to decode but got $result",
        )
        return (result as DirectiveDecodeResult.Decoded).input
    }

    private fun malformed(encoded: String): String {
        val result = WhenPredicateCodec.decode(encoded)
        assertTrue(
            result is DirectiveDecodeResult.Malformed,
            "expected '$encoded' to be Malformed but got $result",
        )
        return (result as DirectiveDecodeResult.Malformed).reason
    }

    private fun assertRoundTrips(predicate: WhenPredicate) {
        val encoded = WhenPredicateEncoder.encode(predicate)
        assertEquals(
            predicate,
            decoded(encoded),
            "round trip changed the meaning of $predicate (encoded as '$encoded')",
        )
    }


    // ---- round trip, every ADT case ----------------------------------------------

    @Test
    @DisplayName("S2A-CODEC-002: every predicate case round trips")
    fun everyCaseRoundTrips() {
        assertRoundTrips(WhenPredicate.AlwaysTrue)
        assertRoundTrips(WhenPredicate.AlwaysFalse)
        assertRoundTrips(WhenPredicate.VariableEquals("DEPLOY_ENV", "prod"))
        assertRoundTrips(WhenPredicate.VariablePresent("TOKEN"))
        assertRoundTrips(WhenPredicate.AllOf(emptyList()))
        assertRoundTrips(WhenPredicate.AnyOf(emptyList()))
        assertRoundTrips(WhenPredicate.Not(WhenPredicate.AlwaysTrue))
        assertRoundTrips(
            WhenPredicate.AllOf(
                listOf(
                    WhenPredicate.VariableEquals("A", "1"),
                    WhenPredicate.VariablePresent("B"),
                ),
            ),
        )
    }

    @Test
    @DisplayName("S2A-CODEC-003: NESTED combinators round trip (the defect the old format had)")
    fun nestedCombinatorsRoundTrip() {
        val nested = WhenPredicate.AllOf(
            listOf(
                WhenPredicate.VariableEquals("A", "1"),
                WhenPredicate.AnyOf(
                    listOf(
                        WhenPredicate.VariablePresent("B"),
                        WhenPredicate.Not(WhenPredicate.VariablePresent("C")),
                    ),
                ),
                WhenPredicate.Not(
                    WhenPredicate.AllOf(
                        listOf(
                            WhenPredicate.AlwaysFalse,
                            WhenPredicate.VariableEquals("D", "4"),
                        ),
                    ),
                ),
            ),
        )

        val encoded = WhenPredicateEncoder.encode(nested)
        // Structural, single-line: no brace matching is possible on this form.
        assertTrue(!encoded.contains("\n"), "the encoding must be a single line, got:\n$encoded")
        assertEquals(nested, decoded(encoded))
    }

    @Test
    @DisplayName("S2A-CODEC-004: values with spaces, braces and quotes survive verbatim")
    fun hostileValuesSurvive() {
        // A length prefix exists precisely so no value needs escaping. These are
        // the values that would break a naive delimiter-based format.
        val hostile = listOf(
            "feature/my branch",
            "a b  c   d",
            "}{ all { any { not {",
            "with \"quotes\" and 'apostrophes'",
            "hash # and comment -- markers",
            "  leading and trailing  ",
            "newline\ninside",
            "unicode ñ 日本語 emoji 🚀",
        )

        hostile.forEach { value ->
            assertRoundTrips(WhenPredicate.VariableEquals("NAME", value))
            assertRoundTrips(WhenPredicate.VariablePresent(value))
        }
    }

    @Test
    @DisplayName("S2A-CODEC-005: a blank variable name is unrepresentable at the source, not caught at decode")
    fun blankVariableNameIsUnrepresentable() {
        // The ADT itself refuses a blank name (defence in depth), so the codec
        // never has to see one. Asserting the invariant AT ITS SOURCE is the
        // honest test: a decode-only assertion would be checking a path that
        // cannot be reached, which is how a real hole stays hidden.
        val rejected = assertThrows<IllegalArgumentException> {
            WhenPredicate.VariableEquals("", "x")
        }
        assertTrue(
            rejected.message?.contains("name") == true,
            "the diagnostic must name the offending field, got: ${rejected.message}",
        )
        assertThrows<IllegalArgumentException> { WhenPredicate.VariableEquals("   ", "x") }
        assertThrows<IllegalArgumentException> { WhenPredicate.VariablePresent("") }
    }

    // ---- totals: malformed inputs ------------------------------------------------

    @Test
    @DisplayName("S2A-CODEC-006: an empty argument is malformed, never a permissive predicate")
    fun emptyArgumentIsMalformed() {
        // An unreadable gate must not become an implicit always-true, which
        // would execute a stage the script meant to withhold.
        malformed("")
        malformed("   ")
    }

    @Test
    @DisplayName("S2A-CODEC-007: an unknown tag is malformed and says which tags exist")
    fun unknownTagIsMalformed() {
        val reason = malformed("Z")
        assertTrue(
            reason.contains("T, F, E, P, A, O, N"),
            "the diagnostic must list the legal tags, got: $reason",
        )
    }

    @Test
    @DisplayName("S2A-CODEC-008: a truncated payload is malformed, never partially decoded")
    fun truncatedPayloadIsMalformed() {
        malformed("A 2 T")                 // declares 2 children, supplies 1
        malformed("A 0 T")                 // declares 0 children, supplies 1
        malformed("N")                     // negation with no child
        malformed("E 1:ONLYNAME")          // equality missing its value
        malformed("P")                     // presence missing its name
        malformed("A notanumber T")        // non-numeric arity
    }

    @Test
    @DisplayName("S2A-CODEC-009: trailing tokens are malformed (exactly one predicate per argument)")
    fun trailingTokensAreMalformed() {
        val reason = malformed("T F")
        assertTrue(reason.contains("trailing"), "the diagnostic must name the surplus, got: $reason")
    }

    @Test
    @DisplayName("S2A-CODEC-010: a lying length prefix is malformed, not silently accepted")
    fun lyingLengthPrefixIsMalformed() {
        // A length prefix that disagrees with the payload means the bytes cannot
        // be trusted. Accepting them would let a truncated value be read as a
        // different, valid one.
        val reason = malformed("E 99:SHORT 1:v")
        assertTrue(reason.contains("length"), "the diagnostic must name the length mismatch, got: $reason")
        malformed("E notanumber:x 1:v")
        malformed("EnoColon 1:v")
        malformed("E -1:x 1:v")
    }

    // ---- empty combinators are decided, not rejected ------------------------------

    @Test
    @DisplayName("S2A-CODEC-011: empty all/any are representable and decided by the evaluator")
    fun emptyCombinatorsAreRepresentable() {
        // `all { }` is vacuously true and `any { }` vacuously false. Rejecting
        // them at the codec would be a second, hidden place making a semantic
        // decision; the evaluator is the only authority for the value.
        assertEquals(WhenPredicate.AllOf(emptyList()), decoded(WhenPredicateEncoder.encode(WhenPredicate.AllOf(emptyList()))))
        assertEquals(WhenPredicate.AnyOf(emptyList()), decoded(WhenPredicateEncoder.encode(WhenPredicate.AnyOf(emptyList()))))
    }

}
