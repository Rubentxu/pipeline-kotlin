package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step-level reconciler for durable shell.
 *
 * ## What it decides
 * A durable `sh` operation leaves a control directory behind, and a later process — one that
 * outlived the worker that started it — has to answer a single question about it: did the step
 * finish, did it time out, is it still running, or is its outcome unknown. This class answers that
 * question from the directory's own contents, and it is the only authority that does.
 *
 * ## The four classifications
 * - **Complete**: `result.txt` exists and holds an exit code.
 * - **TimedOut**: `timeout.flag` exists — the watchdog writes it BEFORE it kills.
 * - **Reattach**: no result yet, but the console transcript is fresh: the child may still be alive.
 * - **Lost**: nothing can be said — fail closed.
 *
 * ## The fail-closed rule
 * If the outcome cannot be established with certainty, the classification is `Lost`, never
 * `Complete`. A worker that died during a `sh` step leaves three possibilities — the child is still
 * running detached, the child crashed, the result was never written — and none of them is success.
 * `Lost` means: do not mark the step succeeded, do not skip it, and let the caller schedule a new
 * attempt with the same fingerprint.
 *
 * A `result.txt` that cannot be read as an exit code is `Lost`, not `Complete`. The file exists,
 * but its content is not the fact the durable protocol promises there, and reading another
 * protocol's record as this one's success is exactly the fabrication the rule forbids.
 *
 * ## The order of the checks, and why it is this order
 * 1. `timeout.flag` first: the watchdog writes it BEFORE the kill, so its presence identifies a
 *    timeout-killed process even when the heartbeat is stale for an unrelated reason.
 * 2. `result.txt` next: a published exit code is the strongest fact available.
 * 3. The heartbeat's freshness last, and only when there is no result: a fresh console transcript
 *    means the child may still be running, and only the absence of every fact means the outcome is
 *    unknown. A control directory that already holds its answer is never made to fail by a
 *    transcript whose timestamp cannot be read.
 *
 * ## Where it is called from
 * The resume path of a `sh` step. `DurableInvocationResolver` decides that a journaled RUNNING
 * operation needs an external-subprocess recovery, `RunningSubprocessRecovery` asks this class for
 * the classification of the operation's control directory, and the classification becomes the
 * observed terminal. A `sh` step declares `RecoveryPolicy.ExternalSubprocess`, so this is the path
 * a resumed `sh` takes.
 */
class StepReconcilerL1(
    private val clock: Clock,
    private val controlDirRoot: Path,
    private val config: DurableShConfig = DurableShConfig.fromSystemProperties(),
) {
    /**
     * Classification of a RUNNING step row during reconciliation.
     */
    sealed class Classification {
        /**
         * Step completed; result.txt exists and holds an exit code.
         * Use the cached exit code from result.txt.
         */
        data class Complete(val exitCode: Int) : Classification()

        /**
         * Step may still be running or completed but result.txt not yet written.
         * Heartbeat is fresh (log file was touched recently).
         * Re-run with the SAME fingerprint is safe.
         */
        data class Reattach(val controlDir: Path) : Classification()

        /**
         * Step was killed by watchdog timeout (SIGKILL).
         * The timeout.flag file was written before kill, signaling that
         * the watchdog triggered. This is distinct from LOST (worker crash)
         * because we KNOW the process was killed by deadline.
         * TIMED_OUT is terminal: FAILED_TIMEOUT is journaled and the step
         * will NOT be re-executed on resume.
         */
        data class TimedOut(val controlDir: Path, val logPath: Path) : Classification()

        /**
         * Step outcome is unknown. result.txt missing AND heartbeat is stale.
         * Fail-closed: must not assume success.
         * The caller should mark this as LOST and schedule a new attempt.
         */
        data object Lost : Classification()
    }

    /**
     * Classifies a RUNNING step's control directory.
     * @param opId The operation ID (used to find the control directory).
     * @return The classification of this step's current state.
     */
    fun classify(opId: String): Classification {
        val controlDir = controlDirRoot.resolve(opId)
        return classifyControlDir(controlDir)
    }

    /**
     * Classifies a step by its control directory path.
     *
     * The reading of the directory and the decision over what it holds are two halves, and only the
     * second one is a pure function of facts. Splitting them here is what lets the guarantee be
     * pinned without a control directory on disk, which a unit test may not create.
     *
     * @param controlDir The control directory for this step.
     * @return The classification of this step's current state.
     */
    fun classifyControlDir(controlDir: Path): Classification {
        val protocol = ShellProtocolLayout(controlDir)
        return classifyControlDirFacts(
            controlDir = controlDir,
            logPath = DurableShellFiles.resolveConsoleLog(controlDir),
            resultFile = protocol.resultFile,
            timeoutFlag = protocol.timeoutFlag,
            probe = FileSystemControlDirProbe(clock),
            config = config,
        )
    }
}

