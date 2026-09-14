package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRefs
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Pure unit tests for [CanonicalBodyInvokerAdapter] (B11 / W2).
 *
 * These tests exercise the engine-side body-reentry adapter in isolation —
 * no coordinator, no journal, no events, no real shell. They prove the
 * open / invoke / close contract that the canonical coordinator relies on
 * and that the body-reentry seam is reachable for handlers.
 *
 * Companion runtime integration tests live in
 * `B11ContextBlocksRuntimeTest`, which proves the adapter is bound under
 * `BODY_INVOKER_CAPABILITY` and that the canonical dispatch path's
 * `dir` / `withEnv` / `timestamps` block Steps still work end-to-end.
 *
 * AGENTS.md test-efficiency:
 * - HF0 contract test (no run-loop).
 * - Each `@Nested` class groups tests by a single invariant.
 * - Per-class `@Timeout` so a deadlock on the openBodies map fails fast
 *   instead of hanging the suite.
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class CanonicalBodyInvokerAdapterTest {

    private fun path(vararg pairs: Pair<Int, String>): List<BlockSegment> =
        pairs.map { (i, k) -> BlockSegment(i, PluginStepId(k)) }

    private fun refAt(path: List<BlockSegment>) = BodyRefs.childBody(path)

    private fun noopFailure() =
        PipelineFailure(FailureKind.SCHEMA, "test-only failure")

    @Nested
    inner class OpenCloseContract {
        @Test
        fun `new adapter has zero open bodies`() {
            val adapter = CanonicalBodyInvokerAdapter()
            assertEquals(0, adapter.openBodyCount)
        }

        @Test
        fun `open registers a body reachable through isOpen and openBodyCount`() {
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(0 to "core.dir"))
            adapter.open(ref) { StepOutcome.Success }
            assertEquals(1, adapter.openBodyCount)
            assertTrue(adapter.isOpen(ref))
        }

        @Test
        fun `close removes the body and openBodyCount returns to zero`() {
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(0 to "core.dir"))
            adapter.open(ref) { StepOutcome.Success }
            adapter.close(ref)
            assertEquals(0, adapter.openBodyCount)
            assertFalse(adapter.isOpen(ref))
        }

        @Test
        fun `close on unknown ref is a no-op and does not throw`() {
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(7 to "core.unknown"))
            adapter.close(ref) // must not throw
            assertEquals(0, adapter.openBodyCount)
        }

        @Test
        fun `open replaces the runner for an already-registered body`() {
            // Defensive contract: re-opening the same bodyRef MUST replace the prior
            // entry (the previous runner is leaked but unreachable). This protects
            // against accidental double-open in the coordinator (would otherwise
            // re-execute the body twice).
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(1 to "core.dir"))
            adapter.open(ref) { StepOutcome.Failure(noopFailure()) }
            adapter.open(ref) { StepOutcome.Success }
            assertEquals(1, adapter.openBodyCount)
        }
    }

    @Nested
    inner class InvokeContract {
        @Test
        fun `invoke on unknown bodyRef returns Cancelled(ParentCancelled) and does not throw`() = runBlocking {
            val adapter = CanonicalBodyInvokerAdapter()
            val unknown = refAt(path(42 to "core.never-opened"))
            val outcome = adapter.invoke(unknown, BodyInvocationContext())
            assertEquals(BodyOutcome.Cancelled(CancellationReason.ParentCancelled), outcome)
        }

        @Test
        fun `invoke on a registered body wraps the runner outcome in Completed`() = runBlocking {
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(0 to "core.dir"))
            val expected = StepOutcome.Success
            adapter.open(ref) { expected }
            val outcome = adapter.invoke(ref, BodyInvocationContext())
            assertEquals(BodyOutcome.Completed(expected), outcome)
        }

        @Test
        fun `invoke on a registered body preserves a typed StepOutcome_Failure result`() = runBlocking {
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(0 to "core.retry"))
            val failure = StepOutcome.Failure(noopFailure())
            adapter.open(ref) { failure }
            val outcome = adapter.invoke(ref, BodyInvocationContext())
            // The body returned a typed Failure; the adapter wraps it in Completed.
            // The handler is responsible for folding the typed failure into its own
            // logic; the adapter never reclassifies it.
            assertEquals(BodyOutcome.Completed(failure), outcome)
        }

        @Test
        fun `invoke invokes the registered runner closure exactly once per call`() = runBlocking {
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(0 to "core.dir"))
            var invocations = 0
            adapter.open(ref) {
                invocations++
                StepOutcome.Success
            }
            adapter.invoke(ref, BodyInvocationContext())
            adapter.invoke(ref, BodyInvocationContext())
            assertEquals(2, invocations)
        }

        @Test
        fun `invoke after close returns Cancelled(ParentCancelled)`() = runBlocking {
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(0 to "core.dir"))
            adapter.open(ref) { StepOutcome.Success }
            adapter.close(ref)
            val outcome = adapter.invoke(ref, BodyInvocationContext())
            assertEquals(BodyOutcome.Cancelled(CancellationReason.ParentCancelled), outcome)
        }
    }

    @Nested
    inner class BodyRefEncoding {
        @Test
        fun `distinct bodyPaths produce distinct bodyRefs`() {
            val adapter = CanonicalBodyInvokerAdapter()
            val a = refAt(path(0 to "core.dir"))
            val b = refAt(path(0 to "core.withEnv"))
            adapter.open(a) { StepOutcome.Success }
            adapter.open(b) { StepOutcome.Success }
            assertEquals(2, adapter.openBodyCount)
            assertTrue(adapter.isOpen(a))
            assertTrue(adapter.isOpen(b))
        }

        @Test
        fun `BodyRefs encodes the body kind prefix deterministically`() {
            // The bodyRef's encoded string must carry the "body" prefix and the
            // segments in canonical order — this is the contract downstream tools
            // and the durable coordinator depend on.
            val ref = refAt(path(1 to "core.dir"))
            assertTrue(ref.encoded.startsWith("body/"))
            assertTrue(ref.encoded.contains("core.dir"))
        }

        @Test
        fun `nested body paths preserve every BlockSegment in the encoded ref`() {
            val nested = BodyRefs.childBody(
                listOf(
                    BlockSegment(0, PluginStepId("core.dir")),
                    BlockSegment(1, PluginStepId("core.withEnv")),
                    BlockSegment(0, PluginStepId("core.timestamps")),
                ),
            )
            // Encoded must carry every step key in order — this is the bodyPath
            // identity the durable coordinator uses to correlate journal rows.
            assertTrue(nested.encoded.contains("core.dir"))
            assertTrue(nested.encoded.contains("core.withEnv"))
            assertTrue(nested.encoded.contains("core.timestamps"))
        }
    }

    @Nested
    inner class RunnerClosureSemantics {
        @Test
        fun `runner is replaced not appended when open is called twice`() = runBlocking {
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(0 to "core.dir"))
            val first = StepOutcome.Failure(noopFailure())
            val second = StepOutcome.Success
            adapter.open(ref) { first }
            adapter.open(ref) { second }
            // invoke reads the latest entry, not a list
            val outcome = adapter.invoke(ref, BodyInvocationContext())
            assertSame(second, (outcome as BodyOutcome.Completed).outcome)
        }

        @Test
        fun `runner captures the canonical body closure environment via suspend`() = runBlocking {
            // The runner is a suspend lambda that closes over the canonical
            // `invokeBodyChildren` invocation. We verify a similar closure shape
            // here: a suspend lambda that reads a captured counter without
            // racing the adapter.
            val adapter = CanonicalBodyInvokerAdapter()
            val ref = refAt(path(0 to "core.dir"))
            var captured = 0
            adapter.open(ref) {
                captured = 42
                StepOutcome.Success
            }
            adapter.invoke(ref, BodyInvocationContext())
            assertEquals(42, captured)
        }
    }

    @Nested
    inner class SingleSharedLoopLaw {
        @Test
        fun `adapter never accepts a BlockStepNode or a StepNode list as a parameter`() {
            // The single shared-loop law (B10/W1d): only `invokeBodyChildren` in
            // CanonicalDurableRunCoordinator is allowed to iterate
            // `block.body.withIndex()`. The runtime contract: this adapter
            // exposes NO method that takes a `BlockStepNode` or any list of
            // step nodes. If a future change ever exposes such a method,
            // `ConcreteBodyRoutingDebt` would creep and this contract test
            // fails here first.
            val iteratesBodies = CanonicalBodyInvokerAdapter::class.java.declaredMethods
                .any { m ->
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    params.contains("BlockStepNode") ||
                        (params.contains("List") && params.contains("StepNode"))
                }
            assertFalse(iteratesBodies) {
                "CanonicalBodyInvokerAdapter must NEVER accept a BlockStepNode / StepNode list — " +
                    "that would re-introduce a body-child loop outside the canonical coordinator."
            }
        }
    }
}
