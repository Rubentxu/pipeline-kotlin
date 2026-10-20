package dev.rubentxu.pipeline.v2.events.durable

/**
 * S2-R0 — run ownership as a first-class domain concept.
 *
 * ## Why this exists when `UNIQUE(run_id, sequence)` already exists
 *
 * The database constraint and run ownership are different guarantees, and
 * conflating them is a real error:
 *
 * ```text
 * UNIQUE(run_id, sequence)  ->  "no two ROWS share a number"
 * run ownership             ->  "exactly one PROCESS publishes facts for a run"
 * ```
 *
 * The first is already delivered (WU-RP-020). It is a *last belt*: the losing
 * writer is rejected by SQLite, its batch dies, and it fails closed at
 * flush/close. That protects the invariant. It does NOT make the loser a
 * well-behaved participant — it makes it a corpse.
 *
 * The consequences that matter, and that a constraint cannot fix:
 *
 *  1. **The failure is a raw driver error.** `Rp020CrossInstanceSequenceAuthorityTest`
 *     records it verbatim: *"the invariant is protected, the error is not yet
 *     a typed rejection"*. An operator sees a UNIQUE violation from SQLite,
 *     not "another process owns this run".
 *  2. **Losing events are lost, not deferred.** The rejected batch is dropped.
 *     A run owned by one process should not lose facts because a second
 *     process probed it.
 *  3. **No fencing.** A stale owner that was paused (SIGSTOP, VM snapshot,
 *     long GC) can wake up and append long after a legitimate owner took
 *     over. The constraint will happily accept its rows if the numbers do not
 *     collide, so the log ends up with facts from a superseded authority.
 *
 * `SqliteEventStore` says this itself: *"Deciding which writer owns a run is
 * the separate RunExecutionLease design question."* This file is that answer.
 *
 * ## The law
 *
 * > A `runId` has exactly one publishing authority at a time, and every
 * > takeover advances a fencing token so a superseded owner can be detected.
 *
 * This file is the **pure decider**. It takes observed lease state and returns
 * a typed decision. It performs no I/O and knows nothing about files, clocks
 * or processes — [RunExecutionLeaseStore] is the adapter that gathers the
 * facts. Keeping the decision pure is what makes the law testable without a
 * filesystem, and what lets a mutation prove the gate has teeth.
 */

/** A process-scoped identity for whoever is attempting to own a run. */
@JvmInline
value class RunOwnerId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        /**
         * A validated owner id. Blank or unprintable ids are rejected at the
         * boundary rather than being normalised into something that could
         * collide with another owner in a diagnostic.
         */
        fun of(raw: String): RunOwnerId? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            return RunOwnerId(trimmed)
        }
    }
}

/**
 * A monotonically increasing authority token. A token is not a lock: it is the
 * *proof of recency*. A writer holding token `N` after `N+1` has been issued
 * knows, without consulting anyone, that it has been superseded.
 */
@JvmInline
value class FencingToken private constructor(val value: Long) {
    override fun toString(): String = value.toString()

    companion object {
        /** The token held before any owner has ever been admitted. */
        val UNISSUED: FencingToken = FencingToken(0)

        /** The token a first owner receives. */
        val FIRST: FencingToken = FencingToken(1)

        fun of(raw: Long): FencingToken = FencingToken(raw)

        internal fun issueNext(current: FencingToken): FencingToken = FencingToken(current.value + 1)
    }
}

/** The durable, observable state of a run's ownership. */
data class LeaseRecord(
    val runId: String,
    /** The current owner, or `null` when the run is unowned. */
    val ownerId: RunOwnerId?,
    /** The last token issued. `FencingToken.UNISSUED` when never owned. */
    val fencingToken: FencingToken,
    /**
     * Whether the current owner is still alive. `null` when unknown, which is
     * deliberately distinct from `false`: an unobserved heartbeat is not a
     * dead owner.
     */
    val ownerAlive: Boolean? = null,
)

/** A request to become the publishing authority for a run. */
data class LeaseRequest(
    val runId: String,
    val ownerId: RunOwnerId,
)

/**
 * The decision for one acquisition attempt. Every case is a legitimate shape
 * of the world, and each carries only the payload that shape needs.
 */
sealed interface LeaseAcquisition {

    /**
     * The requester now owns the run. [fencingToken] is the authority it must
     * present on every subsequent publish; it is never reused.
     */
    data class Acquired(
        val record: LeaseRecord,
        val fencingToken: FencingToken,
    ) : LeaseAcquisition

    /**
     * Someone else holds the lease and is still authoritative. The requester
     * must not publish. [fencingToken] is the CURRENT holder's token, included
     * so the diagnostic can name the live authority rather than just "busy".
     */
    data class AlreadyOwned(
        val record: LeaseRecord,
        val heldBy: RunOwnerId,
        val fencingToken: FencingToken,
    ) : LeaseAcquisition

    /**
     * The previous owner's lease is no longer live (released, or its process is
     * gone), so this request takes over. The token ADVANCES, which is what
     * makes the previous owner detectable as stale if it ever wakes up.
     */
    data class TakenOver(
        val record: LeaseRecord,
        val fencingToken: FencingToken,
        val previousOwner: RunOwnerId?,
    ) : LeaseAcquisition

    /**
     * The requester already owns the run at the expected token: this is a
     * re-entrant acquisition, not a takeover. A distinct case because
     * re-entering must NOT advance the token — that would fence the requester
     * against itself.
     */
    data class Reentered(
        val record: LeaseRecord,
        val fencingToken: FencingToken,
    ) : LeaseAcquisition

