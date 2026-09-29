package example.lock

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Example external DIRECTIVE plugin (S1-D).
 *
 * Depends ONLY on public SDK contracts (pipeline-domain). Contributes the
 * `acme.lock` directive through [DirectiveContributor]; discovered by the host
 * runtime via ServiceLoader. Zero core/production changes: the engine holds
 * this definition only as registry metadata — admission, phase grouping and
 * typed events flow through the same seam as core-contributed directives.
 *
 * The Evaluate policy means: the engine will later INTERPRET this directive
 * (S1-R0 territory). S1-D proves only that an external key ADMITS fail-closed
 * and is OBSERVED — policy interpretation is not this plugin's business.
 */
@Serializable
data class LockInput(val resource: String, val timeoutSeconds: Int? = null)

object LockDirectiveDefinition : DirectiveDefinition<LockInput, Unit> {
    val KEY = DirectiveKey("acme.lock")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override val key: DirectiveKey = KEY

    override val phase: DirectivePhase = DirectivePhase.BEFORE_STAGE

    override val policy: DirectiveExecutionPolicy = DirectiveExecutionPolicy.Evaluate

    override fun decode(encodedArguments: String): DirectiveDecodeResult<LockInput> =
        try {
            DirectiveDecodeResult.Decoded(
                json.decodeFromString(LockInput.serializer(), encodedArguments),
            )
        } catch (e: kotlinx.serialization.SerializationException) {
            // Typed failure, never an exception across the kernel boundary.
            DirectiveDecodeResult.Malformed("acme.lock arguments are not valid LockInput JSON: ${e.message}")
        }
}
