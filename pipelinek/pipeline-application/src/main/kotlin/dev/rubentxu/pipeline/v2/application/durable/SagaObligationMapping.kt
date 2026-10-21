package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligation
import dev.rubentxu.pipeline.v2.domain.saga.SagaPromiseOrigin
import dev.rubentxu.pipeline.v2.events.durable.SagaObligationRow

/**
 * The mapping between a durable row and the domain value that crosses into plugin hands.
 *
 * ## Why it is shared
 *
 * Two seams write obligations — the intent port that arms them and the plan port that moves
 * them along their lifecycle — and both have to read a row back into the same domain value. A
 * mapping written twice is a mapping that eventually differs in one of the two copies, and a
 * difference there is a difference in what a plugin is told the durable state is.
 *
 * ## What the mapping does NOT carry
 *
 * The receipt is deliberately absent from the domain value: it belongs to the row, and the
 * domain value is what crosses into plugin hands. A receipt is a provider's opaque answer,
 * and the port that reports a write returns it to the caller that just observed it.
 */
internal fun SagaObligationRow.toObligation(): CompensationObligation = CompensationObligation(
    sagaId = sagaId,
    actionId = actionId,
    compensationId = compensationId,
    planFingerprint = planFingerprint,
    resourceRef = resourceRef,
    kind = kind,
    intentVersion = intentVersion,
    forwardOpId = forwardOpId,
    compensationOpId = compensationOpId,
    status = status,
    leaseEpoch = leaseEpoch,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
)

/**
 * The provenance of this row: who armed the promise, and under which version of the intent
 * contract.
 */
internal fun SagaObligationRow.toOrigin(): SagaPromiseOrigin = SagaPromiseOrigin(
    pluginId = ownerPluginId,
    intentVersion = intentVersion,
)

/**
 * The row that holds [this] obligation under [runId].
 *
 * The receipt is left empty on purpose: the row is what a promise is made of, and a promise
 * has no receipt until an effect happens.
 *
 * [ownerPluginId] is the plugin that made the promise. It is stamped by the engine, which is
 * the only party that knows which plugin is running: a plugin cannot claim a promise it did not
 * make.
 */
internal fun CompensationObligation.toRow(runId: String, ownerPluginId: String?): SagaObligationRow = SagaObligationRow(
    runId = runId,
    sagaId = sagaId,
    actionId = actionId,
    compensationId = compensationId,
    intentVersion = intentVersion,
    planFingerprint = planFingerprint,
    resourceRef = resourceRef,
    kind = kind,
    status = status,
    forwardOpId = forwardOpId,
    compensationOpId = compensationOpId,
    leaseEpoch = leaseEpoch,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    receiptRef = null,
    ownerPluginId = ownerPluginId,
)
