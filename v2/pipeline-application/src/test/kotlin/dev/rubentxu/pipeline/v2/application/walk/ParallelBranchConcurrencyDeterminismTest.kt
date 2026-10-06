package dev.rubentxu.pipeline.v2.application.walk

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.StepLifecycleContext
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * HAR-PAR-001 — the replacement for a timing test that was certifying nothing.
 *
 * ## What was wrong with what this replaces
 *
 * The previous `WalkParallelFrameConcurrencyTest` built a `ParallelFrame` DATA CLASS and then
 * ran `coroutineScope { async(Dispatchers.IO) { Thread.sleep(100) } }` by hand. It never called
 * `walkParallelFrame`, `ParallelStageEngine.launchBranches()`, or any production code at all. It
 * asserted that `kotlinx.coroutines` is concurrent — which it is — and that is what a reviewer
 * reading a green gate would reasonably believe PipelineK had been certified for.
 *
 * Its 150 ms wall-clock budget over an ideal 100 ms of work is a 50 % margin, and a `sleep`
 * under a loaded JVM is exactly the thing that blows a 50 % margin. It therefore flaked, and it
 * flaked in the direction of saying something alarming: the failure message is *"branches
 * executed sequentially instead of concurrently"*, which is a lie about the code.
 *
 * ## The four recorded occurrences
 *
 * ```text
 * 156 ms  S2C_DIRECTIVE_COMPOSITION_RECEIPT.md §5  (debt observed, threshold deliberately untouched)
 * 160 ms  S4-R1-D block gate
 * 177 ms  S4-R1-BAC block gate, under full-gate load
 * 151 ms  in isolation, minutes after a 100 ms PASS in the same isolation
 * ```
 *
 * ## The replacement
 *
 * A DETERMINISTIC BARRIER that crosses the production authority:
 *
 * ```text
 * ParallelStageEngine.launchBranches() → supervisorScope → async(Dispatchers.Default)
 *     → StepDispatchEngine → registry → StepHandler
 * ```
 *
 * Each branch's handler announces arrival and then waits for all three. The barrier can only
 * open if the three handlers are alive at the same time, so:
 *
 * - CONCURRENT (production today) → all three enter, the barrier opens, the run completes.
 * - SEQUENTIAL (the regression this must catch) → branch 0 enters and waits for branches 1 and 2,
 *   which never start, and the [withTimeout] watchdog fires. The test goes RED with a message
 *   that names the actual cause.
 *
 * There is no performance assertion anywhere in this file. The timeout is a DEADLOCK WATCHDOG
 * with a deliberately absurd budget, because a watchdog that trips only when something is truly
 * deadlocked is the only honest use of a timeout in a concurrency test.
 */
class ParallelBranchConcurrencyDeterminismTest {

    @Test
    fun `three parallel branches are dispatched concurrently, proved by a barrier and not by a clock`() =
        runBlocking {
            val barrier = BarrierStep(branchCount = 3)
            val registry = StepRegistryBuilder().also { r ->
                CoreStepRegistryFactory.registry().let { composed ->
                    // the core registry is already populated; mirror its registrations, then add
                    // the barrier so the parallel stage can dispatch it alongside real Steps
                    composed.keys().forEach { r.add(composed.definition(it)!!) }
                }
                r.add(barrier)
            }.build()
            val clock = SystemClock()
            val journal = InMemoryOperationJournal(clock)
            val sink = InMemoryEventStore()

            val coordinator = CoordinatorFixture.default(clock, journal, sink, stepRegistry = registry)

            val outcome = withTimeout(WATCHDOG_SECONDS * 1000) {
                coordinator.run(threeBranchPipeline(), dev.rubentxu.pipeline.v2.domain.RunId(RUN_ID))
            }

            // Reaching this line at all IS the concurrency proof: every handler returned, which
            // means every handler observed the other two. A sequential engine would have parked
            // the first handler on the watchdog above and never produced an outcome.
            assertEquals(3, barrier.entered.get(), "every branch handler must have been entered")
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.RunOutcome.Success,
                outcome,
                "the parallel stage must complete successfully once the barrier opens; got $outcome",
            )
        }

    // ------------------------------------------------------------------ the barrier

    /**
     * A Step whose handler cannot finish until every sibling has started. That is what makes
     * this a test of concurrency rather than a test of timing: no arrangement of a SEQUENTIAL
     * dispatcher can satisfy it, and no amount of scheduler luck is required to satisfy it.
     */
    private class BarrierStep(private val branchCount: Int) : StepDefinition<String, String> {
        val entered = AtomicInteger(0)
        private val release = CompletableDeferred<Unit>()

        override val contract: StepContract<String, String> = StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "barrier",
                configRef = "",
                executionLocation = ExecutionLocation.AGENT,
                // READ_ONLY + MEMOIZED: the barrier proves DISPATCH concurrency, so it must
                // not introduce a replay dimension of its own. Concurrency is orthogonal to
                // replay, and a step that conflated them would be a second thing to certify.
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
            ),
            inputCodec = IdentityCodec,
            outputCodec = IdentityCodec,
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<String, String> = StepHandler { input, _ ->
            if (entered.incrementAndGet() >= branchCount) {
                release.complete(Unit)
            }
            // Blocking here is the POINT. The watchdog in the caller is what turns "the
            // dispatcher is sequential" from a hang into a failure with a readable cause.
            release.await()
            input
        }

        companion object {
            val KEY = PluginStepId("test.parallel.barrier")

            /** Symmetric trivial codec: this Step transports its input, it does not transform it. */
            val IdentityCodec = object : StepCodec<String> {
                override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)

                override fun decode(encoded: EncodedStepValue): String = encoded.value
            }
        }
    }

    // ------------------------------------------------------------------ the pipeline

    private fun threeBranchPipeline(): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("par-concurrency-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("par"),
                name = "par",
                body = StageBody.Parallel(
                    (0 until 3).map { b ->
                        StageNode(
                            id = StageId("br$b"),
                            name = "br$b",
                            body = StageBody.Steps(
                                listOf(
                                    OpaqueStepNode(
                                        id = StepId("br$b/barrier"),
                                        pluginStepId = BarrierStep.KEY,
                                        payload = VersionedStepPayload("dsl-v1", """{"branch":"br$b"}"""),
                                    ),
                                ),
                            ),
                        )
                    },
                ),
            ),
        ),
    )

    private companion object {
        const val RUN_ID = "par-concurrency"

        /**
         * A deadlock watchdog, in seconds, and deliberately absurd. The slowest honest
         * observation of three coroutines reaching a barrier is microseconds; anything that
         * takes this long is not "slow", it is stuck. A tight budget here would recreate the
         * flakiness this file exists to remove.
         */
        const val WATCHDOG_SECONDS = 30L
    }
}
