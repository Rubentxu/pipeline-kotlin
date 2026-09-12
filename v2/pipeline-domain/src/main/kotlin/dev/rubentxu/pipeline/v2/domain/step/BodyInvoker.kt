package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import kotlinx.serialization.Serializable

/**
 * Serializable identity of a body owned by the engine, referenced by a block
 * Step handler through [BodyInvoker] (ADR-0081, D2).
 *
 * A [BodyRef] carries ONLY value identity — the canonical deterministic
 * `bodyPath` encoding owned by the durable execution model
 * (`OpId.bodyPath: List<BlockSegment>`). It is stable across process restarts
 * and is the natural correlation key for journal rows, control rows and events.
 *
 * A [BodyRef] MUST NOT capture executable content. Runtime content (a
 * condition lambda, a closure over script state) never serializes and never
 * rides in a ref; it reaches the handler through a separate typed capability
 * bound at dispatch time.
 *
 * Handlers and plugins obtain refs ONLY through [BodyRefs]; unknown encodings
 * fail closed at the engine adapter that resolves the ref.
 */
@JvmInline
@Serializable
value class BodyRef(val encoded: String) {
    init {
        require(encoded.isNotBlank()) { "BodyRef encoded must not be blank" }
    }
}

/**
 * Closed factory surface for [BodyRef]s. The encoding is derived
 * deterministically from the engine-owned `bodyPath` segments, mirroring
 * `BlockSegment`'s canonical format, so the same body lands on the same ref in
 * every process and restart (ADR-0081, D2 / D7).
 */
object BodyRefs {

    /** The step-list body of a block Step at [bodyPath] (e.g. retry/timeout children). */
    fun childBody(bodyPath: List<BlockSegment>): BodyRef =
        BodyRef(encode(bodyPath, kind = "body"))

    /** A parallel branch body with its deterministic branch index (ADR-0081, D6). */
    fun branchBody(parentPath: List<BlockSegment>, branchIndex: Int, key: PluginStepId): BodyRef =
        BodyRef(encode(parentPath + BlockSegment("b$branchIndex:${key.value}"), kind = "branch"))

    /** A named body (e.g. a `parallel` named closure) under [parentPath]. */
    fun namedBody(parentPath: List<BlockSegment>, name: String): BodyRef {
        require(name.isNotBlank()) { "namedBody requires a non-blank name" }
        return BodyRef(encode(parentPath + BlockSegment("n:$name"), kind = "named"))
    }

    private fun encode(path: List<BlockSegment>, kind: String): String =
        (listOf(kind) + path.map { it.encoded }).joinToString(separator = "/")
}

/**
 * Reason a body invocation ended in cancellation (ADR-0081, D3 / D8).
 *
 * Closed ADT: cancellation is an execution mechanism (ADR-0076) and is never
 * reclassified as a step failure.
 */
@Serializable
enum class CancellationReason {
    /** The enclosing deadline (e.g. `core.timeout` scope) expired. */
    Deadline,

    /** A parent scope was cancelled (structured-concurrency propagation). */
    ParentCancelled,

    /** The enclosing run/scope shut down (e.g. process teardown). */
    ScopeShutDown,
}

/**
 * Closed typed outcome of a body invocation through [BodyInvoker]
 * (ADR-0081, D3).
 *
 * - [Completed] reuses the closed [StepOutcome] algebra. A
 *   `Completed(StepOutcome.Failure)` is a CONTAINED typed result that block
 *   Steps fold per their declared policy (e.g. `catchError`); it is NOT a
 *   pipeline abort and NOT an invoker failure.
 * - [Cancelled] distinguishes structured cancellation from a step failure;
 *   the engine adapter maps it before `StepOutcome` classification.
 *
 * Invoker infrastructure defects throw; every expected operational outcome is
 * a typed case here.
 */
@Serializable
sealed interface BodyOutcome {
    data class Completed(val outcome: StepOutcome) : BodyOutcome
    data class Cancelled(val reason: CancellationReason) : BodyOutcome
}

/**
 * Explicit immutable execution context for one body invocation (ADR-0081, D4).
 *
 * Pure data derived by the caller — never ambient state, never an owner of
 * journal, registry, coroutine scope, or process executor (CTX-P law). The
 * engine adapter projects [patch] into the canonical scope structure
 * (`BlockShellScope`-equivalent); the port does not reimplement scope logic.
 */
data class BodyInvocationContext(
    /**
     * Per-attempt identity for retried bodies. When present, the engine appends
     * it to the body path as a deterministic `BlockSegment`, exactly as the
     * retry dispatch loop does today (ADR-0081, D9).
     */
    val attempt: AttemptSegment? = null,

    /** Typed execution-scope patch (`dir` / `withEnv` / `withCredentials` projections). */
    val patch: ExecutionContextPatch = ExecutionContextPatch.None,
) {
    init {
        require(attempt?.index?.let { it >= 1 } ?: true) { "AttemptSegment.index must be >= 1" }
    }
}

/** Identity of one retry attempt of a body (ADR-0081, D9). */
data class AttemptSegment(val index: Int, val key: PluginStepId = PluginStepId("retry-attempt"))

/**
 * Typed execution-scope patch applied around a body invocation.
 *
 * Closed ADT. Scope patches compose by pure derivation: each patch produces a
 * child context; no parent or sibling context is mutated.
 */
@Serializable
sealed interface ExecutionContextPatch {
    data object None : ExecutionContextPatch

    /** Runs the body inside [path] (`dir` projection). */
    data class Directory(val path: String) : ExecutionContextPatch

    /** Runs the body with additional environment values (`withEnv` projection). */
    data class Environment(val values: Map<String, String>) : ExecutionContextPatch

    /** Runs the body with a bound credential lease (`withCredentials` projection). */
    data class CredentialLease(val bindingId: String) : ExecutionContextPatch
}

/**
 * The single typed port by which a block Step handler re-enters the engine to
 * execute a body (ADR-0081, D1; concretizes ADR-0073).
 *
 * Supplied to a handler as a capability value under [BODY_INVOKER_CAPABILITY]
 * with fail-closed admission: a handler that declares the capability never runs
 * when the engine has not bound it. Every invocation re-enters the canonical
 * `Invoke → Registry → capability admission → handler → journal/events` path;
 * no per-block dispatch method may exist.
 *
 * Implementations live in the engine adapter; the port itself depends on
 * nothing but the inner domain algebra.
 */
fun interface BodyInvoker {
    suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome
}

/**
 * Capability key under which the engine supplies the [BodyInvoker] port to a
 * handler that re-enters the engine for a DSL body.
 *
 * Neutral owner, mirroring the other capability keys: the runtime capability
 * bridge and any Step definition (core or external plugin) reference the SAME
 * token without the execution seam depending on any concrete Step. Declared in
 * `StepContract.requiredCapabilities`; admission is fail-closed before the
 * handler runs when it is not available.
 */
val BODY_INVOKER_CAPABILITY: StepCapability = StepCapability("bodyInvoker")
