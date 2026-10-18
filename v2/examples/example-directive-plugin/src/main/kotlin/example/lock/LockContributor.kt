package example.lock

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinitionAny
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition

/**
 * ServiceLoader entry point (the directive analogue of StepDefinitionContributor).
 * Discovered generically by the host: no registration list anywhere in core.
 */
class LockContributor : DirectiveContributor {
    override fun definitions(): List<DirectiveDefinitionAny> =
        listOf(ErasedDirectiveDefinition(LockDirectiveDefinition))
}