    /**
     * Ownership could not be established. Fail closed: a process that cannot
     * prove it is the authority must not publish. Never collapsed into
     * [AlreadyOwned] — "I could not tell" and "someone else has it" call for
     * different operator responses.
     */
    data class Unverifiable(val reason: String) : LeaseAcquisition

    fun render(): String = when (this) {
        is Acquired -> "lease ACQUIRED by ${record.ownerId} at token $fencingToken"
        is Reentered -> "lease REENTERED by ${record.ownerId} at token $fencingToken"
        is AlreadyOwned ->
            "lease DENIED: run ${record.runId} is owned by $heldBy at token $fencingToken"
        is TakenOver ->
            "lease TAKEN OVER by ${record.ownerId} at token $fencingToken " +
                "(previous: ${previousOwner ?: "none"})"
        is Unverifiable -> "lease UNVERIFIABLE: $reason"
    }
}

/**
 * The decision for one publish attempt, taken *before* facts are written. This
 * is the fencing check: a superseded owner discovers it here, not by
 * corrupting the log.
 */
sealed interface PublishAuthority {

    /** The publisher still holds the lease at [held]. Proceed. */
    data class Authorised(val held: FencingToken) : PublishAuthority

    /**
     * The publisher's token is behind the current one: it has been superseded
     * and MUST NOT write. This is the whole point of fencing.
     */
    data class Superseded(
        val held: FencingToken,
        val current: FencingToken,
    ) : PublishAuthority

    /** Ownership could not be confirmed, so publishing fails closed. */
    data class Unverifiable(val reason: String) : PublishAuthority

    fun render(): String = when (this) {
        is Authorised -> "publish AUTHORISED at token $held"
        is Superseded ->
            "publish REFUSED: holder token $held is superseded by $current. " +
                "This owner was fenced out; it must not write."
        is Unverifiable -> "publish UNVERIFIABLE: $reason"
    }
}

/**
 * The pure decider. Every function is total and side-effect free: same facts
 * always yield the same decision, which is what makes the lease replayable and
 * the tests honest.
 */
object RunExecutionLease {

    /**
     * Decide whether [request] may become the publishing authority.
     *
     * Ordering of the cases is the policy, and each rule is a distinct
     * property:
     *
     *  1. blank run id -> Unverifiable (cannot key a lease on nothing)
     *  2. live different owner -> AlreadyOwned (the core law)
     *  3. same owner -> Reentered (idempotent, no token advance)
     *  4. unowned / dead previous owner -> Acquired or TakenOver, advancing
     *     the token so the predecessor becomes detectable
     */
    fun acquire(current: LeaseRecord?, request: LeaseRequest): LeaseAcquisition {
        if (request.runId.isBlank()) {
            return LeaseAcquisition.Unverifiable(
                "a lease cannot be keyed on a blank run id",
            )
        }

        if (current == null) {
            val token = FencingToken.FIRST
            return LeaseAcquisition.Acquired(
                record = LeaseRecord(request.runId, request.ownerId, token, ownerAlive = true),
                fencingToken = token,
            )
        }

        if (current.ownerId == request.ownerId) {
            // Re-entry by the current owner: keeping the token stable is what
            // stops an owner fencing itself out of its own run.
            return LeaseAcquisition.Reentered(current, current.fencingToken)
        }

        val previousStillLive = current.ownerId != null && current.ownerAlive == true
        if (previousStillLive) {
            return LeaseAcquisition.AlreadyOwned(current, current.ownerId!!, current.fencingToken)
        }

        // The previous owner released, died, or is unknown-but-not-confirmed-live.
        // An UNKNOWN heartbeat is treated as takeable: the alternative is a run
        // that can never be recovered because nobody can prove a dead process
        // is dead. The fencing token is what makes that trade safe.
        val previousOwner = current.ownerId
        val token = FencingToken.issueNext(current.fencingToken)
        return LeaseAcquisition.TakenOver(
            record = LeaseRecord(request.runId, request.ownerId, token, ownerAlive = true),
            fencingToken = token,
            previousOwner = previousOwner,
        )
    }

    /**
     * Decide whether a publisher may write, given the token it holds and the
     * token the lease currently carries.
     *
     * A publisher whose token is behind is fenced out. A publisher that cannot
     * be resolved fails closed. Only an exact match proceeds.
     */
    fun authorisePublish(
        observed: LeaseRecord?,
        held: FencingToken,
    ): PublishAuthority {
        if (observed == null) {
            return PublishAuthority.Unverifiable(
                "no lease record exists for this run, so publishing authority " +
                    "cannot be confirmed",
            )
        }
        val current = observed.fencingToken
        return when {
            held.value > current.value ->
                PublishAuthority.Superseded(held, current)
            held.value < current.value ->
                PublishAuthority.Superseded(held, current)
            else -> PublishAuthority.Authorised(held)
        }
    }

    /**
     * The state a holder writes when it releases cleanly. Releasing does NOT
     * advance the token: the next owner advances it on acquisition, and a
     * release that bumped the token would fence nobody and confuse the audit
     * trail.
     */
    fun release(current: LeaseRecord, ownerId: RunOwnerId): LeaseRecord {
        if (current.ownerId != ownerId) {
            // A stale owner releasing must not clear the live owner's lease.
            return current
        }
        return current.copy(ownerId = null, ownerAlive = false)
    }
}
