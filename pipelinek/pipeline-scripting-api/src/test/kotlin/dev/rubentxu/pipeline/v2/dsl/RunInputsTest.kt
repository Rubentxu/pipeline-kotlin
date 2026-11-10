package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parameters a run was started with, checked against the declaration of the pipeline.
 *
 * ## The guarantee under trial
 *
 * That a set of parameters is either ACCEPTED whole or REFUSED whole, and that the refusal
 * says WHICH rule it broke. A run that received half of the values it was asked for is a
 * run that ran with something nobody asked for, and a caller that cannot tell a refusal from
 * a partial acceptance cannot act on either.
 *
 * ## Why the rules are tested HERE and not in the caller
 *
 * Because there are three callers —the service, which checks the values against the
 * contract the client carries; the child, which checks them against the declaration of the
 * SCRIPT; and the command line, which checks the values the operator typed— and the rules
 * live in ONE type. A test here is a test of all three, because all three ask this type.
 *
 * ## What the rows are partitioned by
 *
 * By the rule each row breaks, and by the ORDER the rules are applied in: a row that breaks
 * two rules at once says which one wins, because the order is part of the contract and not
 * an implementation detail.
 */
class RunInputsTest {

    private val declaration = listOf(
        ParameterSpec(name = "target", type = RunInputs.TYPE_STRING, defaultValue = "staging"),
        ParameterSpec(name = "dryRun", type = RunInputs.TYPE_BOOLEAN),
        ParameterSpec(name = "mode", type = RunInputs.TYPE_CHOICE, choices = listOf("fast", "safe")),
    )

    @Test
    fun `a pipeline that declares no parameters accepts a request that carries none`() {
        val outcome = RunInputs.of(emptyList(), emptyMap())

        val accepted = assertInstanceOf(RunInputsOutcome.Accepted::class.java, outcome)
        assertTrue(accepted.inputs.isEmpty)
        assertEquals(emptyMap<String, String>(), accepted.inputs.values)
    }

    @Test
    fun `a parameter without a default has to be delivered`() {
        val outcome = RunInputs.of(declaration, mapOf("target" to "prod"))

        val refused = assertInstanceOf(RunInputsOutcome.Refused::class.java, outcome)
        assertEquals("the-parameter-is-missing", refused.refusal.reason)
        assertEquals("dryRun", (refused.refusal as RunInputsRefusal.TheParameterIsMissing).parameter)
    }

    @Test
    fun `a parameter with a default takes the default when nothing is delivered`() {
        val outcome = RunInputs.of(declaration, mapOf("dryRun" to "false", "mode" to "fast"))

        val accepted = assertInstanceOf(RunInputsOutcome.Accepted::class.java, outcome)
        assertEquals("staging", accepted.inputs.values["target"])
        assertEquals("false", accepted.inputs.values["dryRun"])
        assertEquals("fast", accepted.inputs.values["mode"])
    }

    @Test
    fun `a value that does not fit the type of its parameter is refused`() {
        val notABoolean = RunInputs.of(declaration, mapOf("dryRun" to "yes", "mode" to "fast"))
        val refused = assertInstanceOf(RunInputsOutcome.Refused::class.java, notABoolean)
        assertEquals("the-parameter-has-the-wrong-type", refused.refusal.reason)
        assertEquals("dryRun", (refused.refusal as RunInputsRefusal.TheValueHasTheWrongType).parameter)

        val notAChoice = RunInputs.of(declaration, mapOf("dryRun" to "true", "mode" to "turbo"))
        val refusedChoice = assertInstanceOf(RunInputsOutcome.Refused::class.java, notAChoice)
        assertEquals("the-parameter-has-the-wrong-type", refusedChoice.refusal.reason)
        assertEquals("mode", (refusedChoice.refusal as RunInputsRefusal.TheValueHasTheWrongType).parameter)
    }

    @Test
    fun `the boolean of a parameter accepts true and false and nothing else`() {
        assertInstanceOf(
            RunInputsOutcome.Accepted::class.java,
            RunInputs.of(declaration, mapOf("dryRun" to "true", "mode" to "fast")),
        )
        assertInstanceOf(
            RunInputsOutcome.Accepted::class.java,
            RunInputs.of(declaration, mapOf("dryRun" to "false", "mode" to "fast")),
        )
        assertInstanceOf(
            RunInputsOutcome.Refused::class.java,
            RunInputs.of(declaration, mapOf("dryRun" to "True", "mode" to "fast")),
        )
        assertInstanceOf(
            RunInputsOutcome.Refused::class.java,
            RunInputs.of(declaration, mapOf("dryRun" to "1", "mode" to "fast")),
        )
    }

    @Test
    fun `a name that the pipeline does not declare is refused`() {
        val outcome = RunInputs.of(declaration, mapOf("dryRun" to "true", "mode" to "fast", "extra" to "x"))

        val refused = assertInstanceOf(RunInputsOutcome.Refused::class.java, outcome)
        assertEquals("the-parameter-is-unknown", refused.refusal.reason)
        assertEquals("extra", (refused.refusal as RunInputsRefusal.TheParameterIsUnknown).parameter)
    }

    @Test
    fun `a name that belongs to the product is refused`() {
        val outcome = RunInputs.of(declaration, mapOf("PIPELINE_X" to "x"))

        val refused = assertInstanceOf(RunInputsOutcome.Refused::class.java, outcome)
        assertEquals("the-parameter-name-is-reserved", refused.refusal.reason)
    }

