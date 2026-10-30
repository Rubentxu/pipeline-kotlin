package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.io.Closeable

/**
 * ONE launch: the child process AND the state that belongs to it.
 *
 * ## Why the state travels with the launch
 *
 * The pumps, the sinks they wrote into and the agent's warm-up descriptors used to live
 * in three `@Volatile` fields on the executor: [DurableShellExecutor.launch] wrote them
 * and [DurableShellExecutor.executeCore] read them a few lines later. The KDoc called
 * that a "single-threaded launch discipline: one launch per executeCore" — a
 * DISCIPLINE, not an invariant. Nothing stopped two threads from sharing one executor:
 * the second launch would overwrite the fields and the first execution would drain the
 * SECOND's pumps. What comes out of that is the "short, ordered, plausible" transcript
 * that this codebase refuses to accept anywhere else.
 *
 * The exchange is not reachable today — every caller builds its own executor — but it is
 * one refactor away, and a discipline was the only thing holding it. A value removes the
 * reason to trust it: the state a launch produced IS the state that launch returns, so
 * there is nothing left to exchange.
 *
 * ## Why it carries only what the caller needs
 *
 * The handle to kill with, the reader of the code the child left with, the pumps to drain, the
 * sinks to read refusals from, and the warm-up descriptors to close when the child is done. The
 * agent's own bookkeeping stays inside the executor, which is the only thing that can act on it.
 *
 * ## Why the child is carried twice
 *
 * The handle and the reader are the SAME child seen two ways, and each of them answers a
 * question the other cannot:
 *
 *  - the HANDLE is the identity and the kill. It is captured while the child is alive, because
 *    a handle obtained from a dead child is not obtainable at all: the platform looks the pid up
 *    among the live processes and finds nothing there.
 *  - the READER is the outcome. The exit code of a child that ended is reported by the platform
 *    to the object that started it, and to nothing else, so the launch closes over the child and
 *    hands over the reading of its code.
 *
 * Neither is derived from the other, and neither is a copy: the child is one process, and these
 * are the two views of it that the kill and the wait need.
 */
internal class LaunchedShell(
    /** The child, for the kill: its identity and the signal the kill sends. */
    val handle: ProcessHandle,
    /**
     * The code the child left with, for the wait that reports the child's own reason.
     *
     * It is read from the object the platform reports it to, and from nowhere else: a handle
     * says whether a process is alive and never what it exited with, so a reason read from a
     * handle would have to be invented.
     */
    val exitCode: ShellExitCode,
    /** The transcript pumps THIS launch started. Empty when the channel was not pumped. */
    val transcriptPumps: List<TranscriptPumpView>,
    /** The sinks those pumps wrote into. Their refusals are THIS launch's refusals. */
    val transcriptSinks: List<ProcessOutputSink>,
    /** The warm-up descriptors of the ingest agent's FIFOs, to close when the child is done. */
    val agentWarmup: List<Closeable>,
) {
    /** The first refusal any channel's sink accepted responsibility for and could not fulfil. */
    fun transcriptRefusal(): ProcessOutputRefusal? =
        transcriptSinks.firstNotNullOfOrNull { it.refusal() }
}
