package dev.rubentxu.pipeline.v2.application.spike

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.ExternalSubprocessRecovery
import dev.rubentxu.pipeline.v2.application.durable.RunningSubprocessObservation
import dev.rubentxu.pipeline.v2.application.durable.buildDefaultExecutionBoundary
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellFiles
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * S4-R-REC — is "recovery applies" the same fact as "recovery could observe anything"?
 *
 * Test-only. It MEASURES the current behaviour of the recovery authority and repairs nothing. The
 * question it exists to falsify is a design hypothesis, and the harness is built so that a wrong
 * hypothesis shows up as a WRONG ROW rather than as a hopeful assertion.
 *
 * ## The three facts one sentinel currently carries
 *
 * `ExternalSubprocessRecovery.recover` opens with
 * (`RunningSubprocessRecovery.kt:65`):
 *
 * ```kotlin
 * if (recoveryPolicy != ExternalSubprocess || journaled?.status != RUNNING || controlDirRoot == null) {
 *     return RunningCanonicalShellRecovery.NotRunningShell
 * }
 * ```
 *
 * Three unrelated facts share one `NotRunningShell`:
 *
 * ```text
 * (1) recovery does NOT apply        — the declared policy is not ExternalSubprocess
 * (2) the row is not RUNNING         — there is nothing to reattach
 * (3) recovery DOES apply and the
 *     substrate CANNOT be observed   — controlDirRoot == null
 * ```
 *
 * (1) and (2) legitimately fall through to the replay policy. **(3) does not.** It is not "there is
 * nothing to recover", it is "I am required to recover and I cannot see the substrate". The port
 * KDoc (`RunningSubprocessRecovery.kt:36-38`) *enshrines* the collapse — *"including when … no
 * control root is configured. The decision core must not have to know which of those applied"* —
 * which is the architectural error stated as a principle. It makes the adapter's inability to
 * observe indistinguishable from the policy not applying, and it moves the WHEN decision out of
 * the resolver, whose entire job is to decide.
 *
 * The resolver's own KDoc already claims a WHEN the resolver does not enforce:
 * `DurableInvocationResolver.kt:68` — recovery "triggers only when the operation declares
 * [RecoveryPolicy.ExternalSubprocess] AND the journal is RUNNING **AND a control dir exists**".
 * The third conjunct is checked by the ADAPTER, and its failure is signalled with the same value
 * as the first conjunct's failure. The documented precondition and the implemented precondition
 * are different rules.
 *
 * For a Step declaring `RERUN`, fact (3) therefore lands on `Execute`: an external effect of
 * unknown prior state is re-executed. That is the at-least-once window, and it is the row this
 * spike exists to measure.
 *
 * ## The distinction that must survive
 *
 * ```text
 * controlDirRoot == null                              → NOT OBSERVED AT ALL     (row 3)
 * controlDirRoot present, <root>/<operationId> absent  → OBSERVED, nothing there (row 4)
 * ```
 *
 * Row 4 already reaches `StepReconcilerL1` and falls to its Check 3, so it becomes `LOST`: we
 * looked, and there is no recoverable evidence. That is a legitimate observation and `LOST` is
 * the right terminal. Row 3 is the opposite — we never got to look, so converting it to `LOST`
 * would terminalise a row that a later, correctly configured run could still reconcile.
 *
 * ## What the harness crosses, and what it deliberately does not
 *
 * Every behavioural row drives the REAL production spine
 * (`CanonicalDurableRunCoordinator` → `DurableInvocationResolver` → `ExternalSubprocessRecovery` →
 * `StepReconcilerL1`) with a real `StepRegistry` definition whose descriptor carries the declared
 * `effects` / `replayPolicy` / `recoveryPolicy`. Nothing here re-implements the decision. The
 * only instrument is an invocation counter on the probe's own handler, which is the closest thing
 * to the process launcher the probe has.
 *
 * The prior durable row is CLONED from what production actually wrote (id, fingerprint, input,
 * attempt) and only its STATUS is changed. Rebuilding a fingerprint here would test the spike's
 * own arithmetic instead of production's identity.
 *
 * ## No timing assertions anywhere
 *
 * Every row counts something discrete — handler invocations, or the literal classification the
 * reconciler returned. Row 6 is measured at the CLASSIFICATION (`Reattach`) rather than by waiting
 * out the 60 s reattach poll, which is the only honest way to assert "reattach, never a second
 * launch" without spending a minute proving it.
 */
