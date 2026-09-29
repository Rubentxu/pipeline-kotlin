package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.PureBuilderProbe
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIf

/**
 * S0-C1 — Pure Builder Consumption Gate.
 *
 * A [dev.rubentxu.pipeline.v2.dsl.StageScope.scmGit] builder is a PURE_BUILDER:
 * it constructs a carrier value and emits nothing. Purity says nothing about
 * whether the CARRIER must be used. Discarding a carrier silently is semantic
 * loss: the author asked for a checkout and got an empty, successful stage.
 *
 * The rule enforced here is generic: any PURE_BUILDER whose manifest row
 * declares `ResultConsumption = MUST_CONSUME` MUST NOT compile when its result
 * is discarded as a statement, and MUST fail before any effect. There is no
 * case for `scmGit` in this test — the gate reads the manifest and the
 * live return type, so a future PURE_BUILDER inherits the behaviour.
 */
@Timeout(value = 300, unit = java.util.concurrent.TimeUnit.SECONDS)
@EnabledIf("isRealHostAvailable")
class FArchS0CPureBuilderConsumptionTest {

    companion object {
        @JvmStatic
        fun isRealHostAvailable(): Boolean = PureBuilderProbe.isAvailable
    }

    // ------------------------------------------------------------------
    // MUST_CONSUME: discarding the carrier must fail before any effect
    // ------------------------------------------------------------------

    @Test
    fun `MUST_CONSUME builder discarded as statement fails before any effect`() {
        val outcome = PureBuilderProbe.compileAndRun(
            """
            pipeline {
                stages {
                    stage("checkout") {
                        scmGit(url = "https://example.invalid/r.git")
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(
            outcome.rejected,
            "a MUST_CONSUME PURE_BUILDER whose result is discarded MUST be rejected, " +
                "but the run succeeded with an empty stage: ${outcome.summary}",
        )
        assertEquals(
            0,
            outcome.stepCount,
            "no effect may be observed when the result is discarded (fail-closed before effects): ${outcome.summary}",
        )
        assertTrue(
            outcome.summary.contains("return value", ignoreCase = true) ||
                outcome.summary.contains("must be used", ignoreCase = true),
            "rejection must name the unconsumed result, not a generic failure: ${outcome.summary}",
        )
    }

    // ------------------------------------------------------------------
    // Purity is preserved: consumption must not turn a builder into a step
    // ------------------------------------------------------------------

    @Test
    fun `consumed builder is not the silent-discard shape`() {
        val outcome = PureBuilderProbe.compileAndRun(
            """
            pipeline {
                stages {
                    stage("checkout") {
                        checkout(scmGit(url = "https://example.invalid/r.git").scm)
                    }
                }
            }
            """.trimIndent(),
        )

        // Purity must survive: consuming the carrier may not turn scmGit into a
        // step emitter. If the shape is admitted, it must be exactly one
        // checkout — never two (the historical duplicate) and never zero.
        //
        // SCOPE (S0-C1 option A, observed): the canonical bridge currently
        // rejects `core.checkout` fail-closed with exit 2, so the consumed
        // shape is NOT admitted today. The gate under test here is the
        // DISCARD shape above; this test only guards the invariant that the
        // consumed shape is never the same thing as the discarded one, and
        // pins the "exactly one checkout" contract for the day the spine
        // admits it. The core.checkout gap is tracked separately (S0-B), not
        // silently widened into this slice.
        if (outcome.admitted) {
            assertEquals(
                1,
                outcome.stepCount,
                "a consumed MUST_CONSUME builder must produce exactly one step, never zero and never two: ${outcome.summary}",
            )
        } else {
            assertTrue(
                !outcome.summary.contains("must be used", ignoreCase = true) &&
                    !outcome.summary.contains("return value", ignoreCase = true),
                "a consumed builder must not be rejected for an UNCONSUMED result: ${outcome.summary}",
            )
        }
    }

    @Test
    fun `git convenience builder consuming scmGit is not the silent-discard shape`() {
        val outcome = PureBuilderProbe.compileAndRun(
            """
            pipeline {
                stages {
                    stage("checkout") {
                        git("https://example.invalid/r.git")
                    }
                }
            }
            """.trimIndent(),
        )

        // `git(..)` is defined as `checkout(scmGit(..).scm)`, so it CONSUMES the
        // carrier. It must therefore never trip the consumption gate. Whatever
        // the bridge decides about core.checkout is a separate concern.
        assertTrue(
            !outcome.summary.contains("must be used", ignoreCase = true) &&
                !outcome.summary.contains("return value", ignoreCase = true),
            "git(..) consumes scmGit(..); the consumption gate must not fire on it: ${outcome.summary}",
        )
    }

    // ------------------------------------------------------------------
    // No global over-reach: an empty stage stays legal
    // ------------------------------------------------------------------

    @Test
    fun `an empty stage without a discarded builder remains legal`() {
        val outcome = PureBuilderProbe.compileAndRun(
            """
            pipeline {
                stages {
                    stage("noop") {
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(
            outcome.admitted,
            "this gate must not reject a genuinely empty stage; it governs discarded results only: ${outcome.summary}",
        )
        assertEquals(0, outcome.stepCount, "an empty stage emits no steps: ${outcome.summary}")
    }

    @Test
    fun `a stage whose builders are consumed stays admitted`() {
        val outcome = PureBuilderProbe.compileAndRun(
            """
            pipeline {
                stages {
                    stage("ok") {
                        echo("hello")
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(outcome.admitted, "unrelated steps must not be affected: ${outcome.summary}")
        assertEquals(1, outcome.stepCount, "echo emits exactly one step: ${outcome.summary}")
    }
}
