package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Configuration for durable shell execution
 * Loaded from system properties with sensible defaults. All intervals in seconds.
 * ## Heartbeat Constants
 * | Property | Default | Description |
 * |---|---|---|
 * | `HEARTBEAT_CHECK_INTERVAL` | 300 | How often the wrapper touches jenkins-log.txt (seconds) |
 * | `HEARTBEAT_MINIMUM_DELTA` | 2 | Minimum age of log file to consider it stale (seconds) |
 * | `CLEANUP_RETAIN_ON_FAILURE` | true | If true, retain control dir on failure for debugging |
 * ## Control Directory Layout
 * Each `sh` step gets a control directory `$controlDirRoot/$opId/`:
 * | File | Role |
 * |---|---|
 * | `script.sh` | User script, written verbatim before launch |
 * | `script.sh.copy` | Copy of script.sh ( Text-File-Busy workaround) |
 * | `jenkins-log.txt` | stdout+stderr of script; touched by heartbeat |
 * | `result.txt` | Exit code written atomically: `echo $? > tmp && mv tmp result.txt` |
 * | `result.txt.tmp` | Temp file for atomic result.txt write |
 * | `output.txt` | Captured stdout when returnStdout=true (tee-gated wrapper) |
 * | `timeout.flag` | Written by watchdog before SIGKILL |
 * | `.cookie` | Cookie file created by wrapper; existence means process is alive |
 * ## Linux-Only
 * Durable shell relies on Linux-specific primitives (nohup, setsid, /proc).
 * On non-Linux platforms, [LinuxRequiredException] is thrown.
 * Durable sh Pattern</a>
 * FAILED_TIMEOUT Terminal State</a>
