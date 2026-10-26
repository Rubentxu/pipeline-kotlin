package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Configuration for durable shell execution
 * Loaded from system properties with sensible defaults. All intervals in seconds.
 * ## Heartbeat Constants
 * | Property | Default | Description |
 * |---|---|---|
 * | `HEARTBEAT_CHECK_INTERVAL` | 300 | How often the wrapper touches the console transcript (seconds) |
 * | `HEARTBEAT_MINIMUM_DELTA` | 2 | Minimum age of log file to consider it stale (seconds) |
 * ## Control Directory Layout
 * Each `sh` step gets a control directory `$controlDirRoot/$opId/`. The names
 * and paths inside it belong to [ShellProtocolLayout], which is their single
 * authority; they are not listed here, because a second copy of a layout is a
 * copy that can drift.
 * ## The fate of the control directory
 * Whether the control directory survives an execution is not a configuration:
 * it is [ShellResourceCleanup]'s rule. The flag that used to decide it was
 * inert — it could not change the outcome in either position — and it was
 * retired rather than honoured, because a failed execution's directory holds
 * the evidence the reconciler reads to tell an expired execution from a lost
 * one.
 * ## Linux-Only
 * Durable shell relies on Linux-specific primitives (nohup, setsid, /proc).
 * On non-Linux platforms, [LinuxRequiredException] is thrown.
 * Durable sh Pattern</a>
 * FAILED_TIMEOUT Terminal State</a>
*/
data class DurableShConfig(
    /** Heartbeat interval: how often the wrapper touches the console transcript (seconds). */
    val heartbeatCheckInterval: Long = HEARTBEAT_CHECK_INTERVAL_DEFAULT,

    /** Minimum delta: minimum age of log file to consider it stale (seconds). */
    val heartbeatMinimumDelta: Long = HEARTBEAT_MINIMUM_DELTA_DEFAULT,

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
    /**
     * How long a transcript pump may go without reading a byte before the drain declares it stuck.
     *
     * A pump that is writing to the output plane is MOVING even while it writes: it read those
     * bytes already. A pump that is waiting for the child is not moving, and the bytes it waits
     * for may never come. This budget is what tells those two apart, so it is the operator's lever
     * on a machine so loaded that a pump legitimately pauses for longer than this.
     */
    val transcriptDrainIdleBudgetMs: Long = TRANSCRIPT_DRAIN_IDLE_BUDGET_DEFAULT,
    /**
     * The longest the drain of a transcript may wait, whatever happens.
     *
     * A pump that keeps moving is not refused for being slow: it is waited for. This ceiling is the
     * backstop for the case that never ends — a child that dribbles a byte every few seconds and
     * keeps the transcript open forever.
     */
    val transcriptDrainCeilingMs: Long = TRANSCRIPT_DRAIN_CEILING_DEFAULT,
) {
    init {
        require(!(pumpedViaAgent && (agentSecret == null || agentSecret.isEmpty()))) {
            "agentSecret must be non-empty when pumpedViaAgent is true"
        }
        require(!(pumpedViaAgent && runId == null)) {
            "runId must be set when pumpedViaAgent is true"
        }
        require(transcriptDrainIdleBudgetMs > 0) {
            "transcriptDrainIdleBudgetMs must be positive (got $transcriptDrainIdleBudgetMs)"
        }
        require(transcriptDrainCeilingMs >= transcriptDrainIdleBudgetMs) {
            "transcriptDrainCeilingMs ($transcriptDrainCeilingMs) must not be below the idle budget " +
                "($transcriptDrainIdleBudgetMs): a ceiling below the idle budget would refuse every " +
                "drain before it could tell a stuck pump from a slow one"
        }
    }

    companion object {
        const val HEARTBEAT_CHECK_INTERVAL_PROPERTY = "pipeline.durable.heartbeat.check.interval"
        const val HEARTBEAT_MINIMUM_DELTA_PROPERTY = "pipeline.durable.heartbeat.minimum.delta"
        const val RETURN_STDOUT_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.CAPTURE_STDOUT"
        const val CAPTURE_RETAIN_POLICY_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.CAPTURE_RETAIN_POLICY"
        const val PUMPED_VIA_AGENT_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.PUMPED_VIA_AGENT"
        const val AGENT_SECRET_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.AGENT_SECRET"
        const val RUN_ID_PROPERTY = "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor.RUN_ID"
        const val TRANSCRIPT_DRAIN_IDLE_BUDGET_PROPERTY = "pipeline.durable.transcript.drain.idle.budget"
        const val TRANSCRIPT_DRAIN_CEILING_PROPERTY = "pipeline.durable.transcript.drain.ceiling"

        /** Default: 300 seconds (5 minutes) */
        const val HEARTBEAT_CHECK_INTERVAL_DEFAULT = 300L

        /** Default: 2 seconds */
        const val HEARTBEAT_MINIMUM_DELTA_DEFAULT = 2L

        /** Default: false (guard - output.txt written but never read) */
        const val RETURN_STDOUT_DEFAULT = false

        /** Default: READ_THEN_DELETE (single-flight capture) */
        val CAPTURE_RETAIN_POLICY_DEFAULT = CaptureRetainPolicy.READ_THEN_DELETE

        /** Default: false (ships the flag off; spike flips it on for the proof) */
        const val PUMPED_VIA_AGENT_DEFAULT = false

        /** Default: 10 seconds without reading a byte is a stuck pump. */
        const val TRANSCRIPT_DRAIN_IDLE_BUDGET_DEFAULT = 10_000L

        /** Default: 60 seconds is the longest the drain waits, whatever happens. */
        const val TRANSCRIPT_DRAIN_CEILING_DEFAULT = 60_000L

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

                transcriptDrainIdleBudgetMs = System.getProperty(
                    TRANSCRIPT_DRAIN_IDLE_BUDGET_PROPERTY,
                    TRANSCRIPT_DRAIN_IDLE_BUDGET_DEFAULT.toString(),
                ).toLongOrNull() ?: TRANSCRIPT_DRAIN_IDLE_BUDGET_DEFAULT,

                transcriptDrainCeilingMs = System.getProperty(
                    TRANSCRIPT_DRAIN_CEILING_PROPERTY,
                    TRANSCRIPT_DRAIN_CEILING_DEFAULT.toString(),
                ).toLongOrNull() ?: TRANSCRIPT_DRAIN_CEILING_DEFAULT,
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

/**
 * The durable shell is a Linux-only contract, and this is its single check.
 *
 * It lives beside the exception it raises, so the two cannot drift apart, and it is shared by
 * the launch and by the detach step: a host that is not Linux cannot run a durable shell, and
 * the two places that need the child to exist must not disagree about it.
 *
 * @throws LinuxRequiredException When the host is not Linux.
 */
internal fun requireLinuxHost() {
    val osName = System.getProperty("os.name", "").lowercase()
    if (!osName.contains("linux")) {
        throw LinuxRequiredException()
    }
}
