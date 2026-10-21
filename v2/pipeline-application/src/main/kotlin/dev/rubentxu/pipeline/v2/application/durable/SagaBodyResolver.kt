package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.saga.CompensationBodyDigest
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.step.BodySlots
import dev.rubentxu.pipeline.v2.domain.step.SlotResolution

/**
 * The bodies a Saga step owns, and what they look like.
 *
 * ## Why the engine measures and the plugin names
 *
 * A body is code. It cannot be written down, stored or compared, so what a promise can carry is
 * the SHAPE of the body — and the only party that can measure a shape is the engine, which holds
 * the body. So the plugin says "the body I mean is the one named 'compensate'" and the engine
 * says what that body IS.
 *
 * The division is the same one the provenance follows: a plugin cannot claim a promise it did
 * not make, and it cannot state the shape of a body it did not write.
 *
 * ## Why one body and not the whole body
 *
 * The digest is of the body that will undo the effect, NOT of the body that contains it. A
 * promise is about ONE body: a change somewhere else in the step leaves the promised body
 * exactly as it was, and refusing the compensation for that would strand a resource that could
 * perfectly well have been undone.
 */
class SagaBodyResolver(private val body: List<StepNode>) {

    /**
     * The child the [slot] names among this body's children.
     *
     * The answer is the domain's own: exactly one child, none, or several. It is never guessed,
     * because a name that fits two children does not identify either of them.
     */
    fun resolve(slot: PluginStepId): SlotResolution =
        BodySlots.resolve(body.map { it.address }, slot)

    /**
     * The shape of the body the [slot] names, or `null` when the slot names no body.
     *
     * `null` covers both "no child carries that name" and "several children do": in neither case
     * is there a body to measure, and a promise that names one of them cannot be paid with it.
     */
    fun digestOf(slot: PluginStepId): CompensationBodyDigest? {
        val resolution = resolve(slot)
        if (resolution !is SlotResolution.Found) return null
        val digest = BodyStructureDigest.of(listOf(body[resolution.childIndex])) ?: return null
        return CompensationBodyDigest(digest)
    }
}

/**
 * The body a promise is paid with, read from the durable row.
 *
 * ## Why this is a separate seam
 *
 * The promised body is a fact about the PROMISE, and the promise lives in the durable row. It
 * does not travel inside [dev.rubentxu.pipeline.v2.domain.saga.CompensationObligation], because
 * that value is a published contract and this fact is not worth breaking it for.
 *
 * So the interpreter asks for it the same way it asks the store for everything else, and the
 * answer is a digest or nothing: a promise armed before digests were kept names no body, and a
 * promise that names no body cannot prove a divergence.
 */
fun interface SagaPromisedBodyReader {

    /**
     * The shape of the body the promise under [key] is paid with, or `null` when it names none.
     */
    fun digestOf(key: CompensationKey): CompensationBodyDigest?
}
