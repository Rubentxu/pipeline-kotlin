package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.directive.AgentLabel
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The authoring surface of the agent, and what is left of it.
 *
 * ## The guarantee under trial
 *
 * That the language does not offer a way to AUTHOR an agent, at either level,
 * and that the requirement the agent carried is still a first-class thing the
 * plan can carry through the generic seam.
 *
 * ## Why the surface is measured and not the behaviour
 *
 * Because the surface is a CONTRACT: it is what a published artifact offers
 * to the author, and a reader of the release notes learns that `agent(...)` is
 * gone by the method not being there. A behaviour row could not tell that
 * apart from a method that exists and refuses — and the second one is exactly
 * the shape of a deprecation, which this change is not.
 *
 * The methods are read by REFLECTION, over the same classes `apiCheck` reads,
 * so the row and the ABI gate cannot disagree about what the class declares.
 *
 * ## What survives, and why the row is here
 *
 * The DIRECTIVE survives: `core.agent` is still a registered key with its own
 * codec, its own definition and its own resolver, and the generic
 * `directives { }` seam is still a way to carry it. What disappears is the
 * VERB: the language no longer offers a spelling for it.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that a requirement nobody declares is satisfied: that is
 * the runtime's decision, measured where the runtime decides it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AgentAuthoringRetiredTest {

    /**
     * The stage declares no way to author an agent.
     */
    @Test
    @DisplayName("the stage scope declares no method that authors an agent")
    fun theStageScopeDeclaresNoMethodThatAuthorsAnAgent() {
        assertEquals(
            emptyList<String>(),
            authoringMethodsOf(StageScope::class.java),
            "the stage scope still offers a way to author an agent: the language has to stop " +
                "offering it, and a method that stays is a method an author can still call",
        )
    }

    /**
     * The pipeline declares no way to author an agent either.
     *
     * The other level, with the same instrument: a surface that survives at
     * one level and not at the other is a surface that is half retired.
     */
    @Test
    @DisplayName("the pipeline scope declares no method that authors an agent")
    fun thePipelineScopeDeclaresNoMethodThatAuthorsAnAgent() {
        assertEquals(
            emptyList<String>(),
            authoringMethodsOf(PipelineScope::class.java),
            "the pipeline scope still offers a way to author an agent: the language has to stop " +
                "offering it, and a method that stays is a method an author can still call",
        )
    }

    /**
     * The scope that opened the agent block is gone.
     *
     * The block was a second spelling of the same declaration, and it needed a
     * scope of its own to hold the option of Jenkins. With the block gone the
     * scope has no reader, and a class that stays is a class a plugin could
     * still reach.
     */
    @Test
    @DisplayName("the scope that opened the agent block is gone")
    fun theScopeThatOpenedTheAgentBlockIsGone() {
        assertThrows(
            ClassNotFoundException::class.java,
            { Class.forName(AGENT_SCOPE_CLASS) },
            "the scope that opened the agent block still exists: the block is gone, and a scope " +
                "with no block is a class the published artifact carries for nothing",
        )
    }

    /**
     * A plan that declares the requirement through the generic seam still
     * carries it.
     *
     * The characterization that says what SURVIVED: the language stopped
     * offering the verb, and the noun it carried is still a first-class thing
     * the plan carries, with a payload the codec still reads.
     */
    @Test
    @DisplayName("a plan that declares the requirement through the generic seam still carries it")
    fun aPlanThatDeclaresTheRequirementThroughTheGenericSeamStillCarriesIt() {
        val encoded = ExecutionTargetRequirementCodec.encode(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("linux"))),
        )

        val spec = pipeline {
            stages {
                stage("build") {
                    directives { directive(AGENT_DIRECTIVE_KEY.value, encoded) }
                    sh("./build.sh")
                }
            }
        }

        val carried = spec.stages.single().directives.single { directive ->
            directive.key == AGENT_DIRECTIVE_KEY.value
        }
        assertEquals(
            encoded,
            carried.encodedArguments,
            "the requirement the plan carries is the one the author declared: the generic seam " +
                "carries it without changing it",
        )

        val decoded = assertInstanceOf(
            DirectiveDecodeResult.Decoded::class.java,
            ExecutionTargetRequirementCodec.decode(carried.encodedArguments),
            "the payload of the requirement is still readable by the codec that owns it: the " +
                "contract of the directive survived the removal of the way to write it",
        )
        assertEquals(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("linux"))),
            decoded.input,
            "the requirement the codec reads is the one the author declared",
        )
    }

    /**
     * The methods of [type] that author an agent.
     *
     * The synthetic `agent$default` bridge the compiler adds for a method
     * with default arguments is an authoring method too: it is the one a
     * caller reaches when it writes `agent("linux")`.
     */
    private fun authoringMethodsOf(type: Class<*>): List<String> =
        type.methods.map { method -> method.name }
            .filter { name ->
                AUTHORING_NAMES.any { authored -> name == authored || name.startsWith("$authored\$") }
            }
            .sorted()

    private companion object {

        /** The names the authoring surface carried. */
        val AUTHORING_NAMES = listOf("agent", "agentAny", "agentWithCapabilities")

        /** The scope that opened the agent block, by its published name. */
        const val AGENT_SCOPE_CLASS = "dev.rubentxu.pipeline.v2.dsl.AgentScope"
    }
}
