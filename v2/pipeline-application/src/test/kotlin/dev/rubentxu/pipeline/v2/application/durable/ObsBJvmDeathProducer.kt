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
 * The producer a [ObsBJvmDeathOutputRecoveryUatTest] kills.
 *
 * ## Why this exists instead of driving the CLI
 *
 * The UAT needs three things that pull against each other: a **real** `kill -9` on the JVM holding
 * the open reservation, **deterministic** knowledge of which stream belongs to the run, and the
 * **real** pump, redactor and store underneath.
 *
 * Reading the run identity out of the CLI means reading the operation journal while the step is
 * mid-flight, which is itself a timing assumption — and a harness that guesses an identity proves
 * nothing about the store. Forking a JVM that runs the genuine [ShExecution] keeps the crash, the
 * redactor and the store completely real while making the stream identity a value the test owns.
 * What it does not cover is CLI argument plumbing, which is not the property under test.
 *
 * ## What it must NOT do
 *
 * It must not flush, close or shut anything down on the way out. The whole point is that it dies
 * mid-write, so the store is left holding whatever was durably acknowledged and nothing more.
 */
object ObsBJvmDeathProducer {

    /**
     * @param args `controlDirRoot`, `runId`, `workspace`, `barrierFile`, `secret`, `payloadLines`
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val controlDirRoot = Paths.get(args[0])
        val runId = args[1]
        val workspace = Paths.get(args[2])
        val barrier = Paths.get(args[3])
        val secret = args[4]
        val payloadLines = args[5].toInt()

        val registry = SecretPatternRegistry().apply { addSecret(SecretHandle.plain(secret)) }

        // Enough matched bytes to cross many live windows, then a barrier, then block. The barrier
        // is the test's proof that the child is alive; the block keeps it alive until SIGKILL.
        val emitter = (1..payloadLines).joinToString("; ") { "printf '%s' '$secret-line-$it'" }
        val script = "$emitter; touch $barrier; while [ ! -f $barrier.release ]; do sleep 0.05; done"

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
        // Reaching here means the step returned, i.e. the test released it. Not the crash path.
    }

    /** Convenience for building the producer's argv, so the test and the producer cannot drift. */
    fun argv(
        controlDirRoot: Path,
        runId: String,
        workspace: Path,
        barrier: Path,
        secret: String,
        payloadLines: Int,
    ): Array<String> = arrayOf(
        controlDirRoot.toString(),
        runId,
        workspace.toString(),
        barrier.toString(),
        secret,
        payloadLines.toString(),
    )
}
