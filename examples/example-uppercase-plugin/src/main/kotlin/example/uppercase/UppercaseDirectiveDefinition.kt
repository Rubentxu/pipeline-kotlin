package example.uppercase

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinitionAny
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * S6/I — the plugin's SECOND family shape, and the one that is not a Step at all.
 *
 * ## Why this plugin needed a directive to prove anything about directives
 *
 * The existing external directive plugin (`acme.lock`) is a separate JAR with a separate build.
 * It proves a directive can be contributed externally, but it cannot prove what BLOCK 1-I needs
 * to prove: that **one** artifact contributes all four families, and that admission cross-checks
 * the directive family of that same artifact. Cross-checking is inherently per-artifact, so the
 * subject has to be an artifact that has a directive to cross-check.
 */
@Serializable
data class UppercaseAffected(val prefix: String = "")

/**
 * `example.uppercase.casedOn` — declare which Steps this plugin's case table applies to.
 *
 * The policy is [DirectiveExecutionPolicy.Evaluate], so the host INTERPRETS this directive rather
 * than the plugin running code at directive time. That is the point of the seam: a plugin says
 * something structural, and the engine decides what to do about it, which is what keeps the engine
 * from learning the name `example.*`.
 */
object UppercaseCasedOnDirectiveDefinition : DirectiveDefinition<UppercaseAffected, Unit> {

    val KEY = DirectiveKey("example.uppercase.casedOn")

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override val key: DirectiveKey = KEY

    override val phase: DirectivePhase = DirectivePhase.BEFORE_STAGE

    override val policy: DirectiveExecutionPolicy = DirectiveExecutionPolicy.Evaluate

    /**
     * A typed failure, never an exception across the kernel boundary — and for the same reason as
     * everywhere else in this plugin: a malformed directive must be a value the engine can report
     * by name, not a throw that unwinds past the seam it crossed.
     */
    override fun decode(encodedArguments: String): DirectiveDecodeResult<UppercaseAffected> =
        try {
            DirectiveDecodeResult.Decoded(
                json.decodeFromString(UppercaseAffected.serializer(), encodedArguments),
            )
        } catch (e: SerializationException) {
            DirectiveDecodeResult.Malformed(
                "example.uppercase.casedOn arguments are not valid UppercaseAffected JSON: ${e.message}",
            )
        }
}

/**
 * ServiceLoader entry point for the directive family:
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor`.
 *
 * Note the asymmetry with the other two contributors, and it is the SPI's shape rather than this
 * plugin's choice: [DirectiveContributor] carries no `id`. Identity for a directive comes from
 * the key its definition declares, and a second identity on the contributor would be a second
 * answer to "what is this plugin called" that nothing cross-checks.
 */
class UppercaseDirectiveContributor : DirectiveContributor {
    override fun definitions(): List<DirectiveDefinitionAny> =
        listOf(ErasedDirectiveDefinition(UppercaseCasedOnDirectiveDefinition))
}
