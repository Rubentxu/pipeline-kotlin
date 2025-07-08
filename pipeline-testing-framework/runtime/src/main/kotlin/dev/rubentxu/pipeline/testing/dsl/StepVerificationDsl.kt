package dev.rubentxu.pipeline.testing.dsl

import dev.rubentxu.pipeline.testing.mocks.StepInvocationRecorder
import dev.rubentxu.pipeline.testing.mocks.StepInvocation
import dev.rubentxu.pipeline.testing.mocks.NamedArgsStepInvocation
import dev.rubentxu.pipeline.testing.mocks.PositionalArgsStepInvocation
import dev.rubentxu.pipeline.testing.mocks.wildcard
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.assertions.fail

/**
 * DSL for verifying step invocations in pipeline tests.
 *
 * Provides a fluent API for making assertions about the behavior of pipeline steps.
 */
class StepVerificationDsl(private val recorder: StepInvocationRecorder) {
    
    /**
     * Verifies that a step was called.
     *
     * @param stepName Name of the step.
     */
    fun stepWasCalled(stepName: String) {
        if (!recorder.containsMethod(stepName)) {
            fail("Expected step '$stepName' to be called, but it was not invoked")
        }
    }
    
    /**
     * Verifies that a step was NOT called.
     *
     * @param stepName Name of the step.
     */
    fun stepWasNotCalled(stepName: String) {
        if (recorder.containsMethod(stepName)) {
            fail("Expected step '$stepName' to not be called, but it was invoked ${recorder.getCallCount(stepName)} times")
        }
    }
    
    /**
     * Verifies the number of calls to a step.
     *
     * @param stepName Name of the step.
     * @param expectedCount Expected number of calls.
     */
    fun stepCallCount(stepName: String, expectedCount: Int) {
        val actualCount = recorder.getCallCount(stepName)
        if (actualCount != expectedCount) {
            fail("Expected step '$stepName' to be called $expectedCount times, but was called $actualCount times")
        }
    }

    /**
     * Verifies that a step was called with specific named arguments.
     *
     * @param stepName Name of the step.
     * @param expectedArgs Expected arguments.
     */
    fun stepCalledWith(stepName: String, expectedArgs: Map<String, Any>) {
        val invocations = recorder.getMethodInvocations(stepName)
        if (invocations.isEmpty()) {
            fail("Expected step '$stepName' to be called with args $expectedArgs, but step was never called")
        }
        
        val matchingInvocation = invocations.find { invocation ->
            when (invocation) {
                is NamedArgsStepInvocation -> {
                    try {
                        invocation.verifyArgs(expectedArgs)
                        true
                    } catch (e: AssertionError) {
                        false
                    }
                }
                else -> false
            }
        }
        
        if (matchingInvocation == null) {
            val allInvocations = invocations.joinToString("\n") { "  ${it.args}" }
            fail("Expected step '$stepName' to be called with args $expectedArgs.\nActual invocations:\n$allInvocations")
        }
    }
    
    /**
     * Verifies that a step was called with specific positional arguments.
     *
     * @param stepName Name of the step.
     * @param expectedArgs Expected arguments.
     */
    fun stepCalledWith(stepName: String, vararg expectedArgs: Any?) {
        val expectedArgsList = expectedArgs.toList()
        val invocations = recorder.getMethodInvocations(stepName)
        if (invocations.isEmpty()) {
            fail("Expected step '$stepName' to be called with args $expectedArgsList, but step was never called")
        }
        
        val matchingInvocation = invocations.find { invocation ->
            when (invocation) {
                is PositionalArgsStepInvocation -> {
                    try {
                        invocation.verifyArgs(expectedArgsList)
                        true
                    } catch (e: AssertionError) {
                        false
                    }
                }
                else -> false
            }
        }
        
        if (matchingInvocation == null) {
            val allInvocations = invocations.joinToString("\n") { "  ${it.args}" }
            fail("Expected step '$stepName' to be called with args $expectedArgsList.\nActual invocations:\n$allInvocations")
        }
    }
    
    /**
     * Gets all invocations of a step (for custom verification).
     *
     * @param stepName Name of the step.
     * @return List of invocations.
     */
    fun getInvocations(stepName: String): List<StepInvocation<*>> {
        return recorder.getMethodInvocations(stepName)
    }
    
