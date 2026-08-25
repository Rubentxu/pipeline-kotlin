package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step-level reconciler for L1 durable shell (ML-R1 / ADR-0046).
 *
 * This reconciler classifies RUNNING step rows into:
 * - **COMPLETE**: result.txt exists and heartbeat is fresh → use cached exit code
 * - **REATTACH**: result.txt missing but heartbeat is fresh → re-run with same fingerprint
 * - **LOST**: result.txt missing AND heartbeat is stale → fail-closed per UAT-REC-002
 *
 * ## UAT-REC-002 (Fail-Closed LOST)
 *
 * > "worker dies during durable process; reconciliation must not assume success"
 *
 * If the worker (JVM) died during a `sh` step:
 * 1. The subprocess may still be running detached (nohup), OR
 * 2. The subprocess may have crashed, OR
 * 3. The result.txt may never have been written
 *
 * In ALL cases, we must NOT assume the step succeeded. LOST means:
 * - Do NOT mark as SUCCEEDED
 * - Do NOT skip the step
 * - Schedule a new attempt with the SAME fingerprint
 *
 * ## Fingerprint Check
 *
 * The fingerprint is computed from OperationInput + stepId + ReplayPolicy + attempt.
 * If the fingerprint matches a prior RUNNING row, we know:
 * - Same script was being executed
 * - Same attempt number
 * - Same inputs
 *
 * This allows safe re-run without re-executing divergent code.
 *
 * @param clock The clock for heartbeat staleness checks.
 * @param controlDirRoot The root directory for all control directories.
 * @param config The durable shell configuration.
 * @param journal The operation journal for querying RUNNING rows during resume.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 * @see <a href="UAT-REC-002">UAT-REC-002 — Fail-Closed LOST</a>
 */
class StepReconcilerL1(
    private val clock: Clock,
    private val controlDirRoot: Path,
    private val config: DurableShConfig = DurableShConfig.fromSystemProperties(),
    private val journal: OperationJournal? = null,
) {
    /**
     * Classification of a RUNNING step row during reconciliation.
     */
    sealed class Classification {
        /**
         * Step completed; result.txt exists and heartbeat is fresh.
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
         * Step outcome is unknown. result.txt missing AND heartbeat is stale.
         * Fail-closed per UAT-REC-002: must not assume success.
         *
         * The caller should mark this as LOST and schedule a new attempt.
         */
        data object Lost : Classification()
    }

    /**
     * Classifies a RUNNING step's control directory.
     *
     * Checks:
     * 1. Is result.txt present? → COMPLETE
     * 2. Is heartbeat fresh (log file touched within checkInterval + minimumDelta)? → REATTACH
     * 3. Heartbeat is stale → LOST
     *
     * ## Fail-Closed Invariant
     *
     * If we cannot determine the outcome with certainty, we classify as LOST.
     * This is the UAT-REC-002 contract: "must not assume success".
     *
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
     * @param controlDir The control directory for this step.
     * @return The classification of this step's current state.
     */
    fun classifyControlDir(controlDir: Path): Classification {
        val resultFile = controlDir.resolve("result.txt")
        val logFile = controlDir.resolve("jenkins-log.txt")

        // Check 1: Is result.txt present?
        if (Files.exists(resultFile)) {
            return try {
                val exitCode = Files.readString(resultFile).trim().toInt()
                Classification.Complete(exitCode)
            } catch (e: Exception) {
                // Malformed result.txt → treat as LOST
                Classification.Lost
            }
        }

        // Check 2: Is heartbeat fresh?
        if (Files.exists(logFile)) {
            val logLastModified = Files.getLastModifiedTime(logFile).toMillis()
            val now = clock.now().toEpochMilli()
            val heartbeatThreshold = config.heartbeatCheckInterval * 1000 + config.heartbeatMinimumDelta * 1000

            val isFresh = (now - logLastModified) <= heartbeatThreshold

            if (isFresh) {
                // Heartbeat is fresh but no result.txt → may still be running
                return Classification.Reattach(controlDir)
            }
        }

        // Check 3: Heartbeat is stale → LOST (fail-closed per UAT-REC-002)
        return Classification.Lost
    }

    /**
     * Maps a RUNNING row's OperationStatus to a classification.
     *
     * This is used by the walk loop to determine what action to take
     * when encountering a RUNNING row during resume.
     *
     * @param status The current status (must be RUNNING for this reconciler).
     * @param opId The operation ID.
     * @return The classification based on the control directory state.
     */
    fun classifyRunning(status: OperationStatus, opId: String): Classification {
        require(status == OperationStatus.RUNNING) {
            "StepReconcilerL1 only handles RUNNING status, got $status"
        }
        return classify(opId)
    }

    /**
     * Reconciles RUNNING durable shell steps for a given runId.
     *
     * Queries the journal for RUNNING rows with stepType "sh" (shell steps),
     * classifies each via [classifyControlDir], and returns a map keyed by opId.
     *
     * This is called during resume after [ctx.branchReconciler.reconcileRunningOperations]
     * to handle the case where a JVM worker died during a `sh` step.
     *
     * ## Resume Flow
     *
     * 1. `walkPipelineSpecDurable` calls `ctx.stepReconcilerL1.reconcile(runId)`
     * 2. For each RUNNING "sh" opId, classify via result.txt + heartbeat
     * 3. Thread the resulting map into `executeDurableStepImpl`
     * 4. Shell branch checks the map:
     *    - **Complete(exitCode)** → skip execution, return completed result
     *    - **Reattach(controlDir)** → poll/await existing control-dir result
     *    - **Lost** → journal.append LOST + emit StepFailed (fail-closed)
     *
     * @param runId The run identifier to reconcile.
     * @return Map of opId → Classification for all RUNNING shell steps.
     */
    suspend fun reconcile(runId: String): Map<String, Classification> {
        if (journal == null) return emptyMap()

        val result = mutableMapOf<String, Classification>()

        try {
            val allOps = journal.listForRun(runId)

            for (op in allOps) {
                // Filter to step-level RUNNING operations only
                // Step-level opIds don't contain "-b" (branch suffix)
                if (op.id.contains("-b")) continue // skip branch-level ops
                if (op.status != OperationStatus.RUNNING) continue

                // Check if this is a shell step
                // Shell step opIds follow pattern: {runId}-s{stageIndex}-{stepIndex}
                // The stepId in OperationInput is "sh" for shell steps
                val isShellStep = op.id.contains("-s") // All durable step opIds contain -s{stageIndex}

                if (isShellStep) {
                    val classification = classify(op.id)
                    result[op.id] = classification
                }
            }
        } catch (_: Exception) {
            // If journal query fails, return empty map (will trigger fresh execution)
        }

        return result
    }

    /**
     * Determines if a LOST step should be re-run.
     *
     * Per UAT-REC-002, a LOST step should always be re-run (not skipped).
     * The fingerprint remains the same, so the journal will accept the new attempt.
     *
     * @param status The status to check.
     * @return true if the status is LOST (should re-run).
     */
    fun shouldRerun(status: OperationStatus): Boolean {
        return status == OperationStatus.LOST
    }
}