/**
 * How the reconciler asks a control directory what it holds.
 *
 * The seam exists so that the ORDER of the checks and the LAZINESS of the heartbeat read can be
 * observed without touching a filesystem. A probe answers four questions — does this path exist,
 * what does it say, when was it last touched, and what time is it now — and the decision is
 * expressed entirely over those answers.
 */
internal interface ControlDirProbe {
    /** Whether [path] exists. */
    fun exists(path: Path): Boolean

    /** The text of [path], or null when it exists but cannot be read. */
    fun readText(path: Path): String?

    /** When [path] was last touched, in epoch milliseconds. */
    fun lastModifiedMillis(path: Path): Long

    /** The current time, in epoch milliseconds. */
    fun nowMillis(): Long
}

/**
 * The probe over a real control directory.
 *
 * A result that cannot be read is reported as unreadable rather than as absent: the two are
 * different facts, and the decision treats them the same way only because the protocol makes a
 * result that cannot be read as good as no result at all.
 */
internal class FileSystemControlDirProbe(private val clock: Clock) : ControlDirProbe {
    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun readText(path: Path): String? = try {
        Files.readString(path)
    } catch (_: Exception) {
        null
    }

    override fun lastModifiedMillis(path: Path): Long = Files.getLastModifiedTime(path).toMillis()

    override fun nowMillis(): Long = clock.now().toEpochMilli()
}

/**
 * The classification a control directory's facts imply.
 *
 * This is the whole decision, and it is a pure function of the probe's answers: it reads nothing
 * itself, and the same answers always produce the same classification. That is what makes the
 * guarantee — a resumed `sh` fails closed — observable without a control directory on disk.
 *
 * @param controlDir the control directory these facts come from
 * @param logPath the console transcript the heartbeat is measured on
 * @param resultFile the file the exit code is published in
 * @param timeoutFlag the marker the watchdog writes before it kills
 * @param probe how the control directory is asked what it holds
 * @param config the durable shell configuration the heartbeat threshold comes from
 */
internal fun classifyControlDirFacts(
    controlDir: Path,
    logPath: Path,
    resultFile: Path,
    timeoutFlag: Path,
    probe: ControlDirProbe,
    config: DurableShConfig,
): StepReconcilerL1.Classification {
    // Check 0: Is timeout.flag present? (check BEFORE heartbeat)
    if (probe.exists(timeoutFlag)) {
        return StepReconcilerL1.Classification.TimedOut(controlDir, logPath)
    }

    // Check 1: Is result.txt present?
    if (probe.exists(resultFile)) {
        val text = probe.readText(resultFile)
        if (text == null) {
            // Present but unreadable: the outcome cannot be established, so it fails closed.
            return StepReconcilerL1.Classification.Lost
        }
        return try {
            StepReconcilerL1.Classification.Complete(text.trim().toInt())
        } catch (_: Exception) {
            // Malformed result.txt → treat as LOST. The durable protocol publishes a plain integer
            // there; anything else is a record of some other protocol, and reading it as this
            // one's success would fabricate it.
            StepReconcilerL1.Classification.Lost
        }
    }

    // Check 2: Is heartbeat fresh? Consulted ONLY when there is no result, and that order is
    // behaviour rather than convenience: a control directory that already holds its answer must
    // not be made to fail by a transcript whose timestamp cannot be read.
    if (probe.exists(logPath)) {
        val logLastModified = probe.lastModifiedMillis(logPath)
        if (heartbeatIsFresh(probe.nowMillis(), logLastModified, config)) {
            // Heartbeat is fresh but no result.txt → may still be running
            return StepReconcilerL1.Classification.Reattach(controlDir)
        }
    }

    // Check 3: Heartbeat is stale → LOST (fail-closed)
    return StepReconcilerL1.Classification.Lost
}

/**
 * Whether a console transcript last touched at [lastModifiedMillis] is fresh at [nowMillis].
 *
 * The threshold is the check interval plus the minimum delta: a transcript that was touched within
 * the interval the wrapper is supposed to touch it is evidence that the child is alive, and one
 * that was not is evidence that nobody knows. The boundary is inclusive — a transcript exactly at
 * the threshold is fresh — because the wrapper touches on the interval, not after it.
 */
internal fun heartbeatIsFresh(
    nowMillis: Long,
    lastModifiedMillis: Long,
    config: DurableShConfig,
): Boolean {
    val thresholdMillis = config.heartbeatCheckInterval * 1000 + config.heartbeatMinimumDelta * 1000
    return (nowMillis - lastModifiedMillis) <= thresholdMillis
}
