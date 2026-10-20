package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `apiRange` is the admission gate's only defence against loading a plugin
 * built for an incompatible host, so its interval arithmetic is worth pinning.
 *
 * The shape this protects against is real, not hypothetical: `HttpPluginDeclaration`
 * used to carry a hand-typed upper bound, a release shipped `apiRange=[0.47.0, 0.53.0)`
 * on a 0.56.0 host, and the runtime's admission pass then refused every official plugin on
 * the distribution that shipped them. The bound is now derived from
 * `rootProject.version`; what remains the domain's job is the interval rule itself.
 */
class PipelineKApiRangeTest {

    private fun v(major: Int, minor: Int, patch: Int) = SemVer(major, minor, patch)

    @Nested
    @DisplayName("the interval is half-open at both ends")
    inner class HalfOpen {

        private val range = PipelineKApiRange(fromInclusive = v(1, 2, 0), untilExclusive = v(2, 0, 0))

        @Test
        fun `the lower bound is inclusive`() {
            // `from` names the first host the plugin supports, so a plugin
            // declaring it must load on exactly that host.
            assertTrue(range.accepts(v(1, 2, 0)), "the from-bound must be accepted")
        }

        @Test
        fun `the upper bound is exclusive`() {
            // The KDoc is explicit: a compatibility range states where a plugin
            // STOPS BEING SUPPORTED, not where it stops being parseable. An
            // inclusive upper bound would keep admitting a plugin one version
            // past the point its author withdrew support.
            assertFalse(range.accepts(v(2, 0, 0)), "the until-bound must be refused")
        }

        @Test
        fun `a host inside the range is accepted`() {
            assertTrue(range.accepts(v(1, 3, 0)))
            assertTrue(range.accepts(v(1, 9, 9)))
        }

        @Test
        fun `a host outside the range is refused`() {
            assertFalse(range.accepts(v(1, 1, 9)), "below the lower bound")
            assertFalse(range.accepts(v(3, 0, 0)), "above the upper bound")
        }
    }

    /**
     * Version comparison must be NUMERIC and component-wise. A lexicographic
     * comparison is the classic mistake here: "0.9.0" > "0.10.0" as text, and
     * the reverse as versions, so the gate would admit exactly the hosts it
     * exists to refuse.
     */
    @Nested
    @DisplayName("ordering is numeric, not lexicographic")
    inner class NumericOrdering {

        @Test
        fun `minor components compare numerically`() {
            val range = PipelineKApiRange(fromInclusive = v(1, 9, 0), untilExclusive = v(1, 10, 0))
            assertTrue(range.accepts(v(1, 9, 5)), "1.9.5 lies inside [1.9.0, 1.10.0)")
            assertFalse(
                range.accepts(v(1, 10, 0)),
                "lexicographic ordering would wrongly admit 1.10.0 by reading '10' < '9'",
            )
        }

        @Test
        fun `patch components compare numerically`() {
            val range = PipelineKApiRange(fromInclusive = v(0, 47, 0), untilExclusive = v(0, 47, 10))
            assertTrue(range.accepts(v(0, 47, 9)))
            assertFalse(range.accepts(v(0, 47, 10)))
        }

        @Test
        fun `a host that outran the declared range is refused`() {
            // The exact shape of the shipped-incident regression: a range whose
            // upper bound trails the host. If this ever passes, the gate has
            // gone fail-OPEN and an incompatible plugin would load.
            val staleRange = PipelineKApiRange(fromInclusive = v(0, 47, 0), untilExclusive = v(0, 53, 0))
            assertFalse(
                staleRange.accepts(v(0, 56, 0)),
                "a host at 0.56.0 must not be admitted by a range ending at 0.53.0",
            )
        }
    }

    @Nested
    @DisplayName("an empty or inverted range cannot be constructed")
    inner class Construction {

        @Test
        fun `an empty range is refused at construction`() {
            // An empty [x, x) would accept nothing, which reads like a valid
            // "compatible with no version" rather than the contradiction it is.
            assertThrows<IllegalArgumentException> {
                PipelineKApiRange(fromInclusive = v(1, 0, 0), untilExclusive = v(1, 0, 0))
            }
        }

        @Test
        fun `an inverted range is refused at construction`() {
            assertThrows<IllegalArgumentException> {
                PipelineKApiRange(fromInclusive = v(2, 0, 0), untilExclusive = v(1, 0, 0))
            }
        }

        @Test
        fun `a range that differs only in patch is still ordered`() {
            // The `require` compares major, then minor, then patch; a range
            // differing only in patch is legal and must not be refused.
            val range = PipelineKApiRange(fromInclusive = v(1, 0, 0), untilExclusive = v(1, 0, 1))
            assertTrue(range.accepts(v(1, 0, 0)))
        }
    }
}
