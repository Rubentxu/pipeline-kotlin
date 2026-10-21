package dev.rubentxu.pipeline.v2.domain.saga

/**
 * Where a promise comes from.
 *
 * ## Why a promise needs a provenance
 *
 * A promise is a durable claim that something will be undone later: the effect is done, and the
 * promise says how. The plan fingerprint says WHICH effect and WHICH compensation, but it says
 * nothing about WHO promised: two plugins can declare the same action, the same resource and the
 * same slot names, and their fingerprints are then identical even though their bodies are not.
 *
 * That gap is what this type closes. A promise remembers the plugin that made it, so a promise
 * that belongs to someone else can be recognised as such instead of being mistaken for one's own.
 *
 * @property pluginId the plugin that armed the promise. `null` when the build that armed it did
 * not record one, which is the case for promises written before the provenance was kept. Such a
 * promise cannot prove a conflict, so it is not treated as owned by anyone.
 * @property intentVersion the version of the intent contract the promise was written under. It
 * says what the promise MEANS, not who made it.
 */
data class SagaPromiseOrigin(
    val pluginId: String?,
    val intentVersion: Int,
)

/**
 * What two provenances mean when they are compared.
 *
 * The vocabulary is closed: a new way of owning a promise is a new value here, and every place
 * that reads it has to say what it means before the code compiles.
 */
sealed interface SagaOwnershipVerdict {

    /** The writer may write to the promise: it is the writer's, or nobody's to begin with. */
    data object Allowed : SagaOwnershipVerdict

    /**
     * The promise belongs to another plugin.
     *
     * @property owner the plugin that armed the promise.
     * @property writer the plugin that tried to write to it.
     */
    data class ForeignOwner(val owner: String, val writer: String) : SagaOwnershipVerdict
}

/**
 * Who may write to a promise.
 *
 * ## The rule
 *
 * A promise belongs to the plugin that armed it, and nobody else writes to it. A writer that
 * presents a different provenance is refused, and the refusal says WHICH plugin holds the promise
 * so the reason is actionable instead of merely discouraging.
 *
 * ## What is deliberately NOT refused
 *
 * - A promise whose owner was never recorded: the build that wrote it did not keep the
 *   provenance, so nothing can be proven against it. Refusing would strand promises that are
 *   perfectly valid.
 * - A writer that does not state who it is: the caller is not claiming ownership, so there is
 *   nothing to contradict. Callers that CAN state it always do.
 */
object SagaPromiseOwnership {

    /**
     * Whether [writer] may write to a promise whose provenance is [stored].
     *
     * @param stored the provenance of the promise as it is stored.
     * @param writer the provenance of the writer, or `null` when the writer does not state one.
     */
    fun verdict(stored: SagaPromiseOrigin, writer: SagaPromiseOrigin?): SagaOwnershipVerdict {
        val owner = stored.pluginId ?: return SagaOwnershipVerdict.Allowed
        val writing = writer?.pluginId ?: return SagaOwnershipVerdict.Allowed
        if (owner == writing) return SagaOwnershipVerdict.Allowed
        return SagaOwnershipVerdict.ForeignOwner(owner = owner, writer = writing)
    }
}
