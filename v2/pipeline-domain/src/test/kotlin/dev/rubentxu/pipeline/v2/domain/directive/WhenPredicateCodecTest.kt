package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The `when` gate codec is a FAIL-CLOSED boundary, so these are the two
 * guarantees worth protecting permanently.
 *
 * `WhenPredicateCodec`'s KDoc records a real defect, not a hypothetical one: the
 * first version serialized a compound predicate to a readable multi-line block
 * and re-parsed it with a regex, which silently failed on a nested combinator
 * and therefore DISCARDED the gate. A discarded gate is fail-OPEN — the stage
 * runs when it should have been held. The format was replaced with prefix
 * notation over length-prefixed strings precisely to make that unrepresentable,
 * and this class is what keeps the replacement honest.
 */
class WhenPredicateCodecTest {

    /**
     * The encoder and the decoder are a single authority split across two
     * files. If they can disagree, a predicate changes meaning between authoring
     * and execution — the same silent-lie class the ADT exists to remove. This
     * enumerates every constructor case and asserts the pair is an identity.
     */
    @Nested
    @DisplayName("the encoder and decoder are exact inverses")
    inner class RoundTrip {

        @Test
        fun `every predicate case survives its own encoding`() {
            val cases = listOf(
                "always true" to WhenPredicate.AlwaysTrue,
                "always false" to WhenPredicate.AlwaysFalse,
                "variable equals" to WhenPredicate.VariableEquals("TARGET", "prod"),
                "variable present" to WhenPredicate.VariablePresent("TARGET"),
                "empty conjunction" to WhenPredicate.AllOf(emptyList()),
                "empty disjunction" to WhenPredicate.AnyOf(emptyList()),
            )
            for ((label, predicate) in cases) {
                val encoded = WhenPredicateEncoder.encode(predicate)
                assertEquals(
                    predicate,
                    (WhenPredicateCodec.decode(encoded) as DirectiveDecodeResult.Decoded).input,
                    "round trip changed a $label predicate (encoded as '$encoded')",
                )
            }
        }

        /**
         * The historical defect, kept as a named case rather than left to a
         * reader of the commit log. Nesting appears at both depths the DSL can
         * build: a compound inside a compound, and a negation wrapping one.
         */
        @Test
        fun `a nested combinator round trips`() {
            val predicate = WhenPredicate.AllOf(
                listOf(
                    WhenPredicate.VariableEquals("TARGET", "prod"),
                    WhenPredicate.AnyOf(
                        listOf(
                            WhenPredicate.Not(WhenPredicate.VariablePresent("SKIP_LINT")),
                            WhenPredicate.VariableEquals("BRANCH", "feature/my branch"),
                        ),
                    ),
                ),
            )
            val encoded = WhenPredicateEncoder.encode(predicate)
            assertEquals(
                predicate,
                (WhenPredicateCodec.decode(encoded) as DirectiveDecodeResult.Decoded).input,
                "the nested payload '$encoded' did not survive its own encoding",
            )
        }

        /**
         * `Cursor.readString`'s KDoc names this case: a branch name may contain
         * a space. A space-delimited token would truncate `feature/my branch` to
         * `feature/my`, which then compares unequal and flips the gate — a
         * silently different value, the worst possible outcome here.
         */
        @Test
        fun `a value containing spaces is read whole`() {
            val predicate = WhenPredicate.VariableEquals("BRANCH", "feature/my branch")
            val encoded = WhenPredicateEncoder.encode(predicate)
            assertEquals(
                predicate,
                (WhenPredicateCodec.decode(encoded) as DirectiveDecodeResult.Decoded).input,
            )
        }

        /**
         * Length prefixes exist so no character needs escaping. If that claim is
         * false, this assertion is the cheapest thing that notices.
         */
        @Test
        fun `values holding delimiter characters survive intact`() {
            for (awkward in listOf("a b c", "brace { } here", "quote \" here", "new\nline", "colon: here", "")) {
                val predicate = WhenPredicate.VariableEquals("NAME", awkward)
                val encoded = WhenPredicateEncoder.encode(predicate)
                assertEquals(
                    predicate,
                    (WhenPredicateCodec.decode(encoded) as DirectiveDecodeResult.Decoded).input,
                    "the value ${awkward.replace("\n", "\\n")} did not survive '$encoded'",
                )
            }
        }
    }

    /**
     * Every unparseable input must be a typed refusal. The alternative — a
     * partial predicate, or an exception escaping into the gate — is the
     * fail-OPEN defect the whole design exists to prevent.
     */
    @Nested
    @DisplayName("an unreadable payload is refused, never guessed")
    inner class FailClosed {

        private fun refuses(encoded: String): String {
            val result = WhenPredicateCodec.decode(encoded)
            assertTrue(
                result is DirectiveDecodeResult.Malformed,
                "expected '$encoded' to be refused, but it decoded to $result",
            )
            return (result as DirectiveDecodeResult.Malformed).reason
        }

        @Test
        fun `an empty argument is refused with a usable message`() {
            // The refusal text is what an author sees, so it must name what a
            // valid payload looks like rather than only reporting absence.
            assertTrue(
                refuses("").contains("no predicate"),
                "the empty-argument refusal should explain what was expected",
            )
        }

        @Test
        fun `an unknown tag is refused`() {
            refuses("Z")
            refuses("A 1 Q")
        }

        @Test
        fun `a truncated compound is refused rather than partially built`() {
            // "A 3" promises three children and supplies one. Accepting a
            // one-child conjunction would silently narrow a three-way gate.
            refuses("A 3 T")
        }

        @Test
        fun `a surplus token after a complete predicate is refused`() {
            // One argument encodes exactly one predicate; a trailing token means
            // the author and the reader disagree about the payload.
            refuses("T F")
        }

        @Test
        fun `a malformed length prefix is refused`() {
            refuses("E six:prod")     // not a number
            refuses("E 6 TARGET 3:pr") // declares more characters than remain
        }

        @Test
        fun `an empty variable name is refused`() {
            // A nameless variable can never be resolved, and resolving it would
            // be Unverifiable rather than false — so it is a refusal, not a case.
            refuses("E 0: 4:prod")
            refuses("P 0:")
        }
    }
}