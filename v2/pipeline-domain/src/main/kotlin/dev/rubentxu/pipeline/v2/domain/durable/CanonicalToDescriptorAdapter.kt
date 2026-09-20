package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionProjectionDescriptor
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicyShape
import dev.rubentxu.pipeline.v2.domain.step.BlockShellScopeDescriptor
import java.nio.file.Path

/**
 * WU-LPR-024 (slice 1/3) — Adapter from canonical coordinator types to the
 * parallel descriptor vocabulary introduced by WU-LPR-021..023.
 *
 * ## Why this exists
 *
 * WU-LPR-021 created `BodyInterpreter` with its own descriptor family
 * ([BodyExecutionProjectionDescriptor], [BlockShellScopeDescriptor]) because
 * `pipeline-domain` cannot depend on the canonical coordinator's private
 * types. That worked for tests and for the seam itself, but it left the
 * canonical coordinator untouched: it still inlines the dispatch loop,
 * still holds its private `BlockShellScope` / `BodyExecutionProjection`
 * types, and still branches on `is BodyShellScope.Directory` etc.
 *
 * WU-LPR-024 slice 1/3 is the **adapter** that lets the canonical
 * coordinator publish its types and consume the interpreter. Once this
 * adapter is in place, slices 2/3 and 3/3 migrate the inline loops
 * (`dispatchBody`, `runParallelStage`, retry loop) to consume the
 * interpreter + durable control engine policy + branch invoker without
 * changing the durable event/journal byte stream.
 *
 * ## What this is NOT
 *
 *  - NOT an effect. The adapter is a pure transformation between two
 *    type families; the canonical coordinator remains the effectful
 *    runner.
 *  - NOT a behavioural change. The mapping is intentionally a structural
 *    1:1 translation: every canonical case has exactly one descriptor
 *    case, and every descriptor case is reachable from exactly one
 *    canonical case. Golden parity tests pin this contract.
 *  - NOT a Step-name branch. The adapter never reads a StepKey; it only
 *    transforms values.
 *
 * ## Migration note
 *
 * The canonical types (`BlockShellScope`, `BodyExecutionProjection`) are
 * currently `private` inside `CanonicalDurableRunCoordinator`. To make this
 * adapter usable by the canonical code, those types must be published
 * (moved to `:pipeline-domain` or made public inside `:pipeline-application`).
 * Slice 1/3 prepares the adapter; slice 2/3 publishes the types and
 * introduces the canonical coordinator's `interpret(...)` call site;
 * slice 3/3 migrates the inline loops behind the engines.
 */
object CanonicalToDescriptorAdapter {

    /**
     * Maps a canonical `BlockShellScope` to its descriptor twin.
     * Pure, total, no effects.
     */
    fun adaptScope(canonical: Any): BlockShellScopeDescriptor = when (canonical) {
        is CanonicalBlockShellScope.None -> BlockShellScopeDescriptor.None
        is CanonicalBlockShellScope.Directory -> BlockShellScopeDescriptor.Directory(
            target = canonical.target,
            previous = canonical.previous,
        )
        is CanonicalBlockShellScope.TimestampsScope -> BlockShellScopeDescriptor.Timestamps(
            runId = canonical.runId,
        )
        is CanonicalBlockShellScope.EnvScope -> BlockShellScopeDescriptor.Env(
            overrides = canonical.overrides,
            parentEnv = canonical.parentEnv.mapValues { it.value.borrow { bytes -> String(bytes, Charsets.UTF_8) } },
        )
        is CanonicalBlockShellScope.Timeout -> BlockShellScopeDescriptor.Timeout(
            budgetMs = canonical.budgetMs,
        )
        is CanonicalBlockShellScope.Retry -> BlockShellScopeDescriptor.Retry(
            maxAttempts = canonical.maxAttempts,
        )
        is CanonicalBlockShellScope.WaitUntilScope -> BlockShellScopeDescriptor.WaitUntil(
            initialRecurrencePeriodMs = canonical.initialRecurrencePeriod,
            quiet = canonical.quiet,
        )
        else -> error(
            "CanonicalToDescriptorAdapter.adaptScope: unknown canonical scope " +
                "${canonical::class.simpleName}; add a case here AND keep descriptor coverage total.",
        )
    }

