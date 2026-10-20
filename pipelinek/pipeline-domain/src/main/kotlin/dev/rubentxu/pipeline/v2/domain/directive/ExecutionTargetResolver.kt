package dev.rubentxu.pipeline.v2.domain.directive

/**
 * S3.1 — the execution-target resolver SPI.
 *
 * This is the single seam between "a stage declared a target requirement" and
 * "something acquired a target". The engine never decides whether a requirement
 * is satisfiable and never allocates; it hands the requirement here and
 * interprets the typed answer. That split is the whole point: a decision that
 * needs a capability must not live in a coordinator, and an effect must not be
 * reachable from a decision.
 *
 * ## Deliberate divergence from the proposal document
 *
 * `02-directive-model.md` §6 specifies this as
 * `suspend fun acquire(requirement): TargetLeaseResult`. It is declared
 * `fun` here instead, and the reason is mechanical rather than preferential:
 * the durable coordinator's run loop is synchronous, so a `suspend` port would
 * force the entire run to become suspending before a single remote target
 * could be allocated. That is the S4 runtime evolution, not S3.1.
 *
 * The shape RP-8 needs is preserved anyway — a resolver is free to block, and
 * the interface does not forbid an implementation that does — so this is a
 * change of arity, not of contract. Recording it here means the divergence is a
 * decision someone can revisit rather than an accident to rediscover.
 *
 * ## Why a resolver and not a registry lookup
 *
 * A registry lookup would answer "is a target of this kind registered", which
 * is not what an execution target is. Acquiring one is a question about the
 * machine the run is on, the capabilities it was granted, and — in RP-8 —
 * about other machines it may talk to. A name is not an answer to any of those.
 */
fun interface ExecutionTargetResolver {

    /**
     * Acquire a target satisfying [requirement], or refuse with a reason.
     *
     * Implementations MUST be total: every case of
     * [ExecutionTargetRequirement] produces a [TargetLeaseResult], and a
     * requirement this runtime cannot honour produces
     * [TargetLeaseResult.Refused] with a diagnostic naming the gap. Returning
     * "unknown" or throwing is not an option, because a stage whose target
     * cannot be obtained has made no progress and the run has to say so.
     */
    fun acquire(requirement: ExecutionTargetRequirement): TargetLeaseResult
}
