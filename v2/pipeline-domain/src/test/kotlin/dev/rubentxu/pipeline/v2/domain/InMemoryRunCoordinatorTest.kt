package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class InMemoryRunCoordinatorTest {

    private fun step(id: String) = StepDescriptor(id = id, type = "sh", configRef = "$id.config")

    private fun definition(
        vararg stepIds: String,
        edges: List<Edge> = emptyList(),
    ): PipelineDefinition = PipelineDefinition(
        id = DefinitionId("p"),
        name = "p",
        version = "0.0.0",
        steps = stepIds.map(::step),
        edges = edges,
    )

    @Test
    fun `all-success run reduces to RunOutcome Success`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val coordinator = InMemoryRunCoordinator(dispatcher)

        val outcome = coordinator.run(RunRequest(definition("build", "test"), RunId("r1")))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(listOf("build", "test"), dispatcher.dispatchedStepIds)
    }

    @Test
    fun `first failure wins and carries the typed PipelineFailure`() {
        val first = PipelineFailure(FailureKind.SCRIPT, "build broke")
        val second = PipelineFailure(FailureKind.SCRIPT, "test broke")
        val dispatcher = RecordingStepDispatcher(
            mapOf(
                "build" to listOf(StepOutcome.Failure(first)),
                "test" to listOf(StepOutcome.Failure(second)),
            )
        )
        val coordinator = InMemoryRunCoordinator(dispatcher)

        val outcome = coordinator.run(RunRequest(definition("build", "test"), RunId("r1")))

        assertEquals(RunOutcome.Failure(first), outcome, "the FIRST observed failure must win")
    }

    @Test
    fun `unstable run with no failures reduces to RunOutcome Unstable`() {
        val dispatcher = RecordingStepDispatcher(mapOf("build" to listOf(StepOutcome.Unstable)))
        val coordinator = InMemoryRunCoordinator(dispatcher)

        val outcome = coordinator.run(RunRequest(definition("build", "test"), RunId("r1")))

        assertEquals(RunOutcome.Unstable, outcome)
    }

    @Test
    fun `failure beats unstable regardless of order`() {
        val failure = PipelineFailure(FailureKind.SCRIPT, "test broke")
        val dispatcher = RecordingStepDispatcher(
            mapOf(
                "build" to listOf(StepOutcome.Unstable),
                "test" to listOf(StepOutcome.Failure(failure)),
            )
        )
        val coordinator = InMemoryRunCoordinator(dispatcher)

        val outcome = coordinator.run(RunRequest(definition("build", "test"), RunId("r1")))

        assertEquals(RunOutcome.Failure(failure), outcome)
    }

    @Test
    fun `empty definition reduces to Success without dispatching anything`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val coordinator = InMemoryRunCoordinator(dispatcher)

        val outcome = coordinator.run(RunRequest(definition(), RunId("r1")))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(0, dispatcher.dispatchedStepIds.size)
    }

    @Test
    fun `edges drive the dispatch order`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val coordinator = InMemoryRunCoordinator(dispatcher)
        val def = definition(
            "deploy", "build", "test",
            edges = listOf(Edge("build", "test"), Edge("test", "deploy")),
        )

        coordinator.run(RunRequest(def, RunId("r1")))

        assertEquals(listOf("build", "test", "deploy"), dispatcher.dispatchedStepIds)
    }

    @Test
    fun `resumeAfter skips cursor step and everything before it without dispatching`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val coordinator = InMemoryRunCoordinator(dispatcher)
        val def = definition(
            "build", "test", "deploy",
            edges = listOf(Edge("build", "test"), Edge("test", "deploy")),
        )

        val outcome = coordinator.run(RunRequest(def, RunId("r2"), resumeAfter = "test"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(listOf("deploy"), dispatcher.dispatchedStepIds, "steps up to and including the cursor must NOT be dispatched")
    }

    @Test
    fun `resumeAfter referencing unknown step throws`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val coordinator = InMemoryRunCoordinator(dispatcher)
        val def = definition("build", "test")

        val ex = assertThrows(IllegalArgumentException::class.java) {
            coordinator.run(RunRequest(def, RunId("r1"), resumeAfter = "ghost"))
        }
        assertTrue(ex.message!!.contains("ghost"))
        assertEquals(0, dispatcher.dispatchedStepIds.size, "nothing may be dispatched for an invalid request")
    }

    @Test
    fun `same request and deterministic dispatcher produce the same outcome`() {
        val dispatcher = RecordingStepDispatcher(mapOf("build" to listOf(StepOutcome.Unstable)))
        val coordinator = InMemoryRunCoordinator(dispatcher)
        val def = definition("build", "test")

        val first = coordinator.run(RunRequest(def, RunId("r1")))
        val second = coordinator.run(RunRequest(def, RunId("r1")))

        assertEquals(first, second)
    }

    @Test
    fun `invalid definition with cycle fails closed without dispatching`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val coordinator = InMemoryRunCoordinator(dispatcher)
        val def = definition(
            "a", "b",
            edges = listOf(Edge("a", "b"), Edge("b", "a")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.run(RunRequest(def, RunId("r1")))
        }
        assertEquals(0, dispatcher.dispatchedStepIds.size)
    }

    @Test
    fun `repeated dispatch of the same coordinator accumulates per-run call logs`() {
        // The recording dispatcher is shared across runs; the coordinator
        // must not reset or isolate the log — callers own the dispatcher.
        val dispatcher = RecordingStepDispatcher.successOnly()
        val coordinator = InMemoryRunCoordinator(dispatcher)
        val def = definition("build")

        coordinator.run(RunRequest(def, RunId("r1")))
        coordinator.run(RunRequest(def, RunId("r2")))

        assertEquals(2, dispatcher.dispatchCount("build"))
        assertEquals(
            listOf("build", "build"),
            dispatcher.dispatchedStepIds,
        )
    }

    @Test
    fun `PARALLEL-edge siblings dispatch through the SAME dispatcher as a concurrent wave`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val concurrent = ConcurrentStepDispatcher(dispatcher, Executors.newFixedThreadPool(2))
        val coordinator = InMemoryRunCoordinator(dispatcher, concurrent)
        val def = definition(
            "deploy", "lint", "build",
            edges = listOf(
                Edge("build", "deploy"),
                Edge("lint", "deploy"),
                Edge("build", "lint", EdgeKind.PARALLEL),
            ),
        )

        val outcome = coordinator.run(RunRequest(def, RunId("r1")))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(
            setOf("build", "lint", "deploy"),
            dispatcher.dispatchedStepIds.toSet(),
            "every wave step must flow through the single dispatcher instance",
        )
        assertEquals(3, dispatcher.dispatchedStepIds.size)
    }

    @Test
    fun `without a concurrent dispatcher waves flatten to sequential declaration order`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val coordinator = InMemoryRunCoordinator(dispatcher)
        val def = definition(
            "deploy", "lint", "build",
            edges = listOf(
                Edge("build", "deploy"),
                Edge("lint", "deploy"),
                Edge("build", "lint", EdgeKind.PARALLEL),
            ),
        )

        coordinator.run(RunRequest(def, RunId("r1")))

        assertEquals(
            listOf("lint", "build", "deploy"),
            dispatcher.dispatchedStepIds,
            "flatten fallback keeps the deterministic declaration order (deploy=0, lint=1, build=2)",
        )
    }

    @Test
    fun `resumeAfter inside a concurrent wave keeps only the remaining wave steps`() {
        val dispatcher = RecordingStepDispatcher.successOnly()
        val concurrent = ConcurrentStepDispatcher(dispatcher, Executors.newFixedThreadPool(2))
        val coordinator = InMemoryRunCoordinator(dispatcher, concurrent)
        val def = definition(
            "deploy", "lint", "build",
            edges = listOf(
                Edge("build", "deploy"),
                Edge("lint", "deploy"),
                Edge("build", "lint", EdgeKind.PARALLEL),
            ),
        )

        // Flat order: lint, build, deploy. Cursor at lint → the wave tail
        // {build} still runs; deploy follows.
        val outcome = coordinator.run(RunRequest(def, RunId("r2"), resumeAfter = "lint"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(
            setOf("build", "deploy"),
            dispatcher.dispatchedStepIds.toSet(),
            "steps up to and including the cursor must NOT be dispatched; the wave tail still runs",
        )
        assertEquals(2, dispatcher.dispatchedStepIds.size)
    }

    @Test
    fun `first failure in declaration order wins even when a later failure completes first`() {
        // Wave = {slowFailure, fastFailure2}; slowFailure finishes LAST but
        // is declared FIRST, so its failure must win the reduction.
        val fastCompleted = CountDownLatch(1)
        val failureA = PipelineFailure(FailureKind.SCRIPT, "wave failure A")
        val failureB = PipelineFailure(FailureKind.SCRIPT, "wave failure B")
        val delegate = StepDispatcher { step, _ ->
            when (step.id) {
                "slowFailure" -> {
                    fastCompleted.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    StepOutcome.Failure(failureA)
                }
                "fastFailure2" -> {
                    fastCompleted.countDown()
                    StepOutcome.Failure(failureB)
                }
                else -> StepOutcome.Success
            }
        }
        val concurrent = ConcurrentStepDispatcher(delegate, Executors.newFixedThreadPool(2))
        val coordinator = InMemoryRunCoordinator(delegate, concurrent)
        val def = definition(
            "slowFailure", "fastFailure2",
            edges = listOf(Edge("slowFailure", "fastFailure2", EdgeKind.PARALLEL)),
        )

        val outcome = coordinator.run(RunRequest(def, RunId("r1")))

        assertEquals(RunOutcome.Failure(failureA), outcome, "declaration order must decide, not completion order")
    }
}
