package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressGate
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import java.nio.file.Path

/**
 * Options for durable shell step execution.
 *
 * Collapses the L2-essential parameters for shell execution into a single record,
 * reducing the parameter explosion in [executeDurableStepImpl] from 8 positional
 * arguments to 6 positional + 1 ShOptions (per design D8).
 *
 * ## Typed Env Channel (ML-R4)
 *
 * [env] is typed as `Map<String, SecretHandle>` to provide a typed channel
 * for secret values. Secrets never become `String` until the single coercion
 * point at [DurableShellExecutor.launch] where `pb.environment().putAll(env)`
 * coerces via `mapValues { it.value.materialize() }`.
 *
 * Back-compat factory [from] converts legacy `Map<String, String>` callers
 * to the new typed form using [SecretHandle.plain].
 *
 * @property workspaceRoot Root directory for the stage workspace.
 * @property workingDirectory Optional directory for the current nested execution context.
 * @property captureStdout If true, capture stdout to output.txt via tee wrapper.
 * @property timeoutMs Timeout in milliseconds, or null for no timeout.
 * @property env Environment variables to inject via pb.environment().putAll.
 *   Typed as Map<String, SecretHandle> for ML-R4 secret redaction.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 * @see <a href="ADR-0047">ADR-0047 — FAILED_TIMEOUT Terminal State</a>
 */