*/
data class DurableShConfig(
    /** Heartbeat interval: how often the wrapper touches jenkins-log.txt (seconds). */
    val heartbeatCheckInterval: Long = HEARTBEAT_CHECK_INTERVAL_DEFAULT,

    /** Minimum delta: minimum age of log file to consider it stale (seconds). */
    val heartbeatMinimumDelta: Long = HEARTBEAT_MINIMUM_DELTA_DEFAULT,

    /** If true, retain control dir on failure for debugging. */
    val cleanupRetainOnFailure: Boolean = CLEANUP_RETAIN_ON_FAILURE_DEFAULT,

    /**
     * If true, capture stdout to output.txt via tee-gated wrapper.
     * Default false (behavior: output.txt is written but never read)
*/
    val returnStdout: Boolean = RETURN_STDOUT_DEFAULT,

    /**
     * Policy for output.txt retention after capture.
     * Default READ_THEN_DELETE (single-flight read).
*/
    val captureRetainPolicy: CaptureRetainPolicy = CAPTURE_RETAIN_POLICY_DEFAULT,

    /**
     * Pump the transcript through an external ingest agent
     * instead of in-JVM pumps.
     * ## What this flag changes
     * When `true`, [dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.launch]
     * creates two FIFOs in `controlRoot/<runId>/fifos/`, redirects the
     * child's stdout/stderr to those FIFOs, and launches the production
     * `dev.rubentxu.pipeline.v2.application.durable.ingest.ObsPc2IngestAgent`
     * as a separate JVM that owns the read ends. The runtime does NOT
     * start its per-channel pump threads in this mode.
     * When `false` (the default), the existing in-JVM pump behaviour is
     * preserved: `Redirect.PIPE` + daemon pump threads inside the
     * Runtime. This is Level A only; the child is killed by
     * `SIGPIPE` if the runtime JVM dies mid-step.
     * ## compatibility cost
     * The wrapper gains `trap '' PIPE` when this flag is `true`. The
     * `SIG_IGN` disposition is inherited across `exec`, so the user's
     * script — and everything it spawns — runs with `SIGPIPE` ignored.
     * A script that pipes internally (`yes | head -n1`) currently kills
     * its producer with `SIGPIPE`; with the trap, the producer receives
     * `EPIPE` and may exit non-zero instead. ratifies
     * this trade and accepts it.
     * ## Default
     * `false`. Switching this on is the wiring step, and that step has not
     * been taken, so the default is a conservative choice rather than a
     * measured one: nothing in this tree observes what the flag does.
*/
    val pumpedViaAgent: Boolean = PUMPED_VIA_AGENT_DEFAULT,

    /**
     * Secret the ingest agent registers in its `SecretPatternRegistry`
     * when [pumpedViaAgent] is `true`. The agent is responsible for
     * redacting this secret from the child's stdout/stderr in memory
     * before committing to the Output Plane.
     * Required when [pumpedViaAgent] is `true`; ignored otherwise. The
     * runtime passes this value as the last argv to the agent JVM; the
     * agent does not write it to disk.
*/
    val agentSecret: String? = null,

    /**
     * The broader run identifier for the step being launched.
     * Required when [pumpedViaAgent] is `true` (the agent uses
     * `OutputPlaneProvider.streamsOf(runId, opId)` to find the run's
     * streams). Optional otherwise; when absent the executor uses
     * `opId` as a stand-in, which is correct for tests that drive a
     * single opId per run.
*/
    val runId: String? = null,
) {
    init {
        require(!(pumpedViaAgent && (agentSecret == null || agentSecret.isEmpty()))) {
            "agentSecret must be non-empty when pumpedViaAgent is true"
        }
        require(!(pumpedViaAgent && runId == null)) {
            "runId must be set when pumpedViaAgent is true"
        }
    }

    companion object {
        const val HEARTBEAT_CHECK_INTERVAL_PROPERTY = "pipeline.durable.heartbeat.check.interval"
        const val HEARTBEAT_MINIMUM_DELTA_PROPERTY = "pipeline.durable.heartbeat.minimum.delta"
        const val CLEANUP_RETAIN_ON_FAILURE_PROPERTY = "pipeline.durable.cleanup.retain.on.failure"
        const val RETURN_STDOUT_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.CAPTURE_STDOUT"
        const val CAPTURE_RETAIN_POLICY_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.CAPTURE_RETAIN_POLICY"
        const val PUMPED_VIA_AGENT_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.PUMPED_VIA_AGENT"
        const val AGENT_SECRET_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.AGENT_SECRET"
        const val RUN_ID_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.RUN_ID"

        /** Default: 300 seconds (5 minutes) */
        const val HEARTBEAT_CHECK_INTERVAL_DEFAULT = 300L

        /** Default: 2 seconds */
        const val HEARTBEAT_MINIMUM_DELTA_DEFAULT = 2L

        /** Default: true */
        const val CLEANUP_RETAIN_ON_FAILURE_DEFAULT = true

        /** Default: false (guard - output.txt written but never read) */
        const val RETURN_STDOUT_DEFAULT = false

        /** Default: READ_THEN_DELETE (single-flight capture) */
        val CAPTURE_RETAIN_POLICY_DEFAULT = CaptureRetainPolicy.READ_THEN_DELETE

        /** Default: false (ships the flag off; spike flips it on for the proof) */
        const val PUMPED_VIA_AGENT_DEFAULT = false

        /**
         * Loads configuration from system properties with defaults.
         * If a system property is not set, uses the default value.
*/
        fun fromSystemProperties(): DurableShConfig {
            return DurableShConfig(
                heartbeatCheckInterval = System.getProperty(
                    HEARTBEAT_CHECK_INTERVAL_PROPERTY,
                    HEARTBEAT_CHECK_INTERVAL_DEFAULT.toString()
                ).toLongOrNull() ?: HEARTBEAT_CHECK_INTERVAL_DEFAULT,

                heartbeatMinimumDelta = System.getProperty(
                    HEARTBEAT_MINIMUM_DELTA_PROPERTY,
                    HEARTBEAT_MINIMUM_DELTA_DEFAULT.toString()
                ).toLongOrNull() ?: HEARTBEAT_MINIMUM_DELTA_DEFAULT,

                cleanupRetainOnFailure = System.getProperty(
                    CLEANUP_RETAIN_ON_FAILURE_PROPERTY,
                    CLEANUP_RETAIN_ON_FAILURE_DEFAULT.toString()
                ).toBooleanStrictOrNull() ?: CLEANUP_RETAIN_ON_FAILURE_DEFAULT,

                returnStdout = System.getProperty(
                    RETURN_STDOUT_PROPERTY,
                    RETURN_STDOUT_DEFAULT.toString()
                ).toBooleanStrictOrNull() ?: RETURN_STDOUT_DEFAULT,

                captureRetainPolicy = System.getProperty(
                    CAPTURE_RETAIN_POLICY_PROPERTY,
                    CAPTURE_RETAIN_POLICY_DEFAULT.name
                )?.let { name ->
                    try {
                        CaptureRetainPolicy.valueOf(name)
                    } catch (_: Exception) {
                        CAPTURE_RETAIN_POLICY_DEFAULT
                    }
                } ?: CAPTURE_RETAIN_POLICY_DEFAULT,

                pumpedViaAgent = System.getProperty(
                    PUMPED_VIA_AGENT_PROPERTY,
                    PUMPED_VIA_AGENT_DEFAULT.toString(),
                ).toBooleanStrictOrNull() ?: PUMPED_VIA_AGENT_DEFAULT,

                agentSecret = System.getProperty(AGENT_SECRET_PROPERTY)?.takeIf { it.isNotEmpty() },

                runId = System.getProperty(RUN_ID_PROPERTY)?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * Exception thrown when durable shell is attempted on a non-Linux platform.
 * Durable shell relies on Linux-specific primitives:
 * - `/proc` filesystem for PID discovery
 * - `setsid` for process group leadership
 * - `nohup` for detachment
 * Local execution is Linux-only for; Windows/containers come in
*/
class LinuxRequiredException(
    message: String = "Durable shell requires Linux. Non-Linux platforms are not supported in ML-R1."
) : RuntimeException(message)
