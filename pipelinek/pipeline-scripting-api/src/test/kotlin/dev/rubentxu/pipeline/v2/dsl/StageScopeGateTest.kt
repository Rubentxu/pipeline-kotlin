package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateCodec
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The gate a script declares, and the directive the runtime reads.
 *
 * ## The guarantee under trial
 *
 * That `whenEnvIs("X", "value")` declares the gate the runtime evaluates: the
 * directive `core.when`, carrying the predicate that names the variable and
 * the value. The rule the gate follows —which variables it can see, and what
 * it does with a variable nobody declares— lives in the domain and in the
 * runtime, and this row certifies the half that lives HERE: that the author's
 * call arrives at the runtime as the directive it reads.
 *
 * ## Why this is a row and not a reading of the source
 *
 * Because the two halves live in two modules, and a gate that the DSL spelled
 * differently from the one the runtime decodes would leave every script that
 * uses it silently ungated: the stage would run whatever the predicate said.
 * The key travels from `...domain.directivekey`, so both halves read the same
 * name — and this row says that the NAME is what travels.
 *
 * ## What this instrument does not freeze
 *
 * The rule of the gate, and what the runtime does with the verdict. Those are
 * the domain's rows and the runtime's.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StageScopeGateTest {

    /**
     * The gate a script declares is the directive the runtime reads.
     */
    @Test
    fun `the gate a script declares is the directive the runtime reads`() {
        val spec = pipeline {
            stages {
                stage("build") {
                    whenEnvIs("BRANCH_NAME", "main")
                    echo("hello")
                }
            }
        }

        val gates = spec.stages.single().directives
            .filter { it.key == WHEN_DIRECTIVE_KEY.value }
        assertEquals(
            1,
            gates.size,
            "the stage declares ONE gate, and it is the one the runtime reads",
        )

        val decoded = WhenPredicateCodec.decode(gates.single().encodedArguments)
        assertInstanceOf(
            DirectiveDecodeResult.Decoded::class.java,
            decoded,
            "the gate the script declares is a predicate the runtime can read",
        )
        assertEquals(
            WhenPredicate.VariableEquals("BRANCH_NAME", "main"),
            (decoded as DirectiveDecodeResult.Decoded).input,
            "the gate the script declares is the predicate the runtime reads",
        )
    }
}
