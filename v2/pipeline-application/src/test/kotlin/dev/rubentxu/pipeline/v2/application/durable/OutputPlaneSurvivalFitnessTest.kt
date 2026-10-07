package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.ConsoleReadService
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * M1-INTEGRATION — the committed transcript must survive the invocation that produced it.
 *
 * ## Why this is a separate law from `OutputSingleAuthorityFitnessTest`
 *
 * That test reads the transcript back through the **live store object** that just wrote it, in the
 * same process. It therefore proves the bytes were committed, not that they are *still there*.
 * Those are different claims, and the gap between them is exactly where a durability contract dies.
 *
 * ## The hazard, named
 *
 * The shell substrate owns the control-directory lifecycle and cleans it aggressively, **inside the
 * same invocation** that produced the output. `ShExecution` deletes `console.log` and then the
 * emptied per-operation `controlDir` on a successful exit, and `DurableShellExecutor` walks that
 * per-operation directory deleting everything that is not `console.log` — recursively, and with
 * every exception swallowed.
 *
 * M1 survives that today by an accident of naming, not by construction:
 *
 * ```text
 * controlDir     = {controlDirRoot}/{opId}   <- what S4 deletes
 * Output Plane   = {controlDirRoot}/output-plane   <- what M1 writes
 * ```
 *
 * `output-plane` is a **sibling** of the per-operation directories, so the recursive walk never
 * reaches it. Nothing asserts that. It holds because a constant happens to differ from an `opId`,
 * and it would stop holding the first time someone decided the Output Plane belonged with the rest
 * of an operation's control data — a completely reasonable-looking refactor, and one that git
 * cannot flag, because it touches no file S4 also touches.
 *
 * So this file states the invariant that is currently implicit, and proves it the way an operator
 * experiences it: **a later process**, with no shared memory and no cached store, must still read
 * the committed bytes.
 */
@Timeout(180)
class OutputPlaneSurvivalFitnessTest {

    @BeforeEach
    fun resetProvider() {
        OutputPlaneProvider.forgetAll()
    }

    private fun linuxOnly() {
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    private suspend fun runSh(
        controlDirRoot: Path,
        workspaceRoot: Path,
        script: String,
        runId: String,
    ): ShellInvocationResult {
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)
        return ShExecution.invokeShell(
            command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
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
        )
    }

    /**
     * OBS-C2.3: this file no longer reads a raw store stream.
     *
     * It used to carry a `readAll(stream, store)` helper that addressed the operation's fused
     * `.../transcript` stream. That stream no longer receives bytes, and a helper like it is worse
     * than its absence: it would keep offering a reader a way to see "stdout only" and call it the
     * transcript. Reads now go through [ConsoleReadService], which composes the operation's channel
     * streams the way a consumer actually does.
     */

    @Test
    fun `committed output survives the invocation that produced it, as seen by a later process`(
        @TempDir root: Path,
    ) = runBlocking {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-survives"
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)

        val result = runSh(
            controlDirRoot = controlDirRoot,
            workspaceRoot = workspaceRoot,
            script = "echo SURVIVOR-MARKER",
            runId = runId,
        )
        // This assertion is load-bearing, not decoration. The control-directory cleanup under test
        // only runs on a SUCCESSFUL exit - it is gated on `exitCode == 0` - so a step that failed
        // would leave its transcript on disk for the wrong reason and make this whole test pass
        // without ever exercising the hazard. Green here has to mean "the cleanup ran and the bytes
        // outlived it".
        //
        // `UnitValue` witnesses that exit, and it witnesses it because of how `classifyShellTerminal`
        // is written rather than because of a convention: under `ShellReturnMode.NONE` an
        // `Exited` terminal maps to `UnitValue` if and only if `exitCode == 0`, and to
        // `scriptFailure()` otherwise. So this cannot pass on a non-zero exit.
        assertInstanceOf(
            ShellInvocationResult.UnitValue::class.java,
            result,
            "a plain sh step that exits 0 returns UnitValue, and only a zero exit can",
        )

        // The invocation is over. Drop the cached store so the next read cannot borrow the live
        // object that wrote these bytes - this is what a new JVM looks like.
        OutputPlaneProvider.forget(controlDirRoot)
        // OBS-C2.3: read through the console service, which is what a consumer actually calls. It
        // composes the operation's two channel streams; asking the store for one of them directly
        // would read stdout only and then call that "the transcript".
        val bytes = (
            ConsoleReadService.read(controlDirRoot, runId, opId.format(), null, 64 * 1024)
                as? dev.rubentxu.pipeline.v2.application.ConsoleReadService.Result.Page
            )?.page?.bytes?.toString(StandardCharsets.UTF_8).orEmpty()

        assertTrue(
            bytes.contains("SURVIVOR-MARKER"),
            "the committed transcript did not survive the control-directory cleanup; " +
                "the Output Plane must be a sibling of the per-operation control dirs, never inside one",
        )
    }

    @Test
    fun `the Output Plane is not inside any per-operation control directory`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-not-inside"
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)

        runSh(
            controlDirRoot = controlDirRoot,
            workspaceRoot = workspaceRoot,
            script = "echo ANOTHER-MARKER",
            runId = runId,
        )

        // The structural statement of the same invariant, asserted directly so that a future
        // refactor which moves the store gets a failure naming the geometry rather than a
        // mysterious empty transcript.
        val planeDir = controlDirRoot.resolve(OutputPlaneProvider.OUTPUT_DIR)
        assertTrue(
            Files.isDirectory(planeDir),
            "the Output Plane must be a directory directly under the control-dir root",
        )
        val perOperation = controlDirRoot.resolve(opId.format())
        assertTrue(
            !planeDir.startsWith(perOperation),
            "the Output Plane lives inside the per-operation control dir " +
                "($perOperation), which the shell substrate deletes on success",
        )
        assertEquals(
            controlDirRoot.normalize(),
            planeDir.parent.normalize(),
            "the Output Plane must sit directly under the control-dir root, as a sibling of the " +
                "per-operation control directories",
        )
    }

    @Test
    fun `a fresh process recovers the committed extent rather than starting empty`(
        @TempDir root: Path,
    ) = runBlocking {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-recovered-extent"
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)

        runSh(
            controlDirRoot = controlDirRoot,
            workspaceRoot = workspaceRoot,
            script = "echo FIRST; echo SECOND",
            runId = runId,
        )

        OutputPlaneProvider.forget(controlDirRoot)
        // OBS-C2.3: the recovered console is composed from the operation's channel streams, so this
        // row asserts against what a consumer actually receives rather than against one channel.
        val fromStart = assertInstanceOf(
            dev.rubentxu.pipeline.v2.application.ConsoleReadService.Result.Page::class.java,
            ConsoleReadService.read(controlDirRoot, runId, opId.format(), null, 4096),
            "a recovered stream must page, not refuse",
        )
        assertTrue(
            fromStart.page.bytes.isNotEmpty(),
            "a later process recovered a zero-length stream from a step that printed two lines",
        )
        assertTrue(
            fromStart.page.bytes.toString(StandardCharsets.UTF_8).contains("FIRST") &&
                fromStart.page.bytes.toString(StandardCharsets.UTF_8).contains("SECOND"),
            "recovery lost committed bytes: ${fromStart.page.bytes.toString(StandardCharsets.UTF_8)}",
        )
    }
}
