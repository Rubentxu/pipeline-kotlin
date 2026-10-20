package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.StepNode

/**
 * Deterministic structural identity of a block Step's body (WU-RP-035).
 *
 * ## Why a body-bearing Step needs this
 *
 * A `HANDLER_CONTINUATION` Step runs its registered handler on the durable spine, so it
 * acquires an operation, a fingerprint, a journal row and replay semantics that the
 * engine-driven shape has never had. Its fingerprint is computed from the Step's
 * `OperationInput`, and a block's `OperationInput` carries only its own payload — the
 * body is not in it.
 *
 * That is a silent-reuse hazard, not a theoretical one:
 *
 * ```text
 * run 1: plugin(foo) { echo("A") }  ->  parent SUCCEEDED, output memoized
 * run 2, same runId, same payload foo: plugin(foo) { echo("B") }
 *        -> parent fingerprint unchanged -> REUSE
 *        -> handler never runs -> continuation never invoked
 *        -> the changed child never even reaches divergence
 * ```
 *
 * The changed body would be invisible. So the body must be part of the parent's durable
 * identity: the same parent payload with a different body is a different operation, and
 * the durable law decides what follows (re-execution or divergence) instead of a silent
 * reuse.
 *
 * ## What is hashed
 *
 * Each child contributes its `id`, its `pluginStepId` and its ENCODED PAYLOAD, in
 * declaration order. Payloads are included deliberately: a parent's typed output may
 * depend on what its body did, so a changed child must force the parent to re-run rather
 * than hand back a memoized result computed against different work.
 *
 * ## Scope
 *
 * Applied to the `HANDLER_CONTINUATION` family ONLY. Engine-driven blocks keep their
 * existing fingerprint so that every journal row written by the published 0.45.0
 * candidate stays valid — changing the identity of already-shipped Steps is a
 * spine-level decision, not a side effect of adding a Step family.
 */
internal object BodyStructureDigest {

    /**
     * A stable hex digest of the body shape, or `null` for an empty body.
     *
     * `null` rather than a constant for "no body" keeps the parameter ABSENT from the
     * operation input, exactly as the sandbox profile is absent for the default profile:
     * a missing fact and a fact with a value are different journal states.
     */
    fun of(body: List<StepNode>): String? {
        if (body.isEmpty()) {
            return null
        }
        val canonical = buildString {
            body.forEach { child ->
                append(child.id.value)
                append('\u0000')
                append(child.pluginStepId.value)
                append('\u0000')
                append(child.payload.schemaVersion)
                append('\u0000')
                append(child.payload.encoded)
                append('\u0000')
                child.bodySegments().forEach { append(it).append('\u0000') }
                append('\u001E')
            }
        }
        // Shared utility (B0). Same bytes, same lowercase hex, so parent block identities
        // already written to a journal keep matching.
        return Sha256.ofText(canonical)
    }

    /**
     * Nested body children of a child (a `BlockStepNode` inside a body) contribute to the
     * parent's shape too, otherwise a plugin could swap a child for a block with the same
     * key and keep the parent's identity.
     */
    private fun StepNode.bodySegments(): List<String> =
        (this as? dev.rubentxu.pipeline.v2.domain.BlockStepNode)?.body?.map { it.id.value } ?: emptyList()

}
