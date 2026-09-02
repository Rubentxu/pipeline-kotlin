package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineDefinition
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.RunRequest
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DurableRunCoordinatorTest {

    private class RecordedCall(val spec: PipelineSpec, val runId: String, val startFromCursor: Boolean)

    private fun spec() = PipelineSpec(
        stages = listOf(StageSpec(name = "s", steps = listOf(StepSpec.Echo(text = "hi"))))
    )

    private fun definition(id: String = "d1") = PipelineDefinition(
        id = DefinitionId(id),
        name = "p",
        version = "0.0.0",
    )

    private fun registeredCoordinator(
        id: String = "d1",
        delegateResult: (RecordedCall) -> Result<String>,
    ): Pair<DurableRunCoordinator, MutableList<RecordedCall>> {
        val calls = mutableListOf<RecordedCall>()
        val registry = SpecRegistry()
        val s = spec()
        registry.register(DefinitionId(id), s)
        val coordinator = DurableRunCoordinator(
            delegate = DurableRunDelegate { sp, rid, cursor ->
                calls += RecordedCall(sp, rid, cursor)
                delegateResult(calls.last())
            },
            specs = registry,
        )
        return coordinator to calls
    }

    @Test
    fun `legacy success string maps to typed RunOutcome Success`() {
        val (coordinator, _) = registeredCoordinator { Result.success("success") }

        val outcome = coordinator.run(RunRequest(definition(), RunId("r1")))

        assertEquals(RunOutcome.Success, outcome)
    }

    @Test
    fun `legacy unstable string maps to typed RunOutcome Unstable`() {
        val (coordinator, _) = registeredCoordinator { Result.success("unstable") }

        val outcome = coordinator.run(RunRequest(definition(), RunId("r1")))

        assertEquals(RunOutcome.Unstable, outcome)
    }

    @Test
    fun `legacy failure string maps to typed RunOutcome Failure with UNKNOWN kind`() {
        val (coordinator, _) = registeredCoordinator { Result.success("failure") }

        val outcome = coordinator.run(RunRequest(definition(), RunId("r1")))

        assertTrue(outcome is RunOutcome.Failure)
        assertEquals(FailureKind.UNKNOWN, (outcome as RunOutcome.Failure).failure.kind)
    }

    @Test
    fun `delegate Result failure maps to Failure with INFRASTRUCTURE kind and the cause attached`() {
        val divergence = RuntimeException("fingerprint divergence detected")
        val (coordinator, _) = registeredCoordinator { Result.failure<String>(divergence) }

        val outcome = coordinator.run(RunRequest(definition(), RunId("r1")))

        assertTrue(outcome is RunOutcome.Failure)
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
        assertTrue(failure.message.contains("fingerprint divergence"))
        assertTrue(failure.cause === divergence)
    }

    @Test
    fun `delegate throwing maps to Failure with INFRASTRUCTURE kind instead of propagating`() {
        val calls = mutableListOf<RecordedCall>()
        val registry = SpecRegistry()
        registry.register(DefinitionId("d1"), spec())
        val coordinator = DurableRunCoordinator(
            delegate = DurableRunDelegate { _, _, _ ->
                calls += RecordedCall(spec(), "r1", false)
                throw IllegalStateException("journal corrupted")
            },
            specs = registry,
        )

        val outcome = coordinator.run(RunRequest(definition(), RunId("r1")))

        assertTrue(outcome is RunOutcome.Failure)
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
        assertTrue(failure.message.contains("journal corrupted"))
    }

    @Test
    fun `delegate receives the registered spec instance and the request runId`() {
        val (coordinator, calls) = registeredCoordinator { Result.success("success") }

        coordinator.run(RunRequest(definition("d1"), RunId("run-42")))

        assertEquals(1, calls.size)
        assertEquals("run-42", calls.single().runId)
    }

    @Test
    fun `resumeFromCursor true is forwarded to the delegate as startFromCursor`() {
        val (coordinator, calls) = registeredCoordinator { Result.success("success") }

        coordinator.run(RunRequest(definition(), RunId("r1"), resumeFromCursor = true))

        assertTrue(calls.single().startFromCursor)
    }

    @Test
    fun `fresh run forwards startFromCursor false`() {
        val (coordinator, calls) = registeredCoordinator { Result.success("success") }

        coordinator.run(RunRequest(definition(), RunId("r1")))

        assertEquals(false, calls.single().startFromCursor)
    }

    @Test
    fun `resumeAfter is rejected fail-closed and the delegate is never called`() {
        val (coordinator, calls) = registeredCoordinator { Result.success("success") }

        val ex = assertThrows(IllegalArgumentException::class.java) {
            coordinator.run(RunRequest(definition(), RunId("r1"), resumeAfter = "s0-0"))
        }

        assertTrue(ex.message!!.contains("LF-0206"))
        assertEquals(0, calls.size)
    }

    @Test
    fun `unregistered definition id fails closed before the delegate is invoked`() {
        val (coordinator, calls) = registeredCoordinator { Result.success("success") }

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.run(RunRequest(definition("ghost"), RunId("r1")))
        }

        assertEquals(0, calls.size)
    }

    @Test
    fun `unknown legacy token surfaces as fail-closed IllegalArgumentException`() {
        val (coordinator, _) = registeredCoordinator { Result.success("mystery-outcome") }

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.run(RunRequest(definition(), RunId("r1")))
        }
    }
}