    @Test
    fun `a name that is not the shape of a name is refused`() {
        val outcome = RunInputs.of(declaration, mapOf("1LEADING_DIGIT" to "x"))

        val refused = assertInstanceOf(RunInputsOutcome.Refused::class.java, outcome)
        assertEquals("the-parameter-name-is-not-legal", refused.refusal.reason)
    }

    @Test
    fun `a name that breaks the shape AND belongs to the product is refused by the shape`() {
        // The two rules are two WORDS, and the order between them is part of
        // the contract: the shape is the rule of the alphabet and the prefixes
        // are the rule of the product's own namespaces, and the first one
        // checked is the one that is answered.
        val outcome = RunInputs.of(declaration, mapOf("1PIPELINE_X" to "x"))

        val refused = assertInstanceOf(RunInputsOutcome.Refused::class.java, outcome)
        assertEquals("the-parameter-name-is-not-legal", refused.refusal.reason)
    }

    @Test
    fun `a declaration the product cannot check against is refused`() {
        val unknownType = RunInputs.of(
            listOf(ParameterSpec(name = "count", type = "int")),
            emptyMap(),
        )
        val refusedType = assertInstanceOf(RunInputsOutcome.Refused::class.java, unknownType)
        assertEquals("the-declaration-is-not-legal", refusedType.refusal.reason)

        val twice = RunInputs.of(
            listOf(
                ParameterSpec(name = "target", type = RunInputs.TYPE_STRING),
                ParameterSpec(name = "target", type = RunInputs.TYPE_STRING),
            ),
            emptyMap(),
        )
        val refusedTwice = assertInstanceOf(RunInputsOutcome.Refused::class.java, twice)
        assertEquals("the-declaration-is-not-legal", refusedTwice.refusal.reason)

        val closedWithoutChoices = RunInputs.of(
            listOf(ParameterSpec(name = "mode", type = RunInputs.TYPE_CHOICE)),
            emptyMap(),
        )
        val refusedClosed = assertInstanceOf(RunInputsOutcome.Refused::class.java, closedWithoutChoices)
        assertEquals("the-declaration-is-not-legal", refusedClosed.refusal.reason)

        val openWithChoices = RunInputs.of(
            listOf(
                ParameterSpec(
                    name = "target",
                    type = RunInputs.TYPE_STRING,
                    choices = listOf("a"),
                ),
            ),
            emptyMap(),
        )
        val refusedOpen = assertInstanceOf(RunInputsOutcome.Refused::class.java, openWithChoices)
        assertEquals("the-declaration-is-not-legal", refusedOpen.refusal.reason)
    }

    @Test
    fun `a default that does not fit its own type is a defect of the declaration`() {
        // The caller cannot fix a default the author wrote, and a refusal that named the
        // caller would send it to fix something it never wrote.
        val outcome = RunInputs.of(
            listOf(
                ParameterSpec(
                    name = "mode",
                    type = RunInputs.TYPE_CHOICE,
                    defaultValue = "turbo",
                    choices = listOf("fast", "safe"),
                ),
            ),
            emptyMap(),
        )

        val refused = assertInstanceOf(RunInputsOutcome.Refused::class.java, outcome)
        assertEquals("the-declaration-is-not-legal", refused.refusal.reason)
        assertTrue(refused.refusal.detail.contains("turbo"), "the refusal names the default")
    }

    @Test
    fun `the order of the checks is the contract and a name that breaks two rules says which one wins`() {
        // The name belongs to the product AND the pipeline does not declare it.
        // The reserved name wins, because the shape of a name is checked before the
        // declaration is read.
        val outcome = RunInputs.of(declaration, mapOf("PIPELINE_X" to "x"))

        val refused = assertInstanceOf(RunInputsOutcome.Refused::class.java, outcome)
        assertEquals("the-parameter-name-is-reserved", refused.refusal.reason)
    }

    @Test
    fun `the canonical text tells two requests that differ in one value apart`() {
        val first = RunInputs.canonicalText(declaration, mapOf("target" to "prod"))
        val second = RunInputs.canonicalText(declaration, mapOf("target" to "stage"))

        assertNotEquals(first, second)
    }

    @Test
    fun `the canonical text tells a request that differs in the declaration apart`() {
        val withDefault = listOf(
            ParameterSpec(name = "target", type = RunInputs.TYPE_STRING, defaultValue = "staging"),
        )
        val withoutDefault = listOf(ParameterSpec(name = "target", type = RunInputs.TYPE_STRING))

        assertNotEquals(
            RunInputs.canonicalText(withDefault, mapOf("target" to "prod")),
            RunInputs.canonicalText(withoutDefault, mapOf("target" to "prod")),
        )
    }

    @Test
    fun `the canonical text of nothing is empty`() {
        assertEquals("", RunInputs.canonicalText(emptyList(), emptyMap()))
    }

    @Test
    fun `the canonical text cannot be imitated by a value that carries a separator`() {
        val both = listOf(
            ParameterSpec(name = "a", type = RunInputs.TYPE_STRING),
            ParameterSpec(name = "b", type = RunInputs.TYPE_STRING),
        )
        val first = RunInputs.canonicalText(both, mapOf("a" to "1", "b" to "2"))
        val second = RunInputs.canonicalText(both, mapOf("a" to "1\nb=2"))

        assertNotEquals(first, second)
    }
}
