package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S2-A: the typed `when` DSL surface.
 *
 * These tests assert what the CONSTRUCTION produces, never what it evaluates.
 * A DSL that evaluated its predicate here would be a fake runtime returning
 * placeholder values, which is the exact defect class the architecture bans.
 */
class WhenDirectiveDslTest {

    private fun stageWith(block: StageScope.() -> Unit) = StageScope("gated").apply(block).toStageBuilder().build()

    /** Decodes a directive's encoded arguments back into a typed predicate. */
    private fun predicateOf(encoded: String): Any? =
        (WhenPredicateCodec.decode(encoded)
            as? dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded)?.input

    @Test
    @DisplayName("S2A-DSL-001: whenEnvIs encodes the predicate as a directive, not as text")
    fun whenEnvIsProducesDirective() {
        val stage = stageWith {
            whenEnvIs("DEPLOY_TARGET", "prod")
            echo("deploying")
        }

        assertEquals(1, stage.directives.size)
        val directive = stage.directives.single()
        assertEquals(WHEN_DIRECTIVE_KEY.value, directive.key)
        // The argument is a STRUCTURED encoding: a String predicate could not
        // survive this round trip, which is the whole point.
        assertEquals(
            WhenPredicate.VariableEquals("DEPLOY_TARGET", "prod"),
            predicateOf(directive.encodedArguments),
        )
    }

    @Test
    @DisplayName("S2A-DSL-002: construction performs no I/O and fabricates no runtime value")
    fun constructionIsPure() {
        // A predicate over a variable that certainly does not exist must still
        // construct cleanly. If construction evaluated anything, an unset
        // variable would either throw or be resolved to a placeholder here.
        val stage = stageWith {
            whenEnvIs("PK_VARIABLE_THAT_CANNOT_BE_SET_12345", "expected")
        }

        assertEquals(1, stage.directives.size)
    }

    @Test
    @DisplayName("S2A-DSL-003: a `when` is never silently discarded by a later directives { } block")
    fun whenSurvivesLaterDirectivesBlock() {
        // REGRESSION: `directives { }` used to ASSIGN stageDirectives, which
        // erased any `when` declared earlier in the same stage. The stage then
        // ran unconditionally while the script clearly meant to gate it — a
        // fail-OPEN defect, invisible to every other test.
        val stage = stageWith {
            whenEnvIs("DEPLOY_TARGET", "prod")
            directives {
                directive("vendor.owner@example.com/custom", "1")
            }
        }

        assertEquals(2, stage.directives.size, "the gate must survive the directives block")
        assertTrue(
            stage.directives.any { it.key == WHEN_DIRECTIVE_KEY.value },
            "the gate directive was lost",
        )
        assertTrue(
            stage.directives.any { it.key == "vendor.owner@example.com/custom" },
            "the vendor directive was lost",
        )
    }

    @Test
    @DisplayName("S2A-DSL-004: declaration order is preserved")
    fun declarationOrderIsPreserved() {
        val stage = stageWith {
            whenEnvIs("A", "1")
            directives { directive("vendor.owner@example.com/one", "1") }
            whenEnvPresent("B")
        }

        assertEquals(
            listOf(WHEN_DIRECTIVE_KEY.value, "vendor.owner@example.com/one", WHEN_DIRECTIVE_KEY.value),
            stage.directives.map { it.key },
        )
    }

    @Test
    @DisplayName("S2A-DSL-005: combinators build nested predicates")
    fun combinatorsBuildNestedPredicates() {
        val stage = stageWith {
            whenGate(
                WhenPredicate.AllOf(
                    listOf(
                        WhenPredicate.VariableEquals("A", "1"),
                        WhenPredicate.AnyOf(
                            listOf(
                                WhenPredicate.VariablePresent("B"),
                                WhenPredicate.Not(WhenPredicate.VariablePresent("C")),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals(1, stage.directives.size)
        // Structure survives the encoding: the engine never re-parses text.
        val decoded = predicateOf(stage.directives.single().encodedArguments)
        assertTrue(decoded is WhenPredicate.AllOf, "expected a nested AllOf, got $decoded")
    }

    @Test
    @DisplayName("S2A-DSL-006: a stage without directives stays empty (no accidental default gate)")
    fun ungatedStageHasNoDirectives() {
        val stage = stageWith { echo("always") }
        assertTrue(stage.directives.isEmpty(), "an ungated stage must not acquire a gate")
    }
}
