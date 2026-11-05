package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionProjectionDescriptor
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicyShape
import dev.rubentxu.pipeline.v2.domain.step.BlockShellScopeDescriptor
import java.nio.file.Path

/**
 * Adapter from canonical coordinator types to the
 * Parallel descriptor vocabulary.
 * ## Why this exists
 * Created `BodyInterpreter` with its own descriptor family
 * ([BodyExecutionProjectionDescriptor], [BlockShellScopeDescriptor]) because
 * `pipeline-domain` cannot depend on the canonical coordinator's types.
 * That worked for tests and for the seam itself, but it left the
 * canonical coordinator untouched: it still inlined the dispatch loop,
 * still held its own `BlockShellScope` / `BodyExecutionProjection`
 * types, and still branched on `is BodyShellScope.Directory` etc.
 * This is the **adapter** that lets the canonical
 * coordinator publish its types and consume the interpreter. The inline
 * loops have since moved behind the engines (`BodyExecutionEngine`,
 * `ParallelStageEngine`, `RetryEngine`), so the coordinator no longer
 * branches on any of them.
 * ## What this is NOT
 *  - NOT an effect. The adapter is a pure transformation between two
 *    type families; the canonical coordinator remains the effectful
 *    runner.
 *  - NOT a behavioural change. The mapping is intentionally a structural
 *    1:1 translation: every canonical case has exactly one descriptor
 *    case, and every descriptor case is reachable from exactly one
 *    canonical case. Golden parity tests pin this contract.
 *  - NOT a Step-name branch. The adapter never reads a StepKey; it only
 *    transforms values.
 * ## Migration note
 * The canonical types (`BlockShellScope`, `BodyExecutionProjection`) are NOT
 * private inside `DurableRunCoordinator`: they live in
 * `StructuralDecisions.kt` (`:pipeline-application`), and the body
 * loop that used to be inlined in the coordinator now belongs to
 * `BodyExecutionEngine`. The family this adapter maps is the domain's own
 * published `CanonicalBlockShellScope` / `CanonicalBodyExecutionProjection`,
 * whose descriptor twins are what `BodyInterpreter` consumes.
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
        is CanonicalBlockShellScope.TimestampsScope -> BlockShellScopeDescriptor.Timestamps
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
 * Stand-in mirror of the canonical coordinator's
 * `BlockShellScope` and `BodyExecutionProjection` types.
 * ## Why a mirror, not a re-export
 * The canonical types are `internal sealed interface` inside
 * `StructuralDecisions.kt` (`:pipeline-application`); they cannot
 * be re-exported from `:pipeline-domain` without publishing them. The mirror
 * lets the adapter + golden parity tests
 * reason about the canonical shape WITHOUT forcing the publication in this
 * WU. Once the canonical types are published, the mirror collapses
 * and the adapter consumes them directly.
 * The mirror is intentionally minimal: it names the variants, carries the
 * payload, and is `Any`-typed in the adapter's signature so a future
 * unification of the two type families is a search-and-replace away.
 * Variants mirror the canonical types:
 *  - `BlockShellScope`: None / Directory / TimestampsScope / EnvScope /
 *    Timeout / Retry / WaitUntilScope.
 *  - `BodyExecutionProjection`: Scope / CredentialLease / InvalidInput /
 *    Unimplemented.
 * `borrow { bytes -> ... }` on `EnvScope.parentEnv` reflects the canonical
 * `SecretHandle.borrow { ... }` shape; the mirror stores `Map<String, String>`
 * so tests can construct fixtures without going through the secret lifecycle.
*/
sealed interface CanonicalBlockShellScope {
    /**
     * No scope at all: the body runs in the caller's own context.
     *
     * Nothing is derived around the children — no working directory, no
     * environment overlay, no deadline of its own. The adapter maps it onto
     * [BlockShellScopeDescriptor.None].
     */
    data object None : CanonicalBlockShellScope

    /**
     * The body runs inside a derived working directory.
     *
     * [target] is the directory the body starts in, and [previous] is the one
     * that was in effect before the scope was applied, so the runner knows
     * what to go back to.
     */
    data class Directory(val target: Path, val previous: Path) : CanonicalBlockShellScope

    /**
     * The body runs with the timestamps decorator wrapped around every child
     * event, so each child's timing is attributable to the body's own scope.
     */
    data object TimestampsScope : CanonicalBlockShellScope

    /**
     * The body runs with environment values projected around its children.
     *
     * [overrides] names the variables the scope sets; [parentEnv] holds the
     * inherited ones, whose secret material stays behind
     * [CanonicalSecretHandle.borrow] and is never stored as text in this
     * mirror.
     */
    data class EnvScope(
        val overrides: List<String>,
        val parentEnv: Map<String, CanonicalSecretHandle>,
    ) : CanonicalBlockShellScope

    /**
     * The body runs under a deadline of its own.
     *
     * [budgetMs] is the wall-clock budget for the body as a whole, projected
     * down to the children as the per-invocation watchdog.
     */
    data class Timeout(val budgetMs: Long) : CanonicalBlockShellScope

    /**
     * The body is re-dispatched, up to [maxAttempts] times, each attempt with
     * its own deterministic identity.
     */
    data class Retry(val maxAttempts: Int) : CanonicalBlockShellScope

    /**
     * The body is re-dispatched until its condition succeeds or its backoff
     * grows past the cap.
     *
     * [initialRecurrencePeriod] is the delay before the first recurrence, and
     * [maxBackoffMs] is the ceiling the growing delay is held under — note
     * that `adaptScope` drops it: the descriptor twin carries only the
     * initial period.
     */
    data class WaitUntilScope(
        val initialRecurrencePeriod: Long,
        val maxBackoffMs: Long = 60_000L,
    ) : CanonicalBlockShellScope
}

/**
 * What the body machinery is told to do with one body invocation.
 *
 * The projection is the value the coordinator hands to the interpreter. It
 * names the scope the body runs under, the credentials it may use, or the
 * reason the body cannot run at all. It holds no effect of its own: the
 * runner applies it.
 */
sealed interface CanonicalBodyExecutionProjection {
    /**
     * The generic body machinery runs this body with [scope] projected around
     * it.
     */
    data class Scope(val scope: CanonicalBlockShellScope) : CanonicalBodyExecutionProjection

    /**
     * The body runs under a bound credential lease.
     *
     * The bindings are acquired before the environment is overlaid, and they
     * are closed when the body ends, whether it ended well or not.
     */
    data class CredentialLease(val bindings: List<CredentialBindingSpec>) :
        CanonicalBodyExecutionProjection

    /**
     * The decoded input was invalid: a typed rejection.
     *
     * [detail] says what was wrong. The children are not iterated on this
     * projection, and it is not treated as an unimplemented shape either: the
     * two mean different things to the caller.
     */
    data class InvalidInput(val detail: String) : CanonicalBodyExecutionProjection

    /**
     * The declared shape has no interpretation in this interpreter.
     *
     * The case exists so that a shape admitted without an implementation fails
     * closed instead of running the body as something else.
     */
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
