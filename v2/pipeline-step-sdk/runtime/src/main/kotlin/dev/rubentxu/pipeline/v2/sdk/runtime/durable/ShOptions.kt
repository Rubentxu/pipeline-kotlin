package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.nio.file.Path

/**
 * Options for durable shell step execution.
 *
 * Collapses the L2-essential parameters for shell execution into a single record,
 * reducing the parameter explosion in [executeDurableStepImpl] from 8 positional
 * arguments to 6 positional + 1 ShOptions (per design D8).
 *
 * @property workspaceRoot Root directory for the stage workspace.
 * @property captureStdout If true, capture stdout to output.txt via tee wrapper.
 * @property timeoutMs Timeout in milliseconds, or null for no timeout.
 * @property env Environment variables to inject via pb.environment().putAll.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 * @see <a href="ADR-0047">ADR-0047 — FAILED_TIMEOUT Terminal State</a>
 */
data class ShOptions(
    val workspaceRoot: Path,
    val captureStdout: Boolean,
    val timeoutMs: Long?,
    val env: Map<String, String>,
) {
    companion object {
        /**
         * Empty options for tests that don't need workspace/env.
         * Uses /tmp as workspace root, no capture, no timeout, empty env.
         */
        val EMPTY: ShOptions = ShOptions(
            workspaceRoot = java.nio.file.Files.createTempDirectory("shoptions-empty"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        )
    }
}

/**
 * Policy for output.txt retention after capture.
 */
enum class CaptureRetainPolicy {
    /**
     * After reading output.txt, delete it immediately.
     * This is the default policy for single-flight capture.
     */
    READ_THEN_DELETE,

    /**
     * Retain output.txt after reading (for forensics/debugging).
     */
    RETAIN,
}
