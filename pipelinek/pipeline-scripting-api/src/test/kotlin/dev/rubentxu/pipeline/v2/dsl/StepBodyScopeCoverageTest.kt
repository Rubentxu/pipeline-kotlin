package dev.rubentxu.pipeline.v2.dsl

import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout

/**
 * The contract of the receiver of a body of steps, measured against the
 * surface of the stage that hosts it.
 *
 * ## The guarantee under trial
 *
 * - every OPERATION the stage declares, the contract of a body declares too:
 *   a body that cannot call an operation is a body that cannot express the
 *   pipeline;
 * - the contract declares NOTHING the stage does not declare: the contract is
 *   a subset of what the stage offers, not a second vocabulary;
 * - the contract declares NOTHING of the CONFIGURATION of the stage: no
 *   options, no post conditions, no directives, no gate, and not the
 *   declarative environment;
 * - the contract declares the WRAPPERS and the steps of a PLUGIN, which are
 *   the two things a body of steps is made of;
 * - the stage scope is NOT a body of steps, so the pair of declarations that
 *   would share their erasure cannot exist.
 *
 * ## How the two surfaces are COMPARED
 *
 * By NAME, and not by the JVM descriptor: a declaration that carries a value
 * by default is published by the JVM as SEVERAL methods — `publishHTML` has
 * six — and the JVM cannot tell a bridge of `@JvmOverloads` from a second
 * declaration. The name is what an author writes, and a name that is in one
 * surface and not in the other is the thing that breaks a pipeline.
 *
 * The EXPECTATION is a list, and a list that stops being true makes this file
 * RED: a member that appears in the stage and not in the contract, or a member
 * of the contract that the stage does not declare, is a difference the row
 * names. The list cannot go stale in silence.
 *
 * ## What this file does NOT freeze
 *
 * The shape of the `StepSpec` family, the vocabulary of the scopes and the
 * order in which the steps run. This file reads the DECLARED surface, and it
 * reads it from the class, so a member added later shows up here without
 * anybody having to remember this file.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StepBodyScopeCoverageTest {

    /**
     * The CONFIGURATION of the stage: the declarations that belong to the
     * stage and not to a body.
     *
     * They are the declarations the product REFUSES inside a block that
     * carries steps, because a declaration that is dropped in silence is a
     * gate that does not gate. The contract of a body does not carry them,
     * and this file says so by name.
     */
    private val configurationOfTheStage = setOf(
        "directives",
        "options",
        "post",
        "whenEnvIs",
        "whenEnvPresent",
        "whenGate",
    )

    /**
     * The BLOCKS the stage opens: the scopes of their own.
     *
     * A `steps { }` block, a `script { }` block and a `parallel { }` block
     * open a scope, and a body of steps is not a place to open one: the body
     * of a block is written in the contract of a body, and the block itself
     * is written in the stage.
     */
    private val blocksOfTheStage = setOf(
        "parallel",
        "script",
        "steps",
    )

    /**
     * The BUILDER of the stage: the operation that turns the stage into the
     * specification the plan carries.
     *
     * It is not an operation of a body: a body declares steps, and the stage
     * that carries them is what becomes a specification.
     */
    private val builderOfTheStage = setOf(
        "toStageBuilder",
    )

    /**
     * Every member the stage declares and the contract of a body does not.
     *
     * The three groups above, and nothing else. A member outside them is an
     * OPERATION the body cannot use, and the row that reads this list says
     * so.
     */
    private val membersOfTheStageOnly = configurationOfTheStage + blocksOfTheStage + builderOfTheStage

    /**
     * Every operation the contract declares and the stage does not.
     *
     * The contract is a SUBSET of the stage: it declares the operations a
     * body may use, and nothing else. An operation that the contract declares
     * and the stage does not is a vocabulary of its own, and a pipeline that
     * used it would not run against the stage.
     */
    @Test
    @DisplayName("the contract declares every operation the stage scope declares")
    fun theContractDeclaresEveryOperationTheStageScopeDeclares() {
        val stage = memberNames(StageScope::class.java)
        val contract = memberNames(StepBodyScope::class.java)

        assertEquals(
            membersOfTheStageOnly,
            stage - contract,
            "the stage declares a member that the contract of a body does not declare: " +
                "an operation a body cannot use, or a declaration of the stage that " +
                "the contract should not carry",
        )
    }

    /**
     * The contract declares nothing the stage does not declare.
     *
     * The contract is a SUBSET of the surface of the stage: it declares the
     * operations a body may use, and every one of them is an operation the
     * stage offers. A member of the contract that the stage does not declare
     * would be a vocabulary of its own, and a pipeline that used it would
     * declare a step that nothing knows how to run.
     */
    @Test
    @DisplayName("the contract declares nothing the stage scope does not declare")
    fun theContractDeclaresNothingTheStageScopeDoesNotDeclare() {
        val stage = memberNames(StageScope::class.java)
        val contract = memberNames(StepBodyScope::class.java)

        assertEquals(
            emptySet<String>(),
            contract - stage,
            "the contract of a body declares a member the stage does not declare",
        )
    }

    /**
     * The contract declares nothing of the configuration of the stage.
     *
     * The configuration of a stage is the agent, the options, the post
     * conditions, the directives and the gate. A body of steps has no
     * authority over any of them: a declaration written there is DROPPED in
     * silence, and a gate that is dropped is a gate that does not gate. The
     * contract does not carry them, so the script that writes one does not
     * compile.
     *
     * The declarative environment is the member that needs a row of its own:
     * its NAME is in the contract, because the wrapper that binds a
     * credential to a variable has that name too. What the contract carries
     * is the wrapper, and the row below says which of the two it is.
     */
    @Test
    @DisplayName("the contract declares nothing of the configuration of the stage")
    fun theContractDeclaresNothingOfTheConfigurationOfTheStage() {
        val contract = memberNames(StepBodyScope::class.java)

        assertTrue(
            contract.none { it in configurationOfTheStage },
            "the contract of a body declares the configuration of the stage: " +
                "${contract.intersect(configurationOfTheStage)}",
        )
    }

    /**
     * The contract carries the WRAPPER of a credential and not the
     * declarative environment of the stage.
     *
     * Two members of two surfaces share the name `environment`: the
     * declarative one, which belongs to the stage and names the variables the
     * stage runs with, and the wrapper that binds a credential to a variable
     * inside a body. The contract carries the WRAPPER, and it carries it
     * alone: a contract that carried the declarative one would let a body
     * declare the environment of a stage, which is the configuration this
     * contract exists to keep out.
     */
    @Test
    @DisplayName("the contract carries the wrapper of a credential and not the declarative environment")
    fun theContractCarriesTheWrapperOfACredentialAndNotTheDeclarativeEnvironment() {
        val environments = publicMethods(StepBodyScope::class.java).filter { it.name == "environment" }

        assertEquals(
            1,
            environments.size,
            "the contract declares the environment more than once: " +
                "${environments.map { it.parameterTypes.toList() }}",
        )
        assertEquals(
            listOf(String::class.java, String::class.java, Function1::class.java),
            environments.single().parameterTypes.toList(),
            "the environment of the contract is not the wrapper that binds a credential to a variable",
        )
    }

    /**
     * The contract declares the wrappers and the steps of a plugin.
     *
     * Those are the two things a body of steps is made of: the blocks that
     * carry steps of their own, and the steps a plugin publishes. A contract
     * that declared the operations and none of the wrappers would make a
     * body unable to say `withEnv`, and a contract that declared no plugin's
     * step would make a body unable to say what a plugin publishes.
     */
    @Test
    @DisplayName("the contract declares the wrappers and the steps of a plugin")
    fun theContractDeclaresTheWrappersAndTheStepsOfAPlugin() {
        val expected = setOf(
            "catchError",
            "dir",
            "input",
            "lock",
            "registryBlock",
            "registryStep",
            "retry",
            "slot",
            "timeout",
            "timestamps",
            "waitUntil",
            "warnError",
            "withCredentials",
            "withEnv",
        )
        val contract = memberNames(StepBodyScope::class.java)

        assertTrue(
            expected.all { it in contract },
            "the contract of a body does not declare: ${expected - contract}",
        )
    }

    /**
     * The stage scope is not a body of steps.
     *
     * A lambda erases its receiver, so two declarations that differ only in
     * the type of their receiver share their JVM descriptor, and the
     * compiler refuses the pair. The pair is refused because it cannot be
     * written: the stage scope does not implement the contract of a body, and
     * the contract of a body declares no `steps` of its own. The two
     * surfaces are therefore DISJOINT, and the pair cannot exist.
     */
    @Test
    @DisplayName("the stage scope is not a body of steps, so the pair of erasures cannot exist")
    fun theStageScopeIsNotABodyOfStepsSoThePairOfErasuresCannotExist() {
        assertFalse(
            StepBodyScope::class.java.isAssignableFrom(StageScope::class.java),
            "the stage scope is a body of steps, and the two declarations that would " +
                "share their erasure could be written",
        )

        val stepsOfTheStage = publicMethods(StageScope::class.java).filter { it.name == "steps" }
        assertEquals(
            1,
            stepsOfTheStage.count { it.parameterCount == 1 },
            "the stage declares the block of its steps more than once with a body: " +
                "${stepsOfTheStage.map { it.parameterTypes.toList() }}",
        )
        assertTrue(
            memberNames(StepBodyScope::class.java).none { it == "steps" },
            "the contract of a body declares a block of steps, and the stage declares one too",
        )
    }

    /**
     * The object a body receives is a stage scope AND a body of steps.
     *
     * The contract is a TYPE, and the object the runtime hands to a body is
     * the stage itself: it carries the members with their values by default
     * and the synthetics that carry them, and a consumer compiled against
     * the old class keeps linking and keeps running. The narrowing is in the
     * type the author writes, not in the object the runtime builds.
     */
    @Test
    @DisplayName("the object a body receives is a stage scope and a body of steps")
    fun theObjectABodyReceivesIsAStageScopeAndABodyOfSteps() {
        val body = StageScope("stage").blockScope("steps")

        assertTrue(
            StageScope::class.java.isAssignableFrom(body.javaClass),
            "the object a body receives is not a stage scope",
        )
        assertTrue(
            StepBodyScope::class.java.isAssignableFrom(body.javaClass),
            "the object a body receives is not a body of steps",
        )
    }

    /**
     * The refusal of the configuration of the stage still happens.
     *
     * The contract is ERGONOMICS: it makes the invalid form hard to write.
     * It does not REPLACE the validation that refuses it, and this row says
     * so: the declaration of the stage written inside a body is still
     * refused where it is written, with the diagnostic that names it.
     */
    @Test
    @DisplayName("the refusal of the configuration of the stage still happens inside a body")
    fun theRefusalOfTheConfigurationOfTheStageStillHappensInsideABody() {
        val body = StageScope("stage").blockScope("steps")

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            (body as StageScope).whenEnvIs("APPROVED", "true")
        }

        assertTrue(
            refusal.message?.contains("`whenEnvIs`") == true,
            "the refusal does not name the declaration that is refused: the message was " +
                "'${refusal.message}'",
        )
        assertTrue(
            refusal.message?.contains("`steps { }`") == true,
            "the refusal does not name the block where it was written: the message was " +
                "'${refusal.message}'",
        )
    }

    /**
     * The refusal of the CONFIGURATION of the stage happens for every
     * declaration of it.
     *
     * The compiler refuses the configuration of the stage written inside a
     * body, and the refusal of the PLAN refuses it too. The second one is the
     * real defence: it does not need a compilation, and a plan that arrives
     * from anywhere is refused by it. This row says so for every declaration
     * the configuration holds, and it says it without compiling anything.
     */
    @TestFactory
    fun `the refusal of the configuration of the stage happens for every declaration of it`():
        List<DynamicTest> {
        val declarations = listOf(
            "directives" to { scope: StageScope -> scope.directives { } },
            "environment" to { scope: StageScope -> scope.environment { } },
            "options" to { scope: StageScope -> scope.options { } },
            "post" to { scope: StageScope -> scope.post { } },
            "whenEnvIs" to { scope: StageScope -> scope.whenEnvIs("A", "B") },
            "whenEnvPresent" to { scope: StageScope -> scope.whenEnvPresent("A") },
            "whenGate" to { scope: StageScope ->
                scope.whenGate(dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate.AlwaysTrue)
            },
        )

        return declarations.map { (name, declaration) ->
            DynamicTest.dynamicTest(name) {
                val body = StageScope("stage").blockScope("steps")

                val refusal = assertThrows(IllegalArgumentException::class.java) {
                    declaration(body as StageScope)
                }

                assertTrue(
                    refusal.message?.contains("`$name`") == true,
                    "the refusal does not name the declaration that is refused: the message was " +
                        "'${refusal.message}'",
                )
            }
        }
    }

    /**
     * The PUBLIC methods of a type, without the ones the compiler adds.
     *
     * The synthetic `$default` bridges carry a value by default, and they are
     * not declarations anybody writes: they are excluded, and so are the
     * members of `Any`.
     */
    private fun publicMethods(type: Class<*>): List<Method> =
        type.methods
            .filter { !it.isSynthetic && !it.name.contains('$') && it.declaringClass != Any::class.java }
            .toList()

    /**
     * The NAMES of the public methods of a type.
     *
     * The name of a member that takes a value class carries the suffix the
     * compiler adds (`git-XXXX`); the name an author writes is the one before
     * the suffix.
     */
    private fun memberNames(type: Class<*>): Set<String> =
        publicMethods(type).map { it.name.substringBefore('-') }.toSet()
}
