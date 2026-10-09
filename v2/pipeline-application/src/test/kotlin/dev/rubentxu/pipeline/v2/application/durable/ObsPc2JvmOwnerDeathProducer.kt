package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The producer a [ObsPc2ProducerSurvivalSpikeTest] kills — and the harness for the OBS-2 Level B spike.
 *
 * ## Why this is not [ObsBJvmDeathProducer]
 *
 * That producer emits its whole payload, touches its barrier, and then parks. So the JVM is killed
 * after the child has already written everything it was ever going to write, and the row proves —
 * correctly — that acknowledged bytes survive a `kill -9`. It says nothing about what happens to the
 * bytes a child produces **after** its JVM is gone, which is the whole Level B question.
 *
 * Reusing it was the cheaper option and the wrong one: it is the harness behind an already-passing
 * durability row, so changing its script shape would perturb evidence that is not under study. A
 * second harness for a different claim is cheaper than a false result.
 *
 * ## The shape, and why each marker exists
 *
 * ```text
 * emit --before-- lines        -> BARRIER    test kills the JVM here
 * wait for GO                             test releases GO only AFTER the JVM is dead
 * touch RESUMED                           the child resumed at all
 * write a FILE  -> ALIVE_PROOF             the child is alive AND not killed by a watchdog
 * emit --after-- lines, marker per line -> DONE
 * ```
 *
 * `GO` is what makes this a measurement rather than a sleep: without it the child races ahead and an
 * empty tail cannot be told apart from "had not got there yet".
 *
 * `RESUMED` and `ALIVE_PROOF` exist to separate three fictions that all present as "no output after
 * the kill", and which imply completely different repairs:
 *
 * | Observed | Reading |
 * |---|---|
 * | `RESUMED` absent | the child was killed by something else — a watchdog, not the pipe |
 * | `RESUMED` + `ALIVE_PROOF`, no per-line markers | the child is alive and dies **on its stdout write** — `EPIPE`/`SIGPIPE` |
 * | per-line markers present | the child survived its writes and Level B is already closer than expected |
 *
 * Without the split, "no output after the kill" is a single observation compatible with all three, and
 * the architecture chosen on top of it would be a guess.
 *
 * ## Markers are not the secret
 *
 * The identification lines are plain text. The durable bytes are redacted, so a line carrying the
 * canary never appears verbatim and searching for it proves nothing but the redactor's existence. One
 * dedicated line does carry the canary, and that is what the at-rest check uses.
 */
object ObsPc2JvmOwnerDeathProducer {

    /**
     * @param args `controlDirRoot`, `runId`, `workspace`, `barrierFile`, `goFile`, `doneFile`,
     *   `secret`, `linesBefore`, `linesAfter`
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val controlDirRoot = Paths.get(args[0])
        val runId = args[1]
        val workspace = Paths.get(args[2])
        val barrier = Paths.get(args[3])
        val go = Paths.get(args[4])
        val done = Paths.get(args[5])
        val secret = args[6]
        val linesBefore = args[7].toInt()
        val linesAfter = args[8].toInt()

        val registry = SecretPatternRegistry().apply { addSecret(SecretHandle.plain(secret)) }

        val plainBefore = (1..linesBefore).joinToString("; ") { "printf '%s' 'plain-before-$it;'" }
        // Exactly one before-line carries the canary, so redaction can be checked without making every
        // line unfindable.
        val canaryLine = "printf '%s' 'canary=$secret;'"

        val perLineMarkers = (1..linesAfter).joinToString("; ") { "printf '%s' 'plain-after-$it;'; touch $workspace/AFTER-$it" }

        val script = buildString {
            append(plainBefore)
            append("; ").append(canaryLine)
            append("; touch ").append(barrier)
            append("; while [ ! -f ").append(go).append(" ]; do sleep 0.05; done")
            append("; touch ").append(workspace.resolve("RESUMED"))
            append("; printf 'alive-after-jvm-death' > ").append(workspace.resolve("ALIVE_PROOF"))
            append("; touch ").append(workspace.resolve("ALIVE_MARK"))
            append("; ").append(perLineMarkers)
            append("; touch ").append(done)
        }

        runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0),
                runId = runId,
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions(
                    workspaceRoot = workspace,
                    captureStdout = false,
                    timeoutMs = 0,
                    env = emptyMap(),
                    sandbox = SandboxConfig.NONE,
                ),
                controlDirRoot = controlDirRoot,
                eventSink = InMemoryEventStore(),
                secretPatternRegistry = registry,
            )
        }
        // Reaching here means the step returned on its own. That is the Level B outcome, not the crash
        // path, and the test asserts on it rather than on this line.
    }

    /** Convenience for building argv, so the test and the producer cannot drift on argument order. */
    fun argv(
        controlDirRoot: Path,
        runId: String,
        workspace: Path,
        barrier: Path,
        go: Path,
        done: Path,
        secret: String,
        linesBefore: Int,
        linesAfter: Int,
    ): Array<String> = arrayOf(
        controlDirRoot.toString(),
        runId,
        workspace.toString(),
        barrier.toString(),
        go.toString(),
        done.toString(),
        secret,
        linesBefore.toString(),
        linesAfter.toString(),
    )
}