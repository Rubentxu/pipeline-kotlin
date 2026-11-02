package dev.rubentxu.pipeline.v2.output.store

/**
 * A run identity that cannot name a durable directory.
 *
 * ## Why this is a refusal and not a repair
 *
 * The artifact plane names a run's directory after the run. An identity that
 * cannot be a directory name has no faithful representation there, and the only
 * way to produce one anyway is to change the identity into a different one — so
 * the question this raises is not "which name should it get" but "should it be
 * answered at all".
 *
 * ## What a reader gets instead of another run's frames
 *
 * The frame index answers "which frames does this run have". If the identity
 * were repaired into a nameable one, the answer would be the frames of a
 * DIFFERENT run, delivered without any sign that they were not the asked ones.
 * That is the failure this type exists to make impossible: the reader is told
 * the identity cannot be named, which is a fact about the request, instead of
 * being handed another run's facts.
 */
class UnnameableRunIdentity(val runId: String) : IllegalArgumentException(
    "run id '$runId' cannot name a durable directory",
)
