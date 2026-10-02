package dev.rubentxu.pipeline.v2.application

/**
 * Typed seam a registry-routed `core.lock` handler calls to take and release a
 * named resource (RP6-A / WU-091).
 *
 * Mirrors [ShellOperations] exactly: this interface is the *only* contract the
 * handler holds for coordination. The handler MUST NOT open files, keep maps of
 * held resources, or reason about contention itself.
 *
 * ## Why the hold is not a boolean
 *
 * The three outcomes are not "yes/no". Jenkins gives each a different treatment:
 * contention under `skipIfLocked` is a *successful* Step whose body does not run;
 * an allocation timeout is a *failure*; a cancellation is neither. Collapsing
 * them into `Boolean` would force the handler to re-derive, from a flag, which of
 * the three happened — exactly the "boolean coupled to a nullable" shape the
 * project forbids.
 *
 * ## The wait is an ADT, not a nullable timeout plus a flag
 *
 * [LockIntent] has one case per legitimate shape, so `skipIfLocked = true`
 * together with a timeout is not representable rather than being silently
 * resolved. See [LockIntent] for why that divergence from Jenkins is deliberate.
 */
interface LockCoordinator {

    /**
     * Takes [resource] for [owner] according to [intent], or reports why it could not.
     *
     * The contract an implementation MUST honour:
     * - On [LockAdmission.Acquired], the returned hold is what [release] takes.
     *   A later `acquire` of the same resource is a DIFFERENT hold.
     * - The hold is released by exactly one of: [release], the death of the
     *   owning process, or the resource's own expiry — whichever comes first.
     *   An implementation MUST NOT leave a hold that no [release] can clear.
     * - Re-entrancy is keyed by [owner], NEVER by thread, coroutine, or process.
     *   See [LockOwner].
     */
    suspend fun acquire(owner: LockOwner, resource: String, intent: LockIntent): LockAdmission

    /**
     * Releases a previously granted hold.
     *
     * MUST be idempotent for a hold that is no longer held: releasing twice, or
     * releasing a hold this process never took, MUST NOT affect the current
     * holder. A coordinator that cannot satisfy this is not implementing mutual
     * exclusion, it is implementing a hazard.
     */
    fun release(hold: LockHold)
}

/**
 * The durable EXECUTION LANE a hold belongs to.
 *
 * ## Why not the run
 *
 * Keying re-entrancy by run alone is too coarse, and the failure is invisible
 * until the day it matters: two branches of the SAME `parallel` would look like
 * one owner, so branch B would "re-enter" a resource branch A is holding, and the
 * mutual exclusion the lock exists to provide would be gone — in exactly the
 * situation where it is being relied upon.
 *
 * ## Why not the full OpId
 *
 * The opposite error. `lock("a") { lock("a") { ... } }` has two DIFFERENT OpIds
 * (the inner one carries a body path), so OpId identity would deny a nested
 * acquire and deadlock the run against its own legitimate hold.
 *
 * ## What this is
 *
 * ```text
 * Lane = run + parallel lineage
 * ```
 *
 * A structural value, not a coordinate: it is the ordered list of branch frames
 * the execution is inside, so nested parallel widens it without changing the
 * type. It is NOT a thread, a coroutine, a `stepIndex`, or a lock invocation, and
 * it is not ambient — it is derived from the operation identity that is already
 * journalled, so it survives suspension, replay, resume, a change of thread and a
 * change of coroutine.
 *
 * The law, stated once:
 *
 * ```text
 * same run  + same lane   -> reentrant
 * same run  + other lane  -> contention   (parallel branches)
 * other run               -> contention
 * ```
 */
@JvmInline
value class ExecutionLaneId(val value: String) {
    companion object {
        /**
         * Derives a lane from a run and its [branchLineage] (see
         * `OpId.parallelLineage`). Empty lineage is the linear lane.
         *
         * Pure: no clock, no ambient state, no I/O. Two derivations from the same
         * inputs always produce the same lane, which is what makes it durable.
         */
        fun of(runId: String, branchLineage: List<Int>): ExecutionLaneId =
            ExecutionLaneId(
                buildString {
                    append(runId)
                    branchLineage.forEach { append("-b"); append(it) }
                },
            )
    }
}

/**
 * The owner of a hold: a durable execution lane, never a run, a thread, a
 * coroutine or a Step invocation.
 *
 * Wrapping [ExecutionLaneId] rather than aliasing it makes the law visible at
 * every call site: `acquire(owner, …)` says "owner of a lane", and the type
 * cannot be constructed from a bare run id by accident.
 */
@JvmInline
value class LockOwner(val lane: ExecutionLaneId)

/**
 * A granted hold, and the only thing [LockCoordinator.release] accepts.
 *
 * Carries the owner as well as the resource so that "release a lock this run
 * never acquired" is a type error at the call site, and so a release can never
 * free a hold belonging to a different owner.
 */
data class LockHold(val owner: LockOwner, val resource: String)

