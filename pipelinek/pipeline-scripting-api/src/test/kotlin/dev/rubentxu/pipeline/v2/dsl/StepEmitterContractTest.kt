package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The contract a plugin writes steps through, and the three scopes that
 * implement it.
 *
 * ## The guarantee under trial
 *
 * That a plugin can declare a step WITHOUT the configuration of a stage, and
 * that the scopes that host steps say what they admit:
 *
 * - the contract carries the emission and the composition of steps and
 *   NOTHING else: no agent, no options, no post conditions, no `when`
 *   predicates;
 * - the stage, the branch and the post conditions all implement it, so a
 *   plugin's step has a place to go in each of them;
 * - the two routes of a declaration —the contract's and the historic one—
 *   declare the SAME step, so a script that names either gets the same plan;
 * - the post conditions REFUSE a plugin's step, and the refusal says why.
 *
 * ## What this file does NOT freeze
 *
 * The shape of the `StepSpec` family, the vocabulary of the scopes and the
 * order in which the steps run. This file reads the DECLARED steps.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StepEmitterContractTest {

    /** The key a plugin registers its step under, for these rows. */
    private val key = PluginStepId("example.contract")

    /** The payload of that step, for these rows. */
    private val input = EncodedStepValue("{\"kind\":\"contract\"}")

    /**
     * The three scopes that host steps implement the contract.
     *
     * The stage, the branch and the post conditions are the three places a
     * step can be declared, and a plugin's step needs a place in each of
     * them: a scope that does not implement the contract is a scope where the
     * plugin's constructor does not apply, and that is a difference the author
     * discovers by reading the compiler's error.
     */
    @Test
    @DisplayName("the three scopes that host steps implement the contract")
    fun theThreeScopesThatHostStepsImplementTheContract() {
        val spec = pipeline {
            stages {
                stage("build") {
                    post {
                        always { echo("closing") }
                    }
                    parallel {
                        branch("left") { echo("left") }
                        branch("right") { echo("right") }
                    }
                }
            }
        }

        val stage = spec.stages.single()
        val branch = stage.steps.filterIsInstance<StepSpec.Parallel>().single().branches.first()

        assertTrue(
            stage.steps.isNotEmpty(),
            "the stage declares no steps, so the row measures nothing",
        )
        assertTrue(
            BranchScope() is StepEmitter<*>,
            "the branch scope does not implement the contract",
        )
        assertTrue(
            PostStepsScope() is StepEmitter<*>,
            "the scope of the post conditions does not implement the contract",
        )
        assertTrue(
            branch.steps.isNotEmpty(),
            "the branch declares no steps, so the row measures nothing",
        )
    }

    /**
     * The contract carries the emission and the composition of steps, and
     * nothing else.
     *
     * The contract is what a plugin writes against, so what it carries is
     * what a plugin depends on. A member about the agent, the options, the
     * post conditions or a `when` predicate would put the configuration of
     * the stage inside the constructor of a step, and that is the thing this
     * contract exists to separate.
     *
     * The count is measured, not asserted by hand: the members of the
     * interface are read from the class, so a member added later shows up
     * here without anybody having to remember this row.
     */
    @Test
    @DisplayName("the contract carries the emission and the composition of steps and nothing else")
    fun theContractCarriesTheEmissionAndTheCompositionOfStepsAndNothingElse() {
        // The name a JVM carries is the MANGLED one: the parameters of these
        // members are inline classes, and the JVM name of a member with an
        // inline class in its signature carries a suffix. The Kotlin name is
        // the one before the suffix.
        val members = StepEmitter::class.java.declaredMethods
            .filter { it.parameterCount > 0 }
            .map { it.name.substringBefore('-') }
            .sorted()

        assertEquals(
            listOf("registryBlock", "registryStep"),
            members,
            "the contract declares members that are not the emission and the composition of steps",
        )

        val stageMembers = StepEmitter::class.java.declaredMethods
            .flatMap { method -> method.parameterTypes.map { it.simpleName } }
            .map { it.lowercase() }

        assertTrue(
            stageMembers.none { it.contains("agent") || it.contains("option") || it.contains("post") },
            "the contract carries the configuration of the stage: $stageMembers",
        )
    }

    /**
     * The two routes of a declaration declare the same step.
     *
     * The contract's route and the historic one are two ways to write the
     * same declaration, and a script that names either has to get the same
     * plan: the same step, with the same key, with the same payload and in
     * the same place. Two routes that declared different steps would make the
     * choice of a route a change of behaviour, and the choice would be
     * invisible at the call site.
     */
    @Test
    @DisplayName("the two routes of a declaration declare the same step")
    fun theTwoRoutesOfADeclarationDeclareTheSameStep() {
        val contractRoute = pipeline {
            stages {
                stage("build") {
                    registryStep(stepKey = key, encodedInput = input)
                }
            }
        }

        val historicRoute = pipeline {
            stages {
                stage("build") {
                    registryStep(stepKey = key, encodedInput = input, schemaVersion = "dsl-v1")
                }
            }
        }

        assertEquals(
            historicRoute.stages.single().steps,
            contractRoute.stages.single().steps,
            "the two routes declare another step",
        )
        assertEquals(
            listOf(StepSpec.RegistryStepSpec(key, "dsl-v1", input)),
            contractRoute.stages.single().steps,
            "the contract's route declares another step",
        )
    }

    /**
     * The post conditions refuse a plugin's step, and the refusal says why.
     *
     * The conditions that close a stage run the steps the DSL provides. A
     * plugin's step there is refused, and the refusal NAMES the restriction
     * and the step it refused: a restriction that is only implicit is a
     * restriction the author has to guess, and a refusal that does not say
     * why is a refusal the author has to decode.
     */
    @Test
    @DisplayName("the post conditions refuse a plugin's step and the refusal says why")
    fun thePostConditionsRefuseAPluginsStepAndTheRefusalSaysWhy() {
        val scope = PostStepsScope()

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            scope.registryStep(stepKey = key, encodedInput = input)
        }
        assertTrue(
            refusal.message!!.contains(key.value),
            "the refusal does not name the step it refused: ${refusal.message}",
        )
        assertTrue(
            refusal.message!!.contains("post condition"),
            "the refusal does not name the restriction: ${refusal.message}",
        )

        val blockRefusal = assertThrows(IllegalArgumentException::class.java) {
            scope.registryBlock(stepKey = key, encodedInput = input) { echo("inside") }
        }
        assertTrue(
            blockRefusal.message!!.contains(key.value),
            "the refusal of a block does not name the step it refused: ${blockRefusal.message}",
        )
    }

    /**
     * A branch admits a plugin's step and a plugin's block.
     *
     * A branch is a body of steps of its own, and a plugin's step belongs in
     * a branch as much as it belongs in a stage. The body of the plugin's
     * block is written in the branch, and the steps it declares are the
     * branch's.
     */
    @Test
    @DisplayName("a branch admits a plugin's step and a plugin's block")
    fun aBranchAdjectsAPluginsStepAndAPluginsBlock() {
        val branch = BranchScope()
        branch.registryStep(stepKey = key, encodedInput = input)
        branch.registryBlock(stepKey = key, encodedInput = input) { echo("inside the block") }

        assertEquals(
            listOf(
                StepSpec.RegistryStepSpec(key, "dsl-v1", input),
                StepSpec.RegistryBlockSpec(key, "dsl-v1", input, listOf(StepSpec.Echo("inside the block"))),
            ),
            branch.steps,
            "the branch declares another step than the contract's route",
        )
    }

    /**
     * The nested blocks of a stage emit through the contract.
     *
     * `steps { }`, `script { }`, `retry { }` and `dir { }` open a scope of
     * the stage, and that scope is a stage: a plugin's step declared inside
     * one of them is declared in the stage that carries it, and the plan
     * says so.
     */
    @Test
    @DisplayName("the nested blocks of a stage emit through the contract")
    fun theNestedBlocksOfAStageEmitThroughTheContract() {
        val spec = pipeline {
            stages {
                stage("build") {
                    steps {
                        registryStep(stepKey = key, encodedInput = input)
                    }
                    script {
                        registryStep(stepKey = key, encodedInput = input)
                    }
                    retry(2) {
                        registryStep(stepKey = key, encodedInput = input)
                    }
                    dir("sub") {
                        registryStep(stepKey = key, encodedInput = input)
                    }
                }
            }
        }

        val steps = spec.stages.single().steps
        val declared = steps.flatMap { step ->
            when (step) {
                is StepSpec.WithEnv -> step.steps
                is StepSpec.Dir -> step.steps
                is StepSpec.RetryBlock -> step.steps
                else -> listOf(step)
            }
        }

        assertEquals(
            4,
            declared.count { it == StepSpec.RegistryStepSpec(key, "dsl-v1", input) },
            "the nested blocks did not all declare the plugin's step: $declared",
        )
    }

    /**
     * The post conditions keep their steps, and the contract does not change
     * them.
     *
     * The contract is a way to declare a step, and a way that changes what a
     * scope declares is a way that breaks the scopes that do not use it. The
     * steps of a post condition are the steps the DSL provides, and they are
     * the same ones they were.
     */
    @Test
    @DisplayName("the post conditions keep their steps and the contract does not change them")
    fun thePostConditionsKeepTheirStepsAndTheContractDoesNotChangeThem() {
        val spec = pipeline {
            stages {
                stage("build") {
                    post {
                        always {
                            echo("closing")
                            sh("echo done")
                        }
                    }
                }
            }
        }

        assertEquals(
            listOf(StepSpec.Echo("closing"), StepSpec.Shell("echo done")),
            spec.stages.single().post.conditions[PostCondition.ALWAYS],
            "the steps of a post condition are not the steps it declared",
        )
    }
}
