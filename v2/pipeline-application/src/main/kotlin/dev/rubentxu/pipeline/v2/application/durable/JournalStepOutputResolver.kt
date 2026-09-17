package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepOutputPublication
import dev.rubentxu.pipeline.v2.domain.step.StepOutputRef
import dev.rubentxu.pipeline.v2.domain.step.StepOutputResolutionError
import dev.rubentxu.pipeline.v2.domain.step.StepOutputResolutionException
import dev.rubentxu.pipeline.v2.domain.step.StepOutputResolver
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import kotlinx.serialization.json.JsonPrimitive

/**
 * LFC-2E3-P / P2 — canonical [StepOutputResolver] backed by the operation journal.
 *
 * The journal is the single AUTHORITY for committed state, so resolution reads it rather than any
 * in-flight bookkeeping. Consequences, all of them intended laws:
 *
 * - **ordering**: a consumer running before its producer finds no committed output and fails closed
 *   with [StepOutputResolutionError.NotYetProduced]. Ordering is enforced by the authority, not by a
 *   counter the runtime maintains alongside it.
 * - **replay**: a reused committed output resolves identically, because the committed row is what is
 *   read. Nothing is recomputed at resolution time.
 * - **type identity**: the producer's declared tag must equal the consumer's expected tag.
 * - **schema evolution**: the producer's codec identity recorded at publication must equal the one
 *   re-derived now. A mismatch (typically a plugin upgrade between a run and its replay) fails
 *   closed with [StepOutputResolutionError.SchemaDrift] instead of decoding old data with a new
 *   codec.
 * - **explicit publication only**: only outputs a canonical invocation PUBLISHED are reachable.
 *   Every other persisted journal row is unreachable by reference, so a `StepOutputRef` is not an
 *   arbitrary journal lookup key.
 *
 * [published] maps `(producer Step family, logical name)` to the producing operation. It records
 * only IDENTITY — never output data — so the journal stays the sole authority for values.
 */
class JournalStepOutputResolver(
    private val published: Map<StepOutputKey, StepOutputPublication>,
    private val journal: OperationJournal,
    /**
     * Re-derives the producer's current codec identity from the live registry. Returning null means
     * the producer family is no longer registered, which is itself a schema drift.
     */
    private val currentCodecIdentity: (PluginStepId) -> String? = { null },
) : StepOutputResolver {

    override fun resolveEncoded(ref: StepOutputRef): EncodedStepValue {
        // Explicit-publication law: absence here is terminal, even if the journal happens to hold
        // a row with a matching operation id.
        val publication = published[StepOutputKey(ref.producerKey, ref.name)]
            ?: throw StepOutputResolutionException(
                StepOutputResolutionError.UnknownOutput(ref.name),
            )

        // Producer identity: the reference must name the family that actually published it.
        val actualProducer = publication.declaration.producerKey.value
        if (publication.declaration.producerKey != ref.producerKey) {
            throw StepOutputResolutionException(
                StepOutputResolutionError.ProducerMismatch(
                    name = ref.name,
                    expected = ref.producerKey.value,
                    actual = actualProducer,
                ),
            )
        }

        // Type identity.
        if (publication.declaration.typeTag != ref.typeTag) {
            throw StepOutputResolutionException(
                StepOutputResolutionError.TypeMismatch(
                    name = ref.name,
                    expected = ref.typeTag,
                    actual = publication.declaration.typeTag,
                ),
            )
        }

        // Schema identity: fail closed across a codec change rather than decode optimistically.
        val liveIdentity = currentCodecIdentity(ref.producerKey)
        if (liveIdentity == null || liveIdentity != publication.codecIdentity) {
            throw StepOutputResolutionException(
                StepOutputResolutionError.SchemaDrift(
                    name = ref.name,
                    publishedIdentity = publication.codecIdentity,
                    currentIdentity = liveIdentity ?: "<producer no longer registered>",
                ),
            )
        }

        val operation = try {
            journal.get(publication.producerOperationId)
        } catch (e: Throwable) {
            throw StepOutputResolutionException(
                StepOutputResolutionError.Unreadable(
                    ref.name,
                    e.message ?: e::class.simpleName.orEmpty(),
                ),
            )
        } ?: throw StepOutputResolutionException(
            StepOutputResolutionError.NotYetProduced(ref.name),
        )

        // A row that exists but has not SUCCEEDED is still "not yet produced": a consumer must never
        // observe a partial, failed or in-flight producer.
        if (operation.status != OperationStatus.SUCCEEDED) {
            throw StepOutputResolutionException(
                StepOutputResolutionError.NotYetProduced(ref.name),
            )
        }

        val encoded = (operation.output?.result as? JsonPrimitive)?.content
            ?: throw StepOutputResolutionException(
                StepOutputResolutionError.ProducerProducedNoOutput(ref.name),
            )

        return EncodedStepValue(encoded)
    }

    override fun isResolvable(ref: StepOutputRef): Boolean =
        runCatching { resolveEncoded(ref) }.isSuccess
}

/** Composite key: an output is identified by WHICH family published it AND its logical name. */
data class StepOutputKey(
    val producerKey: PluginStepId,
    val name: String,
)
