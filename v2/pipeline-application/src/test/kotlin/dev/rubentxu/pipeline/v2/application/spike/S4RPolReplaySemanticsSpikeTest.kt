package dev.rubentxu.pipeline.v2.application.spike

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedExecution
import dev.rubentxu.pipeline.v2.application.durable.buildDefaultExecutionBoundary
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedArtifactRuntime
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedOperationRuntime
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedRegistryInvoker
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.ReturnStdout
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * SPIKE S4-R-POL — "Replay Semantics Truth". MEASUREMENT ONLY. ZERO production change.
 *
 * ## The question
 *
 * `docs/v2/07-uat/S4_R0_REPLAY_IDENTITY_CONFLICT_MEMO.md` §5 derived, **statically**, an
 * eight-row matrix claiming the CANONICAL durable path and the SCRIPTED durable path take
 * different replay decisions for the same `StepDescriptor` and the same journal state. The memo
 * says so itself: the matrix is a hypothesis, and this spike is asked to confirm or refute it
 * row by row.
 *
 * This class is the answer. It drives BOTH surfaces with REAL code — no stub of either
 * authority — and records four observables per (surface, step, journal state):
 *
 *  1. **handler invocations** — real effects. Counted at the execution boundary on the canonical
 *     side and at the observable event plane on the scripted side (a Step emits exactly one
 *     domain event per handler invocation; `S4IdentityOrdinalFalsificationTest` relies on the
 *     same plane).
 *  2. **what is returned** — the typed value, or the typed failure and its `FailureKind`.
 *  3. **journal terminal state** — the status the run actually left behind.
 *  4. **the replay policy that produced the fingerprint** — recovered by brute force from the
 *     stored hash ([policyOf]), not by reading a source literal. That matters precisely because
 *     one of the two surfaces hardcodes `ReplayPolicy.MEMOIZED`.
 *
 * ## Why prior journal rows are cloned rather than recomputed
 *
 * Preparing "a prior FAILED/RUNNING row" needs a fingerprint that matches what the surface will
 * recompute, or the divergence gate refuses the invocation before the replay kernel is ever
 * reached and the row measures nothing. So every spine FIRST runs fresh — letting PRODUCTION
 * write the row with its own fingerprint — and then re-appends a [MemoizedOperation] carrying
 * that production fingerprint with the target status. `InMemoryOperationJournal.append` upserts
 * on `(opId, attempt)` and preserves `fingerprint`/`input` on conflict, so the identity the
 * engine compares against is the engine's own.
 *
 * ## Honesty contract
 *
 * The assertions pin what the code ACTUALLY did, and each carries a `MEASURED CONFIRMS MEMO` /
 * `REFUTES MEMO` marker. A row that refutes the memo turns this suite red; no assertion is
 * relaxed to make a hypothesis hold. States that cannot be observed are reported as such.
 */
@Timeout(180)
class S4RPolReplaySemanticsSpikeTest {

    // ------------------------------------------------------------------
    // Measurement vocabulary
    // ------------------------------------------------------------------

    /** One measured (surface, step, prior-state) triple. */
    private data class Row(
        val surface: String,
        val step: String,
        val prior: Prior,
        val handlerInvocations: Int,
        val returned: String,
        val journalTerminal: String,
        val fingerprintPolicy: String,
    ) {
        fun render(): String =
            "| $surface | $step | ${prior.label} | invocations=$handlerInvocations" +
                " | returned=$returned | journal=$journalTerminal" +
                " | fingerprintPolicy=$fingerprintPolicy |"
    }

    private val report = mutableListOf<Row>()

    /**
     * The semantic class of a rendered outcome: the leading word, with any parenthetical
     * rendering detail dropped. Two harnesses may describe the same `Success` differently; the
     * convergence assertions must not depend on which phrasing a harness happens to use.
     */
    private fun successClass(rendered: String): String = rendered.substringBefore('(').trim()

    private fun rowOf(spine: Spine, step: String, prior: Prior): Row {
        val row = Row(
            surface = spine.surface,
            step = step,
            prior = prior,
            handlerInvocations = spine.handlerInvocations(),
            returned = spine.lastReturned,
            journalTerminal = spine.terminalStatus(),
            fingerprintPolicy = spine.journalRow()?.let { policyOf(it) } ?: "NO_ROW",
        )
        report += row
        return row
    }

