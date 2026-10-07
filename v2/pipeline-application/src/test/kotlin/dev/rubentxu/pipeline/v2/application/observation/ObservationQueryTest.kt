package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Behaviour tests for the read-side query.
 *
 * ## Harness fidelity
 *
 * HF0 Pure Contract. `compileQuery` and `selectObservations` read no clock, no
 * filesystem and no ambient state; calling the production functions directly is
 * faithful rather than a reimplementation.
 *
 * The limitation is the same as the renderer's: `run` does not yet apply a query
 * on the command line, so these rows prove the QUERY, not the flag. The flag
 * surface is covered by `ObservationCliContractTest` once it exists.
 *
 * ## Mutation
 *
 * - `OR-1` (OR within a dimension) → make `matches` use `all` instead of `any`.
 * - `AND-1` (AND across dimensions) → make each dimension an early `true`.
 * - `EMPTY-1` (empty selector list refused) → accept it and default to match-all.
 * - `TEXTSCOPE-1` (text does not constrain textless records) → return `false`
 *   when `textCarriedBy` is null.
 * - `ORDER-1` (selection preserves order) → sort the result.
 */
class ObservationQueryTest {

    private val at: Instant = Instant.parse("2026-10-06T10:00:00Z")

    private fun runStarted() = RunStarted("e1", "run-1", 1, at, "demo.kts")
    private fun stage(name: String, index: Int = 0) =
        StageStarted("e-stage", "run-1", index.toLong() + 2, at, index, name)
    private fun step(name: String, stageIndex: Int = 0) =
        StepStarted("e-t", "run-1", 9, at, stageIndex, 0, name, "core.echo")

    private fun message(text: String) = EchoOutputCaptured("e-m", "run-1", 10, at, 0, text)

    private fun finished(outcome: String = "SUCCESS") = RunFinished("e-f", "run-1", 99, at, outcome, emptyList())

    private fun select(events: List<DomainEvent>, query: ObservationQuery): List<DomainEvent> {
        val compiled = compileQuery(query)
        assertTrue(compiled is CompileResult.Ok, "query must compile: $compiled")
        return selectObservations(events, (compiled as CompileResult.Ok).value)
    }

    @Test
    fun `IDENTITY-1 the identity query keeps every record`() {
        val events = listOf(runStarted(), stage("build"), message("hello"), finished())

        assertEquals(events, select(events, ObservationQuery()))
        assertTrue(ObservationQuery().isIdentity)
    }

    @Test
    fun `OR-1 several values in ONE dimension union`() {
        val events = listOf(message("alpha"), message("beta"), message("gamma"))

        val kept = select(
            events,
            ObservationQuery(lines = LineSelector.Only(listOf(TextSelector.Literal("alpha"), TextSelector.Literal("beta")))),
        )

        assertEquals(listOf("alpha", "beta"), kept.map { textCarriedBy(it) })
    }

    @Test
    fun `AND-1 two dimensions intersect`() {
        val events = listOf(message("alpha"), message("beta"))

        // --grep alpha --stage build : only the record that is BOTH.
        val kept = select(
            events,
            ObservationQuery(
                stageNames = setOf("build"),
                lines = LineSelector.Only(listOf(TextSelector.Literal("alpha"))),
            ),
        )

        // Messages carry no stage, so the stage dimension excludes them. This is
        // the AND rule stated literally: a dimension a record cannot satisfy
        // excludes it rather than ignoring it.
        assertTrue(kept.isEmpty(), "got $kept")
    }

    @Test
    fun `AND-2 a record satisfying both dimensions is kept`() {
        val events = listOf(stage("build"), message("alpha"))

        val kept = select(events, ObservationQuery(stageNames = setOf("build")))

        assertEquals(1, kept.size)
        assertEquals("build", stageCarriedByPublic(kept.first()))
    }

    @Test
    fun `TEXTSCOPE-1 text does not delete records that carry none`() {
        val events = listOf(runStarted(), message("alpha"), finished("FAILURE"))

        // --grep FAILURE would delete every structural line if "no text" were
        // read as "did not match". The text dimension constrains only records
        // that HAVE text, so the lifecycle survives.
        val kept = select(
            events,
            ObservationQuery(lines = LineSelector.Only(listOf(TextSelector.Literal("alpha")))),
        )

        assertEquals(3, kept.size, "text must not remove textless records")
    }

    @Test
    fun `EMPTY-1 an empty selector list is REFUSED, not guessed`() {
        val compiled = compileLineSelector(LineSelector.Only(emptyList()))

        assertTrue(compiled is CompileResult.Invalid, "got $compiled")
        assertTrue(
            (compiled as CompileResult.Invalid).reason.contains("refusing to guess"),
            "reason must name the ambiguity",
        )
    }

    @Test
    fun `EMPTY-2 an empty Except is refused too, not read as match-all`() {
        assertTrue(compileLineSelector(LineSelector.Except(emptyList())) is CompileResult.Invalid)
    }

    @Test
    fun `REGEX-1 an invalid pattern is a typed failure, not a crash`() {
        val compiled = compileTextSelector(TextSelector.Pattern("([unclosed"))

        assertTrue(compiled is CompileResult.Invalid, "got $compiled")
        assertTrue((compiled as CompileResult.Invalid).reason.contains("invalid regular expression"))
    }

    @Test
    fun `REGEX-2 a valid pattern compiles and matches`() {
        val compiled = compileLineSelector(
            LineSelector.Only(listOf(TextSelector.Pattern("err(or)?"))),
        )

        assertTrue(compiled is CompileResult.Ok)
        val selector = (compiled as CompileResult.Ok).value
        assertTrue(selector.accepts("an error happened"))
        assertFalse(selector.accepts("all fine"))
    }

    @Test
    fun `EXCEPT-1 Except is the blacklist dual of Only`() {
        val events = listOf(message("alpha"), message("beta"))

        val kept = select(
            events,
            ObservationQuery(lines = LineSelector.Except(listOf(TextSelector.Literal("alpha")))),
        )

        assertEquals(listOf("beta"), kept.map { textCarriedBy(it) })
    }

    @Test
    fun `CASE-1 ignoreCase is honoured`() {
        val sensitive = compileLineSelector(LineSelector.Only(listOf(TextSelector.Literal("ERROR"))))
        val insensitive = compileLineSelector(
            LineSelector.Only(listOf(TextSelector.Literal("ERROR", ignoreCase = true))),
        )

        assertFalse((sensitive as CompileResult.Ok).value.accepts("an error happened"))
        assertTrue((insensitive as CompileResult.Ok).value.accepts("an error happened"))
    }

    @Test
    fun `OUTCOME-1 the outcome dimension reads the record that carries one`() {
        val events = listOf(finished("FAILURE"), finished("SUCCESS"))

        val kept = select(events, ObservationQuery(outcomes = setOf("FAILURE")))

        assertEquals(1, kept.size)
    }

    @Test
    fun `KIND-1 the kind dimension reads the event kind`() {
        val events = listOf(runStarted(), finished())

        val kept = select(events, ObservationQuery(eventKinds = setOf("RunFinished")))

        assertEquals(1, kept.size)
    }

    @Test
    fun `ORDER-1 selection preserves order`() {
        val events = listOf(message("c"), message("a"), message("b"))

        val kept = select(events, ObservationQuery(lines = LineSelector.All))

        assertEquals(listOf("c", "a", "b"), kept.map { textCarriedBy(it) })
    }

    /** Exposes the private stage accessor for assertion readability. */
    private fun stageCarriedByPublic(event: DomainEvent): String? = when (event) {
        is StageStarted -> event.stageName
        else -> null
    }
}