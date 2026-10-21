package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector
import dev.rubentxu.pipeline.v2.harness.model.EventConstraint
import dev.rubentxu.pipeline.v2.harness.model.EventContract
import dev.rubentxu.pipeline.v2.harness.model.EventSelector
import dev.rubentxu.pipeline.v2.harness.model.ExpectedRunOutcome
import dev.rubentxu.pipeline.v2.harness.model.RelationScope
import dev.rubentxu.pipeline.v2.harness.model.VerificationResult
import dev.rubentxu.pipeline.v2.harness.model.ViolationRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The verdict `pipeline events verify` gives a contract.
 *
 * The command is the only way a pipeline author can ask whether the run they
 * observed honoured the protocol they wrote down, and its answer is the whole
 * product: a contract that is honoured must come back clean, and a contract that
 * is broken must come back with the rule it broke. A verifier that is too
 * lenient tells an author their pipeline is fine when it is not, and one that is
 * too strict teaches them to ignore the command.
 *
 * Nothing in the executable suite reaches this. The launcher runs a pipeline and
 * reads its exit code; the verifier is a second command that needs a journal, a
 * run id and a contract file, and no scenario drives it. So the guarantee had no
 * observer at all — not a unit test, not an example.
 *
 * The histories below are built by hand from the same event types the store
 * produces, because the verifier reads the typed event and not a recording of
 * it. That keeps the whole file pure: no journal, no filesystem, no clock.
 */
class EventHarnessVerifyTest {

    private val runId = "run-under-contract"

    // --- the four constraints, one case each ----------------------------------------

    @Test
    fun `a contract the history honours comes back clean`() {
        val result = verify(history(), contract(EventConstraint.Exactly(EventSelector("StepStarted"), 1)))

        assertEquals(VerificationResult.Valid, result)
    }

    @Test
    fun `a count the history does not match is a violation`() {
        val result = verify(history(), contract(EventConstraint.Exactly(EventSelector("StepStarted"), 2)))

        assertEquals(ViolationRule.EXACTLY_NOT_SATISFIED, onlyRule(result))
    }

    @Test
    fun `a forbidden event that happened is a violation`() {
        val failed = history().dropLast(1) + stepFailed(5) + runFinished(6, "failure")

        val result = verify(failed, contract(EventConstraint.Never(EventSelector("StepFailed"))))

        assertEquals(ViolationRule.NEVER_VIOLATED, onlyRule(result))
    }

    @Test
    fun `the terminal outcome must be the one the contract expects`() {
        val result = verify(history(), contract(EventConstraint.TerminalOutcome(ExpectedRunOutcome.FAILURE)))

        assertEquals(ViolationRule.TERMINAL_OUTCOME_MISMATCH, onlyRule(result))
    }

    // --- the scope of an ordering ----------------------------------------------------

    @Test
    fun `an ordering that holds across the whole history is honoured`() {
        val ordering = EventConstraint.Before(
            first = EventSelector("StageStarted"),
            second = EventSelector("StepStarted"),
            scope = RelationScope.Global,
        )

        assertEquals(VerificationResult.Valid, verify(history(), contract(ordering)))
    }

    @Test
    fun `an ordering that only holds across the whole history is broken within a subject`() {
        // The same history, the same two events, and the opposite verdict. The
        // difference is the scope: a global ordering asks whether the first event
        // happened before the second one, while a subject-scoped one asks whether
        // that happened FOR THE SAME SUBJECT. A stage and a step inside it are
        // different subjects, so a relation written across those two levels can
        // never hold within one — and a contract author deserves to learn that
        // from the verifier rather than from a report that says nothing broke.
        val ordering = EventConstraint.Before(
            first = EventSelector("StageStarted"),
            second = EventSelector("StepStarted"),
            scope = RelationScope.SameSubject,
        )

        val result = verify(history(), contract(ordering))

        assertEquals(ViolationRule.BEFORE_VIOLATED, onlyRule(result))
    }

    // --- the universal laws ---------------------------------------------------------

    @Test
    fun `a step that finished without starting is a violation`() {
        // One of the laws that run on every verification, whatever the contract
        // says: a step cannot finish without having started. The history is
        // otherwise well formed, so the only thing that can report this is the
        // law itself.
        val orphaned = listOf(
            runStarted(1),
            stepFinished(2, 0, 0),
            runFinished(3, "success"),
        )

        val result = verify(orphaned, contract())

        assertEquals(ViolationRule.LAW_FINISHED_WITHOUT_STARTED, onlyRule(result))
    }

    // --- the fixture ----------------------------------------------------------------

    private fun verify(
        history: List<TypedEvent>,
        contract: EventContract,
    ): VerificationResult = EventHarness.verify(history, contract)

    private fun contract(vararg constraints: EventConstraint) = EventContract(
        version = EventContract.CURRENT_VERSION,
        name = "contract-under-test",
        expect = ExpectedRunOutcome.SUCCESS,
        constraints = constraints.toList(),
    )

    /**
     * A run that started, opened a stage, ran a step in it and finished. Well
     * formed on purpose: the universal protocol laws run on every verification,
     * so a history with a stage that never finished or a step that finished
     * without starting would report a law instead of the contract under test.
     */
    private fun history(): List<TypedEvent> = listOf(
        runStarted(1),
        stageStarted(2, 0),
        stepStarted(3, 0, 0),
        stepFinished(4, 0, 0),
        runFinished(5, "success"),
    )

    private fun onlyRule(result: VerificationResult): ViolationRule {
        val violations = (result as? VerificationResult.Invalid)?.violations
            ?: error("expected a violation, got $result")
        assertEquals(1, violations.size, "expected one violation, got $violations")
        return violations.first().rule
    }

    private fun typed(event: DomainEvent): TypedEvent = TypedEvent(EnvelopeProjector.project(event), event)

    private fun runStarted(sequence: Long) = typed(
        RunStarted("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), "pipeline.kts"),
    )

    private fun stageStarted(sequence: Long, stage: Int) = typed(
        StageStarted("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), stage, "stage$stage"),
    )

    private fun stepStarted(sequence: Long, stage: Int, step: Int) = typed(
        StepStarted("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), stage, step, "step$step", "sh"),
    )

    private fun stepFinished(sequence: Long, stage: Int, step: Int) = typed(
        // The verdict travels with the terminal event: in this history the step succeeded, and the
        // run that follows it reports success.
        StepFinished(
            "e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), stage, step, "step$step", "sh",
            StepOutcome.SUCCESS_TOKEN,
        ),
    )

    private fun stepFailed(sequence: Long) = typed(
        // A step that never started: the step 1 of the stage. The step 0 of that stage DID
        // start and DID finish well, and the terminal-contradiction law — which runs on every
        // verification — reads a failure of the same step as a contradiction. A failure of a
        // step that never started keeps this fixture about the constraint it is testing, and
        // about nothing else.
        StepFailed(
            "e$sequence", runId, sequence, Instant.ofEpochMilli(sequence),
            stageIndex = 0, stepIndex = 1, stepName = "step1", stepType = "sh",
            failureKind = FailureKind.USER, message = "the command failed",
        ),
    )

    private fun runFinished(sequence: Long, outcome: String) = typed(
        RunFinished("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), outcome, emptyList()),
    )
}
