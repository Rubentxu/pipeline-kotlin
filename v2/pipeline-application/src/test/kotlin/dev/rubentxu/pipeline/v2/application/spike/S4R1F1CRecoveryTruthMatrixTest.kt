package dev.rubentxu.pipeline.v2.application.spike

import dev.rubentxu.pipeline.v2.application.CoreShellStep
import dev.rubentxu.pipeline.v2.application.SHELL_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.ShellOperations
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.toStepOutcome
import dev.rubentxu.pipeline.v2.application.scripted.RegistryScriptedShellRuntime
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedCallSiteProvider
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedScope
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedRuntime
import dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.classifyShellTerminal
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.scripting.ReturnStatus
import dev.rubentxu.pipeline.v2.scripting.ReturnStdout
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellFiles
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * S4-F1-C0 — the RECOVERY TRUTH MATRIX, frozen BEFORE production is touched.
 *
 * ## Which authority this harness crosses
 *
 * `ScriptedRuntime.run` → `ScriptedRegistryInvoker` → `DurableInvocationResolver` →
 * `ExternalSubprocessRecovery` (real, real control dir) → `RecoveryInterpretationEngine` → journal.
 *
 * Every one of those is the production composition; [ScriptedInvokerFixture] exists precisely so
 * eight harnesses do not each re-wire it. The ONE thing substituted is the `core.sh` handler's
 * `ShellOperations` capability, and only so that a re-launch would be **countable** rather than
 * merely asserted absent. Nothing about reconciliation, classification or persistence is
 * substituted.
 *
 * `behavioural` in the ADR-0072 sense, not `model`: this file does not reimplement any production
 * algorithm, does not recompute a fingerprint or a replay decision, and reads every value out of
 * the journal row production wrote.
 *
 * ## Why the matrix has TWO halves, and why that is the point
 *
 * ADR-S4-R1 declares `implementation conformance: PARTIAL` with two manifestations, and the second
 * one is not rhetorical: the observer classifies `exitCode != 0` as a SCRIPT failure, while the
 * contract says `sh(returnStatus = true)` with exit 42 is `Status(42) · Success`. Both statements
 * are about the SAME observed fact, and only one can be what the product does.
 *
 * So the matrix is two measurements, never one:
 *
 * ```text
 * HALF A  what the shipped spine DOES   — measured end to end through the real stack
 * HALF B  what the contract SAYS        — classifyShellTerminal + toStepOutcome, the real
 *                                         authorities, on the same observed fact
 * ```
 *
 * **The distance between the halves IS the defect.** Measuring only A would describe a bug without
 * naming what is wrong with it. Measuring only B would describe a contract the product does not
 * implement. Collapsing them into a single "expected" value is the mistake this file is built to
 * prevent, and half A deliberately asserts values that half B contradicts.
 *
 * ## The variable under study
 *
 * `returnMode` is NEVER given to the observer or the resolver. It travels only inside the encoded
 * durable input, where the production DSL compiler puts it, and it is recovered by the production
 * `inputCodec`. That is the whole point of the exercise: the information is ALREADY DURABLE. Nothing
 * needs inventing; it needs arriving at the authority that can read it.
 */
class S4R1F1CRecoveryTruthMatrixTest {

    // ------------------------------------------------------------------ rig

    /**
     * Real `core.sh`, real recovery substrate, real journal — and a launch counter.
     *
     * The counter is the only assertion this harness makes about the handler, and asserting `0` on
     * it is strictly stronger than asserting a status: a status can be right for the wrong reason.
     */
    private class Rig(private val runId: String, controlDirRoot: Path?) {
        val launches = AtomicInteger(0)
        val journal = InMemoryOperationJournal(SystemClock())
        val registry = InMemoryStepRegistry().also { CoreShellStep.registerInto(it) }