class S4RRecIndeterminateEffectSpikeTest {

    /**
     * Every control root in this spike is created under here, and JUnit deletes it afterwards.
     *
     * `Files.createTempDirectory("...")` with no parent lands in `java.io.tmpdir` and is never
     * removed, so a spike run repeatedly inside a long-lived checkout leaks one directory per row
     * per run. That is not a tidiness complaint: on a tmpfs-backed tmp under pressure it starts
     * failing rows with `IOException` that have nothing to do with the code under test, which is
     * exactly the false-RED shape a measurement harness must never produce. A harness that can make
     * its own subject look broken is not measuring its subject.
     */
    @TempDir
    lateinit var tempRoot: Path

    // ------------------------------------------------------------------ row 1

    /**
     * No durable history at all → ordinary execution. This is the control row: if even THIS stopped
     * executing, every other row's counts would be meaningless.
     */
    @Test
    fun `row 1 no journal means an ordinary execution`() = runBlocking {
        val probe = Probe(RecoveryPolicy.None, ReplayPolicy.RERUN, Effect.READ_ONLY)
        val rig = Rig("s4rrec-r1", probe, controlDirRoot = null)

        rig.execute()

        assertEquals(1, probe.invocations.get(), "a fresh invocation must execute the handler once")
        assertEquals(
            OperationStatus.SUCCEEDED,
            rig.row()?.status,
            "and leave a SUCCEEDED terminal row",
        )
    }

    // ------------------------------------------------------------------ row 2

    /**
     * `RecoveryPolicy.None` + RUNNING → recovery legitimately does not apply and the generic replay
     * semantics apply unchanged. This is where `NotApplicable` BELONGS: in the resolver, which can
     * see the policy. Nothing new needs to be invented for it.
     */
    @Test
    fun `row 2 a non-subprocess policy with a RUNNING row keeps the generic replay semantics`() =
        runBlocking {
            val probe = Probe(RecoveryPolicy.None, ReplayPolicy.RERUN, Effect.READ_ONLY)
            val rig = Rig("s4rrec-r2", probe, controlDirRoot = null)
            rig.execute()
            rig.flipRowToRunning()
            val before = probe.invocations.get()

            rig.execute()

            assertEquals(
                before + 1,
                probe.invocations.get(),
                "RERUN + RUNNING under a policy that declares no recovery re-executes. That is the " +
                    "existing generic semantics, and this spike does not change it.",
            )
        }

    // ------------------------------------------------------------------ row 3 — THE DISCRIMINATOR

    /**
     * `ExternalSubprocess` + RUNNING + `controlDirRoot == null`.
     *
     * MEASURED, not assumed. Today this returns `NotRunningShell`, falls through to the replay
     * kernel, and with `RERUN` reaches `Execute` — the handler runs, and an external effect of
     * unknown prior state is re-executed. If a later change makes this fail closed, the handler
     * count drops by zero AND the row stays RUNNING; that is the shape the hypothesis calls for, and
     * this row is where the change will show up.
     */
    /**
     * `ExternalSubprocess` + RUNNING + `controlDirRoot == null`.
     *
     * This row was MEASURED as the defect: the observer returned a "nothing to recover" sentinel,
     * the resolver fell through to the replay kernel, and with `RERUN` the handler ran — re-executing
     * an external effect of unknown prior state — and the row was then terminalised as `SUCCEEDED`.
     *
     * After ADR-0103 R1-E both halves of that are inverted, and the inversion is the deliverable:
     * the handler count does not move, and the row stays `RUNNING` because a later, correctly
     * configured run still needs it in order to reconcile.
     */
    @Test
    fun `row 3 ExternalSubprocess plus RUNNING plus a null control root now fails closed and stays RUNNING`() =
        runBlocking {
            val probe = Probe(RecoveryPolicy.ExternalSubprocess, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS)
            val rig = Rig("s4rrec-r3", probe, controlDirRoot = null)
            rig.execute()
            rig.flipRowToRunning()
            val before = probe.invocations.get()

            rig.execute()

            assertEquals(
                before,
                probe.invocations.get(),
                "M-REC-3 GUARD: the handler must not run. A subprocess whose prior external effect " +
                    "is UNKNOWN is not re-executed just because the runtime could not look — that " +
                    "was the at-least-once window this row measured as open.",
            )
            assertEquals(
                OperationStatus.RUNNING,
                rig.row()?.status,
                "M-REC-3 GUARD: and the row stays RUNNING. Terminalising it — as SUCCEEDED when " +
                    "this was measured, as LOST on a plausible wrong fix — is not fail-closed, it " +
                    "destroys the evidence a correctly configured run would need to reconcile.",
            )
        }

