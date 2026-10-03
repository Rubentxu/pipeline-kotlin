package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.StageOption
import dev.rubentxu.pipeline.v2.dsl.OptionsScope
import dev.rubentxu.pipeline.v2.dsl.OptionsSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S3-R1-B — one timeout bound, owned by one authority, enforced at every door.
 *
 * ## What the S3.3 receipt claimed and the code did not do
 *
 * The receipt recorded that overflow was "deleted". It was not deleted; it was
 * moved. Three doors admitted a value the next door could not survive:
 *
 * ```
 * OptionsScope.timeout(seconds)   require(seconds > 0)          // no upper bound
 * OptionsSpec(timeout = …)        no init at all                // public model, no door
 * OptionsScope.timeout (property) public var, bypasses the fun   // a third door
 *        ↓
 * DslCompiledPipelineCompiler.toOptions()
 *        Math.multiplyExact(seconds, 1_000L)                    // ArithmeticException
 * ```
 *
 * So `options { timeout(Long.MAX_VALUE) }` compiled, and the exception surfaced far from
 * the author, in a compiler, with a message about `long overflow` that names neither the
 * option nor the author. The `OptionsSpec` KDoc — "Invalid surface is unrepresentable" —
 * was true only for authors who went through the DSL function, which is a claim about
 * the facade and not about the model.
 *
 * ## The law this suite pins
 *
 * A stage timeout is a duration in SECONDS whose millisecond form must fit a `Long`.
 * That is one bound, so it is stated once and consulted at every door. With the bound in
 * place, `Math.multiplyExact` becomes a mechanically unreachable assertion rather than a
 * live crash site, and `StageOption.Timeout`'s own positivity invariant is unreachable
 * for DSL-authored values.
 *
 * Two directions are pinned, because the cheap fix gets one of them wrong: the bound
 * must REJECT the unreachable values without rejecting every value.
 */
class S3R1StageTimeoutBoundaryTest {

    /** The largest whole number of seconds whose millisecond form cannot overflow. */
    private val maxSeconds = Long.MAX_VALUE / 1_000L

    /**
     * Assert [block] is refused, naming [subject] when it is not.
     *
     * A local helper because this JUnit binding has no
     * `assertThrows(Class, String, Executable)` overload, and a loop over
     * boundary values needs the failing value in the message or the diagnosis
     * is useless.
     */
    private fun assertRefused(subject: String, block: () -> Unit): IllegalArgumentException {
        val outcome = runCatching(block)
        assertTrue(
            outcome.isFailure && outcome.exceptionOrNull() is IllegalArgumentException,
            "$subject must be refused with IllegalArgumentException, but it was " +
                "${if (outcome.isSuccess) "ACCEPTED" else "threw ${outcome.exceptionOrNull()}"}",
        )
        return outcome.exceptionOrNull() as IllegalArgumentException
    }

    // ------------------------------------------------------------------
    // 1. The DSL door
    // ------------------------------------------------------------------

