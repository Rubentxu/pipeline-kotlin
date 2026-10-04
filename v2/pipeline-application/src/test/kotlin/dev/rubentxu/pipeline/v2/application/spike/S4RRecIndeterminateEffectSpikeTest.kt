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
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
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
 *
 * ## Rows 8-10, added with ADR-S4-R1 §3b: the window that opened and then closed
 *
 * Rows 1-7 left one branch untouched. Row 6 stopped at the classification because the only way past
 * it was to wait out the reattach poll, so the outcome of a `Reattach` was never observed by
 * anything. That branch is the whole of ADR-S4-R1's `ReattachWindowExpired`, and an unobserved
 * branch is the one whose semantics nobody has checked.
 *
 * The seam that made it reachable is the poll itself, forwarded from the composition root
 * (`CanonicalDurableRunCoordinator`'s `reattachPoll`, defaulting to the real executor so production
 * is untouched). Rows 8-10 measure what the system reports when that window closes with no terminal:
 * [OperationStatus.LOST] — the terminal reserved for a substrate that was inspected and held
 * nothing, now also reached by a process that was still alive and merely unobserved for too long.
 *
 * These rows were MEASURED CURRENT BEHAVIOUR when they were written, and the measurement is what
 * made §3c possible. **§3c closed the collapse they were measuring**, so rows 8 and 9 are now
 * NON-REGRESSION tests for the fix and say so in their assertion messages. What did NOT change is
 * the end-to-end consequence: ADR-S4-R1 §2.3 keeps `LOST` as the compatibility policy, and the
 * non-terminal alternative remains the DEFERRED decision D-1. Row 10 therefore still asserts
 * `LOST`, and it is the row that would detect a move across D-1.
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
            observed is RunningSubprocessObservation.Observed,
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
        // S4-F1-C2 TRANSITION DECLARED: this row used to assert SUCCEEDED for a `Probe` that declares
        // NO recovered projection. After F1-C that is the fail-closed case by design — a Step that has
        // not said how its value would be rebuilt from observed facts cannot have one invented for
        // it — so the row now asserts FAILED. The OBSERVATION half of the row is untouched and is
        // the part this spike was written for: the effect still never re-launches.
        assertEquals(
            OperationStatus.FAILED,
            rig.row()?.status,
            "MEASURED: a Step that declares no recovered projection fails closed rather than having a " +
                "value fabricated for it. The spike's own Probe is exactly such a Step, so it is the " +
                "right place to pin this law. `core.sh`, which DOES declare one, is covered by " +
                "S4R1F1CRecoveryTruthMatrixTest row A4.",
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

    // ------------------------------------------------------------------ rows 8-10 — ReattachWindowExpired

    /**
     * The reattach window closes with no terminal. **NON-REGRESSION — this row was a characterisation
     * of a collapse that §3c closed.**
     *
     * Before §3c this row asserted `Recovered(LOST)` and was labelled MEASURED. It exists to record
     * the transition honestly rather than to be silently rewritten: a reader comparing SHAs can see
     * that the claim was MEASURED at one and is a LAW at the next, and why.
     *
     * Production entry point crossed: [ExternalSubprocessRecovery.observe] — the real adapter, over
     * the real [StepReconcilerL1] and a real control directory on disk. Row 6 already measured the
     * `Reattach` classification; this row drives it to a RESULT, which is the branch the whole
     * `ReattachWindowExpired` concept is about and the one that was previously unobservable at all.
     *
     * ## Why substituting the poll is not re-implementing the thing under test
     *
     * The only substituted variable is the poll, and it is substituted with a value production
     * itself produces. [dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.pollResult]
     * returns `null` on exactly two paths: the deadline elapsing, and a `result.txt` that exists but
     * does not parse. In THIS fixture the operation directory holds no `result.txt` at all, so the
     * second path is unreachable and the deadline is the only thing that can produce the `null`.
     * The substituted value is therefore not a stand-in for production's answer — it IS
     * production's answer, reached without 60 s of wall clock per row.
     *
     * The malformed-`result.txt` path is deliberately NOT tested here. Exercising it would mean
     * re-implementing `pollResult`'s body inside the test, which certifies the reimplementation
     * rather than the product. It is recorded as a residual limit in the receipt instead.
     *
     * ## What this row asserts now
     *
     * Before §3c the answer was [OperationStatus.LOST]. That was a claim about the SUBSTRATE —
     * "I looked and there is nothing recoverable" — asserted in a situation whose truth is a claim
     * about OUR WINDOW: the substrate had said the process may still be alive, and we simply
     * stopped looking. ADR-S4-R1 §2.3 names that situation `ReattachWindowExpired` precisely
     * because conflating the two is what makes a live process get reported as lost.
     *
     * The answer now is [RunningSubprocessObservation.ReattachWindowExpired]: a data object with
     * no terminal in it. The observer reports the fact, and the reconciliation question — is an
     * expired window terminal at all? — belongs to the authority, which answers it under the §2.3
     * compatibility policy and defers the non-terminal alternative as D-1.
     */
    @Test
    fun `row 8 an expired reattach window is its own fact, not a recovered LOST`() {
        val root = Files.createDirectory(tempRoot.resolve("r8"))
        val opId = "s4rrec-r8-op"
        Files.createDirectories(root.resolve(opId))
        Files.writeString(DurableShellFiles.resolveConsoleLog(root.resolve(opId)), "still working")

        // PRECONDITION, asserted rather than assumed: the substrate must be saying "may still be
        // alive" for a reattach window to exist at all. Without this, a LOST here could equally
        // have come from a stale heartbeat and the row would be measuring row 4 again.
        val classification = StepReconcilerL1(SystemClock(), root).classify(opId)
        assertTrue(
            classification is StepReconcilerL1.Classification.Reattach,
            "PRECONDITION: the substrate has to classify as Reattach before there is a window to " +
                "expire. Got $classification",
        )

        val observed = ExternalSubprocessRecovery(
            clock = SystemClock(),
            controlDirRoot = root,
            pollResult = { _, _ -> null },
        ).observe(opId)

        assertTrue(
            observed is RunningSubprocessObservation.ReattachWindowExpired,
            "NON-REGRESSION, and the explicit transition from a characterisation. This row used to " +
                "assert `Recovered(LOST)` and was labelled MEASURED CURRENT BEHAVIOUR; ADR-S4-R1 §3c " +
                "closed the collapse it was measuring, so the row now asserts the fix. An expired " +
                "window is a fact about OUR window, and it is reported as its own case — NOT as " +
                "`Recovered(RecoveredTerminal.Lost)`, which is the claim that a process we stopped " +
                "watching was one we watched and did not find. Got $observed",
        )
        assertTrue(
            observed !is RunningSubprocessObservation.Unavailable,
            "and NOT Unavailable either: we DID look, and the look is what ran out. Conflating this " +
                "with row 3 — never being able to look — would terminalise a row that a later, " +
                "correctly configured run could still reconcile. Got $observed",
        )
        assertTrue(
            observed !is RunningSubprocessObservation.Observed,
            "and the observer must NOT invent a terminal it does not have. `ReattachWindowExpired` " +
                "is a data object for exactly this reason: it is structurally unable to carry a " +
                "RecoveredTerminal, so the fact layer cannot decide the reconciliation question " +
                "ADR-S4-R1 §2.3 reserves to the authority. Got $observed",
        )
    }

    /**
     * THE CARRIER, for the second collapse. Same control directory as row 8 — fresh heartbeat, no
     * `result.txt` — but now the poll's window closes with a terminal in hand.
     *
     * Asserted as a pair with row 8 so the two are not read as one claim. Together they establish
     * that the substituted poll is genuinely the variable under study: same substrate, same
     * observer, same branch, two different poll answers, two different observations. A harness whose
     * substituted variable could not change the outcome would be certifying nothing, and this is
     * what rules that out without spending 60 s to find out.
     */
    @Test
    fun `row 9 the same expired window with a terminal in hand is not LOST, so the poll is the variable`() {
        val root = Files.createDirectory(tempRoot.resolve("r9"))
        val opId = "s4rrec-r9-op"
        Files.createDirectories(root.resolve(opId))
        Files.writeString(DurableShellFiles.resolveConsoleLog(root.resolve(opId)), "still working")

        val expired = ExternalSubprocessRecovery(
            clock = SystemClock(),
            controlDirRoot = root,
            pollResult = { _, _ -> null },
        ).observe(opId)
        val terminal = ExternalSubprocessRecovery(
            clock = SystemClock(),
            controlDirRoot = root,
            pollResult = { _, _ -> 0 },
        ).observe(opId)

        assertEquals(
            RunningSubprocessObservation.ReattachWindowExpired,
            expired,
            "NON-REGRESSION: the expired half is its own case. After §3c it is no longer an Observed " +
                "carrying LOST — the point of the row is that the observer does not choose a terminal.",
        )
        assertEquals(
            // S4-F1-C1: the payload is the substrate's own fact vocabulary now, not a semantic
            // terminal the observer had already classified. The row still compares what the observer
            // SAW, which is the property it was written for.
            RunningSubprocessObservation.Observed(
                DurableTaskTerminal.Exited(
                    exitCode = 0,
                    output = DurableTaskOutput(controlDir = root.resolve(opId).toString()),
                ),
            ),
            terminal,
            "NON-REGRESSION: the same substrate with a terminal inside the window recovers as a " +
                "semantic terminal. Compared on `RecoveredTerminal`, not on OperationStatus, because " +
                "the fact layer no longer knows the storage vocabulary — asserting a status here " +
                "would require the observation to carry one, which is the thing §3c removed.",
        )
        assertTrue(
            expired != terminal,
            "NON-REGRESSION: two poll answers, two observations. Identical values here would mean the " +
                "window's expiry is not what decides the answer, and the substituted variable would " +
                "no longer be the one under study — which is what makes this pair a calibration " +
                "rather than a decoration.",
        )
    }

    /**
     * End to end, through the real coordinator. **MEASURED CURRENT BEHAVIOUR — NOT PROMOVED AS
     * DESIRED SEMANTICS.** This is the row that holds the D-1 boundary in place.
     *
     * Rows 8 and 9 assert the observer, and §3c changed what the observer says. This row did NOT
     * change: the observable end-to-end behaviour is identical before and after, because the
     * authority maps `ReattachWindowExpired` to `RecoveredTerminal.Lost` under the ADR-S4-R1 §2.3
     * compatibility policy. That is the point — the reshape moved a decision between layers without
     * moving any observable outcome, and this row is what proves it.
     *
     * The seam in 3c is therefore invisible from the outside, and the only place it could show up is
     * the seam's own tests. When D-1 is decided, THIS row is the one that has to change, and its
     * message says which side of the decision the code is on so the change cannot be silent.
     */
    @Test
    fun `row 10 an expired reattach window journals LOST end to end without re-launching`() = runBlocking {
        val probe = Probe(RecoveryPolicy.ExternalSubprocess, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS)
        val root = Files.createDirectory(tempRoot.resolve("r10"))
        val rig = Rig("s4rrec-r10", probe, controlDirRoot = root, reattachPoll = { _, _ -> null })
        rig.execute()
        val opId = rig.flipRowToRunning()
        val before = probe.invocations.get()

        // A substrate that says "may still be alive": control dir present, no result.txt, no
        // timeout.flag, fresh heartbeat. Deliberately NOT row 4's empty directory and NOT row 5's
        // terminal result file — the branch under study is the one in between.
        Files.createDirectories(root.resolve(opId))
        Files.writeString(DurableShellFiles.resolveConsoleLog(root.resolve(opId)), "still working")

        rig.execute()

        assertEquals(
            before,
            probe.invocations.get(),
            "MEASURED: the handler does not run. A live process whose window closed is reattached, " +
                "not relaunched — the at-least-once window stays shut on this path.",
        )
        assertEquals(
            OperationStatus.LOST,
            rig.row()?.status,
            "MEASURED, and unchanged by §3c: the row is terminalised LOST, not left RUNNING. " +
                "§3c moved WHERE that decision is taken — the authority now, not the observer — " +
                "and the observable outcome is byte-identical, which is what the ADR-S4-R1 §2.3 " +
                "compatibility policy requires. This terminal stays LOST until D-1 is decided; when " +
                "it is, THIS assertion is the one that has to change, and it must change loudly.",
        )
    }

    // ------------------------------------------------------------------ the matrix, side by side

    /**
     * The ten rows as one table, so the shape of the gap is visible rather than inferred. Each
     * cell is the resolution the row asserts, not a restatement of this file's comments.
     *
     * Rows 8-10 arrived with ADR-S4-R1 §3b and are NOT a fourth unobservable case: they add the
     * window that opened and then closed, which the first seven rows could not reach. §3c then
     * changed rows 8 and 9 — the observer stopped choosing a terminal — and deliberately left row 10
     * alone, because the compatibility policy keeps the observable outcome identical.
     */
    @Test
    fun `the measured matrix shows exactly one row where a required recovery is unobservable`() {
        val rows = listOf(
            Row(1, "no journal", "Execute", "handler runs", "-"),
            Row(2, "None + RUNNING", "Execute", "handler runs", "recovery genuinely not applicable"),
            Row(
                3,
                "ExternalSubprocess + RUNNING + root null",
                "RecoveryUnobservable",
                "no handler",
                "REQUIRED BUT UNOBSERVABLE — fails closed, row left RUNNING",
            ),
            Row(4, "ExternalSubprocess + RUNNING + op dir absent", "RecoverRunning(LOST)", "no handler", "observed, no evidence"),
            Row(5, "ExternalSubprocess + RUNNING + result.txt", "RecoverRunning(SUCCEEDED)", "no handler", "observed, terminal evidence"),
            Row(6, "ExternalSubprocess + RUNNING + fresh heartbeat", "Reattach (classification only)", "no handler", "observed, still alive"),
            Row(7, "ExternalSubprocess + RUNNING + timeout.flag", "RecoverRunning(FAILED_TIMEOUT)", "no handler", "observed, watchdog killed it"),
            Row(8, "Reattach + window closed, no terminal", "ReattachWindowExpired (no terminal)", "no handler", "WINDOW CLOSED — own fact since 3c"),
            Row(9, "Reattach + terminal inside window", "RecoverRunning(Succeeded)", "no handler", "observed, terminal arrived in time"),
            Row(10, "Reattach + window closed, end to end", "RecoverRunning(LOST), row journalled", "no handler", "WINDOW CLOSED — D-1 boundary holds"),
        )

        val unobservable = rows.filter { it.note.startsWith("REQUIRED BUT UNOBSERVABLE") }
        assertEquals(
            1,
            unobservable.size,
            "Exactly one row is the required-but-unobservable case, and it is the one where recovery " +
                "was mandatory and the substrate could not be read. Rows 4-7, 9 and 10 all reached " +
                "the reconciler and produced a legitimate observation, including LOST where we " +
                "looked and found nothing. Rows 8 and 10 are NOT a second unobservable case: the " +
                "substrate was read fine and our window simply ended.",
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
        // §3c closed the collapse this assertion used to record. It is kept, inverted, because the
        // place that must not regress is the SEAM: the observer names no terminal, and only the
        // authority's compatibility mapping produces LOST downstream of it.
        val windowClosed = rows.filter { it.note.startsWith("WINDOW CLOSED") }
        assertEquals(
            2,
            windowClosed.size,
            "Rows 8 and 10 are the ReattachWindowExpired pair: the observer-level fact and the " +
                "journalled consequence. Row 10's LOST is the ADR-S4-R1 §2.3 compatibility policy " +
                "and stays until DEFERRED decision D-1 is taken.",
        )
        val observerLevel = windowClosed.single { it.n == 8 }
        val endToEnd = windowClosed.single { it.n == 10 }
        assertTrue(
            !observerLevel.resolution.contains("LOST"),
            "NON-REGRESSION: row 8 names no terminal. Before §3c this cell read `Recovered(LOST)` " +
                "and the equality with row 10 WAS the defect; the observer reported a substrate " +
                "verdict for a fact about our own observation window. Got: ${observerLevel.resolution}",
        )
        assertTrue(
            endToEnd.resolution.contains("LOST"),
            "and row 10 still does, because §3c moved WHERE the decision is taken without moving it: " +
                "the authority maps the window fact to Lost, so the journalled terminal is " +
                "unchanged. If this one ever stops being LOST, D-1 was crossed and it must have " +
                "been a declared decision. Got: ${endToEnd.resolution}",
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
     *
     * [reattachPoll] is forwarded to the coordinator's `reattachPoll` seam (S4-R1 §3b). `null` — used
     * by every pre-existing row — composes the real `DurableShellExecutor` poll exactly as
     * production does, so those rows are untouched by the seam's existence.
     */
    private class Rig(
        private val runId: String,
        probe: Probe,
        controlDirRoot: Path?,
        reattachPoll: ((Path, Long) -> Int?)? = null,
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
            reattachPoll = reattachPoll,
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