    /**
     * Recovers WHICH [ReplayPolicy] produced [DurableOperation.fingerprint] by recomputing the
     * hash under each policy and comparing. A measurement of the hash, not a restatement of the
     * literal that was passed in.
     */
    private fun policyOf(row: DurableOperation): String =
        ReplayPolicy.entries.firstOrNull { candidate ->
            Fingerprint.compute(row.input, row.input.stepId, candidate, row.attempt) == row.fingerprint
        }?.name ?: "UNRECOGNISED"

    // ------------------------------------------------------------------
    // Spine contract
    // ------------------------------------------------------------------

    private interface Spine {
        val surface: String
        var lastReturned: String

        /** Handler invocations observed DURING the measured run only. */
        fun handlerInvocations(): Int

        fun journalRow(): DurableOperation?
        fun terminalStatus(): String
        fun seed(prior: Prior)
    }

    // ------------------------------------------------------------------
    // Canonical spine — real coordinator, real registry, real subprocess
    // ------------------------------------------------------------------

    /**
     * Pass-through counter. `commonExecutionBoundary` REPLACES the coordinator's boundary (it is
     * NOT handed to `ExecutionBoundaryFactory.build` as a recorder — see
     * `CanonicalDurableRunCoordinator.executionBoundary`), so this decorator must delegate to a
     * real boundary itself or nothing would execute at all. Counting only what the delegate
     * actually did keeps the effect real rather than simulated.
     */
    private class CountingBoundary(
        private val delegate: CommonExecutionBoundary,
        private val onCall: () -> Unit,
    ) : CommonExecutionBoundary {
        override suspend fun execute(
            prepared: PreparedExecution,
            context: CanonicalRuntimeContext,
        ): CommonExecutionResult {
            onCall()
            return delegate.execute(prepared, context)
        }
    }