    /**
     * THE CARRIER, before and after.
     *
     * This row was written as the sharpest statement of the defect: the observer was handed a
     * declared policy and a journaled row, and it answered "nothing to recover" for all three
     * facts at once, so no caller could tell them apart. It asserted that two different
     * circumstances produced literally the same value.
     *
     * After ADR-0103 R1-E the observer is asked a different question — *what did you find?* — and
     * no longer receives a policy or a status, so the question of "is recovery applicable" is not
     * even one it is in a position to answer. The two circumstances below are therefore no longer
     * the same call: only one of them is still expressible.
     *
     * This is the row M-REC-3 must kill. Collapsing `Unavailable` back into a "nothing to
     * recover" answer would make the observer silent about its own blindness again, and the
     * equality below would be asserted as true rather than refuted.
     */
    @Test
    fun `row 3b the observer says Unavailable rather than pretending there is nothing to recover`() {
        val input = OperationInput(
            stepId = PROBE_KEY.value,
            params = emptyMap(),
            runId = "s4rrec-r3b",
            attempt = 1,
        )
        val running = RerunOperation(
            id = "s4rrec-r3b-s0-0",
            fingerprint = Fingerprint.compute(input, PROBE_KEY.value, ReplayPolicy.RERUN, 1),
            input = input,
            output = null,
            status = OperationStatus.RUNNING,
            attempt = 1,
        )

        // Recovery was required of it, and there is no control root to look in.
        val unobservable = ExternalSubprocessRecovery(SystemClock(), controlDirRoot = null)
            .observe(running.id)

        // A real inspection, with a real control root and a real terminal result file.
        val root = Files.createDirectory(tempRoot.resolve("r3b"))
        Files.createDirectories(root.resolve(running.id))
        Files.writeString(root.resolve(running.id).resolve("result.txt"), "0")
        val observed = ExternalSubprocessRecovery(SystemClock(), controlDirRoot = root)
            .observe(running.id)

        assertTrue(
            unobservable is RunningSubprocessObservation.Unavailable,
            "M-REC-3 GUARD: recovery was REQUIRED of the observer and it could not look, and the " +
                "thing it says is Unavailable — a fact the decision core can act on. If this " +
                "assertion ever passes by producing some 'nothing to recover' value, the carrier " +
                "has regressed to the collapsed form. Got $unobservable",
        )
        assertTrue(
            observed is RunningSubprocessObservation.Recovered,
            "and a substrate that COULD be inspected yields a different case, never the same value. " +
                "Got $observed",
        )
        assertTrue(
            unobservable != observed,
            "M-REC-3 GUARD: 'I could not look' and 'I looked and found this' are different facts " +
                "and must never be one value again.",
        )
    }

    // ------------------------------------------------------------------ row 4

    /**
     * `ExternalSubprocess` + RUNNING + root present + operation directory absent.
     *
     * This one is already correct, and the spike is here to PROVE it stays correct, because the
     * temptation with any "unavailable" concept is to lump it together with row 3. It must not:
     * here we looked, and there is no recoverable evidence, so `LOST` is the honest terminal.
     */
    @Test
    fun `row 4 an absent operation directory under a present root is LOST, an observation not a gap`() =
        runBlocking {
            val probe = Probe(RecoveryPolicy.ExternalSubprocess, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS)
            val root = Files.createDirectory(tempRoot.resolve("r4"))
            val rig = Rig("s4rrec-r4", probe, controlDirRoot = root)
            rig.execute()
            rig.flipRowToRunning()
            val before = probe.invocations.get()
            // deliberately create NOTHING under the root: no timeout.flag, no result.txt, no log

            rig.execute()

            assertEquals(
                before,
                probe.invocations.get(),
                "MEASURED: the handler does not run. The reconciler LOOKED and found nothing, so " +
                    "the answer is a recovery decision, not a re-execution.",
            )
            assertEquals(
                OperationStatus.LOST,
                rig.row()?.status,
                "MEASURED: LOST, and it must stay LOST. We observed; there was simply no evidence. " +
                    "Categorically different from row 3, where we never observed anything.",
            )
        }

    // ------------------------------------------------------------------ row 5

