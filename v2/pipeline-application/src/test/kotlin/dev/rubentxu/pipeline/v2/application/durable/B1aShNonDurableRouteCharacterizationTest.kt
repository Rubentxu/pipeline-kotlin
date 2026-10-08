package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * B1a — CHARACTERISATION of `ShExecution`'s non-durable shell route (triage AUD-02).
 *
 * ## The subject, exactly
 *
 * `ShExecution.invokeShell(controlDirRoot = null)` reaches
 * `ShExecution.executeNonDurableInvocation` (private; reached only through `invokeShell`) and
 * `ProcessDurableTaskRuntime` (`:pipeline-step-sdk:runtime`). The durable comparator is the same
 * `invokeShell` with a real control-directory root, which runs `DurableShellExecutor.executeTerminal`
 * and `ShExecution.ingestTranscriptIntoOutputPlane`.
 *
 * ## Fidelity (HARNESS FIDELITY LAW §1)
 *
 * **HF1 — in-process, through the production authorities, named:** every row enters through
 * `ShExecution.invokeShell` → `ProcessDurableTaskRuntime` (non-durable) or
 * `DurableShellExecutor.executeTerminal` (durable), with a real `bash` child process, real pipes and
 * a real on-disk control directory. No algorithm is re-implemented: the heap probes sample the JVM
 * the production code is running in, they do not model it. The one authority this file does NOT
 * cross is a production CLI run, which the slice brief forbids — so the reachability question (a) is
 * answered by source enumeration in the receipt, not here, and is labelled as such.
 *
 * ## What the assertions are, and what they are NOT
 *
 * This is a **characterisation**, not a certification. The non-durable route is documented debt
 * (triage AUD-02): it drops the caller's timeout budget and materialises the whole transcript in
 * memory. The rows below assert the *measured current behaviour* and say so in their message; the
 * two that measure a defect carry `CHARACTERISATION OF A DEFECT` with the law that is violated, so
 * that the day the defect closes the flip is deliberate rather than silent. Nothing here is a
 * blessing of the route.
 *
 * ## Hermeticity
 *
 * `@TempDir` for every scratch path; no network; no wall-clock assertion (elapsed times are recorded
 * as observations, never asserted). The one exception to "no writes outside `@TempDir`" is
 * deliberate and is itself a measurement: when `controlDirRoot == null` the production code creates
 * its own directory under `java.io.tmpdir` (`Files.createTempDirectory("pipeline-sh-non-durable")`)
 * and never removes it. The harness writes nothing there; it observes the delta, reports it, and
 * deletes what production left (teardown hygiene). `java.io.tmpdir` cannot be redirected per test:
 * the JDK caches it in a static field at `java.io.TempFileHelper` class-load time.
 */
@Timeout(300)
class B1aShNonDurableRouteCharacterizationTest {

    private val productionTempDirs = mutableSetOf<Path>()
    private val payloadBytes = 16 * 1024 * 1024

