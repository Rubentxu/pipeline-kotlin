package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.nio.file.Path

/**
 * The names and paths of the durable shell's persistent protocol.
 *
 * ## Why this exists
 * A durable shell step is observed by more than one process, and those processes share
 * nothing but these names:
 *
 *  - the executor WRITES the exit code (the wrapper's `mv` publishes it);
 *  - the executor READS it (the poll that decides the step's terminal);
 *  - a LATER process READS it too — [StepReconcilerL1] deciding whether a step finished,
 *    timed out or was lost, and the recovery observer that reports the captured value.
 *
 * Before this existed the names were literals repeated across those readers and writers.
 * A rename in one of them was silent: the wrapper would publish the exit code where nobody
 * polled for it, or a reader would look for a file the writer never wrote and classify a
 * FINISHED step as LOST. Both failures look the same from the outside — "the step never
 * finished" — and neither is reported as a protocol error.
 *
 * ## These names are the durable format
 * A change to one of them is a change to the format that another process, or an operator with
 * a control directory in front of them, reads. It is therefore an explicit decision and not
 * an edit, and the same law that governs every other durable format in this product governs
 * these ones.
 *
 * ## What this does NOT own
 *  - The console transcript's NAME, which belongs to [DurableShellFiles] and is only delegated
 *    below. `console.log` has one authority and it is not this one.
 *  - The control directory of `dev.rubentxu.pipeline.v2.sdk.runtime.durable.task`'s
 *    [dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime], which uses
 *    `script.sh` and `result.txt` for a DIFFERENT protocol: its `result.txt` holds a terminal
 *    RECORD (`EXIT <code> | TIMED_OUT | CANCELLED`), not an exit code. Same names, different
 *    family, different readers, different control root. It is deliberately out of this
 *    layout's scope, and pulling it in would merge two formats that happen to share two
 *    words.
 */
class ShellProtocolLayout(
    /** The control directory of one durable shell operation. */
    val controlDir: Path,
) {

    /** The user's script, written verbatim before the launch. */
    val scriptFile: Path get() = controlDir.resolve(SCRIPT_FILE)

    /** The copy of the script that exists only for the Jenkins Text-File-Busy workaround. */
    val scriptCopyFile: Path get() = controlDir.resolve(SCRIPT_COPY_FILE)

    /** The generated wrapper. The launch's argv is `[setsid, bash, wrapper.sh]`. */
    val wrapperFile: Path get() = controlDir.resolve(WRAPPER_FILE)

    /** The cookie: the wrapper writes its PID here, and its presence means "the child is alive". */
    val cookieFile: Path get() = controlDir.resolve(COOKIE_FILE)

    /** The exit code, published atomically by renaming [resultTmpFile] onto it. */
    val resultFile: Path get() = controlDir.resolve(RESULT_FILE)

    /** The file the exit code is written to before the rename onto [resultFile]. */
    val resultTmpFile: Path get() = controlDir.resolve(RESULT_TMP_FILE)

    /** The captured stdout: the typed VALUE channel of `returnStdout`. */
    val outputFile: Path get() = controlDir.resolve(OUTPUT_FILE)

    /** The timeout marker. The watchdog writes it BEFORE the kill, so it is the authority. */
    val timeoutFlag: Path get() = controlDir.resolve(TIMEOUT_FLAG)

    /** The ingest agent's own log. Nothing in the runtime depends on it. */
    val agentLog: Path get() = controlDir.resolve(AGENT_LOG)

    /** The directory holding the FIFO rendezvous between the child and the ingest agent. */
    val fifoDir: Path get() = controlDir.resolve(FIFO_DIR)

    /** The child's stdout FIFO: the runtime holds it open, the ingest agent reads it. */
    val stdoutFifo: Path get() = fifoDir.resolve(STDOUT_FIFO)

    /** The child's stderr FIFO. */
    val stderrFifo: Path get() = fifoDir.resolve(STDERR_FIFO)

    /** The marker the ingest agent touches once it holds both FIFO read ends. */
    val agentReadyMarker: Path get() = fifoDir.resolve(AGENT_READY_MARKER)

    /**
     * The merged console transcript: the WRITE target.
     *
     * The name belongs to [DurableShellFiles]; this only delegates, so the transcript keeps
     * one authority instead of gaining a second one that happens to agree today.
     */
    val consoleLog: Path get() = DurableShellFiles.consoleLog(controlDir)

    companion object {
        /** The user's script, written verbatim before the launch. */
        const val SCRIPT_FILE: String = "script.sh"

        /** The copy of the script that exists only for the Jenkins Text-File-Busy workaround. */
        const val SCRIPT_COPY_FILE: String = "script.sh.copy"

        /** The generated wrapper. */
        const val WRAPPER_FILE: String = "wrapper.sh"

        /** The cookie file; its existence means the child process is alive. */
        const val COOKIE_FILE: String = ".cookie"

        /** The exit code. */
        const val RESULT_FILE: String = "result.txt"

        /** The file the exit code is written to before the rename onto [RESULT_FILE]. */
        const val RESULT_TMP_FILE: String = "result.txt.tmp"

        /** The captured stdout channel. */
        const val OUTPUT_FILE: String = "output.txt"

        /** The timeout marker, written before the kill. */
        const val TIMEOUT_FLAG: String = "timeout.flag"

        /** The ingest agent's own log. */
        const val AGENT_LOG: String = "agent.log"

        /** The directory holding the FIFO rendezvous. */
        const val FIFO_DIR: String = "fifos"

        /** The child's stdout FIFO. */
        const val STDOUT_FIFO: String = "stdout.fifo"

        /** The child's stderr FIFO. */
        const val STDERR_FIFO: String = "stderr.fifo"

        /** The marker the ingest agent touches once it holds both FIFO read ends. */
        const val AGENT_READY_MARKER: String = "agent.ready"
    }
}