    @Test
    fun `row 5 a terminal result file recovers the outcome without executing`() = runBlocking {
        val probe = Probe(RecoveryPolicy.ExternalSubprocess, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS)
        val root = Files.createDirectory(tempRoot.resolve("r5"))
        val rig = Rig("s4rrec-r5", probe, controlDirRoot = root)
        rig.execute()
        val opId = rig.flipRowToRunning()
        val before = probe.invocations.get()

        Files.createDirectories(root.resolve(opId))
        Files.writeString(root.resolve(opId).resolve("result.txt"), "0")

        rig.execute()

        assertEquals(before, probe.invocations.get(), "MEASURED: a recovered shell never re-launches")
        assertEquals(
            OperationStatus.SUCCEEDED,
            rig.row()?.status,
            "MEASURED: the recovered terminal is journalled.",
        )
    }

    // ------------------------------------------------------------------ row 6

    /**
     * Fresh heartbeat → `Reattach`, measured at the classification.
     *
     * Asserted on `StepReconcilerL1` rather than on the adapter, because the adapter then polls for
     * up to 60 s and this row's claim is about the DECISION (reattach, not re-launch) rather than
     * about how long a poll takes. Waiting a minute to prove "no second launch" would be a timing
     * assertion dressed as a behavioural one.
     */
    @Test
    fun `row 6 a fresh heartbeat classifies as Reattach, which is reattach and never a second launch`() {
        val root = Files.createDirectory(tempRoot.resolve("r6"))
        val controlDir = root.resolve("s4rrec-r6-op")
        Files.createDirectories(controlDir)
        Files.writeString(DurableShellFiles.resolveConsoleLog(controlDir), "working")

        val classification = StepReconcilerL1(SystemClock(), root).classify("s4rrec-r6-op")

        assertTrue(
            classification is StepReconcilerL1.Classification.Reattach,
            "MEASURED: a fresh heartbeat with no result.txt is Reattach, i.e. the process may still be " +
                "alive and must be reattached — NOT re-executed. Got $classification",
        )
    }

    // ------------------------------------------------------------------ row 7

    @Test
    fun `row 7 a timeout flag is FAILED_TIMEOUT and is terminal without executing`() = runBlocking {
        val probe = Probe(RecoveryPolicy.ExternalSubprocess, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS)
        val root = Files.createDirectory(tempRoot.resolve("r7"))
        val rig = Rig("s4rrec-r7", probe, controlDirRoot = root)
        rig.execute()
        val opId = rig.flipRowToRunning()
        val before = probe.invocations.get()

        Files.createDirectories(root.resolve(opId))
        Files.writeString(root.resolve(opId).resolve("timeout.flag"), "")

        rig.execute()

        assertEquals(before, probe.invocations.get(), "MEASURED: a watchdog-killed shell is not re-executed")
        assertEquals(
            OperationStatus.FAILED_TIMEOUT,
            rig.row()?.status,
            "MEASURED: FAILED_TIMEOUT, which is TERMINAL and distinct from LOST — we know the " +
                "watchdog fired; we did not merely fail to look.",
        )
    }

    // ------------------------------------------------------------------ the matrix, side by side

    /**
     * The seven rows as one table, so the shape of the gap is visible rather than inferred. Each
     * cell is the resolution the row asserts, not a restatement of this file's comments.
     */
    @Test
    fun `the measured matrix shows exactly one row where a required recovery is unobservable`() {
        val rows = listOf(
            Row(1, "no journal", "Execute", "handler runs", "-"),
            Row(2, "None + RUNNING", "Execute", "handler runs", "recovery genuinely not applicable"),
            Row(3, "ExternalSubprocess + RUNNING + root null", "RecoveryUnobservable", "no handler", "REQUIRED BUT UNOBSERVABLE — fails closed, row left RUNNING"),
            Row(4, "ExternalSubprocess + RUNNING + op dir absent", "RecoverRunning(LOST)", "no handler", "observed, no evidence"),
            Row(5, "ExternalSubprocess + RUNNING + result.txt", "RecoverRunning(SUCCEEDED)", "no handler", "observed, terminal evidence"),
            Row(6, "ExternalSubprocess + RUNNING + fresh heartbeat", "RecoverRunning via Reattach", "no handler", "observed, still alive"),
            Row(7, "ExternalSubprocess + RUNNING + timeout.flag", "RecoverRunning(FAILED_TIMEOUT)", "no handler", "observed, watchdog killed it"),
        )

        val unobservable = rows.filter { it.note.startsWith("REQUIRED BUT UNOBSERVABLE") }
        assertEquals(
            1,
            unobservable.size,
            "Exactly one row is the required-but-unobservable case, and it is the one where recovery " +
                "was mandatory and the substrate could not be read. Rows 4-7 all reached the " +
                "reconciler and produced a legitimate observation, including LOST where we looked " +
                "and found nothing.",
        )
        assertTrue(
            rows.filter { it.note.startsWith("observed") }.all { it.effect == "no handler" },
            "Every row where recovery DID observe something must leave the handler alone.",
        )
        assertTrue(
            unobservable.single().effect == "no handler",
            "and so must the one where it could not: failing closed is not executing less in only " +
                "the happy cases.",
        )
        println("S4-R-REC measured matrix:")
        rows.forEach {
            println("  row ${it.n}  ${it.situation.padEnd(46)} ${it.resolution.padEnd(28)} ${it.effect.padEnd(13)} ${it.note}")
        }
    }

