package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.runtime.durable.NonCanonicalStep
import dev.rubentxu.pipeline.v2.runtime.durable.analyzeCanonicalDurableExecution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The refusal of a step, and what it tells the author to write.
 *
 * ## The guarantee under trial
 *
 * That the refusal of a step the canonical bridge cannot execute names the
 * FORM of this product that replaces it, when this product owns one — and
 * that it invents none when it does not.
 *
 * ## Why the chain is under trial and not a function
 *
 * Because the fact the author reads is decided in three places, and each of
 * them can be right on its own: the DSL lowers the form of Jenkins to a key,
 * the admission decides that no registered definition owns that key, and the
 * refusal turns that decision into words. A row that measured only the last
 * one would pass with a table that names a form the product does not have,
 * and a row that measured only the first would say nothing about what the
 * author reads.
 *
 * ## Why this matters for the author of a migrated script
 *
 * Because the author of a Jenkins script writes the `node` of Jenkins, the
 * script COMPILES, and the run refuses it with a rule and an id the author
 * never wrote. This product does NOT allocate an executor on an agent: the
 * run executes on the machine where it started. A refusal that says so tells
 * the author what to do with the form they wrote; a refusal that names a form
 * this product does not have sends the author looking for it.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the step is refused FOR THE SAKE of the refusal:
 * the refusal is the published behaviour, and this file does not change it.
 * It affirms what the refusal SAYS.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RefusedStepFormTest {

    /**
     * The refusal of the node of Jenkins says that there is no agent to
     * allocate.
     *
     * The chain is walked whole: the DSL lowers the node to its key, the
     * admission refuses that key, and the refusal says what this product
     * does with the form.
     *
     * The refusal names NO form to write, because there is none: the language
     * does not offer a way to declare an agent, and a refusal that named one
     * would send the author looking for a form this product does not have.
     */
    @Test
    @DisplayName("the refusal of the node of Jenkins says there is no agent to allocate")
    fun theRefusalOfTheNodeOfJenkinsSaysThereIsNoAgentToAllocate() {
        val refused = refusedStepsOf(
            StepSpec.NodeNoOp(label = null, steps = listOf(StepSpec.Echo("hola"))),
        )

        assertEquals(
            listOf("core.node"),
            refused.map { step -> step.pluginStepId },
            "the node of Jenkins lowers to the key 'core.node', and that is the key the " +
                "admission refuses",
        )

        val lines = nonCanonicalRefusalLines(refused)

        assertFalse(
            lines.any { line -> line.contains("agent(...)") },
            "the refusal names a form the language does not have: an author who reads " +
                "'write agent(...)' on a version that removed it loses a second time. The " +
                "lines were $lines",
        )
        assertTrue(
            lines.any { line -> line.contains("does not allocate") },
            "the refusal says the truth about what this product does with a node: it does not " +
                "allocate an executor on an agent, and the run executes on the machine where it " +
                "started. The lines were $lines",
        )
        assertTrue(
            lines.any { line -> line.contains("node") },
            "the refusal names the form of Jenkins that it is refusing, so the author can " +
                "recognise the form they wrote. The lines were $lines",
        )
    }

    /**
     * A refused step that this product has NO form for gets no suggestion.
     *
     * The control of the row above: a table that names a form for EVERY
     * refused step invents one, and an invented form is worse than none,
     * because the author trusts it.
     */
    @Test
    @DisplayName("a refused step that this product has no form for gets no suggestion")
    fun aRefusedStepThatThisProductHasNoFormForGetsNoSuggestion() {
        val refused = refusedStepsOf(
            StepSpec.AnsiColor(colorMapName = "xterm", steps = listOf(StepSpec.Echo("hola"))),
        )

        assertEquals(
            1,
            refused.size,
            "the colour of the output is refused too: this product has no step that owns " +
                "that key",
        )

        val lines = nonCanonicalRefusalLines(refused)

        assertFalse(
            lines.any { line -> line.contains("In PipelineK:") },
            "a step this product has no form for is refused with the rule and the step, " +
                "and with NOTHING else: the table does not invent a form. The lines were " +
                "$lines",
        )
        assertTrue(
            lines.any { line -> line.contains("core.ansiColor") },
            "the refusal still names the step it refuses. The lines were $lines",
        )
    }

    /**
     * A plan composed before the retirement is still refused.
     *
     * The retirement removed the AUTHORING surface, not the representation: a
     * plan composed before it — a journal, a plan on disk, a plan a plugin
     * composed — still carries the key, and the admission still refuses it in
     * closed. The refusal has to be the one that plan always got: a plan that
     * opened and then ran the steps the wrapper wrapped would be the silent
     * semantic drop this product exists to remove.
     */
    @Test
    @DisplayName("a plan composed before the retirement is still refused")
    fun aPlanComposedBeforeTheRetirementIsStillRefused() {
        val refused = listOf(
            StepSpec.NodeNoOp(label = null, steps = listOf(StepSpec.Echo("hola"))),
            StepSpec.AnsiColor(colorMapName = "xterm", steps = listOf(StepSpec.Echo("hola"))),
            StepSpec.Load(path = "otro.pipeline.kts"),
        ).flatMap { step -> refusedStepsOf(step) }

        assertEquals(
            listOf("core.ansiColor", "core.load", "core.node"),
            refused.map { step -> step.pluginStepId }.sorted(),
            "a plan composed before the retirement is not refused the way it always was: the " +
                "retirement has to move the rejection to the authoring, not remove it",
        )
    }

    /**
     * The steps the canonical admission refuses, for a script that writes ONE step.
     *
     * The script is compiled the way the product compiles it, and the analysis
     * is the one the run makes: the row walks both, and does not rewrite
     * either.
     */
    private fun refusedStepsOf(step: StepSpec): List<NonCanonicalStep> {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = listOf(StageSpec(name = "build", steps = listOf(step)))),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        return compiled.analyzeCanonicalDurableExecution()
    }
}
