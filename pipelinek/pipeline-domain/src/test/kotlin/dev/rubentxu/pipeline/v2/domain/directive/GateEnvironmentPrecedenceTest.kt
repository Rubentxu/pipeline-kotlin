package dev.rubentxu.pipeline.v2.domain.directive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What a gate can see, and what it cannot.
 *
 * ## The guarantee under trial
 *
 * That the gate reads the variables the SCRIPT declares, and nothing else. The
 * ambient environment of the host is read only for the names the script
 * declares, so a gate can never observe an arbitrary variable of the agent by
 * guessing its name — and the declaration of the script wins over the value the
 * agent happens to have for the same name.
 *
 * ## Why this matters to a reader who migrates a Jenkinsfile
 *
 * Because this is the rule behind a published divergence: *"a variable that
 * only the agent has is not visible to the gate"*. A rule that no row
 * exercises is a rule that can change without anybody noticing, and the
 * published text would keep saying it.
 *
 * ## What this instrument does not freeze
 *
 * The ambient read itself. The host environment arrives here already captured
 * as a map — reading it is the adapter's job, and the composition root does it
 * — so this row is about the rule, not about the process.
 */
class GateEnvironmentPrecedenceTest {

    /**
     * A variable that only the agent has does not reach the gate.
     *
     * The host carries `BRANCH_NAME`, and the script declares nothing: the
     * gate sees no value for it at all. The name is UNSET, which is a decided
     * negative, and not a value that happens to be missing.
     */
    @Test
    @DisplayName("a variable that only the agent has does not reach the gate")
    fun aVariableThatOnlyTheAgentHasDoesNotReachTheGate() {
        val context = GateEnvironmentPrecedence.DEFAULT.resolve(
            declared = emptyMap(),
            host = mapOf("BRANCH_NAME" to "main"),
        )

        assertTrue(
            context.values.isEmpty(),
            "the gate carries a variable that nobody declared: ${context.values}",
        )
        assertEquals(
            GateContext.Resolution.Unset,
            context.lookup("BRANCH_NAME"),
            "a variable that only the agent has is not visible to the gate",
        )
    }

    /**
     * The declaration of the script wins over the value the agent has.
     *
     * The host carries `BRANCH_NAME=feature` and the script declares
     * `BRANCH_NAME=main`: the gate reads `main`, because the same script has
     * to evaluate the same way on two machines for a reason the author wrote
     * down.
     */
    @Test
    @DisplayName("a declared variable reaches the gate, and the declaration wins")
    fun aDeclaredVariableReachesTheGateAndTheDeclarationWins() {
        val context = GateEnvironmentPrecedence.DEFAULT.resolve(
            declared = mapOf("BRANCH_NAME" to "main"),
            host = mapOf("BRANCH_NAME" to "feature"),
        )

        assertEquals(
            GateContext.Resolution.Present("main"),
            context.lookup("BRANCH_NAME"),
            "the declaration of the script wins over the variable the agent has",
        )
    }
}
