package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.durable.ShExecution
import dev.rubentxu.pipeline.v2.application.durable.terminalDeclaresTranscriptIncomplete
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.OUTPUT_PERSISTENCE_REFUSED
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * WU-B — a seal is a claim of completeness, so it may not be made for a transcript the runtime has
 * just refused to call complete.
 *
 * ## The defect this row closes
 *
 * `ShExecution.invokeShell` classified the terminal and then called `sealOperationTail`
 * **unconditionally**. A seal is the durable statement "this operation's output is complete", and
 * it is the very statement `console --follow` terminates on (`ReachedSealedOutput`). So when the
 * executor refused a transcript — a sink that lost a write, or a pump still holding the read end
 * at the drain budget — the stream was sealed anyway, and the refusal produced an artifact that
 * told every downstream reader the short transcript was final.
 *
 * The `DrainIncomplete` case is not merely inaccurate but unsafe: the pump is still alive and will
 * keep writing, so sealing races a live writer against the store's finality marker.
 *
 * ## What these rows pin
 *
 * ```text
 * SEAL-1  a healthy real sh  -> the reader still terminates on the seal (HF1, control)
 * SEAL-2  a refused terminal -> the seal is withheld                (HF0, the predicate)
 * SEAL-3  any OTHER failure -> the seal still happens               (HF0)
 * ```
 *
 * SEAL-1 is the row that would catch this block breaking the ordinary path, which is the risk any
 * "do not seal" change carries.
 *
 * ## Harness fidelity
 *
 * **SEAL-1 is HF1** — a real `sh` through the real [ShExecution.invokeShell], and the assertion is
 * on the real `SegmentOutputStore`'s own `SealOutcome` for the production stream ids. Nothing is
 * re-derived: the transcript is read back through the same reader the console uses.
 *
 * **SEAL-2/3 are HF0**, and that is the faithful level rather than a convenient one:
 * `terminalDeclaresTranscriptIncomplete` is a pure predicate over the terminal ADT with no clock,
 * no filesystem and no process. Calling it directly IS the production path for that decision.
 *
 * ## What this class does NOT claim
 *
 * The refused terminal is not produced by a real failing store here. `OutputPlaneProvider` hands
 * out a concrete `SegmentOutputStore` with no substitution seam, so injecting a store that refuses
 * would mean opening the provider — a change wider than the defect and wider than this block
 * authorises. So the end-to-end wiring "a real failing store ⇒ no seal" is **BLOCKED**, not
 * claimed: what is proven is the decision predicate plus the real path that consults it, and
 * `TranscriptPumpCompletionTest` (HF1) separately proves that a real pump failure really produces
 * the refused terminal this predicate recognises.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ShTranscriptSealIntegrityTest {

    @TempDir
    lateinit var root: Path

    @BeforeEach
    fun resetProvider() {
        OutputPlaneProvider.forgetAll()
    }

    private fun launchFailed(code: String): DurableTaskTerminal.LaunchFailed =
        DurableTaskTerminal.LaunchFailed(
            FailureRecord(
                code = code,
                kind = FailureKind.INFRASTRUCTURE,
                message = "process output could not be persisted",
                origin = FailureOrigin.DURABLE_TASK,
                retryable = false,
                operationId = "run-x-s0-0",
            ),
        )

    // ------------------------------------------------------------------ predicate (HF0)

    /**
     * SEAL-2 — the row the block exists for.
     *
     * Both refusal shapes must withhold the seal. The terminal is already a failure, so the caller
     * still learns the step failed and why; what is withheld is the *completeness* claim, and only
     * that.
     */
    @Test
    fun `SEAL-2 a refused transcript withholds the seal`() {
        assertTrue(
            terminalDeclaresTranscriptIncomplete(launchFailed(OUTPUT_PERSISTENCE_REFUSED)),
            "a transcript the runtime refused to call complete must not be sealed: the seal is the " +
                "statement every downstream reader terminates on.",
        )
    }

    /**
     * SEAL-3 — and only that failure withholds it.
     *
     * A step that exits 7, times out, or is cancelled produced no transcript at all, and its
     * streams are complete precisely because there was nothing to write. Treating those as
     * "incomplete" would leave every failed and timed-out step's streams unsealed, which is a much
     * larger behavioural change than the defect authorises.
     */
    @Test
    fun `SEAL-3 a failure that is not a transcript refusal still seals`() {
        for (terminal in listOf(
            DurableTaskTerminal.Exited(
                exitCode = 7,
                output = dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput(controlDir = "/tmp/ctrl"),
            ),
            launchFailed("SOMETHING_ELSE_FAILED"),
            DurableTaskTerminal.Cancelled(
                dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord(
                    kind = dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind.TIMEOUT,
                    message = "timed out",
                    operationId = "run-x-s0-0",
                ),
            ),
        )) {
            assertFalse(
                terminalDeclaresTranscriptIncomplete(terminal),
                "only $OUTPUT_PERSISTENCE_REFUSED withholds a seal; got $terminal",
            )
        }
    }

    // ------------------------------------------------------------------ product (HF1)

    /**
     * SEAL-1 — a healthy real run must still seal, and a reader must still terminate on it.
     *
     * Without this row, a change that stopped sealing anything would satisfy SEAL-2 and SEAL-3
     * perfectly and break every reader in the product. That is the specific regression a "do not
     * seal" change is capable of, so it is asserted against a real process rather than trusted.
     *
     * The assertion is deliberately NOT `store.seal(stream) == AlreadySealed`: calling `seal` on an
     * UNSEALED stream would seal it and turn a red into a green. It asks the real consumer instead —
     * `runFollowFrames` reaches `FollowExit.Completed` only when every stream reads back as
     * `OutputTailState.Sealed`, which is the same fact `console --follow` terminates on.
     */
    @Test
    fun `SEAL-1 a healthy real sh seals and a reader terminates on it`() {
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("ws"))
        val runId = "run-seal"
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)

        runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(
                    script = "printf 'stdout-line\\n'; printf 'stderr-line\\n' >&2",
                    returnMode = ShellReturnMode.NONE,
                ),
                opId = opId,
                runId = runId,
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions(
                    workspaceRoot = workspaceRoot,
                    captureStdout = false,
                    timeoutMs = 60_000,
                    env = emptyMap(),
                    sandbox = SandboxConfig.NONE,
                ),
                controlDirRoot = controlDirRoot,
                eventSink = InMemoryEventStore(),
                secretPatternRegistry = null,
            )
        }

        val out = java.io.ByteArrayOutputStream()
        val exit = MainConsoleCli.runFollowFrames(
            request = ConsoleReadRequest.FollowFrames(
                controlDir = controlDirRoot.toString(),
                runId = runId,
                opId = opId.format(),
                afterOrdinal = -1L,
                frameLimit = 64,
                followConfig = FollowConfig(pollIntervalMillis = 1L, maxPolls = 5),
                format = MainConsoleCli.ConsoleFormat.TEXT,
            ),
            root = controlDirRoot,
            out = java.io.PrintStream(out, true, "UTF-8"),
            err = java.io.PrintStream(java.io.ByteArrayOutputStream(), true, "UTF-8"),
            selection = dev.rubentxu.pipeline.v2.application.console.OperationSelection.AllOperations,
        )

        assertEquals(
            FollowExit.Completed,
            exit,
            "every stream must read back as sealed, or `console --follow` cannot terminate. The " +
                "ordinary path stopped sealing. emitted: '${out.toString("UTF-8")}'",
        )
        assertTrue(
            out.toString("UTF-8").contains("stdout-line"),
            "and the sealed transcript still reads back — a seal that lost the bytes would pass this too",
        )
    }
}