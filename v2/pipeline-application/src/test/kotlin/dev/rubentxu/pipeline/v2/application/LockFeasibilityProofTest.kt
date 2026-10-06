package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyContinuation
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * RP6-A / WU-091 — **viability proof** for the shape chosen in
 * `docs/v2/07-uat/RP6A_LOCK_CHARACTERIZATION.md`.
 *
 * ## What this file is
 *
 * `lock` does not exist. There is no behaviour to characterize, so this file does not
 * characterize anything. It tests a CLAIM the characterization makes:
 *
 * > `lock` needs a body that is **conditional** — it may not run at all (contention,
 * > `skipIfLocked`, allocation timeout) — and a `BodyExecutionPolicy` cannot express
 * > that, because every case in that closed family presumes the body runs. A
 * > `HANDLER_CONTINUATION` owner can, because the handler decides WHETHER to invoke
 * > the bound continuation.
 *
 * If that claim is false, the WU-091 design is wrong and cheaper to learn now than
 * after a `StepDefinition`, a DSL façade and a burn-down. So the claim is executed.
 *
 * The lock coordinator here is a **test double**, deliberately not production code:
 * this file proves the SHAPE is expressible, it does not implement `core.lock`.
 *
 * ## What this file is NOT
 *
 * It does not claim `core.lock` is implemented, designed in detail, or certified. It
 * does not cover the `LOCK_COORDINATION_CAPABILITY` binding (that seam is already
 * proven by `core.sh` / `SHELL_OPERATIONS_CAPABILITY`), nor cancellation, nor
 * re-acquisition on resume. Those are WU-091 implementation rows, not viability.
 *
 * ## Rows
 *
 *  - [the_body_runs_only_when_the_lock_was_acquired] the load-bearing claim. A held
 *    resource must produce ZERO child effects, not a substituted engine run.
 *  - [a_declined_acquisition_releases_nothing] never acquired ⇒ never released; a
 *    release of a lock this run never held would corrupt a foreign holder.
 *  - [the_lock_is_released_after_the_body_completes] the happy-path bracket.
 *  - [a_body_that_reports_failure_still_releases_and_fails_the_step] the release law on
 *    the failure RETURN path, AND the finding that a body failure must be carried by the
 *    handler's own typed output ([TypedStepOutput]), or `lock` would report Success over
 *    a body that failed.
 *  - [the_body_effects_reach_the_journal] the lock Step sits on the durable spine.
 *
 * ## A row that was here, and was removed because it lied
 *
 * This file originally asserted "a failing body still releases", with the release
 * written as a Kotlin `finally`. Mutating the `finally` into a plain
 * release-then-return did **not** turn the test red, which means the test was not
 * testing the `finally` at all. The reason is a real property of the engine:
 * [BodyOutcome] is a closed ADT of exactly `Completed(outcome)` and
 * `Cancelled(reason)` — a body signals failure by RETURNING
 * `Completed(StepOutcome.Failure)`, never by throwing. A throw across the continuation
 * would be an engine defect, not an expected operational outcome.
 *
 * So the release contract that actually matters is "release after `invoke` returns,
 * whatever it returned", and that is what the surviving row verifies. The `finally` is
 * kept in the handler as defence-in-depth against an engine defect; it is explicitly
 * NOT a tested property, and claiming otherwise would be a test that manufactures
 * confidence it has not earned.
 */
@Timeout(30)
class LockFeasibilityProofTest {

    private val lockKey = PluginStepId("test.lock")
    private val failKey = PluginStepId("test.failing")

    // ---------------------------------------------------------------- the double

    /**
     * Stand-in for the not-yet-existing `LockCoordinatorPort`.
     *
     * Models only what the viability claim needs: an exclusive, named hold. Note it is
     * NOT re-entrant — a nested hold of the same resource would be declined. Whether
     * that is correct is an open WU-091 decision (Jenkins re-enters per build), so this
     * file does not assert anything about nesting.
     */
    private class TestLockCoordinator {
        private val held = ConcurrentHashMap.newKeySet<String>()

        /** Ordered log of acquisition attempts, including declined ones. */
        val attempts = mutableListOf<Boolean>()

        /** Ordered log of successful releases. */
        val releases = mutableListOf<String>()

        fun tryAcquire(resource: String): Boolean {
            val acquired = held.add(resource)
            attempts += acquired
            return acquired
        }

        fun release(resource: String) {
            if (held.remove(resource)) releases += resource
        }

        /** Simulates another run / process already holding the resource. */
        fun holdExternally(resource: String) {
            held.add(resource)
        }

        fun isHeld(resource: String): Boolean = resource in held
    }

    // ---------------------------------------------------------------- the Steps

