package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.nio.file.Files
import java.nio.file.Path

/**
 * The release of the resources that belong to a launch.
 *
 * ## What this owns
 *
 * The two things a launch leaves behind and that the execution has to give up: the descriptors
 * the runtime opened for the ingest agent, and the control directory the execution filled. The
 * RULES that say what survives and what does not are here too, and they are the whole of this
 * component's judgement.
 *
 * Nothing else. The verdict of the execution is [ShellTerminalDecision]'s, the expiry is
 * [ShellWatchdog]'s, the transcript's bytes are [TranscriptDrain]'s, the names of the protocol
 * are [ShellProtocolLayout]'s, and the console transcript's name is [DurableShellFiles]'s.
 *
 * ## Why the rules live here and not in the executor
 *
 * The executor decided the directory's fate inline, in a method whose first act was to read a
 * configuration flag — and that flag could not change the outcome in either position:
 *
 *  - with the flag ON, the method fell through to a branch that only deletes on success, so a
 *    failed execution kept its directory;
 *  - with the flag OFF, the method returned early, which is the same retention.
 *
 * So the rule was "a failed execution keeps its directory", and it was written as a
 * configuration that could not be honoured. Honouring it — deleting on failure — would destroy
 * the evidence the protocol depends on: the exit code, the timeout marker and the heartbeat are
 * what [StepReconcilerL1] reads to tell an expired execution from a lost one, and a failed
 * execution's directory is that evidence, not a debugging convenience. The flag was retired with
 * this extraction and the rule is now written down where it is read.
 *
 * ## What this does NOT own
 *
 * The NAME of the transcript that survives a disposal: it is [DurableShellFiles]'s, and this
 * component asks for the path instead of spelling the name. The expiry is [ShellWatchdog]'s,
 * and the executor hands this component the facts it already holds: an exit code that is -1 for
 * an expired execution, and whether the transcript is to be kept.
 */
internal class ShellResourceCleanup(
    /** The control directory of the execution whose resources are released. */
    private val controlDir: Path,
) {

    /** The names and paths of the protocol, including the console transcript's path. */
    private val protocol = ShellProtocolLayout(controlDir)

    /**
     * Releases the resources of the launch that the runtime opened.
     *
     * Only the warm-up descriptors are the runtime's to release. The transcript pumps are
     * [TranscriptDrain]'s — they were joined and drained before this runs — and the child's
     * handle belongs to the OS, which reaps it when the child dies.
     *
     * The descriptors are released WHATEVER happened, because the ingest agent's read returns
     * EOF only when every writer is gone: a descriptor left open keeps the agent waiting for a
     * transcript that will never grow, and leaks a descriptor for the life of the JVM.
     *
     * One descriptor that refuses to close does not keep the others open: each is released on
     * its own, and a failure to release one is no reason to leave the rest held.
     */
    fun release(launched: LaunchedShell?) {
        launched?.agentWarmup?.forEach { descriptor ->
            try {
                descriptor.close()
            } catch (_: Throwable) {
                // Deliberate: the next descriptor is released anyway. The release must not
                // change the verdict of the execution it is releasing.
            }
        }
    }

    /**
     * Gives up the control directory, according to the final exit code.
     *
     * A failed execution keeps its directory WHOLE: the exit code, the timeout marker and the
     * heartbeat are the protocol's evidence, and a disposal that kept the console transcript
     * while deleting the rest would leave a transcript and destroy the proof. A successful
     * execution gives the directory up, and the console transcript survives only when the caller
     * asked for it — the production consumer streams it after the executor returns and deletes
     * it itself.
     */
    fun dispose(exitCode: Int, keepTranscriptLog: Boolean) {
        when (val fate = fateOf(exitCode, keepTranscriptLog)) {
            ControlDirFate.Retained -> Unit
            is ControlDirFate.Disposed -> disposeControlDir(keepConsoleLog = fate.consoleLogSurvives)
        }
    }

    /**
     * Whether this path survives the disposal of the control directory.
     *
     * The comparison is by PATH, not by the file's name: the console transcript survives because
     * it is THE transcript of THIS control directory, and a file that happens to carry the same
     * name deeper in the tree is not it.
     */
    fun survivesDisposal(candidate: Path): Boolean = candidate == protocol.consoleLog

    /**
     * Deletes everything under the control directory, except the console transcript when
     * [keepConsoleLog] says it survives.
     *
     * The deletion is best-effort and MUST NOT change the verdict of the execution: it runs in
     * the executor's `finally`, and an exception escaping from here would replace the terminal
     * the execution earned with a cleanup failure.
     */
    private fun disposeControlDir(keepConsoleLog: Boolean) {
        try {
            Files.walk(controlDir).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { candidate ->
                    try {
                        if (!keepConsoleLog || !survivesDisposal(candidate)) {
                            Files.deleteIfExists(candidate)
                        }
                    } catch (_: Throwable) {
                        // One entry that refuses to be deleted does not stop the others.
                    }
                }
            }
        } catch (_: Throwable) {
            // The walk itself failed — a missing or unreadable directory. There is nothing to
            // release, and the failure must not reach the caller.
        }
    }

    /**
     * What the disposal of a control directory does.
     *
     * A closed ADT because the two answers are exclusive, and the mistake that matters is
     * mixing them: a failed execution's directory has to survive WHOLE. Two booleans would let
     * a caller say "retained and disposed" and nobody would notice.
     */
    sealed interface ControlDirFate {

        /** The directory and everything in it survive: the execution failed and its evidence is the protocol's. */
        data object Retained : ControlDirFate

        /** The directory is disposed; [consoleLogSurvives] says whether the console transcript was kept. */
        data class Disposed(val consoleLogSurvives: Boolean) : ControlDirFate
    }

    internal companion object {

        /**
         * The fate of the control directory, given the final exit code.
         *
         * A non-zero exit code means the execution did not succeed, and its directory is
         * retained. A zero exit code means it succeeded, and the directory is disposed; the
         * console transcript survives that disposal only when the caller asked for it.
         */
        fun fateOf(exitCode: Int, keepTranscriptLog: Boolean): ControlDirFate =
            if (exitCode != 0) {
                ControlDirFate.Retained
            } else {
                ControlDirFate.Disposed(consoleLogSurvives = keepTranscriptLog)
            }
    }
}
