package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepOutputDeclaration
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
 * in-flight bookkeeping. Consequences, all of them the intended laws:
 *
 * - **ordering**: a consumer that runs before its producer finds no journal output and fails
 *   closed with [StepOutputResolutionError.NotYetProduced]. The ordering law is enforced by the
 *   authority, not by a counter the runtime maintains.
 * - **replay**: a reused committed output resolves identically, because the committed row is what
 *   is read. Nothing is recomputed at resolution time.
 * - **type safety**: the producer's declared tag must equal the consumer's expected tag, else
 *   [StepOutputResolutionError.TypeMismatch]. Kotlin's DSL cannot prove this statically, so it is
 *   checked at the boundary and fails closed.
 *
 * [published] maps an output NAME to the producing operation. It is populated by the coordinator
 * as it dispatches steps, and it records only IDENTITY — never output data. That keeps the journal
 * the sole authority for values while still letting a consumer discover the producer's row.
 */
class JournalStepOutputResolver(
    private val published: Map<String, PublishedStepOutput>,
    private val journal: OperationJournal,
) : StepOutputResolver {

    override fun resolveEncoded(ref: StepOutputRef): EncodedStepValue {
        val publishedOutput = published[ref.name]
            ?: throw StepOutputResolutionException(
                StepOutputResolutionError.UnknownOutput(ref.name),
            )

        if (publishedOutput.declaration.typeTag != ref.typeTag) {
            throw StepOutputResolutionException(
                StepOutputResolutionError.TypeMismatch(
                    name = ref.name,
                    expected = ref.typeTag,
                    actual = publishedOutput.declaration.typeTag,
                ),
            )
        }

        val operation = try {
            journal.get(publishedOutput.operationId)
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

        // A row that exists but has not SUCCEEDED yet is still "not yet produced": a consumer must
        // never observe a partial, failed or in-flight producer.
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

    override fun isResolvable(ref: StepOutputRef): Boolean = runCatching {
        resolveEncoded(ref)
    }.isSuccess
}

/**
 * Identity of a published Step output: which NAMES exist, what type each declares, and which
 * operation committed it. Deliberately carries no output data — the journal owns values.
 */
data class PublishedStepOutput(
    val declaration: StepOutputDeclaration,
    val operationId: String,
)
