package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.SagaOwnershipVerdict
import dev.rubentxu.pipeline.v2.domain.saga.SagaPromiseOrigin
import dev.rubentxu.pipeline.v2.domain.saga.SagaPromiseOwnership
import dev.rubentxu.pipeline.v2.runtime.durable.SagaObligationRow

/**
 * Whether the promise belongs to a plugin other than the one that writes.
 *
 * The judgement is the DOMAIN's: this is the rule about who owns a promise, and it lives in one
 * place so that the durable store and the volatile one cannot drift apart.
 */
internal fun isForeignOwner(row: SagaObligationRow, writerPluginId: String?): Boolean {
    val stored = SagaPromiseOrigin(pluginId = row.ownerPluginId, intentVersion = row.intentVersion)
    val writer = writerPluginId?.let { SagaPromiseOrigin(pluginId = it, intentVersion = row.intentVersion) }
    val verdict = SagaPromiseOwnership.verdict(stored, writer)
    return verdict is SagaOwnershipVerdict.ForeignOwner
}