/**
 * How long a caller is willing to wait for a resource.
 *
 * One case per legitimate shape. There is deliberately no
 * `timeoutMillis: Long?` beside a `skipIfLocked: Boolean`: that pair can express
 * `skip-if-locked` *and* "wait 30s", which is not a state any caller means.
 *
 * ## Divergence from Jenkins, declared
 *
 * In Jenkins, `skipIfLocked` and `timeoutForAllocateResource` may both be set
 * and `skipIfLocked` silently wins. PipelineK rejects the combination at decode
 * time instead, for the same reason `resolveBodyExecutionPolicy` rejects
 * [dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection.IncoherentMetadata]:
 * honouring one of two contradictory declarations is how a pipeline ends up with
 * semantics nobody wrote. The diagnostic is [LockInputError.SkipIfLockedWithTimeout].
 */
sealed interface LockIntent {

    /** Take it now or not at all. A held resource is denied immediately. */
    data object Now : LockIntent

    /** Wait up to [millis] for the resource. */
    data class UpTo(val millis: Long) : LockIntent

    /** Wait indefinitely for the resource. */
    data object Forever : LockIntent
}

/**
 * The outcome of asking for a resource. Never a boolean, never a nullable.
 */
sealed interface LockAdmission {

    /**
     * The resource is held by [owner].
     *
     * @param resource the held resource, as the coordinator knows it.
     * @param reentrant whether this hold was satisfied by a hold the SAME
     *   [LockOwner] already had. Jenkins is re-entrant per build, so
     *   `lock("a") { lock("a") { ... } }` inside one run re-enters. A DIFFERENT
     *   owner asking for a held resource is contention and is denied, even
     *   inside the same JVM — the process is not the owner.
     */
    data class Acquired(
        val resource: String,
        val reentrant: Boolean = false,
    ) : LockAdmission

    /** The resource was not obtained. [reason] says which of the ways it was not. */
    data class Denied(val reason: LockDenialReason) : LockAdmission
}

/**
 * Why a resource was not obtained. Closed: the remedies differ, so they are not
 * collapsed into a message string.
 */
sealed interface LockDenialReason {

    /** The resource is held by someone else and the caller asked not to wait. */
    data object Held : LockDenialReason

    /** The caller waited [waitedMillis] and the resource never became free. */
    data class TimedOut(val waitedMillis: Long) : LockDenialReason

    /** The run was cancelled or aborted while waiting. */
    data object Cancelled : LockDenialReason
}

/**
 * Typed decode/validation failure for `core.lock` input. Expected operational
 * input errors are values, never exceptions across the kernel boundary.
 */
sealed interface LockInputError {
    val diagnostic: String

    /** Neither the resource nor a selector was given. */
    data object NoResourceSpecified : LockInputError {
        override val diagnostic: String = "either resource label or resource name must be specified"
    }

    /** `skipIfLocked` and a timeout were both given; see [LockIntent]. */
    data object SkipIfLockedWithTimeout : LockInputError {
        override val diagnostic: String =
            "skipIfLocked asks not to wait, so a timeout is contradictory; supply one or the other"
    }

    /** The timeout is negative, which is not a wait. */
    data class NegativeTimeout(val seconds: Int) : LockInputError {
        override val diagnostic: String = "timeoutSeconds must not be negative, got $seconds"
    }

    /** The resource name is blank, so it names nothing. */
    data object BlankResource : LockInputError {
        override val diagnostic: String = "resource must not be blank"
    }
}

/**
 * Pure decision from decoded `core.lock` input to the [LockIntent] it expresses,
 * or the typed reason it cannot be expressed.
 *
 * Pure: no clock, no filesystem, no coordinator. Testable without constructing a
 * lock backend, which is the point of keeping it separate from [LockCoordinator].
 *
 * The project's closed result algebra, not `kotlin.Result` and not a nullable
 * paired with a boolean: the success carries an intent and the failure carries a
 * reason, and neither is expressible without the other.
 */
sealed interface LockIntentResolution {

    /** The pair expresses an intent. */
    data class Resolved(val intent: LockIntent) : LockIntentResolution

    /** The pair is contradictory or invalid. */
    data class Rejected(val error: LockInputError) : LockIntentResolution
}

fun lockIntentOf(
    skipIfLocked: Boolean,
    timeoutSeconds: Int?,
): LockIntentResolution = when {
    skipIfLocked && timeoutSeconds != null ->
        LockIntentResolution.Rejected(LockInputError.SkipIfLockedWithTimeout)
    skipIfLocked -> LockIntentResolution.Resolved(LockIntent.Now)
    timeoutSeconds == null -> LockIntentResolution.Resolved(LockIntent.Forever)
    timeoutSeconds < 0 -> LockIntentResolution.Rejected(LockInputError.NegativeTimeout(timeoutSeconds))
    else -> LockIntentResolution.Resolved(LockIntent.UpTo(timeoutSeconds.toLong() * 1_000L))
}
