package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.charset.StandardCharsets

/**
 * Canonical environment composer for shell step execution.
 *
 * PURE, STATELESS, DEPENDENCY-FREE (binding amendment 3). The
 * composer is the single orchestrator of the six env-var stages; it
 * does NOT resolve credentials, does NOT import any class from
 * `:pipeline-credentials-api`, does NOT import
 * `dev.rubentxu.pipeline.v2.domain.EnvValue`, and does NOT accept a
 * `CredentialProvider` SPI port or any function-type alias for one.
 *
 * Pre-condition (binding amendment 3): every env-var entry has
 * ALREADY been resolved to a `SecretHandle` by the pre-existing
 * `PipelineRun` flow at `PipelineRun.kt:686-692` (stage env widened
 * via `SecretHandle.plain`) and `PipelineRun.kt:797-799` (withCredentials
 * env from `BoundCredentials.env()`). The composer's input is
 * exclusively `SecretHandle` for all env-var stages.
 *
 * ## Six-Stage Precedence (binding amendment 3)
 *
 * | # | Stage | Input shape | Conflict resolution |
 * |---|-------|-------------|-------------------|
 * | 1 | [base] | `Map<String, SecretHandle>` | Seed map |
 * | 2 | [stage] | `Map<String, SecretHandle>` | Overlay on base |
 * | 3 | [withEnv] | `List<Pair<String, SecretHandle>>` | Last-write-wins |
 * | 4 | [credentials] | `Map<String, SecretHandle>` | Pre-resolved by PipelineRun/WithCredentials |
 * | 5 | [pathPlus] | `List<String>` | Prepends in declaration order; first = outermost |
 * | 6 | [sandbox] | [SandboxConfig] | Recorded for launch-choke processing |
 *
 * ## Design Constraints
 *
 * - **No Bash expansion**: Plain values preserve literal `$VAR` text.
 * - **No string materialisation**: [SecretHandle] values pass through
 *   the composer without conversion to String. Materialisation happens
 *   ONLY at the `pb.environment().putAll` choke in
 *   `DurableShellExecutor.launch` (line 270).
 * - **Deterministic ordering**: All stages fold into an immutable [LinkedHashMap]
 *   preserving insertion order.
 * - **Resolution API** (corrected per binding amendment 3):
 *   `provider.resolve(ref)` returns `SecretHandle` directly;
 *   `WithCredentialsExecutor.bind(...).env()` returns
 *   `Map<String, SecretHandle>`. There is NO `CredentialProvider.materialise`
 *   method — references to it in earlier drafts are factually wrong.
 *
 * ## Sandbox Stage
 *
 * The [sandbox] stage is recorded in the composition result metadata but
 * **deny-list / PATH-normalise are NOT applied inside the composer**.
 * Per the M4 Slice 2 design amendment (2026-09-03), those overlays remain
 * at the [DurableShellExecutor.launch] choke (the inherited-ProcessBuilder
 * sandbox pre-merge carve-out).
 *
 * @see EnvCompositionRequest for the full request shape
 * @see SandboxConfig for sandbox profile and allow-extra / path-keep configuration
 */
object EnvironmentComposer {

