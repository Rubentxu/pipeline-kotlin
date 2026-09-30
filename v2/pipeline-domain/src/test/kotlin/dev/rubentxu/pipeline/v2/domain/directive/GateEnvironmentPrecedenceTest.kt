package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The precedence between a stage's declared environment and the host process
 * environment for a gate decision.
 *
 * The rule is asserted here as a pure function because it is a DECISION: getting
 * it wrong is not a wiring bug, it is the gate reading the wrong fact.
 */
class GateEnvironmentPrecedenceTest {
    @Test
    fun `a stage declaration overrides a host variable of the same name`() {
        val resolved = GateEnvironmentPrecedence.DeclarationWins.resolve(
            declared = mapOf("DEPLOY_ENV" to "prod"),
            host = mapOf("DEPLOY_ENV" to "staging"),
        )

        assertEquals(
            GateContext.Resolution.Present("prod"),
            resolved.lookup("DEPLOY_ENV"),
            "the value the author wrote in the stage must be the value the gate reads",
        )
    }

    @Test
    fun `a host value fills a declared name the stage left without a literal`() {
        val resolved = GateEnvironmentPrecedence.DeclarationWins.resolve(
            declared = mapOf("DEPLOY_ENV" to ""),
            host = mapOf("DEPLOY_ENV" to "staging"),
        )

        // An explicitly empty declaration is a decision, not a gap to fill from
        // the host: the author wrote "" and gets "".
        assertEquals(
            GateContext.Resolution.Present(""),
            resolved.lookup("DEPLOY_ENV"),
        )
    }

    @Test
    fun `an undeclared name is never read from the host`() {
        val resolved = GateEnvironmentPrecedence.DeclarationWins.resolve(
            declared = mapOf("DEPLOY_ENV" to "prod"),
            host = mapOf("AWS_SECRET_ACCESS_KEY" to "leaked"),
        )

        assertTrue(
            "AWS_SECRET_ACCESS_KEY" !in resolved.values,
            "a gate must not observe an ambient variable the stage never declared",
        )
        assertEquals(GateContext.Resolution.Unset, resolved.lookup("AWS_SECRET_ACCESS_KEY"))
    }

    @Test
    fun `an empty declaration set yields an empty world, not the whole host`() {
        val resolved = GateEnvironmentPrecedence.DeclarationWins.resolve(
            declared = emptyMap(),
            host = mapOf("PATH" to "/usr/bin", "HOME" to "/root"),
        )

        assertTrue(resolved.values.isEmpty(), "expected no visible values, got ${resolved.values}")
    }
}
