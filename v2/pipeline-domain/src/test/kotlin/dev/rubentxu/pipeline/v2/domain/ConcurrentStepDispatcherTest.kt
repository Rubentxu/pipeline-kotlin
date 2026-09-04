package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConcurrentStepDispatcherTest {

    private fun step(id: String) = StepDescriptor(stepId = id, name = "sh", configRef = "$id.config")

    @Test
    fun `dispatches every wave step through the SAME delegate instance`() {
        val delegate = RecordingStepDispatcher.successOnly()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val dispatcher = ConcurrentStepDispatcher(delegate, executor)

            dispatcher.dispatchAll(listOf(step("a"), step("b"), step("c")), StepExecutionContext(RunId("r1")))

            assertEquals(listOf("a", "b", "c"), delegate.dispatchedStepIds)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `outcomes are returned in declaration order regardless of completion order`() {
        // 'slow' blocks until 'fast' has COMPLETED, forcing completion order
        // to be the reverse of declaration order.
        val fastDone = CountDownLatch(1)
        val delegate = StepDispatcher { step, _ ->
            if (step.id == "fast") {
                fastDone.countDown()
                StepOutcome.Success
            } else {
                fastDone.await(5, TimeUnit.SECONDS)
                StepOutcome.Unstable
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val dispatcher = ConcurrentStepDispatcher(delegate, executor)

            val outcomes = dispatcher.dispatchAll(
                listOf(step("slow"), step("fast")),
                StepExecutionContext(RunId("r1")),
            )

            assertEquals(
                listOf<StepOutcome>(StepOutcome.Unstable, StepOutcome.Success),
                outcomes,
                "declaration order must win over completion order",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `delegate bug on a worker thread is contained as INFRASTRUCTURE failure for that step only`() {
        val delegate = StepDispatcher { step, _ ->
            if (step.id == "broken") throw IllegalStateException("dispatcher bug")
            StepOutcome.Success
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val dispatcher = ConcurrentStepDispatcher(delegate, executor)

            val outcomes = dispatcher.dispatchAll(
                listOf(step("broken"), step("healthy")),
                StepExecutionContext(RunId("r1")),
            )

            assertTrue(outcomes[0] is StepOutcome.Failure)
            assertEquals(FailureKind.INFRASTRUCTURE, (outcomes[0] as StepOutcome.Failure).failure.kind)
            assertTrue((outcomes[0] as StepOutcome.Failure).failure.message.contains("broken"))
            assertEquals(StepOutcome.Success, outcomes[1], "one broken step must not poison the wave")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `execution context is shared across the wave`() {
        val seen = mutableListOf<String>()
        val delegate = StepDispatcher { step, context ->
            synchronized(seen) { seen += "${step.id}:${context.runId.value}" }
            StepOutcome.Success
        }
        val executor = Executors.newFixedThreadPool(3)
        try {
            val dispatcher = ConcurrentStepDispatcher(delegate, executor)

            dispatcher.dispatchAll(listOf(step("a"), step("b")), StepExecutionContext(RunId("run-9")))

            assertEquals(setOf("a:run-9", "b:run-9"), seen.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `same-thread executor degrades waves to sequential dispatch without changing order`() {
        val delegate = RecordingStepDispatcher.successOnly()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = ConcurrentStepDispatcher(delegate, executor)

            dispatcher.dispatchAll(listOf(step("a"), step("b")), StepExecutionContext(RunId("r1")))

            assertEquals(listOf("a", "b"), delegate.dispatchedStepIds)
        } finally {
            executor.shutdownNow()
        }
    }
}