        val invoker = ScriptedInvokerFixture.build(
            registry = registry,
            journal = journal,
            controlDirRoot = controlDirRoot,
            capabilityAccessFactory = { context: CanonicalRuntimeContext ->
                object : CanonicalRuntimeCapabilityAccess(context) {
                    override fun available(): Set<StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)

                    @Suppress("UNCHECKED_CAST")
                    override fun <T : Any> get(key: StepCapability): T {
                        if (key == SHELL_OPERATIONS_CAPABILITY) {
                            return object : ShellOperations {
                                override suspend fun invoke(
                                    command: ShellCommand,
                                    runId: RunId,
                                    stepIndex: Int,
                                ): ShellInvocationResult {
                                    launches.incrementAndGet()
                                    // The FIRST execution must succeed, or there is no row to
                                    // reconcile. A recovering run never reaches here, which is what
                                    // `launches` then proves.
                                    return if (command.returnMode == ShellReturnMode.STATUS) {
                                        ShellInvocationResult.Status(0)
                                    } else if (command.returnMode == ShellReturnMode.STDOUT) {
                                        ShellInvocationResult.Stdout("abc")
                                    } else {
                                        ShellInvocationResult.UnitValue
                                    }
                                }
                            } as T
                        }
                        return super.get(key)
                    }
                }
            },
        )

        fun row(): DurableOperation? = journal.listForRun(runId).lastOrNull()

