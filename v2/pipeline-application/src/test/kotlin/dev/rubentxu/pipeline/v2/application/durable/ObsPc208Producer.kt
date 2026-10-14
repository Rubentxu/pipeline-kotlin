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
 * General-purpose producer for OBS-PC-208.
 *
 * Unlike [ObsPc2JvmOwnerDeathProducer] (which is fixed to the spike shape:
 * 300 before + 50 after, no stderr, fixed opId), this one lets each test
 * pick the line counts, whether to write a stderr corpus, and which opId
 * to use. The argv order is what the test and the producer agree on; the
 * convenience [argv] helper makes the contract explicit.
 */
object ObsPc208Producer {

    /**
     * @param args `controlDirRoot`, `runId`, `workspace`, `barrier`, `go`, `done`,
     *   `secret`, `linesBefore`, `linesAfter`, `extraStderr` ("1"/"0"),
     *   `opIdOverride` (empty = use the default), `scriptExtras` (extra shell
     *   appended after the standard emit).
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val controlDirRoot = Paths.get(args[0])
        val runId = args[1]
        val workspace = Paths.get(args[2])
        val barrier = args[3].let { if (it.isEmpty()) null else Paths.get(it) }
        val go = Paths.get(args[4])
        val done = Paths.get(args[5])
        val secret = args[6]
        val linesBefore = args[7].toInt()
        val linesAfter = args[8].toInt()
        val extraStderr = args[9] == "1"
        val opIdOverride = args[10].ifEmpty { null }
        val scriptExtras = args[11]

        val registry = SecretPatternRegistry().apply { addSecret(SecretHandle.plain(secret)) }

        val plainBefore = if (linesBefore > 0) {
            (1..linesBefore).joinToString("; ") { "printf '%s' 'plain-before-$it;'" }
        } else ""
        // The canary line is only emitted when at least one of the before/after
        // line counts is positive. f05 ("producer that exits without writing
        // anything") uses both counts at zero; the canary would be the only
        // stdout byte and would defeat the falsifier.
        val canaryLine = if (linesBefore > 0 || linesAfter > 0) {
            "printf '%s' 'canary=$secret;'"
        } else ""
        val perLineMarkers = if (linesAfter > 0) {
            (1..linesAfter).joinToString("; ") {
                "printf '%s' 'plain-after-$it;stderr-corpus-$it;'; touch $workspace/AFTER-$it"
            }
        } else ""
        val stderrCorpus = if (extraStderr) {
            "for i in 1 2 3 4 5 6 7 8 9 10; do printf 'stderr-line-%s\\n' \"\$i\" 1>&2; done"
        } else ""

        val opId = opIdOverride ?: OpId(runId = runId, stageIndex = 0, stepIndex = 0).format()

        // R2.3(b) / TODO 4: the previous buildString unconditionally prefixed
        // each non-empty section with "; " and ALWAYS appended "; touch done".
        // That had two failures:
        //   1. When the canary was empty (linesBefore=0 AND linesAfter=0) the
        //      script started with "; " — a leading empty command, which
        //      /bin/sh rejects with a syntax error pointing at the rest of
        //      the line, polluting stderr with a multi-hundred-byte error
        //      that the agent then had to drain instead of the child's
        //      actual output.
        //   2. When scriptExtras already ended with "touch done" (f01, f02,
        //      f04) the final "; touch done" produced a duplicate touch.
        // The fix is a parts list joined with "; ": empty sections are
        // dropped, the separator is only inserted between non-empty pieces,
        // and the final "touch done" is one part like any other.
        val parts = mutableListOf<String>()
        if (plainBefore.isNotEmpty()) parts.add(plainBefore)
        if (canaryLine.isNotEmpty()) parts.add(canaryLine)
        if (barrier != null) {
            parts.add("touch $barrier")
            parts.add("while [ ! -f $go ]; do sleep 0.05; done")
            parts.add("touch ${workspace.resolve("RESUMED")}")
            parts.add("printf 'alive-after-jvm-death' > ${workspace.resolve("ALIVE_PROOF")}")
            parts.add("touch ${workspace.resolve("ALIVE_MARK")}")
        }
        if (perLineMarkers.isNotEmpty()) parts.add(perLineMarkers)
        if (stderrCorpus.isNotEmpty()) parts.add(stderrCorpus)
        if (scriptExtras.isNotEmpty()) parts.add(scriptExtras)
        parts.add("touch $done")
        val script = parts.joinToString("; ")

        runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                opId = OpId(runId = runId, stageIndex = 0, stepIndex = if (opIdOverride != null) opId.substringAfterLast("-").toIntOrNull() ?: 0 else 0),
                runId = runId,
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions(
                    workspaceRoot = workspace,
                    captureStdout = false,
                    timeoutMs = 0,
                    env = emptyMap(),
                    sandbox = SandboxConfig.NONE,
                    pumpedViaAgent = true,
                    agentSecret = secret,
                ),
                controlDirRoot = controlDirRoot,
                eventSink = InMemoryEventStore(),
                secretPatternRegistry = registry,
            )
        }
    }

    fun argv(
        controlDirRoot: Path,
        runId: String,
        workspace: Path,
        barrier: Path?,
        go: Path,
        done: Path,
        secret: String,
        linesBefore: Int,
        linesAfter: Int,
        extraStderr: Boolean,
        opIdOverride: String? = null,
        scriptExtras: String = "",
    ): Array<String> = arrayOf(
        controlDirRoot.toString(),
        runId,
        workspace.toString(),
        barrier?.toString() ?: "",
        go.toString(),
        done.toString(),
        secret,
        linesBefore.toString(),
        linesAfter.toString(),
        if (extraStderr) "1" else "0",
        opIdOverride ?: "",
        scriptExtras,
    )
}
