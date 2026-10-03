package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.PureBuilderProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIf

/**
 * S3.0 — the runtime half of the PURE_BUILDER purity law, on the installed
 * distribution.
 *
 * The structural half lives in `FArchS3PureBuilderPurityTest`, which proves the
 * DSL module cannot reach an event sink, a capability or a process adapter at
 * all. That is the stronger claim, but it is a claim about the CLASSPATH, and a
 * classpath is not a behaviour. This file observes the behaviour, on the same
 * `installDist` binary the H8 ladder certifies, so "a pure builder emits
 * nothing" is a measurement rather than an inference from a dependency graph.
 *
 * The order of these tests is the argument:
 *
 *  - [an empty stage emits only scaffolding] fixes the vocabulary of what a run
 *    that did nothing looks like, and is the baseline every other claim is
 *    compared against;
 *  - [an emitting step is observable] proves the observer can SEE an effect, so
 *    the first test is not passing because the probe is blind;
 *  - [a pure builder emits no effect] then says something, because the observer
 *    was shown to be capable of detecting exactly what it failed to find.
 *
 * A purity fitness without that middle test is the classic vacuous green: zero
 * events observed by a probe that never observes anything.
 */
@Timeout(value = 300, unit = java.util.concurrent.TimeUnit.SECONDS)
@EnabledIf("isRealHostAvailable")
class FArchS3PureBuilderPurityWitnessTest {

    companion object {
        @JvmStatic
        fun isRealHostAvailable(): Boolean = PureBuilderProbe.isAvailable
    }

    /**
     * The complete event vocabulary of a run that does nothing at all.
     *
     * Deliberately an allowlist rather than a denylist. A denylist of "bad"
     * events would pass forever as new event types are added, including a new
     * effect; an allowlist fails the moment a run does something the author did
     * not write, which is the direction that matters.
     *
     * MEASURED, not assumed: an empty stage emits exactly these six kinds on
     * the installed distribution. The first draft of this file guessed that a
     * stage emitted no events of its own and was wrong — StageStarted and
     * StageFinished are real scaffolding, and a fitness that forgot them would
     * have "passed" only because it asserted an empty list against a run that
     * always produces them.
     */
    private val scaffoldingEvents = setOf(
        "CompilationStarted",
        "CompilationFinished",
        "RunStarted",
        "StageStarted",
        "StageFinished",
        "RunFinished",
    )

    // ------------------------------------------------------------------
    // Baseline: what "no effect" looks like
    // ------------------------------------------------------------------

    @Test
    fun `an empty stage emits only scaffolding`() {
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

        assertTrue(outcome.admitted, "an empty stage is a legal run: ${outcome.summary}")

        val beyondScaffolding = outcome.eventKinds.filter { it !in scaffoldingEvents }
        assertEquals(
            0,
            outcome.eventKinds.count { it == "StepStarted" },
            "a stage with no steps must emit no StepStarted: ${outcome.summary}",
        )
        assertEquals(
            emptyList<String>(),
            beyondScaffolding,
            "a stage with no steps must emit only $scaffoldingEvents, but emitted: " +
                beyondScaffolding,
        )
    }

    // ------------------------------------------------------------------
    // The observer must be capable of seeing an effect
    // ------------------------------------------------------------------

