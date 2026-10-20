package dev.rubentxu.pipeline.v2.domain.saga

/**
 * The version of the compensation-intent contract.
 *
 * ## Why the version is a number and not a name
 *
 * The intent is written to durable storage and read back by a LATER PROCESS, possibly a later
 * release. A reader that meets a version it does not know must refuse the row, not reinterpret
 * it: an obligation armed under a different intent semantics is a promise nobody can keep, and
 * the safe reading of "I cannot prove what this promise was" is "I do not fulfil it".
 *
 * So the contract is versioned numerically and the store refuses anything outside the supported
 * window, in BOTH directions. A newer version is refused because this binary cannot know what
 * the newer one promised. An older version is refused because the guarantee it was written
 * under is no longer the guarantee this binary offers.
 */
object SagaIntentContract {

    /** The intent version this build writes. */
    const val CURRENT_VERSION: Int = 1

    /** The oldest intent version this build can still honour. */
    const val MIN_SUPPORTED_VERSION: Int = 1

    /**
     * Whether an intent written under [version] can be read and honoured by this build.
     *
     * Total: it answers for every [Int], including the versions nobody has written yet.
     */
    fun supports(version: Int): Boolean =
        version in MIN_SUPPORTED_VERSION..CURRENT_VERSION

    /**
     * The reason an intent version was refused.
     *
     * @throws IllegalArgumentException when [version] is supported.
     */
    fun requireSupported(version: Int): Int {
        require(supports(version)) {
            "compensation intent version $version is not supported " +
                "(supported: $MIN_SUPPORTED_VERSION..$CURRENT_VERSION)"
        }
        return version
    }
}

/**
 * What a compensation DOES to the resource it compensates.
 *
 * The kind is part of the durable obligation, not of the plugin's body: it is what lets a later
 * process decide, without the original plugin body in scope, whether the compensation is
 * destructive, restorative or a no-op.
 */
enum class CompensationKind {

    /** Puts the resource back the way it was. */
    RESTORE,

    /** Removes the resource. */
    DELETE,

    /** The forward action had nothing to compensate; the obligation exists only for symmetry. */
    NO_OP,
}

/**
 * The lifecycle of a durable compensation obligation.
 *
 * ## Why this is NOT `dev.rubentxu.pipeline.v2.domain.durable.OperationStatus`
 *
 * An obligation is not an operation. The operation journal tracks attempts of a forward effect;
 * an obligation tracks a PROMISE made before that effect. They have different clocks (an
 * operation is born and dies inside an attempt; an obligation outlives the process that armed
 * it) and different failure modes. Folding one into the other would mean the journal's status
 * column starts answering questions about compensation, and every reader of it would have to
 * know which of its states are reachable from a Saga.
 *
 * So the vocabulary is separate and closed: this enum is the only set of states a durable
 * obligation can be written in, and it is declared by the milestone that owns the write.
 */
enum class CompensationObligationStatus {

    /**
     * The durable intent is recorded. The forward effect has NOT been dispatched yet.
     *
     * This is the status a promise is born in, and it is also the status in which nothing is
     * owed: an effect that was never attempted has nothing to compensate.
     */
    ARMED,

    /**
     * The forward effect was DISPATCHED and its outcome has not been recorded.
     *
     * ## Why "dispatched" is a durable fact
     *
     * Between "the call left the process" and "the outcome was written down" there is a window
     * in which a crash loses the answer: the provider may have done the thing, and nobody
     * knows. Recording the dispatch BEFORE it happens is what closes that window — a row found
     * in this state after a restart is not "nothing happened", it is "something may have
     * happened and the answer was lost", which is a very different thing to compensate.
     */
    EFFECT_PENDING,

    /**
     * The forward effect was observed and confirmed.
     *
     * The obligation stays open, because a confirmed forward effect is precisely the case that
     * needs a compensation.
     */
    EFFECT_CONFIRMED,

    /**
     * The forward effect happened AND the Saga COMPLETED: the effect is KEPT and the promise
     * is void.
     *
     * ## Why this state exists
     *
     * A confirmed forward effect owes a compensation — that is what [EFFECT_CONFIRMED] means —
     * and the only way out of that debt used to be undoing the effect. A Saga that ended well
     * could therefore not say so: its obligations stayed owed for ever, a resumed run
     * compensated a Saga that had succeeded, and a run that reported success could show a
     * compensated Saga in its own history.
     *
     * This is the state that says the opposite of [COMPENSATED]: the effect was NOT undone, it
     * was kept. Nothing is owed, and nothing may be undone.
     */
    EFFECT_KEPT,

    /**
     * The forward was observed to have had NO effect.
     *
     * The obligation closes as not required: there is nothing to compensate.
     */
    EFFECT_ABSENT,

    /**
     * The forward effect's outcome is INDETERMINATE.
     *
     * The call was made and the answer did not settle whether the effect happened. The
     * obligation is still open, and the provider may still be able to say what happened: a
     * reconciler that answers "it did happen" moves the obligation to [EFFECT_CONFIRMED], and
     * one that answers "it did not" moves it to [EFFECT_ABSENT].
     */
    EFFECT_UNKNOWN,

    /**
     * The compensation was DISPATCHED and its outcome has not been recorded.
     *
     * The same window as [EFFECT_PENDING], on the other side of the Saga: the compensation may
     * have been applied, so running it again would apply it twice. A row found in this state
     * is never compensated blindly.
     */
    COMPENSATION_PENDING,

    /**
     * The compensation ran and was confirmed.
     */
    COMPENSATED,

    /**
     * The compensation ran and FAILED.
     *
     * The resource was NOT put back. The compensations that depend on this one are not
     * attempted: a dependent compensation against a state that was not restored destroys more
     * than the failure it was recovering from.
     */
    COMPENSATION_FAILED,