    private data class Row(
        val n: Int,
        val situation: String,
        val resolution: String,
        val effect: String,
        val note: String,
    )

    // ------------------------------------------------------------------ rig

    /**
     * One coordinator, one journal, one registry — the production composition, differing only in
     * the probe's declared policies and in [controlDirRoot].
     *
     * `run()` is invoked twice against the SAME coordinator and the SAME journal, which is how a
     * restart-resume is modelled: the durable journal is the state that survives, everything else is
     * rebuilt by the caller exactly as a resumed process would rebuild it.
     */
    private class Rig(
        private val runId: String,
        probe: Probe,
        controlDirRoot: Path?,
    ) {
        private val journal = InMemoryOperationJournal(SystemClock())
        private val registry = InMemoryStepRegistry().also { it.register(probe) }
        private val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(SystemClock()),
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = controlDirRoot,
            shOptions = ShOptions.EMPTY,
            commonExecutionBoundary = buildDefaultExecutionBoundary(
                dispatcher = CanonicalNodeDispatcher(),
                invocationExecutor = null,
                stepRegistry = registry,
            ),
            stepRegistry = registry,
        )

        suspend fun execute() {
            coordinator.run(pipelineFor(runId), RunId(runId))
        }

        fun row(): DurableOperation? = journal.listForRun(runId).lastOrNull()

        /**
         * Clones the row production actually wrote — same id, same fingerprint, same input, same
         * attempt — and changes ONLY its status to RUNNING. It can therefore change only the status
         * being reconciled, never the identity, and it cannot smuggle a different variable into the
         * experiment.
         */
        fun flipRowToRunning(): String {
            val source = row() ?: error("S4-R-REC SPIKE: no production row to clone for $runId")
            journal.append(
                RerunOperation(
                    id = source.id,
                    fingerprint = source.fingerprint,
                    input = source.input,
                    output = null,
                    status = OperationStatus.RUNNING,
                    attempt = source.attempt,
                ),
            )
            return source.id
        }
    }

    // ------------------------------------------------------------------ the probe Step

    /**
     * A registry-native Step whose descriptor declares the policies under study and whose handler
     * counts its own invocations. The counter is the harness's execution boundary: the probe never
     * launches a process, so "did the handler run" is the sharpest question this rig can ask.
     */
    private class Probe(
        recovery: RecoveryPolicy,
        replay: ReplayPolicy,
        effect: Effect,
    ) : StepDefinition<String, String> {

        val invocations = AtomicInteger(0)

        private val codec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }

        override val contract: StepContract<String, String> = StepContract(
            key = PROBE_KEY,
            descriptor = StepDescriptor(
                stepId = PROBE_KEY.value,
                name = "recovery-probe",
                configRef = "",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(effect),
                replayPolicy = replay,
                recoveryPolicy = recovery,
            ),
            inputCodec = codec,
            outputCodec = codec,
        )

        override val handler: StepHandler<String, String> = StepHandler { input, _ ->
            invocations.incrementAndGet()
            input
        }
    }

    private companion object {
        val PROBE_KEY = PluginStepId("spike.recovery.probe")

        fun pipelineFor(runId: String): CompiledPipeline = CompiledPipeline(
            id = DefinitionId("s4rrec-$runId"),
            source = SourceDescriptor("s4rrec.pipeline.kts", Digest("s4rrec-source")),
            pluginLockDigest = Digest("s4rrec-lock"),
            stages = listOf(
                StageNode(
                    id = StageId("spike"),
                    name = "spike",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("spike/step-0"),
                                pluginStepId = PROBE_KEY,
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"probe"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