    @AfterEach
    fun removeWhatTheNonDurableRouteLeftBehind() {
        // Only the directories this class observed as a delta of its own invocations are deleted:
        // a blanket sweep of the prefix could remove another fork's scratch space mid-test.
        for (dir in productionTempDirs) {
            runCatching {
                Files.walk(dir).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
        productionTempDirs.clear()
    }

    // ------------------------------------------------------------------ (b) heap

    /**
     * AUD-02 (b): resident set as a function of transcript size, non-durable vs durable.
     *
     * The property is a **difference**, not a threshold on a machine: the same shell command, the
     * same byte count, the same return mode, only the control-directory root varies. The durable
     * side is the comparator that makes the difference attributable to the route rather than to the
     * JVM or to the operating system.
     *
     * Two quantities are reported per arm, and they answer different questions:
     *
     * - **peak** (`totalMemory - freeMemory`, sampled every 2 ms) is an upper bound on what the route
     *   needed while running. It includes garbage the collector had not yet reclaimed, so it is a
     *   demand figure, not a live-set figure.
     * - **live** (`totalMemory - freeMemory` after asking for GC three times, *while the arm's own
     *   result and sink are still reachable*) is the figure that decides whether a transcript is
     *   resident. For the non-durable route the observable console content is a `String` the event
     *   holds, so it must show up here; for the durable route the transcript's home is the plane on
     *   disk.
     *
     * Both arms prove the bytes really flowed (content length / committed extent), so neither can
     * pass by producing nothing. Each arm runs in its own function so the previous arm's references
     * are out of scope before the next baseline is taken: measuring arm B with arm A's 16 MiB string
     * still reachable would be a harness defect, not a product property.
     */
    @Test
    @Timeout(180)
    fun `b - a large transcript is materialised on the non-durable route and streamed on the durable one`(
        @TempDir root: Path,
    ) {
        linuxOnly()
        val script = bigOutputScript(payloadBytes)
        val expectedBytes = payloadBytes + 8

        val nonDurable = nonDurableArm(root, script, expectedBytes)
        val durable = durableArm(root, script, expectedBytes)

        println("B1a(b) non-durable: peakUsedDelta=${nonDurable.peakDelta} liveAfterGcDelta=${nonDurable.liveDelta} (${nonDurable.detail})")
        println("B1a(b) durable:     peakUsedDelta=${durable.peakDelta} liveAfterGcDelta=${durable.liveDelta} (${durable.detail})")

        // The decisive property: the non-durable route keeps the transcript resident, because its
        // only observable rendering IS the string; the durable route's home for those bytes is the
        // plane on disk.
        println("B1a(b) live difference (non-durable - durable) = ${nonDurable.liveDelta - durable.liveDelta} bytes")
        assertTrue(
            nonDurable.liveDelta - durable.liveDelta >= payloadBytes / 2,
            "CHARACTERISATION (AUD-02 b): the non-durable route materialises the whole transcript, so " +
                "its live set must exceed the durable route's by at least half the payload. " +
                "non-durable live=${nonDurable.liveDelta} durable live=${durable.liveDelta} " +
                "payload=$payloadBytes. If this ever fails, either the route stopped materialising " +
                "(a fix: re-read the receipt) or the probe stopped seeing retention (a harness defect).",
        )
        assertTrue(
            nonDurable.peakDelta - durable.peakDelta >= payloadBytes / 2,
            "CHARACTERISATION (AUD-02 b): the same difference must be visible in the sampled demand " +
                "peak. non-durable peak=${nonDurable.peakDelta} durable peak=${durable.peakDelta} " +
                "payload=$payloadBytes.",
        )
    }

    private data class HeapArm(val peakDelta: Long, val liveDelta: Long, val detail: String)

    private fun nonDurableArm(root: Path, script: String, expectedBytes: Int): HeapArm {
        val sink = RecordingSink()
        val before = nonDurableTempDirs()
        val baseline = usedHeapAfterGc()
        val sampler = PeakHeapSampler().start()
        val result = runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(script = script),
                opId = OpId(runId = "b1a-non-durable", stageIndex = 0, stepIndex = 0),
                runId = "b1a-non-durable",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = shOptions(root.resolve("ws-non-durable"), timeoutMs = null),
                controlDirRoot = null,
                eventSink = sink,
            )
        }
        val peak = sampler.stopAndPeak() - baseline
        val content = sink.events.filterIsInstance<EchoOutputCaptured>().single().content
        assertEquals(
            expectedBytes,
            content.length,
            "the non-durable route must observe every byte of the transcript it materialises; a " +
                "smaller number would mean the heap probe measured a partial transfer",
        )
        assertEquals(ShellInvocationResult.UnitValue, result, "non-durable arm result")
        // Against the snapshot taken BEFORE the call. Passing the current set here was a real harness
        // defect: the delta came out empty, nothing was claimed for teardown, and one production
        // scratch directory per run stayed in `java.io.tmpdir`. The measurement did not change; the
        // hygiene did. Recorded as a harness lesson in the B1a receipt.
        val residue = recordCreatedTempDirs(before)
        val live = usedHeapAfterGc() - baseline
        // `result`, `content` and `sink` are still reachable here on purpose: that is the live set a
        // caller of this route is left holding.
        return HeapArm(peak, live, "contentLength=${content.length} dirsLeftBehind=$residue")
    }