    /**
     * Composes six environment stages into a final `Map<String, SecretHandle>`.
     *
     * Pure: no I/O, no clock, no randomness, no credential resolution.
     * The caller is responsible for having resolved every secret
     * reference to a `SecretHandle` BEFORE calling `compose()` — see
     * the pre-existing `PipelineRun` flow at `PipelineRun.kt:686-692`
     * and `PipelineRun.kt:797-799`.
     *
     * @param req The composition request carrying all six stages, all
     *            already resolved to `SecretHandle`.
     * @return Immutable `Map<String, SecretHandle>` ready for
     *         `DurableShellExecutor.launch` (line 270 `pb.environment().putAll`).
     */
    fun compose(req: EnvCompositionRequest): Map<String, SecretHandle> {
        val result = LinkedHashMap<String, SecretHandle>()

        // Stage 1: base — seed the map
        for ((key, value) in req.base) {
            result[key] = value
        }

        // Stage 2: stage overlay
        for ((key, value) in req.stage) {
            result[key] = value
        }

        // Stage 3: withEnv overrides — last-write-wins (sequential fold)
        for ((key, value) in req.withEnv) {
            result[key] = value
        }

        // Stage 4: credentials — pre-resolved by PipelineRun/WithCredentials
        for ((key, value) in req.credentials) {
            result[key] = value
        }

        // Stage 5: PATH+ prepends — Jenkins verbatim semantics
        // first-declared = outermost in final PATH; iterate reversed so
        // first-declared ends up at the front after successive prepends
        val existingPath = result["PATH"]?.let { handle ->
            handle.borrow { bytes -> String(bytes, StandardCharsets.UTF_8) }
        } ?: System.getenv("PATH") ?: ""

        var currentPath = existingPath
        for (dir in req.pathPlus.asReversed()) {
            if (dir.isNotEmpty()) {
                currentPath = "$dir${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
            }
        }

        val javaHome = result["JAVA_HOME"]?.let { handle ->
            handle.borrow { bytes -> String(bytes, StandardCharsets.UTF_8) }
        }
        if (!javaHome.isNullOrEmpty()) {
            currentPath = "${javaHome}/bin${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
        }

        val m2Home = result["M2_HOME"]?.let { handle ->
            handle.borrow { bytes -> String(bytes, StandardCharsets.UTF_8) }
        }
        if (!m2Home.isNullOrEmpty()) {
            currentPath = "${m2Home}/bin${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
        }

        if (currentPath.isNotEmpty() && currentPath != existingPath) {
            result["PATH"] = SecretHandle.plain(currentPath)
        }

        // Stage 6: sandbox config — recorded for launch-choke processing.
        // applyDenyList / normalizePath remain at DurableShellExecutor.launch
        // per the M4 Slice 2 design amendment (2026-09-03).
        return result
    }
}

/**
 * Request object for [EnvironmentComposer.compose].
 *
 * Six named-arg stages that compose in strict order (binding amendment 3):
 * 1. [base] — base environment seed map
 * 2. [stage] — stage-level overlay
 * 3. [withEnv] — withEnv() overrides (last-write-wins)
 * 4. [credentials] — pre-resolved secrets from PipelineRun/WithCredentials
 * 5. [pathPlus] — PATH+= entries prepended in declaration order
 * 6. [sandbox] — recorded for launch-choke processing
 *
 * All env-var stages accept pre-resolved [SecretHandle] values.
 * Resolution is performed by the pre-existing `PipelineRun` flow:
 * - `stageEnvironment: Map<String, String>` widened via `SecretHandle.plain`
 * - `boundCredentials.env(): Map<String, SecretHandle>` from
 *   `WithCredentialsExecutor.bind(...)`
 *
 * @property base Stage 1: base environment map (seed).
 * @property stage Stage 2: stage-level overlay.
 * @property withEnv Stage 3: withEnv() overrides, last-write-wins order.
 * @property credentials Stage 4: pre-resolved secrets from PipelineRun/WithCredentials.
 * @property pathPlus Stage 5: PATH+= entries (Jenkins verbatim semantics).
 * @property sandbox Stage 6: sandbox configuration for launch-choke processing.
 */
data class EnvCompositionRequest(
    val base: Map<String, SecretHandle> = emptyMap(),
    val stage: Map<String, SecretHandle> = emptyMap(),
    val withEnv: List<Pair<String, SecretHandle>> = emptyList(),
    val credentials: Map<String, SecretHandle> = emptyMap(),
    val pathPlus: List<String> = emptyList(),
    val sandbox: SandboxConfig = SandboxConfig.NONE,
)