    data class LockInput(val resource: String)

    /**
     * Carries its own [StepOutcome] so `CommonExecutionBoundary` can project the body's
     * verdict as the lock Step's own verdict. Without this, a body that failed would be
     * reported as a successful `lock`.
     */
    data class LockOutput(
        val resource: String,
        val bodyRan: Boolean,
        override val outcome: StepOutcome,
    ) : TypedStepOutput

    private val coordinator = TestLockCoordinator()

    private val lockInputCodec = object : StepCodec<LockInput> {
        override fun encode(value: LockInput): EncodedStepValue =
            EncodedStepValue("{\"resource\":\"${value.resource}\"}")

        override fun decode(encoded: EncodedStepValue): LockInput {
            val match = Regex("\"resource\"\\s*:\\s*\"([^\"]*)\"").find(encoded.value)
                ?: throw IllegalArgumentException("invalid lock payload")
            return LockInput(match.groupValues[1])
        }
    }

    private val lockOutputCodec = object : StepCodec<LockOutput> {
        private val json = Json
        override fun encode(value: LockOutput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(String.serializer(), value.bodyRan.toString()))

        override fun decode(encoded: EncodedStepValue): LockOutput =
            LockOutput("decoded", encoded.value.toBoolean(), StepOutcome.Success)
    }