    /**
     * Verifies the order of step invocations.
     *
     * @param stepNames Names of the steps in the expected order.
     */
    fun stepsCalledInOrder(vararg stepNames: String) {
        val actualOrder = recorder.getExecutionOrder()
        val expectedOrder = stepNames.toList()
        
        // Check if the expected steps appear in the actual order in the same sequence
        var lastFoundIndex = -1
        for (expectedStep in expectedOrder) {
            val foundIndex = actualOrder.subList(lastFoundIndex + 1, actualOrder.size).indexOf(expectedStep)
            if (foundIndex == -1) {
                fail("Expected step '$expectedStep' was not found in execution order after position $lastFoundIndex. Expected order: $expectedOrder, Actual order: $actualOrder")
            }
            lastFoundIndex = lastFoundIndex + 1 + foundIndex
        }
    }
    
    /**
     * Verifies that only the specified steps were called.
     *
     * @param stepNames Names of the allowed steps.
     */
    fun onlyStepsCalled(vararg stepNames: String) {
        val expectedSteps = stepNames.toSet()
        val actualSteps = recorder.getInvocations().keys
        
        val unexpectedSteps = actualSteps - expectedSteps
        val missingSteps = expectedSteps - actualSteps
        
        if (unexpectedSteps.isNotEmpty()) {
            fail("Unexpected steps were called: $unexpectedSteps")
        }
        
        if (missingSteps.isNotEmpty()) {
            fail("Expected steps were not called: $missingSteps")
        }
    }
    
    /**
     * Advanced verification with a custom matcher function.
     *
     * @param stepName Name of the step.
     * @param matcher Matcher function.
     * @param errorMessage Custom error message.
     */
    fun verifyStep(stepName: String, matcher: (List<StepInvocation<*>>) -> Boolean, errorMessage: String = "Step verification failed") {
        val invocations = recorder.getMethodInvocations(stepName)
        if (!matcher(invocations)) {
            fail("$errorMessage for step '$stepName'")
        }
    }
    
    /**
     * Verifies that all steps succeeded (no exceptions).
     */
    fun allStepsSucceeded() {
        val allInvocations = recorder.getInvocations()
        allInvocations.forEach { (stepName, invocations) ->
            invocations.forEach { invocation ->
                if (invocation.result.exitCode != 0) {
                    fail("Step '$stepName' failed with exit code ${invocation.result.exitCode}")
                }
            }
        }
    }

    /**
     * Builder for complex step invocation verification.
     *
     * @param stepName Name of the step.
     * @param block Verification configuration block.
     */
    fun verifyStepInvocation(stepName: String, block: StepInvocationVerificationBuilder.() -> Unit) {
        val builder = StepInvocationVerificationBuilder(stepName, recorder)
        block(builder)
        builder.verify()
    }
}

/**
 * Builder for complex step invocation verification.
 *
 * Allows defining multiple verifications on a step's invocations.
 */
class StepInvocationVerificationBuilder(
    private val stepName: String,
    private val recorder: StepInvocationRecorder
) {
    private val verifications = mutableListOf<(List<StepInvocation<*>>) -> Unit>()
    
    /**
     * Verifies the number of calls to the step.
     *
     * @param expected Expected number of invocations.
     */
    fun callCount(expected: Int) {
        verifications.add { invocations ->
            if (invocations.size != expected) {
                fail("Expected $expected invocations of '$stepName', but got ${invocations.size}")
            }
        }
    }

    /**
     * Verifies that the step was called with arguments (supports wildcard).
     *
     * @param expectedArgs Expected arguments.
     */
    fun calledWith(expectedArgs: Map<String, Any>) {
        verifications.add { invocations ->
            val matchingInvocation = invocations.find { invocation ->
                when (invocation) {
                    is NamedArgsStepInvocation -> {
                        try {
                            invocation.verifyArgs(expectedArgs)
                            true
                        } catch (e: AssertionError) {
                            false
                        }
                    }
                    else -> false
                }
            }
            if (matchingInvocation == null) {
                fail("Step '$stepName' was not called with expected args: $expectedArgs")
            }
        }
    }

    /**
     * Verifies the result of the step with a predicate.
     *
     * @param predicate Function that validates the result.
     */
    fun resultMatches(predicate: (dev.rubentxu.pipeline.testing.MockResult) -> Boolean) {
        verifications.add { invocations ->
            if (invocations.any { !predicate(it.result) }) {
                fail("Step '$stepName' result did not match predicate")
            }
        }
    }

    /**
     * Executes all configured verifications.
     */
    internal fun verify() {
        val invocations = recorder.getMethodInvocations(stepName)
        verifications.forEach { verification ->
            verification(invocations)
        }
    }
}