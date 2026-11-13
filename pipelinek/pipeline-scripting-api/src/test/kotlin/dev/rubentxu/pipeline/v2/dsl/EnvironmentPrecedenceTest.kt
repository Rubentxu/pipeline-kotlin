package dev.rubentxu.pipeline.v2.dsl

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WHICH declaration wins, as the DSL decides it.
 *
 * ## The guarantee under trial
 *
 * That the declaration of a STAGE wins over the declaration of the PIPELINE
 * for the name both declare, and that the stage keeps the values of the names
 * it does not declare.
 *
 * ## Why the rule is NAME BY NAME
 *
 * Because a stage that declares a variable of its own keeps it, and a stage
 * that declares none runs with the variables the pipeline declares. The rule
 * is decided one name at a time, so the two facts live together: the stage
 * wins its own name and inherits the others.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the catalogue publishes this rule: that is a fact
 * about the published text, and the row that pins it lives with the
 * publication.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EnvironmentPrecedenceTest {

    /**
     * The declaration of the stage wins the name both declare.
     */
    @Test
    @DisplayName("the declaration of the stage wins the name both declare")
    fun theDeclarationOfTheStageWinsTheNameBothDeclare() {
        var seen: String? = null

        pipeline {
            environment { env(PROBE, "pipeline") }
            stages {
                stage("s") {
                    environment { env(PROBE, "stage") }
                    seen = env(PROBE)
                }
            }
        }

        assertEquals(
            "stage",
            seen,
            "the stage declares the name and the stage wins it: the declaration of the pipeline " +
                "does not reach a stage that declares the same name",
        )
    }

    /**
     * The stage keeps the values of the names it does not declare.
     */
    @Test
    @DisplayName("the stage keeps the values of the names it does not declare")
    fun theStageKeepsTheValuesOfTheNamesItDoesNotDeclare() {
        var seen: String? = null

        pipeline {
            environment { env(OTHER, "pipeline") }
            stages {
                stage("s") {
                    environment { env(PROBE, "stage") }
                    seen = env(OTHER)
                }
            }
        }

        assertEquals(
            "pipeline",
            seen,
            "the rule is decided name by name: the stage keeps the value of the name it does " +
                "not declare",
        )
    }

    private companion object {
        /**
         * The name the two levels declare.
         *
         * It is a name nobody has: a row that reads a variable the PROCESS
         * happens to carry would measure the process and not the DSL.
         */
        const val PROBE = "PIPELINEK_PRECEDENCE_PROBE"

        /** The name only the pipeline declares. */
        const val OTHER = "PIPELINEK_PRECEDENCE_OTHER"
    }
}