    /**
     * Maps a canonical `BodyExecutionProjection` to its descriptor twin.
     * Pure, total, no effects.
     */
    fun adaptProjection(canonical: Any): BodyExecutionProjectionDescriptor = when (canonical) {
        is CanonicalBodyExecutionProjection.Scope ->
            BodyExecutionProjectionDescriptor.Scope(adaptScope(canonical.scope))
        is CanonicalBodyExecutionProjection.CredentialLease ->
            BodyExecutionProjectionDescriptor.CredentialLease(
                bindings = canonical.bindings.map { adaptBinding(it) },
            )
        is CanonicalBodyExecutionProjection.InvalidInput ->
            BodyExecutionProjectionDescriptor.InvalidInput(detail = canonical.detail)
        is CanonicalBodyExecutionProjection.Unimplemented ->
            BodyExecutionProjectionDescriptor.Unimplemented(shape = adaptShape(canonical.shape))
        else -> error(
            "CanonicalToDescriptorAdapter.adaptProjection: unknown canonical projection " +
                "${canonical::class.simpleName}; add a case here AND keep descriptor coverage total.",
        )
    }

    private fun adaptBinding(canonical: Any): CredentialBindingSpec = when (canonical) {
        is CredentialBindingSpec -> canonical
        else -> error(
            "CanonicalToDescriptorAdapter.adaptBinding: expected CredentialBindingSpec, " +
                "got ${canonical::class.simpleName}",
        )
    }

    private fun adaptShape(canonical: Any): BodyExecutionPolicyShape =
        when (canonical) {
            is BodyExecutionPolicyShape -> canonical
            else -> error(
                "CanonicalToDescriptorAdapter.adaptShape: expected BodyExecutionPolicyShape, " +
                    "got ${canonical::class.simpleName}",
            )
        }
}

/**
 * WU-LPR-024 (slice 1/3) — Stand-in mirror of the canonical coordinator's
 * private `BlockShellScope` and `BodyExecutionProjection` types.
 *
 * ## Why a mirror, not a re-export
 *
 * The canonical types are `private sealed interface` inside
 * `CanonicalDurableRunCoordinator.kt`; they cannot be re-exported without
 * publishing them. The mirror lets the adapter + golden parity tests
 * reason about the canonical shape WITHOUT forcing the publication in this
 * WU. Once slice 2/3 publishes the canonical types, the mirror collapses
 * and the adapter consumes them directly.
 *
 * The mirror is intentionally minimal: it names the variants, carries the
 * payload, and is `Any`-typed in the adapter's signature so a future
 * unification of the two type families is a search-and-replace away.
 *
 * Variants mirror the canonical private types:
 *
 *  - `BlockShellScope`: None / Directory / TimestampsScope / EnvScope /
 *    Timeout / Retry / WaitUntilScope.
 *  - `BodyExecutionProjection`: Scope / CredentialLease / InvalidInput /
 *    Unimplemented.
 *
 * `borrow { bytes -> ... }` on `EnvScope.parentEnv` reflects the canonical
 * `SecretHandle.borrow { ... }` shape; the mirror stores `Map<String, String>`
 * so tests can construct fixtures without going through the secret lifecycle.
 */
sealed interface CanonicalBlockShellScope {
    data object None : CanonicalBlockShellScope
    data class Directory(val target: Path, val previous: Path) : CanonicalBlockShellScope
    data class TimestampsScope(val runId: String) : CanonicalBlockShellScope
    data class EnvScope(
        val overrides: List<String>,
        val parentEnv: Map<String, CanonicalSecretHandle>,
    ) : CanonicalBlockShellScope
    data class Timeout(val budgetMs: Long) : CanonicalBlockShellScope
    data class Retry(val maxAttempts: Int) : CanonicalBlockShellScope
    data class WaitUntilScope(
        val initialRecurrencePeriod: Long,
        val quiet: Boolean,
        val maxBackoffMs: Long = 60_000L,
    ) : CanonicalBlockShellScope
}

sealed interface CanonicalBodyExecutionProjection {
    data class Scope(val scope: CanonicalBlockShellScope) : CanonicalBodyExecutionProjection
    data class CredentialLease(val bindings: List<CredentialBindingSpec>) :
        CanonicalBodyExecutionProjection
    data class InvalidInput(val detail: String) : CanonicalBodyExecutionProjection
    data class Unimplemented(val shape: BodyExecutionPolicyShape) :
        CanonicalBodyExecutionProjection
}

/**
 * Stand-in mirror of `dev.rubentxu.pipeline.v2.domain.SecretHandle`. Carries
 * a `borrow { bytes -> T }` shape so the adapter can extract string values
 * without dragging the secret lifecycle into tests.
 */
interface CanonicalSecretHandle {
    fun <T> borrow(block: (ByteArray) -> T): T
}

/** Test-only implementation: stores plaintext bytes. NOT for production. */
class CanonicalPlainSecretHandle(private val bytes: ByteArray) : CanonicalSecretHandle {
    override fun <T> borrow(block: (ByteArray) -> T): T = block(bytes)
    override fun equals(other: Any?): Boolean = other is CanonicalPlainSecretHandle && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}
