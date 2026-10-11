package example.external.consumer

import dev.rubentxu.pipeline.v2.domain.Capability
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * PK-R1 R1-E / T8 — Range read APIs (PipelineKApiRange / SemVer / Digest / Capability).
 *
 * These are the pure-decider value types. The official plugin apiRange
 * `[0.47.0, 0.52.0)` is the canonical range for the 0.51.0 train, and the
 * public capability IDs the SDK publishes (`output.follow.v1`,
 * `events.follow.v1`) are the strings a consumer advertises in its
 * `WorkerHello`/manifest negotiation. The test below distinguishes:
 *
 *   - CONTRACT — the type exists with the documented field/constructor
 *     shape (compile-time; absence is a build failure).
 *   - CAPABILITY — invoking the documented function returns the documented
 *     value, including the half-open upper bound on the range, the
 *     canonical MAJOR.MINOR.PATCH format, the `sha256:` digest envelope,
 *     and the non-blank capability-id invariant.
 */
class RangeReadApiTest {

    // ── CONTRACT ─────────────────────────────────────────────────────────────

    @Test
    fun contract_semVerExposesMajorMinorPatch() {
        val names = SemVer::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("major" in names)
        assertTrue("minor" in names)
        assertTrue("patch" in names)
        assertEquals(3, SemVer::class.java.declaredFields.size, "SemVer is intentionally frozen")
    }

    @Test
    fun contract_pipelineKApiRangeExposesFromInclusiveAndUntilExclusive() {
        val names = PipelineKApiRange::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("fromInclusive" in names)
        assertTrue("untilExclusive" in names)
        assertEquals(2, names.size, "PipelineKApiRange has exactly two SemVer fields")
    }

    @Test
    fun contract_digestExposesValue() {
        val names = Digest::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("value" in names)
    }

    @Test
    fun contract_capabilityValueClassWrapsAString() {
        // The value class is the published authority for what a capability
        // ID may be. Equality, hashing and rendering derive from the inner
        // String.
        val c = Capability("output.follow.v1")
        assertEquals("output.follow.v1", c.id)
        assertEquals(Capability("output.follow.v1"), c)
    }

    // ── CAPABILITY — pure-decider behaviour ─────────────────────────────────

    @Test
    fun capability_semVerRenderingAndOrdering() {
        val v = SemVer(major = 0, minor = 51, patch = 0)
        assertEquals("0.51.0", v.toString(), "canonical textual form is MAJOR.MINOR.PATCH")
        // Ordering: pure data-class compare on (major, minor, patch).
        assertNotEquals(SemVer(0, 51, 0), SemVer(0, 51, 1))
    }

    @Test
    fun capability_semVerRejectsNegativeComponents() {
        assertThrows<IllegalArgumentException> { SemVer(-1, 0, 0) }
        assertThrows<IllegalArgumentException> { SemVer(0, -1, 0) }
        assertThrows<IllegalArgumentException> { SemVer(0, 0, -1) }
    }

    @Test
    fun capability_digestAcceptsTheSha256Envelope() {
        val hex = "0".repeat(64)
        val d = Digest("sha256:$hex")
        assertEquals("sha256:$hex", d.value)
    }

    @Test
    fun capability_digestRejectsWrongPrefixWrongLengthOrNonHex() {
        assertThrows<IllegalArgumentException> { Digest("md5:${hex64()}") }
        assertThrows<IllegalArgumentException> { Digest("sha256:too-short") }
        assertThrows<IllegalArgumentException> { Digest("sha256:${hex63()}g") }
    }

    @Test
    fun capability_pipelineKApiRangeAcceptsWithinAndRejectsOutside() {
        // Official plugin apiRange for the 0.51.0 train.
        val range = PipelineKApiRange(
            fromInclusive = SemVer(0, 47, 0),
            untilExclusive = SemVer(0, 52, 0),
        )
        assertTrue(range.accepts(SemVer(0, 47, 0)), "lower bound is inclusive")
        assertTrue(range.accepts(SemVer(0, 51, 0)), "runtime version 0.51.0 is within")
        assertTrue(range.accepts(SemVer(0, 51, 3)), "patch within 0.51.x is in range")
        assertEquals(false, range.accepts(SemVer(0, 52, 0)), "upper bound is exclusive")
        assertEquals(false, range.accepts(SemVer(0, 46, 9)), "below the range")
    }

    @Test
    fun capability_pipelineKApiRangeRejectsInvertedOrEmptyRange() {
        assertThrows<IllegalArgumentException> {
            PipelineKApiRange(
                fromInclusive = SemVer(0, 52, 0),
                untilExclusive = SemVer(0, 47, 0),
            )
        }
        assertThrows<IllegalArgumentException> {
            PipelineKApiRange(
                fromInclusive = SemVer(0, 51, 0),
                untilExclusive = SemVer(0, 51, 0),
            )
        }
    }

    @Test
    fun capability_pipelineKApiRangeRendering() {
        val range = PipelineKApiRange(
            fromInclusive = SemVer(0, 47, 0),
            untilExclusive = SemVer(0, 52, 0),
        )
        assertEquals("[0.47.0, 0.52.0)", range.toString())
    }

    @Test
    fun capability_capabilityEqualityDerivesFromId() {
        // Backwards-compatible note from Capability KDoc: a String previously
        // asserted against a capability row remains a valid equality target
        // because the type wraps a String with no additional state.
        val a = Capability("output.follow.v1")
        val b = Capability("output.follow.v1")
        val c = Capability("events.follow.v1")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun capability_capabilityRejectsBlankId() {
        assertThrows<IllegalArgumentException> { Capability("") }
        assertThrows<IllegalArgumentException> { Capability("   ") }
    }

    private fun hex64(): String = (0 until 64).joinToString("") { "a" }

    private fun hex63(): String = (0 until 63).joinToString("") { "a" }
}