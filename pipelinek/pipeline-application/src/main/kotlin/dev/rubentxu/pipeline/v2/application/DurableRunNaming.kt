package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId

/**
 * The reason a run identity cannot name a durable location, or `null` when it can.
 *
 * The durable name IS the identity, so an identity that cannot name a location
 * has no name to write: it is refused where it would have entered the durable
 * plane, with the reason stated, instead of being folded onto somebody else's
 * name — because folded, two identities share one location and the reader of
 * one is handed the facts of the other.
 *
 * The rule belongs to the identity ([RunId.isNameable]); this only phrases it
 * for the application layer's typed refusals, so that the two adapters that
 * name a durable location say the same thing.
 */
internal fun unnameableRunIdReason(runId: String, operation: String): String? =
    if (RunId.isNameable(runId)) null
    else "run id '$runId' cannot name a durable location; refusing to $operation"