data class ShOptions(
    val workspaceRoot: Path,
    val captureStdout: Boolean,
    val timeoutMs: Long?,
    val env: Map<String, SecretHandle>,
    val sandbox: SandboxConfig = SandboxConfig.NONE,
    val workingDirectory: Path? = null,
    /**
     * Who owns [workspaceRoot] (RP034-I / ADR-0102).
     *
     * Transport only: the runtime reads it to build a typed `WorkspaceLease`,
     * and nothing infers ownership from the filesystem. `null` keeps the
     * historical behaviour of a PipelineK-managed workspace, which is the
     * fail-closed choice for destruction.
     *
     * This field exists because ownership cannot be recovered downstream. The
     * CLI resolves `WorkspaceLease.Attached` for the caller's own directory,
     * but without carrying the ownership across this transport the runtime
     * re-derived `Managed` for every run — which left `deleteDir()` free to
     * erase the user's project under the local-first default.
     */
    val workspaceOwnership: WorkspaceOwnership? = null,
    /**
     * Who this execution is allowed to reach on the network (LFC-2E3 / WU-093).
     *
     * [DenyAll] by default in the data class itself, so a constructor that forgets
     * to mention it is fail-closed by construction rather than by a condition
     * somebody has to remember to write. Only `--allow-network` produces [AllowAll].
     *
     * It rides here for the same reason `workspaceOwnership` does: it is a
     * per-execution decision set at the CLI boundary that cannot be
     * reconstructed downstream, so it has to cross the transport. The runtime
     * turns it into the generic `NETWORK_EGRESS_CAPABILITY`, and a Step that
     * needs egress declares that capability — which is how "no network" becomes
     * an admission rejection instead of a convention.
     *
     * ## Why a gate and not a verdict
     *
     * This used to be `Allowed | Denied`, which reads the same and is not. A
     * verdict answers "may this run use the network?"; a gate answers "may this
     * run open a socket to THIS host?" — and the second question is the one an
     * allowlist has to ask. Keeping the rules out of here is what stops the Step
     * from becoming the place where a permission is actually decided: the gate is
     * handed over as a capability and asked, and `pipeline-application` never
     * learns that `http.request` exists to ask it.
     */
    val networkEgress: NetworkEgressGate = DenyAll,
    /**
     * Pump the transcript through an external ingest agent
     * (`ObsPc2IngestAgent`) instead of in-JVM pumps. ADR-OBS-003 §5
     * recommends "one agent per run, spawned and supervised by
     * PipelineK". R2.3 wires the flag; R2.3 part 2 was to flip the `0/50` survival assertion
     * in `ObsPc2ProducerSurvivalSpikeTest`. That spike was removed by the test RESET in
     * `2aa071aab`, so the inversion it was to perform has no instrument left to run on.
     *
     * ## What the flag does at the call site
     *
     * When `true`, `ShExecution.invokeShell` constructs a
     * [DurableShConfig] with `pumpedViaAgent = true` and passes it
     * to `DurableShellExecutor.executeTerminal`. The executor then:
     *
     *  - creates two FIFOs under `controlDir/fifos/`
     *  - redirects the child's stdout/stderr to those FIFOs
     *  - launches the production `ObsPc2IngestAgent` as a separate
     *    JVM that owns the read ends, redacts in memory, and
     *    commits sanitized bytes to the Output Plane
     *  - the wrapper gains `trap '' PIPE` so the user's process
     *    survives when the agent dies
     *
     * When `false` (the default), the existing in-JVM pump
     * behaviour is preserved.
     */
    val pumpedViaAgent: Boolean = false,
    /**
     * The secret the ingest agent registers in its
     * `SecretPatternRegistry` for in-memory redaction. Required when
     * [pumpedViaAgent] is `true`; ignored otherwise. The runtime
     * passes this value as the last argv to the agent JVM; the agent
     * does not write it to disk.
     *
     * Production code resolves this from the per-step secret registry
     * (see `ShExecution.invokeShell`'s plumbing). Tests can pass a
     * single literal value to exercise the agent path.
     */
    val agentSecret: String? = null,
    // NOTE: a `Map<StepCapability, Any>` was briefly carried here so the
    // plugin seams would not have to reach CanonicalDurableRunCoordinator.
    // That was reverted: it turned this type — a carrier of FACTS and POLICIES
    // for one execution — into a runtime service locator, and it bought only a
    // line count. Plugin seams travel through RuntimeCapabilityContributor
    // instead. See Lfc2HttpOfficiallyPluginBoundaryFitnessTest FIT-8.
) {
    companion object {
        /**
         * One directory per JVM, not per classload and not per call.
         *
         * This used to be `Files.createTempDirectory("shoptions-empty")` evaluated
         * at class-initialisation time. That leaked exactly one directory into
         * `java.io.tmpdir` per classload — one per Gradle test worker, one per
         * application start — and nothing ever removed it. Over a long test run
         * that is thousands of directories and the failure arrives as
         * `ENOSPC: no space left on device` on the *filesystem*, which reads
         * like a disk problem and is not one.
         *
         * The directory still has to exist: `DurableShellExecutor` passes it to
         * `ProcessBuilder.directory(...)`, which rejects a path that is absent.
         * So the fix is not "stop creating it" but "create it once, under one
         * stable name, and reuse it".
         */
        private val sharedWorkspace: Path = java.nio.file.Files.createTempDirectory("shoptions-shared")

        /**
         * Empty options for tests that don't need workspace/env.
         * Uses a single shared /tmp workspace root, no capture, no timeout, empty env.
         */
        val EMPTY: ShOptions = ShOptions(
            workspaceRoot = sharedWorkspace,
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
            sandbox = SandboxConfig.NONE,
        )

        /**
         * Backwards-compatible factory for legacy callers using Map<String, String>.
         *
         * Converts plain String values to [SecretHandle.plain] wrappers,
         * preserving the legacy call pattern while enabling the typed channel.
         *
         * The workspace root is the same shared directory [EMPTY] uses. It used
         * to call `createTempDirectory("shoptions-from")` per invocation, which
         * leaked one directory per call — and this factory sits on the
         * characterisation-test path, so it was the larger of the two leaks.
         * A caller that needs an isolated workspace passes [workspaceRoot]
         * explicitly; the default exists to satisfy `ProcessBuilder.directory`,
         * not to model a real workspace.
         *
         * @param env The legacy Map<String, String> environment.
         * @return A ShOptions with env wrapped as Map<String, SecretHandle>.
         */
        fun from(
            env: Map<String, String>,
            workspaceRoot: Path = sharedWorkspace,
        ): ShOptions {
            return ShOptions(
                workspaceRoot = workspaceRoot,
                captureStdout = false,
                timeoutMs = null,
                env = env.mapValues { SecretHandle.plain(it.value) },
                sandbox = SandboxConfig.NONE,
            )
        }
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
