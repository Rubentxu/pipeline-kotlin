package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Behaviour tests for the pure console renderer.
 *
 * ## Harness fidelity (ADR-0072)
 *
 * This harness is **HF0 Pure Contract**. It calls [HumanConsoleRenderer.render]
 * directly and crosses no production entry point, because no human console entry
 * point exists yet: `run` only reaches the renderer once a run has completed,
 * which requires a coordinator, a store and a process.
 *
 * That limits what these rows prove. They characterise the renderer against a
 * hand-built event list; they do NOT certify that `pipeline run` prints it. The
 * certification for that belongs to the slice that wires live output, and this
 * file must not be cited as evidence for it.
 *
 * ## Mutation
 *
 * Each claim below has one attributed mutation that must flip it, restored
 * afterwards:
 *
 * - `VERB-1` (echo is verbatim) → prefix the message with the engine tag.
 * - `VIEW-1` (normal hides NOTE) → include NOTE in the NORMAL predicate.
 * - `VIEW-2` (quiet hides everything) → return true for QUIET.
 * - `SCOPE-1` (steps name their stage) → stop recording stage names.
 * - `SCOPE-3` (live stream equals post-hoc render) → make `Scope.after` advance only for the
 *   batch walk, which is the exact shape the duplication allowed: two copies, one of them wrong.
 */
class HumanConsoleRendererTest {

    private val at: Instant = Instant.parse("2026-10-06T10:00:00Z")

    private fun runStarted() = RunStarted("e1", "run-1", 1, at, "demo.kts")

    private fun stageStarted(index: Int = 0, name: String = "build") =
        StageStarted("e2", "run-1", 2, at, index, name)

    private fun stepStarted(stageIndex: Int = 0, name: String = "echo") =
        StepStarted("e3", "run-1", 3, at, stageIndex, 0, name, "core.echo")

    private fun echo(text: String, stepIndex: Int = 0) =
        EchoOutputCaptured("e4", "run-1", 4, at, stepIndex, text)

    private fun stepFinished(stageIndex: Int = 0, name: String = "echo") =
        StepFinished("e5", "run-1", 5, at, stageIndex, 0, name, "core.echo")

    private fun runFinished(outcome: String = "SUCCESS") =
        RunFinished("e6", "run-1", 6, at, outcome, emptyList())

    @Test
    fun `VERB-1 an echo is printed verbatim with no engine prefix`() {
        val out = HumanConsoleRenderer.render(listOf(echo("this stage runs")), ObservationView.NORMAL)

        // Jenkins prints an `echo` as the message itself. A prefix here would be
        // the engine corrupting the user's own output.
        assertEquals("this stage runs\n", out)
    }

    @Test
    fun `VIEW-1 the normal view hides observational notes but keeps failures`() {
        val started = noteEvent()
        val out = HumanConsoleRenderer.render(
            listOf(runStarted(), started, runFinished("FAILURE")),
            ObservationView.NORMAL,
        )

        assertFalse(out.contains("dir entered"), "NOTE family must not appear in normal")
        assertTrue(out.contains("Run started"), "RUN family must appear in normal")
        assertTrue(out.contains("Finished: FAILURE"), "RUN outcome must appear in normal")
    }

    @Test
    fun `VIEW-2 the quiet view emits nothing`() {
        val out = HumanConsoleRenderer.render(listOf(runStarted(), runFinished()), ObservationView.QUIET)

        assertEquals("", out)
    }

    @Test
    fun `VIEW-3 the events view shows strictly more than normal`() {
        val events = listOf(runStarted(), noteEvent(), runFinished())

        val normal = HumanConsoleRenderer.render(events, ObservationView.NORMAL)
        val all = HumanConsoleRenderer.render(events, ObservationView.EVENTS)

        assertTrue(all.length > normal.length, "events view must include the NOTE family")
        assertTrue(all.contains("dir entered"))
    }

    @Test
    fun `SCOPE-1 a step line names the stage it belongs to`() {
        val out = HumanConsoleRenderer.render(
            listOf(stageStarted(name = "compile"), stepStarted()),
            ObservationView.NORMAL,
        )

        assertTrue(
            out.contains("[step: compile] echo"),
            "step line must carry its stage name, got: $out",
        )
    }

    @Test
    fun `SCOPE-2 a step with an unknown stage index degrades without inventing a name`() {
        val out = HumanConsoleRenderer.render(
            listOf(stepStarted(stageIndex = 7)),
            ObservationView.NORMAL,
        )

        // The renderer must not fabricate a stage name it never observed.
        assertTrue(out.contains("[step: stage 7] echo"), "got: $out")
    }

    @Test
    fun `SCOPE-3 the live stream and the post-hoc render agree`() {
        // Two stages and an echo, so a scope that stops advancing produces a DIFFERENT but equally
        // plausible stage name — which is the failure this row exists to make visible. The two walks
        // used to carry their own copy of the StageStarted transition, so they could drift apart and
        // neither would look wrong.
        val events = listOf(
            runStarted(),
            stageStarted(index = 0, name = "compile"),
            stepStarted(stageIndex = 0, name = "first"),
            stepFinished(stageIndex = 0, name = "first"),
            stageStarted(index = 1, name = "test"),
            stepStarted(stageIndex = 1, name = "second"),
            echo("done"),
            stepFinished(stageIndex = 1, name = "second"),
            runFinished(),
        )

        ObservationView.entries.filter { it != ObservationView.QUIET }.forEach { view ->
            val live = HumanConsoleRenderer.stream(view)
                .let { stream -> events.mapNotNull { stream.accept(it) } }
                .joinToString(separator = "\n")
                .let { if (it.isEmpty()) "" else "$it\n" }

            assertEquals(
                HumanConsoleRenderer.render(events, view),
                live,
                "the live stream and the batch render must be the same presentation under $view",
            )
        }
    }

    @Test
    fun `PURITY-1 the same input always yields the same output`() {
        val events = listOf(runStarted(), stageStarted(), echo("hello"), stepFinished(), runFinished())

        assertEquals(
            HumanConsoleRenderer.render(events, ObservationView.NORMAL),
            HumanConsoleRenderer.render(events, ObservationView.NORMAL),
        )
    }

    @Test
    fun `EMPTY-1 an empty run yields an empty string, not a banner`() {
        assertEquals("", HumanConsoleRenderer.render(emptyList(), ObservationView.NORMAL))
    }

    /** NOTE-family event used by the view-projection rows. */
    private fun noteEvent() =
        dev.rubentxu.pipeline.v2.events.DirEntered(
            eventId = "e-n",
            runId = "run-1",
            sequence = 3,
            occurredAt = at,
            path = "nested/dir",
            previousPath = ".",
        )
}