    /**
     * The compensation ran and its outcome is INDETERMINATE.
     *
     * The resource may or may not have been put back. The same rule as a failed compensation,
     * with one more reason to stop: the compensation may already have been applied, so running
     * it again would double it.
     */
    COMPENSATION_UNCERTAIN,

    /**
     * The outcome is indeterminate and neither a reconciler nor an automatic decision can settle
     * it. A human decides.
     */
    NEEDS_INTERVENTION,
}

/**
 * The identity of a compensation obligation, WITHOUT the run.
 *
 * ## Why the run is not here
 *
 * The durable key is `(run_id, saga_id, action_id, compensation_id)`. The run is deliberately
 * absent from this type, because the run is AMBIENT: the SDK port that creates an obligation is
 * bound to one invocation, and a plugin cannot name a run it is not executing in. If the run
 * were a field of the declaration, a plugin could ask for another run's obligation, and the
 * "intent port" would be a general-purpose write cursor into the store — exactly what the
 * port is not allowed to be.
 *
 * The row carries the run because the STORE needs it; the domain value does not, because the
 * domain value is what crosses into plugin hands.
 */
data class CompensationKey(
    val sagaId: SagaId,
    val actionId: SagaActionId,
    val compensationId: CompensationId,
)

/**
 * What a plugin DECLARES when it arms a compensation obligation.
 *
 * This is the plugin's side of the contract and it carries no runtime handle, no lambda, no
 * secret and no step node: only the data that has to survive until a later process compensates.
 */
data class CompensationDeclaration(
    val sagaId: SagaId,
    val actionId: SagaActionId,
    val compensationId: CompensationId,
    val planFingerprint: SagaPlanFingerprint,
    val resourceRef: ResourceRef,
    val kind: CompensationKind,
    val intentVersion: Int = SagaIntentContract.CURRENT_VERSION,
) {
    /** The key this declaration will be stored under. */
    val key: CompensationKey get() = CompensationKey(sagaId, actionId, compensationId)
}

/**
 * A durable compensation obligation, as read back from the store.
 *
 * Immutable, and free of anything that cannot survive a restart: the forward and compensation
 * operation identities are plain strings, the resource is a reference, and the receipt is a
 * reference. There is nothing here that a later process could not reconstruct, and nothing here
 * that a plugin could use to reach back into the engine.
 */
data class CompensationObligation(
    val sagaId: SagaId,
    val actionId: SagaActionId,
    val compensationId: CompensationId,
    val planFingerprint: SagaPlanFingerprint,
    val resourceRef: ResourceRef,
    val kind: CompensationKind,
    val intentVersion: Int,
    val forwardOpId: String,
    val compensationOpId: String,
    val status: CompensationObligationStatus,
    val leaseEpoch: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    /** The key this obligation is stored under. */
    val key: CompensationKey get() = CompensationKey(sagaId, actionId, compensationId)
}

/**
 * The phase of a Saga operation, used to derive a deterministic operation identity.
 *
 * ## Why the phase is part of the identity
 *
 * The runtime's own operation identity is already versioned and unique per attempt. A
 * compensation is NOT the same operation as the forward it undoes: it happens later, possibly
 * in another process, and it must not collide with the forward in the operation journal. The
 * phase slot is what separates them, and it is versioned so a future change to the derivation
 * is detectable instead of silently reusing an identity that already means something else.
 */
enum class SagaOperationPhase(val slot: String) {
    FORWARD("forward"),
    COMPENSATE("compensate"),
    RECONCILE("reconcile"),
}

/**
 * Derives the deterministic identity of a Saga's operations.
 *
 * Pure and total: the same inputs always produce the same identity, with no clock, no
 * randomness and no ambient state. That is what lets a restarted process find the obligation it
 * armed before.
 */
object SagaOperationIdentity {

    /**
     * The version tag in every derived Saga operation identity.
     *
     * Bump it if the derivation changes: identities minted under a different derivation must not
     * silently collide with identities minted under this one.
     */
    const val VERSION_TAG: String = "saga-v1"

    /**
     * The environment variable that carries a Saga operation's key to the body that runs it.
     *
     * ## Why the key has to reach the body
     *
     * A body that talks to the outside world needs a key that the outside world can RECOGNISE.
     * Without one, a body that is asked twice cannot tell "I have never done this" from "I did
     * it and lost the answer", and the only safe answer to that ambiguity is to do nothing and
     * ask a human. With one, the second attempt is recognisable as the same operation, and the
     * body can answer "already done" instead of doing it twice.
     *
     * That is the whole contract of [CompensationPolicy.Reconcilable]: the plugin declares it
     * when its bodies are safe to ask twice, and the engine asks twice with the SAME key.
     *
     * ## The key is the OPERATION's, not the attempt's
     *
     * The value is [derive]d from the forward operation, the phase and the compensation, so it
     * is the same in the first attempt and in every reconciliation. The engine's own attempt
     * identity is a different thing and lives in the operation journal: two attempts of the
     * same operation are two rows there, and one key here.
     */
    const val OPERATION_KEY_ENV: String = "PIPELINEK_SAGA_OPERATION_KEY"

    /**
     * The identity of the compensation operation for [compensationId] within [forwardOpId].
     *
     * @param forwardOpId the identity of the forward operation being compensated.
     * @param phase the phase of the derived operation.
     * @param compensationId the compensation's own identity.
     */
    fun derive(
        forwardOpId: String,
        phase: SagaOperationPhase,
        compensationId: CompensationId,
    ): String {
        require(forwardOpId.isNotBlank()) { "forwardOpId must not be blank" }
        return "$VERSION_TAG|${phase.slot}|${compensationId.value}|$forwardOpId"
    }
}
