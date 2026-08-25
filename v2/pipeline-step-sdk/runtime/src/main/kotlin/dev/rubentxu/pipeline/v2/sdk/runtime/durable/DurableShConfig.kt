package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Configuration for durable shell execution (ML-R1 / ADR-0046).
 *
 * Loaded from system properties with sensible defaults. All intervals in seconds.
 *
 * ## Heartbeat Constants
 *
 * | Property | Default | Description |
 * |---|---|---|
 * | `HEARTBEAT_CHECK_INTERVAL` | 300 | How often the wrapper touches jenkins-log.txt (seconds) |
 * | `HEARTBEAT_MINIMUM_DELTA` | 2 | Minimum age of log file to consider it stale (seconds) |
 * | `CLEANUP_RETAIN_ON_FAILURE` | true | If true, retain control dir on failure for debugging |
 *
 * ## Control Directory Layout
 *
 * Each `sh` step gets a control directory `$controlDirRoot/$opId/`:
 *
 * | File | Role |
 * |---|---|
 * | `script.sh` | User script, written verbatim before launch |
 * | `script.sh.copy` | Copy of script.sh (JENKINS-70874 Text-File-Busy workaround) |
 * | `jenkins-log.txt` | stdout+stderr of script; touched by heartbeat |
 * | `result.txt` | Exit code written atomically: `echo $? > tmp && mv tmp result.txt` |
 * | `result.txt.tmp` | Temp file for atomic result.txt write |
 * | `output.txt` | Reserved for future `returnStdout` use; NEVER read in L1 |
 * | `.cookie` | Cookie file created by wrapper; existence means process is alive |
 *
 * ## Linux-Only
 *
 * Durable shell relies on Linux-specific primitives (nohup, setsid, /proc).
 * On non-Linux platforms, [LinuxRequiredException] is thrown.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
data class DurableShConfig(
    /** Heartbeat interval: how often the wrapper touches jenkins-log.txt (seconds). */
    val heartbeatCheckInterval: Long = HEARTBEAT_CHECK_INTERVAL_DEFAULT,

    /** Minimum delta: minimum age of log file to consider it stale (seconds). */
    val heartbeatMinimumDelta: Long = HEARTBEAT_MINIMUM_DELTA_DEFAULT,

    /** If true, retain control dir on failure for debugging. */
    val cleanupRetainOnFailure: Boolean = CLEANUP_RETAIN_ON_FAILURE_DEFAULT,
) {
    companion object {
        const val HEARTBEAT_CHECK_INTERVAL_PROPERTY = "pipeline.durable.heartbeat.check.interval"
        const val HEARTBEAT_MINIMUM_DELTA_PROPERTY = "pipeline.durable.heartbeat.minimum.delta"
        const val CLEANUP_RETAIN_ON_FAILURE_PROPERTY = "pipeline.durable.cleanup.retain.on.failure"

        /** Default: 300 seconds (5 minutes) */
        const val HEARTBEAT_CHECK_INTERVAL_DEFAULT = 300L

        /** Default: 2 seconds */
        const val HEARTBEAT_MINIMUM_DELTA_DEFAULT = 2L

        /** Default: true */
        const val CLEANUP_RETAIN_ON_FAILURE_DEFAULT = true

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
            )
        }
    }
}

/**
 * Exception thrown when durable shell is attempted on a non-Linux platform.
 *
 * Durable shell relies on Linux-specific primitives:
 * - `/proc` filesystem for PID discovery
 * - `setsid` for process group leadership
 * - `nohup` for detachment
 *
 * Local execution is Linux-only for ML-R1; Windows/containers come in M5.
 */
class LinuxRequiredException(
    message: String = "Durable shell requires Linux. Non-Linux platforms are not supported in ML-R1."
) : RuntimeException(message)
