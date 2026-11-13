package dev.rubentxu.pipeline.v2.dsl

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The wrappers of Jenkins this product does not have, and what is left of them.
 *
 * ## The guarantee under trial
 *
 * That the language does not ACCEPT a wrapper it cannot execute: no `node`,
 * no `ansiColor`, no `load`, and no step-level `retry` that projects a policy
 * nobody reads. The language still offers the block form of `retry`, which the
 * coordinator honours, and the REPRESENTATION of the retired forms still
 * exists, because a plan composed before the retirement has to keep decoding.
 *
 * ## Why the surface is measured and not the behaviour
 *
 * Because the surface is a CONTRACT: it is what a published artifact offers to
 * the author, and a reader of the release notes learns that `node { }` is gone
 * by the method not being there. A behaviour row could not tell that apart
 * from a method that exists and refuses — and the refusal is not what this
 * change makes: a form that cannot work is not written at all.
 *
 * The methods are read by REFLECTION, over the same classes `apiCheck` reads,
 * so the row and the ABI gate cannot disagree about what the class declares.
 *
 * ## Why the representation is measured in the same file
 *
 * Because the two facts are ONE change: the authoring surface is gone AND the
 * representation is still there. A file that measured only the first would
 * pass with a representation deleted, and a plan composed before the
 * retirement would stop decoding — which is the one outcome this change must
 * not produce.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that a plan carrying a retired key is refused at
 * admission, and it does not affirm that the `git` shortcut executes: those
 * are decisions the runtime makes, measured where the runtime makes them.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RetiredJenkinsWrapperTest {

    /**
     * The stage declares no way to write a wrapper it cannot execute.
     *
     * The three forms are measured together because they are one fact: the
     * language accepted a script that compiled and was then refused by the
     * run, and an author learned that at the worst possible moment.
     */
    @Test
    @DisplayName("the stage scope declares no method that writes a wrapper it cannot execute")
    fun theStageScopeDeclaresNoMethodThatWritesAWrapperItCannotExecute() {
        assertEquals(
            emptyList<String>(),
            methodsNamed(StageScope::class.java, RETIRED_AUTHORING_NAMES),
            "the stage scope still offers a wrapper this product cannot execute: the language " +
                "has to stop accepting it, and a method that stays is a method an author can " +
                "still call",
        )
    }

    /**
     * The step-level `retry` retrofit refuses, and it says what to write.
     *
     * This is the one form of the four that the language still DECLARES, and
     * the reason is measured: its route does not continue. It refuses while
     * the script is being written, so the language never accepts it, and a
     * form the language does not accept is not a wrapper it offers.
     *
     * The refusal is worth keeping: it names the block form —the one the
     * coordinator executes— instead of leaving the author with an overload
     * error the compiler produces.
     */
    @Test
    @DisplayName("the step-level retry retrofit refuses and names the form that works")
    fun theStepLevelRetryRetrofitRefusesAndNamesTheFormThatWorks() {
        val scope = StageScope("stage")

        val refusal = assertThrows(
            IllegalArgumentException::class.java,
            { scope.retry(count = 3, delaySeconds = 5L) },
            "the step-level retry retrofit no longer refuses: the language would accept a " +
                "delay that nothing honours",
        )

        assertTrue(
            refusal.message?.contains("retry(3) { ... }") == true,
            "the refusal does not name the form that works: the author is left without a way " +
                "forward. The message was ${refusal.message}",
        )
    }

    /**
     * The block form of `retry` survives.
     *
     * The control of the row above, and the reason the two are not the same
     * decision: this form lowers to a block step the coordinator DOES execute,
     * so retiring it would retire a capability that works.
     */
    @Test
    @DisplayName("the block form of retry survives")
    fun theBlockFormOfRetrySurvives() {
        val retries = StageScope::class.java.methods.filter { method -> method.name == "retry" }

        assertTrue(
            retries.any { method ->
                method.parameterTypes.map { type -> type.name } ==
                    listOf("int", "kotlin.jvm.functions.Function1")
            },
            "the block form of retry is the one form of it this product executes, and it has " +
                "to stay declared: the declared retries are " +
                "${retries.map { method -> method.parameterTypes.map { type -> type.name }} }",
        )
    }

    /**
     * The representation of the retired forms survives.
     *
     * A plan composed before the retirement — a journal, a plan on disk, a plan
     * a plugin composed — still carries these representations, and it has to
     * keep decoding: the refusal it gets at admission is the one it always
     * got, and deleting the representation would turn that refusal into a
     * plan that does not open.
     */
    @Test
    @DisplayName("the representation of the retired forms survives")
    fun theRepresentationOfTheRetiredFormsSurvives() {
        listOf(
            StepSpec.NodeNoOp::class.java,
            StepSpec.AnsiColor::class.java,
            StepSpec.Load::class.java,
        ).forEach { representation ->
            assertTrue(
                representation.name.isNotBlank(),
                "the representation of a retired form is missing: a plan composed before the " +
                    "retirement has to keep decoding",
            )
        }
    }

    /**
     * The methods of [type] whose name is one of [names], with the synthetic
     * bridge the compiler adds for a default argument.
     */
    private fun methodsNamed(type: Class<*>, names: List<String>): List<String> =
        type.methods.map { method -> method.name }
            .filter { name -> names.any { authored -> name == authored || name.startsWith("$authored\$") } }
            .sorted()

    private companion object {

        /** The names the authoring surface carried and must not carry. */
        val RETIRED_AUTHORING_NAMES = listOf("node", "ansiColor", "load")
    }
}