    /**
     * The production canonical surface: [CanonicalDurableRunCoordinator] over
     * [CoreStepRegistryFactory.registry] — the same composition `CoordinatorFixture` builds, with
     * the single deliberate difference that `controlDirRoot` is a REAL temp directory rather than
     * `null`, so the `core.sh` `RunningSubprocessRecovery` hook is ARMED. With
     * `controlDirRoot == null` the hook is inert by contract and row 4 would be unmeasurable.
     */
    private class CanonicalSpine(
        private val runId: String,
        private val stepKey: PluginStepId,
        payloadJson: String,
    ) : Spine {
        override val surface = "CANONICAL"
        override var lastReturned: String = "NOT_RUN"

        private val journal = InMemoryOperationJournal(SystemClock())
        private val events = InMemoryEventStore()

        /** Reassignable so row 4 can point recovery at a control dir that never held a process. */
        private var controlDir: Path = Files.createTempDirectory("s4rpol-canon-ctrl-")

        private var boundaryCalls = 0

        private val pipeline = CompiledPipeline(
            id = DefinitionId("s4-r-pol-$runId"),
            source = SourceDescriptor("s4rpol.pipeline.kts", Digest("s4rpol-source")),
            pluginLockDigest = Digest("s4rpol-lock"),
            stages = listOf(
                StageNode(
                    id = StageId("spike"),
                    name = "spike",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("spike/step-0"),
                                pluginStepId = stepKey,
                                payload = VersionedStepPayload("dsl-v1", payloadJson),
                            ),
                        ),
                    ),
                ),
            ),
        )

        /** A brand-new coordinator per call: models a process restart against the same journal. */
        suspend fun run(): RunOutcome {
            val coordinator = CanonicalDurableRunCoordinator(
                dispatcher = CanonicalNodeDispatcher(),
                journal = journal,
                cursorStore = InMemoryReplayCursorStore(SystemClock()),
                clock = SystemClock(),
                effectReplayPolicy = DefaultEffectReplayPolicy(),
                eventSink = events,
                credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
                controlDirRoot = controlDir,
                shOptions = ShOptions.EMPTY,
                commonExecutionBoundary = CountingBoundary(
                    delegate = buildDefaultExecutionBoundary(
                        dispatcher = CanonicalNodeDispatcher(),
                        invocationExecutor = null,
                        stepRegistry = CoreStepRegistryFactory.registry(),
                    ),
                    onCall = { boundaryCalls++ },
                ),
                stepRegistry = CoreStepRegistryFactory.registry(),
            )
            val outcome = coordinator.run(pipeline, RunId(runId))
            lastReturned = outcome.describe()
            return outcome
        }

        fun resetCounters() {
            boundaryCalls = 0
        }

        /**
         * Points the recovery hook at a control directory that has never held a process, so the
         * RUNNING row is reconciled against "worker died, nothing to reattach" — the only
         * reattach state a spike can produce without spawning a genuinely long-running process.
         */
        fun useVirginControlDir() {
            controlDir = Files.createTempDirectory("s4rpol-virgin-ctrl-")
        }

        override fun handlerInvocations(): Int = boundaryCalls

        override fun journalRow(): DurableOperation? = journal.listForRun(runId).lastOrNull()

        override fun terminalStatus(): String = journalRow()?.status?.name ?: "NO_ROW"

        override fun seed(prior: Prior) = seedPrior(journal, runId, prior)
    }

    // ------------------------------------------------------------------
    // Scripted spine — the harness S4IdentityOrdinalFalsificationTest already proves
    // ------------------------------------------------------------------

    /**
     * Counts handler invocations at the SEAM, not inferred from the event plane.
     *
     * `ScriptedRegistryInvoker.invoke` resolves the definition from the registry it was
     * constructed with (`RegistryExecutionPreparation.prepare(registry, key, …)`), so wrapping
     * that registry observes the real handler call while leaving the codec, the descriptor, the
     * required capabilities and the provider metadata untouched. The same registry instance
     * carries the PRODUCTION definitions, so nothing about the Step under test is substituted.
     */
    private class CountingRegistry(
        private val delegate: StepRegistry,
        private val onHandlerInvoke: () -> Unit,
    ) : StepRegistry {
        override fun register(definition: StepDefinition<*, *>) = delegate.register(definition)

        override fun register(registration: StepRegistration<*, *>) = delegate.register(registration)

        override fun providerOf(key: PluginStepId) = delegate.providerOf(key)

        override fun contains(key: PluginStepId) = delegate.contains(key)

        override fun keys(): Set<PluginStepId> = delegate.keys()

        @Suppress("UNCHECKED_CAST")
        override fun definition(key: PluginStepId): StepDefinition<*, *>? {
            val original = delegate.definition(key) ?: return null
            val typed = original as StepDefinition<Any, Any>
            return object : StepDefinition<Any, Any> {
                override val contract: StepContract<Any, Any> = typed.contract
                override val handler: StepHandler<Any, Any> =
                    StepHandler { input, context ->
                        onHandlerInvoke()
                        typed.handler.execute(input, context)
                    }
            }
        }
    }

    /**
     * The production scripted surface: [ScriptedArtifactRuntime] over [ScriptedRegistryInvoker]
     * with a real journal and the production registry — the same composition
     * `S4IdentityOrdinalFalsificationTest.Spine` builds. The capability bridge is the DEFAULT
     * `CanonicalRuntimeCapabilityAccess`, so the handlers reach the real `ShOperationsAdapter`
     * and the real execution-location substrate: the effects here are real, only the counting
     * seam is added.
     */
    private class ScriptedSpine(
        private val runId: String,
        private val entry: CompiledScriptedEntryPoint,
    ) : Spine {
        override val surface = "SCRIPTED"
        override var lastReturned: String = "NOT_RUN"

        private val journal = InMemoryOperationJournal(SystemClock())
        private val events = InMemoryEventStore()
        private val controlDir: Path = Files.createTempDirectory("s4rpol-script-ctrl-")

        private var handlerInvocations = 0

        private val runtime = ScriptedArtifactRuntime(
            operationRuntime = ScriptedOperationRuntime { error("registry-routed steps never use this") },
            registryInvoker = dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
                registry = CountingRegistry(CoreStepRegistryFactory.registry()) { handlerInvocations++ },
                journal = journal,
                eventSink = events,
                controlDirRoot = controlDir,
            ),
        )

        suspend fun run() {
            val before = handlerInvocations
            val outcome = runCatching { runtime.execute(runId, entry) }
            handlerInvocations -= before
            lastReturned = when {
                outcome.isSuccess -> "success(no exception)"
                outcome.exceptionOrNull() is PipelineStepException ->
                    "Failed(${(outcome.exceptionOrNull() as PipelineStepException).failure.kind})"
                else -> "threw(${outcome.exceptionOrNull()?.let { it::class.simpleName }})"
            }
        }

        fun resetCounters() {
            handlerInvocations = 0
        }

        override fun handlerInvocations(): Int = handlerInvocations

        override fun journalRow(): DurableOperation? = journal.listForRun(runId).lastOrNull()

        override fun terminalStatus(): String = journalRow()?.status?.name ?: "NO_ROW"

        override fun seed(prior: Prior) = seedPrior(journal, runId, prior)
    }

    // ------------------------------------------------------------------
    // Row 1 — core.sh / fresh
    // ------------------------------------------------------------------

    @Test
    fun `row 1 core sh fresh executes on both surfaces`() = runBlocking {
        val script = "echo s4rpol-row1"
        val canonical = canonicalShSpine("s4rpol-r1", script)
        val scripted = ScriptedSpine("s4rpol-r1", ShEntry(script))

        val canonicalOutcome = canonical.run()
        scripted.run()
        val canonicalRow = rowOf(canonical, "core.sh", Prior.FRESH)
        val scriptedRow = rowOf(scripted, "core.sh", Prior.FRESH)
        emit()

        assertEquals(RunOutcome.Success, canonicalOutcome, "canonical fresh run must succeed")
        assertEquals(1, canonicalRow.handlerInvocations, "canonical fresh: handler runs exactly once")
        assertEquals(1, scriptedRow.handlerInvocations, "scripted fresh: handler runs exactly once")
        assertEquals(
            canonicalRow.journalTerminal,
            scriptedRow.journalTerminal,
            "both fresh runs end SUCCEEDED — the memo's row 1 claims no divergence, and on the " +
                "DECISION it is right",
        )

        // The memo compares decisions. The fingerprint is a second, unmeasured axis, and it is
        // where the two surfaces already differ for a RERUN step.
        assertEquals("RERUN", canonicalRow.fingerprintPolicy, "canonical hashes under the DESCRIPTOR policy")
        assertEquals(
            "MEMOIZED",
            scriptedRow.fingerprintPolicy,
            "S4-R-POL MEASURED: scripted hashes under the hardcoded `ReplayPolicy.MEMOIZED` literal " +
                "instead of the descriptor's RERUN. The two surfaces persist DIFFERENT durable " +
                "identities for the same Step and the same journal state. Not in the memo's matrix.",
        )
    }

    // ------------------------------------------------------------------
    // Row 2 — core.sh / prior SUCCEEDED
    // ------------------------------------------------------------------

    @Test
    fun `row 2 core sh with a prior SUCCEEDED row reuses on both surfaces`() = runBlocking {
        val script = "echo s4rpol-row2"
        val canonical = canonicalShSpine("s4rpol-r2", script)
        val scripted = ScriptedSpine("s4rpol-r2", ShEntry(script))

        canonical.run() // fresh: PRODUCTION writes the SUCCEEDED row
        scripted.run()

        canonical.resetCounters()
        scripted.resetCounters()
        val canonicalOutcome = canonical.run()
        scripted.run()

        val canonicalRow = rowOf(canonical, "core.sh", Prior.SUCCEEDED)
        val scriptedRow = rowOf(scripted, "core.sh", Prior.SUCCEEDED)
        emit()

        assertEquals(RunOutcome.Success, canonicalOutcome, "canonical reuse settles success")
        assertEquals(
            0,
            canonicalRow.handlerInvocations,
            "MEASURED CONFIRMS MEMO ROW 2 (canonical half): RERUN + SUCCEEDED → SKIP. The handler " +
                "does not run — `ReplayPolicy.RERUN` does NOT mean what its name and its own " +
                "documentation say.",
        )
        assertEquals(0, scriptedRow.handlerInvocations, "MEASURED CONFIRMS MEMO ROW 2 (scripted half)")
        assertEquals("SUCCEEDED", canonicalRow.journalTerminal)
        assertEquals("SUCCEEDED", scriptedRow.journalTerminal)
    }

    // ------------------------------------------------------------------
    // Row 3 — core.sh / prior FAILED
    // ------------------------------------------------------------------

    @Test
        // ADR-0103 RPL-4 — THIS ROW IS NOW A CONVERGENCE MEASUREMENT, NOT A DIVERGENCE ONE.
        // The scripted half used to re-measure its own `when (status)` table and therefore
        // disagreed with canonical on these rows. The spike was right when it was written;
        // R1-A made `ScriptedRegistryInvoker` consume `DurableInvocationResolver.reconcileInvocation`
        // instead, so the two surfaces now decide from the SAME descriptor. The measurement
        // stands; the expectation it produced has changed, and this is the evidence for that.
    fun `row 3 core sh with a prior FAILED row converges between the surfaces`() = runBlocking {
        val script = "echo s4rpol-row3"
        val canonical = canonicalShSpine("s4rpol-r3", script)
        val scripted = ScriptedSpine("s4rpol-r3", ShEntry(script))

        canonical.run()
        scripted.run()
        canonical.seed(Prior.FAILED)
        scripted.seed(Prior.FAILED)

        canonical.resetCounters()
        scripted.resetCounters()
        val canonicalOutcome = canonical.run()
        scripted.run()

        val canonicalRow = rowOf(canonical, "core.sh", Prior.FAILED)
        val scriptedRow = rowOf(scripted, "core.sh", Prior.FAILED)
        emit()

        assertEquals(
            1,
            canonicalRow.handlerInvocations,
            "MEASURED CONFIRMS MEMO ROW 3 (canonical half): RERUN + FAILED → RERUN; the subprocess " +
                "re-executes and the journaled FAILED row is repaired.",
        )
        assertEquals(RunOutcome.Success, canonicalOutcome, "canonical: a failed run RESUMES to success")
        assertEquals("SUCCEEDED", canonicalRow.journalTerminal, "canonical re-run ends SUCCEEDED")

        assertEquals(
            canonicalRow.handlerInvocations,
            scriptedRow.handlerInvocations,
            "CONVERGENCE (scripted half): the scripted surface now RE-EXECUTES a FAILED row exactly " +
                "as canonical does, because the re-execution decision comes from the descriptor's " +
                "RERUN policy instead of a hand-written status table.",
        )
        assertEquals(
            successClass(canonicalRow.returned),
            successClass(scriptedRow.returned),
            "CONVERGENCE (scripted half): the refusal is gone; both surfaces return the re-executed " +
                "value. Compared by SEMANTIC CLASS rather than by the raw string: the two harnesses " +
                "render the same success with different wording ('success' vs " +
                "'success(no exception)'), and a string comparison would be coupling the assertion " +
                "to one harness's phrasing instead of to the behaviour.",
        )
        assertEquals(
            canonicalRow.journalTerminal,
            scriptedRow.journalTerminal,
            "CONVERGENCE (scripted half): both surfaces end in the same terminal status, so a " +
                "scripted FAILED row is repaired by the same rule that repairs a canonical one.",
        )
    }

    // ------------------------------------------------------------------
    // Row 4 — core.sh / prior RUNNING
    // ------------------------------------------------------------------

    @Test
    fun `row 4 core sh with a prior RUNNING row recovers on both surfaces`() = runBlocking {
        val script = "echo s4rpol-row4"
        val canonical = canonicalShSpine("s4rpol-r4", script)
        val scripted = ScriptedSpine("s4rpol-r4", ShEntry(script))

        canonical.run()
        scripted.run()
        canonical.seed(Prior.RUNNING)
        scripted.seed(Prior.RUNNING)

        // Recovery is pointed at a control directory that never held a process. What is measured
        // here is therefore the RECOVERY BRANCH being taken and its fail-closed outcome.
        // NOT measurable in a spike unit test, and NOT claimed: a successful REATTACH of a LIVE
        // process, which would require a genuinely long-running subprocess. See the report.
        canonical.useVirginControlDir()
        canonical.seed(Prior.RUNNING)

        canonical.resetCounters()
        scripted.resetCounters()
        val canonicalOutcome = canonical.run()
        scripted.run()

        val canonicalRow = rowOf(canonical, "core.sh", Prior.RUNNING)
        val scriptedRow = rowOf(scripted, "core.sh", Prior.RUNNING)
        emit()

        assertEquals(
            0,
            canonicalRow.handlerInvocations,
            "MEASURED CONFIRMS MEMO ROW 3/4 STRUCTURE (canonical half): the RUNNING row is routed to " +
                "the recovery hook, NOT to RERUN — the handler does not re-execute.",
        )
        assertTrue(
            canonicalRow.journalTerminal != "RUNNING",
            "MEASURED CONFIRMS MEMO ROW 4 (canonical half): canonical LEAVES the RUNNING state " +
                "(recovered terminal status = ${canonicalRow.journalTerminal}).",
        )
        assertTrue(
            canonicalOutcome is RunOutcome.Failure,
            "MEASURED: with no reattachable process the canonical recovery is FAIL-CLOSED " +
                "(${canonicalRow.returned}) — it does not assume success (UAT-REC-002).",
        )
        assertEquals(
            0,
            canonicalRow.handlerInvocations,
            "CONVERGENCE (scripted half): a scripted RUNNING row is now routed to the SAME recovery " +
                "hook as a canonical one. The fixture is handed a control dir that never held a " +
                "process, so recovery is fail-closed on both sides — the difference is no longer " +
                "that scripted refuses, but that both recover identically.",
        )
        assertEquals(
            "Failed(REPLAY_COMPATIBILITY)",
            scriptedRow.returned,
            "MEASURED CONFIRMS MEMO ROW 4 (scripted half): Failed(REPLAY_COMPATIBILITY) — " +
                "'no durable task can be reattached'.",
        )
        assertEquals(
            canonicalRow.journalTerminal,
            scriptedRow.journalTerminal,
            "CONVERGENCE: the RUNNING row leaves the RUNNING state on BOTH surfaces. A scripted " +
                "row is now recoverable by the same hook a canonical row is, so the two are no " +
                "longer differently-RECOVERABLE — the original row-4 finding.",
        )
    }

    // ------------------------------------------------------------------
    // Row 5 — core.pwd / fresh
    // ------------------------------------------------------------------

    @Test
    fun `row 5 core pwd fresh executes on both surfaces and the fingerprints agree`() = runBlocking {
        val canonical = canonicalPwdSpine("s4rpol-r5")
        val scripted = ScriptedSpine("s4rpol-r5", PwdEntry())

        val canonicalOutcome = canonical.run()
        scripted.run()
        val canonicalRow = rowOf(canonical, "core.pwd", Prior.FRESH)
        val scriptedRow = rowOf(scripted, "core.pwd", Prior.FRESH)
        emit()

        assertEquals(RunOutcome.Success, canonicalOutcome, "canonical core.pwd fresh run must succeed")
        assertEquals(1, canonicalRow.handlerInvocations, "canonical fresh: handler runs once")
        assertEquals(1, scriptedRow.handlerInvocations, "scripted fresh: handler runs once")
        assertEquals(
            canonicalRow.journalTerminal,
            scriptedRow.journalTerminal,
            "MEASURED CONFIRMS MEMO ROW 5: no divergence on a fresh run",
        )
        assertEquals(
            canonicalRow.fingerprintPolicy,
            scriptedRow.fingerprintPolicy,
            "S4-R-POL MEASURED: for a MEMOIZED descriptor the hardcoded scripted literal is " +
                "indistinguishable from the descriptor, because they are the same value. The " +
                "row-1 fingerprint divergence is specific to RERUN-declared Steps.",
        )
        assertEquals("MEMOIZED", canonicalRow.fingerprintPolicy)
    }

    // ------------------------------------------------------------------
    // Row 6 — core.pwd / prior SUCCEEDED
    // ------------------------------------------------------------------

    @Test
    fun `row 6 core pwd with a prior SUCCEEDED row reuses on both surfaces`() = runBlocking {
        val canonical = canonicalPwdSpine("s4rpol-r6")
        val scripted = ScriptedSpine("s4rpol-r6", PwdEntry())

        canonical.run()
        scripted.run()

        canonical.resetCounters()
        scripted.resetCounters()
        val canonicalOutcome = canonical.run()
        scripted.run()

        val canonicalRow = rowOf(canonical, "core.pwd", Prior.SUCCEEDED)
        val scriptedRow = rowOf(scripted, "core.pwd", Prior.SUCCEEDED)
        emit()

        assertEquals(RunOutcome.Success, canonicalOutcome, "canonical reuse settles success")
        assertEquals(
            0,
            canonicalRow.handlerInvocations,
            "MEASURED CONFIRMS MEMO ROW 6 (canonical half): MEMOIZED + READ_ONLY + SUCCEEDED → SKIP",
        )
        assertEquals(0, scriptedRow.handlerInvocations, "MEASURED CONFIRMS MEMO ROW 6 (scripted half)")
        assertEquals("SUCCEEDED", canonicalRow.journalTerminal)
        assertEquals("SUCCEEDED", scriptedRow.journalTerminal)
    }

    // ------------------------------------------------------------------
    // Row 7 — core.pwd / prior FAILED
    // ------------------------------------------------------------------

    @Test
    fun `row 7 core pwd with a prior FAILED row converges between the surfaces`() = runBlocking {
        val canonical = canonicalPwdSpine("s4rpol-r7")
        val scripted = ScriptedSpine("s4rpol-r7", PwdEntry())

        canonical.run()
        scripted.run()
        canonical.seed(Prior.FAILED)
        scripted.seed(Prior.FAILED)

        canonical.resetCounters()
        scripted.resetCounters()
        val canonicalOutcome = canonical.run()
        scripted.run()

        val canonicalRow = rowOf(canonical, "core.pwd", Prior.FAILED)
        val scriptedRow = rowOf(scripted, "core.pwd", Prior.FAILED)
        emit()

        assertEquals(
            1,
            canonicalRow.handlerInvocations,
            "MEASURED CONFIRMS MEMO ROW 7 (canonical half): MEMOIZED + READ_ONLY + FAILED → RERUN; " +
                "the observation is re-made and the row repaired.",
        )
        assertEquals("SUCCEEDED", canonicalRow.journalTerminal, "canonical re-run repairs the row")
        assertEquals(
            canonicalRow.handlerInvocations,
            scriptedRow.handlerInvocations,
            "CONVERGENCE (scripted half): MEMOIZED + READ_ONLY + FAILED re-observes, exactly as " +
                "canonical does. The decision now reads the descriptor, so the scripted surface " +
                "cannot keep a stricter private rule about when a FAILED row is retried.",
        )
        assertEquals(
            successClass(canonicalRow.returned),
            successClass(scriptedRow.returned),
            "CONVERGENCE (scripted half): both surfaces return the re-observed value instead of " +
                "refusing. Compared by semantic class — see the row-3 note on harness phrasing.",
        )
        assertEquals(
            canonicalRow.journalTerminal,
            scriptedRow.journalTerminal,
            "CONVERGENCE (scripted half): the row is repaired on both surfaces.",
        )
        assertEquals(RunOutcome.Success, canonicalOutcome, "canonical resumes to success")
    }

    // ------------------------------------------------------------------
    // Row 8 — core.error / NEVER / prior SUCCEEDED
    // ------------------------------------------------------------------

    @Test
    fun `row 8 core error NEVER aborts canonically and is UNREACHABLE from the scripted facade`() = runBlocking {
        // --- 8a: the canonical DECISION, measured directly against the authority the memo cites.
        val decision = DefaultEffectReplayPolicy().decide(
            replayPolicy = ReplayPolicy.NEVER,
            effects = setOf(Effect.ABORTS_PIPELINE),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(
            "ABORT",
            decision.name,
            "MEASURED CONFIRMS MEMO ROW 8 (canonical decision): NEVER + journaled → ABORT. The " +
                "deciding policy is NEVER, which the scripted surface cannot even express.",
        )

        // --- 8b: the canonical END-TO-END behaviour on a real SUCCEEDED row.
        val canonical = CanonicalSpine(
            runId = "s4rpol-r8",
            stepKey = PluginStepId("core.error"),
            payloadJson = """{"kind":"error","message":"s4rpol-row8","failureKind":"USER"}""",
        )
        val freshOutcome = canonical.run()
        assertTrue(
            freshOutcome is RunOutcome.Failure,
            "control: core.error fresh always fails; got $freshOutcome",
        )
        canonical.seed(Prior.SUCCEEDED)
        canonical.resetCounters()
        canonical.run()
        val canonicalRow = rowOf(canonical, "core.error", Prior.SUCCEEDED)
        emit()

        assertEquals(
            0,
            canonicalRow.handlerInvocations,
            "MEASURED CONFIRMS MEMO ROW 8 (canonical end-to-end): the handler never runs again.",
        )
        assertEquals(
            "Failure(INFRASTRUCTURE)",
            canonicalRow.returned,
            "MEASURED CONFIRMS MEMO ROW 8 (canonical end-to-end): RejectedAbort settles a typed " +
                "INFRASTRUCTURE failure, NOT a reuse.",
        )
        assertEquals("NEVER", canonicalRow.fingerprintPolicy, "the NEVER policy is in the hash")

        // --- 8c: the scripted half. MEASURED, NOT FORCED.
        //
        // `core.error` cannot be invoked from the scripted façade, so the scripted half of row 8
        // is NOT OBSERVABLE. This is measured rather than assumed: the façade's declared methods
        // are enumerated, and every StepKey those methods can route to is looked up in the
        // PRODUCTION registry to read its real descriptor policy.
        val facadeMethods = ScriptedStepFacade::class.java.methods.map { it.name }.toSet()
        assertTrue(
            "error" !in facadeMethods,
            "S4-R-POL MEASURED: ScriptedStepFacade declares no `error(...)` method. " +
                "Methods = $facadeMethods",
        )
        val routedByFacade = listOf(
            "core.sh",
            "core.pwd",
            "core.pwd.tmp",
            "core.isUnix",
            "core.readFile",
            "core.fileExists",
        )
        val registry = CoreStepRegistryFactory.registry()
        val policiesOfRoutableSteps = routedByFacade.associateWith { key ->
            registry.definition(PluginStepId(key))?.contract?.descriptor?.replayPolicy?.name
        }
        assertNotNull(
            policiesOfRoutableSteps["core.sh"],
            "control: the production registry really does expose the routed Steps",
        )
        assertTrue(
            "NEVER" !in policiesOfRoutableSteps.values,
            "S4-R-POL MEASURED: no Step reachable from the scripted façade declares NEVER. " +
                "Policies = $policiesOfRoutableSteps. The scripted reuse of a NEVER Step is " +
                "therefore LATENT, not observable today — the memo's §5 reading of row 8 is " +
                "CONFIRMED as latent, and the defect it predicts is a containment hole, not an " +
                "incident.",
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun canonicalShSpine(runId: String, script: String) = CanonicalSpine(
        runId = runId,
        stepKey = PluginStepId("core.sh"),
        payloadJson = """{"kind":"sh","command":"$script","isScriptBlock":false,"returnStdout":true}""",
    )

    private fun canonicalPwdSpine(runId: String) = CanonicalSpine(
        runId = runId,
        stepKey = PluginStepId("core.pwd"),
        payloadJson = """{"kind":"pwd","tmp":false}""",
    )

    /**
     * Entry points with a FIXED call site and a single arrival, so the durable identity is stable
     * across the two `execute` calls and the second run really is a resume of the same operation.
     */
    private class ShEntry(private val script: String) : CompiledScriptedEntryPoint {
        override val artifact =
            ScriptedArtifactIdentity("source", "dsl", "compiler", "runtime", "plugins", "facades")
        override val entryPointId = "s4rpol-sh-entry"

        override suspend fun execute(steps: ScriptedStepFacade) {
            steps.sh(ScriptedCallSiteId("pipeline.kts:1:sh"), script, ReturnStdout)
        }
    }

    private class PwdEntry : CompiledScriptedEntryPoint {
        override val artifact =
            ScriptedArtifactIdentity("source", "dsl", "compiler", "runtime", "plugins", "facades")
        override val entryPointId = "s4rpol-pwd-entry"

        override suspend fun execute(steps: ScriptedStepFacade) {
            steps.pwd(ScriptedCallSiteId("pipeline.kts:1:pwd"))
        }
    }

    private fun emit() {
        println("")
        println("=== S4-R-POL SPIKE — MEASURED MATRIX (this run) ===")
        report.forEach { println(it.render()) }
        println("=== end S4-R-POL SPIKE ===")
        println("")
    }
}

// ----------------------------------------------------------------------
// File-level helpers. Top-level, because the nested spine classes below
// the test class need them and a non-inner nested class has no outer
// instance to receive them from.
// ----------------------------------------------------------------------

/** The journal state a scenario starts from. */
internal enum class Prior(val label: String) {
    FRESH("fresh"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    RUNNING("RUNNING"),
}

/**
 * Flips the journal's row for [runId] to [prior], preserving PRODUCTION's `fingerprint` and
 * `input`. This can change only the STATUS being reconciled — never the identity — so it cannot
 * smuggle a different variable into the experiment.
 */
private fun seedPrior(journal: InMemoryOperationJournal, runId: String, prior: Prior) {
    val status = when (prior) {
        Prior.FRESH -> return
        Prior.SUCCEEDED -> OperationStatus.SUCCEEDED
        Prior.FAILED -> OperationStatus.FAILED
        Prior.RUNNING -> OperationStatus.RUNNING
    }
    val source = journal.listForRun(runId).lastOrNull()
        ?: error("S4-R-POL SPIKE: no production row to clone for $runId; the fresh run wrote none")
    val output = source.output.takeIf { status == OperationStatus.SUCCEEDED }
    journal.append(
        MemoizedOperation(
            id = source.id,
            fingerprint = source.fingerprint,
            input = source.input,
            output = output,
            status = status,
            attempt = source.attempt,
            cachedOutput = output,
        ),
    )
}

private fun RunOutcome.describe(): String = when (this) {
    is RunOutcome.Success -> "success"
    is RunOutcome.Unstable -> "unstable"
    is RunOutcome.Aborted -> "aborted"
    is RunOutcome.Failure -> "Failure(${failure.kind})"
}