    @Test
    fun `an emitting step is observable`() {
        val outcome = PureBuilderProbe.compileAndRun(
            """
            pipeline {
                stages {
                    stage("loud") {
                        echo("hello")
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(outcome.admitted, "echo is a legal step: ${outcome.summary}")
        assertEquals(
            1,
            outcome.eventKinds.count { it == "StepStarted" },
            "the control must show exactly one StepStarted, otherwise the purity witnesses " +
                "below are measuring an observer that cannot see effects: ${outcome.summary}",
        )
        assertTrue(
            outcome.eventKinds.any { it == "EchoOutputCaptured" },
            "the control must show the step's own output event, so the observer is proven to " +
                "resolve per-step events and not just a total count: ${outcome.summary}",
        )
    }

    // ------------------------------------------------------------------
    // The law itself
    // ------------------------------------------------------------------

    @Test
    fun `a pure builder emits no effect`() {
        val outcome = PureBuilderProbe.compileAndRun(
            """
            pipeline {
                stages {
                    stage("carrier") {
                        scmGit(url = "https://example.invalid/r.git")
                    }
                }
            }
            """.trimIndent(),
        )

        // scmGit is the manifest's reference PURE_BUILDER: it builds a
        // CheckoutSpec and emits nothing. Discarding that carrier is semantic
        // loss, so the run must fail closed — but it must fail at CONSTRUCTION,
        // before anything was emitted. The distinction is the whole claim: a
        // rejection produced AFTER a checkout would mean the builder had already
        // done the work it claims not to do.
        assertTrue(
            outcome.rejected,
            "discarding the scmGit carrier must fail closed: ${outcome.summary}",
        )

        // NOT asserted: `StepStarted == 0`. The engine records a build-time
        // rejection as a FAILED step so the failure is observable in the
        // timeline, and that bookkeeping step legitimately produces
        // StepStarted/StepFailed/StepFinished. The probe already separates it:
        // executedStepCount counts only a step that SUCCEEDED, which is the real
        // effect signal. Asserting the raw StepStarted count would either fail
        // against correct behaviour or, worse, force someone to delete the
        // rejection event to make a fitness pass.
        assertEquals(
            0,
            outcome.executedStepCount,
            "a PURE_BUILDER must cause no effect to be executed: ${outcome.summary}",
        )
        assertEquals(
            0,
            outcome.eventKinds.count { it == "StepSucceeded" },
            "a PURE_BUILDER must leave no successfully executed step: ${outcome.summary}",
        )

        // The decisive part: no SCM event may appear at all. A pure builder that
        // acquired the capability to check out, or emitted a GitCheckoutStarted
        // and then failed the carrier gate, would be a builder wearing a step's
        // name — the exact defect class this law exists to prevent.
        val scmEvents = outcome.eventKinds.filter { it.startsWith("Git") }
        assertEquals(
            emptyList<String>(),
            scmEvents,
            "a PURE_BUILDER must emit no SCM event; saw $scmEvents: ${outcome.summary}",
        )

        // And no credential event: resolving a credentialsId would be capability
        // acquisition, which law 3 forbids outright.
        val credentialEvents = outcome.eventKinds.filter { it.startsWith("Credential") }
        assertEquals(
            emptyList<String>(),
            credentialEvents,
            "a PURE_BUILDER must acquire no credential; saw $credentialEvents: ${outcome.summary}",
        )
    }

    @Test
    fun `a pure builder needs no granted capability to build its carrier`() {
        // This probe runs the DEFAULT security posture: no --allow-network, no
        // credential store, no egress grant, no capability of any kind. So the
        // question is not "how many capabilities were granted" but "what stopped
        // the carrier being built".
        //
        // The answer, measured: nothing. The script compiles, the DSL lowers,
        // and the run dies at the CANONICAL BRIDGE because core.checkout is not
        // a registered plugin key — a composition fact, not a capability
        // refusal. A builder that acquired a capability would have been stopped
        // earlier, at admission, with a capability diagnostic.
        val outcome = PureBuilderProbe.compileAndRun(
            """
            pipeline {
                stages {
                    stage("checkout") {
                        checkout(scmGit(url = "https://example.invalid/r.git", branch = "main").scm)
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(
            outcome.rejected,
            "the consumed scmGit shape is not admitted today (core.checkout has no plugin " +
                "registration); the witness is about HOW it fails, not whether: ${outcome.summary}",
        )
        assertTrue(
            outcome.diagnostics.contains("non-canonical") ||
                outcome.diagnostics.contains("core.checkout"),
            "the run must fail at the canonical bridge for want of a plugin registration, which " +
                "is what proves no capability was ever needed. A capability refusal would " +
                "instead name the capability. Full diagnostics: ${outcome.diagnostics}",
        )
        assertEquals(
            emptyList<String>(),
            outcome.eventKinds.filter {
                it.startsWith("Git") || it.startsWith("Credential") || it.startsWith("Http")
            },
            "a PURE_BUILDER must reach neither SCM, credentials nor network under the default " +
                "posture: ${outcome.summary}",
        )
    }
}