    @Test
    fun `the DSL refuses a timeout whose millisecond form cannot exist`() {
        // This is the exact payload from the review. Before the fix it passed
        // `require(seconds > 0)` and detonated later in the compiler.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            OptionsScope().apply { timeout(Long.MAX_VALUE) }
        }
        assertTrue(
            failure.message!!.contains("timeout("),
            "the diagnostic must name the construct the author wrote: ${failure.message}",
        )
        assertTrue(
            !failure.message!!.contains("long overflow"),
            "the diagnostic must not leak the compiler's arithmetic message: ${failure.message}",
        )
    }

    @Test
    fun `the DSL refuses the values just past the bound, not just the absurd one`() {
        for (seconds in listOf(maxSeconds + 1, Long.MAX_VALUE / 1_000L + 1000, Long.MAX_VALUE)) {
            assertRefused("options { timeout($seconds) }") { OptionsScope().apply { timeout(seconds) } }
        }
    }

    @Test
    fun `the DSL still refuses non-positive timeouts`() {
        // Pre-existing behaviour, pinned so a bound cannot be added by replacing
        // the positivity check instead of adding to it.
        for (seconds in listOf(0L, -1L, -30L)) {
            assertRefused("options { timeout($seconds) }") { OptionsScope().apply { timeout(seconds) } }
        }
    }

    // ------------------------------------------------------------------
    // 2. The MODEL door — the one the review said was wide open
    // ------------------------------------------------------------------

    @Test
    fun `the public model refuses an overflowing timeout directly`() {
        // Bypasses the DSL entirely, exactly as a consumer of the typed model can.
        for (timeout in listOf(Long.MAX_VALUE, maxSeconds + 1, 0L, -5L)) {
            assertRefused("OptionsSpec(timeout = $timeout)") { OptionsSpec(timeout = timeout) }
        }
    }

    @Test
    fun `the public model accepts the values it should`() {
        // The other direction. A bound that rejects the maximum is a bound in the
        // wrong place, and it would look identical to the defect it replaced.
        assertNull(OptionsSpec().timeout, "an absent timeout is a valid absence")
        assertEquals(1L, OptionsSpec(timeout = 1L).timeout, "the smallest real deadline")
        assertEquals(
            maxSeconds,
            OptionsSpec(timeout = maxSeconds).timeout,
            "the bound is INCLUSIVE: the largest safe value must be accepted",
        )
    }

    // ------------------------------------------------------------------
    // 3. The third door nobody counted: a public property beside the function
    // ------------------------------------------------------------------

    @Test
    fun `the timeout property cannot be assigned around the validating function`() {
        // `OptionsScope` declared `var timeout: Long?` next to `fun timeout(seconds)`.
        // Inside `options { … }` the scope is the receiver, so `timeout = 5` was a
        // third way in that no diagnostic ever guarded. Asserted as a REFLECTIVE
        // fact rather than a compile error, because a compile error cannot be a test:
        // if there is no public setter, the bypass does not exist.
        val setter = OptionsScope::class.java.methods.firstOrNull { it.name == "setTimeout" }
        assertNull(
            setter,
            "OptionsScope must not expose a public timeout setter: it is a third door that " +
                "bypasses the validating function. Assigning through it is how an " +
                "unvalidated value reaches build().",
        )
    }

    // ------------------------------------------------------------------
    // 4. End to end — the crash the receipt said was deleted
    // ------------------------------------------------------------------

    @Test
    fun `an overflowing timeout is refused at the author boundary, not in the compiler`() {
        // The whole point of the slice. The failure must arrive while the author is
        // still inside the `options { }` block — one step EARLIER than the compiler
        // is acceptable, and is in fact what the fix produces, because the bound
        // now lives in the DSL rather than being rediscovered downstream.
        val build = {
            pipeline {
                stages {
                    stage("build") {
                        options { timeout(Long.MAX_VALUE) }
                        echo("hi")
                    }
                }
            }
        }

        val atAuthorBoundary = assertRefused("options { timeout(Long.MAX_VALUE) }") { build() }
        assertTrue(
            atAuthorBoundary.message!!.contains("timeout("),
            "the diagnostic must name the construct: ${atAuthorBoundary.message}",
        )

        // And the end-to-end path, which must fail the same way rather than reach
        // the compiler and raise ArithmeticException. assertThrows on
        // IllegalArgumentException IS the arithmetic assertion: ArithmeticException
        // does not extend it, so `long overflow` fails this test by wrong type.
        val spec = runCatching(build).getOrNull()
        assertRefused("options { timeout(Long.MAX_VALUE) } + compile") {
            DslCompiledPipelineCompiler.compile(
                spec = requireNotNull(spec ?: build()),
                sourcePath = "s3r1.pipeline.kts",
                sourceContent = "pipeline { }",
                pluginLockDigest = Digest("lock"),
            )
        }
    }

    @Test
    fun `a valid timeout still compiles to the typed carrier`() {
        // The counter-test for every bound above: the fix must refuse the
        // unreachable, not the merely large.
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = pipeline {
                stages {
                    stage("build") {
                        options { timeout(30) }
                        echo("hi")
                    }
                }
            },
            sourcePath = "s3r1-ok.pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        assertEquals(
            listOf(StageOption.Timeout(30_000L)),
            compiled.stages.single().options,
        )
    }

    @Test
    fun `the bound agrees at both doors for every value on its edge`() {
        // A bound stated twice is two bounds. This walks the interesting values
        // and asserts the DSL door and the MODEL door accept and refuse the SAME
        // set, which is the property that was violated: `OptionsScope` checked
        // positivity and `OptionsSpec` checked nothing.
        val interesting = listOf(
            0L, 1L, 30L, 1_000L, maxSeconds - 1, maxSeconds, maxSeconds + 1,
            Long.MAX_VALUE / 1_000L * 1_000L, Long.MAX_VALUE,
        )
        for (seconds in interesting) {
            val dslAccepts = runCatching { OptionsScope().apply { timeout(seconds) } }.isSuccess
            val modelAccepts = runCatching { OptionsSpec(timeout = seconds) }.isSuccess
            assertEquals(
                dslAccepts,
                modelAccepts,
                "seconds=$seconds is accepted by one door and refused by the other. Two " +
                    "independent bounds is exactly how the overflow reached the compiler.",
            )
            if (dslAccepts) {
                assertTrue(
                    seconds in 1..maxSeconds,
                    "seconds=$seconds is accepted but is not convertible to milliseconds",
                )
            }
        }
    }

    @Test
    fun `an accepted timeout can always be projected to milliseconds`() {
        // The mechanical claim: for every value the model accepts, the conversion
        // the compiler performs cannot throw and cannot violate StageOption's own
        // invariant. This is what makes `multiplyExact` an assertion rather than a
        // live crash site.
        for (seconds in listOf(1L, 30L, 1_000L, maxSeconds)) {
            val spec = OptionsSpec(timeout = seconds)
            val milliseconds = requireNotNull(spec.timeout) * 1_000L
            assertNotNull(milliseconds)
            assertTrue(
                milliseconds > 0,
                "seconds=$seconds yields $milliseconds, which StageOption.Timeout refuses",
            )
            assertTrue(
                milliseconds in 1..Long.MAX_VALUE,
                "seconds=$seconds overflows a Long in milliseconds",
            )
        }
    }
}
