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
 * DSL for verifying step invocations in pipeline tests
 * Provides fluent API for asserting pipeline step behavior
 */
class StepVerificationDsl(private val recorder: StepInvocationRecorder) {
    
    /**
     * Verify that a step was called
     */
    fun stepWasCalled(stepName: String) {
        if (!recorder.containsMethod(stepName)) {
            fail("Expected step '$stepName' to be called, but it was not invoked")
        }
    }
    
    /**
     * Verify that a step was not called
     */
    fun stepWasNotCalled(stepName: String) {
        if (recorder.containsMethod(stepName)) {
            fail("Expected step '$stepName' to not be called, but it was invoked ${recorder.getCallCount(stepName)} times")
        }
    }
    
    /**
     * Verify step call count
     */
    fun stepCallCount(stepName: String, expectedCount: Int) {
        val actualCount = recorder.getCallCount(stepName)
        if (actualCount != expectedCount) {
            fail("Expected step '$stepName' to be called $expectedCount times, but was called $actualCount times")
        }
    }
    
    /**
     * Verify step was called with specific named arguments
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
     * Verify step was called with specific positional arguments
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
     * Get all invocations for a step (for custom verification)
     */
    fun getInvocations(stepName: String): List<StepInvocation<*>> {
        return recorder.getMethodInvocations(stepName)
    }
    
    /**
     * Verify step invocation order
     */
    fun stepsCalledInOrder(vararg stepNames: String) {
        val allInvocations = recorder.getInvocations()
        val actualOrder = mutableListOf<String>()
        
        // Build chronological order based on invocation recording
        allInvocations.forEach { (stepName, invocations) ->
            repeat(invocations.size) {
                actualOrder.add(stepName)
            }
        }
        
        var stepIndex = 0
        for (expectedStep in stepNames) {
            val foundIndex = actualOrder.indexOf(expectedStep)
            if (foundIndex == -1) {
                fail("Expected step '$expectedStep' was not found in execution order")
            }
            if (foundIndex < stepIndex) {
                fail("Step '$expectedStep' was called before expected position. Expected order: ${stepNames.toList()}, Actual order: $actualOrder")
            }
            stepIndex = foundIndex + 1
        }
    }
    
    /**
     * Verify that only specific steps were called
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
     * Advanced verification with custom matcher function
     */
    fun verifyStep(stepName: String, matcher: (List<StepInvocation<*>>) -> Boolean, errorMessage: String = "Step verification failed") {
        val invocations = recorder.getMethodInvocations(stepName)
        if (!matcher(invocations)) {
            fail("$errorMessage for step '$stepName'")
        }
    }
    
    /**
     * Verify that all steps passed (no exceptions thrown)
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
     * Builder pattern for complex step verification
     */
    fun verifyStepInvocation(stepName: String, block: StepInvocationVerificationBuilder.() -> Unit) {
        val builder = StepInvocationVerificationBuilder(stepName, recorder)
        block(builder)
        builder.verify()
    }
}

/**
 * Builder for complex step invocation verification
 */
class StepInvocationVerificationBuilder(
    private val stepName: String,
    private val recorder: StepInvocationRecorder
) {
    private val verifications = mutableListOf<(List<StepInvocation<*>>) -> Unit>()
    
    /**
     * Verify call count
     */
    fun callCount(expected: Int) {
        verifications.add { invocations ->
            if (invocations.size != expected) {
                fail("Expected $expected invocations of '$stepName', but got ${invocations.size}")
            }
        }
    }
    
    /**
     * Verify that step was called with wildcard support
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
     * Verify step result
     */
    fun resultMatches(predicate: (dev.rubentxu.pipeline.testing.MockResult) -> Boolean) {
        verifications.add { invocations ->
            if (invocations.any { !predicate(it.result) }) {
                fail("Step '$stepName' result did not match predicate")
            }
        }
    }
    
    /**
     * Execute all verifications
     */
    internal fun verify() {
        val invocations = recorder.getMethodInvocations(stepName)
        verifications.forEach { verification ->
            verification(invocations)
        }
    }
}