    private fun durableArm(root: Path, script: String, expectedBytes: Int): HeapArm {
        val controlRoot = Files.createDirectories(root.resolve("control"))
        val runId = "b1a-durable"
        val sink = RecordingSink()
        val baseline = usedHeapAfterGc()
        val sampler = PeakHeapSampler().start()
        val result = runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(script = script),
                opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0),
                runId = runId,
                stageIndex = 0,
                stepIndex = 0,
                shOptions = shOptions(root.resolve("ws-durable"), timeoutMs = null),
                controlDirRoot = controlRoot,
                eventSink = sink,
            )
        }
        val peak = sampler.stopAndPeak() - baseline

        // Route truth: the durable route's transcript authority is the Output Plane. If the durable
        // route had silently fallen back (LinuxRequiredException) there would be no stream at all,
        // and the comparison would be comparing the route with itself.
        val extent = OutputPlaneProvider.storeFor(controlRoot)
            .committedExtent(OutputPlaneProvider.streamId(runId, "b1a-durable-s0-0"))
        assertTrue(
            extent != null && extent >= expectedBytes.toLong(),
            "the durable arm must have committed the transcript to the Output Plane (extent=$extent, " +
                "expected >= $expectedBytes); a null or short extent means the durable route fell " +
                "back to the non-durable one and this row's comparator is void",
        )
        assertTrue(
            sink.events.none { it is EchoOutputCaptured },
            "M1-P2: the durable route's transcript is the plane's, so no console event carries it",
        )
        assertEquals(ShellInvocationResult.UnitValue, result, "durable arm result")
        val live = usedHeapAfterGc() - baseline
        return HeapArm(peak, live, "planeExtent=$extent consoleEvents=${sink.events.size}")
    }

    // ---------------------------------------------------------------- (c) timeout

    /**
     * AUD-02 (c): does the caller's `shOptions.timeoutMs` budget survive the non-durable route?
     *
     * The durable arm is the control: it must classify the same command, with the same budget, as
     * `Interrupted(TIMEOUT)`. The non-durable arm is the measurement. Assertions are on the closed
     * classification, never on elapsed time; elapsed is recorded as an observation only.
     */
    @Test
    @Timeout(120)
    fun `c - the timeout budget is honoured by the durable route and dropped by the non-durable one`(
        @TempDir root: Path,
    ) {
        linuxOnly()
        val script = "printf 'BEGIN\\n'; sleep 2; printf 'END\\n'"
        val budgetMs = 300L

        val durableRunId = "b1a-timeout-durable"
        val controlRoot = Files.createDirectories(root.resolve("control"))
        val durable = runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(script = script),
                opId = OpId(runId = durableRunId, stageIndex = 0, stepIndex = 0),
                runId = durableRunId,
                stageIndex = 0,
                stepIndex = 0,
                shOptions = shOptions(root.resolve("ws-t-durable"), timeoutMs = budgetMs),
                controlDirRoot = controlRoot,
                eventSink = RecordingSink(),
            )
        }
        assertTrue(
            durable is ShellInvocationResult.Interrupted &&
                durable.interruption.kind == InterruptionKind.TIMEOUT,
            "control arm: the durable route must honour the budget and classify it as " +
                "Interrupted(TIMEOUT); observed=$durable",
        )

        val nonDurableStart = System.nanoTime()
        val beforeTimeoutNonDurable = nonDurableTempDirs()
        val nonDurable = runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(script = script),
                opId = OpId(runId = "b1a-timeout-non-durable", stageIndex = 0, stepIndex = 0),
                runId = "b1a-timeout-non-durable",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = shOptions(root.resolve("ws-t-non-durable"), timeoutMs = budgetMs),
                controlDirRoot = null,
                eventSink = RecordingSink(),
            )
        }
        val elapsedMs = (System.nanoTime() - nonDurableStart) / 1_000_000
        println("B1a(c) non-durable with timeoutMs=$budgetMs observed=$nonDurable elapsedMs=$elapsedMs")
        println("B1a(c) non-durable control dirs left behind: ${recordCreatedTempDirs(beforeTimeoutNonDurable)}")

        assertTrue(
            nonDurable !is ShellInvocationResult.Interrupted,
            "CHARACTERISATION OF A DEFECT (AUD-02 c): executeNonDurableInvocation hard-codes " +
                "`timeoutMs = null` in its TaskExecutionRequest and never reads shOptions.timeoutMs, " +
                "so the caller's budget disappears. Observed=$nonDurable (expected: NOT Interrupted). " +
                "When the budget is threaded, this row flips to require Interrupted(TIMEOUT).",
        )
        assertEquals(
            ShellInvocationResult.UnitValue,
            nonDurable,
            "the non-durable route runs the command to completion, so a droppable budget is " +
                "indistinguishable from no budget at all",
        )
        assertTrue(
            elapsedMs >= 2_000 - 250,
            "observation, not a law: the child slept ~2s because nothing killed it",
        )
    }

    // ----------------------------------------------------------- (d) cancellation

    /**
     * AUD-02 (d): what happens to a cancellation that arrives while the non-durable child runs.
     *
     * The script writes a started marker, sleeps far longer than the test, then writes a finished
     * marker. Cancellation is issued once the started marker exists (state positioning; the marker
     * is the condition, not a sleep). Three things are then observed, and none of them is a
     * duration:
     *
     * 1. the verdict the production call itself produced, captured as a **value** outside the
     *    cancelled scope (`CompletableDeferred` completed without suspending), so "the CE escaped"
     *    and "the CE was swallowed into a typed result" cannot be confused;
     * 2. `result.txt` inside the production control directory, which records what
     *    `ProcessDurableTaskRuntime` itself decided (`CANCELLED`);
     * 3. no living descendant process and no finished marker.
     *
     * PAR-D is the law that makes (1) load-bearing: a `CancellationException` is an execution
     * mechanism and MUST NOT be mapped to a generic infrastructure failure.
     */
    @Test
    @Timeout(120)
    fun `d - cancellation of the caller is measured on the non-durable route`(@TempDir root: Path) {
        linuxOnly()
        val scratch = Files.createDirectories(root.resolve("cancel"))
        val started = scratch.resolve("started.marker")
        val finished = scratch.resolve("finished.marker")
        val script = ": > '$started'; sleep 30; : > '$finished'"
        val runId = "b1a-cancel"
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)
        val before = nonDurableTempDirs()

        // The scope carries no explicit dispatcher: the cancellation semantics under study do not
        // depend on which pool the invocation runs on, and `Dispatchers.Default` is what `launch`
        // picks anyway (detekt's InjectDispatcher agrees, and this call site has no reason to disagree).
        val scope = CoroutineScope(Job())
        val verdictOfTheCall = CompletableDeferred<String>()
        val job = scope.launch {
            val verdict = try {
                val result = ShExecution.invokeShell(
                    command = ShellCommand(script = script),
                    opId = opId,
                    runId = runId,
                    stageIndex = 0,
                    stepIndex = 0,
                    shOptions = shOptions(root.resolve("ws-cancel"), timeoutMs = null),
                    controlDirRoot = null,
                    eventSink = RecordingSink(),
                )
                "RETURNED:${describe(result)}"
            } catch (ce: CancellationException) {
                "THREW_CANCELLATION"
            } catch (t: Throwable) {
                "THREW_OTHER:${t::class.qualifiedName}:${t.message}"
            }
            // Non-suspending: recorded even though the scope is cancelled by now.
            verdictOfTheCall.complete(verdict)
        }

        awaitCondition(deadlineMs = 30_000, description = "the non-durable child started") {
            Files.exists(started)
        }
        job.cancel()
        val verdict = runBlocking { withTimeout(30_000) { verdictOfTheCall.await() } }
        runBlocking { withTimeoutOrNull(20_000) { job.join() } }
        println("B1a(d) verdict of the cancelled call = $verdict")

        // (2) What the production runtime wrote down about its own run.
        val created = recordCreatedTempDirs(before)
        assertEquals(
            1,
            created.size,
            "exactly one production control dir must have been created for this invocation: $created",
        )
        val resultFile = created.single().resolve(opId.format()).resolve("result.txt")
        val recorded = if (Files.exists(resultFile)) Files.readString(resultFile).trim() else "<absent>"
        println("B1a(d) result.txt says = $recorded")
        assertEquals(
            "CANCELLED",
            recorded,
            "ProcessDurableTaskRuntime records CANCELLED when it sees the cancellation; an absent " +
                "or different record means the runtime did not classify its own terminal state",
        )

        // (3) The tree was destroyed, so the marker after the sleep can never appear.
        assertFalse(
            Files.exists(finished),
            "the child process tree must be destroyed on cancellation; the script's post-sleep " +
                "marker exists, so something outlived the cancellation",
        )
        val survivors = nonDurableChildProcesses()
        assertTrue(
            survivors.isEmpty(),
            "no descendant may still be running the non-durable script: $survivors",
        )

        // (1) The verdict is the measurement, and it is the row that carries a defect.
        assertTrue(
            verdict.startsWith("RETURNED:Failed(kind=INFRASTRUCTURE"),
            "CHARACTERISATION OF A DEFECT (AUD-02 d / PAR-D): executeNonDurableInvocation catches " +
                "`Exception` around ProcessDurableTaskRuntime.execute, which rethrows the " +
                "CancellationException after recording CANCELLED, so the cancellation is converted " +
                "into a generic infrastructure failure: the caller cannot tell 'the run was " +
                "cancelled' from 'the shell substrate broke'. Observed=$verdict. PAR-D: " +
                "'CancellationException is an execution mechanism: it MUST NOT be mapped to a " +
                "generic infrastructure failure or to a terminal durable outcome.' When that is " +
                "fixed, this row flips to require the cancellation to propagate " +
                "(THREW_CANCELLATION) rather than be returned as a value.",
        )
        println("B1a(d) MEASURED  = $verdict")
    }

    // ------------------------------------------------- the CLI's non-Linux fallback

    /**
     * AUD-02 (e): the fallback at `ShExecution.kt:321` — reachable from the CLI, with a non-null root.
     *
     * `invokeShell` calls the durable substrate first and, when `DurableShellExecutor` refuses the
     * platform (`checkLinuxOrThrow()` reads `os.name` on every call), routes to the SAME
     * `executeNonDurableInvocation` with the caller's real control root. So the null-root branch is
     * not the only door into this function, and the reachability question (a) turns on which door the
     * product can open.
     *
     * The platform property is **simulated**, and the KDoc says so: this runs on Linux and makes
     * `os.name` report a non-Linux host for the duration of one call, restoring it in a `finally`.
     * That is a faithful way to enter the branch (`checkLinuxOrThrow` reads the property per call and
     * the value is not cached anywhere), and it is NOT a claim to have run on macOS.
     */
    @Test
    @Timeout(180)
    fun `e - the non-Linux fallback enters the same non-durable function with a non-null control root`(
        @TempDir root: Path,
    ) {
        linuxOnly()
        val controlRoot = Files.createDirectories(root.resolve("control"))
        val runId = "b1a-nonlinux"
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)
        val sink = RecordingSink()
        val script = "printf 'BEGIN\\n'; sleep 2; printf 'END\\n'"
        val budgetMs = 300L
        val osName = System.getProperty("os.name")

        val started = System.nanoTime()
        val result = try {
            System.setProperty("os.name", "Mac OS X")
            runBlocking {
                ShExecution.invokeShell(
                    command = ShellCommand(script = script),
                    opId = opId,
                    runId = runId,
                    stageIndex = 0,
                    stepIndex = 0,
                    shOptions = shOptions(root.resolve("ws-nonlinux"), timeoutMs = budgetMs),
                    controlDirRoot = controlRoot,
                    eventSink = sink,
                )
            }
        } finally {
            System.setProperty("os.name", osName)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val planeUsed = OutputPlaneProvider.storeFor(controlRoot).hasOutputFor(runId)
        val consoleEvents = sink.events.filterIsInstance<EchoOutputCaptured>()
        println(
            "B1a(e) forced os.name='Mac OS X', controlDirRoot=<real>: observed=$result " +
                "elapsedMs=$elapsedMs planeHasStream=$planeUsed consoleEvents=${consoleEvents.size} " +
                "osNameRestored=${System.getProperty("os.name") == osName}",
        )

        assertFalse(
            planeUsed,
            "route truth: the fallback does not touch the Output Plane, so a stream here would mean " +
                "the durable substrate ran after all and this row measured nothing",
        )
        assertEquals(
            1,
            consoleEvents.size,
            "the non-durable implementation's only rendering is the console event (M1-P2), so exactly " +
                "one must have been emitted; observed=${sink.events.map { it::class.simpleName }}",
        )
        assertTrue(
            consoleEvents.single().content.contains("BEGIN") &&
                consoleEvents.single().content.contains("END"),
            "the fallback must have run the script to completion",
        )
        assertTrue(
            result !is ShellInvocationResult.Interrupted,
            "CHARACTERISATION OF A DEFECT (AUD-02 e): the fallback enters the same function whose " +
                "TaskExecutionRequest hard-codes `timeoutMs = null`, so a run that reached the " +
                "CLI's control root still loses its budget on a non-Linux host. Observed=$result " +
                "(expected: NOT Interrupted).",
        )
        assertTrue(
            elapsedMs >= 2_000 - 250,
            "observation, not a law: the child slept ~2s because nothing killed it",
        )
        assertTrue(
            System.getProperty("os.name") == osName,
            "the simulated platform property must have been restored",
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun shOptions(workspace: Path, timeoutMs: Long?): ShOptions {
        Files.createDirectories(workspace)
        return ShOptions(
            workspaceRoot = workspace,
            captureStdout = false,
            timeoutMs = timeoutMs,
            env = emptyMap<String, SecretHandle>(),
        )
    }

    /** `BEGIN` + N bytes of 'A' + `END`, with no dependence on the shell's own buffering. */
    private fun bigOutputScript(bytes: Int): String =
        "printf 'BEGIN'; head -c $bytes /dev/zero | tr '\\0' 'A'; printf 'END'"

    private fun describe(result: ShellInvocationResult): String = when (result) {
        ShellInvocationResult.UnitValue -> "UnitValue"
        is ShellInvocationResult.Stdout -> "Stdout(${result.value.length} chars)"
        is ShellInvocationResult.Status -> "Status(${result.exitCode})"
        is ShellInvocationResult.Failed ->
            "Failed(kind=${result.failure.kind}, message='${result.failure.message}')"
        is ShellInvocationResult.Interrupted ->
            "Interrupted(kind=${result.interruption.kind}, message='${result.interruption.message}')"
    }

    private fun linuxOnly() {
        assumeTrue(
            System.getProperty("os.name").lowercase().contains("linux"),
            "the durable comparator route is Linux-only (DurableShConfig.LinuxRequiredException)",
        )
    }

    /** A sink that keeps what it was given, so the row can assert the bytes reached the caller. */
    private class RecordingSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    private class PeakHeapSampler {
        @Volatile private var running = true
        @Volatile private var peak = 0L
        private val thread = Thread {
            while (running) {
                val used = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                if (used > peak) peak = used
                runCatching { Thread.sleep(2) }
            }
        }

        fun start(): PeakHeapSampler {
            thread.isDaemon = true
            thread.name = "b1a-peak-heap-sampler"
            thread.start()
            return this
        }

        fun stopAndPeak(): Long {
            running = false
            thread.join(5_000)
            return peak
        }
    }

    private fun usedHeapAfterGc(): Long {
        repeat(3) {
            System.gc()
            runCatching { Thread.sleep(60) }
        }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    private fun nonDurableTempDirs(): Set<Path> {
        val tmpRoot = Path.of(System.getProperty("java.io.tmpdir"))
        if (!Files.isDirectory(tmpRoot)) return emptySet()
        return Files.newDirectoryStream(tmpRoot).use { entries ->
            entries.asSequence()
                .filter { Files.isDirectory(it) }
                .filter { it.fileName.toString().startsWith("pipeline-sh-non-durable") }
                .toSet()
        }
    }

    /**
     * The directories the non-durable route created since [before], claimed for teardown.
     *
     * This is also the leak measurement: nothing in the production route deletes its own
     * `pipeline-sh-non-durable` directory, so the delta is a count of directories that outlive the
     * invocation that made them.
     */
    private fun recordCreatedTempDirs(before: Set<Path>): Set<Path> {
        val created = nonDurableTempDirs() - before
        productionTempDirs += created
        return created
    }

    /** Descendants of this JVM still executing a non-durable script. Empty means the tree died. */
    private fun nonDurableChildProcesses(): List<String> =
        ProcessHandle.current().descendants().toList()
            .mapNotNull { it.info().commandLine().orElse(null) }
            .filter { it.contains("pipeline-sh-non-durable") }

    private fun awaitCondition(deadlineMs: Long, description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + deadlineMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            runCatching { Thread.sleep(20) }
        }
        throw AssertionError("timed out after ${deadlineMs}ms waiting for: $description")
    }
}
