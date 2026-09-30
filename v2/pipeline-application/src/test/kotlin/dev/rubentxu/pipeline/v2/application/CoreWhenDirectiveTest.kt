package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEncoder
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The bundled `core.when` definition.
 *
 * These rows live with the definition because the definition is a CORE PLUGIN
 * instance, not part of the S1 directive kernel. The kernel is scanned by
 * fitness and must stay free of concrete names; a core directive's own identity
 * is a composition fact and belongs beside the other `core.*` definitions.
 */
class CoreWhenDirectiveTest {

    @Test
    @DisplayName("S2A-CODEC-001: the definition is core.when, BEFORE_STAGE, declared as a Gate")
    fun definitionIdentity() {
        val definition = WhenDirectiveDefinition()
        assertEquals(DirectiveKey("core.when"), definition.key)
        assertEquals(DirectiveKey("core.when"), WHEN_DIRECTIVE_KEY)
        assertEquals(DirectivePhase.BEFORE_STAGE, definition.phase)
        assertEquals(DirectiveExecutionPolicy.Gate("core.when"), definition.policy)
    }

    @Test
    @DisplayName("S2A-CODEC-012: the definition's decode is the codec, so the engine reads one format")
    fun definitionUsesTheSameCodec() {
        val definition = WhenDirectiveDefinition()
        val predicate = WhenPredicate.AllOf(
            listOf(WhenPredicate.VariableEquals("A", "1"), WhenPredicate.Not(WhenPredicate.AlwaysTrue)),
        )
        val encoded = WhenPredicateEncoder.encode(predicate)
        assertEquals(
            DirectiveDecodeResult.Decoded(predicate),
            definition.decode(encoded),
        )
    }
}
