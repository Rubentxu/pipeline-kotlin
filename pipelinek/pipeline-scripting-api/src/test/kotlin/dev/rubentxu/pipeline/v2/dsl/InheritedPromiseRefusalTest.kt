package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The forms this product inherited from Jenkins and does not execute, and the
 * refusal that says so while the script is being written.
 *
 * ## The guarantee under trial
 *
 * That the language does not ACCEPT an option the run cannot honour: a flag
 * that is always rejected at run time, and a wait whose body declares no
 * predicate. Both are refused while the script is written, and both refusals
 * name the fact that makes them refusals.
 *
 * ## Why the refusal happens at WRITE time
 *
 * Because the alternative is a script that compiles and is then refused by a
 * run that has already started: the author learns the fact at the worst
 * possible moment, and the plan that carries it is a plan the run cannot
 * execute. The refusal at write time is the same pattern the step-level
 * `retry` retrofit already follows: the form stays DECLARED (so the call fails
 * with THIS diagnostic and not with a bare signature error), and it refuses
 * with a message that says what to write instead.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the runtime refuses the same forms: the runtime
 * keeps its own fail-closed refusal of `keepAll`, and a plan composed before
 * these refusals still reaches it. This file is about the AUTHORING surface.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InheritedPromiseRefusalTest {

    /**
     * `publishHTML(keepAll = true)` is refused while the script is written.
     *
     * The flag reached the run and was rejected there, every time: the archive
     * of reports is written per run, and nothing in the runtime ever kept the
     * reports of the runs before. A script that asked for it compiled and
     * failed halfway through the run.
     */
    @Test
    @DisplayName("publishHTML with keepAll is refused while the script is written")
    fun publishHtmlWithKeepAllIsRefusedWhileTheScriptIsWritten() {
        val scope = StageScope("stage")

        val refusal = assertThrows(
            IllegalArgumentException::class.java,
            { scope.publishHTML("report", "out", keepAll = true) },
            "the language accepted a flag the runtime always rejects: the author would learn " +
                "it when the run had already started",
        )

        assertTrue(
            refusal.message?.contains(KEEP_ALL) == true,
            "the refusal does not name the flag it refuses: the author cannot tell which " +
                "option of the call was rejected. The message was ${refusal.message}",
        )
        assertTrue(
            refusal.message?.contains(RETENTION_LIMIT) == true,
            "the refusal does not name the limit of the retention: the author is left without " +
                "a way forward. The message was ${refusal.message}",
        )
        assertTrue(
            scope.steps().isEmpty(),
            "the refused call enqueued a step anyway: a refusal that leaves the operation " +
                "declared is not a refusal",
        )
    }

    /**
     * `publishHTML` without the flag still publishes.
     *
     * The control of the row above: the refusal is about the flag, not about
     * the publication. A refusal that took the whole step with it would
     * retire a capability that works.
     */
    @Test
    @DisplayName("publishHTML without the flag still publishes")
    fun publishHtmlWithoutTheFlagStillPublishes() {
        val scope = StageScope("stage")

        scope.publishHTML("report", "out")

        val step = assertInstanceOf(
            StepSpec.RegistryStepSpec::class.java,
            scope.steps().singleOrNull(),
            "the publication did not enqueue its step: the steps it enqueued are " +
                "${scope.steps()}",
        )
        assertEquals(
            PluginStepId("core.publishHTML"),
            step.stepKey,
            "the publication enqueued another step",
        )
        assertFalse(
            step.encodedInput.value.contains(KEEP_ALL),
            "the payload of the publication carries the flag it does not honour: " +
                step.encodedInput.value,
        )
    }

    /**
     * A `waitUntil` whose body declares no step is refused while the script is
     * written.
     *
     * The predicate of a wait is the OUTCOME of the steps its body declares. A
     * body that declares none has no predicate to evaluate: the wait polled,
     * the empty body succeeded and the wait ended satisfied on its first poll,
     * having looked at no condition at all. A body written as `{ true }` is
     * that body — the expression is not a step, and Kotlin discards it.
     */
    @Test
    @DisplayName("a waitUntil whose body declares no step is refused while the script is written")
    fun aWaitUntilWhoseBodyDeclaresNoStepIsRefusedWhileTheScriptIsWritten() {
        val scope = StageScope("stage")

        val refusal = assertThrows(
            IllegalArgumentException::class.java,
            { scope.waitUntil(initialRecurrencePeriod = 100L) { true } },
            "the language accepted a wait with no predicate: the wait would end satisfied on " +
                "its first poll without looking at any condition",
        )

        assertTrue(
            refusal.message?.contains(PREDICATE_IS_THE_OUTCOME) == true,
            "the refusal does not name the real predicate: the author does not learn what the " +
                "wait actually waits for. The message was ${refusal.message}",
        )
        assertTrue(
            scope.steps().isEmpty(),
            "the refused wait enqueued a step anyway: a refusal that leaves the operation " +
                "declared is not a refusal",
        )
    }

    /**
     * A `waitUntil` whose body declares a step still lowers to the wait.
     *
     * The control of the row above: the refusal is about a body with no
     * predicate, not about the wait. The form that works has to keep working,
     * and the durable polling it rests on is untouched.
     */
    @Test
    @DisplayName("a waitUntil whose body declares a step still lowers to the wait")
    fun aWaitUntilWhoseBodyDeclaresAStepStillLowersToTheWait() {
        val scope = StageScope("stage")

        scope.waitUntil(initialRecurrencePeriod = 100L) { sh("test -f marker") }

        val wait = assertInstanceOf(
            StepSpec.WaitUntilBlock::class.java,
            scope.steps().singleOrNull(),
            "the wait did not lower to the wait it is: the steps it enqueued are " +
                "${scope.steps()}",
        )
        assertEquals(
            1,
            wait.body.size,
            "the wait lost the step that decides its condition: its body is ${wait.body}",
        )
    }

    private companion object {

        /** The name of the flag the refusal has to name. */
        private const val KEEP_ALL = "keepAll"

        /**
         * The vocabulary the refusal uses to name the limit of the retention.
         *
         * The author has to learn WHY the flag cannot be honoured, and the
         * limit is the reason: the archive is written per run.
         */
        private const val RETENTION_LIMIT = "per run"

        /**
         * The vocabulary the refusal uses to name the real predicate.
         *
         * A refusal that only said "this is not allowed" leaves the author
         * without the fact that decides the wait: the outcome of the steps the
         * body declares.
         */
        private const val PREDICATE_IS_THE_OUTCOME = "outcome of the steps"
    }
}