        /**
         * Clones the row production actually wrote and changes ONLY its status to RUNNING — the
         * state a worker that died mid-effect leaves behind. It cannot change id, fingerprint, input
         * or attempt, so it cannot smuggle a different variable into the experiment.
         */
        fun flipRowToRunning() {
            val source = row() ?: error("F1-C0: no production row to clone for $runId")
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
        }
    }

    // ------------------------------------------------------------------ the shared driver

    /** What the shipped spine produced, read out of the journal row production wrote. */
    private data class Spine(
        val status: OperationStatus?,
        val carriesEncodedOutput: Boolean,
        val launches: Int,
        val scriptedFailure: Throwable?,
    )

    /**
     * Runs one cell of the matrix: first execution, flip to RUNNING, plant the control-dir facts,
     * then re-run and read the result.
     *
     * [plant] receives the operation directory and writes ONLY the facts that cell is about, so no
     * cell can accidentally inherit another's `output.txt`.
     */
    private suspend fun drive(
        runId: String,
        controlDirRoot: Path,
        block: suspend ScriptedScope.() -> Any?,
        plant: (Path) -> Unit,
    ): Spine {
        val rig = Rig(runId, controlDirRoot)
        val callSites = ScriptedCallSiteProvider.fixed("f1c0/$runId")

        // 1. First execution through the real stack, so a production row with a production
        //    fingerprint and the real encoded input exists.
        ScriptedRuntime(RegistryScriptedShellRuntime(rig.invoker), callSites)
            .run(definitionDigest = runId, entryPointId = "main", block = block)
        assertEquals(1, rig.launches.get(), "PRECONDITION: the first run executes exactly once")

        // 2. Leave the row RUNNING and plant the control-dir facts for this cell.
        rig.flipRowToRunning()
        val opDir = Files.createDirectories(controlDirRoot.resolve(rig.row()!!.id))
        plant(opDir)

        val before = rig.launches.get()
        val failure = runCatching {
            ScriptedRuntime(RegistryScriptedShellRuntime(rig.invoker), callSites)
                .run(definitionDigest = runId, entryPointId = "main", block = block)
        }.exceptionOrNull()

        return Spine(
            status = rig.row()?.status,
            carriesEncodedOutput = rig.row()?.output != null,
            launches = rig.launches.get() - before,
            scriptedFailure = failure,
        )
    }

    private fun writeExit(dir: Path, code: Int) {
        Files.writeString(dir.resolve("result.txt"), code.toString())
    }

    // ================================================================== HALF A — the shipped spine

    // ------------------------------------------------------------------ NONE

    @Test
    fun `A1 NONE recovered with exit 0 journals SUCCEEDED and never re-launches`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a1"))
        val s = drive("a1", root, { sh(script = "true") }, ::writeExitZero)
        assertEquals(0, s.launches, "MEASURED: a recovered shell never re-launches")
        assertEquals(OperationStatus.SUCCEEDED, s.status)
        assertTrue(
            !s.carriesEncodedOutput,
            "MEASURED: the recovered row carries NO encoded output. For NONE there is no typed " +
                "value to materialise, so this is correct rather than a gap.",
        )
    }

    @Test
    fun `A2 NONE recovered with exit 42 journals FAILED and never re-launches`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a2"))
        val s = drive("a2", root, { sh(script = "exit 42") }, { writeExit(it, 42) })
        assertEquals(0, s.launches, "MEASURED: a recovered shell never re-launches")
        assertEquals(
            OperationStatus.FAILED,
            s.status,
            "MEASURED: for NONE a non-zero exit IS a SCRIPT failure under the contract. " +
                "CORRECT today and it must stay correct after F1-C.",
        )
    }

    // ------------------------------------------------------------------ STATUS

    @Test
    fun `A3 STATUS recovered with exit 0 journals SUCCEEDED and never re-launches`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a3"))
        val s = drive("a3", root, { sh(script = "true", returnStatus = ReturnStatus) }, ::writeExitZero)
        assertEquals(0, s.launches, "MEASURED: a recovered shell never re-launches")
        assertEquals(OperationStatus.SUCCEEDED, s.status)
    }

    /**
     * THE DISCRIMINATING ROW — half A of it.
     *
     * `sh(returnStatus = true)` with exit 42 is `Status(42) · Success` under the contract (half B,
     * row B2, proves it with the real authorities). The exit code was OBSERVED: it is in
     * `result.txt`, written by the process itself, in the control directory.
     *
     * So honouring it is not fabrication, and the value is not unknowable. The shipped spine
     * journals FAILED because the observer classifies before the Step-owned projection can read
     * `returnMode`.
     */
    @Test
    fun `A4 STATUS recovered with exit 42 journals FAILED today and that is the measured defect`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a4"))
        val s = drive("a4", root, { sh(script = "exit 42", returnStatus = ReturnStatus) }, { writeExit(it, 42) })
        assertEquals(0, s.launches, "MEASURED: a recovered shell never re-launches")
        assertEquals(
            OperationStatus.FAILED,
            s.status,
            "MEASURED DEFECT — F1-C2 owns the closure. The contract says " +
                "sh(returnStatus=true) with exit 42 is Status(42) · Success (see row B2, same " +
                "observed fact, real authorities) and the exit code WAS observed in result.txt. " +
                "The spine journals FAILED because the observer classifies exitCode != 0 as SCRIPT " +
                "before the Step-owned projection can read returnMode. When F1-C2 lands this " +
                "assertion INVERTS to SUCCEEDED with an encoded Status(42); the transition must be " +
                "declared in this message, never a silent rewrite of what is expected.",
        )
    }

    // ------------------------------------------------------------------ STDOUT — absent vs empty

    @Test
    fun `A5 STDOUT recovered with a non-empty output file journals SUCCEEDED`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a5"))
        val s = drive("a5", root, { sh(script = "echo abc", returnStdout = ReturnStdout) }) { dir ->
            writeExitZero(dir)
            Files.writeString(dir.resolve("output.txt"), "abc")
        }
        assertEquals(0, s.launches, "MEASURED: a recovered shell never re-launches")
        assertEquals(OperationStatus.SUCCEEDED, s.status)
    }

    @Test
    fun `A6 STDOUT recovered with a present but EMPTY output file journals SUCCEEDED`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a6"))
        val s = drive("a6", root, { sh(script = "true", returnStdout = ReturnStdout) }) { dir ->
            writeExitZero(dir)
            // Created and deliberately left EMPTY. This is a FACT: the program printed nothing.
            Files.createFile(dir.resolve("output.txt"))
        }
        assertEquals(0, s.launches, "MEASURED: a recovered shell never re-launches")
        assertEquals(OperationStatus.SUCCEEDED, s.status)
    }

    /**
     * The absence row — the one that separates fabrication from conservation.
     *
     * `output.txt` does not exist, so there is no evidence of what the program printed. Producing
     * `Stdout("")` would be inventing one, and `classifyShellTerminal`'s `capturedStdout.orEmpty()`
     * is exactly the operator that would do it.
     */
    @Test
    fun `A7 STDOUT recovered with an ABSENT output file journals SUCCEEDED and transports no value`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a7"))
        val s = drive("a7", root, { sh(script = "true", returnStdout = ReturnStdout) }) { dir ->
            writeExitZero(dir)
            assertTrue(
                !Files.exists(dir.resolve("output.txt")),
                "PRECONDITION: output.txt must not exist for this row to measure ABSENCE",
            )
        }
        assertEquals(0, s.launches, "MEASURED: a recovered shell never re-launches")
        assertEquals(
            OperationStatus.SUCCEEDED,
            s.status,
            "MEASURED GAP — F1-C2 owns the closure. The exit code was 0 and the shell did not fail, " +
                "so SUCCEEDED is defensible as an OUTCOME. What is not defensible is that the typed " +
                "value question is never asked: absent output.txt must fail closed rather than " +
                "become Stdout(\"\") via orEmpty().",
        )
        assertTrue(
            !s.carriesEncodedOutput,
            "MEASURED: no value is transported, so the spine does not fabricate \"\" today. The " +
                "fabrication risk is LATENT in classifyShellTerminal, not yet realised — which is " +
                "exactly why it needs a probe (M-F1-C2) rather than a test that blesses it.",
        )
    }

    @Test
    fun `A8 STDOUT recovered with a non-zero exit journals FAILED`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a8"))
        val s = drive("a8", root, { sh(script = "exit 42", returnStdout = ReturnStdout) }) { dir ->
            writeExit(dir, 42)
            Files.writeString(dir.resolve("output.txt"), "partial output before the failure")
        }
        assertEquals(0, s.launches, "MEASURED: a recovered shell never re-launches")
        assertEquals(
            OperationStatus.FAILED,
            s.status,
            "MEASURED: for STDOUT a non-zero exit IS a SCRIPT failure under the contract, even " +
                "though output.txt holds partial bytes. CORRECT today, must stay correct.",
        )
    }

    // ------------------------------------------------------------------ non-Exited terminals

    @Test
    fun `A9 a recovered timeout is FAILED TIMEOUT and terminal without re-launching`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a9"))
        val s = drive("a9", root, { sh(script = "sleep 99") }) { dir ->
            Files.writeString(dir.resolve("timeout.flag"), "")
        }
        assertEquals(0, s.launches, "MEASURED: a recovered timeout never re-launches")
        assertEquals(OperationStatus.FAILED_TIMEOUT, s.status)
    }

    @Test
    fun `A10 a lost substrate is LOST without re-launching`() = runBlocking {
        val root = Files.createDirectory(tempRoot.resolve("a10"))
        // Plant NOTHING: no result.txt, no timeout.flag, no console log. The operation directory
        // exists but is empty, which is what a vanished worker leaves.
        val s = drive("a10", root, { sh(script = "true") }, {})
        assertEquals(0, s.launches, "MEASURED: a lost substrate never re-launches")
        assertEquals(OperationStatus.LOST, s.status)
    }

    @Test
    fun `A11 an unobservable substrate fails closed and leaves the row RUNNING`() = runBlocking {
        val rig = Rig("a11", controlDirRoot = null)
        val callSites = ScriptedCallSiteProvider.fixed("f1c0/a11")
        ScriptedRuntime(RegistryScriptedShellRuntime(rig.invoker), callSites)
            .run(definitionDigest = "a11", entryPointId = "main") { sh(script = "true") }
        assertEquals(1, rig.launches.get(), "PRECONDITION: the first run executes exactly once")
        rig.flipRowToRunning()
        val before = rig.launches.get()

        runCatching {
            ScriptedRuntime(RegistryScriptedShellRuntime(rig.invoker), callSites)
                .run(definitionDigest = "a11", entryPointId = "main") { sh(script = "true") }
        }

        assertEquals(0, rig.launches.get() - before, "MEASURED: unobservable never re-launches")
        assertEquals(
            OperationStatus.RUNNING,
            rig.row()?.status,
            "MEASURED: a required recovery that could not be observed leaves the row RUNNING. It " +
                "is NOT terminalised, because a later correctly configured run can still reconcile " +
                "it. This is the one row where RUNNING is the correct TERMINAL state.",
        )
    }

    /**
     * The REAL expired reattach window, through the scripted surface, with the REAL 60 s poll.
     *
     * The first cut of this row asserted `SUCCEEDED` on the premise that a fresh heartbeat plus a
     * poll would hand back a terminal. It does not: there is no real detached process behind this
     * control directory, so `DurableShellExecutor.pollResult` finds no `result.txt` and returns
     * `null` — the window closes EMPTY. The premise was wrong and the row was measuring
     * `ReattachWindowExpired`, not "terminal in hand".
     *
     * What makes the row worth keeping is precisely that: it reaches the expiry branch through the
     * production poll in well under a second, where spike rows 8 and 9 have to inject the poll to
     * get there deterministically. So the scripted surface is shown the same `LOST` the
     * compatibility policy produces everywhere else, and the row costs no wall clock.
     *
     * `LOST` here is D-1's compatibility policy, not a law, and D-1 stays `DEFERRED`. This row
     * certifies that the policy is applied CONSISTENTLY across surfaces, which is a different claim
     * from claiming the policy is right.
     */
    @Test
    fun `A12 an expired reattach window journals LOST through the real poll without re-launching`() =
        runBlocking {
            val root = Files.createDirectory(tempRoot.resolve("a12"))
            val rig = Rig("a12", root)
            val callSites = ScriptedCallSiteProvider.fixed("f1c0/a12")
            ScriptedRuntime(RegistryScriptedShellRuntime(rig.invoker), callSites)
                .run(definitionDigest = "a12", entryPointId = "main") {
                    sh(script = "true", returnStatus = ReturnStatus)
                }
            assertEquals(1, rig.launches.get(), "PRECONDITION: the first run executes exactly once")
            rig.flipRowToRunning()
            val before = rig.launches.get()

            // Fresh heartbeat, no result.txt: the substrate says Reattach. With no real detached
            // process, the production poll finds nothing and the window closes empty.
            val opDir = Files.createDirectories(root.resolve(rig.row()!!.id))
            Files.writeString(DurableShellFiles.resolveConsoleLog(opDir), "still working")

            runCatching {
                ScriptedRuntime(RegistryScriptedShellRuntime(rig.invoker), callSites)
                    .run(definitionDigest = "a12", entryPointId = "main") {
                        sh(script = "true", returnStatus = ReturnStatus)
                    }
            }

            assertEquals(0, rig.launches.get() - before, "MEASURED: a reattach never becomes a 2nd launch")
            assertEquals(
                OperationStatus.LOST,
                rig.row()?.status,
                "MEASURED: the expired window is collapsed to Lost by the compatibility policy in " +
                    "DurableInvocationResolver, and the scripted surface sees the same terminal the " +
                    "canonical surface does. This is D-1's policy, still DEFERRED — the row claims " +
                    "CONSISTENCY across surfaces, not that the policy is correct.",
            )
        }

    // ================================================================== HALF B — what the contract says

    /*
     * HF0 Pure Contract. These call the REAL authorities on the SAME observed facts half A plants in
     * the control dir. They are not a reimplementation: `classifyShellTerminal` and `toStepOutcome`
     * are the single authorities for `core.sh` result semantics, and the FRESH path reaches them
     * through the same functions.
     *
     * Their job is to name what the product OUGHT to do, so that half A's FAILED row can be called a
     * defect rather than a preference.
     */

    private fun exited(code: Int, stdout: String?): DurableTaskTerminal =
        DurableTaskTerminal.Exited(code, DurableTaskOutput(controlDir = "/ctrl", capturedStdout = stdout))

    @Test
    fun `B1 the contract maps NONE exit 0 to UnitValue and NONE exit 42 to a SCRIPT failure`() {
        assertEquals(
            ShellInvocationResult.UnitValue,
            classifyShellTerminal(exited(0, null), ShellReturnMode.NONE),
        )
        val failed = classifyShellTerminal(exited(42, null), ShellReturnMode.NONE)
        assertTrue(failed is ShellInvocationResult.Failed, "expected Failed, got $failed")
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
            (failed as ShellInvocationResult.Failed).failure.kind,
        )
    }

    @Test
    fun `B2 the contract maps STATUS exit 42 to Status(42) and that to SUCCESS`() {
        val result = classifyShellTerminal(exited(42, null), ShellReturnMode.STATUS)
        assertEquals(
            ShellInvocationResult.Status(42),
            result,
            "CONTRACT: under STATUS, the exit code IS the value. This is the authority half A4 " +
                "contradicts, and it is the authority because it is the same function the FRESH " +
                "path calls.",
        )
        assertEquals(
            StepOutcome.Success,
            result.toStepOutcome(),
            "CONTRACT: Status classifies as Success, so a recovered 42 and a fresh 42 agree.",
        )
    }

    @Test
    fun `B3 the contract cannot distinguish a present empty output file from an absent one`() {
        assertEquals(
            ShellInvocationResult.Stdout(""),
            classifyShellTerminal(exited(0, ""), ShellReturnMode.STDOUT),
            "CONTRACT: present-and-empty is a fact, and it means an empty string.",
        )
        // And the reason the absence row must fail closed: today's authority cannot tell them apart,
        // because `orEmpty()` maps null to the same "" that an empty file produces. Rows B3 and A7
        // together are why F1-C2 must not simply call this function on a recovered terminal.
        assertEquals(
            classifyShellTerminal(exited(0, ""), ShellReturnMode.STDOUT),
            classifyShellTerminal(exited(0, null), ShellReturnMode.STDOUT),
            "MEASURED DEFECT in the authority itself: present-and-empty and absent are INDISTINGUISHABLE " +
                "through classifyShellTerminal, because capturedStdout.orEmpty() collapses them. " +
                "F1-C2 owns the fix; until then no recovered STDOUT value can be materialised " +
                "honestly through this function.",
        )
    }

    // ================================================================== the two halves, compared

    /**
     * The distance between the halves, stated once so the file cannot quietly drift into asserting
     * only one of them.
     *
     * This asserts the TABLE'S consistency, not the product's behaviour. Its value is that the
     * expected distance is written down: if a later change closes a row, this test forces whoever
     * changed it to say so, in the same commit, rather than leaving half A quietly stale.
     */
    @Test
    fun `the two halves differ on exactly one row and the authority cannot express one more`() {
        val halfA = listOf(
            Cell("NONE", "exit 0", OperationStatus.SUCCEEDED, "no value", "correct"),
            Cell("NONE", "exit 42", OperationStatus.FAILED, "no value", "correct"),
            Cell("STATUS", "exit 0", OperationStatus.SUCCEEDED, "no value", "gap"),
            Cell("STATUS", "exit 42", OperationStatus.FAILED, "no value", "DEFECT"),
            Cell("STDOUT", "exit 0 + output.txt=abc", OperationStatus.SUCCEEDED, "no value", "gap"),
            Cell("STDOUT", "exit 0 + output.txt=''", OperationStatus.SUCCEEDED, "no value", "gap"),
            Cell("STDOUT", "exit 0 + output.txt absent", OperationStatus.SUCCEEDED, "no value", "gap"),
            Cell("STDOUT", "exit 42", OperationStatus.FAILED, "no value", "correct"),
        )

        val defects = halfA.filter { it.verdict == "DEFECT" }
        assertEquals(
            1,
            defects.size,
            "MEASURED: exactly ONE row is misclassified, and it is STATUS + exit 42. If this count " +
                "changes, the observer's rule changed and every half-A row must be RE-MEASURED, not " +
                "assumed. Defects: $defects",
        )
        assertEquals("STATUS", defects.single().returnMode)

        assertEquals(
            4,
            halfA.count { it.verdict == "gap" },
            "MEASURED: four rows have the right OUTCOME but transport no typed VALUE — STATUS " +
                "exit 0, and the three STDOUT exit-0 variants. F1-C2 closes those, and only the " +
                "defect also changes an outcome. (This count was asserted as 3 in the first cut and " +
                "the test caught it: the table carries four gap rows, and the assertion, not the " +
                "table, was wrong.)",
        )

        // The authority half cannot express the absence distinction yet, so even after the DEFECT
        // row is fixed, the STDOUT-ABSENT row needs a separate change. Stating it here is what
        // stops "STATUS(42) is fixed" from being read as "recovery materialises values".
        assertNotNull(
            classifyShellTerminal(exited(0, null), ShellReturnMode.STDOUT),
            "MEASURED: the authority still answers for the absent case — with a value it cannot " +
                "distinguish from an empty file. F1-C2 must add the distinction, not just call " +
                "this function.",
        )
    }

    private data class Cell(
        val returnMode: String,
        val observed: String,
        val status: OperationStatus,
        val transported: String,
        val verdict: String,
    )

    private fun writeExitZero(dir: Path) = writeExit(dir, 0)

    @TempDir
    lateinit var tempRoot: Path
}