    /**
     * A `lock` shaped exactly as the characterization decides: the handler brackets its
     * own body with acquire/release and the ENGINE never substitutes its own semantics.
     */
    private val lockDefinition = object : StepDefinition<LockInput, LockOutput> {
        override val contract = StepContract(
            key = lockKey,
            descriptor = StepDescriptor(
                stepId = lockKey.value,
                name = "lock",
                configRef = "",
                pluginId = "test.lock",
                pluginVersion = "0.1.0",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.HANDLER_CONTINUATION,
                        policy = BodyExecutionPolicy.Sequential,
                    ),
                    introduces = null,
                ),
            ),
            inputCodec = lockInputCodec,
            outputCodec = lockOutputCodec,
            requiredCapabilities = setOf(BODY_CONTINUATION_CAPABILITY),
        )

        override val handler = StepHandler<LockInput, LockOutput> { input, context ->
            if (!coordinator.tryAcquire(input.resource)) {
                // Contention (or skipIfLocked): the body is NOT invoked. This is the row
                // the whole viability claim rests on.
                return@StepHandler LockOutput(input.resource, bodyRan = false, StepOutcome.Success)
            }
            try {
                val continuation: BodyContinuation =
                    context.capabilities.get(BODY_CONTINUATION_CAPABILITY)
                val bodyOutcome = continuation.invoke(BodyInvocationContext())
                val outcome =
                    (bodyOutcome as? BodyOutcome.Completed)?.outcome ?: StepOutcome.Success
                LockOutput(input.resource, bodyRan = true, outcome)
            } finally {
                coordinator.release(input.resource)
            }
        }
    }

    /** A child Step that always fails, to drive the release-on-failure row. */
    private val failDefinition = object : StepDefinition<String, TypedStepOutput> {
        override val contract = StepContract(
            key = failKey,
            descriptor = StepDescriptor(
                stepId = failKey.value,
                name = "failing",
                configRef = "",
                pluginId = "test.failing",
                pluginVersion = "0.1.0",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
            ),
            inputCodec = object : StepCodec<String> {
                override fun encode(value: String) = EncodedStepValue(value)
                override fun decode(encoded: EncodedStepValue) = encoded.value
            },
            outputCodec = object : StepCodec<TypedStepOutput> {
                private val json = Json
                override fun encode(value: TypedStepOutput) =
                    EncodedStepValue(json.encodeToString(String.serializer(), "failed"))
                override fun decode(encoded: EncodedStepValue): TypedStepOutput =
                    FailureOutput
            },
            requiredCapabilities = emptySet(),
        )

        override val handler = StepHandler<String, TypedStepOutput> { _, _ -> FailureOutput }
    }

    private object FailureOutput : TypedStepOutput {
        override val outcome: StepOutcome = StepOutcome.Failure(
            PipelineFailure(FailureKind.SCRIPT, "deliberate body failure for the lock proof"),
        )
    }

    // ---------------------------------------------------------------- harness

    @BeforeEach
    fun reset() {
        coordinator.attempts.clear()
        coordinator.releases.clear()
    }

    private fun registry() = StepRegistryBuilder().apply {
        add(lockDefinition)
        add(failDefinition)
        CoreEchoStep.registerInto(this)
    }.build()

    private fun harness(
        tag: String,
    ): Triple<CanonicalDurableRunCoordinator, InMemoryOperationJournal, InMemoryEventStore> {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val events = InMemoryEventStore()
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("lock feasibility stub"),
                )
            },
            controlDirRoot = Files.createTempDirectory("lock-feasibility-$tag").resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
        )
        return Triple(coordinator, journal, events)
    }

    private fun stage(vararg nodes: StepNode) = CompiledPipeline(
        id = DefinitionId("lock-feasibility"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(id = StageId("B"), name = "B", body = StageBody.Steps(nodes.toList())),
        ),
    )

    private fun lockBlock(resource: String, vararg children: StepNode) = BlockStepNode(
        id = StepId("test/lock-1"),
        pluginStepId = lockKey,
        payload = VersionedStepPayload("dsl-v1", lockInputCodec.encode(LockInput(resource)).value),
        body = children.toList(),
    )

    private fun echoChild(text: String) = OpaqueStepNode(
        id = StepId("test/lock-1/echo-1"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", "{\"kind\":\"echo\",\"text\":\"$text\"}"),
    )

    private fun failingChild() = OpaqueStepNode(
        id = StepId("test/lock-1/fail-1"),
        pluginStepId = failKey,
        payload = VersionedStepPayload("dsl-v1", "fail"),
    )

    private fun bodyRows(journal: InMemoryOperationJournal, runId: String): List<String> =
        journal.listForRun(runId).map { it.id }.filter { it.contains("-bp") }

    // ---------------------------------------------------------------- rows

    @Test
    fun `the body runs only when the lock was acquired`() = runBlocking {
        coordinator.holdExternally("staging")

        val (coordinatorRun, journal, _) = harness("held")
        val outcome = coordinatorRun.run(stage(lockBlock("staging", echoChild("must-not-run"))), RunId("lf1"))

        assertEquals(
            listOf(false),
            coordinator.attempts,
            "the handler must have attempted exactly one acquisition and been declined",
        )
        assertEquals(
            emptyList<String>(),
            bodyRows(journal, "lf1"),
            "a lock that was NOT acquired must produce ZERO child effects; the engine must " +
                "not substitute its own body semantics for the Step's",
        )
        assertEquals(
            RunOutcome.Success,
            outcome,
            "a declined acquisition is a valid outcome for the Step itself (Jenkins skipIfLocked)",
        )
    }

    @Test
    fun `a declined acquisition releases nothing`() = runBlocking {
        coordinator.holdExternally("staging")

        val (coordinatorRun, _, _) = harness("norelease")
        coordinatorRun.run(stage(lockBlock("staging", echoChild("x"))), RunId("lf2"))

        assertEquals(
            emptyList<String>(),
            coordinator.releases,
            "releasing a lock this run never acquired would break a foreign holder",
        )
        assertTrue(
            coordinator.isHeld("staging"),
            "the external holder must still hold the resource after the declined run",
        )
    }

    @Test
    fun `the lock is released after the body completes`() = runBlocking {
        val (coordinatorRun, journal, _) = harness("happy")

        val outcome = coordinatorRun.run(stage(lockBlock("staging", echoChild("ran"))), RunId("lf3"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(listOf(true), coordinator.attempts, "the acquisition must have succeeded")
        assertEquals(
            listOf("staging"),
            coordinator.releases,
            "the lock must be released once the body completes",
        )
        assertFalse(coordinator.isHeld("staging"), "nothing may remain held after the run")
        assertEquals(
            1,
            bodyRows(journal, "lf3").size,
            "the body effect must have been journaled under the lock",
        )
    }

    @Test
    fun `a body that reports failure still releases and fails the step`() = runBlocking {
        val (coordinatorRun, _, _) = harness("failing")

        val outcome = coordinatorRun.run(stage(lockBlock("staging", failingChild())), RunId("lf4"))

        assertEquals(
            listOf("staging"),
            coordinator.releases,
            "a body that reports failure must still release the lock; otherwise the resource " +
                "leaks forever and no other run can ever take it",
        )
        assertFalse(coordinator.isHeld("staging"))
        assertTrue(
            outcome is RunOutcome.Failure,
            "a body failure must be carried by the lock Step's OWN typed output; without " +
                "TypedStepOutput the boundary reports a successful lock over a failed body. " +
                "Observed $outcome",
        )
    }

    @Test
    fun `the body effects reach the journal`() = runBlocking {
        val (coordinatorRun, journal, _) = harness("journal")

        coordinatorRun.run(stage(lockBlock("staging", echoChild("durable"))), RunId("lf5"))

        assertTrue(
            journal.listForRun("lf5").isNotEmpty(),
            "a HANDLER_CONTINUATION lock Step must sit on the durable spine, not beside it",
        )
    }
}
